package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.BuildConfig

object HikNativeUsb {
    init {
        if (BuildConfig.HIK_NATIVE_USB) {
            System.loadLibrary("usb1.0")
            System.loadLibrary("hik_native_usb")
            nativeInit()
        }
    }

    fun ensureLoaded() {
        if (BuildConfig.HIK_NATIVE_USB) {
            nativeInit()
        }
    }

    external fun nativeInit(): Boolean
    external fun nativeOpen(fd: Int): Long
    external fun nativeClaimInterface(handle: Long, iface: Int): Int
    external fun nativeSetInterfaceAlt(handle: Long, iface: Int, alt: Int): Int
    external fun nativeControlWrite(
        handle: Long,
        bm: Int,
        bReq: Int,
        wValue: Int,
        wIndex: Int,
        data: ByteArray,
    ): Int
    external fun nativeControlRead(
        handle: Long,
        bm: Int,
        bReq: Int,
        wValue: Int,
        wIndex: Int,
        length: Int,
    ): ByteArray?
    external fun nativeStartBulkIn(handle: Long, ep: Int, packetSize: Int): Int
    external fun nativePollBulkChunk(handle: Long, maxLen: Int, timeoutMs: Int): ByteArray?
    external fun nativeCancelBulkIn(handle: Long): Int
    /** [cancelCalled, elapsedMs, inFlightAfter (0/1)] — [maxWaitMs] caps cancel join. */
    external fun nativeCancelBulkInDetail(handle: Long, maxWaitMs: Int): IntArray
    external fun nativeReleaseInterface(handle: Long, iface: Int): Int
    /** Cancel-only (~200 ms join), no alt-0. */
    external fun nativeStopChannelSoft(handle: Long, vsInterface: Int): Long
    /** Cancel with full wait (~3 s), no alt-0 — Exit stop phase fallback. */
    external fun nativeStopChannelFull(handle: Long, vsInterface: Int): Long
    /** Reference leave: cancel + alt-0 + release_interface; keep fd open. */
    external fun nativeStopChannelReference(handle: Long, vsInterface: Int, claimedIfaces: IntArray): Long
    /** alt-0 + libusb release_interface + libusb_close — once per full exit close phase. */
    external fun nativeCloseHandle(handle: Long, vsInterface: Int, claimedIfaces: IntArray)
    external fun nativeClose(handle: Long)
}
