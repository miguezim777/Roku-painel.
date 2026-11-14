package com.minipots.ar

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.Process
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Mode { POT, MOVE, ITEM, INTERACT }
enum class ItemKind { BALL, CUBE, COIN, STAR, STONE }

/** The virtual table, in metres. It is fixed in front of the phone: there is no room mapping. */
object Table {
    const val GRID_MIN_X = -.20f
    const val GRID_MAX_X = .20f
    const val GRID_MIN_Z = -.48f
    const val GRID_MAX_Z = .22f
    /** Limits for the centre of pots and objects, so they always stay on screen. */
    const val MIN_X = -.155f
    const val MAX_X = .155f
    const val MIN_Z = -.42f
    const val MAX_Z = .17f
}

class Pot(var x: Float, var z: Float, var color: Int) {
    var size = 1f
    var angle = 0f
    var open = false
    var lid = 0f
    val items = ArrayList<Item>()
}

/** Inside a pot x, y, z are relative to the pot; outside they are table coordinates. */
class Item(val kind: ItemKind, var color: Int) {
    var pot: Pot? = null
    var x = 0f
    var y = 0f
    var z = 0f
    var velocity = 0f
    var size = 1f
    var angle = 0f
}

/** A camera preview stream rendered through an external texture. */
private class CameraStream(
    val texture: Int, val surfaceTexture: SurfaceTexture, val width: Int, val height: Int, val mirror: Boolean,
) {
    @Volatile var info: SurfaceRequest.TransformationInfo? = null
    @Volatile var released = false
}

private class PendingStream(val request: SurfaceRequest, val mirror: Boolean)

/**
 * Game and renderer. Everything here runs on the GL thread.
 *
 * The hand is read once per frame from [HandTracker.state]; the frame never waits for the tracker.
 * A pinch (thumb and index together) acts as a finger on the screen: where it starts, moves and
 * ends drives exactly the same code as touch.
 */
class RoomRenderer(private val tracker: HandTracker, private val callbacks: Callbacks) : GLSurfaceView.Renderer {
    interface Callbacks {
        fun status(text: String)
        fun stats(text: String)
        /** The GL context was recreated: the camera must be bound again to get a new surface. */
        fun contextLost()
    }

    private var mode = Mode.POT
    private var itemKind = ItemKind.BALL
    private val pots = ArrayList<Pot>()
    private val loose = ArrayList<Item>()
    private val drawOrder = ArrayList<Pot>()
    private var selectedPot: Pot? = null
    private var selectedItem: Item? = null

    private var drawing: Drawing? = null
    private var width = 1
    private var height = 1
    private val camera = SceneCamera(0f, .62f, .50f, 0f, .02f, -.06f, 34f)
    private val palette = arrayOf(
        floatArrayOf(1f, .40f, .47f, 1f), floatArrayOf(.39f, .71f, .96f, 1f),
        floatArrayOf(1f, .82f, .40f, 1f), floatArrayOf(.65f, .91f, .53f, 1f),
    )
    private val model = FloatArray(16)
    private val base = FloatArray(16)
    private val hit = FloatArray(2)
    private val screenA = FloatArray(2)
    private val screenB = FloatArray(2)
    private val world = FloatArray(3)

    // Camera preview.
    private val pending = AtomicReference<PendingStream?>(null)
    private var stream: CameraStream? = null
    private val directExecutor = Executor { it.run() }
    private val textureMatrix = FloatArray(16)
    private val texRange = FloatArray(4)
    private val texCoords = FloatArray(8)

    // Hand.
    private val sample = HandSample()
    private val display = FloatArray(HAND_POINTS * 2)
    private var handShown = false
    private var handLostFor = 0f
    private var handPinching = false
    private var cursorX = 0f
    private var cursorY = 0f

    // Pointer: one finger on the screen or one pinching hand.
    private var pointerActive = false
    private var pointerFromHand = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragItem: Item? = null
    private var dragPot: Pot? = null
    private var grabX = 0f
    private var grabZ = 0f

    private var lastFrameNs = 0L
    private var statusKey = -1
    private var noticeUntilNs = 0L
    @Volatile private var trackerError: String? = null
    private var statsStartNs = 0L
    private var statsFrames = 0

    // ---------------------------------------------------------------- camera stream

    /** Called from the main thread when CameraX wants a surface for the preview. */
    fun setSurfaceRequest(request: SurfaceRequest, mirror: Boolean) {
        pending.getAndSet(PendingStream(request, mirror))?.request?.willNotProvideSurface()
    }

    fun setTrackerError(message: String) { trackerError = message }

    private fun accept(p: PendingStream) {
        val ids = IntArray(1); glGenTextures(1, ids, 0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val size = p.request.resolution
        val surfaceTexture = SurfaceTexture(ids[0])
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)
        val s = CameraStream(ids[0], surfaceTexture, size.width, size.height, p.mirror)
        try {
            p.request.setTransformationInfoListener(directExecutor) { s.info = it }
            p.request.provideSurface(surface, directExecutor) {
                s.released = true
                surface.release(); surfaceTexture.release()
            }
        } catch (_: IllegalStateException) {
            // The request was withdrawn while it waited for the GL thread; a newer one follows.
            surface.release(); surfaceTexture.release(); deleteTexture(ids[0])
            return
        }
        stream?.let { deleteTexture(it.texture) }
        stream = s
    }

    private fun deleteTexture(texture: Int) { glDeleteTextures(1, intArrayOf(texture), 0) }

    /** Latches the newest camera frame and draws it. Returns false while there is nothing to show. */
    private fun drawCamera(d: Drawing): Boolean {
        pending.getAndSet(null)?.let { accept(it) }
        val s = stream ?: return false
        if (s.released) { deleteTexture(s.texture); stream = null; return false }
        try {
            s.surfaceTexture.updateTexImage()
            val info = s.info ?: return false
            if (s.surfaceTexture.timestamp == 0L) return false
            s.surfaceTexture.getTransformMatrix(textureMatrix)
            FrameMapping.texRange(textureMatrix, texRange)
            val crop = info.cropRect
            FrameMapping.previewTexCoords(
                texCoords, s.width, s.height, crop.left, crop.top, crop.right, crop.bottom,
                info.rotationDegrees, s.mirror, texRange,
            )
        } catch (_: RuntimeException) {
            // The camera released this surface between two frames; the next stream replaces it.
            return false
        }
        d.background(s.texture, texCoords)
        return true
    }

    // ---------------------------------------------------------------- GL callbacks

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Above the tracking threads, so inference can never take the frame rate down with it.
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Exception) {}
        glClearColor(.05f, .07f, .12f, 1f)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDisable(GL_CULL_FACE); glDisable(GL_DITHER)
        drawing = try { Drawing() } catch (e: RuntimeException) {
            callbacks.status("OpenGL indisponível neste aparelho: ${e.message ?: e.javaClass.simpleName}"); null
        }
        // Textures of a previous context are gone; ask for a new camera surface.
        if (stream != null) { stream = null; callbacks.contextLost() }
        lastFrameNs = 0L; statusKey = -1
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        glViewport(0, 0, width, height)
        camera.setAspect(width.toFloat() / height)
        tracker.viewAspect = width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) .016f else min((now - lastFrameNs) / 1e9f, .05f)
        lastFrameNs = now
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val d = drawing ?: return
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_BLEND)
        val cameraOn = drawCamera(d)

        updateHand(dt, now)
        simulate(dt)

        d.beginScene()
        glEnable(GL_BLEND)
        drawTable(d)
        glEnable(GL_DEPTH_TEST); glDepthMask(true)
        drawObjects(d)
        glDisable(GL_DEPTH_TEST)
        if (handShown && sample.present) d.hand(display, width.toFloat() / height, handPinching)
        glDisable(GL_BLEND)

        updateStatus(cameraOn, now)
        updateStats(now)
    }

    // ---------------------------------------------------------------- hand

    private fun updateHand(dt: Float, now: Long) {
        tracker.state.read(sample)
        val present = sample.present && now - sample.timeNs < STALE_HAND_NS
        if (present) {
            if (!handShown) {
                System.arraycopy(sample.points, 0, display, 0, display.size)
                handShown = true
            } else {
                // Results arrive slower than frames are drawn. Easing towards the newest result
                // over a fraction of the gap between results keeps motion fluid; it is not a
                // smoothing window and never holds a result back.
                val tau = (tracker.intervalMs * .0004f).coerceIn(.008f, .040f)
                val k = 1f - exp(-dt / tau)
                for (i in display.indices) display[i] += (sample.points[i] - display[i]) * k
            }
            handLostFor = 0f
            cursorX = (display[8] + display[16]) * .5f * width
            cursorY = (display[9] + display[17]) * .5f * height
            if (sample.pinch) {
                if (!handPinching) {
                    if (!pointerActive) { handPinching = true; pointerDown(cursorX, cursorY, true) }
                } else pointerMove(cursorX, cursorY)
            } else if (handPinching) {
                handPinching = false
                pointerUp()
            }
        } else if (handShown) {
            // A short dropout must not drop what is being carried.
            handLostFor += dt
            if (handLostFor > HAND_GRACE_SECONDS) {
                handShown = false
                if (handPinching) { handPinching = false; pointerCancel() }
            }
        }
    }

    // ---------------------------------------------------------------- pointer

    /** Touch entry points; coordinates are normalized to the view (0..1). */
    fun touchDown(u: Float, v: Float) {
        if (pointerActive && pointerFromHand) { handPinching = false; pointerCancel() }
        pointerDown(u * width, v * height, false)
    }
    fun touchMove(u: Float, v: Float) { if (pointerActive && !pointerFromHand) pointerMove(u * width, v * height) }
    fun touchUp(u: Float, v: Float) { if (pointerActive && !pointerFromHand) { pointerMove(u * width, v * height); pointerUp() } }
    fun touchCancel() { if (pointerActive && !pointerFromHand) pointerCancel() }

    private fun pointerDown(x: Float, y: Float, fromHand: Boolean) {
        pointerActive = true; pointerFromHand = fromHand
        downX = x; downY = y
        dragging = false; dragItem = null; dragPot = null
        if (mode != Mode.INTERACT) return
        val reach = if (fromHand) HAND_REACH else 1f
        dragItem = pickItem(x, y, reach)
        if (dragItem == null) {
            val pot = pickPot(x, y, reach)
            if (pot != null && tableHit(x, y, 0f)) { dragPot = pot; grabX = pot.x - hit[0]; grabZ = pot.z - hit[1] }
        }
    }

    private fun pointerMove(x: Float, y: Float) {
        if (!dragging) {
            val slop = width * (if (pointerFromHand) HAND_SLOP else TOUCH_SLOP)
            if (hypot(x - downX, y - downY) <= slop) return
            dragging = true
            dragItem?.let { takeOut(it) }
        }
        val item = dragItem
        val pot = dragPot
        if (item != null) {
            // The object stays between the fingers: on the ray through the pinch, lifted off the table.
            if (tableHit(x, y, CARRY_HEIGHT)) { item.x = clampX(hit[0]); item.z = clampZ(hit[1]) }
            item.y = CARRY_HEIGHT; item.velocity = 0f
        } else if (pot != null) {
            if (tableHit(x, y, 0f)) { pot.x = clampX(hit[0] + grabX); pot.z = clampZ(hit[1] + grabZ) }
        }
    }

    private fun pointerUp() {
        if (!pointerActive) return
        pointerActive = false
        if (dragging) finishDrag() else tap(downX, downY, if (pointerFromHand) HAND_REACH else 1f)
        dragging = false; dragItem = null; dragPot = null
    }

    private fun pointerCancel() {
        if (!pointerActive) return
        pointerActive = false
        if (dragging) finishDrag()
        dragging = false; dragItem = null; dragPot = null
    }

    private fun finishDrag() {
        val item = dragItem
        val pot = dragPot
        if (item != null) {
            val target = potAt(item.x, item.z)
            if (target != null && target.open && target.items.size < MAX_ITEMS_PER_POT) putIn(item, target)
            else {
                if (target != null) notice(if (target.open) "Este pote está cheio (máximo: $MAX_ITEMS_PER_POT)." else "Abra o pote antes de guardar um objeto.")
                pushOutOfPots(item)
            }
            selectedItem = item; selectedPot = item.pot ?: selectedPot
        } else if (pot != null) {
            selectedPot = pot; selectedItem = null
        }
    }

    private fun tap(x: Float, y: Float, reach: Float) {
        when (mode) {
            Mode.POT -> if (tableHit(x, y, 0f) && onTable()) {
                if (pots.size >= MAX_POTS) notice("Máximo de $MAX_POTS potinhos. Exclua um para colocar outro.")
                else {
                    val p = Pot(clampX(hit[0]), clampZ(hit[1]), pots.size % palette.size)
                    pots.add(p); selectedPot = p; selectedItem = null; switchMode(Mode.INTERACT)
                }
            }
            Mode.MOVE -> {
                val p = selectedPot
                if (p == null) { notice("Nenhum potinho selecionado."); switchMode(Mode.INTERACT) }
                else if (tableHit(x, y, 0f) && onTable()) { p.x = clampX(hit[0]); p.z = clampZ(hit[1]); switchMode(Mode.INTERACT) }
            }
            Mode.ITEM -> {
                val target = pickPot(x, y, reach)
                if (target != null) {
                    if (target.open && target.items.size < MAX_ITEMS_PER_POT) {
                        val item = Item(itemKind, (target.items.size + 1) % palette.size)
                        item.pot = target; item.y = .18f; target.items.add(item)
                        selectedItem = item; selectedPot = target; switchMode(Mode.INTERACT)
                    } else notice("Abra o pote antes de guardar um objeto (máximo: $MAX_ITEMS_PER_POT).")
                } else if (tableHit(x, y, 0f) && onTable()) {
                    if (loose.size >= MAX_LOOSE_ITEMS) notice("Muitos objetos fora dos potes. Guarde ou exclua alguns.")
                    else {
                        val item = Item(itemKind, loose.size % palette.size)
                        item.x = clampX(hit[0]); item.z = clampZ(hit[1]); item.y = CARRY_HEIGHT
                        pushOutOfPots(item); loose.add(item)
                        selectedItem = item; switchMode(Mode.INTERACT)
                    }
                }
            }
            Mode.INTERACT -> {
                val item = pickItem(x, y, reach)
                if (item != null) { selectedItem = item; selectedPot = item.pot ?: selectedPot }
                else pickPot(x, y, reach)?.let { selectedPot = it; selectedItem = null; it.open = !it.open }
            }
        }
    }

    // ---------------------------------------------------------------- picking and placement

    private fun tableHit(px: Float, py: Float, planeY: Float) = camera.rayToPlane(px / width, py / height, planeY, hit)

    /** True when the last [tableHit] landed on (or very near) the table. */
    private fun onTable(): Boolean {
        val m = .06f
        return hit[0] >= Table.GRID_MIN_X - m && hit[0] <= Table.GRID_MAX_X + m &&
            hit[1] >= Table.GRID_MIN_Z - m && hit[1] <= Table.GRID_MAX_Z + m
    }

    private fun clampX(x: Float) = x.coerceIn(Table.MIN_X, Table.MAX_X)
    private fun clampZ(z: Float) = z.coerceIn(Table.MIN_Z, Table.MAX_Z)

    /** Pot whose picture is under the pointer. [reach] widens the target for the hand. */
    private fun pickPot(px: Float, py: Float, reach: Float): Pot? {
        var best: Pot? = null
        var bestScore = 1f
        for (i in pots.indices) {
            val p = pots[i]
            val cy = .05f * p.size
            if (!camera.project(p.x, cy, p.z, screenA) || !camera.project(p.x + .075f * p.size, cy, p.z, screenB)) continue
            val radius = max(abs(screenB[0] - screenA[0]) * width * 1.25f, width * .10f) * reach
            val score = hypot(screenA[0] * width - px, screenA[1] * height - py) / radius
            if (score < bestScore) { bestScore = score; best = p }
        }
        return best
    }

    /** Object under the pointer. Objects inside a closed pot cannot be picked. */
    private fun pickItem(px: Float, py: Float, reach: Float): Item? {
        var best: Item? = null
        var bestScore = 1f
        for (i in loose.indices) {
            val score = itemScore(loose[i], px, py, reach)
            if (score < bestScore) { bestScore = score; best = loose[i] }
        }
        for (i in pots.indices) {
            val p = pots[i]
            if (!p.open) continue
            for (j in p.items.indices) {
                val score = itemScore(p.items[j], px, py, reach)
                if (score < bestScore) { bestScore = score; best = p.items[j] }
            }
        }
        return best
    }

    private fun itemScore(item: Item, px: Float, py: Float, reach: Float): Float {
        worldPosition(item)
        val scale = item.size * (item.pot?.size ?: 1f)
        if (!camera.project(world[0], world[1], world[2], screenA) ||
            !camera.project(world[0] + .028f * scale, world[1], world[2], screenB)) return Float.MAX_VALUE
        val radius = max(abs(screenB[0] - screenA[0]) * width * 1.7f, width * .06f) * reach
        return hypot(screenA[0] * width - px, screenA[1] * height - py) / radius
    }

    /** Writes the table position of [item] to [world], also for objects inside a pot. */
    private fun worldPosition(item: Item) {
        val p = item.pot
        if (p == null) { world[0] = item.x; world[1] = item.y; world[2] = item.z; return }
        val a = p.angle * DEGREES; val c = cos(a); val s = sin(a)
        world[0] = p.x + (c * item.x + s * item.z) * p.size
        world[1] = item.y * p.size
        world[2] = p.z + (-s * item.x + c * item.z) * p.size
    }

    /** Pot whose opening is under the table point (x, z). */
    private fun potAt(x: Float, z: Float): Pot? {
        var best: Pot? = null
        var bestDistance = Float.MAX_VALUE
        for (i in pots.indices) {
            val p = pots[i]
            val d = hypot(x - p.x, z - p.z)
            if (d < .085f * p.size && d < bestDistance) { bestDistance = d; best = p }
        }
        return best
    }

    /** Moves an object from the table into [pot], keeping its place so it drops in from there. */
    private fun putIn(item: Item, pot: Pot) {
        worldPosition(item)
        item.pot?.items?.remove(item); loose.remove(item)
        val a = pot.angle * DEGREES; val c = cos(a); val s = sin(a)
        val dx = world[0] - pot.x; val dz = world[2] - pot.z
        item.x = (c * dx - s * dz) / pot.size
        item.z = (s * dx + c * dz) / pot.size
        item.y = max(world[1] / pot.size, .14f)
        item.velocity = 0f; item.pot = pot
        pot.items.add(item)
    }

    /** Moves an object out of its pot onto the table, at the place where it is drawn. */
    private fun takeOut(item: Item) {
        val p = item.pot ?: return
        worldPosition(item)
        p.items.remove(item); item.pot = null
        item.x = world[0]; item.y = world[1]; item.z = world[2]; item.velocity = 0f
        loose.add(item)
    }

    /** An object left on the table must not sit inside a pot wall. */
    private fun pushOutOfPots(item: Item) {
        for (i in pots.indices) {
            val p = pots[i]
            val clear = .075f * p.size + .035f * item.size
            var dx = item.x - p.x; var dz = item.z - p.z
            val d = hypot(dx, dz)
            if (d >= clear) continue
            if (d < 1e-4f) { dx = if (p.x > 0f) -1f else 1f; dz = 0f } else { dx /= d; dz /= d }
            // Prefer the side that keeps the object on the table.
            if (p.x + dx * clear > Table.MAX_X || p.x + dx * clear < Table.MIN_X) dx = -dx
            if (p.z + dz * clear > Table.MAX_Z || p.z + dz * clear < Table.MIN_Z) dz = -dz
            item.x = clampX(p.x + dx * clear); item.z = clampZ(p.z + dz * clear)
        }
    }

    // ---------------------------------------------------------------- simulation

    private fun simulate(dt: Float) {
        val settle = min(1f, dt * 8f)
        for (i in pots.indices) {
            val pot = pots[i]
            pot.lid += ((if (pot.open) 1f else 0f) - pot.lid) * min(1f, dt * 7f)
            val items = pot.items
            for (j in items.indices) {
                val item = items[j]
                item.x += (slotX(j) - item.x) * settle
                item.z += (slotZ(j) - item.z) * settle
                fall(item, POT_FLOOR + (if (j >= SLOTS_PER_LAYER) LAYER_HEIGHT else 0f) + restHeight(item.kind) * item.size, dt)
            }
        }
        val held = if (dragging) dragItem else null
        for (i in loose.indices) {
            val item = loose[i]
            if (item !== held) fall(item, restHeight(item.kind) * item.size, dt)
        }
    }

    private fun fall(item: Item, floor: Float, dt: Float) {
        item.velocity -= GRAVITY * dt
        item.y += item.velocity * dt
        if (item.y < floor) {
            item.y = floor
            item.velocity = if (item.velocity < -.15f) -item.velocity * .28f else 0f
        }
    }

    // Objects in a pot sit in two layers of six: one in the middle and five around it.
    private fun slotX(index: Int): Float {
        val k = index % SLOTS_PER_LAYER
        return if (k == 0) 0f else cos(slotAngle(index, k)) * .037f
    }

    private fun slotZ(index: Int): Float {
        val k = index % SLOTS_PER_LAYER
        return if (k == 0) 0f else sin(slotAngle(index, k)) * .037f
    }

    private fun slotAngle(index: Int, k: Int) = (k - 1) * 1.2566f + (if (index >= SLOTS_PER_LAYER) .6283f else 0f)

    /** Height of the centre of each shape when it rests on a surface. */
    private fun restHeight(kind: ItemKind) = when (kind) {
        ItemKind.BALL -> .025f
        ItemKind.CUBE -> .022f
        ItemKind.COIN -> 0f
        ItemKind.STAR -> .006f
        ItemKind.STONE -> .0175f
    }

    // ---------------------------------------------------------------- drawing

    private fun drawTable(d: Drawing) {
        val vp = camera.viewProjection
        d.grid(vp)
        for (i in pots.indices) { val p = pots[i]; d.mark(vp, p.x, p.z, .093f * p.size, false, 0f, 0f, 0f, .24f) }
        for (i in loose.indices) { val o = loose[i]; d.mark(vp, o.x, o.z, .03f * o.size, false, 0f, 0f, 0f, .22f) }
        val item = selectedItem
        val pot = selectedPot
        if (item != null && item.pot == null) d.mark(vp, item.x, item.z, .046f * item.size, true, 1f, .85f, .25f, .9f)
        else if (item != null) item.pot?.let { d.mark(vp, it.x, it.z, .108f * it.size, true, 1f, .85f, .25f, .9f) }
        else if (pot != null) d.mark(vp, pot.x, pot.z, .108f * pot.size, true, 1f, .85f, .25f, .9f)
        // Where the pinch points on the table.
        if (handShown && sample.present && tableHit(cursorX, cursorY, 0f) && onTable()) {
            if (handPinching) d.mark(vp, hit[0], hit[1], .020f, false, 1f, .82f, .2f, .95f)
            else d.mark(vp, hit[0], hit[1], .030f, true, 1f, 1f, 1f, .95f)
        }
    }

    private fun drawObjects(d: Drawing) {
        val vp = camera.viewProjection
        for (i in loose.indices) {
            val item = loose[i]
            Mat4.identity(model); Mat4.translate(model, item.x, item.y, item.z)
            Mat4.rotateY(model, item.angle); Mat4.scale(model, item.size, item.size, item.size)
            d.item(item.kind, model, vp, palette[item.color])
        }
        // Pots are translucent: far ones first so near ones blend over them.
        drawOrder.clear(); drawOrder.addAll(pots)
        for (i in 1 until drawOrder.size) {
            val p = drawOrder[i]
            var j = i - 1
            while (j >= 0 && drawOrder[j].z > p.z) { drawOrder[j + 1] = drawOrder[j]; j-- }
            drawOrder[j + 1] = p
        }
        for (i in drawOrder.indices) {
            val pot = drawOrder[i]
            Mat4.identity(base); Mat4.translate(base, pot.x, 0f, pot.z)
            Mat4.rotateY(base, pot.angle); Mat4.scale(base, pot.size, pot.size, pot.size)
            if (pot.lid > .02f) {
                val items = pot.items
                for (j in items.indices) {
                    val item = items[j]
                    Mat4.copy(model, base); Mat4.translate(model, item.x, item.y, item.z)
                    Mat4.rotateY(model, item.angle); Mat4.scale(model, item.size, item.size, item.size)
                    d.item(item.kind, model, vp, palette[item.color])
                }
            }
            d.pot(base, vp, palette[pot.color], pot.lid)
        }
    }

    // ---------------------------------------------------------------- status

    private fun notice(text: String) {
        callbacks.status(text)
        noticeUntilNs = System.nanoTime() + NOTICE_NS
        statusKey = -1
    }

    private fun updateStatus(cameraOn: Boolean, now: Long) {
        if (now < noticeUntilNs) return
        val error = trackerError
        val hand = handShown || (pointerActive && !pointerFromHand)
        val key = if (error != null) 1000 else if (!cameraOn) 1001 else if (!hand) 1002 else mode.ordinal * 10 + itemKind.ordinal
        if (key == statusKey) return
        statusKey = key
        callbacks.status(when {
            error != null -> error
            !cameraOn -> "Abrindo a câmera…"
            !hand -> "Mostre uma mão para a câmera. Junte polegar e indicador (pinça) para tocar; tocar na tela também funciona."
            else -> when (mode) {
                Mode.POT -> "Faça a pinça sobre a mesa virtual para colocar um potinho."
                Mode.MOVE -> "Faça a pinça onde o potinho selecionado deve ficar."
                Mode.ITEM -> "Pinça em um pote aberto guarda ${kindName(itemKind)}; na mesa, deixa fora."
                Mode.INTERACT -> "Pinça rápida no pote abre ou fecha. Segure a pinça para arrastar potes e objetos."
            }
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
        callbacks.stats("$fps fps · mão $rate Hz · ${tracker.frameCostMs.roundToInt()} ms")
        statsStartNs = now; statsFrames = 0
    }

    private fun kindName(k: ItemKind) = when (k) {
        ItemKind.BALL -> "uma bola"; ItemKind.CUBE -> "um cubo"; ItemKind.COIN -> "uma moeda"
        ItemKind.STAR -> "uma estrela"; ItemKind.STONE -> "uma pedra"
    }

    // ---------------------------------------------------------------- commands (buttons)

    fun switchMode(m: Mode) { mode = m; statusKey = -1 }
    fun itemMode(kind: ItemKind) { itemKind = kind; switchMode(Mode.ITEM) }

    fun toggleLid() {
        val p = selectedPot
        if (p == null) notice("Nenhum potinho selecionado.") else { p.open = !p.open; selectedItem = null; switchMode(Mode.INTERACT) }
    }

    fun changeColor() {
        selectedItem?.let { it.color = (it.color + 1) % palette.size; return }
        selectedPot?.let { it.color = (it.color + 1) % palette.size }
    }

    fun resize(factor: Float) {
        selectedItem?.let { it.size = (it.size * factor).coerceIn(.6f, 1.5f); return }
        selectedPot?.let { it.size = (it.size * factor).coerceIn(.65f, 1.6f) }
    }

    fun rotateSelected() {
        selectedItem?.let { it.angle += 30f; return }
        selectedPot?.let { it.angle += 30f }
    }

    /** Takes the selected object out of its pot and leaves it on the table beside the pot. */
    fun removeItem() {
        val item = selectedItem ?: return
        val p = item.pot ?: return
        if (!p.open) { notice("Abra a tampa para retirar o objeto."); return }
        if (loose.size >= MAX_LOOSE_ITEMS) { notice("Muitos objetos fora dos potes. Guarde ou exclua alguns."); return }
        takeOut(item)
        val side = if (p.x > 0f) -1f else 1f
        item.x = clampX(p.x + side * (.075f * p.size + .05f)); item.z = p.z; item.y = .08f
        pushOutOfPots(item)
    }

    fun deleteSelected() {
        val item = selectedItem
        if (item != null) {
            if (item === dragItem) { dragItem = null; dragging = false }
            item.pot?.items?.remove(item); loose.remove(item); selectedItem = null
            return
        }
        selectedPot?.let { p ->
            if (p === dragPot) { dragPot = null; dragging = false }
            pots.remove(p); selectedPot = null
        }
    }

    private companion object {
        const val DEGREES = 0.017453292f
        const val MAX_POTS = 6
        const val MAX_ITEMS_PER_POT = 12
        const val MAX_LOOSE_ITEMS = 24
        const val SLOTS_PER_LAYER = 6
        const val LAYER_HEIGHT = .042f
        const val POT_FLOOR = .012f
        const val GRAVITY = 2.2f
        /** Height at which a carried object travels, in metres above the table. */
        const val CARRY_HEIGHT = .07f
        /** Movement, as a share of the screen width, below which a touch or pinch is a tap. */
        const val TOUCH_SLOP = .03f
        const val HAND_SLOP = .07f
        /** How much larger targets are for the hand than for a finger on the glass. */
        const val HAND_REACH = 1.3f
        const val HAND_GRACE_SECONDS = .30f
        const val STALE_HAND_NS = 700_000_000L
        const val NOTICE_NS = 2_500_000_000L
    }
}
