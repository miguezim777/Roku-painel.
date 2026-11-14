package com.minipots.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

class ColiseumTest {
    private val pi = PI.toFloat()
    private val dt = 1f / 60f
    private val none = FloatArray(0)

    // ------------------------------------------------------------ looking around

    @Test fun phoneHeldUprightLooksAtTheHorizon() {
        val out = FloatArray(2)
        // Device x east, y up, z south: the back of the phone points north.
        Look.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f), out)
        assertEquals(0f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f)
        // Turned a quarter to the right: device x south, y up, z west.
        Look.fromRotationMatrix(floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f), out)
        assertEquals(pi / 2f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f)
    }

    @Test fun phoneTiltedDownLooksDownTheSameWay() {
        val out = FloatArray(2)
        val h = sqrt(.5f)
        // Facing north and 45 degrees down.
        Look.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, h, -h, 0f, h, h), out)
        assertEquals(0f, out[0], 1e-5f); assertEquals(-pi / 4f, out[1], 1e-5f)
        // Flat on a table, top edge to the north: straight down, heading still north.
        Look.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), out)
        assertEquals(0f, out[0], 1e-5f); assertEquals(-pi / 2f, out[1], 1e-4f)
    }

    @Test fun tiltIsReadFromGravityWithoutAGyroscope() {
        assertEquals(0f, Look.pitchFromGravity(0f, 9.81f, 0f), 1e-5f)
        assertEquals(-pi / 2f, Look.pitchFromGravity(0f, 0f, 9.81f), 1e-4f)
        assertEquals(-pi / 4f, Look.pitchFromGravity(0f, 6.9367f, 6.9367f), 1e-4f)
        assertEquals(pi / 4f, Look.pitchFromGravity(0f, 6.9367f, -6.9367f), 1e-4f)
        // Sideways tilt does not look up or down.
        assertEquals(0f, Look.pitchFromGravity(6.9367f, 6.9367f, 0f), 1e-5f)
        assertEquals(0f, Look.pitchFromGravity(0f, 0f, 0f), 0f)
    }

    @Test fun gyroscopeViewFollowsThePhoneFromWhereItStarted() {
        val c = LookControl()
        c.update(LookMode.GYROSCOPE, 2f, -.1f, true)
        assertEquals(0f, c.yaw, 1e-5f); assertEquals(-.1f, c.pitch, 1e-5f)
        c.update(LookMode.GYROSCOPE, 2.5f, .2f, true)
        assertEquals(.5f, c.yaw, 1e-5f); assertEquals(.2f, c.pitch, 1e-5f)
        // The finger adds a turn on top: dragging right turns left.
        c.drag(.5f, 0f)
        c.update(LookMode.GYROSCOPE, 2.5f, .2f, true)
        assertEquals(.5f - .5f * LookControl.DRAG_TURN, c.yaw, 1e-5f)
        // Across the wrap of the angle the turn stays small.
        val d = LookControl()
        d.update(LookMode.GYROSCOPE, 3f, 0f, true)
        d.update(LookMode.GYROSCOPE, -3f, 0f, true)
        assertEquals(2f * pi - 6f, d.yaw, 1e-4f)
    }

    @Test fun fingerLooksAroundWithoutAnySensor() {
        val c = LookControl()
        c.update(LookMode.TOUCH, 0f, 0f, false)
        assertEquals(0f, c.yaw, 0f); assertEquals(LookControl.START_PITCH, c.pitch, 0f)
        c.drag(-.25f, .1f)
        c.update(LookMode.TOUCH, 0f, 0f, false)
        assertEquals(.25f * LookControl.DRAG_TURN, c.yaw, 1e-5f)
        assertEquals(LookControl.START_PITCH + .1f * LookControl.DRAG_TURN, c.pitch, 1e-5f)
        // Looking up or down stops before straight up or down.
        repeat(20) { c.drag(0f, 1f) }
        c.update(LookMode.TOUCH, 0f, 0f, false)
        assertEquals(LookControl.MAX_PITCH, c.pitch, 1e-5f)
    }

    @Test fun tiltModeTakesUpAndDownFromThePhoneAndTurningFromTheFinger() {
        val c = LookControl()
        c.update(LookMode.TILT, 0f, -.6f, true)
        assertEquals(0f, c.yaw, 0f); assertEquals(-.6f, c.pitch, 1e-5f)
        c.drag(-.5f, .4f)
        c.update(LookMode.TILT, 0f, -.2f, true)
        assertEquals(.5f * LookControl.DRAG_TURN, c.yaw, 1e-5f); assertEquals(-.2f, c.pitch, 1e-5f)
        // Until the sensor has answered, the finger does everything.
        val waiting = LookControl()
        waiting.update(LookMode.TILT, 0f, 1f, false)
        assertEquals(LookControl.START_PITCH, waiting.pitch, 0f)
    }

    @Test fun changingTheWayOfLookingKeepsTheView() {
        val c = LookControl()
        c.update(LookMode.GYROSCOPE, 1f, 0f, true)
        c.update(LookMode.GYROSCOPE, 1.7f, .3f, true)
        c.update(LookMode.TOUCH, 1.7f, .3f, true)
        assertEquals(.7f, c.yaw, 1e-5f); assertEquals(.3f, c.pitch, 1e-5f)
        c.update(LookMode.GYROSCOPE, 1.7f, .3f, true)
        assertEquals(.7f, c.yaw, 1e-5f)
        c.recentre()
        c.update(LookMode.GYROSCOPE, 1.7f, .3f, true)
        assertEquals(0f, c.yaw, 1e-5f)
    }

    // ------------------------------------------------------------ camera and placement

    @Test fun cameraLooksWhereYawAndPitchSay() {
        val c = ArenaCamera()
        c.setAspect(.5f)
        c.place(1f, 2f, 3f, 0f, 0f)
        assertEquals(-1f, c.worldZ(0f, 0f, -1f), 1e-5f)
        assertEquals(1f, c.worldX(1f, 0f, 0f), 1e-5f)
        assertEquals(1f, c.worldY(0f, 1f, 0f), 1e-5f)
        // A point straight ahead lands in the middle of the picture, in front of the camera.
        val m = c.viewProjection
        fun clip(row: Int, x: Float, y: Float, z: Float) = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row]
        assertEquals(0f, clip(0, 1f, 2f, -4f), 1e-4f); assertEquals(0f, clip(1, 1f, 2f, -4f), 1e-4f)
        assertEquals(7f, clip(3, 1f, 2f, -4f), 1e-4f)
        // Something to the right of it lands to the right, something above it lands above.
        assertTrue(clip(0, 2f, 2f, -4f) > 0f); assertTrue(clip(1, 1f, 3f, -4f) > 0f)

        c.place(0f, 0f, 0f, pi / 2f, 0f)
        assertEquals(1f, c.worldX(0f, 0f, -1f), 1e-5f); assertEquals(0f, c.worldZ(0f, 0f, -1f), 1e-5f)
        assertEquals(1f, c.worldZ(1f, 0f, 0f), 1e-5f)
        c.place(0f, 0f, 0f, 0f, -.5f)
        assertTrue(c.worldY(0f, 0f, -1f) < -.4f)
        // The three axes stay square to each other.
        val dot = c.worldX(0f, 1f, 0f) * c.worldX(0f, 0f, -1f) + c.worldY(0f, 1f, 0f) * c.worldY(0f, 0f, -1f) +
            c.worldZ(0f, 1f, 0f) * c.worldZ(0f, 0f, -1f)
        assertEquals(0f, dot, 1e-5f)
    }

    @Test fun cylinderIsStretchedBetweenTwoPoints() {
        val m = FloatArray(16)
        for (end in arrayOf(floatArrayOf(4f, 6f, 3f), floatArrayOf(1f, 9f, 3f), floatArrayOf(1f, -2f, 3f), floatArrayOf(-5f, 2f, 3f))) {
            Place.between(m, 1f, 2f, 3f, end[0], end[1], end[2], .25f)
            // The foot of the unit cylinder is the first point, its top the second.
            assertEquals(1f, m[12], 1e-5f); assertEquals(2f, m[13], 1e-5f); assertEquals(3f, m[14], 1e-5f)
            assertEquals(end[0], m[4] + m[12], 1e-4f); assertEquals(end[1], m[5] + m[13], 1e-4f); assertEquals(end[2], m[6] + m[14], 1e-4f)
            // Its two radii have the asked length and are square to the axis and to each other.
            assertEquals(.25f, sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]), 1e-5f)
            assertEquals(.25f, sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]), 1e-5f)
            assertEquals(0f, m[0] * m[4] + m[1] * m[5] + m[2] * m[6], 1e-4f)
            assertEquals(0f, m[8] * m[4] + m[9] * m[5] + m[10] * m[6], 1e-4f)
            assertEquals(0f, m[0] * m[8] + m[1] * m[9] + m[2] * m[10], 1e-5f)
        }
        // Two points in the same place still give a usable matrix.
        Place.between(m, 1f, 2f, 3f, 1f, 2f, 3f, .25f)
        for (value in m) assertFalse(value.isNaN())
    }

    @Test fun leaningFrameStandsUprightWithoutLean() {
        val m = FloatArray(16)
        Place.leaning(m, 2f, -3f, 0f, 0f, 1f)
        val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 2f, 0f, -3f, 1f)
        for (i in 0 until 16) assertEquals(upright[i], m[i], 1e-5f)
        // Leaning tips the top the way of the lean and keeps the axes square and unit length.
        Place.leaning(m, 0f, 0f, .4f, -.2f, 1f)
        assertTrue(m[4] > .3f); assertTrue(m[6] < -.15f); assertTrue(m[5] > .8f)
        for (c in 0..2) assertEquals(1f, sqrt(m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2]), 1e-4f)
        assertEquals(0f, m[0] * m[4] + m[1] * m[5] + m[2] * m[6], 1e-4f)
        assertEquals(0f, m[8] * m[4] + m[9] * m[5] + m[10] * m[6], 1e-4f)
    }

    // ------------------------------------------------------------ shape of the hand

    @Test fun landmarksAreTurnedToTheUprightPicture() {
        val out = FloatArray(3)
        // Buffer already upright: x right, y down, z away become x right, y up, z towards the viewer.
        HandShape.toCameraSpace(0, false, 1f, 2f, 3f, out, 0)
        assertEquals(1f, out[0], 0f); assertEquals(-2f, out[1], 0f); assertEquals(-3f, out[2], 0f)
        // Buffer that needs a quarter turn clockwise: its x axis points down the picture.
        HandShape.toCameraSpace(90, false, 1f, 0f, 0f, out, 0)
        assertEquals(0f, out[0], 1e-6f); assertEquals(-1f, out[1], 1e-6f)
        HandShape.toCameraSpace(90, false, 0f, 1f, 0f, out, 0)
        assertEquals(-1f, out[0], 1e-6f); assertEquals(0f, out[1], 1e-6f)
        HandShape.toCameraSpace(270, false, 1f, 0f, 0f, out, 0)
        assertEquals(0f, out[0], 1e-6f); assertEquals(1f, out[1], 1e-6f)
        // The front camera is a mirror: left and right swap, nothing else.
        HandShape.toCameraSpace(0, true, 1f, 2f, 3f, out, 0)
        assertEquals(-1f, out[0], 0f); assertEquals(-2f, out[1], 0f); assertEquals(-3f, out[2], 0f)
        // Turning never changes a length.
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            HandShape.toCameraSpace(rotation, rotation == 180, .3f, -.4f, 1.2f, out, 0)
            assertEquals(1.3f, sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]), 1e-5f)
        }
    }

    /** A hand with the wrist at the origin, knuckles 9 cm up and finger tips where [tip] says. */
    private fun handWithTips(tipUp: Float, tipForward: Float): FloatArray {
        val p = FloatArray(HAND_POINTS * 3)
        for (finger in 0..3) {
            val knuckle = 5 + finger * 4
            val x = (finger - 1.5f) * .02f
            p[knuckle * 3] = x; p[knuckle * 3 + 1] = .09f
            p[(knuckle + 3) * 3] = x; p[(knuckle + 3) * 3 + 1] = tipUp; p[(knuckle + 3) * 3 + 2] = tipForward
        }
        return p
    }

    @Test fun fistIsToldFromAnOpenHand() {
        val open = HandShape.closedRatio(handWithTips(.18f, 0f))
        val fist = HandShape.closedRatio(handWithTips(.06f, .03f))
        assertTrue(open > HandShape.OPEN_ABOVE)
        assertTrue(fist < HandShape.CLOSED_BELOW)
        // The same detector as the pinch: clear readings switch at once, doubtful ones need two.
        val d = PinchDetector(HandShape.CLOSED_BELOW, HandShape.OPEN_ABOVE)
        assertFalse(d.update(open))
        assertTrue(d.update(fist))
        assertTrue(d.update(1.35f))
        assertTrue(d.update(1.6f))
        assertFalse(d.update(1.6f))
    }

    // ------------------------------------------------------------ moving like Gorilla Tag

    private fun player() = ArenaPlayer().also { it.reset(0f, 9f) }

    @Test fun playerAtRestStaysWhereItIs() {
        val p = player()
        repeat(120) { p.update(dt, false, 0f, 0f, 0f, none, 0) }
        assertEquals(0f, p.x, 0f); assertEquals(ArenaPlayer.EYE_HEIGHT, p.y, 0f); assertEquals(9f, p.z, 0f)
        // A hand in the air does not move the body either.
        repeat(120) { p.update(dt, true, .3f * (it % 7), -.2f, -1f, none, 0) }
        assertEquals(0f, p.x, 0f); assertEquals(9f, p.z, 0f)
        assertFalse(p.touching)
    }

    @Test fun pullingThePlantedHandBackMovesTheBodyForward() {
        val p = player()
        // The hand reaches the ground 1.2 m ahead and is pulled 0.6 m towards the body.
        for (i in 0 until 30) p.update(dt, true, .1f, -.80f, -1.2f + .6f * i / 29f, none, 0)
        assertTrue(p.touching)
        assertEquals(8.44f, p.z, .06f)
        assertEquals(0f, p.x, 1e-3f)
        assertTrue(p.vz < -.8f)
        // The hand lets go: the body keeps sliding a little and comes to rest.
        repeat(6) { p.update(dt, true, .1f, -.4f, -.6f, none, 0) }
        assertFalse(p.touching)
        repeat(300) { p.update(dt, false, 0f, 0f, 0f, none, 0) }
        assertTrue(p.z < 8.2f && p.z > 7.3f)
        assertEquals(0f, p.vz, 1e-2f)
        assertEquals(ArenaPlayer.EYE_HEIGHT, p.y, 0f)
    }

    @Test fun handHeldStillOnTheGroundHoldsTheBodyStill() {
        val p = player()
        repeat(90) { p.update(dt, true, .2f, -.9f, -1f, none, 0) }
        assertTrue(p.touching)
        assertEquals(0f, p.x, 1e-4f); assertEquals(9f, p.z, 1e-4f)
        // The body rests on the arm: the hand sits on the ground.
        assertEquals(ArenaPlayer.HAND_RADIUS, p.y - .9f, 1e-3f)
    }

    @Test fun pushingDownThrowsTheBodyUp() {
        val p = player()
        for (i in 0 until 9) p.update(dt, true, 0f, -.74f - .66f * i / 8f, -1f, none, 0)
        assertTrue(p.touching)
        assertTrue(p.y > 1.1f); assertTrue(p.vy > 2f)
        var highest = p.y
        repeat(150) { p.update(dt, false, 0f, 0f, 0f, none, 0); highest = max(highest, p.y) }
        assertTrue(highest > 1.5f)
        assertEquals(ArenaPlayer.EYE_HEIGHT, p.y, 0f)
        assertEquals(0f, p.vy, 0f)
    }

    @Test fun wallAndObstaclesKeepTheBodyOut() {
        val p = player()
        repeat(600) { p.vz = 7f; p.update(dt, false, 0f, 0f, 0f, none, 0) }
        assertEquals(ArenaPlayer.WALK_RADIUS, hypot(p.x, p.z), 1e-3f)
        // Something standing in the way: the body stops at its edge.
        val q = player()
        val post = floatArrayOf(0f, 6f, .3f)
        repeat(300) { q.vz = -3f; q.update(dt, false, 0f, 0f, 0f, post, 1) }
        assertEquals(6f + .3f + ArenaPlayer.BODY_RADIUS, q.z, 1e-3f)
    }

    // ------------------------------------------------------------ the game

    /** One frame with the phone level, looking at the middle of the arena. */
    private fun Coliseum.frame(present: Boolean, x: Float, y: Float, z: Float, closed: Boolean) {
        update(dt, 0f, 0f, present, x, y, z, closed, 0f, 0f, -1f, 0f, 1f, 0f)
    }

    /** Closes the hand on the sword, which stands 2 m in front of the player at the start. */
    private fun Coliseum.takeSword(): Weapon {
        val sword = weapons[0]
        val x = sword.x - player.x; val y = sword.y - player.y; val z = sword.z - player.z
        frame(true, x, y, z, false)
        assertNull(held)
        frame(true, x, y, z, true)
        assertSame(sword, held)
        return sword
    }

    @Test fun closedHandTakesTheWeaponItIsOn() {
        val g = Coliseum()
        // A fist in the air, away from every weapon, takes nothing.
        g.frame(true, 1.2f, .5f, -.8f, true)
        assertNull(g.held)
        val sword = g.takeSword()
        assertEquals(WeaponKind.SWORD, sword.kind)
        // It follows the hand and turns along the knuckles.
        repeat(60) { g.frame(true, .3f, .2f, -1f, true) }
        assertEquals(g.player.x + .3f, sword.x, 1e-2f)
        assertEquals(g.player.y + .2f, sword.y, 1e-2f)
        assertEquals(g.player.z - 1f, sword.z, 1e-2f)
        assertEquals(-1f, sword.az, 1e-2f)
        assertEquals(1f, sword.by, 1e-2f)
    }

    @Test fun weaponIsOnlyLetGoByAHandClearlyOpen() {
        val g = Coliseum()
        val sword = g.takeSword()
        // A few frames read as open in the middle of a swing: still held.
        repeat(8) { g.frame(true, .3f, .2f, -1f, false) }
        assertSame(sword, g.held)
        repeat(30) { g.frame(true, .3f, .2f, -1f, true) }
        // The hand leaves the camera's sight for seconds: still held.
        repeat(240) { g.frame(false, 0f, 0f, 0f, false) }
        assertSame(sword, g.held)
        // Open for longer than a blink: it falls and stays stuck in the sand, grip up.
        repeat(30) { g.frame(true, .3f, .2f, -1f, false) }
        assertNull(g.held)
        assertFalse(sword.held)
        repeat(180) { g.frame(true, .3f, .6f, -1f, false) }
        assertEquals(sword.plantedHeight, sword.y, 1e-4f)
        assertEquals(-1f, sword.ay, 1e-3f)
        assertEquals(1f, sqrt(sword.bx * sword.bx + sword.by * sword.by + sword.bz * sword.bz), 1e-3f)
        assertEquals(0f, sword.bx * sword.ax + sword.by * sword.ay + sword.bz * sword.az, 1e-3f)
    }

    /** Takes the sword and stands 1.6 m in front of the nearest dummy, sword held out to its right. */
    private fun readyToStrike(g: Coliseum): Dummy {
        g.takeSword()
        val dummy = g.dummies.minByOrNull { hypot(it.x - g.player.x, it.z - g.player.z) }!!
        g.player.reset(dummy.x, dummy.z + 1.6f)
        repeat(60) { g.frame(true, 1f, .2f, -1.2f, true) }
        assertEquals(Dummy.FULL_HEALTH, dummy.health)
        assertEquals(0, g.blows)
        return dummy
    }

    /** Moves the hand across the dummy in [frames] frames, from right to left or back. */
    private fun sweep(g: Coliseum, frames: Int, toLeft: Boolean): Float {
        var strongest = 0f
        for (i in 0 until frames) {
            val t = (i + 1f) / frames
            g.frame(true, if (toLeft) 1f - 2f * t else -1f + 2f * t, .2f, -1.2f, true)
            strongest = max(strongest, g.blowStrength)
        }
        return strongest
    }

    @Test fun fastSwingThroughADummyIsABlow() {
        val g = Coliseum()
        val dummy = readyToStrike(g)
        val strength = sweep(g, 15, true)
        assertTrue(strength > 0f)
        assertEquals(1, g.blows)
        assertTrue(dummy.health < Dummy.FULL_HEALTH)
        // The figure is pushed the way the blow went.
        assertTrue(dummy.leanSpeedX < 0f || dummy.leanX < 0f)
    }

    @Test fun weaponMovedSlowlyThroughADummyIsNotABlow() {
        val g = Coliseum()
        val dummy = readyToStrike(g)
        assertEquals(0f, sweep(g, 360, true), 0f)
        assertEquals(0, g.blows)
        assertEquals(Dummy.FULL_HEALTH, dummy.health)
    }

    @Test fun dummyFallsApartAndComesBack() {
        val g = Coliseum()
        val dummy = readyToStrike(g)
        var toLeft = true
        var sweeps = 0
        while (dummy.up && sweeps < 12) {
            sweep(g, 15, toLeft)
            repeat(15) { g.frame(true, if (toLeft) -1f else 1f, .2f, -1.2f, true) }
            toLeft = !toLeft; sweeps++
        }
        assertFalse(dummy.up)
        assertEquals(1, g.dummiesDown)
        val blows = g.blows
        // While it is gone nothing can hit it, and the body can walk through its place.
        sweep(g, 15, toLeft)
        assertEquals(blows, g.blows)
        repeat((Coliseum.AWAY_SECONDS * 60).toInt() + 120) { g.frame(true, 1f, .2f, -1.2f, true) }
        assertTrue(dummy.up)
        assertEquals(Dummy.FULL_HEALTH, dummy.health)
        assertEquals(1f, dummy.standing, 1e-2f)
    }

    @Test fun handOnTheGroundWalksThePlayerThroughTheGame() {
        val g = Coliseum()
        val startZ = g.player.z
        var touched = false
        // Looking down at the ground, the hand is planted ahead and pulled towards the body.
        for (i in 0 until 40) {
            g.update(dt, 0f, -.6f, true, 0f, -.3f, -1.3f + .5f * i / 39f, false, 1f, 0f, 0f, 0f, 1f, 0f)
            touched = touched || g.player.touching
        }
        assertTrue(touched)
        assertTrue(g.player.z < startZ - .15f)
        assertEquals(0f, g.player.x, 1e-3f)
        // The hand shown is on the ground, not under it.
        assertTrue(g.handY > 0f && g.handY < .2f)
    }

    @Test fun restartPutsEverythingBack() {
        val g = Coliseum()
        val dummy = readyToStrike(g)
        sweep(g, 15, true)
        g.restart()
        assertNull(g.held)
        assertEquals(0, g.blows)
        assertEquals(Dummy.FULL_HEALTH, dummy.health)
        assertEquals(Arena.START_Z, g.player.z, 0f)
        for (w in g.weapons) {
            assertEquals(w.homeX, w.x, 0f); assertEquals(w.homeZ, w.z, 0f); assertEquals(w.plantedHeight, w.y, 0f)
            assertFalse(w.held)
        }
        assertNotNull(g.camera)
    }

    // ------------------------------------------------------------ scenery

    @Test fun arenaIsMadeOfSoundTriangles() {
        val v = ColiseumScenery.arena()
        assertTrue(v.isNotEmpty())
        assertEquals(0, v.size % (ColiseumScenery.FLOATS_PER_VERTEX * 3))
        for (i in 0 until v.size / 9) {
            val x = v[i * 9]; val y = v[i * 9 + 1]; val z = v[i * 9 + 2]
            assertFalse(x.isNaN() || y.isNaN() || z.isNaN())
            assertTrue(hypot(x, z) < 24f); assertTrue(y >= 0f && y < 12f)
            val n = sqrt(v[i * 9 + 3] * v[i * 9 + 3] + v[i * 9 + 4] * v[i * 9 + 4] + v[i * 9 + 5] * v[i * 9 + 5])
            assertEquals(1f, n, 1e-3f)
            for (c in 6..8) assertTrue(v[i * 9 + c] in 0f..1.05f)
        }
    }

    @Test fun sandIsSeenFromAboveAndTheWallFromInside() {
        val v = ColiseumScenery.arena()
        var sand = 0; var wall = 0
        for (i in 0 until v.size / 9) {
            val x = v[i * 9]; val y = v[i * 9 + 1]; val z = v[i * 9 + 2]
            val nx = v[i * 9 + 3]; val ny = v[i * 9 + 4]; val nz = v[i * 9 + 5]
            if (y == 0f && hypot(x, z) < Arena.RADIUS - .1f) { sand++; assertEquals(1f, ny, 1e-4f) }
            // Inner face of the wall: turned towards the middle of the arena.
            if (abs(hypot(x, z) - Arena.RADIUS) < 1e-3f && y > .1f && y < Arena.WALL_HEIGHT - .1f && abs(ny) < .5f) {
                wall++; assertTrue(nx * x + nz * z < 0f)
            }
        }
        assertTrue(sand > 100); assertTrue(wall > 10)
    }

    @Test fun skyIsADomeAroundTheEyes() {
        val v = ColiseumScenery.sky()
        assertEquals(0, v.size % (ColiseumScenery.FLOATS_PER_VERTEX * 3))
        var top = 0f
        for (i in 0 until v.size / 9) {
            val d = sqrt(v[i * 9] * v[i * 9] + v[i * 9 + 1] * v[i * 9 + 1] + v[i * 9 + 2] * v[i * 9 + 2])
            assertFalse(d.isNaN())
            assertTrue(d > 150f && d < 215f)
            top = max(top, v[i * 9 + 1])
            for (c in 6..8) assertTrue(v[i * 9 + c] in 0f..1f)
        }
        assertEquals(200f, top, 1e-2f)
    }
}
