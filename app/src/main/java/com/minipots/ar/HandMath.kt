package com.minipots.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
 */
class PinchDetector(private val closeBelow: Float = 0.30f, private val openAbove: Float = 0.45f) {
    var pinched = false
        private set
    private var smoothed = -1f

    fun reset() { pinched = false; smoothed = -1f }

    fun update(ratio: Float): Boolean {
        smoothed = if (smoothed < 0f) ratio else smoothed + (ratio - smoothed) * 0.65f
        pinched = if (pinched) smoothed < openAbove else smoothed < closeBelow
        return pinched
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
 * Plans how an analysis frame is turned into the image given to the hand tracker.
 *
 * The tracker receives the part of the frame that is visible on screen plus a margin (so a hand
 * that is partly off screen keeps tracking), already upright. The same plan converts the
 * landmarks it returns to normalized view coordinates.
 */
class AnalysisMapping {
    /** Region of the buffer that is copied, in buffer pixels. */
    var regionLeft = 0; private set
    var regionTop = 0; private set
    var regionRight = 0; private set
    var regionBottom = 0; private set
    /** Size of the upright image given to the tracker. */
    var outputWidth = 0; private set
    var outputHeight = 0; private set
    /** True when the region is drawn smaller than its size in the buffer. */
    var downscaled = false; private set
    /** Affine buffer pixel -> output pixel, in android.graphics.Matrix.setValues order. */
    val bitmapMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // view = origin + ex * nx + ey * ny, for landmarks (nx, ny) normalized to the output image.
    private var viewX0 = 0f; private var viewXdx = 1f; private var viewXdy = 0f
    private var viewY0 = 0f; private var viewYdx = 0f; private var viewYdy = 1f

    private var keyW = -1; private var keyH = -1; private var keyL = -1; private var keyT = -1
    private var keyR = -1; private var keyB = -1; private var keyRot = -1; private var keyMirror = false

    /** Returns true when the plan changed (bitmaps must be recreated). */
    fun update(
        bufferWidth: Int, bufferHeight: Int,
        cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int,
        rotation: Int, mirror: Boolean, margin: Float, maxSide: Int,
    ): Boolean {
        if (bufferWidth == keyW && bufferHeight == keyH && cropLeft == keyL && cropTop == keyT &&
            cropRight == keyR && cropBottom == keyB && rotation == keyRot && mirror == keyMirror) return false
        keyW = bufferWidth; keyH = bufferHeight; keyL = cropLeft; keyT = cropTop
        keyR = cropRight; keyB = cropBottom; keyRot = rotation; keyMirror = mirror

        var cl = cropLeft.coerceIn(0, bufferWidth); var cr = cropRight.coerceIn(0, bufferWidth)
        var ct = cropTop.coerceIn(0, bufferHeight); var cb = cropBottom.coerceIn(0, bufferHeight)
        if (cr - cl < 2 || cb - ct < 2) { cl = 0; ct = 0; cr = bufferWidth; cb = bufferHeight }
        val mx = ((cr - cl) * margin).roundToInt(); val my = ((cb - ct) * margin).roundToInt()
        regionLeft = max(0, cl - mx); regionRight = min(bufferWidth, cr + mx)
        regionTop = max(0, ct - my); regionBottom = min(bufferHeight, cb + my)
        val rw = (regionRight - regionLeft).toFloat(); val rh = (regionBottom - regionTop).toFloat()

        val scale = min(1f, maxSide / max(rw, rh))
        downscaled = scale < 0.999f
        val swap = rotation == 90 || rotation == 270
        outputWidth = max(1, ((if (swap) rh else rw) * scale).roundToInt())
        outputHeight = max(1, ((if (swap) rw else rh) * scale).roundToInt())

        // Buffer pixel -> output pixel, from the images of the three region corners.
        val ox = outX(0f, 0f); val oy = outY(0f, 0f)
        val sx = (outX(1f, 0f) - ox) / rw; val kx = (outX(0f, 1f) - ox) / rh
        val ky = (outY(1f, 0f) - oy) / rw; val sy = (outY(0f, 1f) - oy) / rh
        bitmapMatrix[0] = sx; bitmapMatrix[1] = kx; bitmapMatrix[2] = ox - sx * regionLeft - kx * regionTop
        bitmapMatrix[3] = ky; bitmapMatrix[4] = sy; bitmapMatrix[5] = oy - ky * regionLeft - sy * regionTop
        bitmapMatrix[6] = 0f; bitmapMatrix[7] = 0f; bitmapMatrix[8] = 1f

        // Output-normalized landmark -> normalized view position, from three probe points.
        val cw = (cr - cl).toFloat(); val ch = (cb - ct).toFloat()
        viewX0 = probeX(0f, 0f, cl, ct, cw, ch, mirror); viewY0 = probeY(0f, 0f, cl, ct, cw, ch)
        viewXdx = probeX(1f, 0f, cl, ct, cw, ch, mirror) - viewX0; viewYdx = probeY(1f, 0f, cl, ct, cw, ch) - viewY0
        viewXdy = probeX(0f, 1f, cl, ct, cw, ch, mirror) - viewX0; viewYdy = probeY(0f, 1f, cl, ct, cw, ch) - viewY0
        return true
    }

    /** Normalized view x (0 = left edge of the screen, 1 = right edge) of a tracker landmark. */
    fun viewX(nx: Float, ny: Float): Float = viewX0 + viewXdx * nx + viewXdy * ny

    /** Normalized view y (0 = top edge of the screen, 1 = bottom edge) of a tracker landmark. */
    fun viewY(nx: Float, ny: Float): Float = viewY0 + viewYdx * nx + viewYdy * ny

    private fun outX(p: Float, q: Float) = FrameMapping.uprightA(keyRot, p, q) * outputWidth
    private fun outY(p: Float, q: Float) = FrameMapping.uprightB(keyRot, p, q) * outputHeight

    private fun cropP(a: Float, b: Float, cl: Int, cw: Float) =
        (regionLeft + FrameMapping.bufferP(keyRot, a, b) * (regionRight - regionLeft) - cl) / cw

    private fun cropQ(a: Float, b: Float, ct: Int, ch: Float) =
        (regionTop + FrameMapping.bufferQ(keyRot, a, b) * (regionBottom - regionTop) - ct) / ch

    private fun probeX(a: Float, b: Float, cl: Int, ct: Int, cw: Float, ch: Float, mirror: Boolean): Float {
        val x = FrameMapping.uprightA(keyRot, cropP(a, b, cl, cw), cropQ(a, b, ct, ch))
        return if (mirror) 1f - x else x
    }

    private fun probeY(a: Float, b: Float, cl: Int, ct: Int, cw: Float, ch: Float): Float =
        FrameMapping.uprightB(keyRot, cropP(a, b, cl, cw), cropQ(a, b, ct, ch))
}
