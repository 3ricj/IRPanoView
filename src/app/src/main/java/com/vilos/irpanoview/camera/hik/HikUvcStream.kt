package com.vilos.irpanoview.camera.hik

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

/**
 * UVC Video Streaming probe/commit (libuvc path behind SET 0xBBC + StartStreamCallback).
 *
 * Hik SDK SetVideoParam does not emit extension-unit control transfers; it configures
 * libuvc VS PROBE/COMMIT on the bulk interface (34 B block, 25 fps).
 */
object HikUvcStream {

    private const val BM_CLASS_IF_OUT = 0x21
    private const val BM_CLASS_IF_IN = 0xA1
    private const val UVC_SET_CUR = 0x01
    private const val UVC_GET_CUR = 0x81
    private const val UVC_GET_LEN = 0x85
    private const val UVC_VS_PROBE = 0x0100
    private const val UVC_VS_COMMIT = 0x0200

    /** 100 ns units for [HikUvcConstants.STREAM_FPS] (25 Hz → 400_000). */
    private const val FRAME_INTERVAL_100NS = 10_000_000 / HikUvcConstants.STREAM_FPS

    /** YUY2 is the only VS format on TC002C (descriptor bFormatIndex=1). */
    private const val UVC_FORMAT_INDEX_YUY2 = 1

    /**
     * Frame index 9 in USB descriptor: wWidth=8, wHeight=0x3122, 25 fps (0x61A80).
     * Matches HikVideoParam dwWidth/dwHeight tokens and native MyFStreamCallBack metadata.
     */
    private const val UVC_FRAME_INDEX_HIK = 9

    data class ArmResult(
        val ok: Boolean,
        val altInterfaceSet: Boolean,
        val detail: String,
    )

    /**
     * Stop UVC bulk streaming before releasing interfaces (alt 0 = zero bandwidth).
     * Best-effort: errors are swallowed so [HikUsbConnection.close] always proceeds.
     */
    fun disarmStream(
        transport: HikUvcTransport,
        usb: HikUsbLink,
        device: UsbDevice,
    ): Boolean {
        val vsIface = findStreamingInterface(device) ?: return false
        return runCatching {
            // Match armStream: alt-0 before VS PROBE/COMMIT (decommit-before-alt wedged ~17s in logs).
            usb.selectInterfaceAlt(vsIface.id, 0)
            var decommitted = false
            for (wIdx in buildWIndexCandidates(vsIface)) {
                try {
                    val probeLen = probeWireLen(transport, wIdx) ?: DEFAULT_PROBE_LEN
                    val len = probeLen.coerceIn(26, 48)
                    val probe = readProbe(transport, wIdx, len)
                    if (probe.size >= 26) {
                        HikUvcProtocol.packU32Le(probe, 22, 0)
                    }
                    if (probe.size >= 30) {
                        HikUvcProtocol.packU32Le(probe, 26, 0)
                    }
                    transport.controlWrite(BM_CLASS_IF_OUT, UVC_SET_CUR, UVC_VS_PROBE, wIdx, probe)
                    transport.controlWrite(BM_CLASS_IF_OUT, UVC_SET_CUR, UVC_VS_COMMIT, wIdx, probe)
                    decommitted = true
                    break
                } catch (_: HikProtocolException) {
                    // try next wIndex candidate
                }
            }
            usb.resetBulkCarry()
            // #region agent log
            com.vilos.irpanoview.util.AgentDebugLog.log(
                hypothesisId = "H7",
                location = "HikUvcStream.disarmStream",
                message = "alt0 then vs decommit",
                data = mapOf(
                    "decommitted" to decommitted,
                    "vsIface" to vsIface.id,
                ),
                runId = "post-fix",
            )
            // #endregion
            true
        }.getOrDefault(false)
    }

    /** VS alt-0 only — bounded; no VS PROBE/COMMIT (decommit wedged 17s+ in logs). */
    fun disarmAlt0Bounded(usb: HikUsbLink, device: UsbDevice, timeoutMs: Long = 400L): Boolean {
        val vsIface = findStreamingInterface(device) ?: return false
        if (usb is HikUsbConnection) {
            return usb.selectInterfaceAltBounded(vsIface.id, 0, timeoutMs)
        }
        return runCatching { usb.selectInterfaceAlt(vsIface.id, 0) }.getOrDefault(false)
    }

    fun armStream(
        transport: HikUvcTransport,
        usb: HikUsbLink,
        device: UsbDevice,
    ): ArmResult {
        val vsIface = findStreamingInterface(device)
            ?: return ArmResult(false, false, "no-vs-interface")

        val wIndexCandidates = buildWIndexCandidates(vsIface)
        var lastErr = "vs-commit-failed"
        for (wIdx in wIndexCandidates) {
            try {
                usb.selectInterfaceAlt(vsIface.id, 0)
                val probeLen = probeWireLen(transport, wIdx) ?: DEFAULT_PROBE_LEN
                val len = probeLen.coerceIn(26, 48)
                val probe = readProbe(transport, wIdx, len)
                patchProbeFromVideoParam(probe)
                transport.controlWrite(BM_CLASS_IF_OUT, UVC_SET_CUR, UVC_VS_PROBE, wIdx, probe)
                transport.controlWrite(BM_CLASS_IF_OUT, UVC_SET_CUR, UVC_VS_COMMIT, wIdx, probe)
                HikUvcProtocol.pollCommandState(transport)
                val altOk = usb.selectStreamingAltSetting()
                usb.resetBulkCarry()
                return ArmResult(
                    ok = true,
                    altInterfaceSet = altOk,
                    detail = "wIdx=$wIdx if=${vsIface.id} probeLen=$len fmtIdx=$UVC_FORMAT_INDEX_YUY2 frameIdx=$UVC_FRAME_INDEX_HIK alt=$altOk",
                )
            } catch (e: HikProtocolException) {
                lastErr = "wIdx=$wIdx ${e.message ?: "fail"}"
            }
        }
        return ArmResult(false, false, lastErr)
    }

    /** Visible for unit tests. */
    internal fun patchProbeFromVideoParam(probe: ByteArray) {
        if (probe.size >= 4) {
            probe[2] = UVC_FORMAT_INDEX_YUY2.toByte()
            probe[3] = UVC_FRAME_INDEX_HIK.toByte()
        }
        if (probe.size >= 8) {
            HikUvcProtocol.packU32Le(probe, 4, FRAME_INTERVAL_100NS)
        }
        if (probe.size >= 26) {
            HikUvcProtocol.packU32Le(probe, 22, HikTherm.FRAME_BYTES)
        }
    }

    private const val DEFAULT_PROBE_LEN = 34

    private fun readProbe(transport: HikUvcTransport, wIndex: Int, len: Int): ByteArray {
        val raw = transport.controlRead(BM_CLASS_IF_IN, UVC_GET_CUR, UVC_VS_PROBE, wIndex, len)
        val buf = ByteArray(len)
        raw.copyInto(buf, endIndex = minOf(raw.size, len))
        return buf
    }

    private fun probeWireLen(transport: HikUvcTransport, wIndex: Int): Int? {
        return try {
            val head = transport.controlRead(BM_CLASS_IF_IN, UVC_GET_LEN, UVC_VS_PROBE, wIndex, 2)
            if (head.size < 2) null else (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
        } catch (_: HikProtocolException) {
            null
        }
    }

    private fun buildWIndexCandidates(iface: UsbInterface): List<Int> {
        val n = iface.id
        val alt0 = (0 shl 8) or n
        val altLibuvc = n shl 8
        return listOf(altLibuvc, alt0, n, (iface.alternateSetting shl 8) or n).distinct()
    }

    private fun findStreamingInterface(device: UsbDevice): UsbInterface? {
        var best: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var hasBulkIn = false
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) {
                    hasBulkIn = true
                }
            }
            val isVideo = iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO
            if (!isVideo && !hasBulkIn) continue
            if (best == null ||
                (hasBulkIn && bestHasBulkIn(best) == false) ||
                (hasBulkIn == bestHasBulkIn(best) && iface.alternateSetting >= best.alternateSetting)
            ) {
                best = iface
            }
        }
        return best
    }

    private fun bestHasBulkIn(iface: UsbInterface): Boolean {
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_IN
            ) {
                return true
            }
        }
        return false
    }
}
