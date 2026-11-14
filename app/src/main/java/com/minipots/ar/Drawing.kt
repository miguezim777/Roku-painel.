package com.minipots.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun newBuffer(): Int { val ids = IntArray(1); glGenBuffers(1, ids, 0); return ids[0] }

private fun floatBuffer(count: Int): FloatBuffer =
    ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

private fun floats(a: FloatArray): FloatBuffer = floatBuffer(a.size).apply { put(a); position(0) }

/** Bones of the 21-point MediaPipe hand, as pairs of landmark indices. */
private val HAND_BONES = intArrayOf(
    0, 1, 1, 2, 2, 3, 3, 4,
    0, 5, 5, 6, 6, 7, 7, 8,
    5, 9, 9, 10, 10, 11, 11, 12,
    9, 13, 13, 14, 14, 15, 15, 16,
    13, 17, 17, 18, 18, 19, 19, 20,
    0, 17,
)

/**
 * All OpenGL drawing. Written for OpenGL ES 2.0 so it also runs on old, weak GPUs: one texture
 * fetch per pixel for the camera, lighting computed per vertex, static geometry in GPU buffers,
 * uniform locations cached and no allocation while drawing.
 */
class Drawing {
    private val cameraProgram = program(
        """
        attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord;
        void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vTexCoord = aTexCoord; }
        """,
        """
        #extension GL_OES_EGL_image_external : require
        precision mediump float; uniform samplerExternalOES uTexture; varying vec2 vTexCoord;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
        """,
        "aPosition", "aTexCoord",
    )
    private val meshProgram = program(
        """
        attribute vec3 aPosition; attribute vec3 aNormal;
        uniform mat4 uMvp; uniform mat4 uModel; uniform vec4 uColor; uniform float uLit;
        varying vec4 vColor;
        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
            vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            float diffuse = 0.60 + 0.34 * max(dot(n, vec3(0.3621, 0.8148, 0.4527)), 0.0);
            float edge = 1.0 - abs(n.y);
            vec3 lit = min(uColor.rgb * (diffuse + edge * edge * edge * 0.12) + 0.05, 1.0);
            vColor = vec4(mix(uColor.rgb, lit, uLit), uColor.a);
        }
        """,
        """
        precision mediump float; varying vec4 vColor;
        void main() { gl_FragColor = vColor; }
        """,
        "aPosition", "aNormal",
    )
    private val uCameraTexture = glGetUniformLocation(cameraProgram, "uTexture")
    private val uMvp = glGetUniformLocation(meshProgram, "uMvp")
    private val uModel = glGetUniformLocation(meshProgram, "uModel")
    private val uColor = glGetUniformLocation(meshProgram, "uColor")
    private val uLit = glGetUniformLocation(meshProgram, "uLit")

    private val cameraBuffer = newBuffer()
    private val cameraVertices = floatBuffer(16)
    private val cameraArray = FloatArray(16)

    private val cup = Mesh(cupVertices())
    private val lid = Mesh(cylinder(.079f, .012f, 20))
    private val sphere = Mesh(sphereVertices())
    private val box = Mesh(boxVertices())
    private val coin = Mesh(cylinder(.025f, .009f, 16))
    private val star = Mesh(starVertices())
    private val disk = Mesh(ringVertices(0f, 1f, 20))
    private val ring = Mesh(ringVertices(.8f, 1f, 28))
    private val gridBuffer = newBuffer()
    private val gridCount: Int

    private val handBuffer = newBuffer()
    private val handArray = FloatArray((HAND_BONES.size / 2 + HAND_POINTS) * 6 * 3)
    private val handVertices = floatBuffer(handArray.size)
    private val boneVertexCount = HAND_BONES.size / 2 * 6

    private val identity = FloatArray(16).also { Mat4.identity(it) }
    private val mvp = FloatArray(16)
    private val model = FloatArray(16)
    private val part = FloatArray(16)

    init {
        glBindBuffer(GL_ARRAY_BUFFER, cameraBuffer)
        glBufferData(GL_ARRAY_BUFFER, 16 * 4, null, GL_DYNAMIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, handBuffer)
        glBufferData(GL_ARRAY_BUFFER, handArray.size * 4, null, GL_DYNAMIC_DRAW)
        val grid = gridVertices()
        gridCount = grid.size / 3
        glBindBuffer(GL_ARRAY_BUFFER, gridBuffer)
        glBufferData(GL_ARRAY_BUFFER, grid.size * 4, floats(grid), GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    /**
     * Draws the camera image over the whole surface. [texCoords] holds the four texture
     * coordinates for bottom-left, bottom-right, top-left and top-right.
     */
    fun background(texture: Int, texCoords: FloatArray) {
        val v = cameraArray
        for (i in 0..3) {
            v[i * 4] = if (i % 2 == 0) -1f else 1f
            v[i * 4 + 1] = if (i < 2) -1f else 1f
            v[i * 4 + 2] = texCoords[i * 2]
            v[i * 4 + 3] = texCoords[i * 2 + 1]
        }
        cameraVertices.clear(); cameraVertices.put(v); cameraVertices.position(0)
        glUseProgram(cameraProgram)
        glBindBuffer(GL_ARRAY_BUFFER, cameraBuffer)
        glBufferSubData(GL_ARRAY_BUFFER, 0, 16 * 4, cameraVertices)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        glUniform1i(uCameraTexture, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    /** Selects the program used by every other draw call of the frame. */
    fun beginScene() { glUseProgram(meshProgram); glEnableVertexAttribArray(0) }

    fun grid(vp: FloatArray) {
        glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        glUniformMatrix4fv(uModel, 1, false, identity, 0)
        glUniform4f(uColor, .62f, .84f, 1f, .42f); glUniform1f(uLit, 0f)
        glBindBuffer(GL_ARRAY_BUFFER, gridBuffer)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glDisableVertexAttribArray(1); glVertexAttrib3f(1, 0f, 1f, 0f)
        glDrawArrays(GL_LINES, 0, gridCount)
    }

    /** Flat disc on the table: shadows and, with [hollow], cursor and selection rings. */
    fun mark(vp: FloatArray, x: Float, z: Float, radius: Float, hollow: Boolean, r: Float, g: Float, b: Float, a: Float) {
        Mat4.identity(model); Mat4.translate(model, x, .002f, z); Mat4.scale(model, radius, 1f, radius)
        draw(if (hollow) ring else disk, model, vp, r, g, b, a, 0f)
    }

    fun pot(base: FloatArray, vp: FloatArray, color: FloatArray, lidOpen: Float) {
        draw(cup, base, vp, color[0], color[1], color[2], .87f, 1f)
        Mat4.copy(part, base)
        Mat4.translate(part, 0f, .101f, -.079f); Mat4.rotateX(part, -110f * lidOpen); Mat4.translate(part, 0f, 0f, .079f)
        draw(lid, part, vp, color[0], color[1], color[2], 1f, 1f)
        Mat4.translate(part, 0f, .015f, 0f); Mat4.scale(part, .42f, .42f, .42f)
        draw(sphere, part, vp, 1f, .95f, .86f, 1f, 1f)
    }

    fun item(kind: ItemKind, modelMatrix: FloatArray, vp: FloatArray, color: FloatArray) {
        when (kind) {
            ItemKind.BALL -> draw(sphere, modelMatrix, vp, color[0], color[1], color[2], 1f, 1f)
            ItemKind.CUBE -> draw(box, modelMatrix, vp, color[0], color[1], color[2], 1f, 1f)
            ItemKind.COIN -> draw(coin, modelMatrix, vp, 1f, .82f, .40f, 1f, 1f)
            ItemKind.STAR -> draw(star, modelMatrix, vp, 1f, .82f, .40f, 1f, 1f)
            ItemKind.STONE -> {
                Mat4.copy(part, modelMatrix); Mat4.scale(part, 1.1f, .7f, .8f)
                draw(sphere, part, vp, color[0], color[1], color[2], 1f, 1f)
            }
        }
    }

    /**
     * Hand skeleton in screen space. [points] are normalized view coordinates (y down).
     * Bones and joints are small quads built here, so their thickness does not depend on the
     * line width a GPU happens to support.
     */
    fun hand(points: FloatArray, aspect: Float, pinching: Boolean) {
        val v = handArray
        var n = 0
        val halfBone = 0.0045f; val joint = 0.008f; val tip = 0.014f
        for (i in 0 until HAND_BONES.size / 2) {
            val a = HAND_BONES[i * 2]; val b = HAND_BONES[i * 2 + 1]
            val ax = points[a * 2] * 2f - 1f; val ay = 1f - points[a * 2 + 1] * 2f
            val bx = points[b * 2] * 2f - 1f; val by = 1f - points[b * 2 + 1] * 2f
            // Perpendicular of the bone, measured in square pixels and converted back to NDC.
            var px = -(by - ay); var py = (bx - ax) * aspect
            val length = sqrt(px * px + py * py)
            if (length > 1e-6f) { px = px / length * halfBone / aspect; py = py / length * halfBone } else { px = 0f; py = 0f }
            n = put(v, n, ax - px, ay - py); n = put(v, n, ax + px, ay + py); n = put(v, n, bx + px, by + py)
            n = put(v, n, ax - px, ay - py); n = put(v, n, bx + px, by + py); n = put(v, n, bx - px, by - py)
        }
        for (i in 0 until HAND_POINTS) {
            val x = points[i * 2] * 2f - 1f; val y = 1f - points[i * 2 + 1] * 2f
            val size = if (i == 4 || i == 8) tip else joint
            val dx = size / aspect
            n = put(v, n, x - dx, y); n = put(v, n, x, y - size); n = put(v, n, x + dx, y)
            n = put(v, n, x - dx, y); n = put(v, n, x + dx, y); n = put(v, n, x, y + size)
        }
        handVertices.clear(); handVertices.put(v); handVertices.position(0)
        glUniformMatrix4fv(uMvp, 1, false, identity, 0)
        glUniformMatrix4fv(uModel, 1, false, identity, 0)
        glUniform1f(uLit, 0f)
        glBindBuffer(GL_ARRAY_BUFFER, handBuffer)
        glBufferSubData(GL_ARRAY_BUFFER, 0, v.size * 4, handVertices)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glDisableVertexAttribArray(1); glVertexAttrib3f(1, 0f, 1f, 0f)
        glUniform4f(uColor, 1f, 1f, 1f, .78f)
        glDrawArrays(GL_TRIANGLES, 0, boneVertexCount)
        // Every joint, then the two pinch finger tips again on top in their own colour.
        glUniform4f(uColor, .22f, .78f, 1f, .95f)
        glDrawArrays(GL_TRIANGLES, boneVertexCount, HAND_POINTS * 6)
        if (pinching) glUniform4f(uColor, 1f, .82f, .2f, 1f) else glUniform4f(uColor, 1f, 1f, 1f, 1f)
        glDrawArrays(GL_TRIANGLES, boneVertexCount + 4 * 6, 6)
        glDrawArrays(GL_TRIANGLES, boneVertexCount + 8 * 6, 6)
    }

    private fun put(v: FloatArray, n: Int, x: Float, y: Float): Int { v[n] = x; v[n + 1] = y; v[n + 2] = 0f; return n + 3 }

    private fun draw(mesh: Mesh, modelMatrix: FloatArray, vp: FloatArray, r: Float, g: Float, b: Float, a: Float, lit: Float) {
        Mat4.mul(mvp, vp, modelMatrix)
        glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        glUniformMatrix4fv(uModel, 1, false, modelMatrix, 0)
        glUniform4f(uColor, r, g, b, a); glUniform1f(uLit, lit)
        glBindBuffer(GL_ARRAY_BUFFER, mesh.buffer)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        glDrawArrays(GL_TRIANGLES, 0, mesh.count)
    }

    private class Mesh(vertices: FloatArray) {
        val buffer = newBuffer(); val count = vertices.size / 6
        init {
            glBindBuffer(GL_ARRAY_BUFFER, buffer)
            glBufferData(GL_ARRAY_BUFFER, vertices.size * 4, floats(vertices), GL_STATIC_DRAW)
            glBindBuffer(GL_ARRAY_BUFFER, 0)
        }
    }

    private fun program(vertex: String, fragment: String, attribute0: String, attribute1: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type); glShaderSource(s, src.trimIndent()); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(s) }
            return s
        }
        val v = shader(GL_VERTEX_SHADER, vertex); val f = shader(GL_FRAGMENT_SHADER, fragment)
        val p = glCreateProgram(); glAttachShader(p, v); glAttachShader(p, f)
        glBindAttribLocation(p, 0, attribute0); glBindAttribLocation(p, 1, attribute1)
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { glGetProgramInfoLog(p) }
        glDeleteShader(v); glDeleteShader(f)
        return p
    }
}

/** Lines of the virtual table: a 10 cm grid plus its border. Position only (x, y, z). */
private fun gridVertices(): FloatArray {
    val out = ArrayList<Float>()
    fun line(x0: Float, z0: Float, x1: Float, z1: Float) {
        out.add(x0); out.add(.001f); out.add(z0); out.add(x1); out.add(.001f); out.add(z1)
    }
    val columns = 4; val rows = 7
    for (i in 0..columns) {
        val x = Table.GRID_MIN_X + (Table.GRID_MAX_X - Table.GRID_MIN_X) * i / columns
        line(x, Table.GRID_MIN_Z, x, Table.GRID_MAX_Z)
    }
    for (i in 0..rows) {
        val z = Table.GRID_MIN_Z + (Table.GRID_MAX_Z - Table.GRID_MIN_Z) * i / rows
        line(Table.GRID_MIN_X, z, Table.GRID_MAX_X, z)
    }
    return out.toFloatArray()
}

private class Builder {
    val a = ArrayList<Float>()
    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) { a.add(x); a.add(y); a.add(z); a.add(nx); a.add(ny); a.add(nz) }
    fun tri(p: FloatArray, q: FloatArray, r: FloatArray, n: FloatArray) { for (v in arrayOf(p, q, r)) v(v[0], v[1], v[2], n[0], n[1], n[2]) }
    fun result() = a.toFloatArray()
}

/** Flat ring in the XZ plane facing up; an inner radius of 0 gives a disc. */
private fun ringVertices(inner: Float, outer: Float, segments: Int): FloatArray {
    val b = Builder(); val up = floatArrayOf(0f, 1f, 0f)
    for (i in 0 until segments) {
        val a = 2f * PI.toFloat() * i / segments; val c = 2f * PI.toFloat() * (i + 1) / segments
        fun p(r: Float, t: Float) = floatArrayOf(cos(t) * r, 0f, sin(t) * r)
        b.tri(p(inner, a), p(outer, a), p(outer, c), up)
        if (inner > 0f) b.tri(p(inner, a), p(outer, c), p(inner, c), up)
    }
    return b.result()
}

private fun cylinder(radius: Float, height: Float, segments: Int): FloatArray {
    val b = Builder()
    for (i in 0 until segments) {
        val a = 2f * PI.toFloat() * i / segments; val c = 2f * PI.toFloat() * (i + 1) / segments
        val x = cos(a) * radius; val z = sin(a) * radius; val xx = cos(c) * radius; val zz = sin(c) * radius
        val n = floatArrayOf(cos((a + c) / 2), 0f, sin((a + c) / 2))
        b.tri(floatArrayOf(x, 0f, z), floatArrayOf(xx, 0f, zz), floatArrayOf(xx, height, zz), n)
        b.tri(floatArrayOf(x, 0f, z), floatArrayOf(xx, height, zz), floatArrayOf(x, height, z), n)
        b.tri(floatArrayOf(0f, height, 0f), floatArrayOf(x, height, z), floatArrayOf(xx, height, zz), floatArrayOf(0f, 1f, 0f))
        b.tri(floatArrayOf(0f, 0f, 0f), floatArrayOf(xx, 0f, zz), floatArrayOf(x, 0f, z), floatArrayOf(0f, -1f, 0f))
    }
    return b.result()
}

private fun cupVertices(): FloatArray {
    val b = Builder(); val count = 24; val outer = .075f; val inner = .064f; val h = .10f
    for (i in 0 until count) {
        val a = 2f * PI.toFloat() * i / count; val c = 2f * PI.toFloat() * (i + 1) / count
        fun p(r: Float, y: Float, t: Float) = floatArrayOf(cos(t) * r, y, sin(t) * r)
        val n = floatArrayOf(cos((a + c) / 2), 0f, sin((a + c) / 2)); val ni = floatArrayOf(-n[0], 0f, -n[2]); val up = floatArrayOf(0f, 1f, 0f)
        b.tri(p(outer, 0f, a), p(outer, 0f, c), p(outer, h, c), n); b.tri(p(outer, 0f, a), p(outer, h, c), p(outer, h, a), n)
        b.tri(p(inner, .012f, a), p(inner, h, c), p(inner, .012f, c), ni); b.tri(p(inner, .012f, a), p(inner, h, a), p(inner, h, c), ni)
        b.tri(p(inner, h, a), p(outer, h, a), p(outer, h, c), up); b.tri(p(inner, h, a), p(outer, h, c), p(inner, h, c), up)
        b.tri(floatArrayOf(0f, .012f, 0f), p(inner, .012f, a), p(inner, .012f, c), up)
    }
    return b.result()
}

private fun sphereVertices(): FloatArray {
    val b = Builder(); val lat = 8; val lon = 12; val radius = .025f
    fun point(i: Int, j: Int): FloatArray {
        val t = PI.toFloat() * i / lat; val p = 2f * PI.toFloat() * j / lon
        return floatArrayOf(sin(t) * cos(p), cos(t), sin(t) * sin(p))
    }
    fun v(n: FloatArray) = b.v(n[0] * radius, n[1] * radius, n[2] * radius, n[0], n[1], n[2])
    for (i in 0 until lat) for (j in 0 until lon) {
        val a = point(i, j); val c = point(i + 1, j); val d = point(i + 1, j + 1); val e = point(i, j + 1)
        v(a); v(c); v(d); v(a); v(d); v(e)
    }
    return b.result()
}

private fun boxVertices(): FloatArray {
    val b = Builder(); val s = .022f
    fun face(a: FloatArray, c: FloatArray, d: FloatArray, e: FloatArray, n: FloatArray) { b.tri(a, c, d, n); b.tri(a, d, e, n) }
    face(floatArrayOf(-s, s, -s), floatArrayOf(s, s, -s), floatArrayOf(s, s, s), floatArrayOf(-s, s, s), floatArrayOf(0f, 1f, 0f))
    face(floatArrayOf(-s, -s, s), floatArrayOf(s, -s, s), floatArrayOf(s, -s, -s), floatArrayOf(-s, -s, -s), floatArrayOf(0f, -1f, 0f))
    face(floatArrayOf(-s, -s, s), floatArrayOf(-s, s, s), floatArrayOf(s, s, s), floatArrayOf(s, -s, s), floatArrayOf(0f, 0f, 1f))
    face(floatArrayOf(s, -s, -s), floatArrayOf(s, s, -s), floatArrayOf(-s, s, -s), floatArrayOf(-s, -s, -s), floatArrayOf(0f, 0f, -1f))
    face(floatArrayOf(s, -s, s), floatArrayOf(s, s, s), floatArrayOf(s, s, -s), floatArrayOf(s, -s, -s), floatArrayOf(1f, 0f, 0f))
    face(floatArrayOf(-s, -s, -s), floatArrayOf(-s, s, -s), floatArrayOf(-s, s, s), floatArrayOf(-s, -s, s), floatArrayOf(-1f, 0f, 0f))
    return b.result()
}

private fun starVertices(): FloatArray {
    val b = Builder(); val n = floatArrayOf(0f, 1f, 0f)
    for (i in 0 until 10) {
        fun p(j: Int, y: Float): FloatArray {
            val a = 2f * PI.toFloat() * j / 10; val r = if (j % 2 == 0) .03f else .013f
            return floatArrayOf(cos(a) * r, y, sin(a) * r)
        }
        val a = p(i, .006f); val c = p((i + 1) % 10, .006f); val d = p(i, -.006f); val e = p((i + 1) % 10, -.006f)
        b.tri(floatArrayOf(0f, .006f, 0f), a, c, n); b.tri(floatArrayOf(0f, -.006f, 0f), e, d, floatArrayOf(0f, -1f, 0f))
        val side = floatArrayOf((a[0] + c[0]) * 15f, 0f, (a[2] + c[2]) * 15f); b.tri(a, d, e, side); b.tri(a, e, c, side)
    }
    return b.result()
}
