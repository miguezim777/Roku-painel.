package com.minipots.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Rules of the coliseum game in pure Kotlin, with no Android dependencies, so they are covered by
// JVM unit tests. Distances are in metres, angles in radians, the ground is the plane y = 0.
//
// Directions: yaw 0 looks along -Z and a positive yaw turns to the right (towards +X); a positive
// pitch looks up. "Camera space" is x right, y up, z towards the viewer.

/** Where the phone looks, from its sensors. */
object Look {
    /**
     * Yaw and pitch, written to [out]`[0..1]`, of the direction the back of the phone points to.
     * [r] is an Android rotation matrix (3x3, row-major, device axes to a world whose Z is up).
     *
     * The roll of the phone is ignored, so the horizon stays level. Near straight down or up the
     * heading comes from the top edge of the phone, which is the well-defined direction there.
     */
    fun fromRotationMatrix(r: FloatArray, out: FloatArray) {
        // Columns are the device axes seen from the world: the view is -z, the top edge is +y.
        // World (x, y, z up) becomes game (x, z, -y).
        val fx = -r[2]; val fy = -r[8]; val fz = r[5]
        val ux = r[1]; val uz = -r[4]
        val hx = fx - ux * fy; val hz = fz - uz * fy
        out[0] = if (abs(hx) + abs(hz) < 1e-6f) 0f else atan2(hx, -hz)
        out[1] = asin(fy.coerceIn(-1f, 1f))
    }

    /**
     * Pitch from the accelerometer alone, for phones without a gyroscope. ([x], [y], [z]) is the
     * reading at rest, which points up. Returns 0 when there is no usable reading.
     */
    fun pitchFromGravity(x: Float, y: Float, z: Float): Float {
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1f) return 0f
        return asin((-z / length).coerceIn(-1f, 1f))
    }

    /**
     * Yaw, pitch and roll of the head, written to [out]`[0..2]`, for a phone worn in front of the
     * eyes with its screen turned by [quarterTurns] (the display rotation: 0 upright, 1 with the
     * top edge to the left, 3 with the top edge to the right). [r] is an Android rotation matrix.
     * A positive roll tilts the top of the head to the right.
     */
    fun headFromRotationMatrix(r: FloatArray, quarterTurns: Int, out: FloatArray) {
        val fx = -r[2]; val fy = -r[8]; val fz = r[5]
        // The edge of the phone that is up on the screen, in game axes.
        val column = if (quarterTurns % 2 == 0) 1 else 0
        val sign = if (quarterTurns == 0 || quarterTurns == 1) 1f else -1f
        val ux = r[column] * sign; val uy = r[6 + column] * sign; val uz = -r[3 + column] * sign
        val hx = fx - ux * fy; val hz = fz - uz * fy
        val yaw = if (abs(hx) + abs(hz) < 1e-6f) 0f else atan2(hx, -hz)
        val pitch = asin(fy.coerceIn(-1f, 1f))
        // What "up" and "right" would be with a level head, and where the phone's up is between them.
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val alongUp = ux * (-sy * sp) + uy * cp + uz * (cy * sp)
        val alongRight = ux * cy + uz * sy
        out[0] = yaw; out[1] = pitch; out[2] = atan2(alongRight, alongUp)
    }

    /**
     * Pitch and roll of the head from the accelerometer alone, written to [out]`[1..2]`, for phones
     * without a gyroscope. ([x], [y], [z]) is the reading at rest, which points up; [quarterTurns]
     * as in [headFromRotationMatrix]. Leaves [out] alone when there is no usable reading.
     */
    fun tiltFromGravity(x: Float, y: Float, z: Float, quarterTurns: Int, out: FloatArray) {
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1f) return
        out[1] = asin((-z / length).coerceIn(-1f, 1f))
        // Gravity along the screen: up on the screen and to its right.
        val up = when (quarterTurns) { 1 -> x; 2 -> -y; 3 -> -x; else -> y }
        val right = when (quarterTurns) { 1 -> -y; 2 -> -x; 3 -> y; else -> x }
        out[2] = if (abs(up) + abs(right) < .15f * length) 0f else atan2(-right, up)
    }

    /** Difference a - b wrapped to -PI..PI. */
    fun angleDifference(a: Float, b: Float): Float {
        var d = (a - b) % TWO_PI
        if (d > PI.toFloat()) d -= TWO_PI
        if (d < -PI.toFloat()) d += TWO_PI
        return d
    }

    private const val TWO_PI = 6.2831855f
}

enum class LookMode { GYROSCOPE, TILT, TOUCH }

/**
 * Turns what the sensors and the finger say into the direction of the view.
 *
 *  - [LookMode.GYROSCOPE]: the view follows the phone; the finger adds a turn on top, to face
 *    another way without turning the body.
 *  - [LookMode.TILT], for phones without a gyroscope: looking up and down follows the tilt of the
 *    phone, measured by the accelerometer; turning is done with the finger or with [turnBy] (the
 *    renderer turns while the head leans to a side).
 *  - [LookMode.TOUCH]: the finger does everything.
 */
class LookControl {
    var yaw = 0f
        private set
    var pitch = START_PITCH
        private set
    private var turn = 0f
    private var zero = 0f
    private var fingerPitch = START_PITCH
    private var lastMode: LookMode? = null
    private var recentre = true

    /** Makes the direction the phone points to now the one that faces the middle of the arena. */
    fun recentre() { recentre = true }

    /** Turns the view by [radians] to the right, on top of whatever the sensors say. */
    fun turnBy(radians: Float) { turn += radians }

    /**
     * The finger moved by these fractions of the width and height of the screen. The picture
     * follows the finger: dragging to the right turns the view to the left.
     */
    fun drag(dx: Float, dy: Float) {
        turn -= dx * DRAG_TURN
        fingerPitch = (fingerPitch + dy * DRAG_TURN).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    /** [sensorReady] is false until the sensor of [mode] has given a reading. */
    fun update(mode: LookMode, sensorYaw: Float, sensorPitch: Float, sensorReady: Boolean) {
        val effective = if (sensorReady) mode else LookMode.TOUCH
        if (effective != lastMode) {
            // Changing the way of looking must not turn the view: it keeps pointing where it did.
            turn = yaw; zero = sensorYaw; fingerPitch = pitch
            lastMode = effective
        }
        if (recentre) { recentre = false; turn = 0f; zero = sensorYaw; fingerPitch = START_PITCH }
        yaw = if (effective == LookMode.GYROSCOPE) Look.angleDifference(sensorYaw, zero) + turn else turn
        pitch = (if (effective == LookMode.TOUCH) fingerPitch else sensorPitch).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    companion object {
        /** Looking a little down at the start, so the ground and the weapons are in view. */
        const val START_PITCH = -.35f
        const val MAX_PITCH = 1.35f
        /** Radians turned by a drag across the whole screen. */
        const val DRAG_TURN = 1.9f
    }
}

/**
 * First-person camera of the arena. [place] puts the head somewhere and turns it; [eye] then
 * builds the picture of one eye, which may sit to the side of the head and look through a lens
 * that is not in front of the middle of its picture (see [StereoRig]).
 */
class ArenaCamera {
    val viewProjection = FloatArray(16)
    var tanHalfX = TAN_HALF_Y * .5f
        private set
    val tanHalfY: Float get() = TAN_HALF_Y
    /** The eye whose picture [viewProjection] holds. */
    var eyeX = 0f
        private set
    var eyeY = 0f
        private set
    var eyeZ = 0f
        private set
    /** The middle of the head, between the eyes. */
    var headX = 0f
        private set
    var headY = 0f
        private set
    var headZ = 0f
        private set
    // Orthonormal basis: right (r), up (u), forward (f).
    private var rx = 1f; private var ry = 0f; private var rz = 0f
    private var ux = 0f; private var uy = 1f; private var uz = 0f
    private var fx = 0f; private var fy = 0f; private var fz = -1f

    /** [aspect] is width / height of a single picture on the whole surface. */
    fun setAspect(aspect: Float) { tanHalfX = TAN_HALF_Y * aspect }

    /**
     * Head at ([x], [y], [z]). A positive [roll] tilts the top of the head to the right. Leaves in
     * [viewProjection] one picture for the whole surface, seen from the middle of the head.
     */
    fun place(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float = 0f) {
        headX = x; headY = y; headZ = z
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        fx = sy * cp; fy = sp; fz = -cy * cp
        // Level head first, then tilted about the line of sight.
        val lx = cy; val lz = sy
        val vx = -sy * sp; val vy = cp; val vz = cy * sp
        val cr = cos(roll); val sr = sin(roll)
        rx = lx * cr - vx * sr; ry = -vy * sr; rz = lz * cr - vz * sr
        ux = vx * cr + lx * sr; uy = vy * cr; uz = vz * cr + lz * sr
        eye(0f, -tanHalfX, tanHalfX, TAN_HALF_Y)
    }

    /**
     * The picture of an eye that is [offset] metres to the right of the middle of the head. Its
     * edges are at the tangents [left], [right] and plus and minus [top] from straight ahead.
     */
    fun eye(offset: Float, left: Float, right: Float, top: Float) {
        val x = headX + rx * offset; val y = headY + ry * offset; val z = headZ + rz * offset
        eyeX = x; eyeY = y; eyeZ = z
        val m = viewProjection
        val a = (FAR + NEAR) / (NEAR - FAR); val b = 2f * FAR * NEAR / (NEAR - FAR)
        val across = 2f / (right - left); val shift = (right + left) / (right - left)
        val er = rx * x + ry * y + rz * z
        val eu = ux * x + uy * y + uz * z
        val ef = fx * x + fy * y + fz * z
        m[0] = across * rx - shift * fx; m[4] = across * ry - shift * fy; m[8] = across * rz - shift * fz
        m[12] = -(across * er - shift * ef)
        m[1] = ux / top; m[5] = uy / top; m[9] = uz / top; m[13] = -eu / top
        m[2] = -a * fx; m[6] = -a * fy; m[10] = -a * fz; m[14] = a * ef + b
        m[3] = fx; m[7] = fy; m[11] = fz; m[15] = -ef
    }

    /** World direction of the camera-space vector ([x], [y], [z]), one component at a time. */
    fun worldX(x: Float, y: Float, z: Float) = rx * x + ux * y - fx * z
    fun worldY(x: Float, y: Float, z: Float) = ry * x + uy * y - fy * z
    fun worldZ(x: Float, y: Float, z: Float) = rz * x + uz * y - fz * z

    companion object {
        /** Half of the vertical field of view of a single picture: 70 degrees in all. */
        const val TAN_HALF_Y = StereoRig.MONO_TAN_HALF_Y
        private const val NEAR = .08f
        private const val FAR = 260f
    }
}

/** Model matrices (column-major, OpenGL layout) for shapes placed in the world. */
object Place {
    /** Unit cylinder (radius 1, from y = 0 to y = 1) stretched from point a to point b. */
    fun between(
        m: FloatArray, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float,
    ) {
        var dx = bx - ax; var dy = by - ay; var dz = bz - az
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1e-6f) { dx = 0f; dy = 1f; dz = 0f } else { dx /= length; dy /= length; dz /= length }
        // Any direction across the axis: d x up, or d x right when d is nearly vertical.
        var px: Float; var py: Float; var pz: Float
        if (abs(dy) < .9f) { px = -dz; py = 0f; pz = dx } else { px = 0f; py = dz; pz = -dy }
        val pl = sqrt(px * px + py * py + pz * pz); px /= pl; py /= pl; pz /= pl
        val qx = dy * pz - dz * py; val qy = dz * px - dx * pz; val qz = dx * py - dy * px
        m[0] = px * radius; m[1] = py * radius; m[2] = pz * radius; m[3] = 0f
        m[4] = dx * length; m[5] = dy * length; m[6] = dz * length; m[7] = 0f
        m[8] = qx * radius; m[9] = qy * radius; m[10] = qz * radius; m[11] = 0f
        m[12] = ax; m[13] = ay; m[14] = az; m[15] = 1f
    }

    /** Frame with the given axes as columns (they carry their own length) and origin. */
    fun frame(
        m: FloatArray,
        xx: Float, xy: Float, xz: Float, yx: Float, yy: Float, yz: Float, zx: Float, zy: Float, zz: Float,
        ox: Float, oy: Float, oz: Float,
    ) {
        m[0] = xx; m[1] = xy; m[2] = xz; m[3] = 0f
        m[4] = yx; m[5] = yy; m[6] = yz; m[7] = 0f
        m[8] = zx; m[9] = zy; m[10] = zz; m[11] = 0f
        m[12] = ox; m[13] = oy; m[14] = oz; m[15] = 1f
    }

    /** Upright frame standing on the ground at ([x], [z]), leaning by small angles about its foot. */
    fun leaning(m: FloatArray, x: Float, z: Float, leanX: Float, leanZ: Float, scale: Float) {
        var yx = sin(leanX); var yz = sin(leanZ)
        var yy = sqrt(max(0.05f, 1f - yx * yx - yz * yz))
        val yl = sqrt(yx * yx + yy * yy + yz * yz); yx /= yl; yy /= yl; yz /= yl
        // The x axis stays as close to world x as the lean allows; z completes the frame.
        var xx = 1f - yx * yx; var xy = -yx * yy; var xz = -yx * yz
        val xl = sqrt(xx * xx + xy * xy + xz * xz); xx /= xl; xy /= xl; xz /= xl
        val zx = xy * yz - xz * yy; val zy = xz * yx - xx * yz; val zz = xx * yy - xy * yx
        frame(m, xx * scale, xy * scale, xz * scale, yx * scale, yy * scale, yz * scale, zx * scale, zy * scale, zz * scale, x, 0f, z)
    }
}

/** What the tracked hand looks like, from its 21 points in camera space (metres). */
object HandShape {
    /**
     * Converts one 3D landmark as MediaPipe reports it (x and y along the camera buffer, z away
     * from the camera) to camera space, written to [out] at [offset]. [rotation] is the clockwise
     * rotation that makes the buffer upright, [mirror] is true for the front camera.
     */
    fun toCameraSpace(rotation: Int, mirror: Boolean, x: Float, y: Float, z: Float, out: FloatArray, offset: Int) {
        var right = FrameMapping.uprightA(rotation, x, y) - FrameMapping.uprightA(rotation, 0f, 0f)
        val down = FrameMapping.uprightB(rotation, x, y) - FrameMapping.uprightB(rotation, 0f, 0f)
        if (mirror) right = -right
        out[offset] = right; out[offset + 1] = -down; out[offset + 2] = -z
    }

    /**
     * How closed the hand is: the mean distance from the wrist to the four finger tips, divided by
     * the distance from the wrist to their knuckles. Measured on photographs of real hands with the
     * tracker's model: 1.7 to 2.0 for a flat hand, 0.7 to 1.2 for a fist.
     */
    fun closedRatio(points: FloatArray): Float {
        var sum = 0f
        for (finger in 0..3) {
            val knuckle = 5 + finger * 4
            sum += distance(points, 0, knuckle + 3) / max(distance(points, 0, knuckle), 1e-4f)
        }
        return sum / 4f
    }

    fun distance(points: FloatArray, a: Int, b: Int): Float {
        val dx = points[a * 3] - points[b * 3]; val dy = points[a * 3 + 1] - points[b * 3 + 1]
        val dz = points[a * 3 + 2] - points[b * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Length of the line between two points as the camera sees it (depth ignored). */
    fun flatDistance(points: FloatArray, a: Int, b: Int): Float {
        val dx = points[a * 3] - points[b * 3]; val dy = points[a * 3 + 1] - points[b * 3 + 1]
        return sqrt(dx * dx + dy * dy)
    }

    /** Hand closed below this [closedRatio]... */
    const val CLOSED_BELOW = 1.25f
    /** ...and open again above this one. */
    const val OPEN_ABOVE = 1.55f
}

/** Sizes of the arena shared by the rules and the scenery. */
object Arena {
    /** Radius of the sand. */
    const val RADIUS = 14f
    const val WALL_HEIGHT = 3f
    const val START_Z = 9.5f
}

enum class WeaponKind { SWORD, AXE, MACE }

/**
 * A weapon. Its frame has the origin in the middle of the grip, the y axis along the weapon towards
 * its striking end ([ax], [ay], [az]) and the x axis across it, where the edge is ([bx], [by], [bz]).
 */
class Weapon(val kind: WeaponKind, val homeX: Float, val homeZ: Float) {
    var x = 0f; var y = 0f; var z = 0f
    var ax = 0f; var ay = -1f; var az = 0f
    var bx = 1f; var by = 0f; var bz = 0f
    var held = false
    /** Thrown or dropped and still on its way: it moves by its own speed and gravity. */
    var flying = false
    var vx = 0f; var vy = 0f; var vz = 0f
    /** Turning end over end while it flies, radians per second. */
    var spin = 0f
    /** Where the striking part starts and ends, measured from the grip along the weapon. */
    val strikeFrom: Float = when (kind) { WeaponKind.SWORD -> .25f; WeaponKind.AXE -> .62f; WeaponKind.MACE -> .70f }
    val strikeTo: Float = when (kind) { WeaponKind.SWORD -> 1.25f; WeaponKind.AXE -> .98f; WeaponKind.MACE -> 1.0f }
    /** Height of the grip above the floor when the weapon stands stuck in it, striking end down. */
    val plantedHeight: Float = when (kind) { WeaponKind.SWORD -> .98f; WeaponKind.AXE -> .86f; WeaponKind.MACE -> .88f }
    // Striking end in the previous frame, for the speed of the blow and the path it swept.
    var tipX = 0f; var tipY = 0f; var tipZ = 0f
    var tipSpeed = 0f
    var tipKnown = false

    fun plant(atX: Float, atZ: Float) {
        x = atX; y = plantedHeight; z = atZ
        ax = 0f; ay = -1f; az = 0f; bx = 1f; by = 0f; bz = 0f
        held = false; flying = false; vx = 0f; vy = 0f; vz = 0f; spin = 0f; tipKnown = false; tipSpeed = 0f
    }
}

/** A training dummy: a straw figure on a post that sways when hit and falls apart after a few blows. */
class Dummy(val x: Float, val z: Float) {
    var health = FULL_HEALTH
    var leanX = 0f; var leanZ = 0f
    var leanSpeedX = 0f; var leanSpeedZ = 0f
    /** 1 when standing, 0 when gone; in between while it sinks or comes back. */
    var standing = 1f
    var awayFor = 0f
    var cooldown = 0f

    val up: Boolean get() = awayFor <= 0f

    fun reset() {
        health = FULL_HEALTH; leanX = 0f; leanZ = 0f; leanSpeedX = 0f; leanSpeedZ = 0f
        standing = 1f; awayFor = 0f; cooldown = 0f
    }

    companion object {
        const val FULL_HEALTH = 3
        const val RADIUS = .30f
        const val HEIGHT = 1.75f
    }
}

/** What one hand of the player does in one frame, as [Coliseum.update] is told. */
class HandInput {
    /** The hand is followed (or its last pose is still being held). */
    var present = false
    /** The hand is closed into a fist. */
    var closed = false
    /** Its 21 points in camera space, in real metres. */
    val joints = FloatArray(HAND_POINTS * 3)
}

/**
 * The whole game: the player, the weapons standing in the sand and the training dummies.
 *
 * Each frame [update] is given where the head looks and what the two tracked hands do, in camera
 * space. A hand that touches something moves the body (see [GorillaBody]). A hand closed on the
 * grip of a weapon takes it; the weapon stays in that hand until the hand is clearly open for a
 * moment, and then flies on with the speed the hand had. A weapon that moves fast enough through a
 * dummy is a blow.
 */
class Coliseum {
    val body = GorillaBody()
    val world = ArenaWorld()
    val camera = ArenaCamera()
    val weapons = arrayOf(
        Weapon(WeaponKind.SWORD, 0f, Arena.START_Z - 2.0f),
        Weapon(WeaponKind.AXE, -1.1f, Arena.START_Z - 2.4f),
        Weapon(WeaponKind.MACE, 1.1f, Arena.START_Z - 2.4f),
    )
    val dummies = Array(DUMMY_COUNT) { i ->
        val a = 2f * PI.toFloat() * i / DUMMY_COUNT + .5f
        Dummy(sin(a) * DUMMY_RING, -cos(a) * DUMMY_RING)
    }
    /** The weapon in each hand. */
    val held = arrayOfNulls<Weapon>(2)
    /** Blows that landed, and dummies taken apart. */
    var blows = 0
        private set
    var dummiesDown = 0
        private set
    /** Strength (0..1) of a blow that landed in the last [update], else 0. */
    var blowStrength = 0f
        private set

    /** Which hands are in the world now. */
    val handPresent = booleanArrayOf(false, false)
    /** The 21 points of each hand in the world, at the scale of the player's long arms. */
    val handJoints = arrayOf(FloatArray(HAND_POINTS * 3), FloatArray(HAND_POINTS * 3))
    /** Middle of each palm in the world (x, y, z per hand). */
    val palm = FloatArray(6)

    private val offsets = FloatArray(6)
    private val palmSpeed = FloatArray(6)
    private val lastFree = FloatArray(6)
    private val speedKnown = booleanArrayOf(false, false)
    private val openFor = FloatArray(2)
    /** The fastest the hand moved since it opened: a throw is let go at the speed of the swing. */
    private val openSpeed = FloatArray(6)
    private val grabLock = FloatArray(2)
    private val local = FloatArray(3)

    init { restart() }

    fun restart() {
        body.reset(0f, GorillaBody.EYE_HEIGHT, Arena.START_Z)
        for (w in weapons) w.plant(w.homeX, w.homeZ)
        for (d in dummies) d.reset()
        held[0] = null; held[1] = null
        blows = 0; dummiesDown = 0; blowStrength = 0f
        for (i in 0..1) { openFor[i] = 0f; grabLock[i] = 0f; handPresent[i] = false; speedKnown[i] = false }
    }

    fun update(dt: Float, yaw: Float, pitch: Float, roll: Float, hands: Array<HandInput>) {
        blowStrength = 0f
        if (dt <= 0f) return
        val c = camera
        // Where the head looks is known before the body moves; the body then moves by the hands.
        c.place(body.x, body.y, body.z, yaw, pitch, roll)
        world.clearDummies()
        for (d in dummies) if (d.up && d.standing > .5f) world.addDummy(d.x, d.z, Dummy.HEIGHT * d.standing)

        for (i in 0..1) {
            handPresent[i] = hands[i].present
            if (!handPresent[i]) continue
            palmOf(hands[i].joints)
            offsets[i * 3] = c.worldX(local[0], local[1], local[2]) * ARM_SCALE
            offsets[i * 3 + 1] = c.worldY(local[0], local[1], local[2]) * ARM_SCALE
            offsets[i * 3 + 2] = c.worldZ(local[0], local[1], local[2]) * ARM_SCALE
        }
        body.update(dt, world, handPresent, offsets)
        c.place(body.x, body.y, body.z, yaw, pitch, roll)

        for (i in 0..1) {
            if (!handPresent[i]) { speedKnown[i] = false; continue }
            val j = hands[i].joints
            palmOf(j)
            val px = body.hand[i * 3]; val py = body.hand[i * 3 + 1]; val pz = body.hand[i * 3 + 2]
            palm[i * 3] = px; palm[i * 3 + 1] = py; palm[i * 3 + 2] = pz
            val out = handJoints[i]
            for (k in 0 until HAND_POINTS) {
                val x = (j[k * 3] - local[0]) * ARM_SCALE; val y = (j[k * 3 + 1] - local[1]) * ARM_SCALE
                val z = (j[k * 3 + 2] - local[2]) * ARM_SCALE
                out[k * 3] = px + c.worldX(x, y, z); out[k * 3 + 1] = py + c.worldY(x, y, z); out[k * 3 + 2] = pz + c.worldZ(x, y, z)
            }
            // Speed of the real hand in the world, which is what a thrown weapon leaves with.
            val qx = body.x + offsets[i * 3]; val qy = body.y + offsets[i * 3 + 1]; val qz = body.z + offsets[i * 3 + 2]
            if (speedKnown[i]) {
                val k = min(1f, dt * 18f)
                palmSpeed[i * 3] += ((qx - lastFree[i * 3]) / dt - palmSpeed[i * 3]) * k
                palmSpeed[i * 3 + 1] += ((qy - lastFree[i * 3 + 1]) / dt - palmSpeed[i * 3 + 1]) * k
                palmSpeed[i * 3 + 2] += ((qz - lastFree[i * 3 + 2]) / dt - palmSpeed[i * 3 + 2]) * k
            } else { palmSpeed[i * 3] = 0f; palmSpeed[i * 3 + 1] = 0f; palmSpeed[i * 3 + 2] = 0f; speedKnown[i] = true }
            lastFree[i * 3] = qx; lastFree[i * 3 + 1] = qy; lastFree[i * 3 + 2] = qz
        }

        for (i in 0..1) {
            val present = handPresent[i]
            // Letting go needs a hand that is seen open for a moment: a fast swing blurs the
            // picture and must not throw the weapon away.
            if (grabLock[i] > 0f) grabLock[i] -= dt
            if (present && !hands[i].closed) {
                val fastest = openSpeed[i * 3] * openSpeed[i * 3] + openSpeed[i * 3 + 1] * openSpeed[i * 3 + 1] + openSpeed[i * 3 + 2] * openSpeed[i * 3 + 2]
                val now = palmSpeed[i * 3] * palmSpeed[i * 3] + palmSpeed[i * 3 + 1] * palmSpeed[i * 3 + 1] + palmSpeed[i * 3 + 2] * palmSpeed[i * 3 + 2]
                if (openFor[i] == 0f || now > fastest) {
                    openSpeed[i * 3] = palmSpeed[i * 3]; openSpeed[i * 3 + 1] = palmSpeed[i * 3 + 1]; openSpeed[i * 3 + 2] = palmSpeed[i * 3 + 2]
                }
                openFor[i] += dt
            } else openFor[i] = 0f
            val carried = held[i]
            if (carried != null) {
                if (openFor[i] > DROP_AFTER) {
                    release(carried, openSpeed[i * 3], openSpeed[i * 3 + 1], openSpeed[i * 3 + 2])
                    held[i] = null; grabLock[i] = GRAB_LOCK
                } else if (present) {
                    val j = handJoints[i]
                    // The weapon lies along the knuckles, from the little finger to the index, and
                    // turns its edge the way the knuckles face.
                    carry(carried, dt, palm[i * 3], palm[i * 3 + 1], palm[i * 3 + 2],
                        j[15] - j[51], j[16] - j[52], j[17] - j[53], j[27] - j[0], j[28] - j[1], j[29] - j[2])
                } else {
                    // The camera does not see the hand: the weapon waits at its side of the view.
                    val side = if (i == 0) -REST_X else REST_X
                    carry(carried, dt,
                        body.x + c.worldX(side, REST_Y, REST_Z), body.y + c.worldY(side, REST_Y, REST_Z),
                        body.z + c.worldZ(side, REST_Y, REST_Z),
                        c.worldX(-.25f, .9f, -.35f), c.worldY(-.25f, .9f, -.35f), c.worldZ(-.25f, .9f, -.35f),
                        c.worldX(0f, 0f, -1f), c.worldY(0f, 0f, -1f), c.worldZ(0f, 0f, -1f))
                }
            } else if (present && hands[i].closed && grabLock[i] <= 0f) {
                held[i] = weaponInReach(i)?.also {
                    it.held = true; it.flying = false; it.tipKnown = false; it.tipSpeed = 0f
                }
            }
        }

        for (w in weapons) if (!w.held) { if (w.flying) fly(w, dt) else settle(w, dt) }
        for (d in dummies) sway(d, dt)
        for (w in weapons) if (w.held || w.flying) strike(w, dt)
    }

    private fun palmOf(j: FloatArray) {
        var x = 0f; var y = 0f; var z = 0f
        for (i in HandGeometry.PALM) { x += j[i * 3]; y += j[i * 3 + 1]; z += j[i * 3 + 2] }
        val n = HandGeometry.PALM.size
        local[0] = x / n; local[1] = y / n; local[2] = z / n
    }

    /**
     * The free weapon whose grip the hand is at. Depth is the weakest part of tracking with one
     * camera, so the test is narrow across the line of sight and generous along it.
     */
    private fun weaponInReach(hand: Int): Weapon? {
        val hx = palm[hand * 3]; val hy = palm[hand * 3 + 1]; val hz = palm[hand * 3 + 2]
        var dx = hx - body.x; var dy = hy - body.y; var dz = hz - body.z
        val handDistance = sqrt(dx * dx + dy * dy + dz * dz)
        if (handDistance < 1e-4f) return null
        dx /= handDistance; dy /= handDistance; dz /= handDistance
        var best: Weapon? = null
        var bestOff = GRAB_REACH
        for (w in weapons) {
            if (w.held) continue
            // Nearest point of the grip, which runs a little way along the weapon on both sides.
            val t = ((hx - w.x) * w.ax + (hy - w.y) * w.ay + (hz - w.z) * w.az).coerceIn(-GRIP_BACK, GRIP_FRONT)
            val gx = w.x + w.ax * t - hx; val gy = w.y + w.ay * t - hy; val gz = w.z + w.az * t - hz
            val along = gx * dx + gy * dy + gz * dz
            val px = gx - dx * along; val py = gy - dy * along; val pz = gz - dz * along
            val off = sqrt(px * px + py * py + pz * pz + along * along * GRAB_ALONG * GRAB_ALONG)
            if (off < bestOff) { bestOff = off; best = w }
        }
        return best
    }

    /** Moves a held weapon to the hand and turns it along the knuckles. */
    private fun carry(
        w: Weapon, dt: Float, x: Float, y: Float, z: Float,
        axisX: Float, axisY: Float, axisZ: Float, edgeX: Float, edgeY: Float, edgeZ: Float,
    ) {
        val k = min(1f, dt * 25f)
        w.x += (x - w.x) * k; w.y += (y - w.y) * k; w.z += (z - w.z) * k
        turn(w, axisX, axisY, axisZ, edgeX, edgeY, edgeZ, min(1f, dt * 14f))
    }

    /** The hand opened: the weapon goes on with the speed the hand had, turning end over end. */
    private fun release(w: Weapon, speedX: Float, speedY: Float, speedZ: Float) {
        w.held = false; w.flying = true; w.tipKnown = false; w.tipSpeed = 0f
        w.vx = speedX; w.vy = speedY; w.vz = speedZ
        val speed = sqrt(speedX * speedX + speedY * speedY + speedZ * speedZ)
        if (speed > MAX_THROW) { val f = MAX_THROW / speed; w.vx *= f; w.vy *= f; w.vz *= f }
        w.spin = if (speed > 1.5f) min(speed * 1.6f, 11f) else 0f
    }

    /** A weapon on its way: gravity, and it bounces off the sand, the wall, the seats and the dummies. */
    private fun fly(w: Weapon, dt: Float) {
        w.vy -= GRAVITY * dt
        w.x += w.vx * dt; w.y += w.vy * dt; w.z += w.vz * dt
        if (w.spin != 0f) {
            val cs = cos(w.spin * dt); val sn = sin(w.spin * dt)
            val nax = w.ax * cs + w.bx * sn; val nay = w.ay * cs + w.by * sn; val naz = w.az * cs + w.bz * sn
            w.bx = w.bx * cs - w.ax * sn; w.by = w.by * cs - w.ay * sn; w.bz = w.bz * cs - w.az * sn
            w.ax = nax; w.ay = nay; w.az = naz
            turn(w, w.ax, w.ay, w.az, w.bx, w.by, w.bz, 0f)
        }
        var landed = false
        for (end in 0..1) {
            val along = if (end == 0) 0f else w.strikeTo
            val d = world.distance(w.x + w.ax * along, w.y + w.ay * along, w.z + w.az * along) - WEAPON_RADIUS
            if (d >= 0f) continue
            val nx = world.normal[0]; val ny = world.normal[1]; val nz = world.normal[2]
            w.x -= nx * d; w.y -= ny * d; w.z -= nz * d
            val into = w.vx * nx + w.vy * ny + w.vz * nz
            if (into < 0f) {
                val speed = sqrt(w.vx * w.vx + w.vy * w.vy + w.vz * w.vz)
                val dummy = world.lastDummy
                if (dummy >= 0 && speed >= BLOW_SPEED) hit(dummyAt(dummy), speed, w.vx, w.vz)
                w.vx -= (1f + BOUNCE) * into * nx; w.vy -= (1f + BOUNCE) * into * ny; w.vz -= (1f + BOUNCE) * into * nz
                w.vx *= SLIDE; w.vy *= SLIDE; w.vz *= SLIDE
                w.spin *= .5f
            }
            if (ny > .7f && w.vx * w.vx + w.vy * w.vy + w.vz * w.vz < REST_SPEED * REST_SPEED) landed = true
        }
        if (landed) { w.flying = false; w.vx = 0f; w.vy = 0f; w.vz = 0f; w.spin = 0f; w.tipKnown = false; w.tipSpeed = 0f }
    }

    /** The dummy that [ArenaWorld] holds at [index]: only the standing ones are in it. */
    private fun dummyAt(index: Int): Dummy? {
        var n = 0
        for (d in dummies) if (d.up && d.standing > .5f) { if (n == index) return d; n++ }
        return null
    }

    /** Eases the axes of [w] towards the given ones and keeps them unit length and square. */
    private fun turn(w: Weapon, axisX: Float, axisY: Float, axisZ: Float, edgeX: Float, edgeY: Float, edgeZ: Float, k: Float) {
        val al = sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ)
        if (al > 1e-4f) {
            w.ax += (axisX / al - w.ax) * k; w.ay += (axisY / al - w.ay) * k; w.az += (axisZ / al - w.az) * k
        }
        var l = sqrt(w.ax * w.ax + w.ay * w.ay + w.az * w.az)
        if (l < 1e-4f) { w.ax = 0f; w.ay = 1f; w.az = 0f } else { w.ax /= l; w.ay /= l; w.az /= l }
        val el = sqrt(edgeX * edgeX + edgeY * edgeY + edgeZ * edgeZ)
        if (el > 1e-4f) {
            w.bx += (edgeX / el - w.bx) * k; w.by += (edgeY / el - w.by) * k; w.bz += (edgeZ / el - w.bz) * k
        }
        // The edge is square to the weapon: take away what points along it.
        val along = w.bx * w.ax + w.by * w.ay + w.bz * w.az
        w.bx -= along * w.ax; w.by -= along * w.ay; w.bz -= along * w.az
        l = sqrt(w.bx * w.bx + w.by * w.by + w.bz * w.bz)
        if (l < 1e-3f) {
            // Edge and weapon were in line: any direction across the weapon will do.
            if (abs(w.ay) < .9f) { w.bx = -w.az; w.by = 0f; w.bz = w.ax } else { w.bx = 1f; w.by = 0f; w.bz = 0f }
            val again = w.bx * w.ax + w.by * w.ay + w.bz * w.az
            w.bx -= again * w.ax; w.by -= again * w.ay; w.bz -= again * w.az
            l = sqrt(w.bx * w.bx + w.by * w.by + w.bz * w.bz)
        }
        w.bx /= l; w.by /= l; w.bz /= l
    }

    /** A weapon that came to rest stands stuck in the floor, grip up, ready to be taken again. */
    private fun settle(w: Weapon, dt: Float) {
        val rest = world.floorAt(w.x, w.z) + w.plantedHeight
        w.y += (rest - w.y) * min(1f, dt * 10f)
        turn(w, 0f, -1f, 0f, w.bx, 0f, w.bz, min(1f, dt * 9f))
    }

    private fun sway(d: Dummy, dt: Float) {
        if (d.cooldown > 0f) d.cooldown -= dt
        if (d.awayFor > 0f) {
            d.awayFor -= dt
            if (d.awayFor <= 0f) { d.health = Dummy.FULL_HEALTH; d.leanX = 0f; d.leanZ = 0f; d.leanSpeedX = 0f; d.leanSpeedZ = 0f }
        }
        val target = if (d.up) 1f else 0f
        d.standing += (target - d.standing) * min(1f, dt * 6f)
        // A damped spring brings the figure back upright.
        d.leanSpeedX += (-SPRING * d.leanX - DAMPING * d.leanSpeedX) * dt
        d.leanSpeedZ += (-SPRING * d.leanZ - DAMPING * d.leanSpeedZ) * dt
        d.leanX = (d.leanX + d.leanSpeedX * dt).coerceIn(-MAX_LEAN, MAX_LEAN)
        d.leanZ = (d.leanZ + d.leanSpeedZ * dt).coerceIn(-MAX_LEAN, MAX_LEAN)
    }

    private fun strike(w: Weapon, dt: Float) {
        val tx = w.x + w.ax * w.strikeTo; val ty = w.y + w.ay * w.strikeTo; val tz = w.z + w.az * w.strikeTo
        if (!w.tipKnown) { w.tipX = tx; w.tipY = ty; w.tipZ = tz; w.tipKnown = true; w.tipSpeed = 0f; return }
        val mx = tx - w.tipX; val my = ty - w.tipY; val mz = tz - w.tipZ
        val speed = sqrt(mx * mx + my * my + mz * mz) / dt
        // Quick to rise, slower to fall: one drawn frame of a swing is enough to count.
        w.tipSpeed = if (speed > w.tipSpeed) speed else w.tipSpeed + (speed - w.tipSpeed) * min(1f, dt * 12f)
        if (w.tipSpeed >= BLOW_SPEED) {
            val sx = w.x + w.ax * w.strikeFrom; val sy = w.y + w.ay * w.strikeFrom; val sz = w.z + w.az * w.strikeFrom
            for (d in dummies) {
                // Either the striking part is in the figure now, or its end passed through it
                // since the last frame (a fast swing covers more than a figure per frame).
                if (!crosses(d, sx, sy, sz, tx, ty, tz) && !crosses(d, w.tipX, w.tipY, w.tipZ, tx, ty, tz)) continue
                hit(d, w.tipSpeed, mx, mz)
            }
        }
        w.tipX = tx; w.tipY = ty; w.tipZ = tz
    }

    /** A weapon moving at [speed] in direction ([dirX], [dirZ]) lands on [d]. */
    private fun hit(d: Dummy?, speed: Float, dirX: Float, dirZ: Float) {
        if (d == null || !d.up || d.cooldown > 0f) return
        val strength = ((speed - BLOW_SPEED) / (HEAVY_BLOW - BLOW_SPEED)).coerceIn(0f, 1f)
        d.cooldown = BLOW_COOLDOWN
        d.health -= if (strength >= 1f) 2 else 1
        var px = dirX; var pz = dirZ
        val pl = sqrt(px * px + pz * pz)
        if (pl > 1e-5f) { px /= pl; pz /= pl } else { px = d.x - body.x; pz = d.z - body.z; val l = max(1e-4f, sqrt(px * px + pz * pz)); px /= l; pz /= l }
        val push = 2.5f + 4f * strength
        d.leanSpeedX += px * push; d.leanSpeedZ += pz * push
        blows++
        blowStrength = max(blowStrength, .35f + .65f * strength)
        if (d.health <= 0) { dummiesDown++; d.awayFor = AWAY_SECONDS }
    }

    /** True when the segment from a to b passes through the body of [d]. */
    private fun crosses(d: Dummy, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Boolean {
        val ex = bx - ax; val ez = bz - az
        val l2 = ex * ex + ez * ez
        val t = if (l2 < 1e-8f) 0f else (((d.x - ax) * ex + (d.z - az) * ez) / l2).coerceIn(0f, 1f)
        val cx = ax + ex * t - d.x; val cz = az + ez * t - d.z
        if (cx * cx + cz * cz > HIT_RADIUS * HIT_RADIUS) return false
        val y = ay + (by - ay) * t
        return y > .25f && y < Dummy.HEIGHT + .1f
    }

    companion object {
        const val DUMMY_COUNT = 6
        const val DUMMY_RING = 5.2f
        /**
         * Everything the hands do is this many times bigger than what the real hands do: the place
         * of the hand in front of the eyes and the hand itself. So the hand covers the same part of
         * the view as the real one, and arms of a real length reach the ground.
         */
        const val ARM_SCALE = 2.5f
        /** The striking end of a weapon has to move at least this fast (m/s) for a blow. */
        const val BLOW_SPEED = 2.2f
        const val HEAVY_BLOW = 8f
        const val DROP_AFTER = .25f
        const val AWAY_SECONDS = 4f
        private const val GRAB_LOCK = .4f
        /** How near the grip the palm has to be, with distance along the line of sight counting for [GRAB_ALONG]. */
        private const val GRAB_REACH = .34f
        private const val GRAB_ALONG = .6f
        private const val GRIP_BACK = .15f
        private const val GRIP_FRONT = .20f
        private const val HIT_RADIUS = Dummy.RADIUS + .08f
        private const val BLOW_COOLDOWN = .35f
        private const val SPRING = 55f
        private const val DAMPING = 4.5f
        private const val MAX_LEAN = .55f
        private const val GRAVITY = 9.8f
        private const val MAX_THROW = 12f
        private const val WEAPON_RADIUS = .06f
        private const val BOUNCE = .35f
        private const val SLIDE = .7f
        private const val REST_SPEED = 1.3f
        // Where a held weapon waits while the camera does not see the hand (camera space).
        private const val REST_X = .42f
        private const val REST_Y = -.55f
        private const val REST_Z = -1.3f
    }
}
