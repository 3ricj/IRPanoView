package com.vilos.irpanoview.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class ThermalStreamReceiver(
    private val scope: CoroutineScope,
    private val port: Int = PiConnectionManager.UDP_PORT,
) {
    private val _latestFrame = MutableStateFlow<ThermalFrameParser.ParsedFrame?>(null)
    val latestFrame: StateFlow<ThermalFrameParser.ParsedFrame?> = _latestFrame.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private var job: Job? = null
    private var lastSequence = -1

    data class StreamStats(
        val fps: Double = 0.0,
        val lastSequence: Int = -1,
        val drops: Int = 0,
        val connected: Boolean = false,
    )

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            runLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _stats.value = StreamStats()
    }

    private suspend fun runLoop() {
        val buffer = ByteArray(512 * 1024)
        var frames = 0
        var drops = 0
        val startMs = System.currentTimeMillis()
        var lastReportMs = startMs

        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 500
            socket.bind(InetSocketAddress(port))
            _stats.value = _stats.value.copy(connected = true)
            while (scope.isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val parsed = ThermalFrameParser.parse(
                        buffer.copyOfRange(0, packet.length),
                    ) ?: continue
                    if (parsed.sequence <= lastSequence) {
                        drops++
                        continue
                    }
                    lastSequence = parsed.sequence
                    _latestFrame.value = parsed
                    frames++
                } catch (_: SocketTimeoutException) {
                    // idle
                }

                val now = System.currentTimeMillis()
                if (now - lastReportMs >= 1000L) {
                    val elapsed = (now - lastReportMs).coerceAtLeast(1L)
                    _stats.value = StreamStats(
                        fps = frames * 1000.0 / elapsed,
                        lastSequence = lastSequence,
                        drops = drops,
                        connected = true,
                    )
                    frames = 0
                    lastReportMs = now
                }
            }
        }
        _stats.value = StreamStats()
    }
}
