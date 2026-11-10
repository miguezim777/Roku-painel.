package com.minipots.ar

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session

class MainActivity : Activity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: RoomRenderer
    private lateinit var status: TextView
    private var session: Session? = null
    private var installRequested = false
    private var resumedSurface = false
    private var available = false
    private var lastStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
        }
        renderer = RoomRenderer(this) { message -> runOnUiThread { showStatus(message) } }
        surface.setRenderer(renderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        var downX = 0f; var downY = 0f
        surface.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; val x = downX; val y = downY; surface.queueEvent { renderer.beginTouch(x, y) }; true }
                MotionEvent.ACTION_MOVE -> { val x = event.x; val y = event.y; surface.queueEvent { renderer.moveTouch(x, y) }; true }
                MotionEvent.ACTION_UP -> {
                    val x = event.x; val y = event.y
                    surface.queueEvent { renderer.touch(downX, downY, x, y) }; true
                }
                else -> true
            }
        }
        val root = android.widget.FrameLayout(this)
        root.addView(surface)
        val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 15, 12, 14) }
        val title = TextView(this).apply {
            text = "MINI POTS AR ✦"; textSize = 20f; setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        status = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); setPadding(12, 10, 12, 10)
            setBackgroundColor(0x990e1830.toInt())
        }
        overlay.addView(title); overlay.addView(status)
        val spacer = View(this); overlay.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        fun row(vararg entries: Pair<String, () -> Unit>) {
            val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            entries.forEach { (label, action) ->
                line.addView(Button(this).apply {
                    text = label; textSize = 12f; isAllCaps = false; setOnClickListener { action() }
                }, LinearLayout.LayoutParams(-2, -2))
            }
            scroll.addView(line); overlay.addView(scroll)
        }
        fun command(block: RoomRenderer.() -> Unit) { surface.queueEvent { renderer.block() } }
        row("+ Potinho" to { command { mode = Mode.POT; notifyMode() } },
            "Mover pote" to { command { mode = Mode.MOVE; notifyMode() } },
            "Bola" to { command { itemMode(ItemKind.BALL) } },
            "Cubo" to { command { itemMode(ItemKind.CUBE) } },
            "Moeda" to { command { itemMode(ItemKind.COIN) } },
            "Estrela" to { command { itemMode(ItemKind.STAR) } },
            "Pedra" to { command { itemMode(ItemKind.STONE) } })
        row("Tampa" to { command { toggleLid() } }, "Cor" to { command { changeColor() } }, "−" to { command { resize(0.85f) } },
            "+" to { command { resize(1.18f) } }, "Girar ↻" to { command { rotateSelected() } },
            "Retirar" to { command { removeItem() } }, "Excluir" to { command { deleteSelected() } })
        root.addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
        // Only controls consume touches; the transparent overlay otherwise forwards to the camera.
        overlay.isClickable = false
        setContentView(root)
        showStatus("Verificando compatibilidade com ARCore…")
        checkAvailability()
    }

    private fun showStatus(message: String) { if (lastStatus != message) { lastStatus = message; status.text = message } }
    private fun checkAvailability() {
        val a = ArCoreApk.getInstance().checkAvailability(this)
        if (a.isTransient) { status.postDelayed({ checkAvailability() }, 300); return }
        if (!a.isSupported) {
            showStatus("Este aparelho não oferece suporte ao ARCore. O mapeamento real não está disponível.")
            return
        }
        available = true
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
        } else if (!isFinishing) startAr()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 10 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startAr()
        else showStatus("A câmera é necessária para detectar superfícies reais. Autorize-a nas configurações.")
    }
    private fun startAr() {
        if (!available || resumedSurface || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                session = Session(this).also {
                    val config = Config(it)
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    it.configure(config)
                }
                renderer.session = session
            }
            session?.resume()
            surface.onResume(); resumedSurface = true
            showStatus("Mova o celular lentamente para encontrar superfícies reais.")
        } catch (e: Exception) {
            showStatus("Não foi possível iniciar o ARCore: ${e.localizedMessage ?: e.javaClass.simpleName}. Verifique o Google Play Services para RA e a câmera.")
        }
    }
    override fun onResume() { super.onResume(); if (available) startAr() }
    override fun onPause() {
        if (resumedSurface) { surface.onPause(); session?.pause(); resumedSurface = false }
        super.onPause()
    }
    override fun onDestroy() { session?.close(); session = null; super.onDestroy() }
}
