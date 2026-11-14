package com.minipots.ar

import android.content.Context
import kotlin.math.roundToInt

/**
 * Where hand tracking runs, CPU or GPU. The choice is made once by measuring on this phone (see
 * [ProcessorChooser]), or by hand, and is remembered between launches and shared by both games.
 *
 * Starting the networks on the GPU can close the app on phones with a faulty driver, and that
 * cannot be caught. So a mark is saved before the GPU is started (synchronously: it has to survive
 * the crash) and removed once the GPU delivers results. A mark found at the next launch means the
 * app died in between, and the GPU is not tried again by itself.
 *
 * All methods must be called on the main thread. [notice] shows a passing message to the player.
 */
class ProcessorSetting(context: Context, private val tracker: HandTracker, private val notice: (String) -> Unit) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** True while the choice is still to be made by measuring. */
    val automatic: Boolean
    /** Something to tell the player at the start, or null. */
    val startNotice: String?

    init {
        var processor = settings.getString(KEY_PROCESSOR, AUTO)
        val gpuClosedTheApp = settings.getBoolean(KEY_GPU_STARTING, false)
        if (gpuClosedTheApp) {
            processor = CPU
            settings.edit().putString(KEY_PROCESSOR, CPU).putBoolean(KEY_GPU_STARTING, false).commit()
        }
        automatic = processor == AUTO
        startNotice = if (gpuClosedTheApp) "Da última vez o app fechou ao usar a GPU. O rastreamento fica na CPU." else null
        if (processor == GPU) startGpu()
    }

    /** The automatic comparison reached a step. Costs in ms per tracked frame, rates in fps. */
    fun measured(action: ProcessorChooser.Action, cpuMs: Float, gpuMs: Float, cpuFps: Float, gpuFps: Float) {
        val cpu = cpuMs.roundToInt(); val gpu = gpuMs.roundToInt()
        when (action) {
            ProcessorChooser.Action.TRY_GPU -> {
                startGpu()
                notice("A CPU leva $cpu ms por quadro da mão. Testando a GPU: a mão fica parada por alguns segundos. Depois, mantenha a mão à vista.")
            }
            ProcessorChooser.Action.KEEP_GPU -> {
                save(GPU)
                notice("Rastreamento na GPU: $gpu ms por quadro, contra $cpu ms na CPU.")
            }
            ProcessorChooser.Action.KEEP_CPU -> {
                tracker.preferGpu = false
                save(CPU)
                // gpuMs is 0 when the GPU was not measured: not needed, or it failed (said elsewhere).
                if (gpuMs > 0f) notice(
                    if (gpuFps < cpuFps * ProcessorChooser.FRAME_RATE_KEPT)
                        "Na GPU o jogo caiu de ${cpuFps.roundToInt()} para ${gpuFps.roundToInt()} fps. O rastreamento fica na CPU ($cpu ms por quadro; na GPU, $gpu ms)."
                    else "A GPU não ajudou neste aparelho: $gpu ms por quadro, contra $cpu ms na CPU. O rastreamento fica na CPU."
                )
            }
            ProcessorChooser.Action.NONE -> {}
        }
    }

    /** The player pressed the CPU ⇄ GPU button. The caller stops the automatic comparison. */
    fun switchByHand() {
        val gpu = !tracker.preferGpu
        if (gpu) startGpu() else tracker.preferGpu = false
        save(if (gpu) GPU else CPU)
        notice(
            if (gpu) "Rastreamento na GPU. Compare os números no canto: se o fps cair, volte para a CPU."
            else "Rastreamento na CPU."
        )
    }

    /** The tracker could not use the GPU and went back to the CPU. */
    fun gpuFailed() {
        save(CPU)
        notice("GPU indisponível neste aparelho. O rastreamento continua na CPU.")
    }

    /** The GPU delivered results: it did not close the app. */
    fun gpuReady() { settings.edit().putBoolean(KEY_GPU_STARTING, false).apply() }

    /**
     * The app is leaving the screen alive. Without this, leaving before the GPU has delivered a
     * result would be taken for a crash at the next launch.
     */
    fun stillAlive() { settings.edit().putBoolean(KEY_GPU_STARTING, false).apply() }

    private fun startGpu() {
        settings.edit().putBoolean(KEY_GPU_STARTING, true).commit()
        tracker.preferGpu = true
    }

    private fun save(processor: String) {
        val edit = settings.edit().putString(KEY_PROCESSOR, processor)
        if (processor == CPU) edit.putBoolean(KEY_GPU_STARTING, false)
        edit.apply()
    }

    private companion object {
        const val KEY_PROCESSOR = "processor"
        const val KEY_GPU_STARTING = "gpu_starting"
        const val AUTO = "auto"
        const val CPU = "cpu"
        const val GPU = "gpu"
    }
}
