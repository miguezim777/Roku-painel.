package com.minipots.ar

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.Debug
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
    private val handConfidence = FloatArray(2)
    // How the head was turned lately, to put each reading of a hand where the head was when its
    // picture was taken (see [HandRig.accept]). Only with a sensor that follows the real head.
    private val history = LookHistory()
    private val lookAt = FloatArray(3)
    private val turnThen = FloatArray(9)
    private val turnNow = FloatArray(9)
    private var steady = false
    private var droppedFrames = 0
    private val handPalmSign = FloatArray(2)
    private val handMark = IntArray(2)

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

    /** How the hands move the body. */
    fun setLocomotion(mode: Locomotion) { game.body.mode = mode; statusKey = -1L }

    /** Height of the eyes above the floor, and how many times further than the real hands the hands reach. */
    fun setBody(eyeHeight: Float, reach: Float) { game.eyeHeight = eyeHeight; game.reach = reach }

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
        // A frame that took as long as two or more: the screen showed the one before again.
        if (lastFrameNs != 0L && now - lastFrameNs > DROPPED_FRAME_NS) droppedFrames++
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
        // Dragging a finger or leaning to turn moves the view without moving the head, so the
        // hands stay with the camera then; only a sensor that follows the head steadies them.
        val steadyNow = lookMode == LookMode.GYROSCOPE && sensors.ready
        if (steadyNow != steady) { steady = steadyNow; history.clear(); rigs[0].reset(); rigs[1].reset() }
        if (steady) {
            history.add(now, sensors.yaw, look.pitch, roll)
            Look.cameraToWorld(sensors.yaw, look.pitch, roll, turnNow)
        }
        updateHands(dt, now)
        game.update(dt, look.yaw, look.pitch, roll, inputs)
        if (game.blowStrength > 0f) callbacks.blow(game.blowStrength)

        val c = game.camera
        stereo.layout(width, height)
        for (i in 0..1) { handConfidence[i] = rigs[i].confidence; handPalmSign[i] = rigs[i].palmSign; handMark[i] = markOf(i) }
        for (eye in 0 until stereo.eyes) {
            val x = stereo.viewportX(eye); val w = stereo.viewportWidth(eye)
            glViewport(x, 0, w, stereo.viewportHeight)
            glEnable(GL_SCISSOR_TEST); glScissor(x, 0, w, stereo.viewportHeight)
            c.eye(stereo.eyeOffset(eye), stereo.left(eye), stereo.right(eye), stereo.top)
            d.scene(game, c, handConfidence, handPalmSign, handMark, seconds)
        }
        glDisable(GL_SCISSOR_TEST)

        updateStatus(now)
        statsDrawNs += System.nanoTime() - now
        updateStats(now)
    }

    /** The dot that tells how hand [i] is followed. */
    private fun markOf(i: Int): Int = when {
        showLost(i) -> ColiseumDrawing.MARK_LOST
        rigs[i].state == HandRig.State.HELD -> ColiseumDrawing.MARK_HELD
        rigs[i].state == HandRig.State.TRACKED -> ColiseumDrawing.MARK_LIVE
        else -> ColiseumDrawing.MARK_NONE
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
            var turn: FloatArray? = null
            if (steady) {
                // The reading is stamped when work started on its picture; the picture itself is
                // older by the age the tracker measures.
                val age = tracker.frameAgeMs
                val takenNs = frame.timeNs - (if (age > 0f) (age * 1e6f).toLong() else 0L)
                if (history.at(takenNs, lookAt)) { Look.cameraToWorld(lookAt[0], lookAt[1], lookAt[2], turnThen); turn = turnThen }
                else turn = turnNow
            }
            for (i in 0..1) if (frame.hands[i].present) rigs[i].accept(frame.hands[i], frame.timeNs, frame.aspect, frame.focal, turn)
        }
        for (i in 0..1) {
            val rig = rigs[i]
            rig.advance(now, dt)
            val shown = rig.state != HandRig.State.LOST
            lostFor[i] = if (shown) 0f else min(lostFor[i] + dt, NEVER_SEEN)
            val input = inputs[i]
            input.present = shown
            // Only a hand whose readings are fresh moves the body; a held pose is just drawn.
            input.live = rig.state == HandRig.State.TRACKED
            input.closed = shown && rig.closed
            if (!shown) continue
            if (!steady) { System.arraycopy(rig.joints, 0, input.joints, 0, input.joints.size); continue }
            // From the steady axes back to those of the camera as the head is turned now.
            val t = turnNow; val from = rig.joints; val to = input.joints
            for (k in 0 until HAND_POINTS) {
                val x = from[k * 3]; val y = from[k * 3 + 1]; val z = from[k * 3 + 2]
                to[k * 3] = t[0] * x + t[3] * y + t[6] * z
                to[k * 3 + 1] = t[1] * x + t[4] * y + t[7] * z
                to[k * 3 + 2] = t[2] * x + t[5] * y + t[8] * z
            }
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
        val fluid = game.body.mode == Locomotion.FLUID
        val key = state * 1_000_000_000L + (if (fluid) 100_000_000L else 0L) + lookMode.ordinal * 10_000_000L +
            (if (stereo.stereo) 5_000_000L else 0L) + game.blows * 1_000L + game.dummiesDown
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
            fluid -> "Modo fluido: com a mão abaixo dos olhos, puxe o ar para trás para ir para a frente (ou para um lado, para ir para o outro). Suba a mão até a altura dos olhos para trazê-la de volta. As mãos para baixo, de uma vez, pulam."
            else -> "Olhe para baixo, encoste a mão no chão e puxe para andar (como no Gorilla Tag). Empurre o chão para pular; puxe a parede para baixo para subir. Encoste a mão no cabo de uma arma para pegar: não precisa segurar."
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
        // Memory in use: what the app's own objects take, and what the native side (the model,
        // the camera buffers) takes.
        val runtime = Runtime.getRuntime()
        val heapMb = ((runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L).toInt()
        val nativeMb = try { (Debug.getNativeHeapAllocatedSize() / 1_048_576L).toInt() } catch (_: Throwable) { -1 }
        val memory = if (nativeMb >= 0) "$heapMb+$nativeMb MB" else "$heapMb MB"
        val moving = if (game.body.mode == Locomotion.FLUID) "fluido" else "contato"
        callbacks.stats(
            "tela $fps qps (${(drawMs * 10f).roundToInt() / 10f} ms) · quedas $droppedFrames · câmera $camera qps · rastreio $results Hz · " +
                "rede $inference ms · atraso $delay · mãos ${tracker.handsInView} · $where · física ${(1f / GorillaBody.STEP).roundToInt()} Hz $moving · " +
                "saltos de mão ${game.body.rejected + rigs[0].rejected + rigs[1].rejected} · mem $memory"
        )
        droppedFrames = 0
        statsStartNs = now; statsFrames = 0; statsDrawNs = 0L; statsResults = 0
        chooser?.let { c ->
            val action = c.onSecond(fps, tracker.trackedFrames, tracker.trackedCostMs, tracker.usingGpu, tracker.preferGpu)
            if (action != ProcessorChooser.Action.NONE) callbacks.processor(action, c.cpuCostMs, c.gpuCostMs, c.cpuFps, c.gpuFps)
            if (c.finished) chooser = null
        }
    }

    private companion object {
        const val NEVER_SEEN = 1e6f
        /** A frame later than this after the one before missed at least one refresh of a 60 Hz screen. */
        const val DROPPED_FRAME_NS = 25_000_000L
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
