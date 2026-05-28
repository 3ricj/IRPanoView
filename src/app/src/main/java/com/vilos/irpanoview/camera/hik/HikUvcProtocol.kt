package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.util.AgentDebugLog
import kotlin.math.roundToInt

/**
 * Hik TC002C UVC DeviceConfig protocol (port of py_re_framework/core/hik_uvc.py).
 */
object HikUvcProtocol {

    fun uvcSelect(t: HikUvcTransport, phase: Int, sub: Int) {
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H2-H3",
            location = "HikUvcProtocol.uvcSelect:before",
            message = "SELECT 0x500",
            data = mapOf(
                "phase" to phase,
                "sub" to sub,
                "wIndex" to t.windex,
                "wIndexHex" to "0x${t.windex.toString(16)}",
            ),
        )
        // #endregion
        try {
            t.controlWrite(
                HikUvcConstants.BM_REQ_OUT,
                HikUvcConstants.BREQ_SET,
                HikUvcConstants.WVALUE_SELECT,
                t.windex,
                byteArrayOf(phase.toByte(), sub.toByte()),
            )
        } catch (e: HikProtocolException) {
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H2-H3",
                location = "HikUvcProtocol.uvcSelect:fail",
                message = e.message ?: "select failed",
                data = mapOf(
                    "phase" to phase,
                    "sub" to sub,
                    "wIndex" to t.windex,
                ),
            )
            // #endregion
            throw HikProtocolException(
                "uvcSelect phase=$phase sub=$sub wIdx=0x${t.windex.toString(16)}: ${e.message}",
            )
        }
    }

    fun uvcProbe(t: HikUvcTransport, wvalue: Int): ByteArray =
        t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_PROBE,
            wvalue,
            t.windex,
            4,
        )

    fun uvcProbeWireLen(t: HikUvcTransport, wvalue: Int): Int {
        val probe = uvcProbe(t, wvalue)
        if (probe.size < 2) return 0
        return (probe[0].toInt() and 0xFF) or ((probe[1].toInt() and 0xFF) shl 8)
    }

    fun uvcGet(t: HikUvcTransport, route: HikDeviceConfigRoute): ByteArray {
        uvcSelect(t, route.phase, route.sub)
        uvcProbe(t, route.dataWvalue)
        return t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            route.dataWvalue,
            t.windex,
            route.getWireLen,
        )
    }

    fun uvcSet(t: HikUvcTransport, wvalue: Int, payload: ByteArray) {
        uvcProbe(t, wvalue)
        t.controlWrite(
            HikUvcConstants.BM_REQ_OUT,
            HikUvcConstants.BREQ_SET,
            wvalue,
            t.windex,
            payload,
        )
    }

    fun uvcSetRoute(t: HikUvcTransport, route: HikDeviceConfigRoute, payload: ByteArray) {
        require(payload.size == route.setWireLen) {
            "${route.name} SET expects ${route.setWireLen} B, got ${payload.size}"
        }
        uvcSet(t, route.dataWvalue, payload)
    }

    fun uvcGetModifySet(
        t: HikUvcTransport,
        route: HikDeviceConfigRoute,
        patch: (ByteArray) -> Unit,
    ): ByteArray {
        val raw = uvcGet(t, route)
        val buf = ByteArray(route.setWireLen)
        raw.copyInto(buf, endIndex = minOf(raw.size, route.setWireLen))
        patch(buf)
        uvcSetRoute(t, route, buf)
        return buf
    }

    fun pollCommandState(t: HikUvcTransport): Int {
        uvcProbe(t, HikUvcConstants.WVALUE_STATUS)
        val data = t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_STATUS,
            t.windex,
            1,
        )
        return if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0xFF
    }

    /** `USB_COMMON_COND` wire block for control **0x7E9** (12 B). */
    fun packUsbCommonCond(channelId: Int = 1, sid: Int = 0): ByteArray {
        require(channelId in 0..255 && sid in 0..255) { "channelId/sid out of range" }
        val buf = ByteArray(HikUvcConstants.USB_COMMON_COND_WIRE_LEN)
        packU32Le(buf, 0, HikUvcConstants.USB_COMMON_COND_WIRE_LEN)
        buf[4] = channelId.toByte()
        buf[5] = sid.toByte()
        return buf
    }

    /**
     * 269 B serial wrapper wire for SET **0x838** (inner opcode in [dwDeviceCMD], value in [dwValue]).
     */
    fun packSerialTransmission(deviceCmd: Int, value: Int, sendData: ByteArray = ByteArray(0)): ByteArray {
        require(sendData.size <= 256) { "serial sendData max 256 B" }
        val buf = ByteArray(HikUvcConstants.SERIAL_TRANSMISSION_WIRE_LEN)
        buf[0] = 2 // byMode
        packU32Le(buf, 3, deviceCmd)
        packU32Le(buf, 7, value)
        packU16Le(buf, 11, sendData.size)
        sendData.copyInto(buf, destinationOffset = 13)
        return buf
    }

    /**
     * Auto-shutter policy (persistent). SET **0x838** with inner **0x2001**; TopInfrared init step 8.3.
     */
    fun setAutoShutter(t: HikUvcTransport, enabled: Boolean) {
        uvcProbe(t, HikUvcConstants.WVALUE_SELECT)
        uvcSelect(t, HikUvcConstants.SELECT_PHASE_SERIAL, HikUvcConstants.SELECT_SUB_SERIAL)
        uvcSet(
            t,
            HikUvcConstants.WVALUE_THERM,
            packSerialTransmission(
                HikUvcConstants.SERIAL_CMD_AUTO_SHUTTER,
                if (enabled) 1 else 0,
            ),
        )
        waitCommandIdle(t)
    }

    /**
     * Manual shutter / black reference (NUC). Control **0x7E9** with 12 B `USB_COMMON_COND`.
     * Wire: kind 2025 @ wValue **0x0200** sub **0x04** (Android runtime trace PROBE 0x500 then 0x200).
     * See MasterThermoDocs/09_Shutter_And_Maintenance.md.
     */
    fun manualShutter(t: HikUvcTransport) {
        uvcProbe(t, HikUvcConstants.WVALUE_SELECT)
        uvcSelect(
            t,
            HikUvcConstants.SELECT_PHASE_IMAGE_MANUAL_CORRECT,
            HikUvcConstants.SELECT_SUB_IMAGE_MANUAL_CORRECT,
        )
        uvcSet(t, HikUvcConstants.WVALUE_IMAGE, packUsbCommonCond())
        waitCommandIdle(t)
    }

    fun packU16Le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    fun waitCommandIdle(
        t: HikUvcTransport,
        timeoutMs: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pollCommandState(t) == HikUvcConstants.COMMAND_STATE_IDLE) {
                return
            }
            Thread.sleep(HikUvcConstants.POLL_INTERVAL_MS)
        }
    }

    fun capabilitiesProbeLen(t: HikUvcTransport): Int =
        uvcProbeWireLen(t, HikUvcConstants.WVALUE_CAPABILITIES)

    fun isDeviceConfigBound(t: HikUvcTransport): Boolean {
        val hs = hardwareServerStatus(t)
        if (hs.size >= 3 && !(hs[0] == 0x00.toByte() && hs[1] == 0x02.toByte())) {
            return true
        }
        val cap = capabilitiesProbeLen(t)
        return cap > 2 && cap !in listOf(512, 513, 514)
    }

    /** True when GET **0x7DE** is the cold-plug **2 B** stub (`00 02`) or shorter. */
    fun isDeviceConfigUnbound(hs: ByteArray): Boolean {
        if (hs.size < 2) {
            return true
        }
        return hs[0] == 0x00.toByte() && hs[1] == 0x02.toByte()
    }

    data class WaitUnboundResult(
        val reached: Boolean,
        val elapsedMs: Long,
        val lastHsSize: Int,
        val lastHsHead: String,
    )

    /**
     * Poll hardware server until cold stub or [timeoutMs].
     * Used after disarm+release on bound relaunch before [usbLoginBind].
     */
    fun waitForUnboundStub(
        t: HikUvcTransport,
        timeoutMs: Long = HikUvcConstants.BOUND_UNBOUND_WAIT_MS,
    ): WaitUnboundResult {
        val startMs = System.currentTimeMillis()
        val deadline = startMs + timeoutMs
        var last = ByteArray(0)
        while (System.currentTimeMillis() < deadline) {
            last = runCatching { hardwareServerStatus(t) }.getOrElse { byteArrayOf() }
            if (isDeviceConfigUnbound(last)) {
                return WaitUnboundResult(
                    reached = true,
                    elapsedMs = System.currentTimeMillis() - startMs,
                    lastHsSize = last.size,
                    lastHsHead = last.take(4).joinToString("") { "%02x".format(it) },
                )
            }
            Thread.sleep(HikUvcConstants.POLL_INTERVAL_MS)
        }
        return WaitUnboundResult(
            reached = false,
            elapsedMs = System.currentTimeMillis() - startMs,
            lastHsSize = last.size,
            lastHsHead = last.take(4).joinToString("") { "%02x".format(it) },
        )
    }

    fun capabilitiesInitBurst(t: HikUvcTransport): Pair<ByteArray, ByteArray> {
        uvcProbe(t, HikUvcConstants.WVALUE_CAPABILITIES)
        val head = t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_CAPABILITIES,
            t.windex,
            5,
        )
        uvcProbe(t, HikUvcConstants.WVALUE_CAPABILITIES)
        val blob = t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_CAPABILITIES,
            t.windex,
            HikUvcConstants.CAPABILITIES_BLOB_LEN,
        )
        return head to blob
    }

    fun capabilitiesRefresh(t: HikUvcTransport): Pair<ByteArray, ByteArray> {
        uvcSelect(t, 0x17, 0x1D)
        uvcProbe(t, HikUvcConstants.WVALUE_CAPABILITIES)
        val head = t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_CAPABILITIES,
            t.windex,
            5,
        )
        uvcProbe(t, HikUvcConstants.WVALUE_CAPABILITIES)
        val blob = t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_CAPABILITIES,
            t.windex,
            HikUvcConstants.CAPABILITIES_BLOB_LEN,
        )
        return head to blob
    }

    fun extensionArmTopview(t: HikUvcTransport) {
        uvcProbe(t, HikUvcConstants.WVALUE_EXTENSION_VERSION)
        t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_EXTENSION_VERSION,
            t.windex,
            4,
        )
        uvcProbe(t, HikUvcConstants.WVALUE_STATUS)
        t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_STATUS,
            t.windex,
            1,
        )
        uvcProbe(t, HikUvcConstants.WVALUE_SELECT)
        t.controlWrite(
            HikUvcConstants.BM_REQ_OUT,
            HikUvcConstants.BREQ_SET,
            HikUvcConstants.WVALUE_SELECT,
            t.windex,
            byteArrayOf(0x02, 0x00),
        )
        uvcProbe(t, HikUvcConstants.WVALUE_THERM)
        t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_THERM,
            t.windex,
            14,
        )
        t.controlWrite(
            HikUvcConstants.BM_REQ_OUT,
            HikUvcConstants.BREQ_SET,
            HikUvcConstants.WVALUE_SELECT,
            t.windex,
            byteArrayOf(0x02, 0x00),
        )
        uvcProbe(t, HikUvcConstants.WVALUE_MISC)
        t.controlRead(
            HikUvcConstants.BM_REQ_IN,
            HikUvcConstants.BREQ_GET,
            HikUvcConstants.WVALUE_MISC,
            t.windex,
            3,
        )
    }

    private fun loginBindPreamble(
        t: HikUvcTransport,
        initRounds: Int = 4,
        armTopview: Boolean = true,
    ) {
        if (armTopview) {
            extensionArmTopview(t)
        }
        repeat(initRounds) {
            capabilitiesInitBurst(t)
        }
    }

    private fun loginBindTail(t: HikUvcTransport): Pair<ByteArray, ByteArray> {
        val hs = hardwareServerStatus(t)
        val info = deviceInfoBlock(t)
        capabilitiesRefresh(t)
        return info to hs
    }

    fun usbLoginBind(
        t: HikUvcTransport,
        initRounds: Int = 4,
        armTopview: Boolean = true,
    ): Pair<ByteArray, ByteArray> {
        loginBindPreamble(t, initRounds, armTopview)
        return loginBindTail(t)
    }

    fun hardwareServerStatus(t: HikUvcTransport): ByteArray =
        uvcGet(t, HikDeviceConfigRoute.ROUTE_HARDWARE_SERVER)

    fun deviceInfoBlock(t: HikUvcTransport): ByteArray =
        uvcGet(t, HikDeviceConfigRoute.ROUTE_DEVICE_INFO)

    fun login(
        t: HikUvcTransport,
        coldBind: Boolean = true,
        forceColdBind: Boolean = false,
    ): ByteArray {
        val bound = if (forceColdBind) false else isDeviceConfigBound(t)
        val hsPre = if (forceColdBind) {
            byteArrayOf()
        } else {
            runCatching { hardwareServerStatus(t) }.getOrElse { byteArrayOf() }
        }
        val hsStatus = if (hsPre.size >= 3) hsPre[2].toInt() and 0xFF else -1
        val useCold = forceColdBind || (coldBind && !bound)
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H3",
            location = "HikUvcProtocol.login:branch",
            message = if (useCold) "cold usbLoginBind" else "warm reattach bind",
            data = mapOf(
                "coldBind" to coldBind,
                "forceColdBind" to forceColdBind,
                "isDeviceConfigBound" to bound,
                "hwStatusPre" to hsStatus,
                "hsPreHead" to hsPre.take(4).joinToString("") { "%02x".format(it) },
            ),
            runId = "post-fix",
        )
        // #endregion
        val info = if (useCold) {
            usbLoginBind(t).first
        } else {
            loginBindPreamble(t)
            loginBindTail(t).first
        }
        if (info.size < HikDeviceConfigRoute.ROUTE_DEVICE_INFO.getWireLen) {
            throw HikProtocolException(
                "device not bound (GET 0x7DB returned ${info.size} B stub); retry cold bind",
            )
        }
        return info
    }

    fun waitStreamReady(t: HikUvcTransport, timeoutMs: Long = HikUvcConstants.READY_TIMEOUT_MS.toLong()): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ByteArray(0)
        while (System.currentTimeMillis() < deadline) {
            last = hardwareServerStatus(t)
            if (last.size >= 3) {
                val status = last[2].toInt() and 0xFF
                if (status == 2 || status == HikUvcConstants.HW_SERVER_READY_STATUS) {
                    return last
                }
            }
            Thread.sleep(HikUvcConstants.POLL_INTERVAL_MS)
        }
        throw HikProtocolException("hardware server not ready, last=${last.toHexHead(8)}")
    }

    /**
     * TopInfrared `setIrConfig`: emissivity, distance, ambient on GET/SET **0x7EF**.
     * Safe while streaming (polls command state after SET).
     */
    fun setIrConfig(
        t: HikUvcTransport,
        emissivityWire: Int = HikIrConfigSettings.emissivityWire(),
        distanceWire: Int = HikIrConfigSettings.distanceWire(),
        ambientCelsius: Double = HikIrConfigSettings.ambientCelsius,
    ): ByteArray {
        val therm = uvcGetModifySet(t, HikDeviceConfigRoute.ROUTE_THERM_BASIC) { buf ->
            patchIrConfig(buf, emissivityWire, distanceWire, ambientCelsius)
        }
        pollCommandState(t)
        return therm
    }

    /** Patch 80 B therm wire with scene correction fields only (preserves other GET fields). */
    fun patchIrConfig(
        buf: ByteArray,
        emissivityWire: Int,
        distanceWire: Int,
        ambientCelsius: Double,
    ) {
        if (buf.size >= HikUvcConstants.WIRE_OFF_EMISSIVITY + 4) {
            packU32Le(buf, HikUvcConstants.WIRE_OFF_EMISSIVITY, emissivityWire)
        }
        if (buf.size >= HikUvcConstants.WIRE_OFF_DISTANCE + 4) {
            packU32Le(buf, HikUvcConstants.WIRE_OFF_DISTANCE, distanceWire)
        }
        if (buf.size > HikUvcConstants.WIRE_OFF_ENV_TEMP_ENABLE) {
            buf[HikUvcConstants.WIRE_OFF_ENV_TEMP_ENABLE] = HikUvcConstants.ENV_TEMP_ENABLE_ON.toByte()
        }
        if (buf.size >= HikUvcConstants.WIRE_OFF_ENV_TEMP + 4) {
            val ambientWire = (ambientCelsius * 100.0).roundToInt() + 10_000
            packU32Le(buf, HikUvcConstants.WIRE_OFF_ENV_TEMP, ambientWire)
        }
    }

    fun initConfig(
        t: HikUvcTransport,
        emissivity: Int = HikIrConfigSettings.emissivityWire(),
        distance: Int = HikIrConfigSettings.distanceWire(),
        ambientCelsius: Double = HikIrConfigSettings.ambientCelsius,
        temperatureRange: Int = 2,
        enhancementProfile: HikImageEnhancement.Profile = HikImageEnhancement.Profile.BASELINE,
    ): ByteArray {
        HikUvcProtocol.uvcGetModifySet(t, HikDeviceConfigRoute.ROUTE_VIDEO_ADJUST, ::patchVideoAdjustLandscape)
        val enhanceWire = HikUvcProtocol.uvcGetModifySet(t, HikDeviceConfigRoute.ROUTE_IMAGE_ENHANCE) { buf ->
            HikImageEnhancement.patch(buf, enhancementProfile)
        }
        HikEnhancementProfileSource.recordSetWire(enhanceWire)
        val therm = HikUvcProtocol.uvcGetModifySet(t, HikDeviceConfigRoute.ROUTE_THERM_BASIC) { buf ->
            if (buf.size > HikUvcConstants.WIRE_OFF_THERM_OVERLAY) {
                buf[HikUvcConstants.WIRE_OFF_THERM_OVERLAY] = HikUvcConstants.THERM_OVERLAY_OFF.toByte()
            }
            if (buf.size > HikUvcConstants.WIRE_OFF_TEMPERATURE_RANGE) {
                buf[HikUvcConstants.WIRE_OFF_TEMPERATURE_RANGE] = temperatureRange.toByte()
            }
            patchIrConfig(buf, emissivity, distance, ambientCelsius)
        }
        pollCommandState(t)
        return therm
    }

    /** Pre-stream negotiation selects (doc 06 step 1-3). Stream SET 0xBBC wire: open RE item. */
    fun negotiatePreStream(t: HikUvcTransport) {
        hardwareServerStatus(t)
        pollCommandState(t)
        uvcSelect(t, 0x01, 0x04)
        pollCommandState(t)
        uvcGet(t, HikDeviceConfigRoute.ROUTE_VIDEO_ADJUST)
        uvcGet(t, HikDeviceConfigRoute.ROUTE_IMAGE_ENHANCE)
        pollCommandState(t)
    }

    /**
     * initConfig: force corridor off + landscape row-major grid (256×192).
     * Native HikCmdUtil clears [byCorridor] and [byImageFlipStyle] when either is non-zero
     * ("竖屏192×256 → 横屏256×192"). Without this the radiometric plane stays 192-wide
     * and decoding with a 256 stride produces diagonal row shear.
     */
    fun patchVideoAdjustLandscape(buf: ByteArray) {
        if (buf.size > HikUvcConstants.WIRE_OFF_VIDEO_CORRIDOR) {
            buf[HikUvcConstants.WIRE_OFF_VIDEO_CORRIDOR] = 0
        }
        if (buf.size > HikUvcConstants.WIRE_OFF_VIDEO_FLIP_STYLE) {
            buf[HikUvcConstants.WIRE_OFF_VIDEO_FLIP_STYLE] = 0
        }
    }

    fun packU32Le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.toHexHead(n: Int): String =
        take(n.coerceAtMost(size)).joinToString("") { "%02x".format(it) }
}

class HikProtocolException(message: String) : Exception(message)
