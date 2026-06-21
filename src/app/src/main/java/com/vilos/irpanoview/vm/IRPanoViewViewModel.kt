package com.vilos.irpanoview.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vilos.irpanoview.camera.PanoFrameDecoder
import com.vilos.irpanoview.camera.ThermalDisplaySettings
import com.vilos.irpanoview.camera.ThermalFrameView
import com.vilos.irpanoview.camera.hik.HikIrConfigSettings
import com.vilos.irpanoview.camera.hik.HikPreviewSettings
import com.vilos.irpanoview.data.AppSettingsRepository
import com.vilos.irpanoview.model.ConnectionState
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.network.PiConnectionManager
import com.vilos.irpanoview.network.PiControlClient
import com.vilos.irpanoview.network.ThermalStreamReceiver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class IRPanoViewUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val settingsOpen: Boolean = false,
    val palette: ThermalColorPalette = ThermalColorPalette.default(),
    val temporalAverageFrames: Int = HikPreviewSettings.DEFAULT_TEMPORAL_AVERAGE_FRAMES,
    val thermalFloorCelsius: Double = ThermalDisplaySettings.defaultFloorCelsius(),
    val thermalCeilingCelsius: Double = ThermalDisplaySettings.defaultCeilingCelsius(),
    val irEmissivity: Double = HikIrConfigSettings.DEFAULT_EMISSIVITY,
    val irDistanceM: Double = HikIrConfigSettings.DEFAULT_DISTANCE_M,
    val irAmbientCelsius: Double = HikIrConfigSettings.DEFAULT_AMBIENT_C,
    val debugStatsEnabled: Boolean = false,
    val demoMode: Boolean = false,
    val piHost: String = PiConnectionManager.defaultPiHost(),
    val piConnected: Boolean = false,
    val streamFps: Double = 0.0,
    val stitchFps: Double = 0.0,
    val windowMinC: Double = 20.0,
    val windowMaxC: Double = 40.0,
    val cameraHealthSummary: String = "",
    val lastError: String? = null,
)

class IRPanoViewViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettings = AppSettingsRepository(application)
    private val controlClient = PiControlClient()
    private val streamReceiver = ThermalStreamReceiver(viewModelScope)

    val piStatus: StateFlow<PiControlClient.PiStatus> = controlClient.status
    val streamStats: StateFlow<ThermalStreamReceiver.StreamStats> = streamReceiver.stats

    private val settingsOpen = MutableStateFlow(false)
    private val palette = MutableStateFlow(ThermalColorPalette.default())
    private val temporalAverageFrames = MutableStateFlow(HikPreviewSettings.DEFAULT_TEMPORAL_AVERAGE_FRAMES)
    private val thermalFloorCelsius = MutableStateFlow(ThermalDisplaySettings.defaultFloorCelsius())
    private val thermalCeilingCelsius = MutableStateFlow(ThermalDisplaySettings.defaultCeilingCelsius())
    private val irEmissivity = MutableStateFlow(HikIrConfigSettings.DEFAULT_EMISSIVITY)
    private val irDistanceM = MutableStateFlow(HikIrConfigSettings.DEFAULT_DISTANCE_M)
    private val irAmbientCelsius = MutableStateFlow(HikIrConfigSettings.DEFAULT_AMBIENT_C)
    private val debugStatsEnabled = MutableStateFlow(false)
    private val demoMode = MutableStateFlow(false)
    private val piHost = MutableStateFlow(PiConnectionManager.defaultPiHost())
    private val windowRange = MutableStateFlow(20.0 to 40.0)

    private var panoView: ThermalFrameView? = null
    private var demoJob: Job? = null
    private var demoPhase = 0

    private val localUi = combine(
        combine(settingsOpen, palette, temporalAverageFrames, thermalFloorCelsius, thermalCeilingCelsius) {
                s, p, t, f, c ->
            Quint(s, p, t, f, c)
        },
        combine(irEmissivity, irDistanceM, irAmbientCelsius, debugStatsEnabled, demoMode) {
                e, d, a, dbg, demo ->
            IrDemo(e, d, a, dbg, demo)
        },
        combine(windowRange, piHost) { window, host -> window to host },
    ) { quint, irDemo, windowHost ->
        LocalInputs(
            settings = quint.settings,
            pal = quint.palette,
            temporal = quint.temporal,
            floor = quint.floor,
            ceiling = quint.ceiling,
            ems = irDemo.ems,
            dist = irDemo.dist,
            ambient = irDemo.ambient,
            debug = irDemo.debug,
            demo = irDemo.demo,
            window = windowHost.first,
            piHost = windowHost.second,
        )
    }

    val uiState: StateFlow<IRPanoViewUiState> = combine(
        localUi,
        piStatus,
        streamStats,
    ) { local, pi, stream ->
        val health = pi.cameras.joinToString("  ") { cam ->
            "Cam${cam.slot}:${if (cam.streaming) "OK" else "—"}"
        }
        IRPanoViewUiState(
            connection = when {
                local.demo -> ConnectionState.Connected
                pi.connected && stream.connected -> ConnectionState.Connected
                else -> ConnectionState.Disconnected
            },
            settingsOpen = local.settings,
            palette = local.pal,
            temporalAverageFrames = local.temporal,
            thermalFloorCelsius = local.floor,
            thermalCeilingCelsius = local.ceiling,
            irEmissivity = local.ems,
            irDistanceM = local.dist,
            irAmbientCelsius = local.ambient,
            debugStatsEnabled = local.debug,
            demoMode = local.demo,
            piHost = local.piHost,
            piConnected = pi.connected,
            streamFps = stream.fps,
            stitchFps = pi.stitchFps,
            windowMinC = local.window.first,
            windowMaxC = local.window.second,
            cameraHealthSummary = health,
            lastError = pi.lastError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IRPanoViewUiState())

    init {
        viewModelScope.launch {
            temporalAverageFrames.value = appSettings.readTemporalAverageFrames()
        }
        viewModelScope.launch {
            val (floor, ceiling) = appSettings.readThermalDisplayRange()
            thermalFloorCelsius.value = floor
            thermalCeilingCelsius.value = ceiling
            ThermalDisplaySettings.setRange(floor, ceiling)
        }
        viewModelScope.launch {
            val (ems, dist, ambient) = appSettings.readIrConfig()
            HikIrConfigSettings.set(ems, dist, ambient)
            irEmissivity.value = ems
            irDistanceM.value = dist
            irAmbientCelsius.value = ambient
        }
        viewModelScope.launch {
            debugStatsEnabled.value = appSettings.readDebugStatsEnabled()
        }
        viewModelScope.launch {
            piHost.value = appSettings.readPiHost()
        }
        viewModelScope.launch {
            streamReceiver.latestFrame.collect { frame ->
                if (frame == null || demoMode.value) return@collect
                val decoded = PanoFrameDecoder.decodeFlatRawGrid(
                    rawPixels = frame.rawPixels,
                    width = frame.width,
                    height = frame.height,
                    palette = palette.value,
                    floorC = thermalFloorCelsius.value,
                    ceilingC = thermalCeilingCelsius.value,
                )
                windowRange.value = decoded.windowMinC to decoded.windowMaxC
                panoView?.updateBitmap(decoded.bitmap)
            }
        }
    }

    fun attachPanoView(view: ThermalFrameView) {
        panoView = view
    }

    fun onAppResumed() {
        if (demoMode.value) {
            startDemoLoop()
        }
    }

    fun connectPi() {
        val url = PiConnectionManager.wsUrl(piHost.value)
        controlClient.connect(url)
        streamReceiver.start()
        controlClient.requestStatus()
    }

    fun disconnectPi() {
        controlClient.disconnect()
        streamReceiver.stop()
    }

    fun setDemoMode(enabled: Boolean) {
        demoMode.value = enabled
        if (enabled) {
            disconnectPi()
            startDemoLoop()
        } else {
            demoJob?.cancel()
        }
    }

    fun setSettingsOpen(open: Boolean) {
        settingsOpen.value = open
    }

    fun setPalette(p: ThermalColorPalette) {
        palette.value = p
    }

    fun setTemporalAverageFrames(count: Int) {
        viewModelScope.launch {
            appSettings.setTemporalAverageFrames(count)
            temporalAverageFrames.value = count
            controlClient.setTemporalAverage(count)
        }
    }

    fun setThermalDisplayRange(floorC: Double, ceilingC: Double) {
        viewModelScope.launch {
            appSettings.setThermalDisplayRange(floorC, ceilingC)
            thermalFloorCelsius.value = ThermalDisplaySettings.floorCelsius
            thermalCeilingCelsius.value = ThermalDisplaySettings.ceilingCelsius
        }
    }

    fun setIrConfig(emissivity: Double, distanceM: Double, ambientCelsius: Double) {
        viewModelScope.launch {
            appSettings.setIrConfig(emissivity, distanceM, ambientCelsius)
            irEmissivity.value = HikIrConfigSettings.emissivity
            irDistanceM.value = HikIrConfigSettings.distanceM
            irAmbientCelsius.value = HikIrConfigSettings.ambientCelsius
            controlClient.setIrConfig(emissivity, distanceM, ambientCelsius)
        }
    }

    fun setPiHost(host: String) {
        viewModelScope.launch {
            appSettings.setPiHost(host)
            piHost.value = appSettings.readPiHost()
        }
    }

    fun setDebugStatsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setDebugStatsEnabled(enabled)
            debugStatsEnabled.value = enabled
        }
    }

    fun triggerManualNuc() {
        controlClient.triggerNuc()
    }

    private fun startDemoLoop() {
        demoJob?.cancel()
        demoJob = viewModelScope.launch {
            while (isActive && demoMode.value) {
                val raw = PanoFrameDecoder.syntheticDemoFrame(phase = demoPhase++)
                val decoded = PanoFrameDecoder.decodeFlatRawGrid(
                    rawPixels = raw,
                    width = 1024,
                    height = 192,
                    palette = palette.value,
                    floorC = thermalFloorCelsius.value,
                    ceilingC = thermalCeilingCelsius.value,
                )
                windowRange.value = decoded.windowMinC to decoded.windowMaxC
                panoView?.updateBitmap(decoded.bitmap)
                delay(40)
            }
        }
    }

    override fun onCleared() {
        disconnectPi()
        demoJob?.cancel()
        super.onCleared()
    }

    private data class Quint(
        val settings: Boolean,
        val palette: ThermalColorPalette,
        val temporal: Int,
        val floor: Double,
        val ceiling: Double,
    )

    private data class IrDemo(
        val ems: Double,
        val dist: Double,
        val ambient: Double,
        val debug: Boolean,
        val demo: Boolean,
    )

    private data class LocalInputs(
        val settings: Boolean,
        val pal: ThermalColorPalette,
        val temporal: Int,
        val floor: Double,
        val ceiling: Double,
        val ems: Double,
        val dist: Double,
        val ambient: Double,
        val debug: Boolean,
        val demo: Boolean,
        val window: Pair<Double, Double>,
        val piHost: String,
    )
}
