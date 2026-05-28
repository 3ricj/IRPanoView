package com.vilos.irpanoview.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves decoded preview PNGs (+ optional raw head) under app external storage for adb pull.
 *
 * Pull everything:
 * `adb pull "/sdcard/Android/data/com.vilos.irpanoview/files/logs/" "./irpanoview-logs/"`
 */
object PreviewSnapshotLogger {

    private val tsFile = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val tsLog = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val executor = Executors.newSingleThreadScheduledExecutor()

    private val lastSaveMs = mutableMapOf<String, Long>()
    private val savedRawHead = mutableSetOf<String>()
    private val savedSizeGateReject = mutableSetOf<String>()
    private val streamingStartedMs = mutableMapOf<String, Long>()
    private val jumboWindowStartedMs = mutableMapOf<String, Long>()
    private val footerObservationStates = mutableMapOf<String, FooterObservationState>()
    private val jumboHeaderObservationStates = mutableMapOf<String, JumboHeaderObservationState>()

    private data class FooterObservationState(
        val footer2Out: FileOutputStream,
        var frameCount: Int = 0,
    ) {
        fun closeQuietly() {
            runCatching { footer2Out.close() }
        }
    }

    private data class JumboHeaderObservationState(
        val headerOut: FileOutputStream,
        val indexOut: FileOutputStream,
        var frameCount: Int = 0,
    ) {
        fun closeQuietly() {
            runCatching { headerOut.close() }
            runCatching { indexOut.close() }
        }
    }

    fun logsRoot(context: Context): File =
        File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }

    fun snapshotsDir(context: Context): File =
        File(logsRoot(context), "snapshots").apply { mkdirs() }

    fun pullHint(context: Context): String {
        val dir = logsRoot(context).absolutePath
        return "Debug: adb pull \"$dir\" \"./irpanoview-logs/\""
    }

    /** Session-scoped footer2 observation requires no app-start windowing. */
    fun markAppStarted(context: Context) {
        UvcDebugLogger.log(context.applicationContext, "footer-observation", "footer2 session observation active")
    }

    /** Call after a successful decode; throttles to [intervalMs] per camera. */
    fun onDecodedFrame(
        context: Context,
        busPath: String,
        bitmap: Bitmap,
        reason: String,
        intervalMs: Long = SNAPSHOT_INTERVAL_MS,
    ) {
        val tag = sanitizeBusPath(busPath)
        val now = System.currentTimeMillis()
        val last = lastSaveMs[tag] ?: 0L
        if (reason != REASON_FIRST && reason != REASON_MANUAL && now - last < intervalMs) return
        lastSaveMs[tag] = now

        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        executor.execute {
            try {
                saveSnapshotSet(context, tag, busPath, copy, reason)
            } catch (e: Exception) {
                UvcDebugLogger.log(context, tag, "snapshot failed: ${e.message}")
            } finally {
                copy.recycle()
            }
        }
    }

    /** Legacy wire snapshots are disabled; keep only footer2 binary observation. */
    fun needsWireObservation(_busPath: String): Boolean = false

    /** Legacy wire snapshots are disabled; keep only footer2 binary observation. */
    fun needsWireObservation10s(_busPath: String): Boolean = false

    fun shouldRetainUvcWirePayload(_busPath: String, rawWireDisplay: Boolean): Boolean =
        rawWireDisplay

    /** Footer2 slices are retained for the full stream session. */
    fun shouldRetainFooterSlices(busPath: String): Boolean {
        val tag = sanitizeBusPath(busPath)
        return streamingStartedMs.containsKey(tag)
    }

    /** Jumbo header slices are retained and gated by the first-5-minute stream window. */
    fun shouldRetainJumboHeaderSlices(busPath: String): Boolean {
        val tag = sanitizeBusPath(busPath)
        return jumboWindowStartedMs.containsKey(tag)
    }

    /** Collect footer2 from each UVC frame for the full stream session. */
    fun onUvcFooter2(context: Context, busPath: String, footer2: ByteArray) {
        val tag = sanitizeBusPath(busPath)
        if (streamingStartedMs[tag] == null) return
        val state = footerObservationStates.getOrPut(tag) {
            openFooterObservation(context, tag)
        }
        state.footer2Out.write(footer2)
        state.frameCount++
    }

    /** Collect raw jumbo header bytes during the first 5 minutes after stream start. */
    fun onUvcJumboHeader(context: Context, busPath: String, header: ByteArray) {
        val tag = sanitizeBusPath(busPath)
        val started = jumboWindowStartedMs[tag] ?: return
        val elapsedMs = System.currentTimeMillis() - started
        if (elapsedMs !in 0..JUMBO_HEADER_CAPTURE_WINDOW_MS) return

        val state = jumboHeaderObservationStates.getOrPut(tag) {
            openJumboHeaderObservation(context, tag)
        }
        state.headerOut.write(header)
        state.frameCount++
        state.indexOut.write(
            "${state.frameCount},$elapsedMs,${header.size}\n".toByteArray(Charsets.UTF_8),
        )
    }

    /** Record stream start for session-scoped footer2 observation. */
    fun markHikStreamingStarted(busPath: String) {
        val tag = sanitizeBusPath(busPath)
        streamingStartedMs.putIfAbsent(tag, System.currentTimeMillis())
        jumboWindowStartedMs[tag] = System.currentTimeMillis()
        jumboHeaderObservationStates.remove(tag)?.closeQuietly()
    }

    /** Legacy wire snapshots disabled by design. */
    fun onUvcWireFrame(_context: Context, _busPath: String, _payload: ByteArray) = Unit

    /** Legacy wire snapshots disabled by design. */
    fun onUvcWireFrame10s(_context: Context, _busPath: String, _payload: ByteArray) = Unit

    /**
     * Saves one oversize size-gate reject payload per camera for offline inspection.
     * File lands under logs/uvc-wire/<camera>/ and is picked up by the existing pull script.
     */
    fun onSizeGateRejectedWireFrame(
        context: Context,
        busPath: String,
        payload: ByteArray,
        expectedBytes: Int,
    ) {
        val tag = sanitizeBusPath(busPath)
        if (!savedSizeGateReject.add(tag)) return
        executor.execute {
            runCatching {
                val dir = File(logsRoot(context), "uvc-wire/$tag").apply { mkdirs() }
                val stamp = tsFile.format(Date())
                val stem = "${tag}-size-gate-reject-${payload.size}B-$stamp"
                val bin = File(dir, "$stem.bin")
                FileOutputStream(bin).use { out -> out.write(payload) }
                val txt = File(dir, "$stem.txt")
                txt.writeText(
                    buildString {
                        appendLine("# size gate reject sample")
                        appendLine("bus=$busPath")
                        appendLine("bytes=${payload.size}")
                        appendLine("expected=$expectedBytes")
                        appendLine("delta=${payload.size - expectedBytes}")
                        appendLine("file=${bin.name}")
                        appendLine()
                        appendLine("# head (256 bytes)")
                        appendLine(hexDump(payload.copyOf(minOf(256, payload.size))))
                    },
                )
                UvcDebugLogger.log(
                    context,
                    tag,
                    "size-gate sample saved → uvc-wire/$tag/${bin.name} bytes=${payload.size}",
                )
            }
        }
    }

    private fun openFooterObservation(context: Context, tag: String): FooterObservationState {
        val dir = File(logsRoot(context), "uvc-wire/$tag").apply { mkdirs() }
        val footer2File = File(dir, "footer2_session.bin")
        return FooterObservationState(
            footer2Out = FileOutputStream(footer2File),
        )
    }

    private fun openJumboHeaderObservation(context: Context, tag: String): JumboHeaderObservationState {
        val dir = File(logsRoot(context), "uvc-wire/$tag").apply { mkdirs() }
        val headerFile = File(dir, "jumbo_header_5min.bin")
        val indexFile = File(dir, "jumbo_header_5min.index.txt")
        val indexOut = FileOutputStream(indexFile).also { out ->
            out.write("frameIndex,elapsedMs,headerBytes\n".toByteArray(Charsets.UTF_8))
        }
        return JumboHeaderObservationState(
            headerOut = FileOutputStream(headerFile),
            indexOut = indexOut,
        )
    }

    /** Dump first bytes of a raw frame once per camera (Hik super-frame or YUYV). */
    fun onRawBytes(context: Context, busPath: String, frame: ByteArray, label: String) {
        val tag = sanitizeBusPath(busPath)
        if (!savedRawHead.add("$tag-hik")) return
        executor.execute {
            runCatching {
                val dir = snapshotsDir(context)
                val f = File(dir, "$tag-hik-raw-head.txt")
                val n = minOf(256, frame.size)
                f.writeText(
                    buildString {
                        appendLine("# $label $busPath bytes=${frame.size}")
                        appendLine(hexDump(frame.copyOf(n)))
                        appendLine("# pull: ${dir.absolutePath}")
                    },
                )
                updateIndex(context)
            }
        }
    }

    /** Dump first bytes of YUYV frame once per camera. */
    fun onRawFrame(context: Context, busPath: String, frame: ByteBuffer, width: Int, height: Int) {
        val tag = sanitizeBusPath(busPath)
        if (!savedRawHead.add(tag)) return
        executor.execute {
            runCatching {
                val dir = snapshotsDir(context)
                val f = File(dir, "$tag-raw-head.txt")
                val pos = frame.position()
                val n = minOf(256, frame.remaining())
                val bytes = ByteArray(n)
                frame.get(bytes)
                frame.position(pos)
                f.writeText(
                    buildString {
                        appendLine("# raw YUYV head $busPath ${width}x$height bytes=${frame.remaining()}")
                        appendLine(hexDump(bytes))
                        appendLine("# pull: ${dir.absolutePath}")
                    },
                )
                updateIndex(context)
            }
        }
    }

    fun requestManualSnapshot(context: Context, busPath: String, bitmap: Bitmap?) {
        if (bitmap == null) return
        onDecodedFrame(context, busPath, bitmap, REASON_MANUAL, intervalMs = 0L)
    }

    fun resetCamera(busPath: String) {
        val tag = sanitizeBusPath(busPath)
        savedRawHead.remove(tag)
        savedRawHead.remove("$tag-hik")
        savedSizeGateReject.remove(tag)
        footerObservationStates.remove(tag)?.closeQuietly()
        jumboHeaderObservationStates.remove(tag)?.closeQuietly()
        streamingStartedMs.remove(tag)
        jumboWindowStartedMs.remove(tag)
        lastSaveMs.remove(tag)
    }

    private fun saveSnapshotSet(
        context: Context,
        tag: String,
        busPath: String,
        composite: Bitmap,
        reason: String,
    ) {
        val dir = snapshotsDir(context)
        val stamp = tsFile.format(Date())

        writePng(File(dir, "$tag-latest.png"), composite)
        writePng(File(dir, "$tag-$stamp.png"), composite)
        trimHistory(dir, tag, MAX_HISTORY)

        UvcDebugLogger.log(context, tag, "snapshot $reason → $tag-latest.png (${composite.width}x${composite.height})")
        updateIndex(context, busPath, tag, reason, composite.width, composite.height)
    }

    private fun updateIndex(
        context: Context,
        busPath: String? = null,
        tag: String? = null,
        reason: String? = null,
        width: Int = 0,
        height: Int = 0,
    ) {
        val dir = snapshotsDir(context)
        val files = dir.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }.orEmpty()
        val index = File(logsRoot(context), "snapshots-index.txt")
        index.writeText(
            buildString {
                appendLine("IRPanoView preview snapshots")
                appendLine("Updated: ${tsLog.format(Date())}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Pull: adb pull \"${logsRoot(context).absolutePath}\" \"./irpanoview-logs/\"")
                appendLine()
                if (busPath != null && tag != null) {
                    appendLine("Last write: $tag $reason ${width}x$height")
                    appendLine("  snapshots/$tag-latest.png")
                    appendLine()
                }
                appendLine("PNG files (${files.size}):")
                files.take(20).forEach { f ->
                    appendLine("  snapshots/${f.name}  (${f.length()} bytes)")
                }
            },
        )
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
        }
    }

    private fun trimHistory(dir: File, tag: String, keep: Int) {
        dir.listFiles { f -> f.name.startsWith("$tag-") && f.name.endsWith(".png") && !f.name.endsWith("-latest.png") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    private fun sanitizeBusPath(deviceName: String): String =
        deviceName.trim('/').replace('/', '-').ifEmpty { "unknown" }

    private fun hexDump(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            sb.append(String.format(Locale.US, "%04x: ", i))
            for (j in 0 until 16) {
                if (i + j < bytes.size) sb.append(String.format(Locale.US, "%02x ", bytes[i + j]))
                else sb.append("   ")
            }
            sb.append('\n')
            i += 16
        }
        return sb.toString()
    }

    private const val SNAPSHOT_INTERVAL_MS = 5_000L
    private const val MAX_HISTORY = 3
    private const val JUMBO_HEADER_CAPTURE_WINDOW_MS = 5 * 60 * 1000L
    const val REASON_FIRST = "first-frame"
    const val REASON_PERIODIC = "periodic"
    const val REASON_MANUAL = "manual"
}
