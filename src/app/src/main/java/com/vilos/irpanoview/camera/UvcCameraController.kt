package com.vilos.irpanoview.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.energy.iruvc.ircmd.ConcreteIRCMDBuilder
import com.energy.iruvc.ircmd.IRCMD
import com.energy.iruvc.ircmd.IRCMDType
import com.energy.iruvc.uvc.USBUVCCamera
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCParam
import com.serenegiant.usb.UVCCamera
import com.energy.iruvc.ircmd.LibIRCMD
import com.energy.iruvc.ircmd.ResultCode
import com.energy.iruvc.utils.CommonParams
import com.energy.iruvc.utils.OnCreateResultCallback
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.util.PreviewSnapshotLogger
import com.vilos.irpanoview.util.UvcDebugLogger
import com.energy.irutilslibrary.LibIRTemp
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.concurrent.thread

/**
 * UVC stream for TOPDON cameras: connect, negotiate 256×384 YUYV, decode vis+thermal split.
 */
class UvcCameraController(
    context: Context,
    private val device: UsbDevice,
) {
    private val appContext = context.applicationContext
    private val helper: ICameraHelper = CameraHelper()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = device.deviceName ?: "uvc"

    private var started = false
    private var frameWidth = TopdonFrameDecoder.WIDTH
    private var frameHeight = 392
    private var previewHeight = TopdonFrameDecoder.IMAGE_ROWS
    private var palette: ThermalColorPalette = ThermalColorPalette.default()
    private val temperatureModel: TemperatureModel =
        TemperatureModelRegistry.forUsbDevice(device.vendorId, device.productId)
    private val nativeCorrection: Tc001NativeCorrection? =
        Tc001NativeCorrection.maybeCreate(appContext, device.vendorId, device.productId)
    private val nativeCorrectionMapper: ((Int) -> Double)? = nativeCorrection?.let { correction ->
        { native ->
            if (shouldUseNativeCorrection()) {
                correction.nativeToCorrectedCelsius(native)
            } else {
                temperatureModel.nativeLikeToCelsius(native)
            }
        }
    }
    private val dynamicWindowPolicy get() = ThermalDisplaySettings.topdonDynamicWindowPolicy()
    private var pixels = IntArray(TopdonFrameDecoder.WIDTH * TopdonFrameDecoder.IMAGE_ROWS)
    private var outputBitmap: Bitmap? = null
    private val frameBusy = AtomicBoolean(false)
    private var lastDrawMs = 0L
    private var loggedFrameBytes = false
    private var savedFirstSnapshot = false
    private var loggedDecodeStats = false
    private var loggedScalarStats = false
    private var loggedModelWarning = false
    private var lastDiagMs = 0L
    private var loggedContractViolation = false
    private val startupThreadLaunched = AtomicBoolean(false)
    @Volatile private var startupStage = VendorStartupStage.IDLE
    @Volatile private var startupFailure: String? = null
    @Volatile private var tpdMutationValidated = false
    @Volatile private var correctionGateReady = false
    private val useVendorOwnedPath = isTopdonVendorDevice(device.vendorId, device.productId)
    private var vendorHandle: Long = 0L
    private var vendorConnection: UsbDeviceConnection? = null
    private var vendorIrcmd: IRCMD? = null

    var onFrameBitmap: ((Bitmap) -> Unit)? = null
    var onStatus: ((String?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val frameCallback = IFrameCallback { frame ->
        processFrame(frame)
    }

    private val vendorFrameCallback = object : com.energy.iruvc.utils.IFrameCallback {
        override fun onFrame(frame: ByteArray) {
            if (frame.isNotEmpty()) {
                processFrame(ByteBuffer.wrap(frame))
            }
        }
    }

    private fun processFrame(frame: ByteBuffer) {
        if (!frameBusy.compareAndSet(false, true)) return
        try {
            if (!loggedFrameBytes) {
                loggedFrameBytes = true
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "First frame bytes=${frame.remaining()} expect=${TopdonFrameDecoder.expectedBytes(frameWidth, frameHeight)} " +
                        "size=${frameWidth}x$frameHeight",
                )
                PreviewSnapshotLogger.onRawFrame(appContext, tag, frame, frameWidth, frameHeight)
            }
            val now = System.currentTimeMillis()
            if (now - lastDrawMs < MIN_FRAME_MS) return
            if (useVendorOwnedPath && !TopdonFrameDecoder.isStrictTc001Contract(frameWidth, frameHeight)) {
                if (!loggedContractViolation) {
                    loggedContractViolation = true
                    UvcDebugLogger.log(
                        appContext,
                        tag,
                        "STRICT CONTRACT VIOLATION frame=${frameWidth}x$frameHeight expected=256x384|256x392",
                    )
                    mainHandler.post {
                        onStatus?.invoke("Strict parity blocked: unexpected frame contract ${frameWidth}x$frameHeight")
                    }
                }
                return
            }
            if (!loggedDecodeStats) {
                loggedDecodeStats = true
                runCatching { LibIRTemp.get_side_length() }
                    .onSuccess {
                        UvcDebugLogger.log(
                            appContext,
                            tag,
                            "vendor libadvirtemp loaded; sideLength=$it",
                        )
                    }
                    .onFailure {
                        UvcDebugLogger.log(
                            appContext,
                            tag,
                            "vendor libadvirtemp load/check failed: ${it.message}",
                        )
                    }
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "encoding analysis: ${TopdonFrameDecoder.analyzeEncodings(frame, frameWidth, frameHeight)}",
                )
                runCatching { probeVendorPointTemp(frame) }
                    .onSuccess { probe ->
                        UvcDebugLogger.log(
                            appContext,
                            tag,
                            "vendor point probe centerRaw=${probe.first} pointNative=${probe.second} pointC=${"%.2f".format(probe.third)}",
                        )
                    }
                    .onFailure {
                        UvcDebugLogger.log(
                            appContext,
                            tag,
                            "vendor point probe failed: ${it.message}",
                        )
                    }
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "nativeCorrection=${if (nativeCorrection != null) "enabled" else "disabled"}",
                )
                if (temperatureModel.isDebugFallback && !loggedModelWarning) {
                    loggedModelWarning = true
                    UvcDebugLogger.log(
                        appContext,
                        tag,
                        "WARNING: debug fallback temperature model active: ${temperatureModel.name}",
                    )
                }
            }
            val framePos = frame.position()
            val localRange = TopdonFrameDecoder.scanThermalScalarRange(frame, frameWidth, frameHeight)
                ?: return
            ThermalLevelRegistry.report(tag, localRange.first, localRange.second)
            val localWindowScan = TopdonFrameDecoder.scanThermalCelsiusWindowDetailed(
                frame = frame,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                temperatureModel = temperatureModel,
                policy = dynamicWindowPolicy,
                nativeToCelsiusOverride = nativeCorrectionMapper,
            )
            val localWindow = localWindowScan.window ?: TopdonFrameDecoder.CelsiusWindow(
                minCelsius = dynamicWindowPolicy.hardMinCelsius,
                maxCelsius = dynamicWindowPolicy.hardMaxCelsius,
            )
            ThermalLevelRegistry.reportWindow(tag, localWindow.minCelsius, localWindow.maxCelsius)
            val displayWindow = ThermalLevelRegistry.displayWindowFor(tag)
            frame.position(framePos)
            val stats = TopdonFrameDecoder.decodeThermalPreview(
                frame,
                frameWidth,
                frameHeight,
                palette,
                displayWindow,
                pixels,
                temperatureModel,
                nativeToCelsiusOverride = nativeCorrectionMapper,
            )
            if (stats == null) return
            if (!loggedScalarStats) {
                loggedScalarStats = true
                val global = ThermalLevelRegistry.snapshot()
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "decode thermal ${frameWidth}x${stats.previewRows} local=${stats.scalarMin}..${stats.scalarMax} " +
                        "(eff${stats.effectiveBits}) global=${global.displayMin}..${global.displayMax} " +
                        "windowC=${"%.1f".format(stats.displayWindowMinCelsius)}..${"%.1f".format(stats.displayWindowMaxCelsius)} " +
                        "rectC=${"%.1f".format(stats.rectMinCelsius)}..${"%.1f".format(stats.rectMaxCelsius)} avg=${"%.1f".format(stats.rectAvgCelsius)} " +
                        "rectMinXY=${stats.rectMinX},${stats.rectMinY} rectMaxXY=${stats.rectMaxX},${stats.rectMaxY} " +
                        "cams=${global.perCamera.size} centerRaw=${stats.centerRaw} " +
                        "centerC=${"%.1f".format(stats.centerCelsius)}°C " +
                        "tempModel=${stats.centerCelsiusMethod}",
                )
            }
            if (now - lastDiagMs >= DIAG_LOG_INTERVAL_MS) {
                lastDiagMs = now
                val clippedHighPct = if (stats.totalSamples > 0) {
                    (stats.clippedHighCount * 100.0) / stats.totalSamples
                } else {
                    0.0
                }
                val clippedLowPct = if (stats.totalSamples > 0) {
                    (stats.clippedLowCount * 100.0) / stats.totalSamples
                } else {
                    0.0
                }
                val centerUncorrected = temperatureModel.nativeLikeToCelsius(stats.centerPointNative)
                val correctionDelta = stats.centerCelsius - centerUncorrected
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "diag frame=${frameWidth}x$frameHeight rows=${TopdonFrameDecoder.thermalImageRowRange(frameHeight)} " +
                        "roi=0,${TopdonFrameDecoder.thermalImageRowRange(frameHeight).first}.." +
                        "${frameWidth - 1},${TopdonFrameDecoder.thermalImageRowRange(frameHeight).last} " +
                        "inRange=${localWindowScan.inRangeCount}/${localWindowScan.totalSamples} " +
                        "inRangeC=${fmt(localWindowScan.minInRangeCelsius)}..${fmt(localWindowScan.maxInRangeCelsius)} " +
                        "displayC=${fmt(stats.displayWindowMinCelsius)}..${fmt(stats.displayWindowMaxCelsius)} " +
                        "centerRaw=${stats.centerRaw} centerNative=${stats.centerPointNative} centerC=${"%.2f".format(stats.centerCelsius)} " +
                        "rectNative=${stats.rectMinNative}..${stats.rectMaxNative} avg=${stats.rectAvgNative} " +
                        "rectC=${"%.2f".format(stats.rectMinCelsius)}..${"%.2f".format(stats.rectMaxCelsius)} avg=${"%.2f".format(stats.rectAvgCelsius)} " +
                        "lineNative=${stats.lineMinNative}..${stats.lineMaxNative} avg=${stats.lineAvgNative} " +
                        "lineC=${"%.2f".format(stats.lineMinCelsius)}..${"%.2f".format(stats.lineMaxCelsius)} avg=${"%.2f".format(stats.lineAvgCelsius)} " +
                        "lineMinXY=${stats.lineMinX},${stats.lineMinY} lineMaxXY=${stats.lineMaxX},${stats.lineMaxY} " +
                        "centerBaseC=${"%.2f".format(centerUncorrected)} correctionDeltaC=${"%.2f".format(correctionDelta)} " +
                        "clipLow=${"%.1f".format(clippedLowPct)}% clipHigh=${"%.1f".format(clippedHighPct)}% " +
                        "startupStage=${startupStage.name} tpdValidated=$tpdMutationValidated correctionReady=$correctionGateReady " +
                        "model=${stats.centerCelsiusMethod}",
                )
            }
            lastDrawMs = now
            val bmp = outputBitmap ?: Bitmap.createBitmap(
                frameWidth,
                previewHeight,
                Bitmap.Config.ARGB_8888,
            ).also { outputBitmap = it }
            bmp.setPixels(pixels, 0, frameWidth, 0, 0, frameWidth, previewHeight)
            val snapReason = if (!savedFirstSnapshot) {
                savedFirstSnapshot = true
                PreviewSnapshotLogger.REASON_FIRST
            } else {
                PreviewSnapshotLogger.REASON_PERIODIC
            }
            PreviewSnapshotLogger.onDecodedFrame(appContext, tag, bmp, snapReason)
            mainHandler.post { onFrameBitmap?.invoke(bmp) }
        } finally {
            frameBusy.set(false)
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(dev: UsbDevice) = Unit

        override fun onDeviceOpen(dev: UsbDevice, isFirstOpen: Boolean) {
            if (dev.deviceName != device.deviceName) return
            UvcDebugLogger.logDevice(appContext, tag, dev.vendorId, dev.productId)
            UvcDebugLogger.log(appContext, tag, "onDeviceOpen firstOpen=$isFirstOpen — openCamera(default) to query formats")
            onStatus?.invoke("Opening UVC…")
            val quirks = UVCCamera.getRecommendedPlatformQuirks()
            UvcDebugLogger.log(appContext, tag, "quirks=0x${"%X".format(quirks)}")
            runVendorSessionProbe("preOpen", queryTpd = false)
            helper.openCamera(UVCParam(null, quirks))
        }

        override fun onCameraOpen(dev: UsbDevice) {
            if (dev.deviceName != device.deviceName) return
            val supported = helper.getSupportedSizeList().orEmpty()
            val formats = runCatching { helper.getSupportedFormatList()?.size ?: 0 }.getOrElse { 0 }
            val current = helper.getPreviewSize()
            UvcDebugLogger.log(
                appContext,
                tag,
                "onCameraOpen formats=$formats sizes=${supported.size} current=$current\n" +
                    supported.joinToString("\n") { "  $it" },
            )

            val target = pickTopdonSize(supported)
            val negotiated = when {
                target != null -> {
                    UvcDebugLogger.log(appContext, tag, "setPreviewSize → $target")
                    if (current != null && current.width == target.width && current.height == target.height &&
                        current.type == target.type
                    ) {
                        target
                    } else {
                        runCatching {
                            helper.stopPreview()
                            helper.setPreviewSize(target)
                            target
                        }.getOrElse { e ->
                            UvcDebugLogger.log(appContext, tag, "setPreviewSize failed: ${e.message}")
                            current ?: target
                        }
                    }
                }
                current != null -> {
                    UvcDebugLogger.log(appContext, tag, "No 256×384 match; using camera default $current")
                    current
                }
                else -> {
                    UvcDebugLogger.log(appContext, tag, "No sizes and no current preview")
                    null
                }
            }

            if (negotiated == null) {
                mainHandler.post {
                    onError?.invoke("UVC: no preview size (see logs)")
                }
                return
            }

            applyFrameGeometry(negotiated.width, negotiated.height)
            val typeName = frameTypeName(negotiated.type)
            UvcDebugLogger.log(
                appContext,
                tag,
                "Streaming ${negotiated.width}×${negotiated.height} $typeName tempModel=${temperatureModel.name} " +
                    "windowPolicy=${dynamicWindowPolicy.hardMinCelsius}..${dynamicWindowPolicy.hardMaxCelsius}C " +
                    "p=${dynamicWindowPolicy.lowerPercentile}..${dynamicWindowPolicy.upperPercentile}",
            )
            helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RAW)
            helper.startPreview()
            mainHandler.post { onStatus?.invoke(null) }
        }

        override fun onCameraClose(dev: UsbDevice) = Unit
        override fun onDeviceClose(dev: UsbDevice) = Unit
        override fun onDetach(dev: UsbDevice) = Unit
        override fun onCancel(dev: UsbDevice) {
            if (dev.deviceName == device.deviceName) {
                UvcDebugLogger.log(appContext, tag, "onCancel permission denied")
                mainHandler.post { onError?.invoke("USB permission denied") }
            }
        }

        override fun onError(dev: UsbDevice, e: com.herohan.uvcapp.CameraException) {
            if (dev.deviceName == device.deviceName) {
                UvcDebugLogger.log(appContext, tag, "onError code=${e.code} ${e.message}")
            }
        }
    }

    private fun applyFrameGeometry(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        previewHeight = TopdonFrameDecoder.previewHeight(height)
        val pixelCount = width * previewHeight
        if (pixels.size < pixelCount) pixels = IntArray(pixelCount)
        outputBitmap?.recycle()
        outputBitmap = Bitmap.createBitmap(width, previewHeight, Bitmap.Config.ARGB_8888)
        loggedFrameBytes = false
        savedFirstSnapshot = false
        loggedDecodeStats = false
        loggedScalarStats = false
        PreviewSnapshotLogger.resetCamera(tag)
    }

    fun setPalette(p: ThermalColorPalette) {
        palette = p
    }

    fun start() {
        if (started) return
        started = true
        UvcDebugLogger.log(appContext, tag, "start() selectDevice")
        startupThreadLaunched.set(false)
        startupFailure = null
        tpdMutationValidated = false
        correctionGateReady = !useVendorOwnedPath
        setStartupStage(VendorStartupStage.IDLE)
        UvcPreviewRegistry.register(tag) { outputBitmap }
        onStatus?.invoke("Requesting UVC…")
        if (useVendorOwnedPath) {
            startVendorOwned()
        } else {
            helper.setStateCallback(stateCallback)
            helper.selectDevice(device)
        }
    }

    fun release() {
        if (!started) return
        started = false
        UvcPreviewRegistry.unregister(tag)
        ThermalLevelRegistry.unregister(tag)
        UvcDebugLogger.log(appContext, tag, "release()")
        setStartupStage(VendorStartupStage.RELEASED)
        if (useVendorOwnedPath) {
            stopVendorOwned()
        } else {
            helper.setFrameCallback(null, 0)
            helper.release()
        }
        outputBitmap?.recycle()
        outputBitmap = null
        onFrameBitmap = null
        onStatus = null
        onError = null
    }

    private fun startVendorOwned() {
        mainHandler.post { onStatus?.invoke("Opening UVC…") }
        val manager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (manager == null) {
            onError?.invoke("USB manager unavailable")
            return
        }
        val connection = manager.openDevice(device)
        if (connection == null) {
            onError?.invoke("Vendor path: openDevice failed")
            return
        }
        vendorConnection = connection
        val fd = connection.fileDescriptor
        if (fd <= 0) {
            onError?.invoke("Vendor path: invalid fd=$fd")
            stopVendorOwned()
            return
        }
        val (busNum, devNum) = parseBusDev(device.deviceName)
        val handle = runCatching { USBUVCCamera.nativeCreate() }.getOrDefault(0L)
        if (handle == 0L) {
            onError?.invoke("Vendor path: nativeCreate failed")
            stopVendorOwned()
            return
        }
        vendorHandle = handle
        setStartupStage(VendorStartupStage.CONNECTING)
        val connectRc = callNativeWithTimeout("nativeConnect") {
            USBUVCCamera.nativeConnect(
                handle,
                device.vendorId,
                device.productId,
                fd,
                busNum,
                devNum,
                "/dev/bus/usb",
            )
        }
        UvcDebugLogger.log(appContext, tag, "Vendor owned connectRc=$connectRc handle=$handle bus=$busNum dev=$devNum")
        if (connectRc != 0) {
            onError?.invoke("Vendor path: connect failed rc=$connectRc")
            stopVendorOwned()
            return
        }
        val setSizeRc = callNativeWithTimeout("nativeSetPreviewSize") {
            USBUVCCamera.nativeSetPreviewSize(
                handle,
                frameWidth,
                frameHeight,
                VENDOR_MIN_FPS,
                VENDOR_MAX_FPS,
                VENDOR_FRAME_FORMAT_YUYV,
                VENDOR_BANDWIDTH,
            )
        }
        UvcDebugLogger.log(appContext, tag, "Vendor owned setPreviewSize rc=$setSizeRc ${frameWidth}x$frameHeight")
        if (setSizeRc != 0) {
            onError?.invoke("Vendor path: setPreviewSize failed rc=$setSizeRc")
            stopVendorOwned()
            return
        }
        applyFrameGeometry(frameWidth, frameHeight)
        initVendorIrcmd(handle)
        val readiness = prepareVendorStartupReadiness(handle)

        setStartupStage(VendorStartupStage.PREVIEW_STARTING)
        val cbRc = callNativeWithTimeout("nativeSetFrameCallback") {
            USBUVCCamera.nativeSetFrameCallback(handle, vendorFrameCallback)
        }
        val startRc = callNativeWithTimeout("nativeStartPreview") {
            USBUVCCamera.nativeStartPreview(handle)
        }
        UvcDebugLogger.log(
            appContext,
            tag,
            "Vendor owned setFrameCallback rc=$cbRc startPreview rc=$startRc readiness=${readiness.message}",
        )
        if (cbRc != 0) {
            onError?.invoke("Vendor path: setFrameCallback failed rc=$cbRc")
            stopVendorOwned()
            return
        }
        if (startRc != 0) {
            onError?.invoke("Vendor path: startPreview failed rc=$startRc")
            stopVendorOwned()
            return
        }
        if (readiness.ready) {
            setStartupStage(VendorStartupStage.RUNNING)
            UvcDebugLogger.log(
                appContext,
                tag,
                "STARTUP complete stage=${startupStage.name} tpdValidated=$tpdMutationValidated",
            )
            mainHandler.post { onStatus?.invoke(null) }
        }
    }

    private fun stopVendorOwned() {
        val handle = vendorHandle
        vendorHandle = 0L
        vendorIrcmd = null
        if (handle != 0L) {
            runCatching { USBUVCCamera.nativeSetFrameCallback(handle, null) }
            runCatching { USBUVCCamera.nativeStopPreview(handle) }
            runCatching { USBUVCCamera.nativeRelease(handle) }
            runCatching { USBUVCCamera.nativeDestroy(handle) }
        }
        runCatching { vendorConnection?.close() }
        vendorConnection = null
    }

    private fun prepareVendorStartupReadiness(handle: Long): StartupReadiness {
        if (!startupThreadLaunched.compareAndSet(false, true)) {
            return StartupReadiness(ready = false, message = "startup guard already used")
        }
        val ircmd = vendorIrcmd
        if (handle == 0L || ircmd == null) {
            markStartupFailure("IRCMD unavailable for startup pipeline")
            return StartupReadiness(ready = false, message = "ircmd unavailable")
        }
        if (ENABLE_IRCMD_WARMUP) {
            setStartupStage(VendorStartupStage.IRCMD_WARMUP)
            warmupIrcmdSession(ircmd, handle)
        } else {
            UvcDebugLogger.log(appContext, tag, "IRCMD warmup skipped by config")
        }
        val handshakeRc = callIrcmdWithTimeout("libircmd.hand_shake_preview", handle) {
            LibIRCMD.hand_shake_preview(handle)
        }
        UvcDebugLogger.log(appContext, tag, "IRCMD hand_shake_preview rc=$handshakeRc handle=$handle")
        if (handshakeRc == Int.MIN_VALUE) {
            correctionGateReady = false
            markStartupFailure("IRCMD hand_shake_preview timeout")
            return StartupReadiness(ready = false, message = "handshake timeout")
        }

        setStartupStage(VendorStartupStage.WIRE_INIT)
        if (!applyWireInitHooks(handle)) {
            correctionGateReady = false
            markStartupFailure("TC001 wire-init hooks failed")
            return StartupReadiness(ready = false, message = "wire init failed")
        }

        setStartupStage(VendorStartupStage.APPLY_VENDOR_CONFIG)
        runCatching { applyVendorStartupConfig(ircmd, handle) }
            .onFailure {
                correctionGateReady = false
                markStartupFailure("Vendor startup config failed: ${it.message}")
                return StartupReadiness(ready = false, message = "startup config failed")
            }
        setStartupStage(VendorStartupStage.TPD_VALIDATION)
        val report = runTpdMutationValidation(ircmd, handle)
        tpdMutationValidated = report.validated
        val snapshot = readTpdSnapshot(ircmd, handle)
        val correctionSynced = nativeCorrection?.updateFromTpdSnapshot(
            gain = snapshot.gain,
            ems = snapshot.ems,
            tau = snapshot.tau,
            ta = snapshot.ta,
            tu = snapshot.tu,
            distance = snapshot.distance,
        ) ?: true
        correctionGateReady = report.validated && correctionSynced
        UvcDebugLogger.log(
            appContext,
            tag,
            "STARTUP correctionGate ready=$correctionGateReady tpdValidated=${report.validated} snapshot={${snapshot.summary()}}",
        )
        if (!report.validated) {
            markStartupFailure(
                "TPD validation failed mutated=${report.mutatedFields} " +
                    "allPinned8=${report.allFieldsPinnedEight} baseline={${report.baseline}}",
            )
            return StartupReadiness(ready = false, message = "tpd validation failed")
        }
        if (!correctionSynced) {
            markStartupFailure("Correction gate rejected invalid TPD snapshot")
            return StartupReadiness(ready = false, message = "correction sync failed")
        }
        return StartupReadiness(ready = true, message = "ready")
    }

    private fun applyWireInitHooks(handle: Long): Boolean {
        if (!ENABLE_TC001_WIRE_INIT_HOOKS) {
            UvcDebugLogger.log(appContext, tag, "WIRE init hooks skipped by config")
            return true
        }
        val emitRc = callNativeWithTimeout("nativeSetObjectEmitRate") {
            USBUVCCamera.nativeSetObjectEmitRate(handle, APK_CONFIG_DEFAULT_EMS)
        }
        val distRc = callNativeWithTimeout("nativeSetObjectDistance") {
            USBUVCCamera.nativeSetObjectDistance(handle, (APK_CONFIG_DEFAULT_DISTANCE_M * 100.0f).toInt())
        }
        val ambientRc = callNativeWithTimeout("nativeSetEnverionmentTemperature") {
            USBUVCCamera.nativeSetEnverionmentTemperature(handle, 25.0f)
        }
        val reflectionRc = callNativeWithTimeout("nativeSetReflectionTemperature") {
            USBUVCCamera.nativeSetReflectionTemperature(handle, 25.0f)
        }
        val envRc = callNativeWithTimeout("nativeSetEnvCorrectParams") {
            USBUVCCamera.nativeSetEnvCorrectParams(
                handle,
                (APK_CONFIG_DEFAULT_EMS * 1000.0f).toInt(),
                2500,
                2500,
                (APK_CONFIG_DEFAULT_DISTANCE_M * 100.0f).toInt(),
            )
        }
        val xformRc = callNativeWithTimeout("nativeSetTransformParameter") {
            USBUVCCamera.nativeSetTransformParameter(handle, 0)
        }
        val ok = listOf(emitRc, distRc, ambientRc, reflectionRc, envRc, xformRc).all { it == 0 }
        UvcDebugLogger.log(
            appContext,
            tag,
            "WIRE init hooks rc emit=$emitRc dist=$distRc ambient=$ambientRc reflection=$reflectionRc env=$envRc xform=$xformRc",
        )
        return ok
    }

    private fun initVendorIrcmd(handle: Long) {
        vendorIrcmd = ConcreteIRCMDBuilder(true)
            .setIrcmdType(IRCMDType.USB_IR_256_384)
            .setIdCamera(handle)
            .setCreateResultCallback(object : OnCreateResultCallback {
                override fun onInitResult(resultCode: ResultCode) {
                    UvcDebugLogger.log(appContext, tag, "IRCMD object init result=$resultCode")
                }
            })
            .build()
    }

    private fun warmupIrcmdSession(ircmd: IRCMD, handle: Long) {
        val stopPreviewRc = callIrcmdWithTimeout("ircmd.stopPreview", handle) {
            ircmd.stopPreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0)
        }
        Thread.sleep(IRCMD_RESTART_DELAY_MS)
        var startPreviewRc = callIrcmdWithTimeout("ircmd.startPreview", handle) {
            ircmd.startPreview(
                CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                CommonParams.StartPreviewSource.SOURCE_SENSOR,
                25,
                CommonParams.StartPreviewMode.VOC_DVP_MODE,
                CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT,
            )
        }
        if (startPreviewRc != 0) {
            Thread.sleep(IRCMD_RETRY_DELAY_MS)
            startPreviewRc = callIrcmdWithTimeout("ircmd.startPreview.retry", handle) {
                ircmd.startPreview(
                    CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                    CommonParams.StartPreviewSource.SOURCE_SENSOR,
                    25,
                    CommonParams.StartPreviewMode.VOC_DVP_MODE,
                    CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT,
                )
            }
        }
        Thread.sleep(IRCMD_WARMUP_SETTLE_MS)
        UvcDebugLogger.log(
            appContext,
            tag,
            "IRCMD warmup handle=$handle ircmdStopPreviewRc=$stopPreviewRc ircmdStartPreviewRc=$startPreviewRc",
        )
    }

    private fun callIrcmdWithTimeout(name: String, handle: Long, call: () -> Int): Int {
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "ircmd-$name") {
            try {
                result.set(runCatching(call).getOrDefault(Int.MIN_VALUE))
            } finally {
                done.countDown()
            }
        }
        val completed = done.await(IRCMD_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            UvcDebugLogger.log(appContext, tag, "IRCMD $name timeout handle=$handle")
            return Int.MIN_VALUE
        }
        return result.get()
    }

    private fun getTpdParamIrcmd(param: CommonParams.PropTPDParams, ircmd: IRCMD, handle: Long): Int {
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "tpd-ircmd-${param.name}") {
            try {
                val out = intArrayOf(0)
                val rc = runCatching { ircmd.getPropTPDParams(param, out) }.getOrDefault(-9999)
                result.set(if (rc == 0) out[0] else Int.MIN_VALUE)
            } finally {
                done.countDown()
            }
        }
        val completed = done.await(IRCMD_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            UvcDebugLogger.log(appContext, tag, "TPD IRCMD timeout param=${param.name} handle=$handle")
            return Int.MIN_VALUE
        }
        return result.get()
    }

    private fun setTpdParamIrcmd(param: CommonParams.PropTPDParams, value: Int, ircmd: IRCMD, handle: Long): Int {
        return callIrcmdWithTimeout("setTPD.${param.name}", handle) {
            ircmd.setPropTPDParams(param, value)
        }
    }

    private fun setImageParamIrcmd(
        param: CommonParams.PropImageParams,
        value: CommonParams.PropImageParamsValue,
        ircmd: IRCMD,
        handle: Long,
    ): Int {
        return callIrcmdWithTimeout("setImage.${param.name}", handle) {
            ircmd.setPropImageParams(param, value)
        }
    }

    private fun setAutoShutterIrcmd(
        value: CommonParams.PropAutoShutterParameterValue.StatusSwith,
        ircmd: IRCMD,
        handle: Long,
    ): Int {
        return callIrcmdWithTimeout("setAutoShutter", handle) {
            ircmd.setPropAutoShutterParameter(
                CommonParams.PropAutoShutterParameter.SHUTTER_PROP_SWITCH,
                value,
            )
        }
    }

    private fun zoomCenterDownIrcmd(
        ircmd: IRCMD,
        handle: Long,
        count: Int,
        delayMs: Long,
    ): IntArray {
        val rcs = IntArray(count) { Int.MIN_VALUE }
        for (i in 0 until count) {
            rcs[i] = callIrcmdWithTimeout("zoomCenterDown.$i", handle) {
                ircmd.zoomCenterDown(
                    CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                    CommonParams.ZoomScaleStep.ZOOM_STEP2,
                )
            }
            Thread.sleep(delayMs)
        }
        return rcs
    }

    private fun snapshotTpdIrcmd(ircmd: IRCMD, handle: Long): String {
        val gain = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, ircmd, handle)
        val ems = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_EMS, ircmd, handle)
        val tau = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_TAU, ircmd, handle)
        val ta = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_TA, ircmd, handle)
        val tu = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_TU, ircmd, handle)
        val distance = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_DISTANCE, ircmd, handle)
        return "gain=$gain ems=$ems tau=$tau ta=$ta tu=$tu distance=$distance"
    }

    private fun applyVendorStartupConfig(ircmd: IRCMD, handle: Long) {
        // Production startup config mirrors reference implementation APK configParam sequence.
        UvcDebugLogger.log(appContext, tag, "STARTUP applyVendorStartupConfig begin handle=$handle")
        val emsRaw = (APK_CONFIG_DEFAULT_EMS * APK_CONFIG_SCALE_128).toInt()
        val distanceRaw = (APK_CONFIG_DEFAULT_DISTANCE_M * APK_CONFIG_SCALE_128).toInt()
        val gainRaw = APK_CONFIG_DEFAULT_GAIN_SEL

        val setEmsRc = setTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_EMS, emsRaw, ircmd, handle)
        Thread.sleep(IRCMD_SETTLE_AFTER_WRITE_MS)
        val setDistanceRc = setTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_DISTANCE, distanceRaw, ircmd, handle)
        Thread.sleep(IRCMD_SETTLE_AFTER_WRITE_MS)
        val setGainRc = setTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, gainRaw, ircmd, handle)
        Thread.sleep(IRCMD_SETTLE_AFTER_WRITE_MS)
        val coreRcs = listOf(setEmsRc, setDistanceRc, setGainRc)
        if (coreRcs.any { it == Int.MIN_VALUE }) {
            throw IllegalStateException(
                "IRCMD core config timeout ems=$setEmsRc distance=$setDistanceRc gain=$setGainRc",
            )
        }
        if (coreRcs.any { it != 0 }) {
            throw IllegalStateException(
                "IRCMD core config failed ems=$setEmsRc distance=$setDistanceRc gain=$setGainRc",
            )
        }
        val tpdAfterCore = snapshotTpdIrcmd(ircmd, handle)
        UvcDebugLogger.log(appContext, tag, "STARTUP config afterCore {$tpdAfterCore}")

        val zoomRcs = zoomCenterDownIrcmd(
            ircmd = ircmd,
            handle = handle,
            count = APK_CONFIG_ZOOM_REPEAT_COUNT,
            delayMs = IRCMD_SETTLE_AFTER_WRITE_MS,
        )
        val tpdAfterZoom = snapshotTpdIrcmd(ircmd, handle)
        UvcDebugLogger.log(
            appContext,
            tag,
            "STARTUP config afterZoom {$tpdAfterZoom} zoomRcs=${zoomRcs.joinToString(prefix = "[", postfix = "]")}",
        )

        val mirrorRc = setImageParamIrcmd(
            CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP,
            CommonParams.PropImageParamsValue.MirrorFlipType.NO_MIRROR_FLIP,
            ircmd,
            handle,
        )
        val ddeRc = setImageParamIrcmd(
            CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
            CommonParams.PropImageParamsValue.DDEType.DDE_2,
            ircmd,
            handle,
        )
        val contrastRc = setImageParamIrcmd(
            CommonParams.PropImageParams.IMAGE_PROP_LEVEL_CONTRAST,
            CommonParams.PropImageParamsValue.NumberType(APK_CONFIG_CONTRAST.toString()),
            ircmd,
            handle,
        )
        val agcRc = setImageParamIrcmd(
            CommonParams.PropImageParams.IMAGE_PROP_ONOFF_AGC,
            CommonParams.PropImageParamsValue.StatusSwith.ON,
            ircmd,
            handle,
        )
        val shutterRc = setAutoShutterIrcmd(
            CommonParams.PropAutoShutterParameterValue.StatusSwith.ON,
            ircmd,
            handle,
        )
        Thread.sleep(IRCMD_SETTLE_AFTER_WRITE_MS)
        val tpdAfterTail = snapshotTpdIrcmd(ircmd, handle)

        UvcDebugLogger.log(
            appContext,
            tag,
            "STARTUP config " +
                "core set emsRaw=$emsRaw rc=$setEmsRc distanceRaw=$distanceRaw rc=$setDistanceRc gain=$gainRaw rc=$setGainRc " +
                "afterCore {$tpdAfterCore} zoomRcs=${zoomRcs.joinToString(prefix = "[", postfix = "]")} " +
                "afterZoom {$tpdAfterZoom} mirrorRc=$mirrorRc ddeRc=$ddeRc contrastRc=$contrastRc agcRc=$agcRc shutterRc=$shutterRc " +
                "afterTail {$tpdAfterTail}",
        )
    }

    private fun runTpdMutationValidation(ircmd: IRCMD, handle: Long): TpdMutationReport {
        val baseline = readTpdSnapshot(ircmd, handle)
        val results = mutableListOf<String>()
        var mutatedFields = 0

        runMutationCase(
            name = "EMS",
            param = CommonParams.PropTPDParams.TPD_PROP_EMS,
            baseline = baseline.ems,
            candidate = proposeNumberCandidate(baseline.ems, min = 1, max = 128),
            ircmd = ircmd,
            handle = handle,
        )?.let {
            results += it.logLine
            if (it.mutated) mutatedFields++
        }

        runMutationCase(
            name = "DISTANCE",
            param = CommonParams.PropTPDParams.TPD_PROP_DISTANCE,
            baseline = baseline.distance,
            candidate = proposeNumberCandidate(baseline.distance, min = 0, max = 25600),
            ircmd = ircmd,
            handle = handle,
        )?.let {
            results += it.logLine
            if (it.mutated) mutatedFields++
        }

        runMutationCase(
            name = "GAIN",
            param = CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL,
            baseline = baseline.gain,
            candidate = if (baseline.gain == 0) 1 else 0,
            ircmd = ircmd,
            handle = handle,
        )?.let {
            results += it.logLine
            if (it.mutated) mutatedFields++
        }

        val after = readTpdSnapshot(ircmd, handle)
        val allPinnedEight = baseline.allFieldsPinnedEight() && after.allFieldsPinnedEight()
        val validated = mutatedFields >= 1 && !allPinnedEight

        UvcDebugLogger.log(
            appContext,
            tag,
            "TPD validation mutated=$mutatedFields allPinned8=$allPinnedEight baseline={${baseline.summary()}} after={${after.summary()}} " +
                "details=${results.joinToString(separator = " | ")}",
        )

        return TpdMutationReport(
            validated = validated,
            mutatedFields = mutatedFields,
            allFieldsPinnedEight = allPinnedEight,
            baseline = baseline.summary(),
        )
    }

    private fun runMutationCase(
        name: String,
        param: CommonParams.PropTPDParams,
        baseline: Int,
        candidate: Int,
        ircmd: IRCMD,
        handle: Long,
    ): MutationCaseResult? {
        if (baseline == Int.MIN_VALUE) {
            return MutationCaseResult(false, "$name skipped baseline=timeout")
        }
        if (candidate == baseline) {
            return MutationCaseResult(false, "$name skipped candidate==$baseline")
        }
        val setRc = setTpdParamIrcmd(param, candidate, ircmd, handle)
        Thread.sleep(IRCMD_SETTLE_AFTER_WRITE_MS)
        val readAfterSet = getTpdParamIrcmd(param, ircmd, handle)
        val restoreRc = setTpdParamIrcmd(param, baseline, ircmd, handle)
        Thread.sleep(IRCMD_SETTLE_AFTER_WRITE_MS)
        val readAfterRestore = getTpdParamIrcmd(param, ircmd, handle)
        val mutated = (readAfterSet == candidate) && (readAfterRestore == baseline)
        return MutationCaseResult(
            mutated = mutated,
            logLine = "$name base=$baseline candidate=$candidate setRc=$setRc readAfterSet=$readAfterSet " +
                "restoreRc=$restoreRc readAfterRestore=$readAfterRestore mutated=$mutated",
        )
    }

    private fun readTpdSnapshot(ircmd: IRCMD, handle: Long): TpdSnapshot {
        return TpdSnapshot(
            gain = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, ircmd, handle),
            ems = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_EMS, ircmd, handle),
            tau = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_TAU, ircmd, handle),
            ta = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_TA, ircmd, handle),
            tu = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_TU, ircmd, handle),
            distance = getTpdParamIrcmd(CommonParams.PropTPDParams.TPD_PROP_DISTANCE, ircmd, handle),
        )
    }

    private fun proposeNumberCandidate(baseline: Int, min: Int, max: Int): Int {
        if (baseline == Int.MIN_VALUE) return min
        val delta = if (baseline < max) 1 else -1
        return max(min, min(max, baseline + delta))
    }

    private fun setStartupStage(stage: VendorStartupStage) {
        startupStage = stage
        UvcDebugLogger.log(appContext, tag, "STARTUP stage=${stage.name}")
        val status = when (stage) {
            VendorStartupStage.CONNECTING -> "Connecting vendor UVC…"
            VendorStartupStage.PREVIEW_STARTING -> "Starting vendor preview…"
            VendorStartupStage.IRCMD_WARMUP -> "Warming IRCMD session…"
            VendorStartupStage.WIRE_INIT -> "Applying TC001 wire init hooks…"
            VendorStartupStage.APPLY_VENDOR_CONFIG -> "Applying vendor startup config…"
            VendorStartupStage.TPD_VALIDATION -> "Validating TPD mutation…"
            VendorStartupStage.RUNNING -> null
            else -> null
        }
        if (status != null || stage == VendorStartupStage.RUNNING) {
            mainHandler.post { onStatus?.invoke(status) }
        }
    }

    private fun markStartupFailure(message: String) {
        startupFailure = message
        correctionGateReady = false
        setStartupStage(VendorStartupStage.DEGRADED)
        UvcDebugLogger.log(appContext, tag, "STARTUP failure: $message")
        mainHandler.post { onStatus?.invoke("Startup degraded: $message") }
    }

    private fun shouldUseNativeCorrection(): Boolean {
        if (nativeCorrection == null) return false
        if (!useVendorOwnedPath) return true
        return correctionGateReady
    }

    private fun callNativeWithTimeout(name: String, call: () -> Int): Int {
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "native-$name") {
            try {
                result.set(runCatching(call).getOrDefault(Int.MIN_VALUE))
            } finally {
                done.countDown()
            }
        }
        val completed = done.await(NATIVE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!completed) {
            UvcDebugLogger.log(appContext, tag, "native $name timeout")
            return Int.MIN_VALUE
        }
        return result.get()
    }

    private fun parseBusDev(deviceName: String?): Pair<Int, Int> {
        if (deviceName.isNullOrBlank()) return 0 to 0
        val parts = deviceName.split('/')
        if (parts.size < 2) return 0 to 0
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0
        val devNum = parts.lastOrNull()?.toIntOrNull() ?: 0
        return bus to devNum
    }

    /** Prefer TOPDON 256×384 YUYV; accept close variants. */
    private fun pickTopdonSize(list: List<Size>): Size? {
        if (list.isEmpty()) return null

        val uncompressed = list.filter { it.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED }

        // Lenovo + TOPDON: descriptor reports 256×392 (matches tablet IPC screenshot), not 384.
        uncompressed.firstOrNull { it.width == 256 && it.height == 392 }?.let { return it }
        uncompressed.firstOrNull { it.width == 256 && it.height == 384 }?.let { return it }
        uncompressed.firstOrNull { it.width == 256 && it.height == 386 }?.let { return it }
        uncompressed.firstOrNull { it.width == 256 && it.height == 480 }?.let { return it }
        uncompressed.filter { it.width == 256 }.maxByOrNull { it.height }?.let { return it }

        // Some devices only list 256×192 (single band) — try doubling height via 384 request
        uncompressed.firstOrNull { it.width == 256 && it.height == 192 }?.let { band ->
            return Size(
                UVCCamera.UVC_VS_FRAME_UNCOMPRESSED,
                256,
                384,
                band.fps,
                band.fpsList ?: arrayListOf(band.fps),
            )
        }

        list.firstOrNull { it.width == 256 && it.height == 384 }?.let { return it }
        list.firstOrNull { it.width == 256 && it.height == 386 }?.let { return it }

        return uncompressed.maxByOrNull { it.width * it.height }
            ?: list.maxByOrNull { it.width * it.height }
    }

    private fun frameTypeName(type: Int): String = when (type) {
        UVCCamera.UVC_VS_FRAME_UNCOMPRESSED -> "YUYV"
        UVCCamera.UVC_VS_FRAME_MJPEG -> "MJPEG"
        else -> "type=$type"
    }

    private fun logTpdParamsIfAvailable() {
        thread(isDaemon = true, name = "tpd-probe-${device.deviceId}") {
            logTpdParamsWorker()
        }
    }

    private fun logTpdParamsWorker() {
        val controlHandle = runCatching {
            val control = helper.getUVCControl()
            val field = control.javaClass.getDeclaredField("mNativePtr")
            field.isAccessible = true
            field.getLong(control)
        }.getOrNull() ?: 0L
        val cameraHandle = extractUvccameraNativePtr()
        UvcDebugLogger.log(
            appContext,
            tag,
            "TPD probe start cameraHandle=$cameraHandle controlHandle=$controlHandle",
        )
        if (controlHandle == 0L && cameraHandle == 0L) {
            UvcDebugLogger.log(appContext, tag, "TPD props: no native handle available")
            return
        }
        val emsRaw = getTpdParam(3, cameraHandle)
        val tauRaw = getTpdParam(4, cameraHandle)
        val taRaw = getTpdParam(2, cameraHandle)
        val tuRaw = getTpdParam(1, cameraHandle)
        val emsCtrl = getTpdParam(3, controlHandle)
        val tauCtrl = getTpdParam(4, controlHandle)
        val taCtrl = getTpdParam(2, controlHandle)
        val tuCtrl = getTpdParam(1, controlHandle)
        UvcDebugLogger.log(
            appContext,
            tag,
            "TPD props cameraHandle=$cameraHandle ems=$emsRaw tau=$tauRaw ta=$taRaw tu=$tuRaw " +
                "controlHandle=$controlHandle emsCtrl=$emsCtrl tauCtrl=$tauCtrl taCtrl=$taCtrl tuCtrl=$tuCtrl",
        )

        runVendorSessionProbe("postOpen", queryTpd = true)
    }

    private fun runVendorSessionProbe(stage: String, queryTpd: Boolean) {
        val vendorOpen = Tc001VendorSessionBridge.open(appContext, device)
        val vendorSession = vendorOpen.session
        if (vendorSession == null) {
            UvcDebugLogger.log(appContext, tag, "Vendor session[$stage] open failed: ${vendorOpen.reason}")
            return
        }
        try {
            if (!queryTpd) {
                UvcDebugLogger.log(
                    appContext,
                    tag,
                    "Vendor session[$stage] connectRc=${vendorSession.connectResult} " +
                        "handle=${vendorSession.handle} bus=${vendorSession.busNum} dev=${vendorSession.devNum} reason=${vendorOpen.reason}",
                )
                return
            }
            val emsVendor = getTpdParam(3, vendorSession.handle)
            val tauVendor = getTpdParam(4, vendorSession.handle)
            val taVendor = getTpdParam(2, vendorSession.handle)
            val tuVendor = getTpdParam(1, vendorSession.handle)
            UvcDebugLogger.log(
                appContext,
                tag,
                "TPD props[$stage] vendorHandle=${vendorSession.handle} connectRc=${vendorSession.connectResult} " +
                    "bus=${vendorSession.busNum} dev=${vendorSession.devNum} " +
                    "reason=${vendorOpen.reason} " +
                    "emsVendor=$emsVendor tauVendor=$tauVendor taVendor=$taVendor tuVendor=$tuVendor",
            )
        } finally {
            Tc001VendorSessionBridge.close(vendorSession)
        }
    }

    private fun getTpdParam(key: Int, cameraHandle: Long): Int {
        if (cameraHandle == 0L) return Int.MIN_VALUE
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "tpd-param-$key") {
            try {
                val out = intArrayOf(0)
                val rc = runCatching { LibIRCMD.get_prop_tpd_params(key, out, cameraHandle) }.getOrDefault(-9999)
                result.set(if (rc == 0) out[0] else Int.MIN_VALUE)
            } finally {
                done.countDown()
            }
        }
        val completed = done.await(300, TimeUnit.MILLISECONDS)
        if (!completed) {
            UvcDebugLogger.log(
                appContext,
                tag,
                "TPD get_prop_tpd_params timeout key=$key handle=$cameraHandle",
            )
            return Int.MIN_VALUE
        }
        return result.get()
    }

    private fun extractUvccameraNativePtr(): Long {
        return runCatching {
            val serviceField = helper.javaClass.getDeclaredField("mService")
            serviceField.isAccessible = true
            val serviceObj = serviceField.get(helper) ?: return@runCatching 0L
            val camerasField = serviceObj.javaClass.getDeclaredField("mCameras")
            camerasField.isAccessible = true
            val cameras = camerasField.get(serviceObj) as? Map<*, *> ?: return@runCatching 0L
            val cameraInternal = cameras.values.firstOrNull() ?: return@runCatching 0L
            val uvccField = cameraInternal.javaClass.getDeclaredField("mUVCCamera")
            uvccField.isAccessible = true
            val uvcc = uvccField.get(cameraInternal) ?: return@runCatching 0L
            val ptrField = uvcc.javaClass.getDeclaredField("mNativePtr")
            ptrField.isAccessible = true
            ptrField.getLong(uvcc)
        }.getOrDefault(0L)
    }

    private fun probeVendorPointTemp(frame: ByteBuffer): Triple<Int, Int, Double> {
        val thermalRows = TopdonFrameDecoder.thermalImageRowRange(frameHeight)
        require(!thermalRows.isEmpty()) { "No thermal rows for frame height=$frameHeight" }
        val thermalHeight = thermalRows.count()
        val width = frameWidth
        val base = frame.position()
        val bytes = ByteArray(width * thermalHeight * 2)
        var outIdx = 0
        for ((outY, srcY) in thermalRows.withIndex()) {
            for (x in 0 until width) {
                val raw = TopdonFrameDecoder.readLe16Raw(frame, base, width, srcY, x)
                bytes[outIdx++] = (raw and 0xFF).toByte()
                bytes[outIdx++] = ((raw shr 8) and 0xFF).toByte()
            }
        }
        val centerX = width / 2
        val centerY = thermalHeight / 2
        val centerRaw = TopdonFrameDecoder.readLe16Raw(frame, base, width, thermalRows.first + centerY, centerX)
        val dot = LibIRTemp.Dot_t().apply {
            x = centerX
            y = centerY
        }
        val res = LibIRTemp.TempDataRes_t().apply {
            this.width = width.toChar()
            this.height = thermalHeight.toChar()
        }
        val outTemp = charArrayOf(0.toChar())
        LibIRTemp.get_point_temp(bytes, res, dot, outTemp)
        val native = outTemp[0].code
        val celsius = native.toDouble() / 16.0 - 273.15
        return Triple(centerRaw, native, celsius)
    }

    companion object {
        private const val MIN_FRAME_MS = 33L
        private const val DIAG_LOG_INTERVAL_MS = 2000L
        private const val NATIVE_CALL_TIMEOUT_MS = 5000L
        private const val IRCMD_CALL_TIMEOUT_MS = 3000L
        private const val IRCMD_RESTART_DELAY_MS = 100L
        private const val IRCMD_RETRY_DELAY_MS = 300L
        private const val IRCMD_WARMUP_SETTLE_MS = 1500L
        private const val IRCMD_SETTLE_AFTER_WRITE_MS = 250L
        private const val APK_CONFIG_SCALE_128 = 128.0f
        private const val APK_CONFIG_DEFAULT_EMS = 0.95f
        private const val APK_CONFIG_DEFAULT_DISTANCE_M = 1.0f
        private const val APK_CONFIG_DEFAULT_GAIN_SEL = 0
        private const val APK_CONFIG_ZOOM_REPEAT_COUNT = 5
        private const val APK_CONFIG_CONTRAST = 50
        private const val VENDOR_MIN_FPS = 1
        private const val VENDOR_MAX_FPS = 51
        private const val VENDOR_FRAME_FORMAT_YUYV = 0
        private const val VENDOR_BANDWIDTH = 1.0f
        private const val ENABLE_IRCMD_WARMUP = false
        private const val ENABLE_TC001_WIRE_INIT_HOOKS = true
        private const val VID_TOPDON_NEW = 0x2BDF
        private const val VID_TOPDON_OLD = 0x3474
        private const val PID_TC001 = 0x0102
        private const val PID_TC001_MAX = 0x4962

        fun hasSystemCamera2External(context: Context): Boolean =
            context.packageManager.hasSystemFeature("android.hardware.camera.external")

        fun logPullHint(context: Context): String = PreviewSnapshotLogger.pullHint(context)

        private fun isTopdonVendorDevice(vendorId: Int, productId: Int): Boolean {
            return (vendorId == VID_TOPDON_NEW && productId == PID_TC001) ||
                (vendorId == VID_TOPDON_OLD && productId == PID_TC001_MAX)
        }

        private fun fmt(value: Double?): String = if (value == null || !value.isFinite()) {
            "na"
        } else {
            "%.1f".format(value)
        }
    }

    private enum class VendorStartupStage {
        IDLE,
        CONNECTING,
        PREVIEW_STARTING,
        IRCMD_WARMUP,
        WIRE_INIT,
        APPLY_VENDOR_CONFIG,
        TPD_VALIDATION,
        RUNNING,
        DEGRADED,
        RELEASED,
    }

    private data class MutationCaseResult(
        val mutated: Boolean,
        val logLine: String,
    )

    private data class TpdMutationReport(
        val validated: Boolean,
        val mutatedFields: Int,
        val allFieldsPinnedEight: Boolean,
        val baseline: String,
    )

    private data class StartupReadiness(
        val ready: Boolean,
        val message: String,
    )

    private data class TpdSnapshot(
        val gain: Int,
        val ems: Int,
        val tau: Int,
        val ta: Int,
        val tu: Int,
        val distance: Int,
    ) {
        fun summary(): String = "gain=$gain ems=$ems tau=$tau ta=$ta tu=$tu distance=$distance"
        fun allFieldsPinnedEight(): Boolean = listOf(gain, ems, tau, ta, tu, distance).all { it == 8 }
    }
}
