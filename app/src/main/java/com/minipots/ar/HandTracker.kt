package com.minipots.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Process
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import kotlin.math.max
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
 *    describe an old image. No frame skipping, no resolution switching, no prediction.
 *  - Nothing is allocated per frame except the small result objects MediaPipe returns.
 */
class HandTracker(context: Context, private val onError: (String) -> Unit) : ImageAnalysis.Analyzer {
    val state = HandState()

    /** True for the front camera: the view is mirrored like a mirror, so landmarks are too. */
    @Volatile var mirror = false
    /** Width / height of the view, used to measure distances on screen. */
    @Volatile var viewAspect = 0.5f
    /** Smoothed cost of one tracked frame (copy + inference) in milliseconds. */
    @Volatile var frameCostMs = 0f
    /** Smoothed time between two results in milliseconds. */
    @Volatile var intervalMs = 0f

    private val appContext = context.applicationContext
    private var landmarker: HandLandmarker? = null
    private var failed = false
    private var closed = false
    private var errors = 0
    private var prioritySet = false

    private val mapping = AnalysisMapping()
    private var source: Bitmap? = null
    private var input: Bitmap? = null
    private var canvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private var tightRows: ByteBuffer? = null

    private val raw = FloatArray(HAND_POINTS * 2)
    private val filtered = FloatArray(HAND_POINTS * 2)
    private val filters = Array(HAND_POINTS * 2) { OneEuroFilter(MIN_CUTOFF_HZ, BETA, DERIVATIVE_CUTOFF_HZ) }
    private val pinch = PinchDetector()
    private var tracking = false
    private var lastTimestampMs = 0L
    private var lastSampleNs = 0L
    private var lastResultNs = 0L

    override fun analyze(image: ImageProxy) {
        var returned = false
        try {
            if (failed || closed) return
            if (!prioritySet) {
                // Slightly below the render thread: when the CPU is contended the frame rate wins and
                // tracking takes what is left. The inference threads MediaPipe starts inherit this.
                prioritySet = true
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2 * Process.THREAD_PRIORITY_LESS_FAVORABLE) } catch (_: Exception) {}
            }
            val startNs = System.nanoTime()
            val frame = prepareInput(image)
            // The pixels are copied: give the buffer back to the camera before the slow part.
            image.close(); returned = true
            val firstFrame = landmarker == null
            val tracker = landmarker ?: createLandmarker().also { landmarker = it }
            val timestampMs = max(lastTimestampMs + 1, SystemClock.uptimeMillis())
            lastTimestampMs = timestampMs
            val result = tracker.detectForVideo(BitmapImageBuilder(frame).build(), timestampMs)
            val nowNs = System.nanoTime()
            publish(result, nowNs)
            if (!firstFrame) {
                // The first frame also loads the model; it says nothing about the running cost.
                val cost = (nowNs - startNs) / 1e6f
                frameCostMs = if (frameCostMs == 0f) cost else frameCostMs + (cost - frameCostMs) * 0.15f
                if (lastResultNs != 0L) {
                    val gap = (nowNs - lastResultNs) / 1e6f
                    intervalMs = if (intervalMs == 0f) gap else intervalMs + (gap - intervalMs) * 0.15f
                }
                lastResultNs = nowNs
            }
            errors = 0
        } catch (e: Throwable) {
            // Without a tracker nothing can work; a bad frame is only fatal if it keeps happening.
            if (landmarker == null || ++errors >= MAX_CONSECUTIVE_ERRORS) {
                failed = true
                onError("Rastreamento de mão indisponível: ${e.message ?: e.javaClass.simpleName}")
            }
            state.publish(null, false, System.nanoTime())
        } finally {
            if (!returned) image.close()
        }
    }

    /** Must run on the analysis thread (after the camera is unbound). */
    fun close() {
        closed = true
        try { landmarker?.close() } catch (_: Exception) {}
        landmarker = null
        source?.recycle(); source = null
        input?.recycle(); input = null; canvas = null
    }

    private fun createLandmarker(): HandLandmarker {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    // CPU (XNNPACK) on purpose: the GPU is left entirely to rendering, so tracking
                    // cannot lower the frame rate, and weak GPUs are slower at this model anyway.
                    .setDelegate(Delegate.CPU)
                    .build()
            )
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

    /** Copies the visible part of the camera frame, upright, into the reusable input bitmap. */
    private fun prepareInput(image: ImageProxy): Bitmap {
        val width = image.width; val height = image.height
        val crop = image.cropRect
        val changed = mapping.update(
            width, height, crop.left, crop.top, crop.right, crop.bottom,
            image.imageInfo.rotationDegrees, mirror, OFFSCREEN_MARGIN, MAX_INPUT_SIDE,
        )
        var src = source
        if (src == null || src.width != width || src.height != height) {
            src?.recycle()
            src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); source = src
        }
        var dst = input
        if (changed || dst == null || dst.width != mapping.outputWidth || dst.height != mapping.outputHeight) {
            dst?.recycle()
            dst = Bitmap.createBitmap(mapping.outputWidth, mapping.outputHeight, Bitmap.Config.ARGB_8888); input = dst
            canvas = Canvas(dst)
            matrix.setValues(mapping.bitmapMatrix)
            paint.isFilterBitmap = mapping.downscaled
        }

        val plane = image.planes[0]
        val pixels = plane.buffer
        val rowBytes = width * 4
        if (plane.rowStride == rowBytes) {
            pixels.rewind()
            src.copyPixelsFromBuffer(pixels)
        } else {
            // Rows are padded on this device: pack them first.
            val tight = tightRows?.takeIf { it.capacity() == rowBytes * height }
                ?: ByteBuffer.allocateDirect(rowBytes * height).also { tightRows = it }
            tight.clear()
            for (y in 0 until height) {
                pixels.limit(y * plane.rowStride + rowBytes)
                pixels.position(y * plane.rowStride)
                tight.put(pixels)
            }
            tight.rewind()
            src.copyPixelsFromBuffer(tight)
        }
        canvas!!.drawBitmap(src, matrix, paint)
        return dst
    }

    private fun publish(result: HandLandmarkerResult, nowNs: Long) {
        val hands = result.landmarks()
        val worlds = result.worldLandmarks()
        if (hands.isEmpty() || hands[0].size < HAND_POINTS) {
            if (tracking) { tracking = false; pinch.reset(); for (f in filters) f.reset() }
            lastSampleNs = nowNs
            state.publish(null, false, nowNs)
            return
        }
        val hand = hands[0]
        for (i in 0 until HAND_POINTS) {
            val p = hand[i]; val x = p.x(); val y = p.y()
            raw[i * 2] = mapping.viewX(x, y); raw[i * 2 + 1] = mapping.viewY(x, y)
        }
        val dt = if (tracking) (nowNs - lastSampleNs) / 1e9f else 0f
        lastSampleNs = nowNs; tracking = true

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
        /** Share of the visible area kept around it so a hand leaving the screen keeps tracking. */
        const val OFFSCREEN_MARGIN = 0.15f
        /** The tracker crops the hand to 224 px itself; more input than this adds cost, not accuracy. */
        const val MAX_INPUT_SIDE = 640
        const val MIN_CUTOFF_HZ = 1.2f
        const val BETA = 12f
        const val DERIVATIVE_CUTOFF_HZ = 1f
    }
}
