package com.minipots.ar

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// What the coliseum needs to be played inside phone goggles, in pure Kotlin with no Android
// dependencies, so it is covered by JVM unit tests: the two pictures for the two eyes, the two
// hands in 3D, and moving the body with the hands. Distances are in metres.
//
// "Camera space" is x right, y up, z towards the viewer, with the camera at the origin.

// ---------------------------------------------------------------------------------------------
// The two eyes
// ---------------------------------------------------------------------------------------------

/**
 * The pictures for the two eyes of phone goggles (VR Box and the like): the screen is split in
 * two halves, each seen through its own lens.
 *
 * Each eye gets its own camera, [ipd] apart, and its own projection. The projection is not
 * symmetric: the lens sits in front of the middle of the *eye*, which is rarely the middle of the
 * half screen, so the picture is centred on the lens and not on the half. Something far away is
 * then drawn exactly one [ipd] apart in the two halves, which is what makes the eyes look
 * straight ahead at it.
 */
class StereoRig {
    /** Distance between the eyes in metres. */
    var ipd = DEFAULT_IPD
    /** Width of the whole screen in metres. */
    var screenWidth = .14f
    /** Distance from lens to screen in metres: how much of the world fits in the picture. */
    var lensDistance = DEFAULT_LENS_DISTANCE
    /** False for one picture on the whole screen, without goggles. */
    var stereo = true

    val eyes: Int get() = if (stereo) 2 else 1

    private var width = 2
    private var height = 1

    /** Size of the render surface in pixels. */
    fun layout(surfaceWidth: Int, surfaceHeight: Int) { width = max(2, surfaceWidth); height = max(1, surfaceHeight) }

    fun viewportX(eye: Int) = if (stereo && eye == 1) width / 2 else 0
    fun viewportWidth(eye: Int) = if (!stereo) width else if (eye == 0) width / 2 else width - width / 2
    val viewportHeight: Int get() = height

    /** How far this eye is to the right of the middle of the head, in metres. */
    fun eyeOffset(eye: Int) = if (!stereo) 0f else if (eye == 0) -ipd * .5f else ipd * .5f

    // Edges of the picture of one eye, as tangents of the angles from straight ahead.
    fun left(eye: Int): Float {
        if (!stereo) return -MONO_TAN_HALF_Y * width / height
        return if (eye == 0) -(screenWidth - ipd) * .5f / lensDistance else -ipd * .5f / lensDistance
    }

    fun right(eye: Int): Float {
        if (!stereo) return MONO_TAN_HALF_Y * width / height
        return if (eye == 0) ipd * .5f / lensDistance else (screenWidth - ipd) * .5f / lensDistance
    }

    val top: Float get() = if (!stereo) MONO_TAN_HALF_Y else screenWidth * height / width * .5f / lensDistance

    companion object {
        const val DEFAULT_IPD = .063f
        const val MIN_IPD = .052f
        const val MAX_IPD = .074f
        const val DEFAULT_LENS_DISTANCE = .042f
        const val MIN_LENS_DISTANCE = .028f
        const val MAX_LENS_DISTANCE = .070f
        /** Half of the vertical field of view of the single picture: 70 degrees in all. */
        const val MONO_TAN_HALF_Y = .70f
    }
}

// ---------------------------------------------------------------------------------------------
// The hands, from the tracker to 3D
// ---------------------------------------------------------------------------------------------

/** One hand in one camera picture, as the tracker reports it. */
class HandReading {
    var present = false
    /** x, y pairs in the upright camera picture: 0..1, x to the right, y down. */
    val image = FloatArray(HAND_POINTS * 2)
    /** x, y, z triples along the camera axes in metres, around any point of the hand. */
    val world = FloatArray(HAND_POINTS * 3)
    /**
     * +1 or -1: with +1 the palm faces the way cross(index knuckle - wrist, little knuckle - wrist)
     * points, with -1 the other way. This is what tells a right hand from a left one.
     */
    var palmSign = 1f

    fun copyFrom(other: HandReading) {
        present = other.present; palmSign = other.palmSign
        System.arraycopy(other.image, 0, image, 0, image.size)
        System.arraycopy(other.world, 0, world, 0, world.size)
    }
}

/** Both hands in one camera picture. */
class HandsFrame {
    val hands = arrayOf(HandReading(), HandReading())
    /** When the picture reached the tracker, on the clock of System.nanoTime. */
    var timeNs = 0L
    var sequence = 0
    /** Width / height of the upright camera picture. */
    var aspect = 4f / 3f
    /** Focal length of the camera in units of the width of the upright picture. */
    var focal = .77f
}

/** Latest [HandsFrame], handed from the tracking thread to the render thread. */
class HandsExchange {
    private val latest = HandsFrame()

    fun publish(frame: HandsFrame) {
        synchronized(latest) {
            for (i in 0..1) latest.hands[i].copyFrom(frame.hands[i])
            latest.timeNs = frame.timeNs; latest.aspect = frame.aspect; latest.focal = frame.focal
            latest.sequence++
        }
    }

    /** Copies the latest frame to [out]. Returns false when [out] already holds it. */
    fun read(out: HandsFrame): Boolean {
        synchronized(latest) {
            if (out.sequence == latest.sequence) return false
            for (i in 0..1) out.hands[i].copyFrom(latest.hands[i])
            out.timeNs = latest.timeNs; out.aspect = latest.aspect; out.focal = latest.focal
            out.sequence = latest.sequence
            return true
        }
    }
}

/**
 * Keeps each hand in its own slot from one picture to the next. The tracker returns hands in no
 * particular order and its left/right label flickers when a hand is seen edge on; a hand that
 * changed slot would let go of the ground and of its weapon.
 *
 * A hand goes to the slot that was last seen near it; a slot that has not been seen for a while
 * takes the hand whose label fits (slot 0: left, slot 1: right).
 */
class HandSlots {
    private val lastX = FloatArray(2)
    private val lastY = FloatArray(2)
    private val lastNs = longArrayOf(0L, 0L)
    private val seen = booleanArrayOf(false, false)

    fun reset() { seen[0] = false; seen[1] = false }

    /**
     * [count] hands (0..2) were found at ([x], [y]) in the picture; [right] is their label. Writes
     * to [slotOf] the slot of each hand.
     */
    fun assign(count: Int, x: FloatArray, y: FloatArray, right: BooleanArray, timeNs: Long, slotOf: IntArray) {
        if (count <= 0) return
        if (count == 1) {
            slotOf[0] = if (cost(0, 0, x, y, right, timeNs) <= cost(0, 1, x, y, right, timeNs)) 0 else 1
        } else {
            val straight = cost(0, 0, x, y, right, timeNs) + cost(1, 1, x, y, right, timeNs)
            val crossed = cost(0, 1, x, y, right, timeNs) + cost(1, 0, x, y, right, timeNs)
            // Nothing to tell them apart: the one on the left of the picture is the left hand.
            val first = if (abs(straight - crossed) < 1e-4f) (if (x[0] <= x[1]) 0 else 1) else if (straight < crossed) 0 else 1
            slotOf[0] = first; slotOf[1] = 1 - first
        }
        for (i in 0 until min(count, 2)) {
            val s = slotOf[i]
            lastX[s] = x[i]; lastY[s] = y[i]; lastNs[s] = timeNs; seen[s] = true
        }
    }

    private fun cost(hand: Int, slot: Int, x: FloatArray, y: FloatArray, right: BooleanArray, timeNs: Long): Float {
        if (seen[slot] && timeNs - lastNs[slot] < RECENT_NS) {
            val dx = x[hand] - lastX[slot]; val dy = y[hand] - lastY[slot]
            return sqrt(dx * dx + dy * dy)
        }
        return if (right[hand] == (slot == 1)) LABEL_FITS else LABEL_DIFFERS
    }

    private companion object {
        const val RECENT_NS = 600_000_000L
        const val LABEL_FITS = .15f
        const val LABEL_DIFFERS = .45f
    }
}

/** From a [HandReading] to 21 points in camera space, and the proportions of a hand. */
object HandGeometry {
    /** Each bone as (from, to), parents before children. */
    val BONES = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4,
        0, 5, 5, 6, 6, 7, 7, 8,
        0, 9, 9, 10, 10, 11, 11, 12,
        0, 13, 13, 14, 14, 15, 15, 16,
        0, 17, 17, 18, 18, 19, 19, 20,
    )
    /** Length of each bone of an average hand, in units of the distance wrist to middle knuckle. */
    val PROPORTIONS = floatArrayOf(
        .40f, .42f, .34f, .28f,
        1.02f, .43f, .26f, .22f,
        1.00f, .47f, .30f, .23f,
        .95f, .43f, .28f, .23f,
        .90f, .34f, .20f, .20f,
    )
    const val BONE_COUNT = 20
    /** Landmarks around the palm; their mean is the place of the hand. */
    val PALM = intArrayOf(0, 5, 9, 13, 17)
    private val SIZE_PAIRS = intArrayOf(0, 9, 5, 17, 0, 5, 0, 17)
    const val MIN_DEPTH = .12f
    const val MAX_DEPTH = 1.2f

    /**
     * Distance from the camera to the hand along its axis, in metres: the real size of the hand
     * across the picture against its size in the picture. Depth inside the hand is left out on
     * both sides, so a hand that points at the camera is not taken for a distant one. Returns 0
     * when the reading is unusable.
     */
    fun depth(r: HandReading, aspect: Float, focal: Float): Float {
        var real = 0f; var seen = 0f
        for (i in 0 until SIZE_PAIRS.size / 2) {
            val a = SIZE_PAIRS[i * 2]; val b = SIZE_PAIRS[i * 2 + 1]
            val wx = r.world[a * 3] - r.world[b * 3]; val wy = r.world[a * 3 + 1] - r.world[b * 3 + 1]
            real += sqrt(wx * wx + wy * wy)
            val sx = (r.image[a * 2] - r.image[b * 2]) / focal
            val sy = (r.image[a * 2 + 1] - r.image[b * 2 + 1]) / (focal * aspect)
            seen += sqrt(sx * sx + sy * sy)
        }
        if (seen < 1e-4f || real < 1e-4f) return 0f
        return (real / seen).coerceIn(MIN_DEPTH, MAX_DEPTH)
    }

    /** Middle of the palm in the picture, as tangents of the angles from the camera axis. */
    fun palmTanX(r: HandReading, focal: Float): Float {
        var u = 0f
        for (i in PALM) u += r.image[i * 2]
        return (u / PALM.size - .5f) / focal
    }

    fun palmTanY(r: HandReading, aspect: Float, focal: Float): Float {
        var v = 0f
        for (i in PALM) v += r.image[i * 2 + 1]
        return (.5f - v / PALM.size) / (focal * aspect)
    }

    /**
     * The 21 points of [r] around the middle of the palm, written to [out]. The tracker describes
     * the hand as it is seen, that is, along the line from the camera to the hand; the shape is
     * turned from there to the camera axes, so a hand at the edge of the picture is not twisted.
     */
    fun shape(r: HandReading, tanX: Float, tanY: Float, out: FloatArray) {
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in PALM) { cx += r.world[i * 3]; cy += r.world[i * 3 + 1]; cz += r.world[i * 3 + 2] }
        cx /= PALM.size; cy /= PALM.size; cz /= PALM.size
        // Smallest turn that takes straight ahead (0, 0, -1) to the direction of the hand.
        val l = sqrt(tanX * tanX + tanY * tanY + 1f)
        val dx = tanX / l; val dy = tanY / l; val dz = -1f / l
        var kx = dy; var ky = -dx
        val sine = sqrt(kx * kx + ky * ky); val cosine = -dz
        if (sine > 1e-6f) { kx /= sine; ky /= sine }
        for (i in 0 until HAND_POINTS) {
            val x = r.world[i * 3] - cx; val y = r.world[i * 3 + 1] - cy; val z = r.world[i * 3 + 2] - cz
            if (sine <= 1e-6f) { out[i * 3] = x; out[i * 3 + 1] = y; out[i * 3 + 2] = z; continue }
            // Rodrigues' formula with the axis (kx, ky, 0).
            val dot = kx * x + ky * y
            val crossX = ky * z; val crossY = -kx * z; val crossZ = kx * y - ky * x
            out[i * 3] = x * cosine + crossX * sine + kx * dot * (1f - cosine)
            out[i * 3 + 1] = y * cosine + crossY * sine + ky * dot * (1f - cosine)
            out[i * 3 + 2] = z * cosine + crossZ * sine
        }
    }

    fun boneLength(points: FloatArray, bone: Int): Float {
        val a = BONES[bone * 2]; val b = BONES[bone * 2 + 1]
        val dx = points[b * 3] - points[a * 3]; val dy = points[b * 3 + 1] - points[a * 3 + 1]
        val dz = points[b * 3 + 2] - points[a * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Gives every bone of [points] the length in [lengths] and keeps its direction. This is what
     * keeps fingers from stretching and shrinking when the tracker is unsure of a joint: the
     * tracker says where each bone points, never how long it is. [scratch] holds 63 floats.
     */
    fun fixBones(points: FloatArray, lengths: FloatArray, scratch: FloatArray) {
        System.arraycopy(points, 0, scratch, 0, HAND_POINTS * 3)
        for (bone in 0 until BONE_COUNT) {
            val a = BONES[bone * 2]; val b = BONES[bone * 2 + 1]
            var dx = scratch[b * 3] - scratch[a * 3]; var dy = scratch[b * 3 + 1] - scratch[a * 3 + 1]
            var dz = scratch[b * 3 + 2] - scratch[a * 3 + 2]
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            if (l < 1e-6f) { dx = 0f; dy = 1f; dz = 0f } else { dx /= l; dy /= l; dz /= l }
            points[b * 3] = points[a * 3] + dx * lengths[bone]
            points[b * 3 + 1] = points[a * 3 + 1] + dy * lengths[bone]
            points[b * 3 + 2] = points[a * 3 + 2] + dz * lengths[bone]
        }
    }
}

/**
 * One hand of the player, from what the tracker says to what is drawn and played with.
 *
 * [accept] takes each reading of the tracker, [advance] is called for every drawn frame and leaves
 * the hand in [joints]. In between:
 *  - the place of the hand comes from where it is in the picture and how big it is there;
 *  - every bone keeps a steady length, so fingers cannot stretch;
 *  - a reading that jumps further than a hand can move is held back until the next one agrees;
 *  - a filter that adapts to the speed takes the tremor out of a still hand and lets a fast one
 *    through;
 *  - between two readings the hand keeps moving the way it was, for a bounded short time;
 *  - when readings stop, the last pose is kept while [confidence] falls, and after [HOLD_SECONDS]
 *    (longer when readings come slowly) the hand is [State.LOST]: it is never shown as if it were
 *    still followed.
 */
class HandRig {
    enum class State { LOST, TRACKED, HELD }

    var state = State.LOST
        private set
    /** 1 while readings arrive, falling to 0 while the last pose is being held. */
    var confidence = 0f
        private set
    /** The hand is closed into a fist. */
    var closed = false
        private set
    var palmSign = 1f
        private set
    /** 21 points in camera space, metres. Only meaningful unless [state] is [State.LOST]. */
    val joints = FloatArray(HAND_POINTS * 3)
    /** Speed of the hand in camera space, metres per second. */
    val velocity = FloatArray(3)
    /** Smoothed time between two readings in seconds, 0 until two have arrived. */
    var interval = 0f
        private set
    /** Readings held back as jumps since the start. */
    var rejected = 0
        private set

    private val raw = FloatArray(HAND_POINTS * 3)
    private val shape = FloatArray(HAND_POINTS * 3)
    private val scratch = FloatArray(HAND_POINTS * 3)
    private val lengths = FloatArray(HandGeometry.BONE_COUNT)
    private var size = 0f
    private val tanFilters = Array(2) { OneEuroFilter(1.2f, 12f, 2f) }
    private val depthFilter = OneEuroFilter(.8f, 5f, 1.5f)
    private val shapeFilters = Array(HAND_POINTS * 3) { OneEuroFilter(1f, 8f, 1.5f) }
    private val fist = PinchDetector(HandShape.CLOSED_BELOW, HandShape.OPEN_ABOVE)
    private var centreX = 0f
    private var centreY = 0f
    private var centreZ = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var rawZ = 0f
    private var lastSampleNs = 0L
    private var following = false
    private var heldBack = 0
    private var shown = false

    fun reset() {
        state = State.LOST; confidence = 0f; closed = false; following = false; shown = false; heldBack = 0
        lastSampleNs = 0L; interval = 0f; size = 0f
        fist.reset(); velocity.fill(0f)
    }

    /** A reading of this hand taken at [timeNs]. Readings without the hand are simply not passed. */
    fun accept(r: HandReading, timeNs: Long, aspect: Float, focal: Float) {
        if (!r.present) return
        val depth = HandGeometry.depth(r, aspect, focal)
        if (depth <= 0f) return
        val tanX = HandGeometry.palmTanX(r, focal); val tanY = HandGeometry.palmTanY(r, aspect, focal)
        val x = tanX * depth; val y = tanY * depth; val z = -depth

        var dt = 0f
        if (following) {
            dt = (timeNs - lastSampleNs) / 1e9f
            if (dt <= 0f) return
            val dx = x - rawX; val dy = y - rawY; val dz = z - rawZ
            val jump = sqrt(dx * dx + dy * dy + dz * dz)
            if (dt > hold()) {
                // Back after a loss: a new start, nothing is carried over.
                following = false; dt = 0f
            } else if (jump > JUMP_METRES + JUMP_SPEED * dt) {
                if (heldBack < MAX_HELD_BACK) { heldBack++; rejected++; return }
                // The next reading agrees: the hand really is there now.
                following = false; dt = 0f
            }
        }
        heldBack = 0
        if (!following) {
            for (f in tanFilters) f.reset()
            depthFilter.reset()
            for (f in shapeFilters) f.reset()
            velocity.fill(0f)
        } else {
            interval = if (interval == 0f) dt else interval + (dt - interval) * .2f
        }
        rawX = x; rawY = y; rawZ = z

        // Sideways the picture is precise and depth is not, so they are steadied apart. Speeds are
        // measured in hand sizes per second, so the filter behaves the same near and far.
        val tanScale = depth * SPEED_SCALE
        val fx = tanFilters[0].filter(tanX, dt, tanScale)
        val fy = tanFilters[1].filter(tanY, dt, tanScale)
        val fd = depthFilter.filter(depth, dt, DEPTH_SPEED_SCALE)
        val nx = fx * fd; val ny = fy * fd; val nz = -fd
        if (following && dt > 0f) {
            // Mostly the newest speed: in a simulated swing, an older one sends the hand on the
            // wrong way at each turn.
            velocity[0] += ((nx - centreX) / dt - velocity[0]) * VELOCITY_RATE
            velocity[1] += ((ny - centreY) / dt - velocity[1]) * VELOCITY_RATE
            velocity[2] += ((nz - centreZ) / dt - velocity[2]) * VELOCITY_RATE
        }
        centreX = nx; centreY = ny; centreZ = nz

        HandGeometry.shape(r, tanX, tanY, raw)
        steadyLengths(raw)
        for (i in shape.indices) shape[i] = shapeFilters[i].filter(raw[i], dt, SPEED_SCALE)
        HandGeometry.fixBones(shape, lengths, scratch)
        // Fixing the bones starts at the wrist; the palm must stay where the picture has it.
        var mx = 0f; var my = 0f; var mz = 0f
        for (i in HandGeometry.PALM) { mx += shape[i * 3]; my += shape[i * 3 + 1]; mz += shape[i * 3 + 2] }
        mx /= HandGeometry.PALM.size; my /= HandGeometry.PALM.size; mz /= HandGeometry.PALM.size
        for (i in 0 until HAND_POINTS) { shape[i * 3] -= mx; shape[i * 3 + 1] -= my; shape[i * 3 + 2] -= mz }

        closed = fist.update(HandShape.closedRatio(r.world))
        palmSign = r.palmSign
        following = true
        lastSampleNs = timeNs
    }

    /**
     * Each bone slowly takes the length the tracker measures for it, within what a hand can be:
     * lengths follow the player's own hand, but never a doubtful reading.
     */
    private fun steadyLengths(points: FloatArray) {
        val measured = HandGeometry.boneLength(points, 8).coerceIn(MIN_SIZE, MAX_SIZE)
        val first = size == 0f
        size = if (first) measured else size + (measured - size) * LENGTH_RATE
        for (bone in 0 until HandGeometry.BONE_COUNT) {
            val average = HandGeometry.PROPORTIONS[bone] * size
            val length = HandGeometry.boneLength(points, bone).coerceIn(average * .8f, average * 1.25f)
            lengths[bone] = if (first) average else lengths[bone] + (length - lengths[bone]) * LENGTH_RATE
        }
    }

    /** Brings [joints], [state] and [confidence] up to [nowNs]. [dt] is the time since the last drawn frame. */
    fun advance(nowNs: Long, dt: Float) {
        if (!following) { lose(); return }
        val age = (nowNs - lastSampleNs) / 1e9f
        val hold = hold()
        if (age > hold) { lose(); return }
        // Up to two readings in a row may be missing, or one may take three times as long (the
        // look for the second hand does), before the hand counts as held.
        val fresh = (if (interval > 0f) interval * FRESH_READINGS else .10f).coerceIn(.10f, hold * .6f)
        if (age <= fresh) { state = State.TRACKED; confidence = 1f }
        else { state = State.HELD; confidence = (1f - (age - fresh) / (hold - fresh)).coerceIn(0f, 1f) }

        // Between readings the hand goes on the way it was moving, but only for a short time and
        // only when it clearly moves: a still hand is never pushed around by its own tremor.
        val speed = sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2])
        val sure = ((speed - PREDICT_FROM) / (PREDICT_FULLY - PREDICT_FROM)).coerceIn(0f, 1f)
        val lead = min(max(age, 0f), MAX_LEAD_SECONDS) * sure
        val px = centreX + velocity[0] * lead; val py = centreY + velocity[1] * lead; val pz = centreZ + velocity[2] * lead
        // A new reading never makes the drawn hand jump: it closes in over a few frames.
        val k = if (!shown || dt <= 0f) 1f else 1f - exp(-dt / GLIDE_SECONDS)
        for (i in 0 until HAND_POINTS) {
            joints[i * 3] += (px + shape[i * 3] - joints[i * 3]) * k
            joints[i * 3 + 1] += (py + shape[i * 3 + 1] - joints[i * 3 + 1]) * k
            joints[i * 3 + 2] += (pz + shape[i * 3 + 2] - joints[i * 3 + 2]) * k
        }
        shown = true
    }

    /**
     * How long the last pose is kept after the last reading: at least [HOLD_SECONDS], and on a
     * phone whose readings come slowly, the time of [HOLD_READINGS] of them.
     */
    private fun hold() = max(HOLD_SECONDS, min(interval * HOLD_READINGS, MAX_HOLD_SECONDS))

    private fun lose() {
        state = State.LOST; confidence = 0f; shown = false
        if (following) { following = false; fist.reset(); closed = false; velocity.fill(0f); interval = 0f }
    }

    companion object {
        /** The last pose is kept at least this long after the last reading. */
        const val HOLD_SECONDS = .9f
        /** The longest the last pose is ever kept. */
        const val MAX_HOLD_SECONDS = 2f
        private const val HOLD_READINGS = 5f
        private const val FRESH_READINGS = 3f
        private const val VELOCITY_RATE = .8f
        /** Longest time the hand is moved on beyond its last reading. */
        const val MAX_LEAD_SECONDS = .07f
        // A reading further away than this from the one before is taken for a mistake.
        const val JUMP_METRES = .18f
        const val JUMP_SPEED = 4f
        private const val MAX_HELD_BACK = 1
        private const val PREDICT_FROM = .12f
        private const val PREDICT_FULLY = .45f
        private const val GLIDE_SECONDS = .025f
        /** Filters want speeds in hand sizes (about 9 cm) per second. */
        private const val SPEED_SCALE = 11f
        private const val DEPTH_SPEED_SCALE = 5f
        private const val LENGTH_RATE = .05f
        private const val MIN_SIZE = .070f
        private const val MAX_SIZE = .115f
    }
}

/**
 * Which of the two trackers the next camera frame goes to (see [HandTracker]): the one for a
 * single hand, which is cheap, or the one for two, which looks over the whole picture whenever
 * it follows fewer than two hands and then costs about three times as much.
 *
 *  - With no hand followed the search is done by the tracker for two, so that two hands held up
 *    together are both there from the first frame.
 *  - With two hands followed it stays with the tracker for two, and gives a missing hand a few
 *    frames to come back before it lets go.
 *  - With one hand followed the cheap tracker does the work, and the other one takes a look for
 *    the second hand now and then: never more often than once in [PROBE_FRAMES] + 1 frames, so
 *    that on a slow phone the look cannot take most of the time.
 */
class TrackerChoice {
    /** Two hands are followed, or were a moment ago. */
    var pair = false
        private set
    private var following = false
    private var singles = 0
    private var missing = 0
    private var lastLookNs = 0L
    private var lastPairNs = 0L

    fun reset() { pair = false; following = false; singles = 0; missing = 0; lastLookNs = 0L; lastPairNs = 0L }

    /** True when the frame that starts at [nowNs] goes to the tracker for two hands. */
    fun forTwo(nowNs: Long): Boolean =
        pair || !following || (singles >= PROBE_FRAMES && nowNs - lastLookNs >= PROBE_NS)

    /** A frame is done at [nowNs]: it went to the tracker for two if [usedTwo], and found [count] hands. */
    fun result(usedTwo: Boolean, count: Int, nowNs: Long) {
        following = count > 0
        if (usedTwo) { lastLookNs = nowNs; singles = 0 } else singles++
        if (usedTwo && count >= 2) { pair = true; missing = 0; lastPairNs = nowNs }
        else if (pair && ++missing >= PAIR_FRAMES && nowNs - lastPairNs > PAIR_HOLD_NS) { pair = false; missing = 0 }
    }

    companion object {
        /** While one hand is followed, the other one is looked for at most this often, */
        const val PROBE_NS = 350_000_000L
        /** and only after this many frames of following the first. */
        const val PROBE_FRAMES = 6
        /** With two hands followed, one may be missing this long, */
        const val PAIR_HOLD_NS = 400_000_000L
        /** and for at least this many frames, before the cheap tracker takes over. */
        const val PAIR_FRAMES = 2
    }
}

// ---------------------------------------------------------------------------------------------
// The arms
// ---------------------------------------------------------------------------------------------

/**
 * The arm between a shoulder and a wrist: two bones and an elbow. The player's arms are long, as
 * in Gorilla Tag, because the hands reach [Coliseum.ARM_SCALE] times further than the real ones;
 * when a hand goes further still, the arm stretches instead of letting go of it.
 */
object ArmPose {
    /** Length of the upper arm and of the forearm, in metres. */
    const val BONE = .80f
    const val SHOULDER_ACROSS = .21f
    const val SHOULDER_BELOW = .23f
    const val SHOULDER_BEHIND = .07f

    /**
     * Where the shoulder of one arm is, written to [out] at [offset]: beside and a little below
     * and behind the eyes at ([headX], [headY], [headZ]). The body turns with the head ([yaw]) but
     * stays upright when the head nods. [side] is -1 for the left arm and 1 for the right one.
     */
    fun shoulder(headX: Float, headY: Float, headZ: Float, yaw: Float, side: Float, out: FloatArray, offset: Int) {
        val c = cos(yaw); val sn = sin(yaw)
        // To the right is (c, 0, sn); forward is (sn, 0, -c).
        out[offset] = headX + c * side * SHOULDER_ACROSS - sn * SHOULDER_BEHIND
        out[offset + 1] = headY - SHOULDER_BELOW
        out[offset + 2] = headZ + sn * side * SHOULDER_ACROSS + c * SHOULDER_BEHIND
    }

    /**
     * The elbow of the arm from the shoulder at [shoulders] `[offset..offset+2]` to the wrist at
     * ([wx], [wy], [wz]), written to [out] at [offset]. It bends outwards, down and back, as a
     * hanging arm does. Returns how many times longer than [BONE] each bone has to be: 1 while the
     * hand is within reach.
     */
    fun elbow(shoulders: FloatArray, offset: Int, wx: Float, wy: Float, wz: Float, yaw: Float, side: Float, out: FloatArray): Float {
        val sx = shoulders[offset]; val sy = shoulders[offset + 1]; val sz = shoulders[offset + 2]
        var dx = wx - sx; var dy = wy - sy; var dz = wz - sz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val c = cos(yaw); val sn = sin(yaw)
        // Outwards, down and back.
        var px = c * side * .7f - sn * .4f; var py = -.6f; var pz = sn * side * .7f + c * .4f
        if (d < 1e-4f) {
            val l = sqrt(px * px + py * py + pz * pz)
            out[offset] = sx + px / l * BONE; out[offset + 1] = sy + py / l * BONE; out[offset + 2] = sz + pz / l * BONE
            return 1f
        }
        dx /= d; dy /= d; dz /= d
        val stretch = max(1f, d / (2f * BONE))
        val bone = BONE * stretch
        // Both bones are equally long: the elbow is over the middle, as far out as the bones allow.
        val height = sqrt(max(0f, bone * bone - d * d * .25f))
        val along = px * dx + py * dy + pz * dz
        px -= along * dx; py -= along * dy; pz -= along * dz
        var l = sqrt(px * px + py * py + pz * pz)
        if (l < 1e-3f) {
            // The arm points the way the elbow would go: any direction across it will do.
            if (abs(dy) < .9f) { px = -dz; py = 0f; pz = dx } else { px = 1f; py = 0f; pz = 0f }
            val again = px * dx + py * dy + pz * dz
            px -= again * dx; py -= again * dy; pz -= again * dz
            l = sqrt(px * px + py * py + pz * pz)
        }
        out[offset] = sx + dx * d * .5f + px / l * height
        out[offset + 1] = sy + dy * d * .5f + py / l * height
        out[offset + 2] = sz + dz * d * .5f + pz / l * height
        return stretch
    }
}

// ---------------------------------------------------------------------------------------------
// The solid things of the arena
// ---------------------------------------------------------------------------------------------

/**
 * Everything a hand can touch and a body cannot pass: the sand, the wall around it, the rows of
 * seats behind the wall, and the training dummies. [distance] tells how far a point is from the
 * nearest of them (negative inside) and leaves the direction away from it in [normal].
 *
 * The arena is round, so sand, wall and seats are one staircase turned around the centre: a point
 * is tested against it by its distance from the centre and its height.
 */
class ArenaWorld {
    val normal = FloatArray(3)
    /** Which dummy the last [distance] was measured to, or -1 when it was sand, wall or seats. */
    var lastDummy = -1
        private set
    private val dummyX = FloatArray(MAX_DUMMIES)
    private val dummyZ = FloatArray(MAX_DUMMIES)
    private val dummyHeight = FloatArray(MAX_DUMMIES)
    private var dummies = 0

    fun clearDummies() { dummies = 0 }

    fun addDummy(x: Float, z: Float, height: Float) {
        if (dummies >= MAX_DUMMIES) return
        dummyX[dummies] = x; dummyZ[dummies] = z; dummyHeight[dummies] = height; dummies++
    }

    /** Height of the floor (sand, top of the wall or a row of seats) at this place. */
    fun floorAt(x: Float, z: Float): Float {
        val rho = sqrt(x * x + z * z)
        var floor = 0f
        for (i in 0 until STEP_COUNT) if (rho >= STEPS[i * 2]) floor = STEPS[i * 2 + 1]
        return floor
    }

    fun distance(x: Float, y: Float, z: Float): Float {
        // The sand.
        lastDummy = -1
        var best = y
        normal[0] = 0f; normal[1] = 1f; normal[2] = 0f
        val rho = sqrt(x * x + z * z)
        val ux = if (rho > 1e-5f) x / rho else 1f; val uz = if (rho > 1e-5f) z / rho else 0f
        // Each step is everything beyond its radius and below its height.
        for (i in 0..STEP_COUNT) {
            val radius = if (i < STEP_COUNT) STEPS[i * 2] else OUTER_RADIUS
            val height = if (i < STEP_COUNT) STEPS[i * 2 + 1] else 1e4f
            val a = radius - rho; val b = y - height
            if (a <= 0f && b <= 0f) {
                // Inside: out through the nearer face.
                if (a > b) { if (a < best) { best = a; normal[0] = -ux; normal[1] = 0f; normal[2] = -uz } }
                else if (b < best) { best = b; normal[0] = 0f; normal[1] = 1f; normal[2] = 0f }
            } else {
                val pa = max(a, 0f); val pb = max(b, 0f)
                val d = sqrt(pa * pa + pb * pb)
                if (d < best) { best = d; normal[0] = -ux * pa / d; normal[1] = pb / d; normal[2] = -uz * pa / d }
            }
        }
        for (i in 0 until dummies) {
            val dx = x - dummyX[i]; val dz = z - dummyZ[i]
            val r = sqrt(dx * dx + dz * dz)
            val a = r - Dummy.RADIUS; val b = y - dummyHeight[i]
            val vx = if (r > 1e-5f) dx / r else 1f; val vz = if (r > 1e-5f) dz / r else 0f
            if (a <= 0f && b <= 0f) {
                if (a > b) { if (a < best) { best = a; lastDummy = i; normal[0] = vx; normal[1] = 0f; normal[2] = vz } }
                else if (b < best) { best = b; lastDummy = i; normal[0] = 0f; normal[1] = 1f; normal[2] = 0f }
            } else {
                val pa = max(a, 0f); val pb = max(b, 0f)
                val d = sqrt(pa * pa + pb * pb)
                if (d < best) { best = d; lastDummy = i; normal[0] = vx * pa / d; normal[1] = pb / d; normal[2] = vz * pa / d }
            }
        }
        return best
    }

    companion object {
        const val MAX_DUMMIES = 16
        /** Radius at which each step starts, and its height: the wall, then five rows of seats. */
        val STEPS = floatArrayOf(
            Arena.RADIUS, Arena.WALL_HEIGHT,
            Arena.RADIUS + 1f, Arena.WALL_HEIGHT + .9f,
            Arena.RADIUS + 2.5f, Arena.WALL_HEIGHT + 1.8f,
            Arena.RADIUS + 4f, Arena.WALL_HEIGHT + 2.7f,
            Arena.RADIUS + 5.5f, Arena.WALL_HEIGHT + 3.6f,
            Arena.RADIUS + 7f, Arena.WALL_HEIGHT + 4.5f,
        )
        const val STEP_COUNT = 6
        /** Nobody gets past the columns on top of the seats. */
        const val OUTER_RADIUS = Arena.RADIUS + 7.7f
    }
}

// ---------------------------------------------------------------------------------------------
// Moving with the hands
// ---------------------------------------------------------------------------------------------

/**
 * The body of the player, moved the way Gorilla Tag does it. There are no legs, no stick and no
 * teleport: the only thing that moves the body is a hand touching something.
 *
 *  - A hand that reaches a surface (sand, wall, seats, a dummy) is held there: its place in the
 *    world is fixed for as long as it stays in touch.
 *  - While a hand is held, moving the real hand moves the body the other way. Pulling the hand
 *    back moves the body forward, pushing down lifts it, pulling down on a wall climbs it.
 *  - With both hands in touch the body follows the mean of the two.
 *  - The speed the body has when the last hand lets go is kept, and from then on only gravity
 *    acts: a quick push is a jump, a quick pull along the ground a slide.
 *  - The body cannot enter anything solid.
 */
class GorillaBody {
    /** Position of the eyes. */
    var x = 0f; var y = EYE_HEIGHT; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    /** True while the hand is held on a surface. */
    val touching = booleanArrayOf(false, false)
    /** Where each hand is drawn, x, y, z per hand: on the surface while held, else where the real hand is. */
    val hand = FloatArray(6)
    /** The body stands on a floor (and is not hanging from a hand above it). */
    var onGround = true
        private set

    private val pin = FloatArray(6)
    private val historyX = FloatArray(HISTORY)
    private val historyY = FloatArray(HISTORY)
    private val historyZ = FloatArray(HISTORY)
    private var historyCount = 0
    private var historyNext = 0

    fun reset(startX: Float, startY: Float, startZ: Float) {
        x = startX; y = startY; z = startZ
        vx = 0f; vy = 0f; vz = 0f
        touching[0] = false; touching[1] = false; onGround = true
        historyCount = 0; historyNext = 0
    }

    /**
     * Advances by [dt] seconds. [offsets] holds, for each hand, where its palm is relative to the
     * eyes in world axes (x, y, z per hand); a hand that is not [present] is ignored.
     */
    fun update(dt: Float, world: ArenaWorld, present: BooleanArray, offsets: FloatArray) {
        if (dt <= 0f) return
        val wasHeld = touching[0] || touching[1]
        if (!wasHeld) {
            vy -= GRAVITY * dt
            x += vx * dt; y += vy * dt; z += vz * dt
            collide(world)
            if (onGround) {
                val f = exp(-dt * FRICTION); vx *= f; vz *= f
                if (vx * vx + vz * vz < STOP_SPEED * STOP_SPEED) { vx = 0f; vz = 0f }
            }
        }

        var held = 0
        var mx = 0f; var my = 0f; var mz = 0f
        for (i in 0..1) {
            if (!present[i]) { touching[i] = false; continue }
            val qx = x + offsets[i * 3]; val qy = y + offsets[i * 3 + 1]; val qz = z + offsets[i * 3 + 2]
            val d = world.distance(qx, qy, qz) - HAND_RADIUS
            if (!touching[i]) {
                if (d <= TOUCH_MARGIN) {
                    // First touch: the hand stays on the surface, where it came in.
                    touching[i] = true
                    pin[i * 3] = qx - world.normal[0] * d; pin[i * 3 + 1] = qy - world.normal[1] * d
                    pin[i * 3 + 2] = qz - world.normal[2] * d
                    if (!wasHeld && held == 0) { historyCount = 0; historyNext = 0 }
                }
            } else {
                val ex = pin[i * 3] - qx; val ey = pin[i * 3 + 1] - qy; val ez = pin[i * 3 + 2] - qz
                // Lifted off the surface, or pulled further than an arm can be from where it holds.
                if (d > RELEASE_DISTANCE || ex * ex + ey * ey + ez * ez > UNSTICK_DISTANCE * UNSTICK_DISTANCE) touching[i] = false
            }
            if (touching[i]) {
                mx += pin[i * 3] - qx; my += pin[i * 3 + 1] - qy; mz += pin[i * 3 + 2] - qz
                held++
            }
        }

        if (held > 0) {
            // Firm but not rigid: the tremor of tracking is not passed on to the whole view.
            val k = 1f - exp(-dt * FOLLOW)
            var sx = mx / held * k; var sy = my / held * k; var sz = mz / held * k
            val step = sqrt(sx * sx + sy * sy + sz * sz)
            if (step > MAX_SPEED * dt) { val f = MAX_SPEED * dt / step; sx *= f; sy *= f; sz *= f }
            val fromX = x; val fromY = y; val fromZ = z
            x += sx; y += sy; z += sz
            vx = 0f; vy = 0f; vz = 0f
            collide(world)
            // A body lifted by a hand does not stand on the ground.
            historyX[historyNext] = (x - fromX) / dt; historyY[historyNext] = (y - fromY) / dt
            historyZ[historyNext] = (z - fromZ) / dt
            historyNext = (historyNext + 1) % HISTORY
            if (historyCount < HISTORY) historyCount++
            var ax = 0f; var ay = 0f; var az = 0f
            for (i in 0 until historyCount) { ax += historyX[i]; ay += historyY[i]; az += historyZ[i] }
            vx = ax / historyCount; vy = ay / historyCount; vz = az / historyCount
        } else if (wasHeld) {
            // The last hand let go: the body keeps the speed the hands gave it.
            vx *= JUMP_MULTIPLIER; vy *= JUMP_MULTIPLIER; vz *= JUMP_MULTIPLIER
            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            if (speed > MAX_JUMP_SPEED) { val f = MAX_JUMP_SPEED / speed; vx *= f; vy *= f; vz *= f }
            if (speed < REST_SPEED) { vx = 0f; vy = 0f; vz = 0f }
        }

        for (i in 0..1) {
            if (touching[i]) { hand[i * 3] = pin[i * 3]; hand[i * 3 + 1] = pin[i * 3 + 1]; hand[i * 3 + 2] = pin[i * 3 + 2] }
            else { hand[i * 3] = x + offsets[i * 3]; hand[i * 3 + 1] = y + offsets[i * 3 + 1]; hand[i * 3 + 2] = z + offsets[i * 3 + 2] }
        }
    }

    /** Keeps the body out of everything solid and on top of the floor. */
    private fun collide(world: ArenaWorld) {
        for (pass in 0..2) {
            val d = world.distance(x, y - CHEST_BELOW_EYES, z) - BODY_RADIUS
            if (d >= 0f) break
            val nx = world.normal[0]; val ny = world.normal[1]; val nz = world.normal[2]
            x -= nx * d; y -= ny * d; z -= nz * d
            val into = vx * nx + vy * ny + vz * nz
            if (into < 0f) { vx -= into * nx; vy -= into * ny; vz -= into * nz }
        }
        val floor = world.floorAt(x, z) + EYE_HEIGHT
        if (y <= floor) {
            y = floor
            if (vy < 0f) vy = 0f
            onGround = true
        } else onGround = false
    }

    companion object {
        /** Height of the eyes above the floor at rest. Low on purpose: the hands have to reach the ground. */
        const val EYE_HEIGHT = .8f
        /** The palm touches as a ball of this radius. */
        const val HAND_RADIUS = .09f
        const val BODY_RADIUS = .30f
        private const val CHEST_BELOW_EYES = .45f
        /** A hand this close to a surface takes hold of it. */
        const val TOUCH_MARGIN = .03f
        /** A held hand lets go when it is this far off the surface. Wide enough for the tremor of tracking. */
        const val RELEASE_DISTANCE = .12f
        private const val UNSTICK_DISTANCE = 1.0f
        private const val FOLLOW = 30f
        private const val GRAVITY = 9.8f
        private const val FRICTION = 2.2f
        private const val MAX_SPEED = 10f
        private const val JUMP_MULTIPLIER = 1.1f
        private const val MAX_JUMP_SPEED = 8f
        private const val REST_SPEED = .25f
        private const val STOP_SPEED = .06f
        private const val HISTORY = 6
    }
}
