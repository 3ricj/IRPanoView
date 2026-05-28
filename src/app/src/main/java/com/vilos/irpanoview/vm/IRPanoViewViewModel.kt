package com.vilos.irpanoview.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vilos.irpanoview.IRPanoViewApplication
import com.vilos.irpanoview.camera.ExternalCameraEnumerator
import com.vilos.irpanoview.camera.ThermalDisplaySettings
import com.vilos.irpanoview.camera.ThermalLevelRegistry
import com.vilos.irpanoview.camera.UvcPreviewRegistry
import com.vilos.irpanoview.camera.hik.HikCameraRegistry
import com.vilos.irpanoview.camera.hik.HikGridDecodeTuning
import com.vilos.irpanoview.camera.hik.HikIrConfigSettings
import com.vilos.irpanoview.camera.hik.HikMultiCamPolicy
import com.vilos.irpanoview.camera.hik.HikProtocolSupport
import com.vilos.irpanoview.util.PreviewSnapshotLogger
import com.vilos.irpanoview.util.UvcDebugLogger
import com.vilos.irpanoview.data.AppSettingsRepository
import com.vilos.irpanoview.data.SlotLayoutRepository
import com.vilos.irpanoview.camera.hik.HikPreviewSettings
import com.vilos.irpanoview.model.CameraIdentity
import com.vilos.irpanoview.model.CameraSlot
import com.vilos.irpanoview.model.ConnectionState
import com.vilos.irpanoview.model.QuadCameraOrder
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.usb.UsbCameraRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IRPanoViewUiState(
    val slots: List<CameraSlot> = emptyList(),
    val focusedStableKey: String? = null,
    val settingsOpen: Boolean = false,
    val palette: ThermalColorPalette = ThermalColorPalette.default(),
    val temporalAverageFrames: Int = HikPreviewSettings.DEFAULT_TEMPORAL_AVERAGE_FRAMES,
    val thermalFloorCelsius: Double = ThermalDisplaySettings.defaultFloorCelsius(),
    val thermalCeilingCelsius: Double = ThermalDisplaySettings.defaultCeilingCelsius(),
    val irEmissivity: Double = HikIrConfigSettings.DEFAULT_EMISSIVITY,
    val irDistanceM: Double = HikIrConfigSettings.DEFAULT_DISTANCE_M,
    val irAmbientCelsius: Double = HikIrConfigSettings.DEFAULT_AMBIENT_C,
    val dynamicCompEnabled: Boolean = HikPreviewSettings.DYNAMIC_COMP_DEFAULT_ENABLED,
    val dynamicCompOverlapColumns: Int = HikPreviewSettings.DYNAMIC_COMP_DEFAULT_OVERLAP_COLUMNS,
    val dynamicCompCenterBandRatio: Double = HikPreviewSettings.DYNAMIC_COMP_DEFAULT_CENTER_BAND_RATIO,
    val debugStatsEnabled: Boolean = false,
    /** 0 = none; UI dev without hardware */
    val demoCameraCount: Int = 0,
    val usbDeviceCount: Int = 0,
)

class IRPanoViewViewModel(application: Application) : AndroidViewModel(application) {

    private val slotRepo = SlotLayoutRepository(application)
    private val appSettings = AppSettingsRepository(application)
    private val usbRegistry: UsbCameraRegistry =
        (application as IRPanoViewApplication).usbCameraRegistry
    private val externalCameras = ExternalCameraEnumerator(application)

    private val demoCount = MutableStateFlow(0)
    private val focusedKey = MutableStateFlow<String?>(null)
    private val settingsOpen = MutableStateFlow(false)
    private val palette = MutableStateFlow(ThermalColorPalette.default())
    private val temporalAverageFrames = MutableStateFlow(HikPreviewSettings.DEFAULT_TEMPORAL_AVERAGE_FRAMES)
    private val thermalFloorCelsius = MutableStateFlow(ThermalDisplaySettings.defaultFloorCelsius())
    private val thermalCeilingCelsius = MutableStateFlow(ThermalDisplaySettings.defaultCeilingCelsius())
    private val irEmissivity = MutableStateFlow(HikIrConfigSettings.DEFAULT_EMISSIVITY)
    private val irDistanceM = MutableStateFlow(HikIrConfigSettings.DEFAULT_DISTANCE_M)
    private val irAmbientCelsius = MutableStateFlow(HikIrConfigSettings.DEFAULT_AMBIENT_C)
    private val dynamicCompEnabled = MutableStateFlow(HikPreviewSettings.DYNAMIC_COMP_DEFAULT_ENABLED)
    private val dynamicCompOverlapColumns = MutableStateFlow(HikPreviewSettings.DYNAMIC_COMP_DEFAULT_OVERLAP_COLUMNS)
    private val dynamicCompCenterBandRatio = MutableStateFlow(HikPreviewSettings.DYNAMIC_COMP_DEFAULT_CENTER_BAND_RATIO)
    private val debugStatsEnabled = MutableStateFlow(false)
    private val preferredCells = MutableStateFlow<Map<String, Int>>(emptyMap())

    init {
        HikGridDecodeTuning.set(0, 0)
        viewModelScope.launch {
            val saved = appSettings.readTemporalAverageFrames()
            temporalAverageFrames.value = saved
            HikPreviewSettings.setTemporalAverageFrames(saved)
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
            irEmissivity.value = HikIrConfigSettings.emissivity
            irDistanceM.value = HikIrConfigSettings.distanceM
            irAmbientCelsius.value = HikIrConfigSettings.ambientCelsius
        }
        viewModelScope.launch {
            val (enabled, overlapColumns, centerBandRatio) = appSettings.readDynamicCompSettings()
            HikPreviewSettings.setDynamicCompEnabled(enabled)
            HikPreviewSettings.setDynamicCompOverlapColumns(overlapColumns)
            HikPreviewSettings.setDynamicCompCenterBandRatio(centerBandRatio)
            dynamicCompEnabled.value = HikPreviewSettings.dynamicCompEnabled
            dynamicCompOverlapColumns.value = HikPreviewSettings.dynamicCompOverlapColumns
            dynamicCompCenterBandRatio.value = HikPreviewSettings.dynamicCompCenterBandRatio
        }
        viewModelScope.launch {
            debugStatsEnabled.value = appSettings.readDebugStatsEnabled()
        }
    }

    private val irConfigUi = combine(
        irEmissivity,
        irDistanceM,
        irAmbientCelsius,
    ) { ems, dist, ambient ->
        Triple(ems, dist, ambient)
    }

    private val dynamicCompUi = combine(
        dynamicCompEnabled,
        dynamicCompOverlapColumns,
        dynamicCompCenterBandRatio,
    ) { enabled, overlapColumns, centerBandRatio ->
        Triple(enabled, overlapColumns, centerBandRatio)
    }

    private val thermalRangeUi = combine(
        thermalFloorCelsius,
        thermalCeilingCelsius,
    ) { floor, ceiling ->
        floor to ceiling
    }

    private val dynamicCompAndDebugUi = combine(
        dynamicCompUi,
        debugStatsEnabled,
    ) { dyn, debugStats ->
        dyn to debugStats
    }

    private val localUi = combine(
        combine(demoCount, focusedKey, settingsOpen, palette, temporalAverageFrames) { demo, focus, settings, pal, avg ->
            PartialUiInputs(demo, focus, settings, pal, avg)
        },
        thermalRangeUi,
        irConfigUi,
        dynamicCompAndDebugUi,
        preferredCells,
    ) { partial, range, ir, dynAndDebug, cells ->
        val dyn = dynAndDebug.first
        val debugStats = dynAndDebug.second
        LocalUiInputs(
            demo = partial.demo,
            focus = partial.focus,
            settings = partial.settings,
            pal = partial.pal,
            temporalAvg = partial.temporalAvg,
            thermalFloor = range.first,
            thermalCeiling = range.second,
            irEmissivity = ir.first,
            irDistanceM = ir.second,
            irAmbientCelsius = ir.third,
            dynamicCompEnabled = dyn.first,
            dynamicCompOverlapColumns = dyn.second,
            dynamicCompCenterBandRatio = dyn.third,
            debugStatsEnabled = debugStats,
            cells = cells,
        )
    }

    val uiState: StateFlow<IRPanoViewUiState> = combine(localUi, usbRegistry.devices) { local, usbList ->
        val usbSlots = buildUsbSlots(usbList, local.cells)
        val demoSlots = buildDemoSlots(local.demo, local.cells)
        val slots = when {
            usbSlots.isNotEmpty() -> usbSlots
            local.demo > 0 -> demoSlots
            else -> emptyList()
        }
        IRPanoViewUiState(
            slots = slots,
            focusedStableKey = local.focus,
            settingsOpen = local.settings,
            palette = local.pal,
            temporalAverageFrames = local.temporalAvg,
            thermalFloorCelsius = local.thermalFloor,
            thermalCeilingCelsius = local.thermalCeiling,
            irEmissivity = local.irEmissivity,
            irDistanceM = local.irDistanceM,
            irAmbientCelsius = local.irAmbientCelsius,
            dynamicCompEnabled = local.dynamicCompEnabled,
            dynamicCompOverlapColumns = local.dynamicCompOverlapColumns,
            dynamicCompCenterBandRatio = local.dynamicCompCenterBandRatio,
            debugStatsEnabled = local.debugStatsEnabled,
            demoCameraCount = local.demo,
            usbDeviceCount = usbList.size,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        IRPanoViewUiState(),
    )

    init {
        viewModelScope.launch {
            uiState.collect { state ->
                val busPath = state.focusedStableKey?.let { key ->
                    state.slots.firstOrNull { it.identity.stableKey == key }?.identity?.busPath
                }
                ThermalLevelRegistry.setFocusedBusPath(busPath)
                if (state.focusedStableKey != null && busPath == null) {
                    focusedKey.value = null
                }
            }
        }
        viewModelScope.launch {
            usbRegistry.devices.collect { list ->
                val activePaths = list.mapNotNull { it.device.deviceName }.toSet()
                val detachedPaths = usbRegistry.consumeDetachedPaths()
                HikCameraRegistry.releaseAbsentFrom(
                    activePaths,
                    getApplication(),
                    detachedPaths,
                )
                list.forEach { state ->
                    val key = CameraIdentity.fromUsb(state.device).stableKey
                    if (key !in preferredCells.value) {
                        val serial = if (state.hasPermission) {
                            CameraIdentity.resolveSerial(state.device, true)
                        } else {
                            null
                        }
                        val canonical = QuadCameraOrder.cellIndex(serial)
                        val saved = slotRepo.readPreferredCell(key)
                        val cell = canonical ?: saved
                        if (cell != null) {
                            preferredCells.update { it + (key to cell) }
                            if (canonical != null && saved != canonical) {
                                slotRepo.setPreferredCell(key, canonical)
                            }
                        }
                    }
                }
            }
        }
    }

    fun onAppResumed() {
        usbRegistry.refresh()
        usbRegistry.requestPermissionsForAll()
        refreshExternalCameras()
    }

    fun refreshUsb() {
        usbRegistry.refresh()
        usbRegistry.requestPermissionsForAll()
        refreshExternalCameras()
    }

    /** Writes PNG snapshots under files/logs/snapshots/ for adb pull. */
    fun saveDebugSnapshots() {
        val ctx = getApplication<Application>()
        UvcPreviewRegistry.activePaths().forEach { path ->
            PreviewSnapshotLogger.requestManualSnapshot(
                ctx,
                path,
                UvcPreviewRegistry.currentBitmap(path),
            )
        }
        UvcDebugLogger.log(ctx, "snapshots", "manual save (${UvcPreviewRegistry.activePaths().size} cameras)")
    }

    private fun refreshExternalCameras() {
        // Triggers combine() via usbRegistry state; external ids read inside buildUsbSlots
    }

    fun setDemoCameraCount(count: Int) {
        viewModelScope.launch {
            val c = count.coerceIn(0, 4)
            if (c == 0) {
                preferredCells.value = preferredCells.value.filterKeys { it.startsWith("demo:") }
                demoCount.value = 0
                return@launch
            }
            val newMap = preferredCells.value.toMutableMap()
            for (i in 0 until c) {
                val key = demoStableKey(i)
                if (key !in newMap) {
                    newMap[key] = slotRepo.readPreferredCell(key) ?: (i % 4)
                }
            }
            preferredCells.value = newMap
            demoCount.value = c
        }
    }

    fun setFocusedStableKey(key: String?) {
        focusedKey.value = key
    }

    fun toggleFocusOn(stableKey: String) {
        focusedKey.update { cur -> if (cur == stableKey) null else stableKey }
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
            temporalAverageFrames.value = HikPreviewSettings.temporalAverageFrames
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
            HikCameraRegistry.applyIrConfigAll()
        }
    }

    fun setDynamicCompEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setDynamicCompEnabled(enabled)
            dynamicCompEnabled.value = HikPreviewSettings.dynamicCompEnabled
        }
    }

    fun setDynamicCompOverlapColumns(columns: Int) {
        viewModelScope.launch {
            appSettings.setDynamicCompOverlapColumns(columns)
            dynamicCompOverlapColumns.value = HikPreviewSettings.dynamicCompOverlapColumns
        }
    }

    fun setDynamicCompCenterBandRatio(ratio: Double) {
        viewModelScope.launch {
            appSettings.setDynamicCompCenterBandRatio(ratio)
            dynamicCompCenterBandRatio.value = HikPreviewSettings.dynamicCompCenterBandRatio
        }
    }

    fun setDebugStatsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.setDebugStatsEnabled(enabled)
            debugStatsEnabled.value = enabled
        }
    }

    /** Manual black reference (NUC) on every active Hik camera stream. */
    fun triggerManualNuc() {
        if (HikPreviewSettings.BLACK_REFERENCE_DESCOPED) return
        val ctx = getApplication<Application>()
        val paths = HikCameraRegistry.activeBusPaths()
        if (paths.isEmpty()) {
            UvcDebugLogger.log(ctx, "settings", "manual NUC skipped — no active camera streams")
            return
        }
        UvcDebugLogger.log(ctx, "settings", "manual NUC for ${paths.size} cameras: ${paths.joinToString()}")
        HikCameraRegistry.requestBlackReferenceAll()
    }

    fun setPreferredCell(stableKey: String, cell: Int) {
        val c = cell.coerceIn(0, 3)
        preferredCells.update { it + (stableKey to c) }
        viewModelScope.launch { slotRepo.setPreferredCell(stableKey, c) }
    }

    private fun buildUsbSlots(
        usbList: List<UsbCameraRegistry.UsbDeviceState>,
        cells: Map<String, Int>,
    ): List<CameraSlot> {
        if (usbList.isEmpty()) return emptyList()

        data class Entry(
            val state: UsbCameraRegistry.UsbDeviceState,
            val identity: CameraIdentity,
            val serial: String?,
        )

        val entries = usbList.map { state ->
            val dev = state.device
            val serial = if (state.hasPermission) {
                CameraIdentity.resolveSerial(dev, true)
            } else {
                CameraIdentity.fromUsb(dev).serial
            }
            val identity = CameraIdentity.fromUsb(dev, serial)
            Entry(state, identity, serial)
        }

        val sorted = entries.sortedWith(
            compareBy<Entry> { QuadCameraOrder.sortKey(it.serial) }
                .thenBy { it.identity.busPath },
        )

        val sortedBusPaths = HikMultiCamPolicy.sortedBusPathsByCameraOrder(
            entries.map { it.identity.busPath to it.serial },
        )
        val externalIds = externalCameras.sortedExternalCameraIds()

        return sorted.mapIndexed { index, entry ->
            val dev = entry.state.device
            val busPath = dev.deviceName ?: ""
            val identity = entry.identity
            val cameraNumber = QuadCameraOrder.cameraNumber(entry.serial)
            val canonicalCell = QuadCameraOrder.cellIndex(entry.serial)
            val cell = canonicalCell ?: cells[identity.stableKey] ?: (index % 4)
            val cameraId = externalIds.getOrNull(index)
            val connected = entry.state.hasPermission && cameraId != null
            val isHik = HikProtocolSupport.isHikThermalDevice(dev.vendorId, dev.productId)
            val hikRank = if (isHik) HikMultiCamPolicy.streamRank(busPath, sortedBusPaths) else null
            val hikEnabled = !isHik || HikMultiCamPolicy.isStreamEnabled(busPath, sortedBusPaths)
            CameraSlot(
                identity = identity,
                connection = if (connected) ConnectionState.Connected else ConnectionState.Disconnected,
                preferredCell = cell,
                externalCameraId = cameraId,
                hasUsbPermission = entry.state.hasPermission,
                hikStreamEnabled = hikEnabled,
                hikStreamRank = hikRank?.takeIf { it >= 0 },
                cameraNumber = cameraNumber,
            )
        }
    }

    private fun buildDemoSlots(count: Int, cells: Map<String, Int>): List<CameraSlot> {
        return (0 until count).map { i ->
            val key = demoStableKey(i)
            val cell = cells[key] ?: (i % 4)
            val path = "/dev/bus/usb/demo/${i + 1}"
            CameraSlot(
                identity = CameraIdentity(
                    stableKey = key,
                    busPath = path,
                    serial = null,
                    vendorId = 0x2BDF,
                    productId = 0x0102,
                    displayLabel = "Demo ${i + 1}",
                ),
                connection = ConnectionState.Connected,
                preferredCell = cell,
                externalCameraId = null,
                hasUsbPermission = true,
                cameraNumber = i + 1,
            )
        }
    }

    private data class PartialUiInputs(
        val demo: Int,
        val focus: String?,
        val settings: Boolean,
        val pal: ThermalColorPalette,
        val temporalAvg: Int,
    )

    private data class LocalUiInputs(
        val demo: Int,
        val focus: String?,
        val settings: Boolean,
        val pal: ThermalColorPalette,
        val temporalAvg: Int,
        val thermalFloor: Double,
        val thermalCeiling: Double,
        val irEmissivity: Double,
        val irDistanceM: Double,
        val irAmbientCelsius: Double,
        val dynamicCompEnabled: Boolean,
        val dynamicCompOverlapColumns: Int,
        val dynamicCompCenterBandRatio: Double,
        val debugStatsEnabled: Boolean,
        val cells: Map<String, Int>,
    )

    companion object {
        fun demoStableKey(index: Int) = "demo:tc001:$index"
    }
}
