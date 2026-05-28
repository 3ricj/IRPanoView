package com.vilos.irpanoview.camera.hik

import android.content.Context
import com.vilos.irpanoview.util.PreviewSnapshotLogger
import com.vilos.irpanoview.util.UvcDebugLogger
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Observations raw UVC wire frames + stats for adb pull / offline analysis. */
object HikStreamProbe {

    /** Observations raw wire frames for adb pull — off during live streaming. */
    const val ENABLED = false

    private val executor = Executors.newSingleThreadExecutor()
    private val savedCount = mutableMapOf<String, Int>()
    private val loggedAlign = mutableSetOf<String>()

    const val MAX_CAPTURES = 4

    data class FrameMeta(
        val frameIndex: Int,
        val ringBytesBefore: Int,
        val alignOffset: Int,
        val alignScore: Double,
        val centerCelsius: Double,
        val chunkSizes: List<Int>,
        val reassembly: String = "uvc_eof",
        val uvcPackets: Int = 0,
    )

    fun maybeObservation(
        context: Context,
        busPath: String,
        frame: ByteArray,
        meta: FrameMeta,
    ) {
        if (!ENABLED) return
        val tag = sanitize(busPath)
        val n = savedCount.getOrDefault(tag, 0)
        if (n >= MAX_CAPTURES) return
        savedCount[tag] = n + 1

        if (meta.alignOffset > 0 && loggedAlign.add(tag)) {
            UvcDebugLogger.log(
                context,
                busPath,
                "Hik align offset=${meta.alignOffset} score=${"%.1f".format(meta.alignScore)} " +
                    "centerC=${"%.2f".format(meta.centerCelsius)}",
            )
        }

        executor.execute {
            runCatching {
                val dir = File(PreviewSnapshotLogger.logsRoot(context), "hik-frames-uvc/$tag").apply { mkdirs() }
                val idx = meta.frameIndex.toString().padStart(3, '0')
                File(dir, "frame_$idx.bin").writeBytes(frame)
                File(dir, "frame_$idx.txt").writeText(formatStats(busPath, frame, meta))
                if (n == 0) {
                    UvcDebugLogger.log(context, busPath, "Hik probe → ${dir.absolutePath}")
                }
            }.onFailure {
                UvcDebugLogger.log(context, busPath, "Hik probe write failed: ${it.message}")
            }
        }
    }

    fun reset(busPath: String) {
        val tag = sanitize(busPath)
        savedCount.remove(tag)
        loggedAlign.remove(tag)
    }

    private fun formatStats(busPath: String, frame: ByteArray, meta: FrameMeta): String = buildString {
        appendLine("# Hik thermal probe $busPath")
        appendLine("frameIndex=${meta.frameIndex} bytes=${frame.size}")
        appendLine("ringBefore=${meta.ringBytesBefore} alignOffset=${meta.alignOffset} " +
            "alignScore=${"%.2f".format(meta.alignScore)} centerC=${"%.2f".format(meta.centerCelsius)}")
        appendLine("reassembly=${meta.reassembly} uvcPackets=${meta.uvcPackets}")
        appendLine("bulkChunks=${meta.chunkSizes}")
        appendLine("prefix64=${hex(frame, 0, 64)}")
        appendLine("radioHead32=${hex(frame, HikUvcWireLayout.RADIO_OFFSET, 32)}")
        val cx = HikTherm.GRID_WIDTH / 2
        appendLine("row95_col128=${rowSample(frame, 95, cx - 4, cx + 4)}")
        appendLine("row96_col128=${rowSample(frame, 96, cx - 4, cx + 4)}")
        appendLine("row97_col128=${rowSample(frame, 97, cx - 4, cx + 4)}")
    }

    private fun rowSample(frame: ByteArray, y: Int, x0: Int, x1: Int): String {
        val parts = mutableListOf<String>()
        for (x in x0..x1) {
            if (x in 0 until HikTherm.GRID_WIDTH) {
                val c = HikTherm.celsiusAtPixel(frame, x, y)
                parts.add("${"%.1f".format(c)}")
            }
        }
        return parts.joinToString(", ")
    }

    private fun hex(frame: ByteArray, off: Int, len: Int): String {
        val end = minOf(off + len, frame.size)
        return buildString {
            var i = off
            while (i < end) {
                append(String.format(Locale.US, "%02x", frame[i]))
                i++
            }
        }
    }

    private fun sanitize(busPath: String): String =
        busPath.trim('/').replace('/', '-').ifEmpty { "unknown" }
}
