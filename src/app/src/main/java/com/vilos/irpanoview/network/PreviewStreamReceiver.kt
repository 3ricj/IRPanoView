package com.vilos.irpanoview.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency live preview: TCP IRPR-framed IRP8 over IPv4.
 * Single reader job — start() is idempotent for the same host.
 */
class PreviewStreamReceiver(
    private val scope: CoroutineScope,
    private val port: Int = PiConnectionManager.PREVIEW_TCP_PORT,
) {
    data class StreamStats(
        val fps: Double = 0.0,
        val lastSequence: Int = -1,
        val drops: Int = 0,
        val connected: Boolean = false,
        val lastError: String? = null,
    )

    private val _latestFrame = MutableStateFlow<PreviewFrameParser.ParsedFrame?>(null)
    val latestFrame: StateFlow<PreviewFrameParser.ParsedFrame?> = _latestFrame.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private var job: Job? = null
    private var host: String = PiConnectionManager.defaultPiHost()
    private val generation = AtomicInteger(0)

    fun start(host: String) {
        val normalized = PiConnectionManager.normalizeHost(host)
        synchronized(this) {
            if (job?.isActive == true && this.host == normalized) {
                Log.i(TAG, "start skipped — already running host=$normalized")
                return
            }
            Log.i(TAG, "start host=$normalized")
            this.host = normalized
            val gen = generation.incrementAndGet()
            job?.cancel()
            job = scope.launch(Dispatchers.IO) {
                runLoop(gen)
            }
        }
    }

    fun stop() {
        synchronized(this) {
            generation.incrementAndGet()
            job?.cancel()
            job = null
            _latestFrame.value = null
            _stats.value = StreamStats()
        }
    }

    private suspend fun runLoop(gen: Int) {
        var lastSequence = -1
        var drops = 0
        while (scope.isActive && generation.get() == gen) {
            var connectTarget = host
            try {
                connectTarget = PiConnectionManager.resolveIpv4Host(host)
                Log.i(TAG, "preview connect $connectTarget:$port gen=$gen")
                Socket().use { socket ->
                    if (generation.get() != gen) return
                    socket.tcpNoDelay = true
                    socket.soTimeout = 30_000
                    socket.connect(InetSocketAddress(connectTarget, port), 3_000)
                    if (generation.get() != gen) return
                    _stats.value = _stats.value.copy(connected = true, lastError = null)
                    val input = DataInputStream(socket.getInputStream())
                    var frames = 0
                    var lastReportMs = System.currentTimeMillis()
                    while (scope.isActive && generation.get() == gen) {
                        val packet = readIrprPacket(input) ?: break
                        val parsed = PreviewFrameParser.parse(packet) ?: continue
                        if (lastSequence >= 0 && parsed.sequence <= lastSequence) {
                            drops++
                        }
                        lastSequence = parsed.sequence
                        _latestFrame.value = parsed
                        frames++
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs >= 1_000L) {
                            val elapsed = (now - lastReportMs).coerceAtLeast(1L)
                            _stats.value = StreamStats(
                                fps = frames * 1000.0 / elapsed,
                                lastSequence = lastSequence,
                                drops = drops,
                                connected = true,
                                lastError = null,
                            )
                            frames = 0
                            lastReportMs = now
                        }
                    }
                }
            } catch (e: Exception) {
                if (!scope.isActive || generation.get() != gen) break
                val msg = "${e.javaClass.simpleName}: ${e.message ?: ""} → $connectTarget:$port"
                Log.w(TAG, msg)
                _stats.value = _stats.value.copy(connected = false, lastError = msg)
                delay(1_000)
            }
        }
        if (generation.get() == gen) {
            _stats.value = _stats.value.copy(connected = false)
        }
    }

    companion object {
        private const val TAG = "PreviewStream"
        const val IRPR_MAGIC = 0x52505249
        private const val MAX_PACKET_BYTES = 8 * 1024 * 1024

        fun readIrprPacket(input: InputStream): ByteArray? {
            val header = ByteArray(8)
            if (!readExact(input, header)) return null
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            if (magic != IRPR_MAGIC) {
                throw IOException("Bad IRPR magic 0x${magic.toString(16)}")
            }
            val length = buf.int
            if (length <= 0 || length > MAX_PACKET_BYTES) {
                throw IOException("Bad IRPR length $length")
            }
            val packet = ByteArray(length)
            if (!readExact(input, packet)) return null
            return packet
        }

        fun readExact(input: InputStream, buffer: ByteArray): Boolean {
            var offset = 0
            while (offset < buffer.size) {
                val n = try {
                    input.read(buffer, offset, buffer.size - offset)
                } catch (_: EOFException) {
                    return false
                }
                if (n < 0) return false
                offset += n
            }
            return true
        }
    }
}
