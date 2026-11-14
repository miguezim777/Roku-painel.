package com.minipots.ar

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The coliseum game, made to be played with the phone in goggles (VR Box and the like): the
 * screen lies on its side and shows the arena twice, once for each eye. The camera on the back
 * follows both hands, and the motion sensors follow the head.
 *
 * Inside goggles nobody reaches the screen, so the controls are for before the phone goes in, and
 * the volume keys do what is needed afterwards: up looks straight ahead again, down shows or hides
 * the controls.
 */
class ColiseumActivity : ComponentActivity(), ColiseumRenderer.Callbacks, HandTracker.Listener, SensorEventListener {
    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: ColiseumRenderer
    private lateinit var tracker: HandTracker
    private lateinit var processorSetting: ProcessorSetting
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var lookButton: Button
    private lateinit var stereoButton: Button
    private lateinit var ipdLabel: Button
    private lateinit var overlay: LinearLayout
    private lateinit var modeButton: Button
    private lateinit var bodyLabel: Button
    private var stereoOn = true
    // How the hands move the body, how high the eyes are, and how far the hands reach.
    private var fluid = false
    private var eyeCm = DEFAULT_EYE_CM
    private var reachTenths = DEFAULT_REACH_TENTHS
    // The last minutes of the line of measurements, to be copied out.
    private val measurements = ArrayDeque<String>()
    private var heat = ""
    private var heatAtMs = 0L
    private var ipdMm = 63
    private var lensMm = 42
    private var quarterTurns = 1
    private var compass = false
    private var dragged = false
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
    private val lookOut = FloatArray(3)
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
        hideSystemBars()

        settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        stereoOn = settings.getBoolean(KEY_STEREO, true)
        ipdMm = settings.getInt(KEY_IPD, 63).coerceIn(MIN_IPD_MM, MAX_IPD_MM)
        lensMm = settings.getInt(KEY_LENS, 42).coerceIn(MIN_LENS_MM, MAX_LENS_MM)
        fluid = settings.getBoolean(KEY_FLUID, false)
        eyeCm = settings.getInt(KEY_EYE_CM, DEFAULT_EYE_CM).coerceIn(MIN_EYE_CM, MAX_EYE_CM)
        reachTenths = settings.getInt(KEY_REACH, DEFAULT_REACH_TENTHS).coerceIn(MIN_REACH_TENTHS, MAX_REACH_TENTHS)
        findLookModes()

        analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "hand-tracking") }
        // Both hands, with the lighter of the two hand models.
        tracker = HandTracker(this, this, hands = 2, modelAsset = HandTracker.LIGHT_MODEL_ASSET)
        // The picture is not shown; this only matters to the single-hand readings, unused here.
        tracker.viewAspect = 4f / 3f
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
        applyBody()
        // A finger dragged over the picture turns the view; a tap shows or hides the controls.
        surface.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchX = event.x; touchY = event.y; dragged = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - touchX) / view.width.coerceAtLeast(1)
                    val dy = (event.y - touchY) / view.height.coerceAtLeast(1)
                    if (dragged || abs(dx) + abs(dy) > .02f) {
                        dragged = true
                        touchX = event.x; touchY = event.y
                        surface.queueEvent { renderer.drag(dx, dy) }
                    }
                }
                MotionEvent.ACTION_UP -> { if (!dragged) toggleControls(); view.performClick() }
            }
            true
        }
        surface.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> onSurfaceLayout(right - left, bottom - top) }

        val root = FrameLayout(this)
        root.addView(surface)
        overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "COLISEU  "; textSize = 16f; setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        stats = TextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.END
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }
        header.addView(title, LinearLayout.LayoutParams(-2, -2))
        header.addView(stats, LinearLayout.LayoutParams(0, -2, 1f))
        status = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); setPadding(12, 8, 12, 8)
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
        button("Esconder") { toggleControls() }
        button("Recentrar") { surface.queueEvent { renderer.recentre() } }
        stereoButton = button(stereoLabel()) { stereoOn = !stereoOn; saveStereo() }
        button("DIP −") { ipdMm = (ipdMm - 1).coerceAtLeast(MIN_IPD_MM); saveStereo() }
        ipdLabel = button(ipdText()) { ipdMm = 63; lensMm = 42; saveStereo() }
        button("DIP +") { ipdMm = (ipdMm + 1).coerceAtMost(MAX_IPD_MM); saveStereo() }
        button("Escala −") { lensMm = (lensMm - 2).coerceAtLeast(MIN_LENS_MM); saveStereo() }
        button("Escala +") { lensMm = (lensMm + 2).coerceAtMost(MAX_LENS_MM); saveStereo() }
        lookButton = button(lookLabel()) { nextLookMode() }
        modeButton = button(modeLabel()) { fluid = !fluid; saveBody(); renderer.showNotice(modeNotice()) }
        button("Altura −") { eyeCm = (eyeCm - 5).coerceAtLeast(MIN_EYE_CM); saveBody() }
        bodyLabel = button(bodyText()) { eyeCm = DEFAULT_EYE_CM; reachTenths = DEFAULT_REACH_TENTHS; saveBody() }
        button("Altura +") { eyeCm = (eyeCm + 5).coerceAtMost(MAX_EYE_CM); saveBody() }
        button("Mãos −") { reachTenths = (reachTenths - 1).coerceAtLeast(MIN_REACH_TENTHS); saveBody() }
        button("Mãos +") { reachTenths = (reachTenths + 1).coerceAtMost(MAX_REACH_TENTHS); saveBody() }
        button("Recomeçar") { surface.queueEvent { renderer.restart() } }
        button("Câmera ⟲") { frontCamera = !frontCamera; bindCamera() }
        button("CPU ⇄ GPU") { surface.queueEvent { renderer.stopChoosingProcessor() }; processorSetting.switchByHand() }
        button("Copiar medições") { copyMeasurements() }
        button("Potinhos") { openPots() }
        scroll.addView(line); overlay.addView(scroll)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
        // Only controls consume touches; the transparent overlay otherwise forwards to the game view.
        overlay.isClickable = false
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left + 12, bars.top + 8, bars.right + 12, bars.bottom + 8)
            insets
        }
        setContentView(root)

        showStatus("Preparando a câmera…")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ---------------------------------------------------------------- ColiseumRenderer.Callbacks (GL thread)

    override fun status(text: String) { runOnUiThread { showStatus(text) } }
    override fun stats(text: String) {
        runOnUiThread {
            val line = text + heatText()
            stats.text = line
            measurements.addLast(line)
            while (measurements.size > MAX_MEASUREMENTS) measurements.removeFirst()
        }
    }

    /**
     * How warm the phone is, for the line of measurements: the temperature of the battery, which
     * every phone reports, and from Android 10 on how hard the system is holding the phone back
     * because of heat (0 is not at all). Looked up every few seconds.
     */
    private fun heatText(): String {
        val now = SystemClock.elapsedRealtime()
        if (heatAtMs != 0L && now - heatAtMs < HEAT_EVERY_MS) return heat
        heatAtMs = now
        var text = ""
        try {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths != Int.MIN_VALUE) text += " · bateria ${tenths / 10} °C"
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val status = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                text += " · freio térmico $status"
            } catch (_: Exception) {}
        }
        heat = text
        return text
    }

    /** Puts the last minutes of measurements on the clipboard, with what they were measured on. */
    private fun copyMeasurements() {
        val text = StringBuilder()
        text.append("Coliseu · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
        text.append("${lookLabel()} · ${modeLabel()} · ${bodyText()} · ${stereoLabel()}\n")
        for (line in measurements) text.append(line).append('\n')
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Medições do Coliseu", text.toString()))
            renderer.showNotice("${measurements.size} linhas de medições copiadas. Cole onde quiser enviar.")
        } catch (e: Exception) {
            renderer.showNotice("Não foi possível copiar: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- the body

    private fun modeLabel() = if (fluid) "Andar: fluido" else "Andar: contato"
    private fun bodyText() = "Altura $eyeCm cm · mãos ${reachTenths / 10},${reachTenths % 10}×"

    private fun modeNotice() = if (fluid)
        "Modo fluido: não é preciso tocar em nada. Com a mão abaixo dos olhos, puxe para trás para ir para a frente; suba a mão para trazê-la de volta. As mãos para baixo, de uma vez, pulam."
    else "Modo contato: a mão precisa tocar o chão ou a parede. Encoste e puxe para andar, empurre para pular, puxe a parede para baixo para subir."

    private fun saveBody() {
        settings.edit().putBoolean(KEY_FLUID, fluid).putInt(KEY_EYE_CM, eyeCm).putInt(KEY_REACH, reachTenths).apply()
        modeButton.text = modeLabel(); bodyLabel.text = bodyText()
        applyBody()
    }

    private fun applyBody() {
        val mode = if (fluid) Locomotion.FLUID else Locomotion.CONTACT
        val height = eyeCm / 100f; val reach = reachTenths / 10f
        surface.queueEvent { renderer.setLocomotion(mode); renderer.setBody(height, reach) }
    }

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

    // ---------------------------------------------------------------- the two pictures

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun stereoLabel() = if (stereoOn) "VR: ligado" else "VR: desligado"
    private fun ipdText() = "DIP $ipdMm mm · lente $lensMm mm"

    private fun toggleControls() {
        overlay.visibility = if (overlay.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
    }

    private fun saveStereo() {
        settings.edit().putBoolean(KEY_STEREO, stereoOn).putInt(KEY_IPD, ipdMm).putInt(KEY_LENS, lensMm).apply()
        stereoButton.text = stereoLabel(); ipdLabel.text = ipdText()
        applyStereo()
    }

    /**
     * Width of the screen in metres, which decides where each lens looks at it. Phones report
     * their dots per inch; a value that cannot be true is replaced by a common screen size.
     */
    private fun screenWidthMetres(): Float {
        val metrics = resources.displayMetrics
        val pixels = if (viewWidth > 0) viewWidth else metrics.widthPixels
        val dpi = metrics.xdpi
        val metres = if (dpi > 50f) pixels / dpi * .0254f else 0f
        return if (metres in .09f..(.20f)) metres else .14f
    }

    private fun applyStereo() {
        val on = stereoOn; val ipd = ipdMm / 1000f; val lens = lensMm / 1000f; val width = screenWidthMetres()
        surface.queueEvent { renderer.setStereo(on, ipd, lens, width) }
    }

    /** Volume up looks straight ahead again; volume down shows or hides the controls. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { surface.queueEvent { renderer.recentre() }; return true }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { toggleControls(); return true }
        return super.onKeyDown(keyCode, event)
    }

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
        if (rotationSensor == null) {
            // No gyroscope: the compass and the accelerometer together still tell where the head
            // is turned, only less steadily.
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            compass = rotationSensor != null
        }
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (rotationSensor != null) lookModes.add(LookMode.GYROSCOPE)
        if (accelerometer != null) lookModes.add(LookMode.TILT)
        lookModes.add(LookMode.TOUCH)
        val saved = settings.getString(KEY_LOOK, null)
        lookMode = lookModes.firstOrNull { it.name == saved } ?: lookModes[0]
    }

    private fun lookLabel() = when (lookMode) {
        LookMode.GYROSCOPE -> if (compass) "Olhar: bússola" else "Olhar: giroscópio"
        LookMode.TILT -> "Olhar: inclinar"
        LookMode.TOUCH -> "Olhar: dedo"
    }

    private fun nextLookMode() {
        lookMode = lookModes[(lookModes.indexOf(lookMode) + 1) % lookModes.size]
        settings.edit().putString(KEY_LOOK, lookMode.name).apply()
        lookButton.text = lookLabel()
        if (sensorsOn) { stopSensors(); startSensors() }
        renderer.lookMode = lookMode
        renderer.showNotice(when (lookMode) {
            LookMode.GYROSCOPE -> if (compass) "Sem giroscópio: a bússola acompanha a cabeça, com menos firmeza. Gire a cabeça para olhar em volta."
                else "Olhar com o giroscópio: gire a cabeça. Arrastar o dedo na tela também vira."
            LookMode.TILT -> "Sem giroscópio: olhe para cima e para baixo normalmente; para virar, incline a cabeça para o lado (ou arraste o dedo na tela)."
            LookMode.TOUCH -> "Olhar com o dedo: arraste na tela para olhar em volta."
        })
    }

    private fun startSensors() {
        sensorsOn = true
        sensorLook.ready = false
        gravityNs = 0L
        syncRotation()
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
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                if (lookMode != LookMode.GYROSCOPE || values.size < 3) return
                // Some phones add an accuracy value at the end, which older systems reject.
                val count = min(values.size, 4)
                for (i in 0 until 4) rotationVector[i] = if (i < count) values[i] else 0f
                if (count == 4) SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
                else {
                    // Three values: the fourth is implied. The array handed over must have exactly three.
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, floatArrayOf(values[0], values[1], values[2]))
                }
                Look.headFromRotationMatrix(rotationMatrix, quarterTurns, lookOut)
                if (compass && sensorLook.ready) {
                    // The compass trembles: only its slow part is followed.
                    val dt = if (gravityNs == 0L) 0f else (event.timestamp - gravityNs) / 1e9f
                    val k = if (dt <= 0f || dt > .5f) 1f else dt / (COMPASS_SMOOTHING_SECONDS + dt)
                    sensorLook.yaw += Look.angleDifference(lookOut[0], sensorLook.yaw) * k
                    sensorLook.pitch += (lookOut[1] - sensorLook.pitch) * k
                    sensorLook.roll += Look.angleDifference(lookOut[2], sensorLook.roll) * k
                } else {
                    sensorLook.yaw = lookOut[0]; sensorLook.pitch = lookOut[1]; sensorLook.roll = lookOut[2]
                }
                gravityNs = event.timestamp
                sensorLook.ready = true
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
                lookOut[1] = sensorLook.pitch; lookOut[2] = sensorLook.roll
                Look.tiltFromGravity(gravityX, gravityY, gravityZ, quarterTurns, lookOut)
                sensorLook.pitch = lookOut[1]; sensorLook.roll = lookOut[2]
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
        applyStereo()
        syncRotation()
    }

    /**
     * The screen turns on its side a moment after this screen opens. Whatever was started before
     * that must be told: the camera, so its picture is upright for the tracker, and the sensors,
     * so "up" is the edge of the phone that is up now.
     */
    private fun syncRotation() {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        quarterTurns = rotation
        try { boundAnalysis?.targetRotation = rotation } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        syncRotation()
    }

    /**
     * Focal length of the camera in units of the long side of its picture, from what the phone
     * says about its lens and sensor. It turns the place of a hand in the picture into a
     * direction. Falls back to a typical phone camera.
     */
    private fun cameraFocal(front: Boolean): Float {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != facing) continue
                val lengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: break
                val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: break
                if (lengths.isEmpty() || sensor.width <= 0f) break
                val focal = lengths[0] / sensor.width
                // The first camera on that side is the one CameraX opens.
                return if (focal in .5f..(1.1f)) focal else HandTracker.DEFAULT_FOCAL
            }
        } catch (_: Exception) {}
        return HandTracker.DEFAULT_FOCAL
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
            tracker.focal = cameraFocal(frontCamera)
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
        /** Both pictures together: 640 pixels across for each eye. */
        const val MAX_RENDER_WIDTH = 1280
        // Not the key of the version held in the hand: inside goggles the best way to look is another.
        const val KEY_LOOK = "look_vr"
        const val KEY_STEREO = "stereo"
        const val KEY_IPD = "ipd_mm"
        const val KEY_LENS = "lens_mm"
        const val KEY_FLUID = "locomotion_fluid"
        const val KEY_EYE_CM = "eye_cm"
        const val KEY_REACH = "reach_tenths"
        const val DEFAULT_EYE_CM = 80
        const val MIN_EYE_CM = 50
        const val MAX_EYE_CM = 130
        const val DEFAULT_REACH_TENTHS = 25
        const val MIN_REACH_TENTHS = 12
        const val MAX_REACH_TENTHS = 32
        /** Three minutes of the line of measurements, one a second. */
        const val MAX_MEASUREMENTS = 180
        const val HEAT_EVERY_MS = 5_000L
        const val MIN_IPD_MM = 52
        const val MAX_IPD_MM = 74
        const val MIN_LENS_MM = 28
        const val MAX_LENS_MM = 70
        const val COMPASS_SMOOTHING_SECONDS = .25f
        /** Time over which the accelerometer is averaged to find gravity. */
        const val GRAVITY_SMOOTHING_SECONDS = .12f
    }
}
