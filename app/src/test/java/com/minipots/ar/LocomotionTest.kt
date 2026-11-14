package com.minipots.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Moving the body with the hands, in both ways of moving, played without a phone: the hands are
 * put where a player would put them, frame by frame, with the faults real tracking has (tremor,
 * hands that vanish and come back somewhere else, readings that jump), and the tests look at what
 * the body does.
 */
class LocomotionTest {
    private val hands = arrayOf(HandInput(), HandInput())
    private val flat = FloatArray(HAND_POINTS * 3).also { p ->
        // A flat hand is enough here: only the middle of the palm matters to the body.
        for (i in 0 until HAND_POINTS) { p[i * 3] = (i % 5 - 2) * .015f; p[i * 3 + 1] = (i / 5) * .02f }
    }
    private val palm = FloatArray(3).also { c ->
        for (i in HandGeometry.PALM) { c[0] += flat[i * 3]; c[1] += flat[i * 3 + 1]; c[2] += flat[i * 3 + 2] }
        for (k in 0..2) c[k] = c[k] / HandGeometry.PALM.size.toFloat()
    }

    /** Puts hand [i] so that its palm is at ([x], [y], [z]) from the eyes of [g], in world axes, looking along -Z. */
    private fun place(g: Coliseum, i: Int, x: Float, y: Float, z: Float, live: Boolean = true) {
        val h = hands[i]; h.present = true; h.live = live; h.closed = false
        for (k in 0 until HAND_POINTS) {
            h.joints[k * 3] = x / g.reach + flat[k * 3] - palm[0]
            h.joints[k * 3 + 1] = y / g.reach + flat[k * 3 + 1] - palm[1]
            h.joints[k * 3 + 2] = z / g.reach + flat[k * 3 + 2] - palm[2]
        }
    }

    private fun hide(i: Int) { hands[i].present = false; hands[i].live = true }

    private fun game(mode: Locomotion = Locomotion.CONTACT): Coliseum {
        hide(0); hide(1)
        val g = Coliseum(); g.body.mode = mode
        return g
    }

    private fun Coliseum.frames(count: Int, dt: Float = 1f / 60f) { repeat(count) { update(dt, 0f, 0f, 0f, hands) } }

    /** Moves the given hands in a straight line, in world axes from the eyes, for [seconds]. */
    private fun Coliseum.move(
        seconds: Float, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        left: Boolean = true, right: Boolean = true, dt: Float = 1f / 60f, across: Float = .25f,
    ) {
        val count = max(1, Math.round(seconds / dt))
        for (f in 1..count) {
            val t = f.toFloat() / count
            val x = x0 + (x1 - x0) * t; val y = y0 + (y1 - y0) * t; val z = z0 + (z1 - z0) * t
            if (left) place(this, 0, x - across, y, z)
            if (right) place(this, 1, x + across, y, z)
            update(dt, 0f, 0f, 0f, hands)
        }
    }

    private fun speed(g: Coliseum) = sqrt(g.body.vx * g.body.vx + g.body.vy * g.body.vy + g.body.vz * g.body.vz)

    /** How far below the eyes the palm is when it rests on the sand. */
    private fun ground(g: Coliseum) = -(g.eyeHeight - g.body.handRadius)

    // ------------------------------------------------------------ the steps of time

    @Test fun timeGoesInFixedStepsWhateverTheFrameRate() {
        val ends = FloatArray(3)
        val rates = floatArrayOf(30f, 60f, 90f)
        for (n in rates.indices) {
            val dt = 1f / rates[n]
            val g = game()
            place(g, 0, -.25f, -.4f, -.6f); place(g, 1, .25f, -.4f, -.6f); g.frames(Math.round(.2f / dt), dt)
            g.move(.2f, 0f, -.4f, -.6f, 0f, ground(g), -.6f, dt = dt)
            g.move(.3f, 0f, ground(g), -.6f, 0f, ground(g), .2f, dt = dt)
            hide(0); hide(1); g.frames(Math.round(3f / dt), dt)
            ends[n] = g.body.z
            // 3.7 seconds at 120 steps a second, give or take a frame.
            assertEquals(3.7f * 120f, g.body.steps.toFloat(), 8f)
        }
        // The same strokes take the body to the same place at any frame rate.
        assertTrue("moved from ${Arena.START_Z} to ${ends[1]}", ends[1] < Arena.START_Z - .8f)
        assertEquals(ends[1], ends[0], .06f); assertEquals(ends[1], ends[2], .06f)
    }

    @Test fun frameThatTookVeryLongIsNotMadeUpFor() {
        val g = game()
        g.update(.5f, 0f, 0f, 0f, hands)
        assertEquals(GorillaBody.MAX_STEPS.toLong(), g.body.steps)
        g.update(.5f, 0f, 0f, 0f, hands)
        assertEquals(2L * GorillaBody.MAX_STEPS, g.body.steps)
        // A frame shorter than a step waits for the next one.
        val h = game()
        h.update(.004f, 0f, 0f, 0f, hands); assertEquals(0L, h.body.steps)
        h.update(.004f, 0f, 0f, 0f, hands); assertEquals(0L, h.body.steps)
        h.update(.004f, 0f, 0f, 0f, hands); assertEquals(1L, h.body.steps)
    }

    // ------------------------------------------------------------ 1. standing still

    /** Both hands held at ([y], [z]) for [seconds], trembling the way tracking makes them. */
    private fun tremble(g: Coliseum, seconds: Float, y: Float, z: Float, real: Float) {
        val random = java.util.Random(11)
        var jx = 0f; var jy = 0f; var jz = 0f
        val count = Math.round(seconds * 60f)
        for (f in 0 until count) {
            // A new reading ten times a second, each a little off, as a slow phone gives them.
            if (f % 6 == 0) {
                jx = (random.nextFloat() - .5f) * 2f * real * g.reach; jy = (random.nextFloat() - .5f) * 2f * real * g.reach
                jz = (random.nextFloat() - .5f) * 2f * real * g.reach
            }
            place(g, 0, -.25f + jx, y + jy, z + jz); place(g, 1, .25f - jy, y + jz, z + jx)
            g.update(1f / 60f, 0f, 0f, 0f, hands)
        }
    }

    @Test fun playerWhoStandsStillIsNotMovedByTheTremorOfTheHands() {
        for (mode in Locomotion.values()) {
            // Hands in the air in front of the chest, trembling by 4 mm either way, for 10 seconds.
            val g = game(mode)
            tremble(g, 10f, -.4f, -.7f, .004f)
            assertEquals("$mode x", 0f, g.body.x, 0f); assertEquals("$mode z", Arena.START_Z, g.body.z, 0f)
            assertEquals("$mode y", g.eyeHeight, g.body.y, 0f)
            assertFalse(g.body.stroking[0] || g.body.stroking[1])
        }
        // Hands resting on the sand: they hold on, and the body stays within the tremor itself.
        val g = game()
        tremble(g, 10f, ground(g), -.6f, .004f)
        assertTrue(g.body.touching[0] && g.body.touching[1])
        assertEquals(0f, g.body.x, .02f); assertEquals(Arena.START_Z, g.body.z, .02f)
        assertEquals(g.eyeHeight, g.body.y, .02f)
        hide(0); hide(1); g.frames(60)
        assertEquals(0f, speed(g), 0f)
    }

    // ------------------------------------------------------------ 2-5. pushing the ground

    @Test fun oneHandOnTheGroundPulledBackMovesTheBodyForward() {
        for (hand in 0..1) {
            val g = game()
            val left = hand == 0
            g.move(.2f, 0f, -.4f, -.7f, 0f, ground(g), -.7f, left, !left)
            assertTrue(g.body.touching[hand]); assertFalse(g.body.touching[1 - hand])
            // The hand comes 60 cm towards the body in 0.4 s: the body goes 60 cm forward.
            g.move(.4f, 0f, ground(g), -.7f, 0f, ground(g), -.1f, left, !left)
            assertEquals(Arena.START_Z - .6f, g.body.z, .08f)
            assertEquals(0f, g.body.x, .02f)
            assertEquals(g.eyeHeight, g.body.y, .02f)
            // It lets go: the body slides on a little and stops.
            hide(hand); g.frames(240)
            assertTrue("slid to ${g.body.z}", g.body.z < Arena.START_Z - .7f && g.body.z > Arena.START_Z - 2f)
            assertEquals(0f, speed(g), 0f)
        }
    }

    @Test fun twoHandsOnTheGroundPulledBackMoveTheBodyForwardAndNoFurtherThanTheHandsWent() {
        val g = game()
        g.move(.2f, 0f, -.4f, -.7f, 0f, ground(g), -.7f)
        assertTrue(g.body.touching[0] && g.body.touching[1])
        g.move(.4f, 0f, ground(g), -.7f, 0f, ground(g), -.1f)
        // Not twice as far for two hands: the body follows the mean of them.
        assertEquals(Arena.START_Z - .6f, g.body.z, .08f)
        // While held, the body is never faster than the hands were (1.5 m/s).
        assertTrue("speed ${speed(g)}", speed(g) < 1.7f)
    }

    @Test fun pullingToOneSideMovesTheBodyToTheOther() {
        for (mode in Locomotion.values()) {
            val g = game(mode)
            val y = if (mode == Locomotion.CONTACT) ground(g) else -.45f
            g.move(.2f, .3f, -.3f, -.6f, .3f, y, -.6f)
            // Hands go 60 cm to the left: the body goes to the right, and neither forward nor back.
            g.move(.4f, .3f, y, -.6f, -.3f, y, -.6f)
            hide(0); hide(1); g.frames(240)
            assertTrue("$mode: x ${g.body.x}", g.body.x > .45f)
            assertEquals("$mode z", Arena.START_Z, g.body.z, .05f)
            assertEquals("$mode y", g.eyeHeight, g.body.y, 1e-3f)
        }
    }

    // ------------------------------------------------------------ 6. jumping

    @Test fun pushingTheGroundDownQuicklyIsAJump() {
        val g = game()
        g.move(.15f, 0f, -.3f, -.5f, 0f, ground(g), -.5f)
        assertTrue(g.body.touching[0] && g.body.touching[1])
        // Hands go 45 cm down in 0.15 s (3 m/s): the body goes up as fast, then flies.
        g.move(.15f, 0f, ground(g), -.5f, 0f, ground(g) - .45f, -.5f)
        hide(0); hide(1)
        var top = 0f; var fastest = 0f
        for (f in 0 until 180) {
            g.frames(1)
            top = max(top, g.body.y); fastest = max(fastest, abs(g.body.vy))
        }
        assertTrue("top $top", top > g.eyeHeight + .7f)
        // Never faster than the limits, and back on the ground at the end.
        assertTrue("fastest $fastest", fastest <= GorillaBody.MAX_JUMP_SPEED + .01f && fastest <= GorillaBody.MAX_VERTICAL_SPEED)
        assertEquals(g.eyeHeight, g.body.y, 1e-4f); assertTrue(g.body.onGround)
    }

    @Test fun fluidJumpNeedsEveryFollowedHandAndWaitsBetweenJumps() {
        val g = game(Locomotion.FLUID)
        // Both hands go 30 cm down in 0.15 s (2 m/s; 0.8 m/s of the real hands).
        g.move(.3f, 0f, -.3f, -.6f, 0f, -.3f, -.6f)
        g.move(.15f, 0f, -.3f, -.6f, 0f, -.6f, -.6f)
        assertFalse("0.8 m/s of real hand is not yet a jump", g.body.y > g.eyeHeight)
        // 60 cm in 0.15 s (4 m/s; 1.6 m/s of the real hands): a jump.
        g.move(.3f, 0f, -.6f, -.6f, 0f, -.2f, -.6f)
        g.frames(30)
        g.move(.15f, 0f, -.2f, -.6f, 0f, -.8f, -.6f)
        assertTrue("jumped to ${g.body.y}", g.body.y > g.eyeHeight + .05f)
        assertTrue(g.body.vy <= GorillaBody.FLUID_MAX_JUMP_SPEED)
        var top = g.body.y
        for (f in 0 until 120) { g.frames(1); top = max(top, g.body.y) }
        assertTrue("top $top", top > g.eyeHeight + .5f && top < g.eyeHeight + 1.8f)
        assertEquals(g.eyeHeight, g.body.y, 1e-4f)

        // One hand alone goes down while the other is followed and still: no jump.
        val h = game(Locomotion.FLUID)
        place(h, 0, -.25f, -.3f, -.6f); place(h, 1, .25f, -.3f, -.6f); h.frames(20)
        h.move(.15f, 0f, -.2f, -.6f, 0f, -.8f, -.6f, left = false)
        assertEquals(h.eyeHeight, h.body.y, 0f)
        // With only one hand in view, that hand is enough.
        val one = game(Locomotion.FLUID)
        place(one, 1, .25f, -.2f, -.6f); one.frames(20)
        one.move(.15f, 0f, -.2f, -.6f, 0f, -.8f, -.6f, left = false)
        assertTrue(one.body.y > one.eyeHeight + .05f)
    }

    // ------------------------------------------------------------ the fluid way of moving

    @Test fun fluidStrokeBelowTheEyesRowsTheBodyAndLiftedHandComesBackFree() {
        val g = game(Locomotion.FLUID)
        place(g, 0, -.25f, -.45f, -.8f); place(g, 1, .25f, -.45f, -.8f); g.frames(20)
        assertEquals(Arena.START_Z, g.body.z, 0f)
        // Three strokes: back below the eyes, forward again at the level of the eyes.
        var fastest = 0f; var last = g.body.z
        repeat(3) {
            g.move(.3f, 0f, -.45f, -.8f, 0f, -.45f, -.2f)
            fastest = max(fastest, sqrt(g.body.vx * g.body.vx + g.body.vz * g.body.vz))
            assertTrue(g.body.z < last - .3f)
            val before = g.body.z
            g.move(.1f, 0f, -.45f, -.2f, 0f, .05f, -.2f)
            g.move(.3f, 0f, .05f, -.2f, 0f, .05f, -.8f)
            g.move(.1f, 0f, .05f, -.8f, 0f, -.45f, -.8f)
            // Coming back did not row the body back: it only went on, more slowly.
            assertTrue("went back from $before to ${g.body.z}", g.body.z <= before)
            last = g.body.z
        }
        assertTrue("three strokes took the body ${Arena.START_Z - g.body.z} m", Arena.START_Z - g.body.z > 2f)
        // The hands went at 2 m/s: the body was never faster than that.
        assertTrue("fastest $fastest", fastest <= 2.05f)
        assertEquals(0f, g.body.x, .03f); assertEquals(g.eyeHeight, g.body.y, 1e-3f)
        // Left alone it stops.
        g.frames(300)
        assertEquals(0f, speed(g), 0f)
    }

    @Test fun fluidStrokeSpeedsTheBodyUpBitByBit() {
        val g = game(Locomotion.FLUID)
        place(g, 0, -.25f, -.45f, -.8f); place(g, 1, .25f, -.45f, -.8f); g.frames(20)
        var previous = 0f
        for (f in 0 until 12) {
            val t = (f + 1) / 60f
            place(g, 0, -.25f, -.45f, -.8f + 3f * t); place(g, 1, .25f, -.45f, -.8f + 3f * t)
            g.frames(1)
            val now = -g.body.vz
            // Never more than the acceleration allows in one frame, and never a jump in speed.
            assertTrue("frame $f: from $previous to $now", now - previous <= GorillaBody.FLUID_ACCELERATION / 60f + .02f)
            previous = now
        }
        assertTrue(previous > 1.5f)
    }

    @Test fun onlyOneWayOfMovingActsAtATime() {
        // Fluid: hands on the sand hold on to nothing, and a pull along it is one push, not two.
        val g = game(Locomotion.FLUID)
        g.move(.2f, 0f, -.4f, -.7f, 0f, ground(g), -.7f)
        assertFalse(g.body.touching[0] || g.body.touching[1])
        g.move(.4f, 0f, ground(g), -.7f, 0f, ground(g), -.1f)
        assertFalse(g.body.touching[0] || g.body.touching[1])
        assertTrue("speed ${-g.body.vz}", -g.body.vz <= 1.55f)
        // Contact: strokes in the air move nothing.
        val c = game(Locomotion.CONTACT)
        c.move(.3f, 0f, -.45f, -.8f, 0f, -.45f, -.2f)
        assertFalse(c.body.stroking[0] || c.body.stroking[1])
        assertEquals(Arena.START_Z, c.body.z, 0f)
        // Changing the way of moving in the middle of a hold lets go and gives no push.
        val s = game(Locomotion.CONTACT)
        s.move(.2f, 0f, -.4f, -.7f, 0f, ground(s), -.7f)
        s.move(.2f, 0f, ground(s), -.7f, 0f, ground(s), -.4f)
        val before = speed(s)
        s.body.mode = Locomotion.FLUID
        assertFalse(s.body.touching[0] || s.body.touching[1])
        s.frames(1)
        assertTrue(speed(s) <= before + .01f)
    }

    // ------------------------------------------------------------ 8-10. hands that vanish and come back

    @Test fun handThatIsOnlyHeldInPlaceMovesNothingAndHoldsNothing() {
        for (mode in Locomotion.values()) {
            val g = game(mode)
            val y = if (mode == Locomotion.CONTACT) ground(g) else -.45f
            // A pose that is only kept from the last reading: drawn, on the sand, and moved about.
            for (f in 0 until 60) {
                val z = -.7f + .5f * sin(f * .3f)
                place(g, 0, -.25f, y, z, live = false); place(g, 1, .25f, y, z, live = false)
                g.frames(1)
            }
            assertTrue(g.handPresent[0] && g.handPresent[1])
            assertFalse(g.handLive[0] || g.handLive[1])
            assertFalse(g.body.touching[0] || g.body.touching[1] || g.body.stroking[0] || g.body.stroking[1])
            assertEquals("$mode", Arena.START_Z, g.body.z, 0f); assertEquals(g.eyeHeight, g.body.y, 0f)
        }
    }

    @Test fun oneHandLostInTheMiddleOfAPullGivesNoPushAndTheOtherGoesOn() {
        val g = game()
        g.move(.2f, 0f, -.4f, -.7f, 0f, ground(g), -.7f)
        g.move(.2f, 0f, ground(g), -.7f, 0f, ground(g), -.4f)
        assertTrue(g.body.touching[0] && g.body.touching[1])
        val before = speed(g)
        // The left hand is no longer followed: its pose stays where it was, the right one pulls on.
        for (f in 1..12) {
            place(g, 0, -.25f, ground(g), -.4f + (Arena.START_Z - .3f - g.body.z) * 0f, live = false)
            place(g, 1, .25f, ground(g), -.4f + .3f * f / 12f)
            g.frames(1)
            assertFalse(g.body.touching[0]); assertTrue(g.body.touching[1])
            assertTrue("frame $f: ${speed(g)} after $before", speed(g) <= before + .15f)
        }
        // The body went on with the right hand alone: 60 cm in all.
        assertEquals(Arena.START_Z - .6f, g.body.z, .08f)
    }

    @Test fun bothHandsLostInTheMiddleOfAPullLeaveTheBodyToSlideAndStop() {
        for (vanish in 0..1) {
            val g = game()
            g.move(.2f, 0f, -.4f, -.7f, 0f, ground(g), -.7f)
            g.move(.2f, 0f, ground(g), -.7f, 0f, ground(g), -.4f)
            val before = speed(g)
            assertTrue(before > 1f)
            // Either the poses are kept but no longer followed, or the hands are gone altogether.
            if (vanish == 0) { place(g, 0, -.25f, ground(g), -.4f, live = false); place(g, 1, .25f, ground(g), -.4f, live = false) }
            else { hide(0); hide(1) }
            var fastest = 0f
            for (f in 0 until 300) { g.frames(1); fastest = max(fastest, speed(g)) }
            // Losing the hands never makes the body faster than the hands had made it.
            assertTrue("case $vanish: $fastest after $before", fastest <= before * (if (vanish == 0) 1.001f else 1.11f))
            assertEquals(0f, speed(g), 0f)
            assertEquals(g.eyeHeight, g.body.y, 1e-4f)
            assertTrue(g.body.z < Arena.START_Z - .3f && g.body.z > Arena.START_Z - 2.5f)
        }
    }

    @Test fun handThatComesBackSomewhereElseIsNotTakenForAHandThatMoved() {
        for (mode in Locomotion.values()) {
            val g = game(mode)
            val y = if (mode == Locomotion.CONTACT) ground(g) else -.45f
            place(g, 0, -.25f, y, -.7f); place(g, 1, .25f, y, -.7f); g.frames(30)
            // Lost for half a second...
            hide(0); hide(1); g.frames(30)
            val x = g.body.x; val z = g.body.z
            // ...and found again 60 cm away, where they stay.
            place(g, 0, -.25f, y, -.1f); place(g, 1, .25f, y, -.1f); g.frames(60)
            assertEquals("$mode x", x, g.body.x, .01f); assertEquals("$mode z", z, g.body.z, .01f)
            assertEquals("$mode speed", 0f, speed(g), .01f)
        }
    }

    @Test fun readingThatJumpsFurtherThanAHandCanMoveThrowsNobody() {
        for (mode in Locomotion.values()) {
            val g = game(mode)
            val y = if (mode == Locomotion.CONTACT) ground(g) else -.45f
            place(g, 0, -.25f, y, -.8f); place(g, 1, .25f, y, -.8f); g.frames(30)
            // From one frame to the next both hands are 70 cm nearer and 60 cm lower: 55 m/s.
            // No hand does that; a wrong reading does.
            place(g, 0, -.25f, y - .6f, -.1f); place(g, 1, .25f, y - .6f, -.1f)
            var fastest = 0f
            for (f in 0 until 120) { g.frames(1); fastest = max(fastest, speed(g)) }
            assertTrue("$mode rejected ${g.body.rejected}", g.body.rejected > 0)
            assertEquals("$mode z", Arena.START_Z, g.body.z, .03f)
            assertEquals("$mode y", g.eyeHeight, g.body.y, .03f)
            assertTrue("$mode fastest $fastest", fastest < .5f)
        }
    }

    // ------------------------------------------------------------ 12. fast hands

    @Test fun fastestPullThereIsStaysWithinTheLimits() {
        for (mode in Locomotion.values()) {
            val g = game(mode)
            val y = if (mode == Locomotion.CONTACT) ground(g) else -.45f
            g.move(.2f, 0f, -.3f, -1.2f, 0f, y, -1.2f)
            // 1.3 m in 0.08 s: 16 m/s, a hand at 6.5 m/s, about as fast as an arm goes.
            g.move(.08f, 0f, y, -1.2f, 0f, y, .1f)
            hide(0); hide(1)
            var fastest = 0f
            for (f in 0 until 300) { g.frames(1); fastest = max(fastest, speed(g)) }
            val limit = if (mode == Locomotion.CONTACT) GorillaBody.MAX_JUMP_SPEED else GorillaBody.FLUID_MAX_SPEED
            assertTrue("$mode fastest $fastest", fastest <= limit + .01f)
            assertTrue("$mode moved to ${g.body.z}", g.body.z < Arena.START_Z - .5f)
            // Still inside the arena, on the sand, at rest.
            assertTrue(sqrt(g.body.x * g.body.x + g.body.z * g.body.z) <= Arena.RADIUS - GorillaBody.BODY_RADIUS + .01f)
            assertEquals(g.eyeHeight, g.body.y, 1e-4f); assertEquals(0f, speed(g), 0f)
        }
    }

    @Test fun fastHandIsTestedAllAlongItsWayBetweenTwoFrames() {
        val g = game()
        val d = g.dummies[0]
        // Beside a dummy, at its chest height, at 20 frames a second.
        g.body.reset(d.x - .45f, g.eyeHeight, d.z + .9f)
        val y = .2f
        place(g, 1, .45f, y, -.4f); g.frames(4, .05f)
        assertFalse(g.body.touching[1])
        // In one frame the hand goes from 50 cm in front of the dummy to 30 cm behind it. At
        // neither end does it touch; on the way it does.
        place(g, 1, .45f, y, -1.2f); g.frames(1, .05f)
        assertTrue("held on the dummy", g.body.touching[1])
        val dx = g.palm[3] - d.x; val dz = g.palm[5] - d.z
        assertEquals(Dummy.RADIUS + g.body.handRadius, sqrt(dx * dx + dz * dz), .05f)
        assertTrue("on the near side", g.palm[5] > d.z)
    }

    // ------------------------------------------------------------ 13. things in the way

    @Test fun fluidRowingStopsAtTheWallAndAtADummy() {
        // Towards the wall behind the gate, stroke after stroke.
        val g = game(Locomotion.FLUID)
        g.body.reset(0f, g.eyeHeight, 11f)
        repeat(8) {
            g.move(.3f, 0f, -.45f, -.2f, 0f, -.45f, -.8f)
            g.move(.1f, 0f, -.45f, -.8f, 0f, .05f, -.8f)
            g.move(.3f, 0f, .05f, -.8f, 0f, .05f, -.2f)
            g.move(.1f, 0f, .05f, -.2f, 0f, -.45f, -.2f)
            assertTrue(g.body.z <= Arena.RADIUS - GorillaBody.BODY_RADIUS + .01f)
        }
        hide(0); hide(1); g.frames(120)
        assertEquals(Arena.RADIUS - GorillaBody.BODY_RADIUS, g.body.z, .02f)
        assertEquals(g.eyeHeight, g.body.y, 1e-4f)

        // Into a dummy: the body goes round it or stops, never through it.
        val h = game(Locomotion.FLUID)
        val d = h.dummies[0]
        h.body.reset(d.x, h.eyeHeight, d.z + 2f)
        repeat(8) {
            h.move(.3f, 0f, -.45f, -.8f, 0f, -.45f, -.2f)
            h.move(.1f, 0f, -.45f, -.2f, 0f, .05f, -.2f)
            h.move(.3f, 0f, .05f, -.2f, 0f, .05f, -.8f)
            h.move(.1f, 0f, .05f, -.8f, 0f, -.45f, -.8f)
            val dx = h.body.x - d.x; val dz = h.body.z - d.z
            assertTrue(sqrt(dx * dx + dz * dz) >= Dummy.RADIUS + GorillaBody.HEAD_RADIUS - .01f)
        }
    }

    @Test fun headDoesNotGoIntoAThingTheChestClears() {
        // Hanging from the top of the wall with the chest below the edge: the head is level with
        // the wall and must stay out of it as the chest does.
        val g = game()
        g.body.reset(0f, Arena.WALL_HEIGHT - .1f, -(Arena.RADIUS - .05f))
        g.frames(1)
        assertTrue("head at ${g.body.z}", g.body.z >= -(Arena.RADIUS - GorillaBody.HEAD_RADIUS) - .01f)
    }

    // ------------------------------------------------------------ the size of the body

    @Test fun heightOfTheEyesCanBeSetAndTheBodyStandsAtIt() {
        for (height in floatArrayOf(.5f, .65f, .8f, 1.1f, 1.3f)) {
            val g = game()
            g.eyeHeight = height
            assertEquals(height, g.eyeHeight, 0f)
            assertEquals("the eyes go with the body at once", height, g.body.y, 1e-5f)
            g.frames(120)
            // The floor does not push a small body up, nor does a tall one sink.
            assertEquals(height, g.body.y, 1e-5f); assertTrue(g.body.onGround)
            g.restart()
            assertEquals(height, g.body.y, 0f)
            // The hands still find the sand at that height.
            g.move(.2f, 0f, -.3f, -.6f, 0f, ground(g), -.6f)
            assertTrue("height $height", g.body.touching[0] && g.body.touching[1])
            assertEquals(g.body.handRadius, g.palm[1], .01f)
        }
        // Beyond what a body can be, the nearest that it can be.
        val g = game()
        g.eyeHeight = 5f; assertEquals(GorillaBody.MAX_EYE_HEIGHT, g.eyeHeight, 0f)
        g.eyeHeight = 0f; assertEquals(GorillaBody.MIN_EYE_HEIGHT, g.eyeHeight, 0f)
    }

    @Test fun reachOfTheHandsCanBeSetAndTheHandsAreAsBigAsTheyReach() {
        val real = floatArrayOf(.12f, -.20f, -.40f)
        for (reach in floatArrayOf(1.2f, 1.8f, 2.5f, 3.2f)) {
            val g = game()
            g.reach = reach
            // The real hand is 40 cm in front of the eyes, 20 cm below and 12 cm to the right.
            val h = hands[1]; h.present = true; h.live = true
            for (k in 0 until HAND_POINTS) for (c in 0..2) h.joints[k * 3 + c] = real[c] + flat[k * 3 + c] - palm[c]
            g.frames(2)
            assertEquals(real[0] * reach, g.palm[3] - g.body.x, 1e-4f)
            assertEquals(real[1] * reach, g.palm[4] - g.body.y, 1e-4f)
            assertEquals(real[2] * reach, g.palm[5] - g.body.z, 1e-4f)
            // The hand itself: the distance between two of its points grows with the reach.
            val j = g.handJoints[1]
            val across = sqrt((j[0] - j[12]) * (j[0] - j[12]) + (j[1] - j[13]) * (j[1] - j[13]) + (j[2] - j[14]) * (j[2] - j[14]))
            val model = sqrt((flat[0] - flat[12]) * (flat[0] - flat[12]) + (flat[1] - flat[13]) * (flat[1] - flat[13]))
            assertEquals(model * reach, across, 1e-4f)
            assertEquals(.036f * reach, g.body.handRadius, 1e-5f)
            // The arm is as long as the hands reach, and still joined at shoulder, elbow and wrist.
            val bone = ArmPose.BONE * reach / Coliseum.ARM_SCALE * g.armStretch[1]
            val ex = g.elbow[3]; val ey = g.elbow[4]; val ez = g.elbow[5]
            assertEquals(bone, sqrt((ex - g.shoulder[3]) * (ex - g.shoulder[3]) + (ey - g.shoulder[4]) * (ey - g.shoulder[4]) + (ez - g.shoulder[5]) * (ez - g.shoulder[5])), 1e-3f)
            assertEquals(bone, sqrt((ex - j[0]) * (ex - j[0]) + (ey - j[1]) * (ey - j[1]) + (ez - j[2]) * (ez - j[2])), 1e-3f)
        }
    }

    // ------------------------------------------------------------ the head turns while a reading is on its way

    @Test fun turnOfTheHeadIsTheSameAsTheCameraOfTheArena() {
        val c = ArenaCamera(); val m = FloatArray(9)
        val looks = floatArrayOf(0f, 0f, 0f, .7f, -.4f, .2f, -2.5f, 1.1f, -.6f, 3.0f, -1.2f, 1.0f)
        for (n in 0 until looks.size / 3) {
            val yaw = looks[n * 3]; val pitch = looks[n * 3 + 1]; val roll = looks[n * 3 + 2]
            c.place(0f, 0f, 0f, yaw, pitch, roll)
            Look.cameraToWorld(yaw, pitch, roll, m)
            val v = floatArrayOf(.3f, -.2f, -.9f)
            assertEquals(c.worldX(v[0], v[1], v[2]), m[0] * v[0] + m[1] * v[1] + m[2] * v[2], 1e-5f)
            assertEquals(c.worldY(v[0], v[1], v[2]), m[3] * v[0] + m[4] * v[1] + m[5] * v[2], 1e-5f)
            assertEquals(c.worldZ(v[0], v[1], v[2]), m[6] * v[0] + m[7] * v[1] + m[8] * v[2], 1e-5f)
        }
    }

    @Test fun historyOfTheHeadGivesTheTurnAtAnyMomentOfTheLastSecond() {
        val h = LookHistory(); val out = FloatArray(3)
        assertFalse(h.at(5L, out))
        // 60 entries a second for two seconds; the yaw goes through the place where it wraps round.
        for (i in 0 until 120) h.add(i * 16_000_000L, Look.angleDifference(3f + i * .01f, 0f), -.2f + i * .001f, .1f)
        // Between two entries.
        assertTrue(h.at(100 * 16_000_000L + 8_000_000L, out))
        assertEquals(0f, Look.angleDifference(out[0], 3f + 100.5f * .01f), 1e-4f)
        assertEquals(-.2f + 100.5f * .001f, out[1], 1e-5f); assertEquals(.1f, out[2], 1e-6f)
        // Exactly on one, later than the newest, and earlier than the oldest that is kept.
        assertTrue(h.at(50 * 16_000_000L, out)); assertEquals(0f, Look.angleDifference(out[0], 3.5f), 1e-4f)
        assertTrue(h.at(Long.MAX_VALUE / 2, out)); assertEquals(0f, Look.angleDifference(out[0], 3f + 119 * .01f), 1e-4f)
        assertTrue(h.at(-5L, out)); assertEquals(0f, Look.angleDifference(out[0], 3f), 1e-4f)
        h.clear()
        assertFalse(h.at(0L, out))
    }

    /** What the tracker reports for a flat hand with its palm at ([x], [y], [z]) in camera space. */
    private fun reading(x: Float, y: Float, z: Float): HandReading {
        val aspect = 4f / 3f; val focal = .77f
        val r = HandReading(); r.present = true
        // A hand of a real size: 9 cm from the wrist to the knuckles.
        for (i in 0 until HAND_POINTS) {
            val sx = (i % 5 - 2) * .02f; val sy = (i / 5) * .03f
            val px = x + sx; val py = y + sy
            r.image[i * 2] = .5f + px / -z * focal
            r.image[i * 2 + 1] = .5f - py / -z * focal * aspect
            r.world[i * 3] = sx; r.world[i * 3 + 1] = sy; r.world[i * 3 + 2] = 0f
        }
        return r
    }

    private fun palmOf(j: FloatArray, out: FloatArray) {
        out.fill(0f)
        for (i in HandGeometry.PALM) { out[0] += j[i * 3]; out[1] += j[i * 3 + 1]; out[2] += j[i * 3 + 2] }
        for (k in 0..2) out[k] = out[k] / HandGeometry.PALM.size.toFloat()
    }

    /**
     * A hand that stays still in the room while the head turns from side to side, on a phone that
     * gives 6 readings a second, each 200 ms old when it arrives. Returns the mean and the worst
     * distance, in the view, between where the hand is shown and where it really is.
     */
    private fun turningHead(steadied: Boolean): FloatArray {
        val rig = HandRig(); val history = LookHistory()
        val then = FloatArray(9); val nowTurn = FloatArray(9); val look = FloatArray(3); val shown = FloatArray(3)
        // Where the palm is in the room, from the eyes: 40 cm ahead, a little down.
        val wx = 0f; val wy = -.08f; val wz = -.40f
        var sum = 0f; var worst = 0f; var n = 0
        var nextReading = 0f
        val pending = ArrayList<FloatArray>()
        // The middle of the palm of the hand that [reading] makes, from the point it is placed by.
        var ox = 0f; var oy = 0f
        for (i in HandGeometry.PALM) { ox += (i % 5 - 2) * .02f; oy += (i / 5) * .03f }
        ox = ox / HandGeometry.PALM.size.toFloat(); oy = oy / HandGeometry.PALM.size.toFloat()
        for (f in 0 until 60 * 6) {
            val t = f / 60f; val nowNs = 1_000_000_000L + (t * 1e9f).toLong()
            // The head turns 35 degrees to each side, once every two seconds.
            val yaw = .6f * sin(t * 3.1416f); val pitch = -.3f
            history.add(nowNs, yaw, pitch, 0f)
            Look.cameraToWorld(yaw, pitch, 0f, nowTurn)
            if (t >= nextReading) {
                nextReading += 1f / 6f
                // The picture is taken now: the hand as the camera sees it at this turn of the head.
                val cx = nowTurn[0] * wx + nowTurn[3] * wy + nowTurn[6] * wz
                val cy = nowTurn[1] * wx + nowTurn[4] * wy + nowTurn[7] * wz
                val cz = nowTurn[2] * wx + nowTurn[5] * wy + nowTurn[8] * wz
                pending.add(floatArrayOf(t + .2f, t, cx, cy, cz))
            }
            while (pending.isNotEmpty() && pending[0][0] <= t) {
                val p = pending.removeAt(0)
                val takenNs = 1_000_000_000L + (p[1] * 1e9f).toLong()
                var turn: FloatArray? = null
                if (steadied) { assertTrue(history.at(takenNs, look)); Look.cameraToWorld(look[0], look[1], look[2], then); turn = then }
                // The tracker stamps the reading 30 ms after the picture; the age it measures says so.
                rig.accept(reading(p[2], p[3], p[4]), takenNs + 30_000_000L, 4f / 3f, .77f, turn)
            }
            rig.advance(nowNs, 1f / 60f)
            if (rig.state == HandRig.State.LOST || t < 1f) continue
            palmOf(rig.joints, shown)
            if (steadied) {
                // Back to the axes of the camera as the head is turned now.
                val x = shown[0]; val y = shown[1]; val z = shown[2]
                shown[0] = nowTurn[0] * x + nowTurn[3] * y + nowTurn[6] * z
                shown[1] = nowTurn[1] * x + nowTurn[4] * y + nowTurn[7] * z
                shown[2] = nowTurn[2] * x + nowTurn[5] * y + nowTurn[8] * z
            }
            val tx = nowTurn[0] * wx + nowTurn[3] * wy + nowTurn[6] * wz + ox
            val ty = nowTurn[1] * wx + nowTurn[4] * wy + nowTurn[7] * wz + oy
            val tz = nowTurn[2] * wx + nowTurn[5] * wy + nowTurn[8] * wz
            val e = sqrt((shown[0] - tx) * (shown[0] - tx) + (shown[1] - ty) * (shown[1] - ty) + (shown[2] - tz) * (shown[2] - tz))
            sum += e; worst = max(worst, e); n++
        }
        assertTrue(n > 200)
        return floatArrayOf(sum / n, worst)
    }

    @Test fun handThatIsStillInTheRoomStaysStillWhileTheHeadTurns() {
        val carried = turningHead(steadied = false)
        val steadied = turningHead(steadied = true)
        // Without the turn of the head, the hand is carried along with the view until the next
        // reading: centimetres off, at 40 cm from the eyes.
        assertTrue("carried along: mean ${carried[0]} worst ${carried[1]}", carried[0] > .04f && carried[1] > .10f)
        // With it, the hand stays where it is in the room.
        assertTrue("steadied: mean ${steadied[0]} worst ${steadied[1]}", steadied[0] < .012f && steadied[1] < .03f)
    }
}
