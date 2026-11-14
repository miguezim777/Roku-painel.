package com.minipots.ar

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.Process
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the motion sensors say about where the phone points. Written by the sensor thread. */
class SensorLook {
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    /** Tilt of the head to the side; positive is the top of the head to the right. */
    @Volatile var roll = 0f
    /** False until the sensor in use has given its first reading. */
    @Volatile var ready = false
}

/**
 * Renders the coliseum and runs the game on the GL thread.
 *
 * The camera is only used to follow the hands; the picture on screen is entirely the arena, drawn
 * twice, side by side, once for each eye of phone goggles (see [StereoRig]). Both hands are drawn
 * where the real ones are in front of the phone, and everything the player does goes through
 * them: touching the ground or a wall to move (as in Gorilla Tag), closing one on a weapon to take
 * it, swinging it to strike.
 *
 * Drawing never waits for tracking: each frame takes the newest reading there is, if there is a
 * new one, and otherwise carries the hands on by themselves (see [HandRig]).
 */
class ColiseumRenderer(
    private val tracker: HandTracker,
    private val sensors: SensorLook,
    private val callbacks: Callbacks,
    chooseProcessor: Boolean,
) : GLSurfaceView.Renderer {
    /** Called on the GL thread. */
    interface Callbacks {
        fun status(text: String)
        fun stats(text: String)
        /** The measurements say where tracking should run; see [ProcessorChooser]. */
        fun processor(action: ProcessorChooser.Action, cpuMs: Float, gpuMs: Float, cpuFps: Float, gpuFps: Float)
        /** A blow landed on a dummy; [strength] is 0..1. */
        fun blow(strength: Float)
    }

    /** How the player looks around. Set from the UI thread. */
    @Volatile var lookMode = LookMode.TOUCH
    /** The two pictures. Changed on the GL thread only. */
    val stereo = StereoRig()

    private val game = Coliseum()
    private val look = LookControl()
    private var drawing: ColiseumDrawing? = null
    private var width = 2
    private var height = 1

    private val frame = HandsFrame()
    private val rigs = arrayOf(HandRig(), HandRig())
    private val inputs = arrayOf(HandInput(), HandInput())
    /** Seconds since each hand was last followed; large when it never was. */
    private val lostFor = floatArrayOf(NEVER_SEEN, NEVER_SEEN)

    private var lastFrameNs = 0L
    private var seconds = 0f
    private var statusKey = -1L
    private var noticeUntilNs = 0L
    @Volatile private var trackerError: String? = null
    private val pendingNotice = AtomicReference<String?>(null)
    private var chooser: ProcessorChooser? = if (chooseProcessor) ProcessorChooser() else null
    // What is measured, each on its own: frames drawn, time spent drawing one, results of the tracker.
    private var statsStartNs = 0L
    private var statsFrames = 0
    private var statsDrawNs = 0L
    private var statsResults = 0

    fun setTrackerError(message: String) { trackerError = message }

    /** Shows a passing message. Can be called from any thread. */
    fun showNotice(message: String) { pendingNotice.set(message) }

    // ---------------------------------------------------------------- commands (GL thread)

    /** The finger moved by these fractions of the screen. */
    fun drag(dx: Float, dy: Float) { look.drag(dx, dy) }

    /** Back to the gate, weapons back in the sand, and the phone's direction is "forward" again. */
    fun restart() { game.restart(); look.recentre(); statusKey = -1L }

    /** The direction the head points to now becomes "forward", towards the middle of the arena. */
    fun recentre() { look.recentre() }

    /** The user chose the processor by hand: the automatic comparison stops. */
    fun stopChoosingProcessor() { chooser = null }

    /** Goggles on or off, the distance between the eyes and from lens to screen, the width of the screen. */
    fun setStereo(on: Boolean, ipd: Float, lensDistance: Float, screenWidth: Float) {
        stereo.stereo = on; stereo.ipd = ipd; stereo.lensDistance = lensDistance; stereo.screenWidth = screenWidth
        statusKey = -1L
    }

    // ---------------------------------------------------------------- GL callbacks

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Above the tracking threads, so inference can never take the frame rate down with it.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        // Black between and around the two pictures.
        glClearColor(0f, 0f, 0f, 1f)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDisable(GL_CULL_FACE); glDisable(GL_DITHER)
        drawing = try { ColiseumDrawing() } catch (e: RuntimeException) {
            callbacks.status("OpenGL indisponível neste aparelho: ${e.message ?: e.javaClass.simpleName}"); null
        }
        lastFrameNs = 0L; statusKey = -1L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(2, w); height = max(1, h)
        stereo.layout(width, height)
        game.camera.setAspect(width.toFloat() / height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) .016f else min((now - lastFrameNs) / 1e9f, .05f)
        lastFrameNs = now
        seconds += dt
        glDisable(GL_SCISSOR_TEST)
        glViewport(0, 0, width, height)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val d = drawing ?: return

        // Without a gyroscope or a compass nothing tells which way the head is turned, and inside
        // goggles no finger reaches the screen: leaning the head to a side turns the view.
        var roll = if (lookMode != LookMode.TOUCH && sensors.ready) sensors.roll else 0f
        if (lookMode == LookMode.TILT && sensors.ready && stereo.stereo && abs(roll) > LEAN_FROM) {
            look.turnBy((roll - (if (roll > 0f) LEAN_FROM else -LEAN_FROM)) * LEAN_TURN * dt)
        }
        roll = roll.coerceIn(-MAX_ROLL, MAX_ROLL)
        look.update(lookMode, sensors.yaw, sensors.pitch, sensors.ready)
        updateHands(dt, now)
        game.update(dt, look.yaw, look.pitch, roll, inputs)
        if (game.blowStrength > 0f) callbacks.blow(game.blowStrength)

        val c = game.camera
        stereo.layout(width, height)
        for (eye in 0 until stereo.eyes) {
            val x = stereo.viewportX(eye); val w = stereo.viewportWidth(eye)
            glViewport(x, 0, w, stereo.viewportHeight)
            glEnable(GL_SCISSOR_TEST); glScissor(x, 0, w, stereo.viewportHeight)
            c.eye(stereo.eyeOffset(eye), stereo.left(eye), stereo.right(eye), stereo.top)
            drawScene(d, c)
        }
        glDisable(GL_SCISSOR_TEST)

        updateStatus(now)
        statsDrawNs += System.nanoTime() - now
        updateStats(now)
    }

    /** Everything, as the eye that [c] is set to sees it. */
    private fun drawScene(d: ColiseumDrawing, c: ArenaCamera) {
        d.begin(c.viewProjection)
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_BLEND)
        d.sky(c.eyeX, c.eyeY, c.eyeZ)
        glEnable(GL_DEPTH_TEST); glDepthMask(true)
        d.arena()

        // Shadows lie on the sand, which nothing in the arena hides: no depth test needed, and no
        // flicker from two surfaces at the same height.
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glEnable(GL_BLEND)
        for (dummy in game.dummies) if (dummy.standing > .02f) d.shadow(dummy.x, dummy.z, .46f * dummy.standing, .30f * dummy.standing)
        for (weapon in game.weapons) {
            if (game.world.floorAt(weapon.x, weapon.z) > 0f) continue
            if (weapon.held || weapon.flying) d.shadow(weapon.x, weapon.z, .20f, .16f) else d.shadow(weapon.x, weapon.z, .17f, .26f)
        }
        for (i in 0..1) if (game.handPresent[i]) {
            val x = game.palm[i * 3]; val y = game.palm[i * 3 + 1]; val z = game.palm[i * 3 + 2]
            if (game.world.floorAt(x, z) > 0f) continue
            // The shadow tells how high the hand is, which is what moving depends on.
            val above = max(0f, y)
            d.shadow(x, z, .17f + above * .10f, (.34f - above * .16f).coerceIn(.08f, .34f))
            if (game.body.touching[i] && y < .3f) d.spot(x, z, .30f, 1f, 1f, 1f, .28f)
        }
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthMask(true)

        for (dummy in game.dummies) d.dummy(dummy)
        for (weapon in game.weapons) d.weapon(weapon)
        for (i in 0..1) if (game.handPresent[i]) {
            val j = game.handJoints[i]
            d.arm(game.shoulder, game.elbow, i * 3, j[0], j[1], j[2], Coliseum.ARM_SCALE, rigs[i].confidence, c.eyeX, c.eyeY, c.eyeZ)
            d.hand(j, Coliseum.ARM_SCALE, rigs[i].palmSign, rigs[i].confidence, c.eyeX, c.eyeY, c.eyeZ)
        }

        // A hand that is not followed is never drawn as if it were: a small dot says so instead.
        var any = false
        for (i in 0..1) if (showLost(i)) {
            if (!any) { glDisable(GL_DEPTH_TEST); glDepthMask(false); glEnable(GL_BLEND); any = true }
            d.lostMarker(c, if (i == 0) -1f else 1f, .75f + .25f * kotlin.math.sin(seconds * 4f))
        }
        if (any) { glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthMask(true) }
    }

    /**
     * The dot for hand [i] shows while that hand is lost, unless the player is plainly using the
     * other hand alone and this one has been away for a good while.
     */
    private fun showLost(i: Int): Boolean {
        if (rigs[i].state != HandRig.State.LOST) return false
        if (game.held[i] != null) return true
        val otherFollowed = rigs[1 - i].state != HandRig.State.LOST
        return !(otherFollowed && lostFor[i] > FORGET_HAND_SECONDS)
    }

    // ---------------------------------------------------------------- hands

    private fun updateHands(dt: Float, now: Long) {
        if (tracker.frames.read(frame)) {
            statsResults++
            for (i in 0..1) if (frame.hands[i].present) rigs[i].accept(frame.hands[i], frame.timeNs, frame.aspect, frame.focal)
        }
        for (i in 0..1) {
            val rig = rigs[i]
            rig.advance(now, dt)
            val shown = rig.state != HandRig.State.LOST
            lostFor[i] = if (shown) 0f else min(lostFor[i] + dt, NEVER_SEEN)
            val input = inputs[i]
            input.present = shown
            input.closed = shown && rig.closed
            if (shown) System.arraycopy(rig.joints, 0, input.joints, 0, input.joints.size)
        }
    }

    // ---------------------------------------------------------------- status

    private fun notice(text: String) {
        callbacks.status(text)
        // Long messages stay long enough to be read.
        noticeUntilNs = System.nanoTime() + max(NOTICE_NS, text.length * NOTICE_NS_PER_CHARACTER)
        statusKey = -1L
    }

    private fun updateStatus(now: Long) {
        pendingNotice.getAndSet(null)?.let { notice(it) }
        if (now < noticeUntilNs) return
        val error = trackerError
        val shown = (if (inputs[0].present) 1 else 0) + (if (inputs[1].present) 1 else 0)
        val armed = game.held[0] != null || game.held[1] != null
        val state = if (error != null) 9L else if (shown == 0) 1L else if (armed) 3L else 2L
        val key = state * 100_000_000L + lookMode.ordinal * 10_000_000L + (if (stereo.stereo) 5_000_000L else 0L) +
            game.blows * 1_000L + game.dummiesDown
        if (key == statusKey) return
        statusKey = key
        callbacks.status(when {
            error != null -> error
            shown == 0 -> "Rastreamento perdido: mostre as mãos para a câmera, a uns 40 cm. " + when (lookMode) {
                LookMode.GYROSCOPE -> "Gire a cabeça para olhar em volta."
                LookMode.TILT -> if (stereo.stereo) "Sem giroscópio: incline a cabeça para o lado para virar." else "Incline o celular para olhar para cima e para baixo e arraste o dedo para virar."
                LookMode.TOUCH -> "Arraste o dedo na tela para olhar em volta."
            }
            armed -> "Golpes: ${game.blows} · Bonecos derrubados: ${game.dummiesDown}. Bata nos bonecos de palha. A arma fica na mão sozinha; para largar, feche a mão e abra."
            else -> "Olhe para baixo, encoste a mão no chão e puxe para andar (como no Gorilla Tag). Empurre o chão para pular. Encoste a mão no cabo de uma arma para pegar: não precisa segurar."
        })
    }

    /**
     * Once a second: how fast the screen is drawn, how long drawing takes, how fast the camera
     * delivers, how often the hands are really updated, how long the networks take and how old a
     * reading is when it arrives. Each number is measured where it happens; none stands in for
     * another.
     */
    private fun updateStats(now: Long) {
        statsFrames++
        if (statsStartNs == 0L) { statsStartNs = now; statsFrames = 0; statsDrawNs = 0L; statsResults = 0; return }
        val elapsed = now - statsStartNs
        if (elapsed < 1_000_000_000L) return
        val fps = (statsFrames * 1e9f / elapsed).roundToInt()
        val drawMs = statsDrawNs / 1e6f / max(1, statsFrames)
        val results = (statsResults * 1e9f / elapsed).roundToInt()
        val camera = if (tracker.captureIntervalMs > 0f) (1000f / tracker.captureIntervalMs).roundToInt() else 0
        val inference = tracker.inferenceMs.roundToInt()
        val age = tracker.frameAgeMs
        val delay = if (age >= 0f) "${(age + tracker.frameCostMs).roundToInt()} ms" else "≥${tracker.frameCostMs.roundToInt()} ms"
        val where = (if (tracker.usingGpu) "GPU" else "CPU") + (if (tracker.lightModel) " leve" else "")
        callbacks.stats(
            "tela $fps qps (${(drawMs * 10f).roundToInt() / 10f} ms) · câmera $camera qps · rastreio $results Hz · " +
                "rede $inference ms · atraso $delay · mãos ${tracker.handsInView} · $where"
        )
        statsStartNs = now; statsFrames = 0; statsDrawNs = 0L; statsResults = 0
        chooser?.let { c ->
            val action = c.onSecond(fps, tracker.trackedFrames, tracker.trackedCostMs, tracker.usingGpu, tracker.preferGpu)
            if (action != ProcessorChooser.Action.NONE) callbacks.processor(action, c.cpuCostMs, c.gpuCostMs, c.cpuFps, c.gpuFps)
            if (c.finished) chooser = null
        }
    }

    private companion object {
        const val NEVER_SEEN = 1e6f
        /** A hand away for this long, while the other one plays, is no longer pointed out. */
        const val FORGET_HAND_SECONDS = 8f
        /** Leaning the head further than this (radians) turns the view, in the mode without gyroscope. */
        const val LEAN_FROM = .17f
        /** Radians of turn per second for each radian of lean beyond [LEAN_FROM]. */
        const val LEAN_TURN = 2.2f
        const val MAX_ROLL = 1.2f
        const val NOTICE_NS = 2_500_000_000L
        const val NOTICE_NS_PER_CHARACTER = 60_000_000L
    }
}
