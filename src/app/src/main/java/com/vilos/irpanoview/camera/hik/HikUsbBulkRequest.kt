package com.vilos.irpanoview.camera.hik

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.content.Context
import com.vilos.irpanoview.util.UvcDebugLogger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancellable bulk IN via [UsbRequest] (libusb_cancel_transfer analog).
 *
 * Uses blocking [UsbDeviceConnection.requestWait] — timed wait throws [android.os.TimeoutException]
 * on this OEM. Stop unblocks via [UsbRequest.cancel].
 */
internal class HikUsbBulkRequest(
    private val connection: UsbDeviceConnection,
) {

    data class CancelResult(
        val cancelCalled: Int,
        val drained: Int,
        val elapsedMs: Long,
        val inFlightAfter: Boolean,
    )

    private class Slot {
        var request: UsbRequest? = null
        var buffer: ByteBuffer? = null
        var queued: Boolean = false
    }

    private val lock = Any()
    private val slot = Slot()
    private var boundEndpoint: UsbEndpoint? = null
    private val inFlight = AtomicBoolean(false)

    fun primePipeline(endpoint: UsbEndpoint, logContext: Context? = null, busPath: String? = null) {
        bindEndpoint(endpoint)
        synchronized(lock) {
            if (slot.queued) return
            if (!queueSlotLocked(endpoint)) {
                throw HikProtocolException(
                    "UsbRequest.queue failed during prime ep=${endpoint.address.toString(16)} " +
                        "len=${queueLength(endpoint)} maxPkt=${endpoint.maxPacketSize}",
                )
            }
            inFlight.set(true)
            logContext?.let { ctx ->
                busPath?.let { path ->
                    UvcDebugLogger.log(
                        ctx,
                        path,
                        "BULK_ASYNC prime ep=${endpoint.address.toString(16)} len=${queueLength(endpoint)} " +
                            "maxPkt=${endpoint.maxPacketSize}",
                    )
                }
            }
        }
    }

    fun transfer(
        endpoint: UsbEndpoint,
        chunk: ByteArray,
        @Suppress("UNUSED_PARAMETER") timeoutMs: Int,
        shouldAbort: () -> Boolean,
    ): Int {
        if (shouldAbort()) return 0
        bindEndpoint(endpoint)
        synchronized(lock) {
            if (!slot.queued && !queueSlotLocked(endpoint)) {
                return -1
            }
        }
        inFlight.set(true)
        val completed = safeRequestWaitBlocking()
        synchronized(lock) {
            inFlight.set(false)
            if (completed == null) {
                return 0
            }
            if (completed != slot.request) {
                return 0
            }
            slot.queued = false
            val bytesRead = slot.buffer?.position()?.coerceAtLeast(0) ?: 0
            if (shouldAbort()) {
                return 0
            }
            if (bytesRead > 0) {
                slot.buffer?.rewind()
                val copy = minOf(bytesRead, chunk.size)
                slot.buffer?.get(chunk, 0, copy)
                if (!shouldAbort()) {
                    queueSlotLocked(endpoint)
                    inFlight.set(true)
                }
                return copy
            }
            if (!shouldAbort()) {
                queueSlotLocked(endpoint)
                inFlight.set(true)
            }
            return 0
        }
    }

    fun cancelOnly(): CancelResult {
        val startMs = System.currentTimeMillis()
        var cancelCalled = 0
        synchronized(lock) {
            val req = slot.request
            if (req != null && slot.queued && req.cancel()) {
                cancelCalled++
            }
            slot.queued = false
        }
        inFlight.set(false)
        return CancelResult(
            cancelCalled = cancelCalled,
            drained = 0,
            elapsedMs = System.currentTimeMillis() - startMs,
            inFlightAfter = false,
        )
    }

    fun cancelAndDrain(): CancelResult {
        val startMs = System.currentTimeMillis()
        var cancelCalled = 0
        synchronized(lock) {
            val req = slot.request
            if (req != null && slot.queued && req.cancel()) {
                cancelCalled++
            }
            slot.queued = false
        }
        var drained = 0
        val deadline = startMs + HikUvcConstants.BULK_CANCEL_DRAIN_MS
        while (System.currentTimeMillis() < deadline) {
            val completed = safeRequestWait(0L) ?: break
            if (completed == slot.request) {
                drained++
                synchronized(lock) {
                    slot.queued = false
                }
            }
        }
        inFlight.set(false)
        return CancelResult(
            cancelCalled = cancelCalled,
            drained = drained,
            elapsedMs = System.currentTimeMillis() - startMs,
            inFlightAfter = inFlight.get(),
        )
    }

    fun close() {
        synchronized(lock) {
            slot.request?.cancel()
            slot.queued = false
            closeSlotLocked()
            boundEndpoint = null
            inFlight.set(false)
        }
        repeat(8) {
            safeRequestWait(0L) ?: return@repeat
        }
    }

    /** Lenovo TB-Q706F: even [requestWait(0)] can throw [android.os.TimeoutException]. */
    private fun safeRequestWait(timeoutMs: Long): UsbRequest? =
        runCatching { connection.requestWait(timeoutMs) }.getOrNull()

    private fun safeRequestWaitBlocking(): UsbRequest? =
        runCatching { connection.requestWait() }.getOrNull()

    private fun bindEndpoint(endpoint: UsbEndpoint) {
        synchronized(lock) {
            if (boundEndpoint?.address == endpoint.address &&
                slot.request?.endpoint?.address == endpoint.address
            ) {
                return
            }
            closeSlotLocked()
            val req = UsbRequest()
            if (!req.initialize(connection, endpoint)) {
                throw HikProtocolException(
                    "UsbRequest.initialize failed ep=${endpoint.address.toString(16)}",
                )
            }
            slot.request = req
            slot.buffer = ByteBuffer.allocateDirect(16_384)
            slot.queued = false
            boundEndpoint = endpoint
        }
    }

    private fun queueSlotLocked(endpoint: UsbEndpoint): Boolean {
        val req = slot.request ?: return false
        val buffer = slot.buffer ?: return false
        val want = queueLength(endpoint)
        buffer.clear()
        buffer.limit(want)
        if (!req.queue(buffer)) {
            return false
        }
        slot.queued = true
        return true
    }

    private fun closeSlotLocked() {
        slot.request?.close()
        slot.request = null
        slot.buffer = null
        slot.queued = false
    }

    private fun queueLength(endpoint: UsbEndpoint): Int =
        endpoint.maxPacketSize.coerceIn(64, 16_384)
}
