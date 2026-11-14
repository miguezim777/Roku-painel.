package com.minipots.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole way from the camera to the body, played without a phone: a player whose real hands
 * move in front of a camera, a tracker that reads them a few times a second, late, with noise
 * and with the readings it misses when a hand moves fast, the [HandRig]s that turn readings into
 * hands, and the game that moves the body by them. The tests in [LocomotionTest] hand the game
 * perfect hands sixty times a second; a phone does not.
 */
class PipelineTest {
    /** What the tracker is like. */
    class Phone(
        /** Readings a second. */
        val rate: Float,
        /** Seconds from the picture to its reading arriving. */
        val delay: Float = 1f / rate + .03f,
        /** How far off a point in the picture is, as a part of the width of the picture. */
        val jitter: Float = .003f,
        /** How far off the size of the hand in the picture is, which is what the distance comes from. */
        val sizeError: Float = .04f,
        /**
         * The tracker looks for the hand around where the last picture had it. A hand that has
         * moved further than this since then, in widths of a hand, is not found in this picture;
         * the next picture is then searched all over and has it again.
         */
        val lostBeyond: Float = 1e9f,
        /** The hand of the player is this many times smaller than the hand the tracker assumes, so it seems further away. */
        val smallHand: Float = 1f,
        /** Part of the readings that are missed for no reason. */
        val missed: Float = 0f,
    )

    private val aspect = 4f / 3f
    private val focal = .77f
    private val random = java.util.Random(5)
    val game = Coliseum()
    private val rigs = arrayOf(HandRig(), HandRig())
    private val inputs = arrayOf(HandInput(), HandInput())
    /** Where the real hands are, in metres along the axes of the camera; null when out of sight. */
    private val real = arrayOfNulls<FloatArray>(2)
    private val lastSeen = arrayOf(FloatArray(2), FloatArray(2))
    private val seenBefore = booleanArrayOf(false, false)
    private var pitch = -.9f
    private var time = 0f
    private var nextPicture = 0f
    private val waiting = ArrayList<Pair<Float, HandsFrame>>()
    var readings = 0
    var lostFrames = 0
    var highest = 0f
    var trace = false

    private fun now() = 1_000_000_000L + (time.toDouble() * 1e9).toLong()

    /** The reading a tracker gives of a flat hand whose palm is at ([x], [y], [z]) from the camera. */
    private fun read(r: HandReading, x: Float, y: Float, z: Float, phone: Phone) {
        r.present = true; r.palmSign = 1f
        val grow = (1f + (random.nextFloat() * 2f - 1f) * phone.sizeError) / phone.smallHand
        var mx = 0f; var my = 0f
        for (i in HandGeometry.PALM) { mx += (i % 5 - 2) * .02f; my += (i / 5) * .03f }
        mx = mx / HandGeometry.PALM.size.toFloat(); my = my / HandGeometry.PALM.size.toFloat()
        for (i in 0 until HAND_POINTS) {
            val sx = (i % 5 - 2) * .02f - mx; val sy = (i / 5) * .03f - my
            val u = .5f + (x + sx * grow) / -z * focal + (random.nextFloat() * 2f - 1f) * phone.jitter
            val v = .5f - (y + sy * grow) / -z * focal * aspect + (random.nextFloat() * 2f - 1f) * phone.jitter
            r.image[i * 2] = u; r.image[i * 2 + 1] = v
            r.world[i * 3] = sx; r.world[i * 3 + 1] = sy; r.world[i * 3 + 2] = 0f
        }
    }

    /** One drawn frame, as [ColiseumRenderer] does it. */
    fun frame(phone: Phone, dt: Float = 1f / 60f) {
        time += dt
        // The camera takes a picture whenever the tracker is free for one.
        while (time >= nextPicture) {
            val f = HandsFrame(); f.aspect = aspect; f.focal = focal; f.timeNs = 1_000_000_000L + (nextPicture.toDouble() * 1e9).toLong()
            for (i in 0..1) {
                val p = real[i]
                f.hands[i].present = false
                if (p == null || p[2] > -.05f) { seenBefore[i] = false; continue }
                val u = p[0] / -p[2] * focal; val v = p[1] / -p[2] * focal * aspect
                // Out of the picture, blurred by its own speed, or simply missed.
                val out = abs(u) > .5f || abs(v) > .5f
                // How far it went since the last picture, in widths of a hand (8 cm) as seen at that distance.
                val handWidth = .08f / -p[2] * focal
                val moved = if (seenBefore[i]) sqrt((u - lastSeen[i][0]) * (u - lastSeen[i][0]) + (v - lastSeen[i][1]) * (v - lastSeen[i][1])) / handWidth else 0f
                lastSeen[i][0] = u; lastSeen[i][1] = v
                if (out) { seenBefore[i] = false; continue }
                if (moved > phone.lostBeyond || random.nextFloat() < phone.missed) { seenBefore[i] = false; continue }
                seenBefore[i] = true
                read(f.hands[i], p[0], p[1], p[2], phone)
            }
            waiting.add(Pair(nextPicture + phone.delay, f))
            nextPicture += 1f / phone.rate
        }
        while (waiting.isNotEmpty() && waiting[0].first <= time) {
            val f = waiting.removeAt(0).second
            for (i in 0..1) if (f.hands[i].present) { rigs[i].accept(f.hands[i], f.timeNs, f.aspect, f.focal); readings++ }
        }
        for (i in 0..1) {
            val rig = rigs[i]
            rig.advance(now(), dt)
            val shown = rig.state != HandRig.State.LOST
            if (!shown && real[i] != null) lostFrames++
            val input = inputs[i]
            input.present = shown
            input.live = rig.state == HandRig.State.TRACKED && !rig.restarted
            input.closed = shown && rig.closed
            input.confidence = if (rig.readings >= 2) rig.confidence else rig.confidence * .5f
            input.interval = rig.interval
            input.aimed = shown
            if (shown) {
                System.arraycopy(rig.joints, 0, input.joints, 0, input.joints.size)
                System.arraycopy(rig.target, 0, input.target, 0, 3)
            }
        }
        game.update(dt, 0f, pitch, 0f, inputs)
        highest = max(highest, game.body.y)
        if (trace) {
            val r = real[1]; val c = cos(pitch); val sn = sin(pitch)
            val ty = if (r == null) 0f else (r[1] * c - r[2] * sn) * game.reach; val tz = if (r == null) 0f else (r[1] * sn + r[2] * c) * game.reach
            println("%.3f real y %.2f z %.2f | shown y %.2f z %.2f %s %s touch %s | body y %.2f z %.2f v %.2f %.2f rej %d".format(time, ty, tz,
                game.palm[4] - game.body.y, game.palm[5] - game.body.z, rigs[1].state, if (inputs[1].live) "live" else "----", game.body.touching[1], game.body.y, game.body.z, game.body.vy, game.body.vz, game.body.rejected))
        }
    }

    /**
     * Puts real hand [i] where the hand of the game would be at ([x], [y], [z]) from the eyes,
     * in metres of the game along the axes of the world, the player looking down by [pitch].
     */
    fun hand(i: Int, x: Float, y: Float, z: Float) {
        // The world to the camera: a turn about the x axis by the pitch.
        val c = cos(pitch); val s = sin(pitch)
        val cy = y * c + z * s; val cz = -y * s + z * c
        real[i] = floatArrayOf(x / game.reach, cy / game.reach, cz / game.reach)
    }

    fun hide(i: Int) { real[i] = null }

    fun look(down: Float) { pitch = -down }

    /** Both hands (or one) go in a straight line for [seconds]. */
    fun move(phone: Phone, seconds: Float, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, left: Boolean = true, right: Boolean = true) {
        val count = max(1, Math.round(seconds * 60f))
        for (f in 1..count) {
            val t = f.toFloat() / count
            if (left) hand(0, x0 - .28f + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t)
            if (right) hand(1, x0 + .28f + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t)
            frame(phone)
        }
    }

    /**
     * The way a player walks: the hands go down on to the sand in front, are pulled back along
     * it, come up and go forward again. The player feels no floor, so the hands go a little
     * "into" it. [strokes] times, each pull taking [pull] seconds. Returns how far the body went.
     */
    fun walk(phone: Phone, strokes: Int, pull: Float = .4f, left: Boolean = true, right: Boolean = true, into: Float = .06f): Float {
        val start = game.body.z
        val floor = -(game.eyeHeight - game.body.handRadius) - into
        if (left) hand(0, -.28f, -.35f, -.85f); if (right) hand(1, .28f, -.35f, -.85f)
        repeat(40) { frame(phone) }
        repeat(strokes) {
            move(phone, .25f, 0f, -.35f, -.85f, 0f, floor, -.85f, left, right)
            move(phone, pull, 0f, floor, -.85f, 0f, floor, -.25f, left, right)
            move(phone, .2f, 0f, floor, -.25f, 0f, -.35f, -.25f, left, right)
            move(phone, .3f, 0f, -.35f, -.25f, 0f, -.35f, -.85f, left, right)
        }
        repeat(90) { frame(phone) }
        return start - game.body.z
    }

    @Test fun walkingWorksWithTheHandsAPhoneReallyGives() {
        // From a quick phone to the slowest one measured (6 readings a second); a tracker that
        // never loses a moving hand and one that loses it whenever it moves a hand's width
        // between two pictures; a player who stops at the sand and one who pushes 30 cm into it;
        // a hand of the size the tracker assumes and a child's.
        for (rate in floatArrayOf(30f, 15f, 10f, 6f)) for (lost in floatArrayOf(1e9f, 1f)) for (into in floatArrayOf(.06f, .30f)) for (small in floatArrayOf(1f, 1.35f)) {
            val p = PipelineTest()
            val went = p.walk(Phone(rate, lostBeyond = lost, smallHand = small), 5, into = into)
            val what = "$rate readings a second, lost beyond $lost, $into into the sand, hand $small times smaller"
            // Five pulls of 60 cm: forward, by a distance that has to do with the pulls.
            assertTrue("$what: went $went m", went > 1.5f && went < 12f)
            assertEquals(what, p.game.eyeHeight, p.game.body.y, 1e-3f)
            assertTrue("$what: highest ${p.highest}", p.highest < p.game.eyeHeight + 1f)
        }
    }

    @Test fun oneHandAloneWalksToo() {
        for (rate in floatArrayOf(30f, 10f)) for (hand in 0..1) {
            val p = PipelineTest()
            val went = p.walk(Phone(rate, lostBeyond = 1f), 5, left = hand == 0, right = hand == 1, into = .2f)
            assertTrue("$rate readings a second, hand $hand: went $went m", went > 1.5f && went < 10f)
            assertEquals(0f, p.game.body.x, .5f)
        }
    }

    @Test fun handsThatAreStillMoveNothingWhateverThePhone() {
        for (rate in floatArrayOf(30f, 10f, 6f)) for (y in floatArrayOf(-.35f, -.75f, -1.1f)) {
            // In the air in front of the chest, resting on the sand, and resting "in" it; ten
            // seconds, with the noise of tracking and a reading in five missing.
            val p = PipelineTest()
            val phone = Phone(rate, missed = .2f)
            p.hand(0, -.28f, y, -.6f); p.hand(1, .28f, y, -.6f)
            repeat(600) { p.frame(phone) }
            val what = "$rate readings a second, hands at $y"
            assertEquals(what, Arena.START_Z, p.game.body.z, .12f)
            assertEquals(what, 0f, p.game.body.x, .12f)
            assertEquals(what, p.game.eyeHeight, p.game.body.y, .12f)
            // The hands were on the screen all the time once they had been found.
            assertTrue("$what: lost for ${p.lostFrames} frames", p.lostFrames < 2 * 60)
        }
    }

    @Test fun handStaysOnTheScreenThroughReadingsThatAreMissed() {
        // Half the readings of a slowly moving hand are missed, at 10 readings a second.
        val p = PipelineTest()
        val phone = Phone(10f, missed = .5f)
        p.look(.5f)
        p.hand(1, .25f, -.3f, -.8f)
        repeat(60) { p.frame(phone) }
        val before = p.lostFrames
        var jumps = 0; var lastX = 0f; var lastY = 0f; var lastZ = 0f
        for (f in 0 until 1200) {
            val t = f / 60f
            p.hand(1, .25f + .15f * sin(t * 1.3f), -.3f + .1f * sin(t * .9f), -.8f + .1f * cos(t * 1.1f))
            p.frame(phone)
            val x = p.game.palm[3]; val y = p.game.palm[4]; val z = p.game.palm[5]
            // From one drawn frame to the next the hand never moves more than 10 cm of the arena
            // (4 cm of real hand): it closes in on a late reading, it does not teleport.
            if (f > 0 && sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ)) > .10f) jumps++
            lastX = x; lastY = y; lastZ = z
        }
        assertEquals("frames without the hand", before, p.lostFrames)
        assertEquals("jumps", 0, jumps)
    }

    @Test fun handThatLeavesThePictureIsKeptForAWhileAndComesBackWithoutAPush() {
        val p = PipelineTest()
        val phone = Phone(15f)
        p.hand(0, -.28f, -.8f, -.6f); p.hand(1, .28f, -.8f, -.6f)
        repeat(60) { p.frame(phone) }
        assertTrue(p.game.body.touching[0] && p.game.body.touching[1])
        val z = p.game.body.z
        // Out of the picture for a second: still drawn, and holding nothing.
        p.hide(0); p.hide(1)
        repeat(60) { p.frame(phone) }
        assertTrue(p.game.handPresent[0] && p.game.handPresent[1])
        assertFalse(p.game.handLive[0] || p.game.handLive[1])
        assertFalse(p.game.body.touching[0] || p.game.body.touching[1])
        // Back, 30 cm nearer to the body than they left: that is not a pull.
        p.hand(0, -.28f, -.8f, -.3f); p.hand(1, .28f, -.8f, -.3f)
        repeat(90) { p.frame(phone) }
        assertTrue(p.game.handLive[0] && p.game.handLive[1])
        assertEquals(z, p.game.body.z, .05f)
        assertEquals(p.game.eyeHeight, p.game.body.y, .05f)
    }

    @Test fun swordAtTheStartIsOutOfReachForAnyHandTheCameraCanSee() {
        // The player stands at the start and holds a hand up towards the sword, 2 m away, with a
        // small hand that the tracker takes to be further off than it is.
        for (small in floatArrayOf(1f, 1.35f, 2f)) {
            val p = PipelineTest()
            val phone = Phone(15f, smallHand = small)
            p.look(0f)
            for (f in 0 until 300) {
                val t = f / 300f
                // The hand goes from near the face to the full length of a real arm.
                p.hand(1, .1f, .1f, -.5f - 1.1f * t)
                p.frame(phone)
            }
            assertTrue("hand $small times smaller", p.game.held[1] == null && p.game.held[0] == null)
            val dx = p.game.palm[3] - p.game.shoulder[3]; val dy = p.game.palm[4] - p.game.shoulder[4]; val dz = p.game.palm[5] - p.game.shoulder[5]
            assertTrue(sqrt(dx * dx + dy * dy + dz * dz) <= p.game.armLength + .02f)
        }
    }

    @Test fun table() {
        if (System.getProperty("only") != "table") return
        for (into in floatArrayOf(.06f, .30f)) for (small in floatArrayOf(1f, 1.35f)) for (rate in floatArrayOf(30f, 15f, 10f, 6f)) for (lost in floatArrayOf(1e9f, 1.0f)) {
            val p = PipelineTest()
            val phone = Phone(rate, lostBeyond = lost, smallHand = small)
            val went = p.walk(phone, 5, into = into)
            println("into $into small $small rate $rate lostBeyond $lost: went $went m; rejected body ${p.game.body.rejected} rigs ${p.rigs[0].rejected + p.rigs[1].rejected}; readings ${p.readings}; lost frames ${p.lostFrames}; highest ${p.highest}")
        }
    }
}
