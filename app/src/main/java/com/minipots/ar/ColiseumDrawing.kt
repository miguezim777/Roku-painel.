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
        uniform vec4 uShade; uniform vec3 uLight;
        varying vec4 vColor;
        void main() {
            gl_Position = uVp * (uModel * vec4(aPosition, 1.0));
            vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);
            float sun = max(dot(n, vec3(0.45, 0.50, -0.74)), 0.0);
            vec3 light = vec3(0.58, 0.60, 0.66) * (0.875 + 0.125 * n.y) + vec3(0.70, 0.62, 0.48) * sun;
            vec3 base = aColor * uTint.rgb;
            vec3 color = mix(base, min(base * light, 1.0), uLit);
            // Skin: its own colour where the soft light falls, the shade colour where it does
            // not, and a touch of the colour of the light itself where it falls straight.
            float facing = clamp(0.5 + 0.5 * dot(n, uLight), 0.0, 1.0);
            vec3 skin = mix(uShade.rgb, base, smoothstep(0.3, 0.9, facing));
            skin = mix(skin, vec3(1.0, 0.894, 0.761), 0.2 * facing * facing);
            vColor = vec4(mix(color, skin, uShade.a), uTint.a);
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
    private val uShade = glGetUniformLocation(program, "uShade")
    private val uLight = glGetUniformLocation(program, "uLight")

    private val arenaMesh = Mesh(ColiseumScenery.arena(), ColiseumScenery.FLOATS_PER_VERTEX)
    private val skyMesh = Mesh(ColiseumScenery.sky(), ColiseumScenery.FLOATS_PER_VERTEX)
    private val ball = Mesh(unitBall(), 6)
    private val cylinder = Mesh(unitCylinder(16), 6)
    private val taper = Mesh(unitCylinder(16, TAPER), 6)
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
        glUniform4f(uShade, 0f, 0f, 0f, 0f); glUniform3f(uLight, 0f, 1f, 0f)
        glEnableVertexAttribArray(0); glEnableVertexAttribArray(1)
    }

    /**
     * Everything in [game], as the eye that [c] is set to sees it. For each hand, [confidence]
     * and [palmSign] are those of its [HandRig] and [mark] is the dot that tells how it is
     * followed ([MARK_NONE], [MARK_LOST], [MARK_HELD] or [MARK_LIVE]). [seconds] runs on and makes
     * the dot of a lost hand pulse.
     */
    fun scene(game: Coliseum, c: ArenaCamera, confidence: FloatArray, palmSign: FloatArray, mark: IntArray, seconds: Float) {
        begin(c.viewProjection)
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_BLEND)
        sky(c.eyeX, c.eyeY, c.eyeZ)
        glEnable(GL_DEPTH_TEST); glDepthMask(true)
        arena()

        // Shadows lie on the sand, which nothing in the arena hides: no depth test needed, and no
        // flicker from two surfaces at the same height.
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glEnable(GL_BLEND)
        for (dummy in game.dummies) if (dummy.standing > .02f) shadow(dummy.x, dummy.z, .46f * dummy.standing, .30f * dummy.standing)
        for (weapon in game.weapons) {
            if (game.world.floorAt(weapon.x, weapon.z) > 0f) continue
            if (weapon.held || weapon.flying) shadow(weapon.x, weapon.z, .20f, .16f) else shadow(weapon.x, weapon.z, .17f, .26f)
        }
        for (i in 0..1) if (game.handPresent[i]) {
            val x = game.palm[i * 3]; val y = game.palm[i * 3 + 1]; val z = game.palm[i * 3 + 2]
            if (game.world.floorAt(x, z) > 0f) continue
            // The shadow tells how high the hand is, which is what moving depends on.
            val above = max(0f, y)
            shadow(x, z, .17f + above * .10f, (.34f - above * .16f).coerceIn(.08f, .34f))
            if (game.body.touching[i] && y < .3f) spot(x, z, .30f, 1f, 1f, 1f, .28f)
            // A stroke that rows the body along shows on the sand under the hand, in green.
            if (game.body.stroking[i]) spot(x, z, .26f, LIVE_R, LIVE_G, LIVE_B, .34f)
        }
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthMask(true)

        for (dummy in game.dummies) dummy(dummy)
        for (weapon in game.weapons) weapon(weapon)
        for (i in 0..1) if (game.handPresent[i]) {
            val j = game.handJoints[i]
            arm(game.shoulder, game.elbow, i * 3, j[0], j[1], j[2], game.reach, confidence[i], c.eyeX, c.eyeY, c.eyeZ)
            hand(j, game.reach, palmSign[i], confidence[i], c.eyeX, c.eyeY, c.eyeZ)
        }

        // A hand that is not followed is never drawn as if it were: a dot low on its side of the
        // view says so instead, red. The dot is amber while the last pose is only being held, and
        // small and green while the hand is followed.
        var any = false
        for (i in 0..1) if (mark[i] != MARK_NONE) {
            if (!any) { glDisable(GL_DEPTH_TEST); glDepthMask(false); glEnable(GL_BLEND); any = true }
            val side = if (i == 0) -1f else 1f
            when (mark[i]) {
                MARK_LOST -> marker(c, side, 1f, LOST_R, LOST_G, LOST_B, .75f + .25f * sin(seconds * 4f))
                MARK_HELD -> marker(c, side, .8f, 1f, .70f, .22f, .8f)
                else -> marker(c, side, .5f, LIVE_R, LIVE_G, LIVE_B, .8f)
            }
        }
        if (any) { glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthMask(true) }
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
     * A hand: palm, five fingers of three bones each with a ball on every joint, nails, and a
     * rounded wrist for the forearm of [arm] to arrive at. [j] holds the 21 joints as x, y, z triples in the world,
     * [scale] is how many times bigger than a real hand it is, [palmSign] tells which side the palm
     * is on (see [HandReading.palmSign]) and [confidence] goes from 1 to 0 while a hand that is no
     * longer followed is still shown: it fades towards grey. The eye is at ([eyeX], [eyeY], [eyeZ]).
     */
    fun hand(j: FloatArray, scale: Float, palmSign: Float, confidence: Float, eyeX: Float, eyeY: Float, eyeZ: Float) {
        val sure = confidence.coerceIn(0f, 1f)
        skinLight(j[27], j[28], j[29], eyeX, eyeY, eyeZ, sure)
        val r = fade(SKIN_R, sure); val g = fade(SKIN_G, sure); val b = fade(SKIN_B, sure)

        // The frame of the palm: across it (little finger to index), along it (wrist to knuckles)
        // and out of the back of the hand.
        val kx = (j[15] + j[27] + j[39] + j[51]) * .25f; val ky = (j[16] + j[28] + j[40] + j[52]) * .25f
        val kz = (j[17] + j[29] + j[41] + j[53]) * .25f
        var ax = j[15] - j[51]; var ay = j[16] - j[52]; var az = j[17] - j[53]
        val across = max(1e-5f, sqrt(ax * ax + ay * ay + az * az)); ax /= across; ay /= across; az /= across
        var px = kx - j[0]; var py = ky - j[1]; var pz = kz - j[2]
        val dot = px * ax + py * ay + pz * az
        px -= dot * ax; py -= dot * ay; pz -= dot * az
        val along = max(1e-5f, sqrt(px * px + py * py + pz * pz)); px /= along; py /= along; pz /= along
        // Back of the hand: along x across, turned by which hand it is.
        val nx = (py * az - pz * ay) * palmSign; val ny = (pz * ax - px * az) * palmSign; val nz = (px * ay - py * ax) * palmSign

        // Wrist: a ball where the forearm arrives (see [arm]), and a flatter one that leads into
        // the palm. A wrist is wider than it is thick.
        val wide = .0255f * scale; val deep = .0175f * scale
        globe(j[0], j[1], j[2], .0225f * scale, r, g, b)
        Place.frame(
            model, ax * wide, ay * wide, az * wide, px * deep, py * deep, pz * deep,
            nx * deep, ny * deep, nz * deep, j[0] + px * .010f * scale, j[1] + py * .010f * scale, j[2] + pz * .010f * scale,
        )
        draw(ball, model, r, g, b, 1f, 1f)

        // Palm: an oval slab between wrist and knuckles, and the bones of the palm inside it.
        val halfAcross = across * .60f; val halfAlong = along * .58f; val thick = .0125f * scale
        // The cylinder stands across the palm: its two flat ends are the palm and the back of the hand.
        Place.frame(
            model, ax * halfAcross, ay * halfAcross, az * halfAcross, nx * thick * 2f, ny * thick * 2f, nz * thick * 2f,
            px * halfAlong, py * halfAlong, pz * halfAlong,
            (j[0] + kx) * .5f + px * along * .06f - nx * thick, (j[1] + ky) * .5f + py * along * .06f - ny * thick,
            (j[2] + kz) * .5f + pz * along * .06f - nz * thick,
        )
        draw(cylinder, model, r, g, b, 1f, 1f)
        for (i in 0 until PALM_BONES.size / 2) {
            // The first one carries the ball of the thumb, which is the thickest part of a hand.
            bone(j, PALM_BONES[i * 2], PALM_BONES[i * 2 + 1], (if (i == 0) .0150f else .0118f) * scale, r, g, b)
        }
        globe(j[3], j[4], j[5], .0153f * scale, r, g, b)

        // Fingers: three bones each, thinner towards the tip, with a ball on every joint.
        for (finger in 0..4) {
            val first = 1 + finger * 4
            val thickness = scale * when (finger) { 0 -> 1.14f; 4 -> .86f; else -> 1f }
            for (k in 0..2) {
                val radius = (.0095f - .0009f * k) * thickness
                bone(j, first + k, first + k + 1, radius, r, g, b)
                // A hair wider than the bone, so the flat end of the bone stays inside the ball.
                globe(j[(first + k) * 3], j[(first + k) * 3 + 1], j[(first + k) * 3 + 2], radius * 1.025f, r, g, b)
            }
            val tip = first + 3
            val tipRadius = .0077f * thickness
            globe(j[tip * 3], j[tip * 3 + 1], j[tip * 3 + 2], tipRadius, r, g, b)

            // Nail: a small flat oval on the back of the last bone.
            var dx = j[tip * 3] - j[(tip - 1) * 3]; var dy = j[tip * 3 + 1] - j[(tip - 1) * 3 + 1]; var dz = j[tip * 3 + 2] - j[(tip - 1) * 3 + 2]
            val length = max(1e-5f, sqrt(dx * dx + dy * dy + dz * dz)); dx /= length; dy /= length; dz /= length
            // The back of a finger: square to the finger and to the line of the knuckles. The
            // thumb is turned a quarter: its back looks away from the other fingers.
            var bx: Float; var by: Float; var bz: Float
            if (finger == 0) {
                bx = dy * nz - dz * ny; by = dz * nx - dx * nz; bz = dx * ny - dy * nx
                if (bx * ax + by * ay + bz * az < 0f) { bx = -bx; by = -by; bz = -bz }
            } else {
                bx = (dy * az - dz * ay) * palmSign; by = (dz * ax - dx * az) * palmSign; bz = (dx * ay - dy * ax) * palmSign
            }
            val bl = sqrt(bx * bx + by * by + bz * bz)
            if (bl < 1e-4f) continue
            bx /= bl; by /= bl; bz /= bl
            // Across the nail: square to both.
            val wx = dy * bz - dz * by; val wy = dz * bx - dx * bz; val wz = dx * by - dy * bx
            // A thin plate that lies on the finger from the middle of the last bone to the tip.
            val lift = tipRadius * .90f; val back = length * .36f - tipRadius * .25f
            Place.frame(
                model, wx * tipRadius * .70f, wy * tipRadius * .70f, wz * tipRadius * .70f,
                dx * length * .36f, dy * length * .36f, dz * length * .36f,
                bx * tipRadius * .26f, by * tipRadius * .26f, bz * tipRadius * .26f,
                j[tip * 3] - dx * back + bx * lift, j[tip * 3 + 1] - dy * back + by * lift, j[tip * 3 + 2] - dz * back + bz * lift,
            )
            draw(ball, model, fade(NAIL_R, sure), fade(NAIL_G, sure), fade(NAIL_B, sure), 1f, 1f)
        }
        glUniform4f(uShade, 0f, 0f, 0f, 0f)
    }

    /**
     * The arm of a hand: upper arm from the shoulder to the elbow and forearm from the elbow to the
     * wrist, each thinner towards its far end, with a ball on the shoulder and on the elbow. The
     * points are x, y, z triples starting at [offset] of [shoulders] and [elbows]; the wrist is
     * ([wx], [wy], [wz]). [scale] and [confidence] are those of the hand it ends in.
     */
    fun arm(
        shoulders: FloatArray, elbows: FloatArray, offset: Int, wx: Float, wy: Float, wz: Float,
        scale: Float, confidence: Float, eyeX: Float, eyeY: Float, eyeZ: Float,
    ) {
        val sure = confidence.coerceIn(0f, 1f)
        val sx = shoulders[offset]; val sy = shoulders[offset + 1]; val sz = shoulders[offset + 2]
        val ex = elbows[offset]; val ey = elbows[offset + 1]; val ez = elbows[offset + 2]
        skinLight(ex, ey, ez, eyeX, eyeY, eyeZ, sure)
        val r = fade(SKIN_R, sure); val g = fade(SKIN_G, sure); val b = fade(SKIN_B, sure)
        // The forearm arrives at the wrist as thick as the wrist of the hand is.
        val wrist = .0215f * scale; val elbow = wrist / TAPER; val shoulder = elbow / TAPER
        Place.between(model, sx, sy, sz, ex, ey, ez, shoulder); draw(taper, model, r, g, b, 1f, 1f)
        Place.between(model, ex, ey, ez, wx, wy, wz, elbow); draw(taper, model, r, g, b, 1f, 1f)
        globe(sx, sy, sz, shoulder * 1.02f, r, g, b)
        globe(ex, ey, ez, elbow * 1.02f, r, g, b)
        glUniform4f(uShade, 0f, 0f, 0f, 0f)
    }

    /**
     * Sets the light for skin around ([x], [y], [z]): soft, from a little above the eyes, so skin
     * is never a dark shape against the sun. [sure] fades the shade colour to grey with the rest.
     */
    private fun skinLight(x: Float, y: Float, z: Float, eyeX: Float, eyeY: Float, eyeZ: Float, sure: Float) {
        var lx = eyeX - x; var ly = eyeY - y + .6f; var lz = eyeZ - z
        val ll = max(1e-5f, sqrt(lx * lx + ly * ly + lz * lz)); lx /= ll; ly /= ll; lz /= ll
        lx = lx * .8f + .45f * .5f; ly = ly * .8f + .50f * .5f; lz = lz * .8f - .74f * .5f
        val l2 = max(1e-5f, sqrt(lx * lx + ly * ly + lz * lz))
        glUniform3f(uLight, lx / l2, ly / l2, lz / l2)
        glUniform4f(uShade, fade(SHADE_R, sure), fade(SHADE_G, sure), fade(SHADE_B, sure), 1f)
    }

    /** A colour on its way to grey as the hand is less sure. */
    private fun fade(value: Float, sure: Float) = .52f + (value - .52f) * (.35f + .65f * sure)

    /**
     * A dot low in the view, on the side of a hand, that tells how that hand is followed. It
     * floats 1.4 m in front of the head, so both eyes see it at the same depth. Needs blending on
     * and depth off. [side] is -1 for the left hand and 1 for the right one, [size] 1 for the
     * full dot.
     */
    private fun marker(camera: ArenaCamera, side: Float, size: Float, r: Float, g: Float, b: Float, strength: Float) {
        val distance = 1.4f
        val x = side * .40f; val y = -.34f
        val cx = camera.headX + camera.worldX(x, y, -distance); val cy = camera.headY + camera.worldY(x, y, -distance)
        val cz = camera.headZ + camera.worldZ(x, y, -distance)
        for (ring in 0..1) {
            val radius = (if (ring == 0) .050f else .032f) * size
            // The disc lies in its x-z plane: x to the right, z up, and y towards the eyes.
            Place.frame(
                model, camera.worldX(radius, 0f, 0f), camera.worldY(radius, 0f, 0f), camera.worldZ(radius, 0f, 0f),
                camera.worldX(0f, 0f, 1f), camera.worldY(0f, 0f, 1f), camera.worldZ(0f, 0f, 1f),
                camera.worldX(0f, radius, 0f), camera.worldY(0f, radius, 0f), camera.worldZ(0f, radius, 0f),
                cx, cy, cz,
            )
            if (ring == 0) draw(disc, model, .14f, .14f, .14f, .45f * strength, 0f)
            else draw(disc, model, r, g, b, .85f * strength, 0f)
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

    companion object {
        /** Values of the dot of a hand in [scene]. */
        const val MARK_NONE = -1
        const val MARK_LOST = 0
        const val MARK_HELD = 1
        const val MARK_LIVE = 2
        // Tracking that is valid, #35D07F, and tracking that is lost, #F05B54.
        private const val LIVE_R = 0x35 / 255f
        private const val LIVE_G = 0xD0 / 255f
        private const val LIVE_B = 0x7F / 255f
        private const val LOST_R = 0xF0 / 255f
        private const val LOST_G = 0x5B / 255f
        private const val LOST_B = 0x54 / 255f
        // The colours of the hand: skin #C99678, its shade #98694F, nails #E3B7A0. The soft light
        // on it, #FFE4C2, is in the shader.
        private const val SKIN_R = 0xC9 / 255f
        private const val SKIN_G = 0x96 / 255f
        private const val SKIN_B = 0x78 / 255f
        private const val SHADE_R = 0x98 / 255f
        private const val SHADE_G = 0x69 / 255f
        private const val SHADE_B = 0x4F / 255f
        private const val NAIL_R = 0xE3 / 255f
        private const val NAIL_G = 0xB7 / 255f
        private const val NAIL_B = 0xA0 / 255f
        /** How much thinner the far end of each bone of the arm is than its near end. */
        private const val TAPER = .82f
        /** Bones inside the palm: from the wrist to the base of each finger, and along the knuckles. */
        private val PALM_BONES = intArrayOf(0, 1, 0, 5, 0, 9, 0, 13, 0, 17, 5, 9, 9, 13, 13, 17)
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
private fun unitCylinder(sides: Int, top: Float = 1f): FloatArray {
    val out = FloatArray(sides * 12 * 6)
    var n = 0
    fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        out[n++] = x; out[n++] = y; out[n++] = z; out[n++] = nx; out[n++] = ny; out[n++] = nz
    }
    for (i in 0 until sides) {
        val a = 2f * PI.toFloat() * i / sides; val c = 2f * PI.toFloat() * (i + 1) / sides
        val x0 = cos(a); val z0 = sin(a); val x1 = cos(c); val z1 = sin(c)
        // Smooth sides: each corner has the normal of its own direction. [top] is the radius at
        // the far end; the slope it gives is too slight to matter to the light.
        put(x0, 0f, z0, x0, 0f, z0); put(x1, 0f, z1, x1, 0f, z1); put(x1 * top, 1f, z1 * top, x1, 0f, z1)
        put(x0, 0f, z0, x0, 0f, z0); put(x1 * top, 1f, z1 * top, x1, 0f, z1); put(x0 * top, 1f, z0 * top, x0, 0f, z0)
        put(0f, 1f, 0f, 0f, 1f, 0f); put(x0 * top, 1f, z0 * top, 0f, 1f, 0f); put(x1 * top, 1f, z1 * top, 0f, 1f, 0f)
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
