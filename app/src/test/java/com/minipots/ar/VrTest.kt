package com.minipots.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The coliseum inside goggles: two eyes, two hands, and moving the body with them. These play the
 * game without a phone: hands are put where a player would put them, frame by frame, and the
 * tests look at what the body, the weapons and the pictures do.
 */
class VrTest {
    private val dt = 1f / 60f

    // ------------------------------------------------------------ a hand to play with

    /** A hand with the wrist at the origin and the fingers up; [curl] 0 is flat, 1 is a fist. */
    private fun model(curl: Float): FloatArray {
        val p = FloatArray(HAND_POINTS * 3)
        val size = .09f
        fun put(i: Int, x: Float, y: Float, z: Float) { p[i * 3] = x; p[i * 3 + 1] = y; p[i * 3 + 2] = z }
        // Thumb, out to the side of the index finger.
        put(1, .030f, .020f, .005f); put(2, .055f, .045f, .010f)
        put(3, .070f + -.030f * curl, .070f, .015f + .020f * curl); put(4, .082f - .060f * curl, .092f, .020f + .030f * curl)
        for (finger in 0..3) {
            val k = 5 + finger * 4
            val x = .030f - finger * .020f
            val bone = 16 + finger * 4 // not used: lengths come from PROPORTIONS below
            val l1 = HandGeometry.PROPORTIONS[finger * 4 + 5] * size
            val l2 = HandGeometry.PROPORTIONS[finger * 4 + 6] * size
            val l3 = HandGeometry.PROPORTIONS[finger * 4 + 7] * size
            val y0 = HandGeometry.PROPORTIONS[finger * 4 + 4] * size
            put(k, x, y0, 0f)
            // Each joint bends by the same angle; a fist bends them by about 85 degrees each.
            val bend = curl * 1.5f
            var angle = bend; var y = y0; var z = 0f
            y += cos(angle) * l1; z += sin(angle) * l1; put(k + 1, x, y, z)
            angle += bend; y += cos(angle) * l2; z += sin(angle) * l2; put(k + 2, x, y, z)
            angle += bend; y += cos(angle) * l3; z += sin(angle) * l3; put(k + 3, x, y, z)
            if (bone < 0) put(k, x, y0, 0f)
        }
        return p
    }

    private fun palmOf(p: FloatArray, out: FloatArray = FloatArray(3)): FloatArray {
        out.fill(0f)
        for (i in HandGeometry.PALM) { out[0] += p[i * 3]; out[1] += p[i * 3 + 1]; out[2] += p[i * 3 + 2] }
        for (k in 0..2) out[k] = out[k] / HandGeometry.PALM.size.toFloat()
        return out
    }

    /** What the tracker would report for [shape] with its palm at ([x], [y], [z]) in camera space. */
    private fun reading(shape: FloatArray, x: Float, y: Float, z: Float, aspect: Float = 4f / 3f, focal: Float = .77f): HandReading {
        val r = HandReading(); r.present = true
        val c = palmOf(shape)
        for (i in 0 until HAND_POINTS) {
            val px = x + shape[i * 3] - c[0]; val py = y + shape[i * 3 + 1] - c[1]; val pz = z + shape[i * 3 + 2] - c[2]
            r.image[i * 2] = .5f + px / -pz * focal
            r.image[i * 2 + 1] = .5f - py / -pz * focal * aspect
            r.world[i * 3] = shape[i * 3]; r.world[i * 3 + 1] = shape[i * 3 + 1]; r.world[i * 3 + 2] = shape[i * 3 + 2]
        }
        return r
    }

    /** Feeds [rig] one reading every [every] seconds for [seconds], drawing at 60 frames a second. */
    private fun follow(rig: HandRig, startNs: Long, seconds: Float, every: Float, at: (Float) -> HandReading?): Long {
        var now = startNs; var sinceReading = every
        var t = 0f
        while (t < seconds) {
            if (sinceReading >= every) { sinceReading -= every; at(t)?.let { rig.accept(it, now, 4f / 3f, .77f) } }
            rig.advance(now, dt)
            now += (dt * 1e9f).toLong(); t += dt; sinceReading += dt
        }
        return now
    }

    // ------------------------------------------------------------ 9. the two eyes

    private fun rig(): StereoRig = StereoRig().also { it.screenWidth = .150f; it.layout(1600, 720) }

    /** Where a world point lands on the screen, in metres from its middle, for one eye. */
    private fun onScreen(s: StereoRig, c: ArenaCamera, eye: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        c.eye(s.eyeOffset(eye), s.left(eye), s.right(eye), s.top)
        val m = c.viewProjection
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        val nx = (m[0] * x + m[4] * y + m[8] * z + m[12]) / w
        val ny = (m[1] * x + m[5] * y + m[9] * z + m[13]) / w
        val pixel = s.viewportX(eye) + (nx * .5f + .5f) * s.viewportWidth(eye)
        out[0] = (pixel / 1600f - .5f) * s.screenWidth
        out[1] = ny
    }

    @Test fun screenIsSplitInTwoHalvesSideBySide() {
        val s = rig()
        assertEquals(2, s.eyes)
        assertEquals(0, s.viewportX(0)); assertEquals(800, s.viewportWidth(0))
        assertEquals(800, s.viewportX(1)); assertEquals(800, s.viewportWidth(1))
        assertEquals(720, s.viewportHeight)
        assertEquals(.063f, s.ipd, 1e-6f)
        assertEquals(-.0315f, s.eyeOffset(0), 1e-6f)
        assertEquals(.0315f, s.eyeOffset(1), 1e-6f)
    }

    @Test fun somethingFarAwayIsDrawnOneEyeDistanceApart() {
        // That is what makes both eyes look straight ahead at it, each through its own lens.
        val s = rig(); val c = ArenaCamera(); c.place(0f, 1f, 0f, 0f, 0f)
        val l = FloatArray(2); val r = FloatArray(2)
        onScreen(s, c, 0, 0f, 1f, -5000f, l); onScreen(s, c, 1, 0f, 1f, -5000f, r)
        assertEquals(-.0315f, l[0], 2e-4f)
        assertEquals(.0315f, r[0], 2e-4f)
        assertEquals(l[1], r[1], 1e-5f)
        // And it stays so for other eye distances, which only move the two pictures.
        s.ipd = .070f
        onScreen(s, c, 0, 0f, 1f, -5000f, l); onScreen(s, c, 1, 0f, 1f, -5000f, r)
        assertEquals(.070f, r[0] - l[0], 3e-4f)
    }

    @Test fun nearThingsAreSeenFromTwoPlacesAndNotAsTheSamePictureTwice() {
        val s = rig(); val c = ArenaCamera(); c.place(0f, 1f, 0f, 0f, 0f)
        val l = FloatArray(2); val r = FloatArray(2)
        var last = s.ipd
        // The nearer, the closer together the two pictures of it: that difference is the depth.
        for (distance in floatArrayOf(20f, 5f, 1f, .4f)) {
            onScreen(s, c, 0, 0f, 1f, -distance, l); onScreen(s, c, 1, 0f, 1f, -distance, r)
            val apart = r[0] - l[0]
            assertEquals(s.ipd * (1f - s.lensDistance / distance), apart, 2e-4f)
            assertTrue(apart < last); last = apart
        }
        // Each eye is where it should be: half the eye distance to its side of the head.
        c.eye(s.eyeOffset(0), s.left(0), s.right(0), s.top); val leftEye = c.eyeX
        c.eye(s.eyeOffset(1), s.left(1), s.right(1), s.top)
        assertEquals(s.ipd, c.eyeX - leftEye, 1e-6f)
    }

    @Test fun tiltedHeadTiltsTheLineBetweenTheEyes() {
        val s = rig(); val c = ArenaCamera(); c.place(0f, 1f, 0f, 0f, 0f, .5f)
        c.eye(s.eyeOffset(0), s.left(0), s.right(0), s.top); val lx = c.eyeX; val ly = c.eyeY
        c.eye(s.eyeOffset(1), s.left(1), s.right(1), s.top)
        // Top of the head to the right: the right eye is lower than the left one.
        assertEquals(s.ipd * cos(.5f), c.eyeX - lx, 1e-5f)
        assertEquals(-s.ipd * sin(.5f), c.eyeY - ly, 1e-5f)
    }

    @Test fun pictureIsAsWideAsTheLensShowsIt() {
        val s = rig()
        // Half of the 150 mm screen is 75 mm, seen from 42 mm: the outer edge is 43.5 mm from the lens.
        assertEquals(-.0435f / .042f, s.left(0), 1e-4f)
        assertEquals(.0315f / .042f, s.right(0), 1e-4f)
        assertEquals(-s.right(0), s.left(1), 1e-6f)
        assertEquals(-s.left(0), s.right(1), 1e-6f)
        assertEquals(.150f * 720f / 1600f * .5f / .042f, s.top, 1e-5f)
        // Without goggles: one picture on the whole screen.
        s.stereo = false
        assertEquals(1, s.eyes); assertEquals(1600, s.viewportWidth(0)); assertEquals(0f, s.eyeOffset(0), 0f)
        assertEquals(-s.left(0), s.right(0), 1e-6f)
    }

    @Test fun headIsReadFromAPhoneWornSideways() {
        val out = FloatArray(3)
        // Phone in goggles, top edge to the left, looking north with a level head.
        Look.headFromRotationMatrix(floatArrayOf(0f, -1f, 0f, 0f, 0f, -1f, 1f, 0f, 0f), 1, out)
        assertEquals(0f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f); assertEquals(0f, out[2], 1e-5f)
        // The same with the top of the head tilted to the right by t.
        val t = .3f
        Look.headFromRotationMatrix(floatArrayOf(sin(t), -cos(t), 0f, 0f, 0f, -1f, cos(t), sin(t), 0f), 1, out)
        assertEquals(0f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f); assertEquals(t, out[2], 1e-5f)
        // The other way round in the goggles (top edge to the right), level.
        Look.headFromRotationMatrix(floatArrayOf(0f, 1f, 0f, 0f, 0f, -1f, -1f, 0f, 0f), 3, out)
        assertEquals(0f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f); assertEquals(0f, out[2], 1e-5f)
        // Looking east: a quarter turn to the right.
        Look.headFromRotationMatrix(floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f), 1, out)
        assertEquals(Math.PI.toFloat() / 2f, out[0], 1e-5f); assertEquals(0f, out[2], 1e-5f)
        // Without a gyroscope the tilt comes from gravity alone.
        out.fill(9f)
        Look.tiltFromGravity(9.8f * cos(t), 9.8f * sin(t), 0f, 1, out)
        assertEquals(0f, out[1], 1e-5f); assertEquals(t, out[2], 1e-5f)
        Look.tiltFromGravity(9.8f * .8f, 0f, -9.8f * .6f, 1, out)
        assertEquals(.6435f, out[1], 1e-3f); assertEquals(0f, out[2], 1e-5f)
    }

    // ------------------------------------------------------------ 1-3. the hands in 3D

    @Test fun handIsPutWhereTheCameraSeesIt() {
        val open = model(0f)
        for (place in arrayOf(floatArrayOf(0f, 0f, -.40f), floatArrayOf(.12f, -.08f, -.35f), floatArrayOf(-.20f, .05f, -.60f))) {
            val rig = HandRig()
            follow(rig, 1_000_000_000L, .5f, 1f / 30f) { reading(open, place[0], place[1], place[2]) }
            assertEquals(HandRig.State.TRACKED, rig.state)
            val c = palmOf(rig.joints)
            assertEquals(place[0], c[0], .015f); assertEquals(place[1], c[1], .015f); assertEquals(place[2], c[2], .03f)
            // All 21 points, wrist below the finger tips as in the model.
            assertTrue(rig.joints[12 * 3 + 1] > rig.joints[1] + .10f)
        }
    }

    @Test fun leftAndRightHandKeepTheirOwnSlots() {
        val slots = HandSlots(); val slot = IntArray(2)
        val x = FloatArray(2); val y = floatArrayOf(.5f, .5f); val right = BooleanArray(2)
        // Only the right hand, then only the left one: each goes to its own slot.
        x[0] = .7f; right[0] = true
        slots.assign(1, x, y, right, 0L, slot); assertEquals(1, slot[0])
        slots.reset()
        x[0] = .3f; right[0] = false
        slots.assign(1, x, y, right, 0L, slot); assertEquals(0, slot[0])
        // Both, reported in either order.
        slots.reset()
        x[0] = .7f; right[0] = true; x[1] = .3f; right[1] = false
        slots.assign(2, x, y, right, 0L, slot); assertEquals(1, slot[0]); assertEquals(0, slot[1])
        x[0] = .32f; right[0] = false; x[1] = .69f; right[1] = true
        slots.assign(2, x, y, right, 33_000_000L, slot); assertEquals(0, slot[0]); assertEquals(1, slot[1])
        // The label of a hand seen edge on flickers: the hand stays in its slot.
        x[0] = .33f; right[0] = true; x[1] = .68f; right[1] = true
        slots.assign(2, x, y, right, 66_000_000L, slot); assertEquals(0, slot[0]); assertEquals(1, slot[1])
        // One hand leaves the picture: the other one does not change slot, whatever its label says.
        x[0] = .66f; right[0] = false
        slots.assign(1, x, y, right, 99_000_000L, slot); assertEquals(1, slot[0])
        // The hands cross over: each is still followed by where it was.
        var t = 99_000_000L
        var a = .33f; var b = .66f
        slots.reset()
        x[0] = a; right[0] = false; x[1] = b; right[1] = true
        slots.assign(2, x, y, right, t, slot)
        repeat(20) {
            a += .02f; b -= .02f; t += 33_000_000L
            x[0] = a; x[1] = b; y[0] = .4f; y[1] = .6f
            slots.assign(2, x, y, right, t, slot)
            assertEquals(0, slot[0]); assertEquals(1, slot[1])
        }
    }

    @Test fun openAndClosedHandsAreToldApart() {
        val rig = HandRig()
        var now = follow(rig, 1_000_000_000L, .3f, 1f / 30f) { reading(model(0f), 0f, 0f, -.4f) }
        assertFalse(rig.closed)
        now = follow(rig, now, .3f, 1f / 30f) { reading(model(1f), 0f, 0f, -.4f) }
        assertTrue(rig.closed)
        // Half closed is neither: the hand stays what it was, so a weapon is not dropped by doubt.
        now = follow(rig, now, .3f, 1f / 30f) { reading(model(.45f), 0f, 0f, -.4f) }
        assertTrue(rig.closed)
        follow(rig, now, .3f, 1f / 30f) { reading(model(0f), 0f, 0f, -.4f) }
        assertFalse(rig.closed)
        // The numbers behind it, measured on photographs of real hands.
        assertTrue(HandShape.closedRatio(model(0f)) > 1.7f)
        assertTrue(HandShape.closedRatio(model(1f)) < 1.2f)
    }

    @Test fun fingersNeverStretch() {
        val rig = HandRig()
        val open = model(0f)
        var now = follow(rig, 1_000_000_000L, .5f, 1f / 30f) { reading(open, 0f, 0f, -.4f) }
        // The tracker loses its grip on the index finger: its tip is reported 12 cm too far out.
        val bad = open.copyOf(); bad[8 * 3 + 1] += .12f; bad[7 * 3 + 1] += .05f
        follow(rig, now, .5f, 1f / 30f) { reading(bad, 0f, 0f, -.4f) }
        for (bone in 0 until HandGeometry.BONE_COUNT) {
            val average = HandGeometry.PROPORTIONS[bone] * .09f
            val length = HandGeometry.boneLength(rig.joints, bone)
            assertTrue("bone $bone is $length", length < average * 1.45f && length > average * .6f)
        }
        // Both hands have the same proportions, near or far.
        val far = HandRig()
        now = follow(far, 1_000_000_000L, .5f, 1f / 30f) { reading(open, .1f, 0f, -.7f) }
        for (bone in 0 until HandGeometry.BONE_COUNT) {
            assertEquals(HandGeometry.PROPORTIONS[bone] * .09f, HandGeometry.boneLength(far.joints, bone), .004f)
        }
    }

    // ------------------------------------------------------------ 8, 10. steady, and honest about losses

    /** Repeatable noise in -1..1. */
    private fun noise(i: Int): Float {
        var h = i * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffff) / 32767.5f - 1f
    }

    @Test fun stillHandIsSteadyAndMovingHandIsFollowed() {
        val open = model(0f)
        val rig = HandRig()
        var n = 0
        var sum = 0f; var sumSquares = 0f; var count = 0
        var rawSquares = 0f
        var now = 1_000_000_000L
        // Two seconds of a still hand whose place the tracker reports with 6 mm of tremor.
        for (frame in 0 until 120) {
            if (frame % 2 == 0) {
                val jx = noise(n++) * .006f; val jy = noise(n++) * .006f
                rig.accept(reading(open, .05f + jx, jy, -.4f), now, 4f / 3f, .77f)
                rawSquares += jx * jx
            }
            rig.advance(now, dt); now += 16_666_667L
            if (frame >= 30) { val x = palmOf(rig.joints)[0]; sum += x; sumSquares += x * x; count++ }
        }
        val mean = sum / count
        val steadied = sqrt(max(0f, sumSquares / count - mean * mean)); val reported = sqrt(rawSquares / 60f)
        assertEquals(.05f, mean, .004f)
        assertTrue("tremor $steadied of $reported", steadied < reported * .7f)
        // Then it moves at 60 cm a second: the drawn hand keeps up, within 3 cm.
        var x = .05f
        for (frame in 0 until 30) {
            x -= .6f * dt
            if (frame % 2 == 0) rig.accept(reading(open, x, 0f, -.4f), now, 4f / 3f, .77f)
            rig.advance(now, dt); now += 16_666_667L
        }
        assertEquals(x, palmOf(rig.joints)[0], .03f)
        assertEquals(0, rig.rejected)
    }

    @Test fun readingThatJumpsIsNotBelievedAtOnce() {
        val open = model(0f)
        val rig = HandRig()
        var now = follow(rig, 1_000_000_000L, .5f, 1f / 30f) { reading(open, -.1f, 0f, -.4f) }
        // One reading puts the hand 35 cm away: a mistake, the hand stays.
        rig.accept(reading(open, .25f, 0f, -.4f), now, 4f / 3f, .77f)
        rig.advance(now, dt); now += 33_000_000L
        assertEquals(-.1f, palmOf(rig.joints)[0], .01f)
        assertEquals(1, rig.rejected)
        now = follow(rig, now, .2f, 1f / 30f) { reading(open, -.1f, 0f, -.4f) }
        assertEquals(-.1f, palmOf(rig.joints)[0], .01f)
        // Two in a row agree: the hand really is there, and it is shown there.
        follow(rig, now, .2f, 1f / 30f) { reading(open, .25f, 0f, -.4f) }
        assertEquals(.25f, palmOf(rig.joints)[0], .015f)
    }

    @Test fun lostHandIsHeldBrieflyThenShownAsLostAndComesBack() {
        val open = model(0f)
        val rig = HandRig()
        assertEquals(HandRig.State.LOST, rig.state)
        var now = follow(rig, 1_000_000_000L, .5f, 1f / 30f) { reading(open, .1f, 0f, -.4f) }
        assertEquals(HandRig.State.TRACKED, rig.state); assertEquals(1f, rig.confidence, 0f)
        // Readings stop. For a moment the last pose is kept, with less and less confidence.
        now = follow(rig, now, .15f, 1f) { null }
        assertEquals(HandRig.State.HELD, rig.state)
        val early = rig.confidence
        assertTrue(early > 0f && early < 1f)
        assertEquals(.1f, palmOf(rig.joints)[0], .01f)
        now = follow(rig, now, .12f, 1f) { null }
        assertEquals(HandRig.State.HELD, rig.state)
        assertTrue(rig.confidence < early)
        // After that it is lost, and says so.
        now = follow(rig, now, .15f, 1f) { null }
        assertEquals(HandRig.State.LOST, rig.state); assertEquals(0f, rig.confidence, 0f)
        now = follow(rig, now, 1f, 1f) { null }
        assertEquals(HandRig.State.LOST, rig.state)
        // It comes back somewhere else: followed again at once, there, with no slide from the old place.
        rig.accept(reading(open, -.15f, -.05f, -.5f), now, 4f / 3f, .77f)
        rig.advance(now, dt)
        assertEquals(HandRig.State.TRACKED, rig.state)
        assertEquals(-.15f, palmOf(rig.joints)[0], .015f)
    }

    @Test fun handIsMovedOnBetweenReadingsButOnlyALittle() {
        val open = model(0f)
        val rig = HandRig()
        // A slow tracker: 10 readings a second of a hand moving at 80 cm a second.
        var x = -.2f
        var now = 1_000_000_000L
        for (frame in 0 until 60) {
            x += .8f * dt
            if (frame % 6 == 0) rig.accept(reading(open, x, 0f, -.4f), now, 4f / 3f, .77f)
            rig.advance(now, dt); now += 16_666_667L
        }
        val lastReading = x - .8f * dt * 6f
        // Six frames after the last reading the drawn hand is ahead of it, closer to the real one...
        val drawn = palmOf(rig.joints)[0]
        assertTrue("drawn $drawn reading $lastReading", drawn > lastReading + .01f)
        assertTrue(drawn < x + .02f)
        // ...and when readings stop it does not sail away: at most 70 ms of movement.
        for (frame in 0 until 15) { rig.advance(now, dt); now += 16_666_667L }
        assertTrue(palmOf(rig.joints)[0] < lastReading + .8f * (HandRig.MAX_LEAD_SECONDS + .08f))
    }

    // ------------------------------------------------------------ 4-6. moving like Gorilla Tag

    private val hands = arrayOf(HandInput(), HandInput())
    private val flat = model(0f)
    private val fist = model(1f)

    /** Puts hand [i] so that its palm is at ([x], [y], [z]) from the eyes, in world axes, looking along -Z. */
    private fun place(i: Int, x: Float, y: Float, z: Float, closed: Boolean = false) {
        val shape = if (closed) fist else flat
        val c = palmOf(shape)
        val h = hands[i]; h.present = true; h.closed = closed
        for (k in 0 until HAND_POINTS) {
            h.joints[k * 3] = x / Coliseum.ARM_SCALE + shape[k * 3] - c[0]
            h.joints[k * 3 + 1] = y / Coliseum.ARM_SCALE + shape[k * 3 + 1] - c[1]
            h.joints[k * 3 + 2] = z / Coliseum.ARM_SCALE + shape[k * 3 + 2] - c[2]
        }
    }

    private fun hide(i: Int) { hands[i].present = false; hands[i].closed = false }

    private fun Coliseum.frames(count: Int) { repeat(count) { update(dt, 0f, 0f, 0f, hands) } }

    /** Moves hand [i] in a straight line over [count] frames. */
    private fun Coliseum.stroke(
        i: Int, count: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, closed: Boolean = false,
    ) {
        for (f in 1..count) {
            val t = f.toFloat() / count
            place(i, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, closed)
            update(dt, 0f, 0f, 0f, hands)
        }
    }

    private fun game(): Coliseum { hide(0); hide(1); return Coliseum() }

    @Test fun bodyAtRestStaysWhereItIs() {
        val g = game()
        g.frames(120)
        assertEquals(0f, g.body.x, 0f); assertEquals(GorillaBody.EYE_HEIGHT, g.body.y, 1e-5f); assertEquals(Arena.START_Z, g.body.z, 0f)
        // Hands in the air touch nothing and move nothing.
        place(0, -.3f, -.2f, -.8f); place(1, .3f, -.2f, -.8f)
        g.frames(60)
        assertFalse(g.body.touching[0]); assertFalse(g.body.touching[1])
        assertEquals(Arena.START_Z, g.body.z, 0f)
        assertTrue(g.handPresent[0] && g.handPresent[1])
    }

    @Test fun handOnTheGroundIsHeldThereAndPullingItBackMovesTheBodyForward() {
        for (hand in 0..1) {
            val g = game()
            val side = if (hand == 0) -.25f else .25f
            // The hand goes down to the sand in front of the body.
            g.stroke(hand, 20, side, -.3f, -.9f, side, -.76f, -.9f)
            assertTrue(g.body.touching[hand]); assertFalse(g.body.touching[1 - hand])
            val heldX = g.palm[hand * 3]; val heldZ = g.palm[hand * 3 + 2]
            assertEquals(GorillaBody.HAND_RADIUS, g.palm[hand * 3 + 1], .01f)
            // Pulled back by 60 cm along the sand: the hand stays, the body goes forward by 60 cm.
            g.stroke(hand, 40, side, -.76f, -.9f, side, -.76f, -.3f)
            g.frames(10)
            assertTrue(g.body.touching[hand])
            assertEquals(Arena.START_Z - .6f, g.body.z, .03f)
            assertEquals(0f, g.body.x, .02f)
            assertEquals(heldX, g.palm[hand * 3], 1e-4f); assertEquals(heldZ, g.palm[hand * 3 + 2], 1e-4f)
            // Sideways works the same way: hand to the left, body to the right.
            g.stroke(hand, 30, side, -.76f, -.3f, side - .3f, -.76f, -.3f)
            g.frames(10)
            assertEquals(.3f, g.body.x, .03f)
            // Lifted: the hand is free again and the body stays on the sand.
            g.stroke(hand, 10, side - .3f, -.76f, -.3f, side - .3f, -.3f, -.3f)
            assertFalse(g.body.touching[hand])
            g.frames(60)
            assertEquals(GorillaBody.EYE_HEIGHT, g.body.y, 1e-4f)
        }
    }

    @Test fun handsTakeTurnsToWalkAcrossTheArena() {
        val g = game()
        // Left, right, left, right: each hand reaches forward, lands, pulls back and lifts, and
        // the next one lands before it lets go.
        place(0, -.25f, -.3f, -.9f); place(1, .25f, -.3f, -.3f)
        g.frames(5)
        repeat(8) { step ->
            val hand = step % 2; val other = 1 - hand
            val side = if (hand == 0) -.25f else .25f
            g.stroke(hand, 8, side, -.3f, -.9f, side, -.76f, -.9f)
            // The other hand, if it was down, comes up and forward meanwhile.
            val otherSide = -side
            for (f in 1..24) {
                val t = f / 24f
                place(hand, side, -.76f, -.9f + .6f * t)
                place(other, otherSide, if (t < .5f) -.76f + .92f * t else -.3f, -.3f - .6f * t)
                g.update(dt, 0f, 0f, 0f, hands)
            }
        }
        // Eight pulls of 60 cm.
        assertTrue("walked to ${g.body.z}", g.body.z < Arena.START_Z - 4.2f)
        assertEquals(0f, g.body.x, .15f)
        assertTrue(g.body.y < GorillaBody.EYE_HEIGHT + .15f)
    }

    @Test fun bothHandsTogetherMoveTheBodyByTheirMean() {
        val g = game()
        g.stroke(0, 15, -.25f, -.3f, -.9f, -.25f, -.76f, -.9f)
        g.stroke(1, 15, .25f, -.3f, -.9f, .25f, -.76f, -.9f)
        assertTrue(g.body.touching[0] && g.body.touching[1])
        // Both pull back together.
        for (f in 1..30) {
            val z = -.9f + .5f * f / 30f
            place(0, -.25f, -.76f, z); place(1, .25f, -.76f, z)
            g.update(dt, 0f, 0f, 0f, hands)
        }
        g.frames(10)
        assertEquals(Arena.START_Z - .5f, g.body.z, .03f)
        // One pulls another 40 cm and the other does not: the body goes half of that.
        g.stroke(0, 30, -.25f, -.76f, -.4f, -.25f, -.76f, 0f)
        g.frames(10)
        assertEquals(Arena.START_Z - .7f, g.body.z, .04f)
    }

    @Test fun pushingDownOnTheGroundThrowsTheBodyUp() {
        val g = game()
        place(0, -.25f, -.3f, -.5f); place(1, .25f, -.3f, -.5f)
        g.frames(5)
        for (f in 1..12) { val y = -.3f - .46f * f / 12f; place(0, -.25f, y, -.5f); place(1, .25f, y, -.5f); g.update(dt, 0f, 0f, 0f, hands) }
        assertTrue(g.body.touching[0] && g.body.touching[1])
        // A slow push only lifts the body, by as much as the hands go down.
        for (f in 1..60) { val y = -.76f - .4f * f / 60f; place(0, -.25f, y, -.5f); place(1, .25f, y, -.5f); g.update(dt, 0f, 0f, 0f, hands) }
        g.frames(10)
        assertEquals(GorillaBody.EYE_HEIGHT + .45f, g.body.y, .04f)
        assertFalse(g.body.onGround)
        // Back down, then a quick push of 50 cm and the hands let go: a jump.
        for (f in 1..30) { val y = -1.16f + .4f * f / 30f; place(0, -.25f, y, -.5f); place(1, .25f, y, -.5f); g.update(dt, 0f, 0f, 0f, hands) }
        for (f in 1..8) { val y = -.76f - .5f * f / 8f; place(0, -.25f, y, -.6f + .2f * f / 8f); place(1, .25f, y, -.6f + .2f * f / 8f); g.update(dt, 0f, 0f, 0f, hands) }
        val startZ = g.body.z
        place(0, -.25f, -.3f, -.5f); place(1, .25f, -.3f, -.5f)
        g.update(dt, 0f, 0f, 0f, hands)
        assertFalse(g.body.touching[0]); assertFalse(g.body.touching[1])
        assertTrue("rising at ${g.body.vy}", g.body.vy > 2f)
        var top = g.body.y
        var airborne = 0
        while (!g.body.onGround && airborne < 300) { g.update(dt, 0f, 0f, 0f, hands); top = max(top, g.body.y); airborne++ }
        // Well above where the hands alone could lift it, and gravity brings it back.
        assertTrue("top $top", top > GorillaBody.EYE_HEIGHT + .8f)
        assertTrue(airborne in 20..200)
        assertEquals(GorillaBody.EYE_HEIGHT, g.body.y, 1e-4f)
        // The push also went backwards a little, so the jump went forward.
        assertTrue(g.body.z < startZ - .05f)
    }

    @Test fun quickPullAlongTheGroundSlidesOn() {
        val g = game()
        g.stroke(1, 10, .25f, -.3f, -.9f, .25f, -.76f, -.9f)
        g.stroke(1, 10, .25f, -.76f, -.9f, .25f, -.76f, -.2f)
        val whenReleased = g.body.z
        g.stroke(1, 3, .25f, -.76f, -.2f, .25f, -.3f, -.2f)
        assertFalse(g.body.touching[1])
        g.frames(60)
        // It keeps going after the hand let go, and friction stops it.
        assertTrue(g.body.z < whenReleased - .3f)
        g.frames(240)
        val rest = g.body.z
        g.frames(120)
        assertEquals(rest, g.body.z, 0f)
    }

    @Test fun wallsAndDummiesCannotBeWalkedThrough() {
        val g = game()
        // Thrown at the wall behind the start.
        g.body.reset(0f, GorillaBody.EYE_HEIGHT, 12.5f)
        g.body.vz = 7f
        g.frames(180)
        assertEquals(Arena.RADIUS - GorillaBody.BODY_RADIUS, g.body.z, .02f)
        // And at a dummy.
        val d = g.dummies[0]
        g.body.reset(d.x, GorillaBody.EYE_HEIGHT, d.z + 2f)
        g.body.vz = -6f
        g.frames(180)
        val dx = g.body.x - d.x; val dz = g.body.z - d.z
        assertTrue(sqrt(dx * dx + dz * dz) >= Dummy.RADIUS + GorillaBody.BODY_RADIUS - .01f)
        // Hands dragging the body into the wall do not get it through either.
        g.body.reset(0f, GorillaBody.EYE_HEIGHT, -13f)
        g.stroke(0, 10, -.25f, -.3f, -.4f, -.25f, -.76f, -.4f)
        g.stroke(0, 60, -.25f, -.76f, -.4f, -.25f, -.76f, .9f)
        g.frames(30)
        assertTrue(g.body.z >= -(Arena.RADIUS - GorillaBody.BODY_RADIUS) - .01f)
    }

    @Test fun wallIsClimbedHandOverHand() {
        val g = game()
        // Facing the wall, 60 cm from it.
        g.body.reset(0f, GorillaBody.EYE_HEIGHT, -13.4f)
        place(0, -.25f, .3f, -.2f); place(1, .25f, .3f, -.2f)
        g.frames(5)
        // The left hand goes to the wall, above the head.
        g.stroke(0, 8, -.25f, .3f, -.2f, -.25f, .3f, -.62f)
        assertTrue(g.body.touching[0])
        assertEquals(-Arena.RADIUS + GorillaBody.HAND_RADIUS, g.palm[2], .01f)
        var heights = g.body.y
        repeat(3) { step ->
            val hand = step % 2; val other = 1 - hand
            val side = if (hand == 0) -.25f else .25f
            // This hand pulls down on the wall while the other one comes off, goes up and lands
            // again: higher up on the wall, or, the last time, on top of it.
            val low = if (step == 0) .3f else -.5f
            val landY = if (step == 2) .05f else .3f
            // 40 cm in from the edge of the wall.
            val landZ = if (step == 2) -(Arena.RADIUS + .4f) - g.body.z else -.62f
            for (f in 1..36) {
                val t = f / 36f
                place(hand, side, .3f - .8f * t, -.62f)
                when {
                    t < .25f -> place(other, -side, low, -.62f + .42f * t * 4f)
                    t < .75f -> place(other, -side, low + (.3f - low) * (t - .25f) * 2f, -.2f)
                    else -> place(other, -side, .3f + (landY - .3f) * (t - .75f) * 4f, -.2f + (landZ + .2f) * (t - .75f) * 4f)
                }
                g.update(dt, 0f, 0f, 0f, hands)
            }
            assertTrue("step $step: hand $other holds", g.body.touching[other])
            assertTrue("step $step: rose from $heights to ${g.body.y}", g.body.y > heights + .6f)
            heights = g.body.y
        }
        // Three pulls of 80 cm: the eyes are level with the top of the wall, which is 3 m high,
        // and the right hand lies on top of it.
        assertTrue("climbed to ${g.body.y}", g.body.y > Arena.WALL_HEIGHT)
        assertEquals(Arena.WALL_HEIGHT + GorillaBody.HAND_RADIUS, g.palm[4], .01f)
        // Over the edge: that hand pushes down and back, the body goes up and on to the wall.
        hide(0)
        val reach = g.palm[5] - g.body.z
        g.stroke(1, 50, .25f, .05f, reach, .25f, -.85f, reach + 1.1f)
        g.frames(20)
        assertTrue("over the edge at ${g.body.z}", g.body.z < -Arena.RADIUS - .05f)
        hide(1)
        g.frames(90)
        // Standing on the wall.
        assertFalse(g.body.touching[0] || g.body.touching[1])
        assertEquals(Arena.WALL_HEIGHT + GorillaBody.EYE_HEIGHT, g.body.y, 1e-3f)
        assertTrue(g.body.onGround)
        // And from there up the rows of seats, which are steps of 90 cm: the hand lands on the
        // next row and pushes down and back.
        place(1, .25f, .2f, -.9f); g.frames(3)
        g.stroke(1, 10, .25f, .2f, -.9f, .25f, .18f, -1.1f)
        assertTrue(g.body.touching[1])
        g.stroke(1, 40, .25f, .18f, -1.1f, .25f, -.95f, -.1f)
        hide(1); g.frames(90)
        assertEquals(Arena.WALL_HEIGHT + .9f + GorillaBody.EYE_HEIGHT, g.body.y, 1e-3f)
    }

    @Test fun dummyCanBeHeldAndPulledOn() {
        val g = game()
        val d = g.dummies[0]
        g.body.reset(d.x, GorillaBody.EYE_HEIGHT, d.z + 1.2f)
        // The hand goes to the post, at chest height.
        g.stroke(1, 20, .1f, .1f, -.3f, .1f, .1f, -.85f)
        assertTrue(g.body.touching[1])
        // Pulling on it pulls the body towards it.
        val before = g.body.z
        g.stroke(1, 20, .1f, .1f, -.85f, .1f, .1f, -.55f)
        g.frames(10)
        assertEquals(before - .3f, g.body.z, .04f)
    }

    // ------------------------------------------------------------ 8. losing a hand that holds on

    @Test fun losingTheHandLetsGoWithoutThrowingTheBody() {
        val g = game()
        g.stroke(1, 10, .25f, -.3f, -.9f, .25f, -.76f, -.9f)
        g.stroke(1, 20, .25f, -.76f, -.9f, .25f, -.76f, -.6f)
        g.frames(20)
        assertTrue(g.body.touching[1])
        val z = g.body.z
        // The camera loses the hand while it holds the ground.
        hide(1)
        g.frames(60)
        assertFalse(g.body.touching[1]); assertFalse(g.handPresent[1])
        assertEquals(z, g.body.z, .02f)
        assertEquals(GorillaBody.EYE_HEIGHT, g.body.y, 1e-4f)
        // It comes back somewhere else: no jump of the body, and it works again.
        place(1, .3f, -.3f, -.5f)
        g.frames(5)
        assertEquals(z, g.body.z, .02f)
        g.stroke(1, 10, .3f, -.3f, -.5f, .3f, -.3f, -.9f)
        g.stroke(1, 10, .3f, -.3f, -.9f, .3f, -.76f, -.9f)
        g.stroke(1, 20, .3f, -.76f, -.9f, .3f, -.76f, -.5f)
        g.frames(10)
        assertEquals(z - .4f, g.body.z, .04f)
    }

    // ------------------------------------------------------------ 7. weapons

    /** Stands at the sword and closes hand [i] on its grip. */
    private fun Coliseum.takeSword(i: Int): Weapon {
        val sword = weapons[0]
        body.reset(sword.x, GorillaBody.EYE_HEIGHT, sword.z + .8f)
        place(i, 0f, sword.y - GorillaBody.EYE_HEIGHT, -.8f)
        frames(5)
        assertNull(held[i])
        place(i, 0f, sword.y - GorillaBody.EYE_HEIGHT, -.8f, closed = true)
        frames(3)
        return sword
    }

    @Test fun closedHandOnTheGripTakesTheWeaponAndItStaysInThatHand() {
        for (hand in 0..1) {
            val g = game()
            val sword = g.takeSword(hand)
            assertSame(sword, g.held[hand]); assertNull(g.held[1 - hand])
            assertTrue(sword.held)
            // It follows the hand.
            g.stroke(hand, 30, 0f, .18f, -.8f, .4f, .5f, -.6f, closed = true)
            g.frames(20)
            assertEquals(g.palm[hand * 3], sword.x, .02f); assertEquals(g.palm[hand * 3 + 1], sword.y, .02f)
            assertEquals(g.palm[hand * 3 + 2], sword.z, .02f)
            // And lies along the knuckles of the fist.
            val j = g.handJoints[hand]
            var kx = j[15] - j[51]; var ky = j[16] - j[52]; var kz = j[17] - j[53]
            val kl = sqrt(kx * kx + ky * ky + kz * kz); kx /= kl; ky /= kl; kz /= kl
            assertTrue(sword.ax * kx + sword.ay * ky + sword.az * kz > .98f)
            // The other hand, closed in the air, takes nothing.
            place(1 - hand, if (hand == 0) .5f else -.5f, 0f, -.6f, closed = true)
            g.frames(10)
            assertNull(g.held[1 - hand])
        }
    }

    @Test fun closedHandAwayFromAnyGripTakesNothing() {
        val g = game()
        val sword = g.weapons[0]
        g.body.reset(sword.x, GorillaBody.EYE_HEIGHT, sword.z + .8f)
        // Half a metre to the side of the grip.
        place(1, .5f, sword.y - GorillaBody.EYE_HEIGHT, -.8f, closed = true)
        g.frames(20)
        assertNull(g.held[1])
        // At the blade, down by the sand, is not at the grip either.
        place(1, 0f, -.5f, -.8f, closed = true)
        g.frames(20)
        assertNull(g.held[1])
    }

    @Test fun weaponIsOnlyLetGoByAHandClearlyOpenAndThenFallsAndStandsAgain() {
        val g = game()
        val sword = g.takeSword(1)
        g.stroke(1, 20, 0f, .18f, -.8f, .3f, .6f, -.6f, closed = true)
        // A glimpse of an open hand, as a blurred picture gives, does not drop it.
        place(1, .3f, .6f, -.6f); g.frames(6)
        assertSame(sword, g.held[1])
        place(1, .3f, .6f, -.6f, closed = true); g.frames(10)
        // Neither does losing the hand: the weapon waits at the side of the view.
        hide(1); g.frames(60)
        assertSame(sword, g.held[1])
        place(1, .3f, .6f, -.6f, closed = true); g.frames(20)
        // Open for a quarter of a second: let go.
        place(1, .3f, .6f, -.6f); g.frames(20)
        assertNull(g.held[1]); assertFalse(sword.held)
        // It falls, comes to rest and stands in the sand again, grip up, to be taken once more.
        g.frames(240)
        assertFalse(sword.flying)
        assertEquals(sword.plantedHeight, sword.y, .01f)
        assertEquals(-1f, sword.ay, .01f)
        assertEquals(g.body.x + .3f, sword.x, .25f)
    }

    @Test fun weaponLetGoInASwingIsThrown() {
        val g = game()
        val sword = g.takeSword(1)
        g.stroke(1, 20, 0f, .18f, -.8f, .3f, .5f, -.2f, closed = true)
        g.frames(10)
        // A throw: the hand goes forward at 6 m/s and opens on the way.
        for (f in 1..9) { place(1, .3f, .5f, -.2f - .1f * f, closed = f < 4); g.update(dt, 0f, 0f, 0f, hands) }
        // The arm is stretched and the open hand stays in sight.
        g.frames(12)
        assertNull(g.held[1])
        val from = g.body.z
        var furthest = sword.z
        repeat(300) { g.update(dt, 0f, 0f, 0f, hands); furthest = minOf(furthest, sword.z) }
        assertTrue("thrown to $furthest from $from", furthest < from - 2.5f)
        assertFalse(sword.flying)
        // Wherever it lands it is inside the arena and above the floor.
        assertTrue(sqrt(sword.x * sword.x + sword.z * sword.z) < ArenaWorld.OUTER_RADIUS)
        assertEquals(g.world.floorAt(sword.x, sword.z) + sword.plantedHeight, sword.y, .02f)
    }

    @Test fun thrownWeaponBouncesOffTheWall() {
        val g = game()
        val sword = g.takeSword(1)
        g.body.reset(0f, GorillaBody.EYE_HEIGHT, -11f)
        g.stroke(1, 20, .3f, .5f, -.2f, .3f, .5f, -.2f, closed = true)
        for (f in 1..9) { place(1, .3f, .5f, -.2f - .1f * f, closed = f < 4); g.update(dt, 0f, 0f, 0f, hands) }
        g.frames(12)
        assertNull(g.held[1])
        var deepest = 0f
        repeat(300) {
            g.update(dt, 0f, 0f, 0f, hands)
            deepest = max(deepest, sqrt(sword.x * sword.x + sword.z * sword.z))
        }
        // It never got into the wall, and it came back from it.
        assertTrue("reached $deepest", deepest < Arena.RADIUS)
        assertTrue(deepest > Arena.RADIUS - .6f)
        assertFalse(sword.flying)
    }

    /** Stands to the left of a dummy with the sword in hand [i]. */
    private fun readyToStrike(g: Coliseum, i: Int): Dummy {
        g.takeSword(i)
        val d = g.dummies[0]
        g.body.reset(d.x - .7f, GorillaBody.EYE_HEIGHT, d.z + .9f)
        // The sword points to the right, along the knuckles; the hand waits in front of the body.
        g.stroke(i, 30, .1f, .2f, -.3f, .1f, .2f, -.3f, closed = true)
        g.frames(30)
        return d
    }

    @Test fun fastSwingThroughADummyIsABlowWithEitherHand() {
        for (hand in 0..1) {
            val g = game()
            val d = readyToStrike(g, hand)
            assertEquals(0, g.blows)
            // The fist goes forward by 1.1 m in a tenth of a second; the blade sweeps the dummy.
            var felt = 0f
            for (f in 1..6) {
                place(hand, .1f, .2f, -.3f - 1.1f * f / 6f, closed = true)
                g.update(dt, 0f, 0f, 0f, hands); felt = max(felt, g.blowStrength)
            }
            repeat(20) { g.update(dt, 0f, 0f, 0f, hands); felt = max(felt, g.blowStrength) }
            assertEquals(1, g.blows)
            assertTrue(d.health < Dummy.FULL_HEALTH)
            assertTrue(felt > .35f)
            assertTrue(abs(d.leanSpeedX) + abs(d.leanSpeedZ) + abs(d.leanX) + abs(d.leanZ) > 0f)
        }
    }

    @Test fun weaponMovedSlowlyThroughADummyIsNotABlow() {
        val g = game()
        val d = readyToStrike(g, 1)
        g.stroke(1, 120, .1f, .2f, -.3f, .1f, .2f, -1.4f, closed = true)
        assertEquals(0, g.blows)
        assertEquals(Dummy.FULL_HEALTH, d.health)
    }

    @Test fun dummyFallsApartAndComesBack() {
        val g = game()
        val d = readyToStrike(g, 1)
        var swings = 0
        while (d.up && swings < 8) {
            for (f in 1..6) { place(1, .1f, .2f, -.3f - 1.1f * f / 6f, closed = true); g.update(dt, 0f, 0f, 0f, hands) }
            g.frames(30)
            g.stroke(1, 40, .1f, .2f, -1.4f, .1f, .2f, -.3f, closed = true)
            g.frames(30)
            swings++
        }
        assertFalse(d.up)
        assertEquals(1, g.dummiesDown)
        assertTrue(swings in 2..3)
        // While it is away the hand finds nothing to hold on to there.
        g.frames(60)
        assertTrue(d.standing < .1f)
        g.frames((Coliseum.AWAY_SECONDS * 60).toInt() + 60)
        assertTrue(d.up); assertEquals(Dummy.FULL_HEALTH, d.health); assertTrue(d.standing > .9f)
    }

    @Test fun oneHandFightsWhileTheOtherWalks() {
        val g = game()
        val sword = g.takeSword(1)
        val z = g.body.z
        // The right hand keeps the sword up; the left one pulls the body forward along the sand.
        place(1, .3f, .3f, -.6f, closed = true)
        for (f in 1..10) { place(0, -.25f, -.3f - .46f * f / 10f, -.9f); g.update(dt, 0f, 0f, 0f, hands) }
        for (f in 1..30) { place(0, -.25f, -.76f, -.9f + .6f * f / 30f); g.update(dt, 0f, 0f, 0f, hands) }
        g.frames(10)
        assertEquals(z - .6f, g.body.z, .04f)
        assertSame(sword, g.held[1])
        assertEquals(g.palm[5], sword.z, .03f)
    }

    @Test fun restartPutsEverythingBack() {
        val g = game()
        val sword = g.takeSword(1)
        g.stroke(1, 20, 0f, .18f, -.8f, .3f, .5f, -.4f, closed = true)
        g.restart()
        assertNull(g.held[0]); assertNull(g.held[1])
        assertFalse(sword.held)
        assertEquals(sword.homeX, sword.x, 0f); assertEquals(sword.homeZ, sword.z, 0f)
        assertEquals(Arena.START_Z, g.body.z, 0f); assertEquals(GorillaBody.EYE_HEIGHT, g.body.y, 0f)
        assertEquals(0, g.blows)
        assertNotNull(g.camera)
    }

    // ------------------------------------------------------------ the solid things, and the cost

    @Test fun solidThingsAreWhereTheSceneryDrawsThem() {
        val w = ArenaWorld()
        assertEquals(1.5f, w.distance(0f, 1.5f, 0f), 1e-5f); assertEquals(1f, w.normal[1], 0f)
        // The wall: 14 m from the centre, 3 m high, facing inwards.
        assertEquals(.5f, w.distance(13.5f, 1f, 0f), 1e-5f); assertEquals(-1f, w.normal[0], 1e-5f)
        assertEquals(.5f, w.distance(0f, 1f, -13.5f), 1e-5f); assertEquals(1f, w.normal[2], 1e-5f)
        assertTrue(w.distance(14.3f, 1f, 0f) < 0f)
        // Its top, and the rows of seats behind it.
        assertEquals(.2f, w.distance(14.5f, 3.2f, 0f), 1e-5f); assertEquals(1f, w.normal[1], 1e-5f)
        assertEquals(0f, w.floorAt(13.9f, 0f), 0f); assertEquals(3f, w.floorAt(14.5f, 0f), 0f)
        assertEquals(3.9f, w.floorAt(0f, 15.5f), 1e-5f); assertEquals(7.5f, w.floorAt(21.2f, 0f), 1e-5f)
        // The edge of the wall is a corner.
        assertEquals(sqrt(.02f), w.distance(13.9f, 3.1f, 0f), 1e-4f)
        // Nobody gets out at the top.
        assertTrue(w.distance(ArenaWorld.OUTER_RADIUS + .1f, 30f, 0f) < 0f)
        // Dummies.
        assertEquals(-1, w.lastDummy)
        w.addDummy(2f, 3f, 1.75f)
        assertEquals(.2f, w.distance(2.5f, 1f, 3f), 1e-5f); assertEquals(0, w.lastDummy); assertEquals(1f, w.normal[0], 1e-5f)
        assertEquals(.25f, w.distance(2f, 2f, 3f), 1e-5f)
        w.clearDummies()
        assertEquals(1f, w.distance(2.5f, 1f, 3f), 1e-5f)
    }

    @Test fun aFrameOfTheGameCostsAlmostNothing() {
        // Everything the game does per drawn frame, besides drawing: both hands and the world.
        val g = game()
        val rigs = arrayOf(HandRig(), HandRig())
        val open = model(0f)
        var now = 1_000_000_000L
        val start = System.nanoTime()
        val frames = 6000
        for (f in 0 until frames) {
            if (f % 3 == 0) for (i in 0..1) rigs[i].accept(reading(open, -.1f + .2f * i, -.1f * sin(f * .05f), -.4f), now, 4f / 3f, .77f)
            for (i in 0..1) {
                rigs[i].advance(now, dt)
                hands[i].present = rigs[i].state != HandRig.State.LOST; hands[i].closed = rigs[i].closed
                System.arraycopy(rigs[i].joints, 0, hands[i].joints, 0, hands[i].joints.size)
            }
            g.update(dt, .3f * sin(f * .01f), -.5f, 0f, hands)
            now += 16_666_667L
        }
        val perFrameMs = (System.nanoTime() - start) / 1e6f / frames
        // A phone has 16 ms per frame; this is a desktop JVM, so only a gross error would show.
        assertTrue("one frame took $perFrameMs ms", perFrameMs < 2f)
        assertFalse(g.body.x.isNaN() || g.body.y.isNaN() || g.body.z.isNaN())
    }
}
