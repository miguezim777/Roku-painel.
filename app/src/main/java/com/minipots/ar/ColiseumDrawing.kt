package com.minipots.ar

import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All OpenGL drawing of the coliseum game. OpenGL ES 2.0 with lighting per vertex, like the rest of
 * the app: the whole arena is one buffer and one draw call, the moving things are a handful of
 * shared shapes (ball, cylinder, box, disc) placed by a matrix. Nothing is allocated while drawing.
 */
class ColiseumDrawing {
    private val program = buildProgram(
        """
        attribute vec3 aPosition; attribute vec3 aNormal; attribute vec3 aColor;
        uniform mat4 uVp; uniform mat4 uModel; uniform vec4 uTint; uniform float uLit;
        varying vec4 vColor;
        void main() {
            gl_Position = uVp * (uModel * vec4(aPosition, 1.0));
            vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            float sun = max(dot(n, vec3(0.45, 0.50, -0.74)), 0.0);
            vec3 light = vec3(0.58, 0.60, 0.66) * (0.875 + 0.125 * n.y) + vec3(0.70, 0.62, 0.48) * sun;
            vec3 base = aColor * uTint.rgb;
            vColor = vec4(mix(base, min(base * light, 1.0), uLit), uTint.a);
        }
        """,
        """
        precision mediump float; varying vec4 vColor;
        void main() { gl_FragColor = vColor; }
        """,
    )
    private val uVp = glGetUniformLocation(program, "uVp")
    private val uModel = glGetUniformLocation(program, "uModel")
    private val uTint = glGetUniformLocation(program, "uTint")
    private val uLit = glGetUniformLocation(program, "uLit")

    private val arenaMesh = Mesh(ColiseumScenery.arena(), ColiseumScenery.FLOATS_PER_VERTEX)
    private val skyMesh = Mesh(ColiseumScenery.sky(), ColiseumScenery.FLOATS_PER_VERTEX)
    private val ball = Mesh(unitBall(), 6)
    private val cylinder = Mesh(unitCylinder(10), 6)
    private val box = Mesh(unitBox(), 6)
    private val disc = Mesh(unitDisc(20), 6)

    private val identity = FloatArray(16).also { Mat4.identity(it) }
    private val model = FloatArray(16)
    private val base = FloatArray(16)
    private val part = FloatArray(16)

    /** Selects the program and the camera for every other call of the frame. */
    fun begin(viewProjection: FloatArray) {
        glUseProgram(program)
        glUniformMatrix4fv(uVp, 1, false, viewProjection, 0)
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1)
    }

    /** The sky is drawn around the eyes, so it never comes closer. Depth must be off. */
    fun sky(eyeX: Float, eyeY: Float, eyeZ: Float) {
        Mat4.identity(model); Mat4.translate(model, eyeX, eyeY, eyeZ)
        draw(skyMesh, model, 1f, 1f, 1f, 1f, 0f)
    }

    fun arena() { draw(arenaMesh, identity, 1f, 1f, 1f, 1f, 1f) }

    /** Soft dark spot on the sand under something. Needs blending on and depth off. */
    fun shadow(x: Float, z: Float, radius: Float, strength: Float) {
        spot(x, z, radius, .22f, .15f, .08f, strength)
    }

    /** Flat coloured spot on the sand. Needs blending on and depth off. */
    fun spot(x: Float, z: Float, radius: Float, r: Float, g: Float, b: Float, a: Float) {
        Mat4.identity(model); Mat4.translate(model, x, .02f, z); Mat4.scale(model, radius, 1f, radius)
        draw(disc, model, r, g, b, a, 0f)
    }

    fun dummy(d: Dummy) {
        if (d.standing < .02f) return
        Place.leaning(base, d.x, d.z, d.leanX, d.leanZ, d.standing)
        // Straw gets darker with every blow taken.
        val worn = 1f - .12f * (Dummy.FULL_HEALTH - d.health).coerceIn(0, Dummy.FULL_HEALTH)
        tube(0f, .05f, .34f, .66f, .62f, .56f)
        tube(0f, .78f, .07f, .44f, .29f, .17f)
        tube(.62f, 1.30f, .27f, .88f * worn, .74f * worn, .40f * worn)
        tube(.90f, 1.02f, .285f, .76f, .21f, .19f)
        block(0f, 1.13f, 0f, 1.05f, .09f, .09f, .44f, .29f, .17f)
        sphere(0f, 1.51f, 0f, .19f, .19f, .19f, .92f * worn, .80f * worn, .50f * worn)
    }

    fun weapon(w: Weapon) {
        // Columns: across the weapon (its edge), along it, and the direction square to both.
        val zx = w.by * w.az - w.bz * w.ay; val zy = w.bz * w.ax - w.bx * w.az; val zz = w.bx * w.ay - w.by * w.ax
        Place.frame(base, w.bx, w.by, w.bz, w.ax, w.ay, w.az, zx, zy, zz, w.x, w.y, w.z)
        when (w.kind) {
            WeaponKind.SWORD -> {
                tube(-.15f, .16f, .034f, .37f, .23f, .15f)
                sphere(0f, -.19f, 0f, .055f, .055f, .055f, .86f, .69f, .27f)
                block(0f, .18f, 0f, .40f, .055f, .085f, .86f, .69f, .27f)
                block(0f, .72f, 0f, .10f, 1.03f, .022f, .82f, .85f, .90f)
            }
            WeaponKind.AXE -> {
                tube(-.20f, 1.0f, .032f, .52f, .35f, .20f)
                block(.17f, .80f, 0f, .34f, .32f, .035f, .78f, .81f, .86f)
                block(-.07f, .80f, 0f, .10f, .13f, .035f, .78f, .81f, .86f)
            }
            WeaponKind.MACE -> {
                tube(-.20f, .80f, .032f, .52f, .35f, .20f)
                sphere(0f, .88f, 0f, .15f, .15f, .15f, .40f, .40f, .45f)
                block(0f, .88f, 0f, .38f, .07f, .07f, .62f, .62f, .66f)
                block(0f, .88f, 0f, .07f, .38f, .07f, .62f, .62f, .66f)
                block(0f, .88f, 0f, .07f, .07f, .38f, .62f, .62f, .66f)
            }
        }
    }

    /**
     * A solid hand in skin colour. [j] holds the 21 joints as x, y, z triples in the world and
     * [scale] is how many times bigger than a real hand it is. The arm leaves towards
     * ([shoulderX], [shoulderY], [shoulderZ]).
     */
    fun hand(j: FloatArray, scale: Float, shoulderX: Float, shoulderY: Float, shoulderZ: Float) {
        val r = SKIN_R; val g = SKIN_G; val b = SKIN_B
        // Arm, a little darker towards the body so it reads as going away.
        limb(j[0], j[1], j[2], shoulderX, shoulderY, shoulderZ, .027f * scale, r * .96f, g * .96f, b * .96f)
        globe(j[0], j[1], j[2], .028f * scale, r, g, b)

        // Palm: an oval slab between wrist and knuckles, and the bones of the palm inside it.
        val kx = (j[15] + j[27] + j[39] + j[51]) * .25f; val ky = (j[16] + j[28] + j[40] + j[52]) * .25f
        val kz = (j[17] + j[29] + j[41] + j[53]) * .25f
        var ax = j[15] - j[51]; var ay = j[16] - j[52]; var az = j[17] - j[53]
        val across = max(1e-5f, sqrt(ax * ax + ay * ay + az * az)); ax /= across; ay /= across; az /= across
        var lx = kx - j[0]; var ly = ky - j[1]; var lz = kz - j[2]
        // Along the palm, made square to the line of the knuckles.
        val dot = lx * ax + ly * ay + lz * az
        lx -= dot * ax; ly -= dot * ay; lz -= dot * az
        val along = max(1e-5f, sqrt(lx * lx + ly * ly + lz * lz)); lx /= along; ly /= along; lz /= along
        val nx = ay * lz - az * ly; val ny = az * lx - ax * lz; val nz = ax * ly - ay * lx
        val halfAcross = across * .60f; val halfAlong = along * .58f; val thick = .0135f * scale
        // The cylinder stands across the palm: its two flat ends are the palm and the back of the hand.
        Place.frame(
            model, ax * halfAcross, ay * halfAcross, az * halfAcross, nx * thick * 2f, ny * thick * 2f, nz * thick * 2f,
            lx * halfAlong, ly * halfAlong, lz * halfAlong,
            (j[0] + kx) * .5f + lx * along * .06f - nx * thick, (j[1] + ky) * .5f + ly * along * .06f - ny * thick,
            (j[2] + kz) * .5f + lz * along * .06f - nz * thick,
        )
        draw(cylinder, model, r, g, b, 1f, 1f)
        for (i in 0 until PALM_BONES.size / 2) bone(j, PALM_BONES[i * 2], PALM_BONES[i * 2 + 1], .0122f * scale, r, g, b)

        // Fingers: three bones each, thinner towards the tip, with a ball on every joint.
        for (finger in 0..4) {
            val first = 1 + finger * 4
            val thickness = scale * when (finger) { 0 -> 1.16f; 4 -> .88f; else -> 1f }
            for (k in 0..2) {
                val radius = (.0100f - .0010f * k) * thickness
                bone(j, first + k, first + k + 1, radius, r, g, b)
                globe(j[(first + k) * 3], j[(first + k) * 3 + 1], j[(first + k) * 3 + 2], radius * 1.06f, r, g, b)
            }
            val tip = first + 3
            globe(j[tip * 3], j[tip * 3 + 1], j[tip * 3 + 2], .0082f * thickness, r, g, b)
        }
    }

    private fun bone(j: FloatArray, a: Int, b: Int, radius: Float, red: Float, green: Float, blue: Float) {
        limb(j[a * 3], j[a * 3 + 1], j[a * 3 + 2], j[b * 3], j[b * 3 + 1], j[b * 3 + 2], radius, red, green, blue)
    }

    private fun limb(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, radius: Float, red: Float, green: Float, blue: Float,
    ) {
        Place.between(model, ax, ay, az, bx, by, bz, radius)
        draw(cylinder, model, red, green, blue, 1f, 1f)
    }

    private fun globe(x: Float, y: Float, z: Float, radius: Float, red: Float, green: Float, blue: Float) {
        Mat4.identity(model); Mat4.translate(model, x, y, z); Mat4.scale(model, radius, radius, radius)
        draw(ball, model, red, green, blue, 1f, 1f)
    }

    // Parts of the thing whose frame is in [base].

    private fun tube(y0: Float, y1: Float, radius: Float, red: Float, green: Float, blue: Float) {
        Mat4.copy(part, base); Mat4.translate(part, 0f, y0, 0f); Mat4.scale(part, radius, y1 - y0, radius)
        draw(cylinder, part, red, green, blue, 1f, 1f)
    }

    private fun block(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, red: Float, green: Float, blue: Float) {
        Mat4.copy(part, base); Mat4.translate(part, x, y, z); Mat4.scale(part, sx, sy, sz)
        draw(box, part, red, green, blue, 1f, 1f)
    }

    private fun sphere(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, red: Float, green: Float, blue: Float) {
        Mat4.copy(part, base); Mat4.translate(part, x, y, z); Mat4.scale(part, sx, sy, sz)
        draw(ball, part, red, green, blue, 1f, 1f)
    }

    private fun draw(mesh: Mesh, modelMatrix: FloatArray, r: Float, g: Float, b: Float, a: Float, lit: Float) {
        glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0)
        glUniform4f(uTint, r, g, b, a); glUniform1f(uLit, lit)
        glBindBuffer(GL_ARRAY_BUFFER, mesh.buffer)
        val stride = mesh.floatsPerVertex * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 12)
        if (mesh.floatsPerVertex >= 9) {
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, false, stride, 24)
        } else {
            // Shapes have no colours of their own: white, coloured by the tint.
            glDisableVertexAttribArray(2); glVertexAttrib3f(2, 1f, 1f, 1f)
        }
        glDrawArrays(GL_TRIANGLES, 0, mesh.count)
    }

    private class Mesh(vertices: FloatArray, val floatsPerVertex: Int) {
        val buffer: Int
        val count = vertices.size / floatsPerVertex
        init {
            val ids = IntArray(1); glGenBuffers(1, ids, 0); buffer = ids[0]
            val data: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            data.put(vertices); data.position(0)
            glBindBuffer(GL_ARRAY_BUFFER, buffer)
            glBufferData(GL_ARRAY_BUFFER, vertices.size * 4, data, GL_STATIC_DRAW)
            glBindBuffer(GL_ARRAY_BUFFER, 0)
        }
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val s = glCreateShader(type); glShaderSource(s, source.trimIndent()); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(s) }
            return s
        }
        val v = shader(GL_VERTEX_SHADER, vertex); val f = shader(GL_FRAGMENT_SHADER, fragment)
        val p = glCreateProgram(); glAttachShader(p, v); glAttachShader(p, f)
        glBindAttribLocation(p, 0, "aPosition"); glBindAttribLocation(p, 1, "aNormal"); glBindAttribLocation(p, 2, "aColor")
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { glGetProgramInfoLog(p) }
        glDeleteShader(v); glDeleteShader(f)
        return p
    }

    private companion object {
        const val SKIN_R = .91f
        const val SKIN_G = .71f
        const val SKIN_B = .58f
        /** Bones inside the palm: from the wrist to the base of each finger, and along the knuckles. */
        val PALM_BONES = intArrayOf(0, 1, 0, 5, 0, 9, 0, 13, 0, 17, 5, 9, 9, 13, 13, 17)
    }
}

// Shared shapes, as position and normal (6 floats per vertex).

/** Ball of radius 1 around the origin. */
private fun unitBall(): FloatArray {
    val rows = 8; val columns = 12
    val out = FloatArray(rows * columns * 6 * 6)
    var n = 0
    fun put(row: Int, column: Int) {
        val t = PI.toFloat() * row / rows; val p = 2f * PI.toFloat() * column / columns
        val x = sin(t) * cos(p); val y = cos(t); val z = sin(t) * sin(p)
        out[n++] = x; out[n++] = y; out[n++] = z; out[n++] = x; out[n++] = y; out[n++] = z
    }
    for (row in 0 until rows) for (column in 0 until columns) {
        put(row, column); put(row + 1, column); put(row + 1, column + 1)
        put(row, column); put(row + 1, column + 1); put(row, column + 1)
    }
    return out
}

/** Cylinder of radius 1 standing on the origin, from y = 0 to y = 1, closed at both ends. */
private fun unitCylinder(sides: Int): FloatArray {
    val out = FloatArray(sides * 12 * 6)
    var n = 0
    fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        out[n++] = x; out[n++] = y; out[n++] = z; out[n++] = nx; out[n++] = ny; out[n++] = nz
    }
    for (i in 0 until sides) {
        val a = 2f * PI.toFloat() * i / sides; val c = 2f * PI.toFloat() * (i + 1) / sides
        val x0 = cos(a); val z0 = sin(a); val x1 = cos(c); val z1 = sin(c)
        // Smooth sides: each corner has the normal of its own direction.
        put(x0, 0f, z0, x0, 0f, z0); put(x1, 0f, z1, x1, 0f, z1); put(x1, 1f, z1, x1, 0f, z1)
        put(x0, 0f, z0, x0, 0f, z0); put(x1, 1f, z1, x1, 0f, z1); put(x0, 1f, z0, x0, 0f, z0)
        put(0f, 1f, 0f, 0f, 1f, 0f); put(x0, 1f, z0, 0f, 1f, 0f); put(x1, 1f, z1, 0f, 1f, 0f)
        put(0f, 0f, 0f, 0f, -1f, 0f); put(x1, 0f, z1, 0f, -1f, 0f); put(x0, 0f, z0, 0f, -1f, 0f)
    }
    return out
}

/** Cube with sides of length 1 around the origin. */
private fun unitBox(): FloatArray {
    val out = FloatArray(6 * 6 * 6)
    var n = 0
    // For each face: its normal and the two directions that span it.
    val faces = floatArrayOf(
        0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
        -1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
        0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f,
        0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f,
    )
    val corners = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
    for (f in 0 until 6) {
        val nx = faces[f * 9]; val ny = faces[f * 9 + 1]; val nz = faces[f * 9 + 2]
        val ux = faces[f * 9 + 3]; val uy = faces[f * 9 + 4]; val uz = faces[f * 9 + 5]
        val vx = faces[f * 9 + 6]; val vy = faces[f * 9 + 7]; val vz = faces[f * 9 + 8]
        for (c in 0 until 6) {
            val s = corners[c * 2] * .5f; val t = corners[c * 2 + 1] * .5f
            out[n++] = nx * .5f + ux * s + vx * t
            out[n++] = ny * .5f + uy * s + vy * t
            out[n++] = nz * .5f + uz * s + vz * t
            out[n++] = nx; out[n++] = ny; out[n++] = nz
        }
    }
    return out
}

/** Disc of radius 1 lying on the ground, facing up. */
private fun unitDisc(sides: Int): FloatArray {
    val out = FloatArray(sides * 3 * 6)
    var n = 0
    for (i in 0 until sides) {
        val a = 2f * PI.toFloat() * i / sides; val c = 2f * PI.toFloat() * (i + 1) / sides
        out[n++] = 0f; out[n++] = 0f; out[n++] = 0f; out[n++] = 0f; out[n++] = 1f; out[n++] = 0f
        out[n++] = cos(a); out[n++] = 0f; out[n++] = sin(a); out[n++] = 0f; out[n++] = 1f; out[n++] = 0f
        out[n++] = cos(c); out[n++] = 0f; out[n++] = sin(c); out[n++] = 0f; out[n++] = 1f; out[n++] = 0f
    }
    return out
}
