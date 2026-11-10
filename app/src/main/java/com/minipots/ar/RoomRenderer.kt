package com.minipots.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

enum class Mode { POT, MOVE, ITEM, INTERACT }
enum class ItemKind { BALL, CUBE, COIN, STAR, STONE }
private data class Pot(var anchor: Anchor, var color: Int, var size: Float = 1f, var angle: Float = 0f,
                       var open: Boolean = false, var lid: Float = 0f, val items: MutableList<Item> = mutableListOf())
private data class Item(val kind: ItemKind, var anchor: Anchor? = null, var pot: Pot? = null,
                        var x: Float = 0f, var y: Float = 0.025f, var z: Float = 0f,
                        var velocity: Float = 0f, var size: Float = 1f, var angle: Float = 0f, var color: Int = 0)

class RoomRenderer(private val context: Context, private val message: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    var mode = Mode.POT
    private var itemKind = ItemKind.BALL
    private val pots = mutableListOf<Pot>()
    private val loose = mutableListOf<Item>()
    private var selectedPot: Pot? = null
    private var selectedItem: Item? = null
    private var dragItem: Item? = null
    private var dragPose: Pose? = null
    private var downX = 0f; private var downY = 0f
    private var frame: Frame? = null
    private var width = 1; private var height = 1
    private var cameraTexture = 0; private var textureSession: Session? = null
    private lateinit var drawing: Drawing
    private val view = FloatArray(16); private val projection = FloatArray(16); private val vp = FloatArray(16)
    private val palette = arrayOf(floatArrayOf(1f,.40f,.47f,1f), floatArrayOf(.39f,.71f,.96f,1f), floatArrayOf(1f,.82f,.40f,1f), floatArrayOf(.65f,.91f,.53f,1f))
    private var lastTime = 0L
    private var status = ""

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(0f, 0f, 0f, 1f)
        val textures = IntArray(1); glGenTextures(1, textures, 0); cameraTexture = textures[0]
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        drawing = Drawing(); textureSession = null
    }
    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w; height = h; glViewport(0,0,w,h) }
    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        try {
            if (textureSession !== s) { s.setCameraTextureName(cameraTexture); textureSession = s }
            @Suppress("DEPRECATION")
            s.setDisplayGeometry((context as android.app.Activity).windowManager.defaultDisplay.rotation, width, height)
            val f = s.update(); frame = f
            drawing.background(f, cameraTexture)
            val camera = f.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                setStatus("Rastreamento pausado. Aponte para uma área iluminada e mova o celular devagar.")
                return
            }
            camera.getViewMatrix(view, 0); camera.getProjectionMatrix(projection, 0, .05f, 100f)
            Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
            val now = System.nanoTime(); val dt = if (lastTime == 0L) 0.016f else ((now-lastTime)/1e9f).coerceIn(0f, .05f); lastTime = now
            glEnable(GL_DEPTH_TEST); glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            var surfaces = 0
            for (plane in s.getAllTrackables(Plane::class.java)) {
                if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
                surfaces++
                val m = FloatArray(16); plane.centerPose.toMatrix(m, 0)
                drawing.plane(plane.polygon, m, vp)
            }
            for (pot in pots) {
                if (pot.anchor.trackingState != TrackingState.TRACKING) continue
                pot.lid += ((if (pot.open) 1f else 0f) - pot.lid) * min(1f, dt * 7f)
                val base = matrix(pot.anchor.pose, pot.angle, pot.size)
                drawing.pot(base, vp, palette[pot.color], pot.lid)
                for (item in pot.items) {
                    if (item !== dragItem || dragPose == null) {
                        item.velocity -= 1.3f * dt; item.y += item.velocity * dt
                        val floor = .025f * item.size
                        if (item.y < floor) { item.y = floor; item.velocity = if (item.velocity < -.1f) -item.velocity*.28f else 0f }
                        val model = base.copyOf(); Matrix.translateM(model,0,item.x,item.y,item.z); Matrix.rotateM(model,0,item.angle,0f,1f,0f); Matrix.scaleM(model,0,item.size,item.size,item.size)
                        drawing.item(item.kind, model, vp, palette[item.color])
                    }
                }
            }
            for (item in loose) {
                val a = item.anchor ?: continue
                if (a.trackingState != TrackingState.TRACKING || (item === dragItem && dragPose != null)) continue
                val model = matrix(a.pose, item.angle, item.size); Matrix.translateM(model,0,0f,.025f,0f)
                drawing.item(item.kind, model, vp, palette[item.color])
            }
            dragItem?.let { item -> dragPose?.let { pose ->
                val model = matrix(pose, item.angle, item.size); Matrix.translateM(model,0,0f,.03f,0f); drawing.item(item.kind, model, vp, palette[item.color])
            } }
            glDisable(GL_BLEND)
            setStatus(if (surfaces == 0) "Mova o celular lentamente para detectar superfícies reais." else when(mode) {
                Mode.POT -> "Superfície detectada! Toque no chão ou em uma mesa para colocar um potinho."
                Mode.MOVE -> "Toque em uma superfície horizontal para mover o potinho selecionado."
                Mode.ITEM -> "Toque em um pote aberto para adicionar ${kindName(itemKind)} ou numa superfície para colocar fora."
                Mode.INTERACT -> "Toque no pote para abrir ou fechar. Arraste um objeto; use Retirar para colocá-lo fora."
            })
        } catch (e: Exception) { setStatus("AR pausado: ${e.localizedMessage ?: e.javaClass.simpleName}. Tente voltar ao aplicativo.") }
    }
    private fun setStatus(s: String) { if (status != s) { status = s; message(s) } }
    fun notifyMode() { status = "" }
    fun itemMode(kind: ItemKind) { itemKind = kind; mode = Mode.ITEM; notifyMode() }
    private fun kindName(k: ItemKind) = when(k) { ItemKind.BALL -> "uma bola"; ItemKind.CUBE -> "um cubo"; ItemKind.COIN -> "uma moeda"; ItemKind.STAR -> "uma estrela"; ItemKind.STONE -> "uma pedra" }
    private fun matrix(pose: Pose, angle: Float, scale: Float): FloatArray {
        val m = FloatArray(16); pose.toMatrix(m, 0); Matrix.rotateM(m,0,angle,0f,1f,0f); Matrix.scaleM(m,0,scale,scale,scale); return m
    }
    private fun hit(x: Float, y: Float): HitResult? = frame?.hitTest(x, y)?.firstOrNull {
        val p = it.trackable as? Plane
        p != null && p.trackingState == TrackingState.TRACKING && p.type == Plane.Type.HORIZONTAL_UPWARD_FACING && p.isPoseInPolygon(it.hitPose)
    }
    private fun screen(pose: Pose, x: Float = 0f, y: Float = 0f, z: Float = 0f): Pair<Float,Float>? {
        val point = pose.transformPoint(floatArrayOf(x,y,z)); val out = FloatArray(4)
        Matrix.multiplyMV(out,0,vp,0,floatArrayOf(point[0], point[1], point[2], 1f),0)
        if (out[3] <= 0f) return null
        return Pair((out[0]/out[3]*.5f+.5f)*width, (1f-(out[1]/out[3]*.5f+.5f))*height)
    }
    private fun dist(a: Pair<Float,Float>?, x: Float, y: Float): Float = if (a == null) Float.MAX_VALUE else hypot(a.first-x,a.second-y)
    private fun closestPot(x: Float, y: Float): Pot? = pots.filter { it.anchor.trackingState == TrackingState.TRACKING }.minByOrNull { dist(screen(it.anchor.pose,0f,.07f*it.size,0f),x,y) }?.takeIf { dist(screen(it.anchor.pose,0f,.07f*it.size,0f),x,y) < context.resources.displayMetrics.density*65 }
    private fun itemScreen(item: Item): Pair<Float,Float>? {
        val p = item.pot
        if (p != null) {
            val rad = Math.toRadians(p.angle.toDouble()); val x = (cos(rad)*item.x+sin(rad)*item.z).toFloat()*p.size; val z = (-sin(rad)*item.x+cos(rad)*item.z).toFloat()*p.size
            return screen(p.anchor.pose,x,item.y*p.size,z)
        }
        return item.anchor?.let { screen(it.pose,0f,.025f,0f) }
    }
    private fun closestItem(x: Float,y: Float): Item? = (loose + pots.flatMap { it.items }).minByOrNull { dist(itemScreen(it),x,y) }?.takeIf { dist(itemScreen(it),x,y) < context.resources.displayMetrics.density*32 }
    fun beginTouch(x: Float, y: Float) { downX = x; downY = y; dragItem = if (mode == Mode.INTERACT) closestItem(x,y) else null; dragPose = null }
    fun moveTouch(x: Float, y: Float) {
        if (hypot(x-downX,y-downY) > context.resources.displayMetrics.density*12 && dragItem != null && frame?.camera?.trackingState == TrackingState.TRACKING) dragPose = hit(x,y)?.hitPose
    }
    fun touch(startX: Float, startY: Float, x: Float, y: Float) {
        if (frame?.camera?.trackingState != TrackingState.TRACKING) { dragItem = null; dragPose = null; return }
        val dragging = hypot(x-startX,y-startY) > context.resources.displayMetrics.density*12
        if (dragging && dragItem != null) {
            val item = dragItem!!; val target = closestPot(x,y)
            if (target != null && target.open && target.items.size < 12) putIn(item,target)
            else hit(x,y)?.let { placeLoose(item,it) }
            selectedItem = item; selectedPot = item.pot ?: selectedPot; dragItem = null; dragPose = null; mode = Mode.INTERACT; return
        }
        dragItem = null; dragPose = null
        when(mode) {
            Mode.POT -> hit(x,y)?.let { val p = Pot(it.createAnchor(), pots.size % palette.size); pots.add(p); selectedPot=p; selectedItem=null; mode=Mode.INTERACT }
            Mode.MOVE -> hit(x,y)?.let { h -> selectedPot?.let { p -> p.anchor.detach(); p.anchor=h.createAnchor(); mode=Mode.INTERACT } }
            Mode.ITEM -> {
                val target = closestPot(x,y)
                if (target != null) {
                    if (target.open && target.items.size < 12) { val item = Item(itemKind, color = (target.items.size+1)%palette.size); putIn(item,target); selectedItem=item; selectedPot=target; mode=Mode.INTERACT }
                    else { message("Abra o pote antes de guardar um objeto (máximo: 12).") }
                } else hit(x,y)?.let { h -> val item = Item(itemKind,color = loose.size%palette.size); placeLoose(item,h); selectedItem=item; mode=Mode.INTERACT }
            }
            Mode.INTERACT -> {
                val item = closestItem(x,y)
                if (item != null) { selectedItem=item; selectedPot=item.pot ?: selectedPot }
                else closestPot(x,y)?.let { selectedPot=it; selectedItem=null; it.open=!it.open }
            }
        }
        notifyMode()
    }
    private fun putIn(item: Item, pot: Pot) {
        item.pot?.items?.remove(item); loose.remove(item); item.anchor?.detach(); item.anchor=null; item.pot=pot
        val i = pot.items.size; val angle=i*2.4f; val radius=if (i==0) 0f else .028f
        item.x=cos(angle)*radius; item.z=sin(angle)*radius; item.y=.16f; item.velocity=0f; pot.items.add(item)
    }
    private fun placeLoose(item: Item, hit: HitResult) {
        val anchor = hit.createAnchor(); item.anchor?.detach(); item.pot?.items?.remove(item); item.pot=null; item.anchor=anchor; if (!loose.contains(item)) loose.add(item)
    }
    fun toggleLid() { selectedPot?.let { it.open = !it.open; selectedItem = null; mode = Mode.INTERACT } }
    fun changeColor() { selectedItem?.let { it.color=(it.color+1)%palette.size; return }; selectedPot?.let { it.color=(it.color+1)%palette.size } }
    fun resize(factor: Float) { selectedItem?.let { it.size=(it.size*factor).coerceIn(.6f,1.5f); return }; selectedPot?.let { it.size=(it.size*factor).coerceIn(.65f,1.6f) } }
    fun rotateSelected() { selectedItem?.let { it.angle+=30f; return }; selectedPot?.let { it.angle+=30f } }
    fun removeItem() {
        val item=selectedItem ?: return; val p=item.pot ?: return
        if (!p.open) { message("Abra a tampa para retirar o objeto."); return }
        // A new ARCore anchor uses the same tracked plane coordinate system, offset beside the pot.
        val pose=p.anchor.pose.compose(Pose.makeTranslation(.15f*p.size,0f,0f))
        val a=session?.createAnchor(pose) ?: return
        p.items.remove(item); item.pot=null; item.anchor=a; loose.add(item)
    }
    fun deleteSelected() {
        selectedItem?.let { it.anchor?.detach(); it.pot?.items?.remove(it); loose.remove(it); selectedItem=null; return }
        selectedPot?.let { p -> p.anchor.detach(); pots.remove(p); selectedPot=null }
    }
}
