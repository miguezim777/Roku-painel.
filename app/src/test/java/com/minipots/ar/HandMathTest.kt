package com.minipots.ar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HandMathTest {
    private fun transform(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    // ------------------------------------------------------------ matrices

    @Test fun matrixOperationsApplyInWrittenOrder() {
        val m = FloatArray(16); Mat4.identity(m)
        Mat4.translate(m, 1f, 2f, 3f); Mat4.rotateY(m, 90f); Mat4.scale(m, 2f, 2f, 2f)
        // Scale first, then turn +X towards -Z, then move.
        assertArrayEquals(floatArrayOf(1f, 2f, 1f), transform(m, 1f, 0f, 0f), 1e-5f)
        assertArrayEquals(floatArrayOf(3f, 2f, 3f), transform(m, 0f, 0f, 1f), 1e-5f)

        Mat4.identity(m); Mat4.rotateX(m, 90f)
        // +Y turns towards +Z.
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), transform(m, 0f, 1f, 0f), 1e-5f)
    }

    @Test fun matrixProductMatchesSequentialTransforms() {
        val a = FloatArray(16); Mat4.identity(a); Mat4.translate(a, .3f, -.2f, .5f); Mat4.rotateY(a, 37f)
        val b = FloatArray(16); Mat4.identity(b); Mat4.rotateX(b, -110f); Mat4.scale(b, 1.5f, .5f, 2f)
        val ab = FloatArray(16); Mat4.mul(ab, a, b)
        val inner = transform(b, .1f, .2f, .3f)
        assertArrayEquals(transform(a, inner[0], inner[1], inner[2]), transform(ab, .1f, .2f, .3f), 1e-5f)
    }

    // ------------------------------------------------------------ scene camera

    private fun sceneCamera(aspect: Float) =
        SceneCamera(0f, .62f, .50f, 0f, .02f, -.06f, 34f).also { it.setAspect(aspect) }

    @Test fun tableProjectsWhereTheLayoutExpects() {
        val out = FloatArray(2)
        val tall = sceneCamera(9f / 20f)
        assertTrue(tall.project(Table.GRID_MIN_X, 0f, -.45f, out))
        assertEquals(.203f, out[0], .002f); assertEquals(.319f, out[1], .002f)
        assertTrue(tall.project(Table.GRID_MAX_X, 0f, Table.GRID_MAX_Z, out))
        assertEquals(1.008f, out[0], .002f); assertEquals(.749f, out[1], .002f)
        // The width covered does not depend on the shape of the screen.
        val wide = sceneCamera(9f / 16f)
        assertTrue(wide.project(Table.GRID_MIN_X, 0f, -.45f, out))
        assertEquals(.203f, out[0], .002f)
    }

    @Test fun everyPlaceAnObjectCanStandIsOnScreen() {
        val out = FloatArray(2)
        for (aspect in floatArrayOf(9f / 21f, 9f / 20f, 9f / 16f, 3f / 4f)) {
            val camera = sceneCamera(aspect)
            for (x in floatArrayOf(Table.MIN_X, Table.MAX_X)) for (z in floatArrayOf(Table.MIN_Z, Table.MAX_Z)) {
                assertTrue(camera.project(x, 0f, z, out))
                assertTrue("x=$x z=$z aspect=$aspect -> ${out[0]}, ${out[1]}", out[0] in .05f..0.95f && out[1] in .15f..0.95f)
            }
        }
    }

    @Test fun rayThroughAProjectedPointReturnsToIt() {
        val camera = sceneCamera(.5f)
        val screen = FloatArray(2); val hit = FloatArray(2)
        for (height in floatArrayOf(0f, .07f)) for (x in floatArrayOf(-.15f, 0f, .12f)) for (z in floatArrayOf(-.4f, -.1f, .15f)) {
            assertTrue(camera.project(x, height, z, screen))
            assertTrue(camera.rayToPlane(screen[0], screen[1], height, hit))
            assertEquals(x, hit[0], 1e-4f); assertEquals(z, hit[1], 1e-4f)
        }
    }

    @Test fun rayAboveTheHorizonMissesTheTable() {
        assertFalse(sceneCamera(.5f).rayToPlane(.5f, -3f, 0f, FloatArray(2)))
    }

    // ------------------------------------------------------------ one euro filter

    private fun filter() = OneEuroFilter(1.2f, 20f, 2f)

    /** Steady lag, in seconds, behind an input moving at [speed] units per second. */
    private fun lagSeconds(speed: Float): Float {
        val f = filter(); val dt = 1f / 30f
        var out = 0f; var x = 0f
        for (i in 0 until 300) { x = speed * i * dt; out = f.filter(x, dt, 1f) }
        return (x - out) / speed
    }

    @Test fun stillHandStaysExactlyWhereItIs() {
        val f = filter()
        for (i in 0 until 50) assertEquals(.4f, f.filter(.4f, 1f / 30f, 1f), 0f)
    }

    @Test fun fastMotionIsFollowedAlmostWithoutLag() {
        val fast = lagSeconds(3f); val slow = lagSeconds(.2f)
        assertTrue("fast lag $fast s", fast < .005f)
        assertTrue("slow lag $slow s", slow > .015f && slow < .060f)
    }

    @Test fun jitterOfAStillHandIsRemoved() {
        val f = filter()
        var low = Float.MAX_VALUE; var high = -Float.MAX_VALUE
        for (i in 0 until 200) {
            val out = f.filter(.5f + (if (i % 2 == 0) .002f else -.002f), 1f / 30f, 1f)
            if (i > 100) { low = min(low, out); high = max(high, out) }
        }
        assertTrue("left ${high - low} of 0.004", high - low < .004f * .3f)
    }

    @Test fun handThatStartsMovingIsFollowedClosely() {
        // At rest, then suddenly 6 hand sizes per second: the worst case for an adaptive filter.
        for (rate in intArrayOf(15, 30)) {
            val f = filter(); val dt = 1f / rate
            repeat(rate) { f.filter(0f, dt, 1f) }
            var x = 0f; var worst = 0f
            repeat(rate / 2) { x += 6f * dt; worst = max(worst, x - f.filter(x, dt, 1f)) }
            assertTrue("behind by $worst hand sizes at $rate Hz", worst < .03f)
        }
    }

    @Test fun filterRestartsAfterReset() {
        val f = filter()
        f.filter(.1f, 1f / 30f, 1f); f.filter(.2f, 1f / 30f, 1f)
        f.reset()
        assertEquals(.9f, f.filter(.9f, 1f / 30f, 1f), 0f)
    }

    // ------------------------------------------------------------ pinch

    @Test fun pinchHasHysteresis() {
        val p = PinchDetector()
        assertFalse(p.update(1f))
        repeat(5) { p.update(.2f) }
        assertTrue(p.pinched)
        // Between the two thresholds the state is kept, whichever it is.
        repeat(10) { p.update(.40f) }
        assertTrue(p.pinched)
        repeat(5) { p.update(.6f) }
        assertFalse(p.pinched)
        repeat(10) { p.update(.40f) }
        assertFalse(p.pinched)
    }

    @Test fun clearPinchRegistersOnTheFirstReading() {
        val p = PinchDetector()
        repeat(5) { p.update(.7f) }
        assertTrue(p.update(.15f))
        // A reading that only just crosses the threshold still has to be confirmed.
        val q = PinchDetector()
        repeat(5) { q.update(.7f) }
        assertFalse(q.update(.27f))
        assertTrue(q.update(.27f))
    }

    @Test fun openHandReleasesAtOnce() {
        val p = PinchDetector()
        p.update(.1f)
        assertFalse(p.update(.65f))
        // Only just past the threshold: released on the second reading.
        p.update(.1f)
        assertTrue(p.update(.5f))
        assertFalse(p.update(.5f))
    }

    @Test fun oneNoisyReadingDoesNotOpenAPinch() {
        val p = PinchDetector()
        repeat(5) { p.update(.15f) }
        p.update(.55f)
        assertTrue(p.pinched)
    }

    // ------------------------------------------------------------ camera frame mapping

    private val fullTexture = floatArrayOf(0f, 1f, 0f, 1f)

    @Test fun rotationHelpersAreInverses() {
        for (rotation in intArrayOf(0, 90, 180, 270)) for (a in floatArrayOf(0f, .25f, 1f)) for (b in floatArrayOf(0f, .6f, 1f)) {
            val p = FrameMapping.bufferP(rotation, a, b); val q = FrameMapping.bufferQ(rotation, a, b)
            assertEquals(a, FrameMapping.uprightA(rotation, p, q), 1e-6f)
            assertEquals(b, FrameMapping.uprightB(rotation, p, q), 1e-6f)
        }
    }

    @Test fun uprightImageIsTheBufferTurnedClockwise() {
        // Turning a picture 90 degrees clockwise brings its bottom-left corner to the top-left.
        assertEquals(0f, FrameMapping.uprightA(90, 0f, 1f), 0f); assertEquals(0f, FrameMapping.uprightB(90, 0f, 1f), 0f)
        // ...and its top-left corner to the top-right.
        assertEquals(1f, FrameMapping.uprightA(90, 0f, 0f), 0f); assertEquals(0f, FrameMapping.uprightB(90, 0f, 0f), 0f)
        // 270 degrees: the top-right corner comes to the top-left.
        assertEquals(0f, FrameMapping.uprightA(270, 1f, 0f), 0f); assertEquals(0f, FrameMapping.uprightB(270, 1f, 0f), 0f)
    }

    @Test fun previewOfBackCameraInPortrait() {
        // 1280x960 buffer, upright after 90 degrees clockwise, centre 576 rows visible.
        val out = FloatArray(8)
        FrameMapping.previewTexCoords(out, 1280, 960, 0, 192, 1280, 768, 90, false, fullTexture)
        // bottom-left, bottom-right, top-left, top-right
        assertArrayEquals(floatArrayOf(1f, .8f, 1f, .2f, 0f, .8f, 0f, .2f), out, 1e-6f)
    }

    @Test fun previewMirrorSwapsLeftAndRight() {
        val plain = FloatArray(8); val mirrored = FloatArray(8)
        FrameMapping.previewTexCoords(plain, 640, 480, 0, 60, 640, 420, 270, false, fullTexture)
        FrameMapping.previewTexCoords(mirrored, 640, 480, 0, 60, 640, 420, 270, true, fullTexture)
        assertArrayEquals(floatArrayOf(plain[2], plain[3], plain[0], plain[1], plain[6], plain[7], plain[4], plain[5]), mirrored, 1e-6f)
    }

    @Test fun previewUsesOnlyTheValidPartOfTheTexture() {
        // SurfaceTexture matrix of a 1920x1088 buffer holding 1080 valid rows, turned 90 degrees:
        // u = 1 - t, v = (1 - s) * 1080 / 1088.
        val k = 1080f / 1088f
        val matrix = floatArrayOf(0f, -k, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, k, 0f, 1f)
        val range = FloatArray(4); FrameMapping.texRange(matrix, range)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f, k), range, 1e-6f)
        val out = FloatArray(8)
        FrameMapping.previewTexCoords(out, 1920, 1080, 0, 0, 1920, 1080, 0, false, range)
        assertArrayEquals(floatArrayOf(0f, k, 1f, k, 0f, 0f, 1f, 0f), out, 1e-6f)
        // A matrix that carries no information yet falls back to the whole texture.
        FrameMapping.texRange(FloatArray(16), range)
        assertArrayEquals(fullTexture, range, 0f)
    }

    // ------------------------------------------------------------ tracker landmarks

    private fun mapping(rotation: Int, mirror: Boolean) =
        BufferMapping().also { it.update(640, 480, 0, 96, 640, 384, rotation, mirror) }

    @Test fun landmarksLandOnTheScreenWhereTheHandIsShown() {
        // Back camera in portrait: the buffer is upright after a quarter turn clockwise, and the
        // screen shows its rows 96..384.
        val m = mapping(90, false)
        assertEquals(.5f, m.viewX(.5f, .5f), 1e-5f); assertEquals(.5f, m.viewY(.5f, .5f), 1e-5f)
        // The left end of the buffer is the top of the screen; row 384 is the left screen edge.
        assertEquals(0f, m.viewX(0f, 384f / 480f), 1e-5f); assertEquals(0f, m.viewY(0f, 384f / 480f), 1e-5f)
        assertEquals(1f, m.viewX(1f, 96f / 480f), 1e-5f); assertEquals(1f, m.viewY(1f, 96f / 480f), 1e-5f)
        // Rows outside the crop are off screen, and still tracked.
        assertEquals(-96f / 288f, m.viewX(.3f, 1f), 1e-5f)
        assertEquals(1f + 96f / 288f, m.viewX(.3f, 0f), 1e-5f)
    }

    @Test fun frontCameraIsShownAsAMirror() {
        val plain = mapping(270, false); val mirrored = mapping(270, true)
        for (p in floatArrayOf(0f, .3f, 1f)) for (q in floatArrayOf(.1f, .5f, .9f)) {
            assertEquals(1f - plain.viewX(p, q), mirrored.viewX(p, q), 1e-5f)
            assertEquals(plain.viewY(p, q), mirrored.viewY(p, q), 1e-5f)
        }
    }

    @Test fun mappingFollowsTheCameraWhenItChanges() {
        val m = mapping(90, false)
        val before = m.viewX(.2f, .4f)
        m.update(640, 480, 0, 96, 640, 384, 270, true)
        assertEquals(before, m.viewX(1f - .2f, .4f), 1e-5f)
        // An empty crop rectangle means the whole buffer.
        m.update(640, 480, 0, 0, 0, 0, 0, false)
        assertEquals(.25f, m.viewX(.25f, .75f), 1e-5f); assertEquals(.75f, m.viewY(.25f, .75f), 1e-5f)
    }

    /**
     * The picture and the skeleton must agree: a point of the scene shown at some place on screen
     * by the preview has to be reported at that same place by the landmark mapping, for every
     * rotation and for both cameras.
     */
    @Test fun skeletonLinesUpWithThePreview() {
        val tex = FloatArray(8)
        for (rotation in intArrayOf(0, 90, 180, 270)) for (mirror in booleanArrayOf(false, true)) {
            // Preview: 1280x960, analysis: 640x480; the view port crops the same part of both.
            val swap = rotation % 180 != 0
            val pl = if (swap) 0 else 320; val pt = if (swap) 192 else 0
            val pr = 1280 - pl; val pb = 960 - pt
            FrameMapping.previewTexCoords(tex, 1280, 960, pl, pt, pr, pb, rotation, mirror, fullTexture)
            val m = BufferMapping()
            m.update(640, 480, pl / 2, pt / 2, pr / 2, pb / 2, rotation, mirror)
            for (u in floatArrayOf(.1f, .5f, .8f)) for (v in floatArrayOf(.2f, .5f, .9f)) {
                // Place in the camera buffer that the preview shows at view (u, v): bilinear in the
                // four corners. The tracker reports a hand there with these same two numbers.
                val p = (tex[4] * (1 - u) + tex[6] * u) * (1 - v) + (tex[0] * (1 - u) + tex[2] * u) * v
                val q = (tex[5] * (1 - u) + tex[7] * u) * (1 - v) + (tex[1] * (1 - u) + tex[3] * u) * v
                val label = "rotation=$rotation mirror=$mirror u=$u v=$v"
                assertTrue(label, abs(m.viewX(p, q) - u) < 1e-4f)
                assertTrue(label, abs(m.viewY(p, q) - v) < 1e-4f)
            }
        }
    }
}
