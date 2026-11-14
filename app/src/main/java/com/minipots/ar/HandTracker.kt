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

const val HAND_POINTS = 21

/** One reading of the hand, copied between the tracking thread and the render thread. */
class HandSample {
    /** x, y pairs in normalized view coordinates (0..1, y down). May lie slightly outside 0..1. */
    val points = FloatArray(HAND_POINTS * 2)
    var present = false
    var pinch = false
    var sequence = 0
    var timeNs = 0L
}

/** Latest hand reading. Writer and reader only hold the lock while copying 42 floats. */
class HandState {
    private val latest = HandSample()

    fun publish(points: FloatArray?, pinch: Boolean, timeNs: Long) {
        synchronized(latest) {
            if (points != null) System.arraycopy(points, 0, latest.points, 0, latest.points.size)
            latest.present = points != null
            latest.pinch = points != null && pinch
            latest.timeNs = timeNs
            latest.sequence++
        }
    }

    fun read(out: HandSample) {
        synchronized(latest) {
            if (out.sequence != latest.sequence) {
                System.arraycopy(latest.points, 0, out.points, 0, out.points.size)
                out.present = latest.present; out.pinch = latest.pinch
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
 */
class HandTracker(
    context: Context,
    private val onError: (String) -> Unit,
    private val onNotice: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    val state = HandState()

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

    private val appContext = context.applicationContext
    private var landmarker: HandLandmarker? = null
    private var failed = false
    private var closed = false
    private var errors = 0
    private var prioritySet = false

    private val mapping = BufferMapping()
    private var tightRows: ByteBuffer? = null
    private var options: ImageProcessingOptions? = null
    private var optionsRotation = -1

    private val raw = FloatArray(HAND_POINTS * 2)
    private val filtered = FloatArray(HAND_POINTS * 2)
    private val filters = Array(HAND_POINTS * 2) { OneEuroFilter(MIN_CUTOFF_HZ, BETA, DERIVATIVE_CUTOFF_HZ) }
    private val pinch = PinchDetector()
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
            if (!tracking && startNs - lastHandNs > IDLE_AFTER_NS && startNs - lastRunNs < IDLE_INTERVAL_NS) return
            lastRunNs = startNs
            val created = ensureLandmarker()
            val tracker = landmarker ?: return

            val width = image.width; val height = image.height
            val rotation = image.imageInfo.rotationDegrees
            val crop = image.cropRect
            mapping.update(width, height, crop.left, crop.top, crop.right, crop.bottom, rotation, mirror)
            val frameNs = image.imageInfo.timestamp
            val timestampMs = max(lastTimestampMs + 1, SystemClock.uptimeMillis())
            lastTimestampMs = timestampMs
            val frame = ByteBufferImageBuilder(frameBuffer(image), width, height, MPImage.IMAGE_FORMAT_RGBA).build()
            val result = tracker.detectForVideo(frame, processingOptions(rotation), timestampMs)

            val nowNs = System.nanoTime()
            val wasTracking = tracking
            publish(result, nowNs, frameNs)
            if (!created) {
                // A frame that also loaded the model says nothing about the running cost.
                val cost = (nowNs - startNs) / 1e6f
                frameCostMs = if (frameCostMs == 0f) cost else frameCostMs + (cost - frameCostMs) * 0.15f
                // The rate that matters is the one while a hand is followed, not the idle search.
                if (tracking && wasTracking && lastResultNs != 0L) {
                    val gap = (nowNs - lastResultNs) / 1e6f
                    intervalMs = if (intervalMs == 0f) gap else intervalMs + (gap - intervalMs) * 0.15f
                }
            }
            lastResultNs = nowNs
            errors = 0
        } catch (e: Throwable) {
            loseHand()
            state.publish(null, false, System.nanoTime())
            if (usingGpu && landmarker != null) {
                // The GPU path is optional: give it one more frame, then go back to the CPU.
                if (++errors >= 2) {
                    errors = 0; preferGpu = false; closeLandmarker()
                    onNotice("A GPU falhou neste aparelho. O rastreamento voltou para a CPU.")
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
        onError("Rastreamento de mão indisponível: ${e.message ?: e.javaClass.simpleName}")
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
        landmarker = null; usingGpu = false
    }

    /** Makes sure a tracker of the requested kind exists. Returns true when one was created now. */
    private fun ensureLandmarker(): Boolean {
        val gpu = preferGpu
        if (landmarker != null && gpu == usingGpu) return false
        closeLandmarker()
        loseHand()
        if (gpu) {
            try {
                landmarker = createLandmarker(Delegate.GPU); usingGpu = true
            } catch (e: Throwable) {
                preferGpu = false
                onNotice("GPU indisponível neste aparelho. O rastreamento continua na CPU.")
            }
        }
        if (landmarker == null) landmarker = createLandmarker(Delegate.CPU)
        frameCostMs = 0f; intervalMs = 0f; lastResultNs = 0L
        return true
    }

    private fun createLandmarker(delegate: Delegate): HandLandmarker {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            // CPU (XNNPACK) by default: the GPU is left entirely to rendering, so tracking cannot
            // lower the frame rate. The GPU is only used when the user asks for it.
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(delegate).build())
            // VIDEO mode keeps MediaPipe's own tracking between frames (palm detection only runs
            // when the hand is lost) and returns the result synchronously.
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
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
        tracking = false; pinch.reset()
        for (f in filters) f.reset()
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
        }
        state.publish(filtered, pinched, nowNs)
    }

    private fun screenDistance(a: Int, b: Int, aspect: Float): Float {
        val dx = (raw[a * 2] - raw[b * 2]) * aspect; val dy = raw[a * 2 + 1] - raw[b * 2 + 1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun distance(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    private companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        const val MAX_CONSECUTIVE_ERRORS = 5
        /** After this long without a hand, look for one only every [IDLE_INTERVAL_NS]. */
        const val IDLE_AFTER_NS = 1_500_000_000L
        const val IDLE_INTERVAL_NS = 60_000_000L
        // One Euro filter, with speed in hand sizes per second. Chosen by simulation at 15 and 30
        // results per second: a still hand keeps 40% of its jitter, and a hand that starts moving
        // is followed within about 2% of its own size, at any speed.
        const val MIN_CUTOFF_HZ = 1.2f
        const val BETA = 20f
        const val DERIVATIVE_CUTOFF_HZ = 2f
    }
}
