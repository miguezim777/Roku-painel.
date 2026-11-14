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
 *    phone, measured by the accelerometer; turning is done with the finger.
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

/** First-person camera of the arena. */
class ArenaCamera {
    val viewProjection = FloatArray(16)
    var tanHalfX = TAN_HALF_Y * .5f
        private set
    val tanHalfY: Float get() = TAN_HALF_Y
    var eyeX = 0f
        private set
    var eyeY = 0f
        private set
    var eyeZ = 0f
        private set
    // Orthonormal basis: right (r), up (u), forward (f).
    private var rx = 1f; private var rz = 0f
    private var ux = 0f; private var uy = 1f; private var uz = 0f
    private var fx = 0f; private var fy = 0f; private var fz = -1f

    /** [aspect] is width / height of the render surface. */
    fun setAspect(aspect: Float) { tanHalfX = TAN_HALF_Y * aspect }

    fun place(x: Float, y: Float, z: Float, yaw: Float, pitch: Float) {
        eyeX = x; eyeY = y; eyeZ = z
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        fx = sy * cp; fy = sp; fz = -cy * cp
        rx = cy; rz = sy
        ux = -sy * sp; uy = cp; uz = cy * sp
        val m = viewProjection
        val px = 1f / tanHalfX; val py = 1f / TAN_HALF_Y
        val a = (FAR + NEAR) / (NEAR - FAR); val b = 2f * FAR * NEAR / (NEAR - FAR)
        val tx = -(rx * x + rz * z)
        val ty = -(ux * x + uy * y + uz * z)
        val tz = fx * x + fy * y + fz * z
        m[0] = px * rx; m[4] = 0f; m[8] = px * rz; m[12] = px * tx
        m[1] = py * ux; m[5] = py * uy; m[9] = py * uz; m[13] = py * ty
        m[2] = -a * fx; m[6] = -a * fy; m[10] = -a * fz; m[14] = a * tz + b
        m[3] = fx; m[7] = fy; m[11] = fz; m[15] = -tz
    }

    /** World direction of the camera-space vector ([x], [y], [z]), one component at a time. */
    fun worldX(x: Float, y: Float, z: Float) = rx * x + ux * y - fx * z
    fun worldY(x: Float, y: Float, z: Float) = uy * y - fy * z
    fun worldZ(x: Float, y: Float, z: Float) = rz * x + uz * y - fz * z

    companion object {
        /** Half of the vertical field of view: 70 degrees in all, close to a phone camera held upright. */
        const val TAN_HALF_Y = .70f
        private const val NEAR = .12f
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
     * the distance from the wrist to their knuckles. About 1.9 for a flat hand, below 1 for a fist.
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
    const val CLOSED_BELOW = 1.2f
    /** ...and open again above this one. */
    const val OPEN_ABOVE = 1.5f
}

/**
 * The player, moved the way Gorilla Tag does it: there are no legs. A hand that reaches the ground
 * holds on to it, and from then on moving the hand moves the body the other way. Pulling the hand
 * back walks forward, pushing down lifts the body, and the speed the body has when the hand lets
 * go is kept, so a quick push is a jump or a slide.
 */
class ArenaPlayer {
    /** Position of the eyes. */
    var x = 0f; var y = EYE_HEIGHT; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    /** True while the hand is planted on the ground. */
    var touching = false
        private set
    private var anchorX = 0f
    private var anchorZ = 0f

    fun reset(startX: Float, startZ: Float) {
        x = startX; y = EYE_HEIGHT; z = startZ
        vx = 0f; vy = 0f; vz = 0f; touching = false
    }

    /**
     * Advances by [dt] seconds. ([hx], [hy], [hz]) is where the hand is relative to the eyes, in
     * world axes; it is ignored unless [present]. [obstacles] holds x, z, radius triples the body
     * cannot enter; only the first [obstacleCount] triples are used.
     */
    fun update(dt: Float, present: Boolean, hx: Float, hy: Float, hz: Float, obstacles: FloatArray, obstacleCount: Int) {
        if (dt <= 0f) return
        if (present) {
            if (!touching && y + hy <= HAND_RADIUS) {
                touching = true; anchorX = x + hx; anchorZ = z + hz
            }
            if (touching) {
                // Height of the eyes that keeps the hand exactly on the ground.
                var ty = HAND_RADIUS - hy
                if (ty < EYE_HEIGHT - LIFT_OFF) {
                    touching = false
                } else {
                    ty = min(max(ty, EYE_HEIGHT), EYE_HEIGHT + MAX_LIFT)
                    // Firm but not rigid: tracking noise is not passed on to the whole view.
                    val k = min(1f, dt * FOLLOW)
                    val nx = x + (anchorX - hx - x) * k
                    val ny = y + (ty - y) * k
                    val nz = z + (anchorZ - hz - z) * k
                    val kv = min(1f, dt * 10f)
                    vx += ((nx - x) / dt - vx) * kv
                    vy += ((ny - y) / dt - vy) * kv
                    vz += ((nz - z) / dt - vz) * kv
                    x = nx; y = ny; z = nz
                    val speed = sqrt(vx * vx + vz * vz)
                    if (speed > MAX_SPEED) { vx *= MAX_SPEED / speed; vz *= MAX_SPEED / speed }
                    vy = vy.coerceIn(-MAX_RISE, MAX_RISE)
                }
            }
        } else touching = false

        if (!touching) {
            vy -= GRAVITY * dt
            x += vx * dt; y += vy * dt; z += vz * dt
            if (y <= EYE_HEIGHT) {
                y = EYE_HEIGHT
                if (vy < 0f) vy = 0f
                val f = exp(-dt * FRICTION)
                vx *= f; vz *= f
            }
        }

        for (i in 0 until obstacleCount) {
            val r = obstacles[i * 3 + 2] + BODY_RADIUS
            val dx = x - obstacles[i * 3]; val dz = z - obstacles[i * 3 + 1]
            val d = sqrt(dx * dx + dz * dz)
            if (d >= r) continue
            if (d < 1e-4f) { x += r; continue }
            x = obstacles[i * 3] + dx / d * r; z = obstacles[i * 3 + 1] + dz / d * r
            stopTowards(-dx / d, -dz / d)
        }
        val d = sqrt(x * x + z * z)
        if (d > WALK_RADIUS) {
            val nx = x / d; val nz = z / d
            x = nx * WALK_RADIUS; z = nz * WALK_RADIUS
            stopTowards(nx, nz)
        }
    }

    /** Removes the part of the speed that goes in direction ([nx], [nz]). */
    private fun stopTowards(nx: Float, nz: Float) {
        val into = vx * nx + vz * nz
        if (into > 0f) { vx -= into * nx; vz -= into * nz }
    }

    companion object {
        /** Height of the eyes at rest. Low on purpose: the hands have to reach the ground. */
        const val EYE_HEIGHT = .8f
        const val HAND_RADIUS = .06f
        const val BODY_RADIUS = .35f
        /** The body can walk up to this far from the centre of the arena. */
        const val WALK_RADIUS = Arena.RADIUS - .6f
        private const val LIFT_OFF = .03f
        private const val MAX_LIFT = 1.2f
        private const val FOLLOW = 20f
        private const val GRAVITY = 9.8f
        private const val FRICTION = 2.2f
        private const val MAX_SPEED = 7f
        private const val MAX_RISE = 5f
    }
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
    var fallSpeed = 0f
    /** Where the striking part starts and ends, measured from the grip along the weapon. */
    val strikeFrom: Float = when (kind) { WeaponKind.SWORD -> .25f; WeaponKind.AXE -> .62f; WeaponKind.MACE -> .70f }
    val strikeTo: Float = when (kind) { WeaponKind.SWORD -> 1.25f; WeaponKind.AXE -> .98f; WeaponKind.MACE -> 1.0f }
    /** Height of the grip when the weapon stands stuck in the sand, striking end down. */
    val plantedHeight: Float = when (kind) { WeaponKind.SWORD -> .98f; WeaponKind.AXE -> .86f; WeaponKind.MACE -> .88f }
    // Striking end in the previous frame, for the speed of the blow and the path it swept.
    var tipX = 0f; var tipY = 0f; var tipZ = 0f
    var tipSpeed = 0f
    var tipKnown = false

    fun plant(atX: Float, atZ: Float) {
        x = atX; y = plantedHeight; z = atZ
        ax = 0f; ay = -1f; az = 0f; bx = 1f; by = 0f; bz = 0f
        held = false; fallSpeed = 0f; tipKnown = false; tipSpeed = 0f
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

/**
 * The whole game: the player, the weapons standing in the sand and the training dummies.
 *
 * Each frame [update] is given where the phone looks and what the tracked hand does, in camera
 * space. A closed hand near a weapon picks it up; the weapon then follows the hand until the hand
 * is clearly open for a moment. A weapon that moves fast enough through a dummy is a blow.
 */
class Coliseum {
    val player = ArenaPlayer()
    val camera = ArenaCamera()
    val weapons = arrayOf(
        Weapon(WeaponKind.SWORD, 0f, Arena.START_Z - 2.0f),
        // Close together: a phone held upright sees a narrow strip, and all three are in it.
        Weapon(WeaponKind.AXE, -.6f, Arena.START_Z - 2.4f),
        Weapon(WeaponKind.MACE, .6f, Arena.START_Z - 2.4f),
    )
    val dummies = Array(DUMMY_COUNT) { i ->
        val a = 2f * PI.toFloat() * i / DUMMY_COUNT + .5f
        Dummy(sin(a) * DUMMY_RING, -cos(a) * DUMMY_RING)
    }
    var held: Weapon? = null
        private set
    /** Blows that landed, and dummies taken apart. */
    var blows = 0
        private set
    var dummiesDown = 0
        private set
    /** Strength (0..1) of a blow that landed in the last [update], else 0. */
    var blowStrength = 0f
        private set

    /** Hand in the world. Only meaningful while [handPresent]. */
    var handX = 0f
        private set
    var handY = 0f
        private set
    var handZ = 0f
        private set
    var handPresent = false
        private set

    private var openFor = 0f
    private var grabLock = 0f
    private val obstacles = FloatArray(DUMMY_COUNT * 3)

    init { restart() }

    fun restart() {
        player.reset(0f, Arena.START_Z)
        for (w in weapons) w.plant(w.homeX, w.homeZ)
        for (d in dummies) d.reset()
        held = null; blows = 0; dummiesDown = 0; blowStrength = 0f; openFor = 0f; grabLock = 0f
        handPresent = false
    }

    /**
     * [present]: a hand is tracked. ([lx], [ly], [lz]) is its place in camera space, already at the
     * scale of the player's long arms. [closed]: the hand is a fist. ([kx], [ky], [kz]) is the line
     * of the knuckles from the little finger to the index, and ([wx], [wy], [wz]) the direction
     * from the wrist to the knuckles, both in camera space; a held weapon lies along the first and
     * turns its edge to the second.
     */
    fun update(
        dt: Float, yaw: Float, pitch: Float,
        present: Boolean, lx: Float, ly: Float, lz: Float, closed: Boolean,
        kx: Float, ky: Float, kz: Float, wx: Float, wy: Float, wz: Float,
    ) {
        blowStrength = 0f
        if (dt <= 0f) return
        val c = camera
        // Where the phone looks is known before the body moves; the body then moves by the hand.
        c.place(player.x, player.y, player.z, yaw, pitch)
        var count = 0
        for (d in dummies) if (d.up) {
            obstacles[count * 3] = d.x; obstacles[count * 3 + 1] = d.z; obstacles[count * 3 + 2] = Dummy.RADIUS
            count++
        }
        val ox = c.worldX(lx, ly, lz); val oy = c.worldY(lx, ly, lz); val oz = c.worldZ(lx, ly, lz)
        player.update(dt, present, ox, oy, oz, obstacles, count)
        c.place(player.x, player.y, player.z, yaw, pitch)
        handPresent = present
        if (present) { handX = player.x + ox; handY = player.y + oy; handZ = player.z + oz }

        // Letting go needs a hand that is seen open for a moment: a fast swing blurs the picture
        // and must not throw the weapon away.
        if (grabLock > 0f) grabLock -= dt
        openFor = if (present && !closed) openFor + dt else 0f
        val carried = held
        if (carried != null) {
            if (openFor > DROP_AFTER) {
                carried.held = false; carried.fallSpeed = 0f; carried.tipKnown = false; carried.tipSpeed = 0f
                held = null; grabLock = GRAB_LOCK
            } else if (present) {
                carry(carried, dt, handX, handY, handZ,
                    c.worldX(kx, ky, kz), c.worldY(kx, ky, kz), c.worldZ(kx, ky, kz),
                    c.worldX(wx, wy, wz), c.worldY(wx, wy, wz), c.worldZ(wx, wy, wz))
            } else {
                // The hand is out of the camera's sight: the weapon waits at the side of the view.
                carry(carried, dt,
                    player.x + c.worldX(REST_X, REST_Y, REST_Z), player.y + c.worldY(REST_X, REST_Y, REST_Z),
                    player.z + c.worldZ(REST_X, REST_Y, REST_Z),
                    c.worldX(-.25f, .9f, -.35f), c.worldY(-.25f, .9f, -.35f), c.worldZ(-.25f, .9f, -.35f),
                    c.worldX(0f, 0f, -1f), c.worldY(0f, 0f, -1f), c.worldZ(0f, 0f, -1f))
            }
        } else if (present && closed && grabLock <= 0f) {
            held = weaponInReach()?.also { it.held = true; it.tipKnown = false; it.tipSpeed = 0f }
        }

        for (w in weapons) if (!w.held) settle(w, dt)
        for (d in dummies) sway(d, dt)
        held?.let { strike(it, dt) }
    }

    /**
     * The free weapon the hand is at. Depth is the weakest part of tracking with one camera, so the
     * test is narrow across the line of sight and generous along it.
     */
    private fun weaponInReach(): Weapon? {
        val ex = player.x; val ey = player.y; val ez = player.z
        var dx = handX - ex; var dy = handY - ey; var dz = handZ - ez
        val handDistance = sqrt(dx * dx + dy * dy + dz * dz)
        if (handDistance < 1e-4f) return null
        dx /= handDistance; dy /= handDistance; dz /= handDistance
        var best: Weapon? = null
        var bestOff = GRAB_ACROSS
        for (w in weapons) {
            val gx = w.x - ex; val gy = w.y - ey; val gz = w.z - ez
            val along = gx * dx + gy * dy + gz * dz
            if (abs(along - handDistance) > GRAB_ALONG) continue
            val px = gx - dx * along; val py = gy - dy * along; val pz = gz - dz * along
            val off = sqrt(px * px + py * py + pz * pz)
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

    /** A weapon nobody holds falls and stays stuck in the sand, grip up, ready to be taken again. */
    private fun settle(w: Weapon, dt: Float) {
        val d = sqrt(w.x * w.x + w.z * w.z)
        val limit = Arena.RADIUS - 1f
        if (d > limit) { w.x *= limit / d; w.z *= limit / d }
        if (w.y > w.plantedHeight) {
            w.fallSpeed += 9.8f * dt
            w.y = max(w.plantedHeight, w.y - w.fallSpeed * dt)
        } else { w.y = w.plantedHeight; w.fallSpeed = 0f }
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
                if (!d.up || d.cooldown > 0f) continue
                // Either the striking part is in the figure now, or its end passed through it
                // since the last frame (a fast swing covers more than a figure per frame).
                if (!crosses(d, sx, sy, sz, tx, ty, tz) && !crosses(d, w.tipX, w.tipY, w.tipZ, tx, ty, tz)) continue
                val strength = ((w.tipSpeed - BLOW_SPEED) / (HEAVY_BLOW - BLOW_SPEED)).coerceIn(0f, 1f)
                d.cooldown = BLOW_COOLDOWN
                d.health -= if (strength >= 1f) 2 else 1
                var px = mx; var pz = mz
                val pl = sqrt(px * px + pz * pz)
                if (pl > 1e-5f) { px /= pl; pz /= pl } else { px = d.x - player.x; pz = d.z - player.z; val l = max(1e-4f, sqrt(px * px + pz * pz)); px /= l; pz /= l }
                val push = 2.5f + 4f * strength
                d.leanSpeedX += px * push; d.leanSpeedZ += pz * push
                blows++
                blowStrength = max(blowStrength, .35f + .65f * strength)
                if (d.health <= 0) { dummiesDown++; d.awayFor = AWAY_SECONDS }
            }
        }
        w.tipX = tx; w.tipY = ty; w.tipZ = tz
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
        /** Real arm lengths are multiplied by this: the player's arms reach the ground. */
        const val ARM_SCALE = 2.6f
        /** The striking end of a weapon has to move at least this fast (m/s) for a blow. */
        const val BLOW_SPEED = 2.2f
        const val HEAVY_BLOW = 8f
        const val DROP_AFTER = .25f
        const val AWAY_SECONDS = 4f
        private const val GRAB_LOCK = .4f
        private const val GRAB_ACROSS = .42f
        private const val GRAB_ALONG = 1.1f
        private const val HIT_RADIUS = Dummy.RADIUS + .08f
        private const val BLOW_COOLDOWN = .35f
        private const val SPRING = 55f
        private const val DAMPING = 4.5f
        private const val MAX_LEAN = .55f
        // Where a held weapon waits while the camera does not see the hand (camera space).
        private const val REST_X = .42f
        private const val REST_Y = -.55f
        private const val REST_Z = -1.3f
    }
}
