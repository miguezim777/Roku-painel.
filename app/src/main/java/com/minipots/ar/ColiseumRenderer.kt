package com.minipots.ar

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.Process
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the motion sensors say about where the phone points. Written by the sensor thread. */
class SensorLook {
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    /** False until the sensor in use has given its first reading. */
    @Volatile var ready = false
}

/**
 * Renders the coliseum and runs the game on the GL thread.
 *
 * The camera is only used to follow the hand; the picture on screen is entirely the arena. The
 * tracked hand is drawn as a solid hand at the end of a long arm, where the real hand is in front
 * of the phone, and everything the player does goes through it: planting it on the ground to move
 * (as in Gorilla Tag), closing it to take a weapon, swinging it to strike.
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

    private val game = Coliseum()
    private val look = LookControl()
    private var drawing: ColiseumDrawing? = null
    private var width = 1
    private var height = 1

    // Hand, in camera space at the scale of the player's arms.
    private val sample = HandSample()
    private var handShown = false
    private var handLostFor = 0f
    private var handClosed = false
    private var localX = 0f
    private var localY = 0f
    private var localZ = -1f
    /** Joints around the middle of the hand. */
    private val joints = FloatArray(HAND_POINTS * 3)
    private val target = FloatArray(HAND_POINTS * 3)
    private val worldJoints = FloatArray(HAND_POINTS * 3)

    private var lastFrameNs = 0L
    private var statusKey = -1L
    private var noticeUntilNs = 0L
    @Volatile private var trackerError: String? = null
    private val pendingNotice = AtomicReference<String?>(null)
    private var chooser: ProcessorChooser? = if (chooseProcessor) ProcessorChooser() else null
    private var statsStartNs = 0L
    private var statsFrames = 0

    fun setTrackerError(message: String) { trackerError = message }

    /** Shows a passing message. Can be called from any thread. */
    fun showNotice(message: String) { pendingNotice.set(message) }

    // ---------------------------------------------------------------- commands (GL thread)

    /** The finger moved by these fractions of the screen. */
    fun drag(dx: Float, dy: Float) { look.drag(dx, dy) }

    /** Back to the gate, weapons back in the sand, and the phone's direction is "forward" again. */
    fun restart() { game.restart(); look.recentre(); statusKey = -1L }

    /** The user chose the processor by hand: the automatic comparison stops. */
    fun stopChoosingProcessor() { chooser = null }

    // ---------------------------------------------------------------- GL callbacks

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Above the tracking threads, so inference can never take the frame rate down with it.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Exception) {}
        glClearColor(1f, .86f, .70f, 1f)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDisable(GL_CULL_FACE); glDisable(GL_DITHER)
        drawing = try { ColiseumDrawing() } catch (e: RuntimeException) {
            callbacks.status("OpenGL indisponível neste aparelho: ${e.message ?: e.javaClass.simpleName}"); null
        }
        lastFrameNs = 0L; statusKey = -1L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        glViewport(0, 0, width, height)
        game.camera.setAspect(width.toFloat() / height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) .016f else min((now - lastFrameNs) / 1e9f, .05f)
        lastFrameNs = now
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val d = drawing ?: return

        look.update(lookMode, sensors.yaw, sensors.pitch, sensors.ready)
        updateHand(dt, now)
        // A held weapon lies along the knuckles and turns its edge the way the knuckles face.
        game.update(
            dt, look.yaw, look.pitch, handShown, localX, localY, localZ, handClosed,
            joints[15] - joints[51], joints[16] - joints[52], joints[17] - joints[53],
            joints[27] - joints[0], joints[28] - joints[1], joints[29] - joints[2],
        )
        if (game.blowStrength > 0f) callbacks.blow(game.blowStrength)

        val c = game.camera
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
            if (weapon.held) d.shadow(weapon.x, weapon.z, .20f, .16f) else d.shadow(weapon.x, weapon.z, .17f, .26f)
        }
        if (game.handPresent) {
            // The shadow tells how high the hand is, which is what walking depends on.
            val above = max(0f, game.handY)
            d.shadow(game.handX, game.handZ, .17f + above * .10f, (.34f - above * .16f).coerceIn(.08f, .34f))
            if (game.player.touching) d.spot(game.handX, game.handZ, .30f, 1f, 1f, 1f, .28f)
        }
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthMask(true)

        for (dummy in game.dummies) d.dummy(dummy)
        for (weapon in game.weapons) d.weapon(weapon)
        if (game.handPresent) {
            for (i in 0 until HAND_POINTS) {
                val x = joints[i * 3]; val y = joints[i * 3 + 1]; val z = joints[i * 3 + 2]
                worldJoints[i * 3] = game.handX + c.worldX(x, y, z)
                worldJoints[i * 3 + 1] = game.handY + c.worldY(x, y, z)
                worldJoints[i * 3 + 2] = game.handZ + c.worldZ(x, y, z)
            }
            // The arm goes down and back towards the body, out of the bottom of the picture.
            val sx = localX * .45f; val sy = SHOULDER_Y; val sz = SHOULDER_Z
            d.hand(
                worldJoints, Coliseum.ARM_SCALE,
                c.eyeX + c.worldX(sx, sy, sz), c.eyeY + c.worldY(sx, sy, sz), c.eyeZ + c.worldZ(sx, sy, sz),
            )
        }

        updateStatus(now)
        updateStats(now)
    }

    // ---------------------------------------------------------------- hand

    private fun updateHand(dt: Float, now: Long) {
        tracker.state.read(sample)
        val present = sample.present && now - sample.timeNs < STALE_HAND_NS
        if (!present) {
            // A short dropout must not let go of the ground or of what is being carried.
            if (handShown) {
                handLostFor += dt
                if (handLostFor > HAND_GRACE_SECONDS) handShown = false
            }
            return
        }
        handLostFor = 0f
        handClosed = sample.closed

        // Middle of the palm in the camera picture, and in the 3D shape.
        var u = 0f; var v = 0f; var cx = 0f; var cy = 0f; var cz = 0f
        for (i in PALM) {
            u += sample.points[i * 2]; v += sample.points[i * 2 + 1]
            cx += sample.shape[i * 3]; cy += sample.shape[i * 3 + 1]; cz += sample.shape[i * 3 + 2]
        }
        val n = PALM.size
        u /= n; v /= n; cx /= n; cy /= n; cz /= n
        // The hand is put where the real one is in front of the phone, further away by the length
        // of the long arms. Sideways and up it is mapped to the picture on screen, so a hand the
        // camera sees is always a hand the player sees.
        val depth = if (sample.depth > 0f) sample.depth else DEFAULT_DEPTH
        val distance = depth * Coliseum.ARM_SCALE
        val c = game.camera
        val tx = (u - .5f) * 2f * c.tanHalfX * IN_VIEW * distance
        val ty = (.5f - v) * 2f * c.tanHalfY * IN_VIEW * distance
        val tz = -distance
        val scale = Coliseum.ARM_SCALE
        for (i in 0 until HAND_POINTS) {
            target[i * 3] = (sample.shape[i * 3] - cx) * scale
            target[i * 3 + 1] = (sample.shape[i * 3 + 1] - cy) * scale
            target[i * 3 + 2] = (sample.shape[i * 3 + 2] - cz) * scale
        }

        val interval = tracker.intervalMs
        if (!handShown || interval <= SNAP_INTERVAL_MS) {
            // First sight of the hand, or a tracker that keeps up with the camera: shown as it is.
            localX = tx; localY = ty; localZ = tz
            System.arraycopy(target, 0, joints, 0, joints.size)
            handShown = true
        } else {
            // Results arrive much slower than frames are drawn: close in on the newest one over
            // a fraction of the gap, so the hand glides instead of jumping. Nothing is predicted.
            val tau = (interval * .00025f).coerceIn(.010f, .030f)
            val k = 1f - exp(-dt / tau)
            localX += (tx - localX) * k; localY += (ty - localY) * k; localZ += (tz - localZ) * k
            for (i in joints.indices) joints[i] += (target[i] - joints[i]) * k
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
        val state = if (error != null) 9L else if (!handShown) 1L else if (game.held != null) 3L else 2L
        val key = state * 100_000_000L + lookMode.ordinal * 10_000_000L + game.blows * 1_000L + game.dummiesDown
        if (key == statusKey) return
        statusKey = key
        callbacks.status(when {
            error != null -> error
            !handShown -> "Mostre uma mão para a câmera. " + when (lookMode) {
                LookMode.GYROSCOPE -> "Gire o celular para olhar em volta; arrastar o dedo na tela também vira."
                LookMode.TILT -> "Incline o celular para olhar para cima e para baixo e arraste o dedo na tela para virar."
                LookMode.TOUCH -> "Arraste o dedo na tela para olhar em volta."
            }
            game.held != null -> "Golpes: ${game.blows} · Bonecos derrubados: ${game.dummiesDown}. Bata nos bonecos de palha. Abra a mão para soltar a arma."
            else -> "Para andar, encoste a mão no chão e puxe (como no Gorilla Tag). Feche a mão sobre uma arma para pegar."
        })
    }

    private fun updateStats(now: Long) {
        statsFrames++
        if (statsStartNs == 0L) { statsStartNs = now; statsFrames = 0; return }
        val elapsed = now - statsStartNs
        if (elapsed < 1_000_000_000L) return
        val fps = (statsFrames * 1e9f / elapsed).roundToInt()
        val interval = tracker.intervalMs
        val rate = if (interval > 0f) (1000f / interval).roundToInt() else 0
        val where = if (tracker.usingGpu) "GPU" else "CPU"
        callbacks.stats("$fps fps · mão $rate Hz · ${tracker.frameCostMs.roundToInt()} ms · $where")
        statsStartNs = now; statsFrames = 0
        chooser?.let { c ->
            val action = c.onSecond(fps, tracker.trackedFrames, tracker.trackedCostMs, tracker.usingGpu, tracker.preferGpu)
            if (action != ProcessorChooser.Action.NONE) callbacks.processor(action, c.cpuCostMs, c.gpuCostMs, c.cpuFps, c.gpuFps)
            if (c.finished) chooser = null
        }
    }

    private companion object {
        /** Landmarks around the palm; their mean is the place of the hand. */
        val PALM = intArrayOf(0, 5, 9, 13, 17)
        const val HAND_GRACE_SECONDS = .30f
        const val STALE_HAND_NS = 700_000_000L
        /** Up to this many milliseconds between results, results are shown without any easing. */
        const val SNAP_INTERVAL_MS = 45f
        /** Used until the tracker has measured how far the hand is (metres). */
        const val DEFAULT_DEPTH = .45f
        /** The hand stays inside this share of the picture, so it is not cut by the edges. */
        const val IN_VIEW = .9f
        // Where the arm comes from, in camera space: below and a little behind the eyes.
        const val SHOULDER_Y = -1.0f
        const val SHOULDER_Z = .3f
        const val NOTICE_NS = 2_500_000_000L
        const val NOTICE_NS_PER_CHARACTER = 60_000_000L
    }
}
