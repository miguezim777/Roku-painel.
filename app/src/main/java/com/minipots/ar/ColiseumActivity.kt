package com.minipots.ar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The coliseum game. The screen shows only the arena; the camera is used to follow one hand, and
 * the motion sensors (or the finger, on phones without a gyroscope) to look around.
 */
class ColiseumActivity : ComponentActivity(), ColiseumRenderer.Callbacks, HandTracker.Listener, SensorEventListener {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ColiseumRenderer
    private lateinit var tracker: HandTracker
    private lateinit var processorSetting: ProcessorSetting
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var lookButton: Button
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var settings: SharedPreferences
    private lateinit var sensorManager: SensorManager
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundAnalysis: ImageAnalysis? = null
    private var frontCamera = false
    private var lastStatus = ""
    private var surfaceResumed = false
    private var viewWidth = 0
    private var viewHeight = 0

    // Looking around.
    private val sensorLook = SensorLook()
    private var rotationSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private val lookModes = ArrayList<LookMode>()
    private var lookMode = LookMode.TOUCH
    private var sensorsOn = false
    private val rotationVector = FloatArray(4)
    private val rotationMatrix = FloatArray(9)
    private val lookOut = FloatArray(2)
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityNs = 0L
    private var touchX = 0f
    private var touchY = 0f

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

        settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        findLookModes()

        analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "hand-tracking") }
        tracker = HandTracker(this, this)
        // Without a preview the tracker sees the whole upright camera picture, which is 3:4.
        tracker.viewAspect = .75f
        processorSetting = ProcessorSetting(this, tracker) { message -> renderer.showNotice(message) }
        renderer = ColiseumRenderer(tracker, sensorLook, this, chooseProcessor = processorSetting.automatic)
        renderer.lookMode = lookMode
        processorSetting.startNotice?.let { renderer.showNotice(it) }
        // The model loads while the camera opens, instead of on the first frame.
        analysisExecutor.execute { tracker.prepare() }

        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        // A finger dragged over the picture turns the view.
        surface.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchX = event.x; touchY = event.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - touchX) / view.width.coerceAtLeast(1)
                    val dy = (event.y - touchY) / view.height.coerceAtLeast(1)
                    touchX = event.x; touchY = event.y
                    surface.queueEvent { renderer.drag(dx, dy) }
                }
                MotionEvent.ACTION_UP -> view.performClick()
            }
            true
        }
        surface.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> onSurfaceLayout(right - left, bottom - top) }

        val root = FrameLayout(this)
        root.addView(surface)
        val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "COLISEU"; textSize = 20f; setTextColor(Color.WHITE)
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
            setBackgroundColor(0x99301c0e.toInt())
        }
        overlay.addView(header); overlay.addView(status)
        overlay.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit): Button {
            val b = Button(this).apply { text = label; textSize = 12f; isAllCaps = false; setOnClickListener { action() } }
            line.addView(b, LinearLayout.LayoutParams(-2, -2))
            return b
        }
        lookButton = button(lookLabel()) { nextLookMode() }
        button("Recomeçar") { surface.queueEvent { renderer.restart() } }
        button("Câmera ⟲") { frontCamera = !frontCamera; bindCamera() }
        button("CPU ⇄ GPU") { surface.queueEvent { renderer.stopChoosingProcessor() }; processorSetting.switchByHand() }
        button("Potinhos") { openPots() }
        scroll.addView(line); overlay.addView(scroll)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
        // Only controls consume touches; the transparent overlay otherwise forwards to the game view.
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

    // ---------------------------------------------------------------- ColiseumRenderer.Callbacks (GL thread)

    override fun status(text: String) { runOnUiThread { showStatus(text) } }
    override fun stats(text: String) { runOnUiThread { stats.text = text } }

    override fun processor(action: ProcessorChooser.Action, cpuMs: Float, gpuMs: Float, cpuFps: Float, gpuFps: Float) {
        runOnUiThread { processorSetting.measured(action, cpuMs, gpuMs, cpuFps, gpuFps) }
    }

    override fun blow(strength: Float) {
        // A blow is felt in the hand that holds the phone. Needs no permission.
        runOnUiThread {
            surface.performHapticFeedback(
                if (strength > .7f) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
            )
        }
    }

    // ---------------------------------------------------------------- HandTracker.Listener (tracking thread)

    override fun trackerError(message: String) { renderer.setTrackerError(message) }
    override fun gpuFailed() { runOnUiThread { processorSetting.gpuFailed() } }
    override fun gpuReady() { runOnUiThread { processorSetting.gpuReady() } }

    private fun showStatus(message: String) { if (lastStatus != message) { lastStatus = message; status.text = message } }

    // ---------------------------------------------------------------- looking around

    /**
     * Finds out how this phone can look around. With a gyroscope the view follows the phone.
     * Without one, the accelerometer still tells how far the phone is tilted up or down, and the
     * finger turns left and right. The finger alone always works.
     */
    private fun findLookModes() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyroscope != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (rotationSensor != null) lookModes.add(LookMode.GYROSCOPE)
        if (accelerometer != null) lookModes.add(LookMode.TILT)
        lookModes.add(LookMode.TOUCH)
        val saved = settings.getString(KEY_LOOK, null)
        lookMode = lookModes.firstOrNull { it.name == saved } ?: lookModes[0]
    }

    private fun lookLabel() = when (lookMode) {
        LookMode.GYROSCOPE -> "Olhar: giroscópio"
        LookMode.TILT -> "Olhar: inclinar + dedo"
        LookMode.TOUCH -> "Olhar: dedo"
    }

    private fun nextLookMode() {
        lookMode = lookModes[(lookModes.indexOf(lookMode) + 1) % lookModes.size]
        settings.edit().putString(KEY_LOOK, lookMode.name).apply()
        lookButton.text = lookLabel()
        if (sensorsOn) { stopSensors(); startSensors() }
        renderer.lookMode = lookMode
        renderer.showNotice(when (lookMode) {
            LookMode.GYROSCOPE -> "Olhar com o giroscópio: gire o celular. Arrastar o dedo na tela também vira."
            LookMode.TILT -> "Sem giroscópio: incline o celular para olhar para cima e para baixo e arraste o dedo na tela para virar."
            LookMode.TOUCH -> "Olhar com o dedo: arraste na tela para olhar em volta."
        })
    }

    private fun startSensors() {
        sensorsOn = true
        sensorLook.ready = false
        gravityNs = 0L
        val sensor = when (lookMode) {
            LookMode.GYROSCOPE -> rotationSensor
            LookMode.TILT -> accelerometer
            LookMode.TOUCH -> null
        }
        if (sensor != null) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopSensors() {
        sensorsOn = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                if (lookMode != LookMode.GYROSCOPE || values.size < 3) return
                // Some phones add an accuracy value at the end, which older systems reject.
                val count = min(values.size, 4)
                for (i in 0 until 4) rotationVector[i] = if (i < count) values[i] else 0f
                if (count == 4) SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
                else {
                    // Three values: the fourth is implied. The array handed over must have exactly three.
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, floatArrayOf(values[0], values[1], values[2]))
                }
                Look.fromRotationMatrix(rotationMatrix, lookOut)
                sensorLook.yaw = lookOut[0]; sensorLook.pitch = lookOut[1]; sensorLook.ready = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (lookMode != LookMode.TILT || values.size < 3) return
                // The reading also contains every shake of the hand: only its slow part is gravity.
                val dt = if (gravityNs == 0L) 0f else (event.timestamp - gravityNs) / 1e9f
                gravityNs = event.timestamp
                val k = if (dt <= 0f || dt > .5f) 1f else dt / (GRAVITY_SMOOTHING_SECONDS + dt)
                gravityX += (values[0] - gravityX) * k
                gravityY += (values[1] - gravityY) * k
                gravityZ += (values[2] - gravityZ) * k
                sensorLook.pitch = Look.pitchFromGravity(gravityX, gravityY, gravityZ)
                sensorLook.ready = true
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
     * Renders the GL surface at no more than [MAX_RENDER_WIDTH] pixels across: weak GPUs are
     * limited by the number of pixels they fill.
     */
    private fun onSurfaceLayout(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == viewWidth && height == viewHeight)) return
        viewWidth = width; viewHeight = height
        if (width > MAX_RENDER_WIDTH) {
            surface.holder.setFixedSize(MAX_RENDER_WIDTH, (height.toFloat() * MAX_RENDER_WIDTH / width).roundToInt())
        } else surface.holder.setSizeFromLayout()
    }

    /** The camera only feeds the hand tracker; its picture is not shown. */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (isDestroyed || isFinishing) return
        try {
            val back = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            if (!back && !front) { showStatus("Nenhuma câmera encontrada neste aparelho."); return }
            if (frontCamera && !front) frontCamera = false
            if (!frontCamera && !back) frontCamera = true
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                )
                // Only ever the newest frame: tracking never works through a backlog.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            // The front camera is a mirror: the hand moves the way its reflection would.
            tracker.mirror = frontCamera
            analysis.setAnalyzer(analysisExecutor, tracker)
            provider.unbindAll()
            provider.bindToLifecycle(this, if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            boundAnalysis = analysis
        } catch (e: Exception) {
            showStatus("Não foi possível abrir a câmera: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    /** Leaves for the other game. Only one of them holds the camera and the tracker at a time. */
    private fun openPots() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (!surfaceResumed) { surface.onResume(); surfaceResumed = true }
        startSensors()
    }

    override fun onPause() {
        stopSensors()
        if (surfaceResumed) { surface.onPause(); surfaceResumed = false }
        super.onPause()
    }

    override fun onStop() {
        processorSetting.stillAlive()
        super.onStop()
    }

    override fun onDestroy() {
        // Only the stream this screen started: when the other game was opened it is already using
        // the camera, and it must keep it.
        val analysis = boundAnalysis
        boundAnalysis = null
        if (analysis != null) {
            analysis.clearAnalyzer()
            try { cameraProvider?.unbind(analysis) } catch (_: Exception) {}
        }
        // After that no more frames arrive; the tracker is closed on its own thread.
        analysisExecutor.execute { tracker.close() }
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val MAX_RENDER_WIDTH = 720
        const val KEY_LOOK = "look"
        /** Time over which the accelerometer is averaged to find gravity. */
        const val GRAVITY_SMOOTHING_SECONDS = .12f
    }
}
