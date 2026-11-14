package com.minipots.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), RoomRenderer.Callbacks, HandTracker.Listener {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: RoomRenderer
    private lateinit var tracker: HandTracker
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var processorSetting: ProcessorSetting
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundPreview: Preview? = null
    private var boundAnalysis: ImageAnalysis? = null
    private var frontCamera = false
    private var lastStatus = ""
    private var surfaceResumed = false
    private var viewWidth = 0
    private var viewHeight = 0
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else showStatus("A câmera é necessária para ver a sua mão. Autorize-a nas configurações do aparelho.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The game is played with the hand in the air: without this the screen would switch off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "hand-tracking") }
        tracker = HandTracker(this, this)
        processorSetting = ProcessorSetting(this, tracker) { message -> renderer.showNotice(message) }
        renderer = RoomRenderer(tracker, this, chooseProcessor = processorSetting.automatic)
        processorSetting.startNotice?.let { renderer.showNotice(it) }
        // The model loads while the camera opens, instead of on the first frame.
        analysisExecutor.execute { tracker.prepare() }
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        surface.setOnTouchListener { view, event ->
            val u = event.x / view.width.coerceAtLeast(1)
            val v = event.y / view.height.coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> surface.queueEvent { renderer.touchDown(u, v) }
                MotionEvent.ACTION_MOVE -> surface.queueEvent { renderer.touchMove(u, v) }
                MotionEvent.ACTION_UP -> { surface.queueEvent { renderer.touchUp(u, v) }; view.performClick() }
                MotionEvent.ACTION_CANCEL -> surface.queueEvent { renderer.touchCancel() }
            }
            true
        }
        surface.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> onSurfaceLayout(right - left, bottom - top) }

        val root = FrameLayout(this)
        root.addView(surface)
        val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "MINI POTS ✦ MÃO"; textSize = 20f; setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        stats = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.END
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        header.addView(title, LinearLayout.LayoutParams(-2, -2))
        header.addView(stats, LinearLayout.LayoutParams(0, -2, 1f))
        status = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); setPadding(12, 10, 12, 10)
            setBackgroundColor(0x990e1830.toInt())
        }
        overlay.addView(header); overlay.addView(status)
        overlay.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
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
        row("+ Potinho" to { command { switchMode(Mode.POT) } },
            "Mover pote" to { command { switchMode(Mode.MOVE) } },
            "Bola" to { command { itemMode(ItemKind.BALL) } },
            "Cubo" to { command { itemMode(ItemKind.CUBE) } },
            "Moeda" to { command { itemMode(ItemKind.COIN) } },
            "Estrela" to { command { itemMode(ItemKind.STAR) } },
            "Pedra" to { command { itemMode(ItemKind.STONE) } })
        row("Tampa" to { command { toggleLid() } }, "Cor" to { command { changeColor() } }, "−" to { command { resize(0.85f) } },
            "+" to { command { resize(1.18f) } }, "Girar ↻" to { command { rotateSelected() } },
            "Retirar" to { command { removeItem() } }, "Excluir" to { command { deleteSelected() } },
            "Câmera ⟲" to { switchCamera() },
            "CPU ⇄ GPU" to { switchProcessor() },
            "Coliseu" to { openColiseum() })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
        // Only controls consume touches; the transparent overlay otherwise forwards to the camera view.
        overlay.isClickable = false
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + 12, bars.top + 15, bars.right + 12, bars.bottom + 14)
            insets
        }
        setContentView(root)

        showStatus("Preparando a câmera…")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ---------------------------------------------------------------- RoomRenderer.Callbacks (GL thread)

    override fun status(text: String) { runOnUiThread { showStatus(text) } }
    override fun stats(text: String) { runOnUiThread { stats.text = text } }
    override fun contextLost() { runOnUiThread { bindCamera() } }

    override fun processor(action: ProcessorChooser.Action, cpuMs: Float, gpuMs: Float, cpuFps: Float, gpuFps: Float) {
        runOnUiThread { processorSetting.measured(action, cpuMs, gpuMs, cpuFps, gpuFps) }
    }

    // ---------------------------------------------------------------- HandTracker.Listener (tracking thread)

    override fun trackerError(message: String) { renderer.setTrackerError(message) }

    override fun gpuFailed() { runOnUiThread { processorSetting.gpuFailed() } }

    override fun gpuReady() { runOnUiThread { processorSetting.gpuReady() } }

    private fun showStatus(message: String) { if (lastStatus != message) { lastStatus = message; status.text = message } }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                showStatus("Não foi possível abrir a câmera: ${e.localizedMessage ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Renders the GL surface at no more than [MAX_RENDER_WIDTH] pixels across. The camera image has
     * less detail than that anyway, and weak GPUs are limited by the number of pixels they fill.
     */
    private fun onSurfaceLayout(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == viewWidth && height == viewHeight)) return
        viewWidth = width; viewHeight = height
        if (width > MAX_RENDER_WIDTH) {
            surface.holder.setFixedSize(MAX_RENDER_WIDTH, (height.toFloat() * MAX_RENDER_WIDTH / width).roundToInt())
        } else surface.holder.setSizeFromLayout()
        bindCamera()
    }

    private fun switchCamera() {
        frontCamera = !frontCamera
        bindCamera()
    }

    /**
     * Moves the tracking networks between CPU and GPU by hand. This ends the automatic comparison
     * and the choice is kept for the next launches.
     */
    private fun switchProcessor() {
        surface.queueEvent { renderer.stopChoosingProcessor() }
        processorSetting.switchByHand()
    }

    /** Leaves for the other game. Only one of them holds the camera and the tracker at a time. */
    private fun openColiseum() {
        startActivity(Intent(this, ColiseumActivity::class.java))
        finish()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (viewWidth == 0 || viewHeight == 0 || isDestroyed || isFinishing) return
        try {
            val back = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            if (!back && !front) { showStatus("Nenhuma câmera encontrada neste aparelho."); return }
            if (frontCamera && !front) frontCamera = false
            if (!frontCamera && !back) frontCamera = true
            val mirror = frontCamera
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation

            // Both streams use the sensor's own 4:3 shape, which every camera supports and which
            // gives the widest view. The preview is as sharp as the capped render surface; the
            // tracker gets 640x480, from which MediaPipe crops the hand to 224 px.
            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .setTargetFrameRate(Range(30, 30))
                .setResolutionSelector(selector(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { request -> renderer.setSurfaceRequest(request, mirror) }
            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(selector(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                // Only ever the newest frame: tracking never works through a backlog.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            tracker.mirror = mirror
            analysis.setAnalyzer(analysisExecutor, tracker)
            // The view port makes CameraX report, for both streams, the exact part of the image
            // that fills the screen, so the hand skeleton lines up with the picture.
            val viewPort = ViewPort.Builder(Rational(viewWidth, viewHeight), rotation).setScaleType(ViewPort.FILL_CENTER).build()
            val group = UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(analysis).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, group)
            boundPreview = preview; boundAnalysis = analysis
        } catch (e: Exception) {
            showStatus("Não foi possível abrir a câmera: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Lets go of the streams this screen started, and of nothing else: when the other game was
     * opened it is already using the camera, and it must keep it.
     */
    private fun releaseCamera() {
        val provider = cameraProvider ?: return
        val preview = boundPreview; val analysis = boundAnalysis
        boundPreview = null; boundAnalysis = null
        analysis?.clearAnalyzer()
        try {
            if (preview != null) provider.unbind(preview)
            if (analysis != null) provider.unbind(analysis)
        } catch (_: Exception) {}
    }

    private fun selector(size: Size, fallback: Int) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(size, fallback))
        .build()

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (!surfaceResumed) { surface.onResume(); surfaceResumed = true }
    }

    override fun onPause() {
        if (surfaceResumed) { surface.onPause(); surfaceResumed = false }
        super.onPause()
    }

    override fun onStop() {
        processorSetting.stillAlive()
        super.onStop()
    }

    override fun onDestroy() {
        releaseCamera()
        // After unbinding no more frames arrive; the tracker is closed on its own thread.
        analysisExecutor.execute { tracker.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val MAX_RENDER_WIDTH = 720
    }
}
