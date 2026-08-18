package com.vilos.irpanoview.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vilos.irpanoview.camera.PanoFrameDecoder
import com.vilos.irpanoview.camera.PreviewU8Blitter
import com.vilos.irpanoview.camera.ThermalDisplaySettings
import com.vilos.irpanoview.camera.ThermalFrameView
import com.vilos.irpanoview.camera.hik.HikIrConfigSettings
import com.vilos.irpanoview.camera.hik.HikPreviewSettings
import com.vilos.irpanoview.data.AppSettingsRepository
import com.vilos.irpanoview.model.ConnectionState
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.network.PiConnectionManager
import com.vilos.irpanoview.network.PiControlClient
import com.vilos.irpanoview.network.PiWifiHelper
import com.vilos.irpanoview.network.PreviewFrameParser
import com.vilos.irpanoview.network.PreviewStreamReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IRPanoViewUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val settingsOpen: Boolean = false,
    val palette: ThermalColorPalette = ThermalColorPalette.default(),
    val temporalAverageFrames: Int = HikPreviewSettings.DEFAULT_TEMPORAL_AVERAGE_FRAMES,
    val thermalFloorCelsius: Double = ThermalDisplaySettings.defaultFloorCelsius(),
    val thermalCeilingCelsius: Double = ThermalDisplaySettings.defaultCeilingCelsius(),
    val displayAutoRange: Boolean = false,
    val equalizationEnabled: Boolean = true,
    val irEmissivity: Double = HikIrConfigSettings.DEFAULT_EMISSIVITY,
    val irDistanceM: Double = HikIrConfigSettings.DEFAULT_DISTANCE_M,
    val irAmbientCelsius: Double = HikIrConfigSettings.DEFAULT_AMBIENT_C,
    val debugStatsEnabled: Boolean = false,
    val demoMode: Boolean = false,
    val piHost: String = PiConnectionManager.defaultPiHost(),
    val wifiSsid: String? = null,
    val statusLine: String = "Starting…",
    val piConnected: Boolean = false,
    val streamFps: Double = 0.0,
    val stitchFps: Double = 0.0,
    val windowMinC: Double = 20.0,
    val windowMaxC: Double = 40.0,
    val cameraHealthSummary: String = "",
    val lastError: String? = null,
)

class IRPanoViewViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val appSettings = AppSettingsRepository(application)
    private val controlClient = PiControlClient()
    private val previewReceiver = PreviewStreamReceiver(viewModelScope)
    private val previewBlitter = PreviewU8Blitter()

    val piStatus: StateFlow<PiControlClient.PiStatus> = controlClient.status
    val streamStats: StateFlow<PreviewStreamReceiver.StreamStats> = previewReceiver.stats

    private val settingsOpen = MutableStateFlow(false)
    private val palette = MutableStateFlow(ThermalColorPalette.default())
    private val temporalAverageFrames = MutableStateFlow(HikPreviewSettings.DEFAULT_TEMPORAL_AVERAGE_FRAMES)
    private val thermalFloorCelsius = MutableStateFlow(ThermalDisplaySettings.defaultFloorCelsius())
    private val thermalCeilingCelsius = MutableStateFlow(ThermalDisplaySettings.defaultCeilingCelsius())
    private val displayAutoRange = MutableStateFlow(false)
    private val equalizationEnabled = MutableStateFlow(true)
    private val irEmissivity = MutableStateFlow(HikIrConfigSettings.DEFAULT_EMISSIVITY)
    private val irDistanceM = MutableStateFlow(HikIrConfigSettings.DEFAULT_DISTANCE_M)
    private val irAmbientCelsius = MutableStateFlow(HikIrConfigSettings.DEFAULT_AMBIENT_C)
    private val debugStatsEnabled = MutableStateFlow(false)
    private val demoMode = MutableStateFlow(false)
    private val piHost = MutableStateFlow(PiConnectionManager.defaultPiHost())
    private val windowRange = MutableStateFlow(20.0 to 40.0)
    private val wifiSsid = MutableStateFlow<String?>(null)
    private val statusLine = MutableStateFlow("Starting…")

    private var panoView: ThermalFrameView? = null
    private var demoJob: Job? = null
    private var sessionJob: Job? = null
    private var demoPhase = 0
    private var lastPreviewFrame: PreviewFrameParser.ParsedFrame? = null
    private var pushedControls = false

    private val localUi = combine(
        combine(settingsOpen, palette, temporalAverageFrames, thermalFloorCelsius, thermalCeilingCelsius) {
                s, p, t, f, c ->
            Quint(s, p, t, f, c)
        },
        combine(irEmissivity, irDistanceM, irAmbientCelsius, debugStatsEnabled, demoMode) {
                e, d, a, dbg, demo ->
            IrDemo(e, d, a, dbg, demo)
        },
        combine(windowRange, piHost, displayAutoRange, equalizationEnabled) { window, host, auto, eq ->
            WindowHost(window, host, auto, eq)
        },
        combine(wifiSsid, statusLine) { ssid, status -> ssid to status },
    ) { quint, irDemo, wh, wifiStatus ->
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
            window = wh.window,
            piHost = wh.piHost,
            autoRange = wh.autoRange,
            equalization = wh.equalization,
            wifiSsid = wifiStatus.first,
            statusLine = wifiStatus.second,
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
            displayAutoRange = local.autoRange,
            equalizationEnabled = local.equalization,
            irEmissivity = local.ems,
            irDistanceM = local.dist,
            irAmbientCelsius = local.ambient,
            debugStatsEnabled = local.debug,
            demoMode = local.demo,
            piHost = local.piHost,
            wifiSsid = local.wifiSsid,
            statusLine = local.statusLine,
            piConnected = pi.connected,
            streamFps = stream.fps,
            stitchFps = pi.compositorHz,
            windowMinC = local.window.first,
            windowMaxC = local.window.second,
            cameraHealthSummary = health,
            lastError = null,
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
            displayAutoRange.value = appSettings.readDisplayAutoRange()
        }
        viewModelScope.launch {
            equalizationEnabled.value = appSettings.readEqualizationEnabled()
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
            val host = appSettings.readPiHost()
            piHost.value = host
            // Persist migration away from irpanoview.local
            appSettings.setPiHost(host)
        }
        viewModelScope.launch {
            previewReceiver.latestFrame
                .filterNotNull()
                .conflate()
                .collect { frame ->
                    if (demoMode.value) return@collect
                    lastPreviewFrame = frame
                    val bmp = withContext(Dispatchers.Default) {
                        previewBlitter.blit(frame.pixels, frame.width, frame.height, palette.value)
                    }
                    windowRange.value = frame.minC.toDouble() to frame.maxC.toDouble()
                    withContext(Dispatchers.Main) {
                        panoView?.updateBitmap(bmp)
                    }
                }
        }
        startAutoSession()
    }

    fun attachPanoView(view: ThermalFrameView) {
        panoView = view
    }

    fun onAppResumed() {
        if (demoMode.value) {
            startDemoLoop()
        } else {
            startAutoSession()
        }
    }

    private fun startAutoSession() {
        if (demoMode.value) return
        synchronized(this) {
            if (sessionJob?.isActive == true) return
            sessionJob = viewModelScope.launch {
                PiWifiHelper.clearProcessNetworkBind(app)
                while (isActive && !demoMode.value) {
                    wifiSsid.value = PiWifiHelper.currentSsid(app)
                    statusLine.value = "Looking for Pi at ${PiConnectionManager.PI_AP_GATEWAY}…"
                    if (!PiWifiHelper.piReachable()) {
                        statusLine.value =
                            "Waiting for Pi at ${PiConnectionManager.PI_AP_GATEWAY}… " +
                                "(on WiFi ${wifiSsid.value ?: PiConnectionManager.PI_WIFI_SSID})"
                        delay(2_000)
                        continue
                    }

                    val host = AppSettingsRepository.canonicalizePiHost(piHost.value).ifBlank {
                        PiConnectionManager.PI_AP_GATEWAY
                    }
                    piHost.value = host

                    statusLine.value = "Connecting to Pi ($host)…"
                    ensureLinked(host)

                    var previewDownTicks = 0
                    while (isActive && !demoMode.value) {
                        wifiSsid.value = PiWifiHelper.currentSsid(app)
                        val piOk = controlClient.status.value.connected
                        val previewOk = previewReceiver.stats.value.connected
                        when {
                            piOk && previewOk -> {
                                previewDownTicks = 0
                                statusLine.value =
                                    "Live · ${"%.0f".format(previewReceiver.stats.value.fps)} fps · " +
                                        "compose ${"%.0f".format(controlClient.status.value.compositorHz)} Hz"
                                if (!pushedControls) {
                                    pushAllControls()
                                    pushedControls = true
                                }
                            }
                            piOk && !previewOk -> {
                                previewDownTicks++
                                statusLine.value = "Reconnecting preview ($host:8769)…"
                                if (previewDownTicks >= 3) {
                                    previewReceiver.start(host)
                                    previewDownTicks = 0
                                }
                            }
                            else -> {
                                previewDownTicks = 0
                                statusLine.value = "Reconnecting…"
                                ensureLinked(host)
                            }
                        }
                        if (!PiWifiHelper.piReachable()) {
                            statusLine.value = "Pi unreachable — waiting for WiFi…"
                            break
                        }
                        delay(1_000)
                    }
                    delay(500)
                }
            }
        }
    }

    private fun ensureLinked(host: String) {
        val url = PiConnectionManager.wsUrl(host)
        android.util.Log.i("IRPanoSession", "ensureLinked host=$host url=$url")
        if (!controlClient.status.value.connected) {
            controlClient.connect(url)
        }
        previewReceiver.start(host)
        controlClient.requestStatus()
    }

    private fun pushAllControls() {
        pushDisplayRangeToPi()
        controlClient.setEqualization(equalizationEnabled.value)
        controlClient.setTemporalAverage(temporalAverageFrames.value)
        controlClient.setIrConfig(
            irEmissivity.value,
            irDistanceM.value,
            irAmbientCelsius.value,
        )
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        controlClient.disconnect()
        previewReceiver.stop()
        lastPreviewFrame = null
        pushedControls = false
    }

    fun setDemoMode(enabled: Boolean) {
        demoMode.value = enabled
        if (enabled) {
            stopSession()
            startDemoLoop()
        } else {
            demoJob?.cancel()
            startAutoSession()
        }
    }

    fun setSettingsOpen(open: Boolean) {
        settingsOpen.value = open
    }

    fun setPalette(p: ThermalColorPalette) {
        palette.value = p
        val frame = lastPreviewFrame ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = previewBlitter.blit(frame.pixels, frame.width, frame.height, p)
            withContext(Dispatchers.Main) {
                panoView?.updateBitmap(bmp)
            }
        }
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
            displayAutoRange.value = false
            appSettings.setDisplayAutoRange(false)
            // Explicit false so Pi leaves auto mode even if StateFlow races.
            controlClient.setDisplayRange(
                floorC = ThermalDisplaySettings.floorCelsius,
                ceilingC = ThermalDisplaySettings.ceilingCelsius,
                auto = false,
            )
        }
    }

    fun setDisplayAutoRange(auto: Boolean) {
        viewModelScope.launch {
            displayAutoRange.value = auto
            appSettings.setDisplayAutoRange(auto)
            controlClient.setDisplayRange(
                floorC = thermalFloorCelsius.value,
                ceilingC = thermalCeilingCelsius.value,
                auto = auto,
            )
        }
    }

    fun setEqualizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            equalizationEnabled.value = enabled
            appSettings.setEqualizationEnabled(enabled)
            controlClient.setEqualization(enabled)
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
            pushedControls = false
            // Force reconnect on new host
            controlClient.disconnect()
            previewReceiver.stop()
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

    private fun pushDisplayRangeToPi() {
        controlClient.setDisplayRange(
            floorC = thermalFloorCelsius.value,
            ceilingC = thermalCeilingCelsius.value,
            auto = displayAutoRange.value,
        )
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
        stopSession()
        demoJob?.cancel()
        previewBlitter.release()
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

    private data class WindowHost(
        val window: Pair<Double, Double>,
        val piHost: String,
        val autoRange: Boolean,
        val equalization: Boolean,
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
        val autoRange: Boolean,
        val equalization: Boolean,
        val wifiSsid: String?,
        val statusLine: String,
    )
}
