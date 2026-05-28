package com.vilos.irpanoview.camera.hik

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * USB link for Hik protocol — Android [HikUsbConnection] or libusb [LibusbHikConnection].
 */
interface HikUsbLink : HikUvcTransport, AutoCloseable {
    val device: UsbDevice
    val androidConnection: UsbDeviceConnection?
    var logContext: Context?
    val claimedInterfaces: List<Int>
    val bulkInEndpoint: UsbEndpoint?
    var lastFrameMeta: HikStreamProbe.FrameMeta?
    var lastFrameBoundary: HikFrameBoundarySnapshot?
    val usesNativeLibusb: Boolean

    fun claimForHik(): Int
    fun selectInterfaceAlt(interfaceNumber: Int, alternateSetting: Int): Boolean
    fun selectStreamingAltSetting(): Boolean
    fun bulkDrainOneFrame(
        endpointAddress: Int,
        drainMs: Int = HikUvcConstants.FRAME_DRAIN_MS,
        shouldAbort: () -> Boolean = { false },
    ): ByteArray?
    fun bulkPollSuperFrame(
        endpointAddress: Int,
        timeoutMs: Int = HikUvcConstants.BULK_TRANSFER_CAP_MS,
        shouldAbort: () -> Boolean = { false },
    ): ByteArray?
    fun hasPartialPayload(): Boolean
    fun resetBulkCarry(reason: String? = null)
    /** Reset UVC FID state at stream arm (libuvc stream start). */
    fun primeUvcFrameAssembly()
    fun releaseClaimedInterfaces(closeBulk: Boolean = true): List<Int>
    fun closeHandle()
    fun cancelBulkIn(
        drainAfterCancel: Boolean = true,
        cancelWaitMs: Int = HikShutdownExperiment.CANCEL_WAIT_FULL_MS,
    ): HikUsbConnection.BulkCancelResult
    fun resetBulkRequest()
    fun startNativeBulkIfNeeded(endpointAddress: Int)
    /** libusb StopChannel; returns elapsed ms or null if not native. */
    fun stopNativeChannel(vsInterface: Int): Long?
    /** Reference leave: alt-0 + libusb release; returns elapsed ms or null if not native. */
    fun stopNativeChannelReference(vsInterface: Int, claimedIfaces: IntArray): Long? = null
    /** Blocking read for tests/tools. */
    fun bulkReadSuperFrame(
        endpointAddress: Int,
        timeoutMs: Int = HikUvcConstants.bulkSteadyTimeoutMs(),
    ): ByteArray

    fun flushBulkEndpoint(
        endpointAddress: Int,
        deadlineMs: Long = HikUvcConstants.BULK_SHUTDOWN_FLUSH_MS,
        emptyStreakLimit: Int = HikUvcConstants.BULK_SHUTDOWN_FLUSH_EMPTY_STREAK,
        perXferMs: Int = HikUvcConstants.BULK_SHUTDOWN_TRANSFER_MS,
    ): HikUsbConnection.BulkFlushResult

    /** Raw UVC-assembled payload waiting for display (no validation). */
    fun consumeRawWireDisplay(): ByteArray?
}
