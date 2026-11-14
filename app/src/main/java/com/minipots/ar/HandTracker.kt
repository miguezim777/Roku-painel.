package com.minipots.ar

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One reading of the hand, copied between the tracking thread and the render thread. */
class HandSample {
    /** x, y pairs in normalized view coordinates (0..1, y down). May lie slightly outside 0..1. */
    val points = FloatArray(HAND_POINTS * 2)
    /** x, y, z triples in camera space (x right, y up, z towards the viewer), in metres, around the middle of the hand. */
    val shape = FloatArray(HAND_POINTS * 3)
    var present = false
    var pinch = false
    /** The hand is closed into a fist. */
    var closed = false
    /** Distance from the camera to the hand in metres, estimated from its size in the picture. */
    var depth = 0f
    var sequence = 0
    var timeNs = 0L
}

/** Latest hand reading. Writer and reader only hold the lock while copying 42 floats. */
class HandState {
    private val latest = HandSample()

    fun publish(points: FloatArray?, pinch: Boolean, timeNs: Long, shape: FloatArray? = null, closed: Boolean = false, depth: Float = 0f) {
        synchronized(latest) {
            if (points != null) System.arraycopy(points, 0, latest.points, 0, latest.points.size)
            if (shape != null) System.arraycopy(shape, 0, latest.shape, 0, latest.shape.size)
            latest.present = points != null
            latest.pinch = points != null && pinch
            latest.closed = points != null && closed
            if (points != null) latest.depth = depth
            latest.timeNs = timeNs
            latest.sequence++
        }
    }

    fun read(out: HandSample) {
        synchronized(latest) {
            if (out.sequence != latest.sequence) {
                System.arraycopy(latest.points, 0, out.points, 0, out.points.size)
                System.arraycopy(latest.shape, 0, out.shape, 0, out.shape.size)
                out.present = latest.present; out.pinch = latest.pinch
                out.closed = latest.closed; out.depth = latest.depth
                out.timeNs = latest.timeNs; out.sequence = latest.sequence
            }
        }
    }
}

/**
 * MediaPipe Hand Landmarker fed by CameraX.
 *
 * Design rules, in order:
 *  - Rendering never waits for tracking. This runs on its own thread and only publishes results.
 *  - Tracking always works on the newest camera frame. Inference is synchronous on this thread and
 *    CameraX keeps only the latest frame meanwhile, so frames are never queued and results never
 *    describe an old image. While a hand is tracked every frame is used: no frame skipping, no
 *    resolution switching, no prediction.
 *  - The camera buffer goes to MediaPipe as it is. Rotation is a parameter of the crop MediaPipe
 *    makes anyway, so no pixel is touched here: no bitmap, no rotated copy.
 *  - With no hand in view the search for one runs at a reduced rate, so an idle phone stays cool
 *    and keeps its full speed for when the hand comes back.
 *  - Nothing is allocated per frame except small wrapper and result objects.
 *
 * With [hands] = 2 both hands are followed and published to [frames], each in its own slot. Looking
 * for a second hand is the expensive part of tracking (it runs the palm detector on the whole
 * picture), and MediaPipe does it on every frame for as long as fewer hands are followed than it
 * was asked for. So on the CPU two trackers are kept: one that follows a single hand, which is
 * used while one hand is in view, and one for two hands, which is used while two are in view and
 * otherwise only a few times a second, to see whether the other hand has come back.
 */
class HandTracker(
    context: Context,
    private val listener: Listener,
    private val hands: Int = 1,
    private val modelAsset: String = MODEL_ASSET,
) : ImageAnalysis.Analyzer {
    /** Called on the tracking thread. */
    interface Listener {
        /** Tracking cannot work at all. */
        fun trackerError(message: String)
        /** The GPU was asked for and could not be used; tracking continues on the CPU. */
        fun gpuFailed()
        /** The GPU was asked for and has produced its first results. */
        fun gpuReady()
    }

    val state = HandState()
    /** Both hands, when the tracker was made for two. */
    val frames = HandsExchange()
    /** True once the lighter model is the one in use. */
    @Volatile var lightModel = false
        private set
    private var chosenModel: String? = null

    /** Focal length of the camera in units of the width of its buffer (the long side). */
    @Volatile var focal = DEFAULT_FOCAL
    /** Smoothed time between two camera frames that reached the tracker, in milliseconds. */
    @Volatile var captureIntervalMs = 0f
        private set
    /** Smoothed age of a camera frame when tracking starts on it, in milliseconds; -1 when the camera's clock is unknown. */
    @Volatile var frameAgeMs = -1f
        private set
    /** Smoothed time the networks take for one frame while a hand is followed, in milliseconds. */
    @Volatile var inferenceMs = 0f
        private set
    /** Hands followed in the latest frame. */
    @Volatile var handsInView = 0
        private set

    /** True for the front camera: the view is mirrored like a mirror, so landmarks are too. */
    @Volatile var mirror = false
    /** Width / height of the view, used to measure distances on screen. */
    @Volatile var viewAspect = 0.5f
    /** Smoothed cost of one tracked frame in milliseconds. */
    @Volatile var frameCostMs = 0f
    /** Smoothed time between two results while a hand is followed, in milliseconds. */
    @Volatile var intervalMs = 0f
    /** Asks for the networks to run on the GPU. Falls back to the CPU by itself if that fails. */
    @Volatile var preferGpu = false
    /** Where the networks run now. */
    @Volatile var usingGpu = false
        private set
    /** Frames in which a hand was followed since the tracker was last (re)started. */
    @Volatile var trackedFrames = 0
        private set
    /** Mean cost of those frames in milliseconds. This is what CPU and GPU are compared by. */
    @Volatile var trackedCostMs = 0f
        private set

    private val appContext = context.applicationContext
    private var landmarker: HandLandmarker? = null
    /** Follows one hand only; see the class comment. Null on the GPU and when one hand is all that is wanted. */
    private var single: HandLandmarker? = null
    private var pairMode = false
    private var lastPairNs = 0L
    private var lastProbeNs = 0L
    private var lastArrivalNs = 0L
    private val slots = HandSlots()
    private val frame = HandsFrame()
    private val slotOf = IntArray(2)
    private val palmX = FloatArray(2)
    private val palmY = FloatArray(2)
    private val labelRight = BooleanArray(2)
    private var failed = false
    private var closed = false
    private var errors = 0
    private var prioritySet = false
    private var gpuFrames = 0
    private var warmUp = 0

    private val mapping = BufferMapping()
    private var tightRows: ByteBuffer? = null
    private var options: ImageProcessingOptions? = null
    private var optionsRotation = -1

    private val raw = FloatArray(HAND_POINTS * 2)
    private val filtered = FloatArray(HAND_POINTS * 2)
    private val filters = Array(HAND_POINTS * 2) { OneEuroFilter(MIN_CUTOFF_HZ, BETA, DERIVATIVE_CUTOFF_HZ) }
    private val pinch = PinchDetector()
    // The hand in 3D, for games that draw it as a solid hand.
    private val shapeRaw = FloatArray(HAND_POINTS * 3)
    private val shape = FloatArray(HAND_POINTS * 3)
    private val shapeFilters = Array(HAND_POINTS * 3) { OneEuroFilter(1f, 8f, 1.5f) }
    private val depthFilter = OneEuroFilter(.8f, 5f, 1.5f)
    private val fist = PinchDetector(HandShape.CLOSED_BELOW, HandShape.OPEN_ABOVE)
    private var depth = 0f
    private var uprightRotation = 0
    private var tracking = false
    private var lastTimestampMs = 0L
    private var lastSampleNs = 0L
    private var lastFrameNs = 0L
    private var lastResultNs = 0L
    private var lastRunNs = 0L
    private var lastHandNs = 0L

    /**
     * Loads the model before the first camera frame arrives, so tracking starts with the camera.
     * Must run on the analysis thread.
     */
    fun prepare() {
        if (failed || closed) return
        try {
            raisePriority()
            ensureLandmarker()
        } catch (e: Throwable) {
            fail(e)
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (failed || closed) return
            raisePriority()
            val startNs = System.nanoTime()
            if (lastArrivalNs != 0L) {
                val gap = (startNs - lastArrivalNs) / 1e6f
                if (gap < 1000f) captureIntervalMs = if (captureIntervalMs == 0f) gap else captureIntervalMs + (gap - captureIntervalMs) * .1f
            }
            lastArrivalNs = startNs
            if (!tracking && startNs - lastHandNs > IDLE_AFTER_NS && startNs - lastRunNs < IDLE_INTERVAL_NS) return
            lastRunNs = startNs
            val created = ensureLandmarker()
            val tracker = chooseLandmarker(startNs) ?: return

            val width = image.width; val height = image.height
            val rotation = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            mapping.update(width, height, crop.left, crop.top, crop.right, crop.bottom, rotation, mirror)
            uprightRotation = rotation
            val frameNs = image.imageInfo.timestamp
            val timestampMs = max(lastTimestampMs + 1, SystemClock.uptimeMillis())
            lastTimestampMs = timestampMs
            // How old the picture is when work starts on it. Cameras stamp their frames on one of
            // two clocks; the one that gives a plausible age is the one in use.
            var age = (SystemClock.elapsedRealtimeNanos() - frameNs) / 1e6f
            if (age < 0f || age > 500f) age = (startNs - frameNs) / 1e6f
            frameAgeMs = if (age < 0f || age > 500f) -1f else if (frameAgeMs < 0f) age else frameAgeMs + (age - frameAgeMs) * .1f
            val picture = ByteBufferImageBuilder(frameBuffer(image), width, height, MPImage.IMAGE_FORMAT_RGBA).build()
            val inferenceStartNs = System.nanoTime()
            val result = tracker.detectForVideo(picture, processingOptions(rotation), timestampMs)

            val nowNs = System.nanoTime()
            val wasTracking = tracking
            if (hands >= 2) publishBoth(result, startNs, nowNs, tracker, width, height, rotation) else publish(result, nowNs, frameNs)
            if (!created) {
                if (tracking && wasTracking) {
                    val spent = (nowNs - inferenceStartNs) / 1e6f
                    inferenceMs = if (inferenceMs == 0f) spent else inferenceMs + (spent - inferenceMs) * .15f
                }
                // A frame that also loaded the model says nothing about the running cost.
                val cost = (nowNs - startNs) / 1e6f
                frameCostMs = if (frameCostMs == 0f) cost else frameCostMs + (cost - frameCostMs) * 0.15f
                // The rate that matters is the one while a hand is followed, not the idle search.
                if (tracking && wasTracking && lastResultNs != 0L) {
                    val gap = (nowNs - lastResultNs) / 1e6f
                    intervalMs = if (intervalMs == 0f) gap else intervalMs + (gap - intervalMs) * 0.15f
                    // The first frames after a start still compile and warm things up.
                    if (++warmUp > WARM_UP_FRAMES) {
                        val n = trackedFrames + 1
                        trackedCostMs += (cost - trackedCostMs) / min(n, MEAN_WINDOW)
                        trackedFrames = n
                    }
                }
            }
            lastResultNs = nowNs
            errors = 0
            if (usingGpu && gpuFrames < GPU_READY_FRAMES && ++gpuFrames == GPU_READY_FRAMES) listener.gpuReady()
        } catch (e: Throwable) {
            loseHand()
            state.publish(null, false, System.nanoTime())
            if (hands >= 2) { frame.hands[0].present = false; frame.hands[1].present = false; frame.timeNs = System.nanoTime(); frames.publish(frame); handsInView = 0 }
            if (usingGpu && landmarker != null) {
                // The GPU path is optional: give it one more frame, then go back to the CPU.
                if (++errors >= 2) {
                    errors = 0; preferGpu = false; closeLandmarker()
                    listener.gpuFailed()
                }
            } else if (landmarker == null || ++errors >= MAX_CONSECUTIVE_ERRORS) {
                // Without a tracker nothing can work; a bad frame is only fatal if it keeps happening.
                fail(e)
            }
        } finally {
            image.close()
        }
    }

    /** Must run on the analysis thread (after the camera is unbound). */
    fun close() {
        closed = true
        closeLandmarker()
    }

    private fun fail(e: Throwable) {
        failed = true
        listener.trackerError("Rastreamento de mão indisponível: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * Just below the render thread and above everything else in the app: inference gets the fast
     * cores, and when the CPU is contended the frame rate still wins. The inference threads
     * MediaPipe starts are created from this thread and inherit its priority.
     */
    private fun raisePriority() {
        if (prioritySet) return
        prioritySet = true
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Exception) {}
    }

    private fun closeLandmarker() {
        try { landmarker?.close() } catch (_: Exception) {}
        try { single?.close() } catch (_: Exception) {}
        landmarker = null; single = null; usingGpu = false
        pairMode = false; inferenceMs = 0f
        // Whatever was measured belongs to the tracker that is gone.
        trackedFrames = 0; trackedCostMs = 0f; warmUp = 0; gpuFrames = 0
        frameCostMs = 0f; intervalMs = 0f; lastResultNs = 0L
    }

    /** Makes sure a tracker of the requested kind exists. Returns true when one was created now. */
    private fun ensureLandmarker(): Boolean {
        val gpu = preferGpu
        if (landmarker != null && gpu == usingGpu) return false
        closeLandmarker()
        loseHand()
        if (gpu) {
            try {
                landmarker = createLandmarker(Delegate.GPU, hands); usingGpu = true
            } catch (e: Throwable) {
                preferGpu = false
                listener.gpuFailed()
            }
        }
        if (landmarker == null) {
            landmarker = createLandmarker(Delegate.CPU, hands)
            if (hands >= 2) single = createLandmarker(Delegate.CPU, 1)
        }
        return true
    }

    /**
     * The tracker for this frame: the one for a single hand while one hand or none is in view,
     * with a look for the second hand every [PROBE_NS]; the one for two hands while both are.
     */
    private fun chooseLandmarker(nowNs: Long): HandLandmarker? {
        val one = single ?: return landmarker
        if (pairMode) return landmarker
        if (tracking && nowNs - lastProbeNs >= PROBE_NS) { lastProbeNs = nowNs; return landmarker }
        return one
    }

    /** The model asked for, or the full one when the APK was built without the lighter one. */
    private fun modelInUse(): String {
        chosenModel?.let { return it }
        val found = modelAsset == MODEL_ASSET || try { appContext.assets.list("")?.contains(modelAsset) == true } catch (_: Exception) { false }
        val model = if (found) modelAsset else MODEL_ASSET
        chosenModel = model
        lightModel = model == LIGHT_MODEL_ASSET
        return model
    }

    private fun createLandmarker(delegate: Delegate, count: Int): HandLandmarker {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            // Starts on the CPU, where tracking cannot take frame rate from drawing. The GPU is
            // used only after it was measured to be better on this phone (ProcessorChooser), or
            // when the user asks for it.
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelInUse()).setDelegate(delegate).build())
            // VIDEO mode keeps MediaPipe's own tracking between frames (palm detection only runs
            // when the hand is lost) and returns the result synchronously.
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(count)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(appContext, options)
    }

    /** The clockwise rotation that makes the buffer upright, as MediaPipe wants to be told. */
    private fun processingOptions(rotation: Int): ImageProcessingOptions {
        val current = options
        if (current != null && optionsRotation == rotation) return current
        val created = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
        options = created; optionsRotation = rotation
        return created
    }

    /**
     * The RGBA pixels of [image] as MediaPipe accepts them: a direct buffer of exactly
     * width * height * 4 bytes. Normally that is the camera buffer itself, without a copy.
     */
    private fun frameBuffer(image: ImageProxy): ByteBuffer {
        val plane = image.planes[0]
        val pixels = plane.buffer
        val rowBytes = image.width * 4
        val size = rowBytes * image.height
        if (pixels.isDirect && plane.rowStride == rowBytes && pixels.capacity() >= size) {
            if (pixels.capacity() == size) return pixels
            pixels.position(0); pixels.limit(size)
            return pixels.slice()
        }
        // Rows are padded on this device: pack them.
        val tight = tightRows?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).also { tightRows = it }
        tight.clear()
        val capacity = pixels.capacity()
        for (y in 0 until image.height) {
            val start = y * plane.rowStride
            pixels.limit(min(capacity, start + rowBytes))
            pixels.position(min(capacity, start))
            tight.put(pixels)
        }
        tight.rewind()
        return tight
    }

    private fun loseHand() {
        if (!tracking) return
        tracking = false; pinch.reset(); fist.reset()
        for (f in filters) f.reset()
        for (f in shapeFilters) f.reset()
        depthFilter.reset()
    }

    /**
     * Both hands for [frames]: each in its slot, in the upright camera picture and along the
     * camera axes. Nothing is smoothed here; [HandRig] does that where the hand is drawn.
     */
    private fun publishBoth(
        result: HandLandmarkerResult, arrivalNs: Long, nowNs: Long, used: HandLandmarker, width: Int, height: Int, rotation: Int,
    ) {
        val found = result.landmarks()
        val worlds = result.worldLandmarks()
        val labels = result.handedness()
        var count = 0
        val source = slotOf // reused below; first holds which result each usable hand is
        val index0: Int; val index1: Int
        var first = -1; var second = -1
        for (k in found.indices) {
            if (found[k].size < HAND_POINTS || k >= worlds.size || worlds[k].size < HAND_POINTS) continue
            if (count == 0) first = k else if (count == 1) second = k
            if (++count == 2) break
        }
        index0 = first; index1 = second
        val mirrored = mirror
        for (n in 0 until count) {
            val k = if (n == 0) index0 else index1
            var x = 0f; var y = 0f
            for (i in HandGeometry.PALM) {
                val p = found[k][i]
                var a = FrameMapping.uprightA(rotation, p.x(), p.y())
                if (mirrored) a = 1f - a
                x += a; y += FrameMapping.uprightB(rotation, p.x(), p.y())
            }
            palmX[n] = x / HandGeometry.PALM.size; palmY[n] = y / HandGeometry.PALM.size
            labelRight[n] = k < labels.size && labels[k].isNotEmpty() && labels[k][0].categoryName() == "Right"
        }
        slots.assign(count, palmX, palmY, labelRight, arrivalNs, source)
        frame.hands[0].present = false; frame.hands[1].present = false
        for (n in 0 until count) {
            val k = if (n == 0) index0 else index1
            val out = frame.hands[source[n]]
            out.present = true
            // The label is right for a palm that faces the way this cross product points; a
            // mirrored picture turns a right hand into a left one.
            out.palmSign = if (labelRight[n] != mirrored) 1f else -1f
            for (i in 0 until HAND_POINTS) {
                val p = found[k][i]
                var a = FrameMapping.uprightA(rotation, p.x(), p.y())
                if (mirrored) a = 1f - a
                out.image[i * 2] = a; out.image[i * 2 + 1] = FrameMapping.uprightB(rotation, p.x(), p.y())
                val w = worlds[k][i]
                HandShape.toCameraSpace(rotation, mirrored, w.x(), w.y(), w.z(), out.world, i * 3)
            }
        }
        // The upright picture is as wide as the buffer is, or as high when it is turned.
        val uprightWidth = if (rotation == 90 || rotation == 270) height else width
        val uprightHeight = if (rotation == 90 || rotation == 270) width else height
        frame.aspect = uprightWidth.toFloat() / uprightHeight
        frame.focal = focal * width / uprightWidth
        frame.timeNs = arrivalNs
        frames.publish(frame)
        handsInView = count

        if (count > 0) { lastHandNs = nowNs; tracking = true } else { tracking = false; slots.reset() }
        // Two hands in view: stay with the tracker for two. One of them gone for a while: back to
        // the cheap one.
        if (single != null) {
            if (count >= 2 && used === landmarker) { pairMode = true; lastPairNs = nowNs }
            else if (pairMode && nowNs - lastPairNs > PAIR_HOLD_NS) { pairMode = false; lastProbeNs = nowNs }
        }
    }

    private fun publish(result: HandLandmarkerResult, nowNs: Long, frameNs: Long) {
        val hands = result.landmarks()
        val worlds = result.worldLandmarks()
        if (hands.isEmpty() || hands[0].size < HAND_POINTS) {
            loseHand()
            state.publish(null, false, nowNs)
            return
        }
        // Landmarks come normalized to the buffer as the sensor delivers it.
        val hand = hands[0]
        for (i in 0 until HAND_POINTS) {
            val p = hand[i]; val x = p.x(); val y = p.y()
            raw[i * 2] = mapping.viewX(x, y); raw[i * 2 + 1] = mapping.viewY(x, y)
        }
        // Time between the two camera exposures, which is what the motion happened in. The time at
        // which results come out also contains the varying cost of inference.
        var dt = 0f
        if (tracking) {
            dt = (frameNs - lastFrameNs) / 1e9f
            if (dt <= 0f || dt > 0.5f) dt = (nowNs - lastSampleNs) / 1e9f
        }
        lastFrameNs = frameNs; lastSampleNs = nowNs; lastHandNs = nowNs; tracking = true

        // Speeds are measured in hand sizes per second, so the filter behaves the same whether the
        // hand is near or far.
        val aspect = viewAspect
        val handSize = max(0.03f, max(screenDistance(0, 9, aspect), screenDistance(5, 17, aspect)))
        val scaleX = aspect / handSize; val scaleY = 1f / handSize
        for (i in 0 until HAND_POINTS) {
            filtered[i * 2] = filters[i * 2].filter(raw[i * 2], dt, scaleX)
            filtered[i * 2 + 1] = filters[i * 2 + 1].filter(raw[i * 2 + 1], dt, scaleY)
        }

        var pinched = pinch.pinched
        if (worlds.isNotEmpty() && worlds[0].size >= HAND_POINTS) {
            // Metric 3D landmarks: independent of distance and of how the hand is turned.
            val w = worlds[0]
            val thumb = w[4]; val index = w[8]; val wrist = w[0]; val knuckle = w[9]
            val gap = distance(thumb.x() - index.x(), thumb.y() - index.y(), thumb.z() - index.z())
            val size = distance(wrist.x() - knuckle.x(), wrist.y() - knuckle.y(), wrist.z() - knuckle.z())
            pinched = pinch.update(gap / max(size, 0.03f))

            // The same landmarks as a shape in camera space, steadied like the screen positions.
            val turned = uprightRotation; val mirrored = mirror
            for (i in 0 until HAND_POINTS) {
                val p = w[i]
                HandShape.toCameraSpace(turned, mirrored, p.x(), p.y(), p.z(), shapeRaw, i * 3)
            }
            for (i in shape.indices) shape[i] = shapeFilters[i].filter(shapeRaw[i], dt, SHAPE_SPEED_SCALE)
            fist.update(HandShape.closedRatio(shapeRaw))
            // How far the hand is: its real size across the picture against its size in the
            // picture. Depth inside the hand is left out on both sides, so a hand that points at
            // the camera is not taken for a distant one.
            var real = 0f; var seen = 0f
            for (i in 0 until DEPTH_PAIRS.size / 2) {
                val a = DEPTH_PAIRS[i * 2]; val b = DEPTH_PAIRS[i * 2 + 1]
                real += HandShape.flatDistance(shapeRaw, a, b); seen += screenDistance(a, b, aspect)
            }
            if (seen > 1e-3f && real > 1e-3f) {
                depth = depthFilter.filter((FOCAL_LENGTH * real / seen).coerceIn(MIN_DEPTH, MAX_DEPTH), dt, DEPTH_SPEED_SCALE)
            }
        }
        state.publish(filtered, pinched, nowNs, shape, fist.pinched, depth)
    }

    private fun screenDistance(a: Int, b: Int, aspect: Float): Float {
        val dx = (raw[a * 2] - raw[b * 2]) * aspect; val dy = raw[a * 2 + 1] - raw[b * 2 + 1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun distance(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    companion object {
        /** The lighter model: the same palm detector with the small landmark network. */
        const val LIGHT_MODEL_ASSET = "hand_landmarker_lite.task"
        /** Focal length of a typical phone camera in units of the long side of its picture. */
        const val DEFAULT_FOCAL = .77f
        private const val MODEL_ASSET = "hand_landmarker.task"
        /** While one hand is followed, the other one is looked for this often. */
        private const val PROBE_NS = 350_000_000L
        /** With two hands followed, one may be missing this long before the cheap tracker takes over. */
        private const val PAIR_HOLD_NS = 400_000_000L
        private const val MAX_CONSECUTIVE_ERRORS = 5
        private const val WARM_UP_FRAMES = 5
        private const val MEAN_WINDOW = 200
        private const val GPU_READY_FRAMES = 3
        /** After this long without a hand, look for one only every [IDLE_INTERVAL_NS]. */
        private const val IDLE_AFTER_NS = 1_500_000_000L
        private const val IDLE_INTERVAL_NS = 60_000_000L
        // One Euro filter, with speed in hand sizes per second. Chosen by simulation at 15 and 30
        // results per second: a still hand keeps 40% of its jitter, and a hand that starts moving
        // is followed within about 2% of its own size, at any speed.
        /** The 3D shape is in metres; the filter wants hand sizes (about 9 cm) per second. */
        private const val SHAPE_SPEED_SCALE = 11f
        private const val DEPTH_SPEED_SCALE = 5f
        /** Focal length of a phone camera in units of the long side of its picture. */
        private const val FOCAL_LENGTH = .77f
        private const val MIN_DEPTH = .15f
        private const val MAX_DEPTH = 1.0f
        /** Pairs of landmarks across the palm, whose distances give the size of the hand. */
        private val DEPTH_PAIRS = intArrayOf(0, 9, 5, 17, 0, 5, 0, 17)
        private const val MIN_CUTOFF_HZ = 1.2f
        private const val BETA = 20f
        private const val DERIVATIVE_CUTOFF_HZ = 2f
    }
}
