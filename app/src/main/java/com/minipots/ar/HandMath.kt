package com.minipots.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Pure Kotlin maths with no Android dependencies, so it is covered by JVM unit tests.
// Everything here works on preallocated arrays: nothing allocates per frame.

/** Column-major 4x4 matrix helpers (OpenGL layout). */
object Mat4 {
    fun identity(m: FloatArray) {
        m.fill(0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    fun copy(dst: FloatArray, src: FloatArray) { System.arraycopy(src, 0, dst, 0, 16) }

    /** out = a * b. [out] must not be the same array as [a] or [b]. */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0..3) {
            val b0 = b[c * 4]; val b1 = b[c * 4 + 1]; val b2 = b[c * 4 + 2]; val b3 = b[c * 4 + 3]
            for (r in 0..3) out[c * 4 + r] = a[r] * b0 + a[4 + r] * b1 + a[8 + r] * b2 + a[12 + r] * b3
        }
    }

    /** m = m * T(x, y, z) */
    fun translate(m: FloatArray, x: Float, y: Float, z: Float) {
        for (r in 0..3) m[12 + r] += m[r] * x + m[4 + r] * y + m[8 + r] * z
    }

    /** m = m * S(x, y, z) */
    fun scale(m: FloatArray, x: Float, y: Float, z: Float) {
        for (r in 0..3) { m[r] *= x; m[4 + r] *= y; m[8 + r] *= z }
    }

    /** m = m * Ry(degrees), right-handed rotation about +Y. */
    fun rotateY(m: FloatArray, degrees: Float) {
        val a = degrees * (PI.toFloat() / 180f); val c = cos(a); val s = sin(a)
        for (r in 0..3) {
            val x = m[r]; val z = m[8 + r]
            m[r] = x * c - z * s; m[8 + r] = x * s + z * c
        }
    }

    /** m = m * Rx(degrees), right-handed rotation about +X. */
    fun rotateX(m: FloatArray, degrees: Float) {
        val a = degrees * (PI.toFloat() / 180f); val c = cos(a); val s = sin(a)
        for (r in 0..3) {
            val y = m[4 + r]; val z = m[8 + r]
            m[4 + r] = y * c + z * s; m[8 + r] = z * c - y * s
        }
    }
}

/**
 * Fixed virtual camera looking down at the virtual table (plane y = 0).
 *
 * The horizontal field of view is constant, so the table always spans the same share of the
 * screen width whatever the aspect ratio of the phone.
 */
class SceneCamera(
    private val eyeX: Float, private val eyeY: Float, private val eyeZ: Float,
    targetX: Float, targetY: Float, targetZ: Float,
    horizontalFovDegrees: Float,
) {
    val viewProjection = FloatArray(16)
    private val tanHalfX = tan(horizontalFovDegrees * (PI.toFloat() / 360f))
    private var tanHalfY = tanHalfX
    // Orthonormal camera basis: right (s), up (u), forward (f).
    private val fx: Float; private val fy: Float; private val fz: Float
    private val sx: Float; private val sy: Float; private val sz: Float
    private val ux: Float; private val uy: Float; private val uz: Float

    init {
        var x = targetX - eyeX; var y = targetY - eyeY; var z = targetZ - eyeZ
        var l = sqrt(x * x + y * y + z * z); x /= l; y /= l; z /= l
        fx = x; fy = y; fz = z
        // s = f x worldUp(0, 1, 0)
        x = -fz; y = 0f; z = fx
        l = sqrt(x * x + z * z); x /= l; z /= l
        sx = x; sy = y; sz = z
        // u = s x f
        ux = sy * fz - sz * fy; uy = sz * fx - sx * fz; uz = sx * fy - sy * fx
        setAspect(0.5f)
    }

    /** [aspect] is width / height of the render surface. */
    fun setAspect(aspect: Float) {
        tanHalfY = tanHalfX / aspect
        val near = 0.05f; val far = 10f
        val m = viewProjection
        // viewProjection = P * V written out directly (rows of V are s, u, -f).
        val px = 1f / tanHalfX; val py = 1f / tanHalfY
        val a = (far + near) / (near - far); val b = 2f * far * near / (near - far)
        val tx = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        val ty = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        val tz = (fx * eyeX + fy * eyeY + fz * eyeZ)
        m[0] = px * sx; m[4] = px * sy; m[8] = px * sz; m[12] = px * tx
        m[1] = py * ux; m[5] = py * uy; m[9] = py * uz; m[13] = py * ty
        m[2] = -a * fx; m[6] = -a * fy; m[10] = -a * fz; m[14] = a * tz + b
        m[3] = fx; m[7] = fy; m[11] = fz; m[15] = -tz
    }

    /**
     * Projects a world point to normalized view coordinates (x right, y down, both 0..1) written to
     * [out]`[0..1]`. Returns false when the point is behind the camera.
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val m = viewProjection
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (w <= 1e-5f) return false
        val cx = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w
        val cy = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w
        out[0] = cx * 0.5f + 0.5f
        out[1] = 0.5f - cy * 0.5f
        return true
    }

    /**
     * Casts a ray through the normalized view point ([u], [v]) and intersects it with the horizontal
     * plane y = [planeY]. Writes the world x and z of the hit to [out]`[0..1]`; returns false when
     * the ray does not reach the plane in front of the camera.
     */
    fun rayToPlane(u: Float, v: Float, planeY: Float, out: FloatArray): Boolean {
        val nx = (u * 2f - 1f) * tanHalfX
        val ny = (1f - v * 2f) * tanHalfY
        val dx = fx + sx * nx + ux * ny
        val dy = fy + sy * nx + uy * ny
        val dz = fz + sz * nx + uz * ny
        if (dy > -1e-4f) return false
        val t = (planeY - eyeY) / dy
        if (t <= 0f) return false
        out[0] = eyeX + dx * t
        out[1] = eyeZ + dz * t
        return true
    }
}

/**
 * One Euro filter (Casiez et al.): an adaptive low-pass whose cutoff rises with speed. It removes
 * jitter while the hand is still and adds almost no lag while it moves, which is why it is used
 * here instead of a fixed smoothing window.
 */
class OneEuroFilter(private val minCutoff: Float, private val beta: Float, private val derivativeCutoff: Float) {
    private var initialized = false
    private var previousRaw = 0f
    private var value = 0f
    private var derivative = 0f

    fun reset() { initialized = false }

    /**
     * [dt] is the time since the previous sample in seconds. [speedScale] converts the units of
     * [raw] into the units the speed term is tuned for (here: hand sizes).
     */
    fun filter(raw: Float, dt: Float, speedScale: Float): Float {
        if (!initialized || dt <= 0f) {
            initialized = true; previousRaw = raw; value = raw; derivative = 0f
            return raw
        }
        val rawDerivative = (raw - previousRaw) * speedScale / dt
        previousRaw = raw
        derivative += (rawDerivative - derivative) * alpha(derivativeCutoff, dt)
        val cutoff = minCutoff + beta * abs(derivative)
        value += (raw - value) * alpha(cutoff, dt)
        return value
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}

/**
 * Pinch (thumb tip touching index tip) with hysteresis. [update] takes the tip distance divided
 * by the hand size, so the result does not depend on how far the hand is from the camera.
 *
 * A clear reading changes the state at once. A reading that only just crosses a threshold has to
 * be repeated on the next result, so tracking noise cannot make the pinch flicker.
 */
class PinchDetector(private val closeBelow: Float = 0.30f, private val openAbove: Float = 0.45f) {
    var pinched = false
        private set
    private var streak = 0

    fun reset() { pinched = false; streak = 0 }

    fun update(ratio: Float): Boolean {
        if (pinched) {
            streak = if (ratio > openAbove) streak + 1 else 0
            if (ratio > openAbove * CLEARLY_OPEN || streak >= CONFIRMATIONS) { pinched = false; streak = 0 }
        } else {
            streak = if (ratio < closeBelow) streak + 1 else 0
            if (ratio < closeBelow * CLEARLY_CLOSED || streak >= CONFIRMATIONS) { pinched = true; streak = 0 }
        }
        return pinched
    }

    private companion object {
        const val CLEARLY_CLOSED = 0.75f
        const val CLEARLY_OPEN = 1.3f
        const val CONFIRMATIONS = 2
    }
}

/**
 * Decides from measurements on this phone whether the tracking networks run on the CPU or on the
 * GPU. Which one is faster cannot be known in advance, and on a weak GPU the networks can also
 * take frame rate away from drawing, so both are measured with a hand in view and the GPU is kept
 * only when tracking gets clearly cheaper and the frame rate stays.
 *
 * [onSecond] is called once per second with the frames drawn in that second, the number of frames
 * tracked since the tracker was (re)started and their mean cost, where the tracker runs now, and
 * whether the GPU has been asked for. Seconds without a hand are ignored.
 */
class ProcessorChooser {
    enum class Action { NONE, TRY_GPU, KEEP_GPU, KEEP_CPU }

    var cpuCostMs = 0f
        private set
    var gpuCostMs = 0f
        private set
    var cpuFps = 0f
        private set
    var gpuFps = 0f
        private set
    val finished: Boolean get() = stage == DONE

    private var stage = ON_CPU
    private var lastFrames = 0
    private var fpsSum = 0
    private var seconds = 0
    private var requestSeen = false
    private var waited = 0

    fun onSecond(fps: Int, trackedFrames: Int, costMs: Float, usingGpu: Boolean, gpuRequested: Boolean): Action {
        when (stage) {
            ON_CPU -> {
                if (usingGpu || gpuRequested) { clear(); return Action.NONE }
                if (!measured(fps, trackedFrames)) return Action.NONE
                cpuCostMs = costMs; cpuFps = fpsSum.toFloat() / seconds
                clear()
                // Already as fast as the camera: nothing to gain, so nothing is risked.
                if (costMs < FAST_ENOUGH_MS) { stage = DONE; return Action.KEEP_CPU }
                stage = ON_GPU
                return Action.TRY_GPU
            }
            ON_GPU -> {
                if (!gpuRequested) {
                    // Withdrawn after being made: the GPU could not be used.
                    if (requestSeen || ++waited > REQUEST_GRACE_SECONDS) { stage = DONE; return Action.KEEP_CPU }
                    return Action.NONE
                }
                requestSeen = true
                if (!usingGpu) { clear(); return Action.NONE }
                if (!measured(fps, trackedFrames)) return Action.NONE
                gpuCostMs = costMs; gpuFps = fpsSum.toFloat() / seconds
                stage = DONE
                return if (gpuCostMs <= cpuCostMs * WORTH_IT && gpuFps >= cpuFps * FRAME_RATE_KEPT) Action.KEEP_GPU
                else Action.KEEP_CPU
            }
        }
        return Action.NONE
    }

    /** Counts this second if a hand was followed during it. True once there is enough to judge. */
    private fun measured(fps: Int, trackedFrames: Int): Boolean {
        if (trackedFrames < lastFrames) clear()
        val followed = trackedFrames > lastFrames
        lastFrames = trackedFrames
        if (!followed) return false
        fpsSum += fps; seconds++
        return trackedFrames >= MIN_FRAMES && seconds >= MIN_SECONDS
    }

    private fun clear() { lastFrames = 0; fpsSum = 0; seconds = 0 }

    companion object {
        /** Below this cost per frame the tracker already keeps up with a 30 fps camera. */
        const val FAST_ENOUGH_MS = 28f
        /** The GPU has to cut the cost to this share of the CPU's. */
        const val WORTH_IT = 0.85f
        /** ...and leave drawing at least this share of the frame rate it had. */
        const val FRAME_RATE_KEPT = 0.9f
        private const val ON_CPU = 0
        private const val ON_GPU = 1
        private const val DONE = 2
        private const val MIN_FRAMES = 20
        private const val MIN_SECONDS = 3
        private const val REQUEST_GRACE_SECONDS = 5
    }
}

/**
 * Relations between a camera buffer (as delivered by the sensor) and the upright image the user
 * sees. `rotation` is the clockwise rotation in degrees that makes the buffer upright, exactly as
 * reported by CameraX. (p, q) are fractions along the buffer x / y axes; (a, b) are fractions
 * along the upright x (right) / y (down) axes.
 */
object FrameMapping {
    fun bufferP(rotation: Int, a: Float, b: Float): Float = when (rotation) { 90 -> b; 180 -> 1f - a; 270 -> 1f - b; else -> a }
    fun bufferQ(rotation: Int, a: Float, b: Float): Float = when (rotation) { 90 -> 1f - a; 180 -> 1f - b; 270 -> a; else -> b }
    fun uprightA(rotation: Int, p: Float, q: Float): Float = when (rotation) { 90 -> 1f - q; 180 -> 1f - p; 270 -> q; else -> p }
    fun uprightB(rotation: Int, p: Float, q: Float): Float = when (rotation) { 90 -> p; 180 -> 1f - q; 270 -> 1f - p; else -> q }

    /**
     * Texture coordinates for a full-screen triangle strip (bottom-left, bottom-right, top-left,
     * top-right) that shows the crop rectangle of the camera buffer upright.
     *
     * [texRange] is (u0, u1, v0, v1): the part of the external texture that holds valid pixels,
     * taken from the SurfaceTexture transform. Its orientation is ignored on purpose; the rotation
     * comes from CameraX, which is defined for every device.
     */
    fun previewTexCoords(
        out: FloatArray, bufferWidth: Int, bufferHeight: Int,
        cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int,
        rotation: Int, mirror: Boolean, texRange: FloatArray,
    ) {
        for (i in 0..3) {
            var a = if (i % 2 == 0) 0f else 1f
            val b = if (i < 2) 1f else 0f
            if (mirror) a = 1f - a
            val x = cropLeft + bufferP(rotation, a, b) * (cropRight - cropLeft)
            val y = cropTop + bufferQ(rotation, a, b) * (cropBottom - cropTop)
            out[i * 2] = texRange[0] + x / bufferWidth * (texRange[1] - texRange[0])
            out[i * 2 + 1] = texRange[2] + y / bufferHeight * (texRange[3] - texRange[2])
        }
    }

    /** Valid texture range (u0, u1, v0, v1) covered by a SurfaceTexture transform matrix. */
    fun texRange(surfaceTextureMatrix: FloatArray, out: FloatArray) {
        val m = surfaceTextureMatrix
        var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
        for (i in 0..3) {
            val s = if (i % 2 == 0) 0f else 1f
            val t = if (i < 2) 0f else 1f
            val u = m[0] * s + m[4] * t + m[12]
            val v = m[1] * s + m[5] * t + m[13]
            u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
        }
        if (u1 - u0 < 1e-3f || v1 - v0 < 1e-3f) { u0 = 0f; u1 = 1f; v0 = 0f; v1 = 1f }
        out[0] = u0; out[1] = u1; out[2] = v0; out[3] = v1
    }
}

/**
 * Converts hand landmarks to screen positions.
 *
 * The tracker is given the camera buffer exactly as the sensor delivers it and returns landmarks
 * normalized to that buffer (p along its x axis, q along its y axis). This maps them to normalized
 * view coordinates, using the same crop rectangle and rotation the preview is drawn with.
 */
class BufferMapping {
    // view = origin + ex * p + ey * q
    private var viewX0 = 0f; private var viewXdp = 1f; private var viewXdq = 0f
    private var viewY0 = 0f; private var viewYdp = 0f; private var viewYdq = 1f

    private var keyW = -1; private var keyH = -1; private var keyL = -1; private var keyT = -1
    private var keyR = -1; private var keyB = -1; private var keyRot = -1; private var keyMirror = false

    fun update(
        bufferWidth: Int, bufferHeight: Int,
        cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int,
        rotation: Int, mirror: Boolean,
    ) {
        if (bufferWidth == keyW && bufferHeight == keyH && cropLeft == keyL && cropTop == keyT &&
            cropRight == keyR && cropBottom == keyB && rotation == keyRot && mirror == keyMirror) return
        keyW = bufferWidth; keyH = bufferHeight; keyL = cropLeft; keyT = cropTop
        keyR = cropRight; keyB = cropBottom; keyRot = rotation; keyMirror = mirror

        var cl = cropLeft.coerceIn(0, bufferWidth); var cr = cropRight.coerceIn(0, bufferWidth)
        var ct = cropTop.coerceIn(0, bufferHeight); var cb = cropBottom.coerceIn(0, bufferHeight)
        if (cr - cl < 2 || cb - ct < 2) { cl = 0; ct = 0; cr = bufferWidth; cb = bufferHeight }
        // Position inside the crop rectangle (0..1 on both axes) of the three buffer corners used
        // as probes; the mapping is affine, so three points define it.
        val p0 = -cl.toFloat() / (cr - cl); val p1 = (bufferWidth - cl).toFloat() / (cr - cl)
        val q0 = -ct.toFloat() / (cb - ct); val q1 = (bufferHeight - ct).toFloat() / (cb - ct)
        viewX0 = across(p0, q0); viewY0 = FrameMapping.uprightB(rotation, p0, q0)
        viewXdp = across(p1, q0) - viewX0; viewYdp = FrameMapping.uprightB(rotation, p1, q0) - viewY0
        viewXdq = across(p0, q1) - viewX0; viewYdq = FrameMapping.uprightB(rotation, p0, q1) - viewY0
    }

    /** Normalized view x (0 = left edge of the screen, 1 = right edge) of a tracker landmark. */
    fun viewX(p: Float, q: Float): Float = viewX0 + viewXdp * p + viewXdq * q

    /** Normalized view y (0 = top edge of the screen, 1 = bottom edge) of a tracker landmark. */
    fun viewY(p: Float, q: Float): Float = viewY0 + viewYdp * p + viewYdq * q

    private fun across(p: Float, q: Float): Float {
        val a = FrameMapping.uprightA(keyRot, p, q)
        return if (keyMirror) 1f - a else a
    }
}
