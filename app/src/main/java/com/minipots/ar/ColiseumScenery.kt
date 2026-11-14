package com.minipots.ar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// The coliseum itself, built once as plain triangle lists. Pure Kotlin (no OpenGL here), so the
// geometry can be checked by JVM unit tests. Each vertex is position, normal, colour: 9 floats.

/** Collects flat-shaded triangles. */
class SceneBuilder {
    private var data = FloatArray(1 shl 15)
    var size = 0
        private set

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float) {
        if (size + 9 > data.size) data = data.copyOf(data.size * 2)
        val d = data; var n = size
        d[n++] = x; d[n++] = y; d[n++] = z; d[n++] = nx; d[n++] = ny; d[n++] = nz; d[n++] = r; d[n++] = g; d[n++] = b
        size = n
    }

    /**
     * Triangle a, b, c with one colour. Its normal is turned to the side that ([hx], [hy], [hz])
     * points to, so the order of the corners does not matter. Triangles without area are dropped.
     */
    fun triangle(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float, r: Float, g: Float, b: Float,
    ) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-9f) return
        val sign = if (nx * hx + ny * hy + nz * hz < 0f) -1f / l else 1f / l
        nx *= sign; ny *= sign; nz *= sign
        vertex(ax, ay, az, nx, ny, nz, r, g, b)
        vertex(bx, by, bz, nx, ny, nz, r, g, b)
        vertex(cx, cy, cz, nx, ny, nz, r, g, b)
    }

    fun result(): FloatArray = data.copyOf(size)
}

object ColiseumScenery {
    const val FLOATS_PER_VERTEX = 9
    /** Direction towards the sun; the light of the whole scene comes from there. */
    const val SUN_X = .45f
    const val SUN_Y = .50f
    const val SUN_Z = -.74f

    private const val SEGMENTS = 40
    private const val TWO_PI = 6.2831855f
    private const val UP = 0
    private const val INWARDS = 1
    private const val DOWN = 2

    /** A small, repeatable variation in brightness, so stone and sand are not one flat colour. */
    private fun shade(i: Int, salt: Int, amount: Float): Float {
        var h = i * 374761393 + salt * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return 1f + ((h and 0xffff) / 65535f - .5f) * amount
    }

    /**
     * A band all around the arena between two circles: ([r0], [y0]) and ([r1], [y1]). [facing]
     * tells which way it is seen from, [vary] how much its blocks differ in brightness.
     */
    private fun band(
        b: SceneBuilder, r0: Float, y0: Float, r1: Float, y1: Float, facing: Int,
        red: Float, green: Float, blue: Float, salt: Int, vary: Float,
    ) {
        for (i in 0 until SEGMENTS) {
            val s = shade(i, salt, vary)
            block(b, i, r0, y0, r1, y1, facing, red * s, green * s, blue * s)
        }
    }

    /** One block of a band: the piece between the angles of segment [i] and the next. */
    private fun block(
        b: SceneBuilder, i: Int, r0: Float, y0: Float, r1: Float, y1: Float, facing: Int,
        red: Float, green: Float, blue: Float,
    ) {
        val a0 = TWO_PI * i / SEGMENTS; val a1 = TWO_PI * (i + 1) / SEGMENTS
        val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
        val mid = (a0 + a1) * .5f
        val hx = if (facing == INWARDS) -cos(mid) else 0f
        val hy = if (facing == UP) 1f else if (facing == DOWN) -1f else 0f
        val hz = if (facing == INWARDS) -sin(mid) else 0f
        b.triangle(c0 * r0, y0, s0 * r0, c1 * r0, y0, s1 * r0, c1 * r1, y1, s1 * r1, hx, hy, hz, red, green, blue)
        b.triangle(c0 * r0, y0, s0 * r0, c1 * r1, y1, s1 * r1, c0 * r1, y1, s0 * r1, hx, hy, hz, red, green, blue)
    }

    private fun column(b: SceneBuilder, x: Float, z: Float, y0: Float, y1: Float, radius: Float, red: Float, green: Float, blue: Float) {
        val sides = 6
        for (i in 0 until sides) {
            val a0 = TWO_PI * i / sides; val a1 = TWO_PI * (i + 1) / sides
            val mid = (a0 + a1) * .5f
            val x0 = x + cos(a0) * radius; val z0 = z + sin(a0) * radius
            val x1 = x + cos(a1) * radius; val z1 = z + sin(a1) * radius
            b.triangle(x0, y0, z0, x1, y0, z1, x1, y1, z1, cos(mid), 0f, sin(mid), red, green, blue)
            b.triangle(x0, y0, z0, x1, y1, z1, x0, y1, z0, cos(mid), 0f, sin(mid), red, green, blue)
        }
    }

    /** Sand, wall with four gates, rows of seats, a colonnade on top and banners on the wall. */
    fun arena(): FloatArray {
        val b = SceneBuilder()
        val radius = Arena.RADIUS; val wall = Arena.WALL_HEIGHT

        // Sand, in rings so that it gets a soft patchwork of tones. One ring is a paler circle
        // around the centre of the arena.
        val rings = floatArrayOf(0f, 1.5f, 1.8f, 4f, 6.5f, 9f, 11.5f, radius)
        for (k in 0 until rings.size - 1) {
            if (k == 1) band(b, rings[k], 0f, rings[k + 1], 0f, UP, .98f, .90f, .72f, k, .02f)
            else band(b, rings[k], 0f, rings[k + 1], 0f, UP, .93f, .80f, .57f, k, .07f)
        }

        // Wall around the sand. Two blocks side by side on each of the four sides are a dark gate.
        val gateTop = 2.2f
        for (i in 0 until SEGMENTS) {
            val gate = i % 10 == 0 || i % 10 == 9
            val s = shade(i, 20, .08f)
            if (gate) block(b, i, radius, 0f, radius, gateTop, INWARDS, .21f, .16f, .13f)
            else block(b, i, radius, 0f, radius, gateTop, INWARDS, .76f * s, .69f * s, .60f * s)
            val t = shade(i, 21, .06f)
            block(b, i, radius, gateTop, radius, wall, INWARDS, .84f * t, .78f * t, .69f * t)
        }
        band(b, radius, wall, radius + 1f, wall, UP, .88f, .83f, .75f, 22, .04f)

        // Rows of seats, each higher and further back than the one before.
        var r = radius + 1f
        var y = wall
        for (k in 0 until 5) {
            band(b, r, y, r, y + .9f, INWARDS, .78f, .72f, .64f, 30 + k, .07f)
            band(b, r, y + .9f, r + 1.5f, y + .9f, UP, .87f, .82f, .74f, 40 + k, .05f)
            r += 1.5f; y += .9f
        }

        // On top: a low wall, columns and the beam they carry, open to the sky in between.
        band(b, r, y, r, y + .7f, INWARDS, .80f, .74f, .66f, 50, .06f)
        val columns = 20
        for (i in 0 until columns) {
            val a = TWO_PI * (i + .5f) / columns
            column(b, cos(a) * (r - .5f), sin(a) * (r - .5f), y, y + 2.9f, .32f, .90f, .86f, .79f)
        }
        band(b, r - 1f, y + 2.9f, r - 1f, y + 3.7f, INWARDS, .86f, .81f, .73f, 51, .05f)
        band(b, r - 1f, y + 2.9f, r, y + 2.9f, DOWN, .70f, .65f, .58f, 52, .04f)

        // Banners hanging in front of the wall, between the gates.
        val banners = 8
        for (i in 0 until banners) {
            val a = TWO_PI * (i + .5f) / banners
            val c = cos(a); val s = sin(a)
            val at = radius - .25f
            fun px(t: Float) = c * at - s * t
            fun pz(t: Float) = s * at + c * t
            val red = if (i % 2 == 0) .76f else .95f
            val green = if (i % 2 == 0) .17f else .74f
            val blue = if (i % 2 == 0) .19f else .22f
            val half = .6f; val top = 2.8f; val bottom = 1.3f
            b.triangle(px(-half), top, pz(-half), px(half), top, pz(half), px(half), bottom, pz(half), -c, 0f, -s, red, green, blue)
            b.triangle(px(-half), top, pz(-half), px(half), bottom, pz(half), px(-half), bottom, pz(-half), -c, 0f, -s, red, green, blue)
            b.triangle(px(-half), bottom, pz(-half), px(half), bottom, pz(half), px(0f), bottom - .4f, pz(0f), -c, 0f, -s, red, green, blue)
        }
        return b.result()
    }

    /**
     * The sky: a dome that goes from a warm horizon to a soft blue, the sun and a few clouds.
     * It is drawn around the eyes, unlit and before everything else.
     */
    fun sky(): FloatArray {
        val b = SceneBuilder()
        val far = 200f
        val rows = 7; val columns = 24
        val color = FloatArray(3)
        fun tone(degrees: Float) {
            // Horizon, a pale band above it, then blue.
            val t: Float
            val r0: Float; val g0: Float; val b0: Float; val r1: Float; val g1: Float; val b1: Float
            if (degrees <= 22f) {
                t = (degrees / 22f).coerceIn(0f, 1f)
                r0 = 1f; g0 = .86f; b0 = .70f; r1 = .76f; g1 = .86f; b1 = .95f
            } else {
                t = ((degrees - 22f) / 68f).coerceIn(0f, 1f)
                r0 = .76f; g0 = .86f; b0 = .95f; r1 = .40f; g1 = .62f; b1 = .90f
            }
            color[0] = r0 + (r1 - r0) * t; color[1] = g0 + (g1 - g0) * t; color[2] = b0 + (b1 - b0) * t
        }
        fun corner(row: Int, column: Int) {
            val degrees = -12f + 102f * row / rows
            val e = degrees * (PI.toFloat() / 180f); val a = TWO_PI * column / columns
            tone(degrees)
            b.vertex(cos(e) * cos(a) * far, sin(e) * far, cos(e) * sin(a) * far, 0f, 1f, 0f, color[0], color[1], color[2])
        }
        for (row in 0 until rows) for (column in 0 until columns) {
            corner(row, column); corner(row + 1, column); corner(row + 1, column + 1)
            corner(row, column); corner(row + 1, column + 1); corner(row, column + 1)
        }

        // Sun with a pale ring of light around it.
        ellipse(b, SUN_X, SUN_Y, SUN_Z, 190f, 0f, 0f, 17f, 17f, 1f, .93f, .80f)
        ellipse(b, SUN_X, SUN_Y, SUN_Z, 185f, 0f, 0f, 8.5f, 8.5f, 1f, .98f, .89f)

        // Clouds: each one a few flat ovals side by side.
        val clouds = floatArrayOf(
            // direction in degrees around and above the horizon, then width
            200f, 30f, 26f,
            265f, 42f, 20f,
            120f, 36f, 30f,
            40f, 52f, 22f,
            330f, 28f, 24f,
            165f, 58f, 16f,
        )
        for (i in 0 until clouds.size / 3) {
            val a = clouds[i * 3] * (PI.toFloat() / 180f); val e = clouds[i * 3 + 1] * (PI.toFloat() / 180f)
            val w = clouds[i * 3 + 2]
            val dx = cos(e) * cos(a); val dy = sin(e); val dz = cos(e) * sin(a)
            ellipse(b, dx, dy, dz, 180f, -w * .45f, w * .05f, w * .50f, w * .19f, .97f, .96f, .96f)
            ellipse(b, dx, dy, dz, 179f, w * .40f, w * .02f, w * .55f, w * .17f, .98f, .97f, .96f)
            ellipse(b, dx, dy, dz, 178f, 0f, w * .12f, w * .62f, w * .26f, 1f, .99f, .98f)
        }
        return b.result()
    }

    /**
     * Flat oval on the sky, facing the centre. It sits at [distance] in direction ([dx], [dy],
     * [dz]), moved sideways by [offsetAcross] and upwards by [offsetUp]; [across] and [up] are its
     * half sizes. The direction must not be straight up.
     */
    private fun ellipse(
        b: SceneBuilder, dx: Float, dy: Float, dz: Float, distance: Float,
        offsetAcross: Float, offsetUp: Float, across: Float, up: Float, red: Float, green: Float, blue: Float,
    ) {
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        val x = dx / dl; val y = dy / dl; val z = dz / dl
        // Sideways along the horizon, and the direction that climbs the dome.
        var tx = -z; var tz = x
        val tl = sqrt(tx * tx + tz * tz); tx /= tl; tz /= tl
        val ux = -tz * y; val uy = tz * x - tx * z; val uz = tx * y
        val cx = x * distance + tx * offsetAcross + ux * offsetUp
        val cy = y * distance + uy * offsetUp
        val cz = z * distance + tz * offsetAcross + uz * offsetUp
        val steps = 14
        for (i in 0 until steps) {
            val a0 = TWO_PI * i / steps; val a1 = TWO_PI * (i + 1) / steps
            val p0 = cos(a0) * across; val q0 = sin(a0) * up
            val p1 = cos(a1) * across; val q1 = sin(a1) * up
            b.vertex(cx, cy, cz, 0f, 1f, 0f, red, green, blue)
            b.vertex(cx + tx * p0 + ux * q0, cy + uy * q0, cz + tz * p0 + uz * q0, 0f, 1f, 0f, red, green, blue)
            b.vertex(cx + tx * p1 + ux * q1, cy + uy * q1, cz + tz * p1 + uz * q1, 0f, 1f, 0f, red, green, blue)
        }
    }
}
