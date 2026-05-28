package com.vilos.irpanoview.camera.hik

/**
 * Session orchestration: bind, ready gate, initConfig, stream negotiation.
 * Port of py_re_framework/core/hik_session.py — pure USB only, no vendor native libs.
 */
class HikSession(
    private val transport: HikUvcTransport,
    private val usb: HikUsbLink? = null,
) {
    var deviceInfo: ByteArray = ByteArray(0)
        private set
    var lastThermWire: ByteArray = ByteArray(0)
        private set
    var lastHardwareServer: ByteArray = ByteArray(0)
        private set
    var lastStreamArm: HikStreamArm.Result? = null
        private set

    data class Config(
        val emissivity: Int = 95,
        val distance: Int = 100,
        val temperatureRange: Int = 2,
        val bulkEndpoint: Int = HikTherm.BULK_EP_DEFAULT,
        val coldBind: Boolean = true,
        val forceColdBind: Boolean = false,
        val enhancementProfile: HikImageEnhancement.Profile = HikEnhancementProfileSource.activeProfile(),
    )

    fun login(config: Config = Config()): ByteArray {
        deviceInfo = HikUvcProtocol.login(
            transport,
            coldBind = config.coldBind,
            forceColdBind = config.forceColdBind,
        )
        return deviceInfo
    }

    fun waitStreamReady(): ByteArray {
        lastHardwareServer = HikUvcProtocol.waitStreamReady(transport)
        return lastHardwareServer
    }

    fun initConfig(config: Config = Config()): ByteArray {
        lastThermWire = HikUvcProtocol.initConfig(
            transport,
            emissivity = config.emissivity,
            distance = config.distance,
            temperatureRange = config.temperatureRange,
            enhancementProfile = config.enhancementProfile,
        )
        return lastThermWire
    }

    fun negotiatePreStream() {
        HikUvcProtocol.negotiatePreStream(transport)
    }

    /** Full startup: bind → ready → initConfig → pure-USB stream arm attempt. */
    fun runStartup(config: Config = Config()): StartupResult {
        login(config)
        waitStreamReady()
        initConfig(config)
        val arm = usb?.let { HikStreamArm.attemptArm(transport, it, it.device) }
        lastStreamArm = arm
        val enhanceWire = HikEnhancementProfileSource.lastSetWire
        return StartupResult(
            deviceInfoBytes = deviceInfo.size,
            hardwareStatus = lastHardwareServer.getOrNull(2)?.toInt()?.and(0xFF),
            thermWireHead = lastThermWire.take(16).joinToString("") { "%02x".format(it) },
            enhancementProfile = config.enhancementProfile.id,
            enhancementWireSummary = HikImageEnhancement.formatKeyBytes(enhanceWire),
            streamArm = arm,
        )
    }

    fun reArmStream(): HikStreamArm.Result? {
        val conn = usb ?: return null
        val arm = HikStreamArm.attemptArm(transport, conn, conn.device)
        lastStreamArm = arm
        conn.resetBulkCarry("rearm")
        return arm
    }

    fun drainFrame(
        endpoint: Int = HikTherm.BULK_EP_DEFAULT,
        firstFrame: Boolean = false,
        shouldAbort: () -> Boolean = { false },
    ): ByteArray? {
        val conn = usb ?: throw HikProtocolException("no UsbDeviceConnection for bulk read")
        return if (HikBulkGate.useParallelBulk()) {
            val drainMs = if (firstFrame) {
                HikUvcConstants.FIRST_FRAME_DRAIN_MS
            } else {
                HikUvcConstants.FRAME_DRAIN_MS
            }
            conn.bulkDrainOneFrame(endpoint, drainMs, shouldAbort)
        } else {
            conn.bulkPollSuperFrame(endpoint, shouldAbort = shouldAbort)
        }
    }

    fun pollFrame(
        endpoint: Int = HikTherm.BULK_EP_DEFAULT,
        timeoutMs: Int = HikUvcConstants.BULK_TRANSFER_CAP_MS,
    ): ByteArray? {
        val conn = usb ?: throw HikProtocolException("no UsbDeviceConnection for bulk read")
        return conn.bulkPollSuperFrame(endpoint, timeoutMs)
    }

    fun readFrame(
        endpoint: Int = HikTherm.BULK_EP_DEFAULT,
        timeoutMs: Int = HikUvcConstants.bulkSteadyTimeoutMs(),
    ): ByteArray {
        val conn = usb ?: throw HikProtocolException("no UsbDeviceConnection for bulk read")
        return conn.bulkReadSuperFrame(endpoint, timeoutMs)
    }

    /** Scene params (ems / distance / ambient) via GET/SET 0x7EF — TopInfrared setIrConfig parity. */
    fun applyIrConfig() {
        HikUvcProtocol.setIrConfig(transport)
    }

    /** Persistent auto-shutter policy (serial 0x838 / inner 0x2001). Call once after stream is live. */
    fun setAutoShutter(enabled: Boolean = true) {
        HikUvcProtocol.setAutoShutter(transport, enabled)
    }

    /** One-shot manual NUC / black reference (control 0x7E9). Safe while streaming. */
    fun manualShutter() {
        HikUvcProtocol.manualShutter(transport)
    }

    data class StartupResult(
        val deviceInfoBytes: Int,
        val hardwareStatus: Int?,
        val thermWireHead: String,
        val enhancementProfile: String,
        val enhancementWireSummary: String,
        val streamArm: HikStreamArm.Result?,
    )
}
