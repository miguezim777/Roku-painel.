package com.minipots.ar

import android.Manifest
import android.content.SharedPreferences
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
    private lateinit var settings: SharedPreferences
    private var cameraProvider: ProcessCameraProvider? = null
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
        settings = getSharedPreferences("settings", MODE_PRIVATE)
        var processor = settings.getString(KEY_PROCESSOR, PROCESSOR_AUTO)
        val gpuClosedTheApp = settings.getBoolean(KEY_GPU_STARTING, false)
        if (gpuClosedTheApp) {
            // The mark is written before the GPU is started and removed once it works. Finding it
            // here means the app died in between, so the GPU is not tried again by itself.
            processor = PROCESSOR_CPU
            settings.edit().putString(KEY_PROCESSOR, PROCESSOR_CPU).putBoolean(KEY_GPU_STARTING, false).commit()
        }
        tracker = HandTracker(this, this)
        renderer = RoomRenderer(tracker, this, chooseProcessor = processor == PROCESSOR_AUTO)
        if (processor == PROCESSOR_GPU) startGpu()
        if (gpuClosedTheApp) renderer.showNotice("Da última vez o app fechou ao usar a GPU. O rastreamento fica na CPU.")
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
            "CPU ⇄ GPU" to { switchProcessor() })
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
        runOnUiThread {
            val cpu = cpuMs.roundToInt(); val gpu = gpuMs.roundToInt()
            when (action) {
                ProcessorChooser.Action.TRY_GPU -> {
                    startGpu()
                    renderer.showNotice("A CPU leva $cpu ms por quadro da mão. Testando a GPU: o esqueleto da mão fica parado por alguns segundos. Depois, mantenha a mão à vista.")
                }
                ProcessorChooser.Action.KEEP_GPU -> {
                    saveProcessor(PROCESSOR_GPU)
                    renderer.showNotice("Rastreamento na GPU: $gpu ms por quadro, contra $cpu ms na CPU.")
                }
                ProcessorChooser.Action.KEEP_CPU -> {
                    tracker.preferGpu = false
                    saveProcessor(PROCESSOR_CPU)
                    // gpuMs is 0 when the GPU was not measured: not needed, or it failed (said elsewhere).
                    if (gpuMs > 0f) renderer.showNotice(
                        if (gpuFps < cpuFps * ProcessorChooser.FRAME_RATE_KEPT)
                            "Na GPU o jogo caiu de ${cpuFps.roundToInt()} para ${gpuFps.roundToInt()} fps. O rastreamento fica na CPU ($cpu ms por quadro; na GPU, $gpu ms)."
                        else "A GPU não ajudou neste aparelho: $gpu ms por quadro, contra $cpu ms na CPU. O rastreamento fica na CPU."
                    )
                }
                ProcessorChooser.Action.NONE -> {}
            }
        }
    }

    // ---------------------------------------------------------------- HandTracker.Listener (tracking thread)

    override fun trackerError(message: String) { renderer.setTrackerError(message) }

    override fun gpuFailed() {
        runOnUiThread {
            saveProcessor(PROCESSOR_CPU)
            renderer.showNotice("GPU indisponível neste aparelho. O rastreamento continua na CPU.")
        }
    }

    override fun gpuReady() { runOnUiThread { settings.edit().putBoolean(KEY_GPU_STARTING, false).apply() } }

    // ---------------------------------------------------------------- CPU or GPU

    /**
     * Starting the networks on the GPU can close the app on phones with a faulty driver, and that
     * cannot be caught. So a mark is saved first (synchronously: it has to survive the crash) and
     * removed when the GPU delivers results; see [onCreate] for what happens if it is found.
     */
    private fun startGpu() {
        settings.edit().putBoolean(KEY_GPU_STARTING, true).commit()
        tracker.preferGpu = true
    }

    private fun saveProcessor(processor: String) {
        val edit = settings.edit().putString(KEY_PROCESSOR, processor)
        if (processor == PROCESSOR_CPU) edit.putBoolean(KEY_GPU_STARTING, false)
        edit.apply()
    }

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
     * and the choice is kept for the next launches. The tracker still returns to the CPU by itself
     * if the GPU cannot be used.
     */
    private fun switchProcessor() {
        val gpu = !tracker.preferGpu
        surface.queueEvent { renderer.stopChoosingProcessor() }
        if (gpu) startGpu() else tracker.preferGpu = false
        saveProcessor(if (gpu) PROCESSOR_GPU else PROCESSOR_CPU)
        renderer.showNotice(
            if (gpu) "Rastreamento na GPU. Compare os números no canto: se o fps cair, volte para a CPU."
            else "Rastreamento na CPU."
        )
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (viewWidth == 0 || viewHeight == 0 || isDestroyed) return
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
        } catch (e: Exception) {
            showStatus("Não foi possível abrir a câmera: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
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
        // Still alive, so the GPU did not close the app. Without this, leaving before the GPU has
        // delivered a result would be taken for a crash on the next launch.
        settings.edit().putBoolean(KEY_GPU_STARTING, false).apply()
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        // After unbinding no more frames arrive; the tracker is closed on its own thread.
        analysisExecutor.execute { tracker.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val MAX_RENDER_WIDTH = 720
        const val KEY_PROCESSOR = "processor"
        const val KEY_GPU_STARTING = "gpu_starting"
        const val PROCESSOR_AUTO = "auto"
        const val PROCESSOR_CPU = "cpu"
        const val PROCESSOR_GPU = "gpu"
    }
}
