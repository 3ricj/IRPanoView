package com.vilos.irpanoview.camera.hik

import android.content.Context
import android.graphics.Bitmap
import com.vilos.irpanoview.util.UvcDebugLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Timed A/B observation for enhancement matrix tests: 2 samples per camera per profile,
 * spaced [SAMPLE_GAP_MS] apart (default 5 s).
 *
 * Output: files/logs/enhancement-matrix-jet/{profile}/{bus}/sample_{0,1}_*
 */
object HikEnhancementObservationPlan {

    /** Timed observation for enhancement matrix tests — off during normal quadview. */
    const val ENABLED = false

    /** Host false-color for matrix PNGs; fixed so UI palette changes do not affect observations. */
    val CAPTURE_PALETTE = com.vilos.irpanoview.model.ThermalColorPalette.Jet

    /** Separate from prior runs that may have used a different UI palette. */
    const val OUTPUT_DIR = "enhancement-matrix-jet"

    /** Wait after first decoded frame before sample 0 (NUC / black-ref settle). */
    const val BOOTSTRAP_MS = 20_000L
    const val SAMPLE_GAP_MS = 5_000L
    const val SAMPLES_PER_CAMERA = 2

    private val tsFile = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val executor = Executors.newSingleThreadExecutor()
    private val state = mutableMapOf<String, CameraState>()

    data class CameraState(
        var streamStartMs: Long = 0L,
        var samplesSaved: Int = 0,
        var firstSampleMs: Long = 0L,
    )

    fun resetAll() {
        state.clear()
    }

    fun resetCamera(busPath: String) {
        state.remove(sanitize(busPath))
    }

    fun profileId(): String = HikEnhancementProfileSource.activeProfile().id

    /**
     * Call on each decoded frame. Saves raw super-frame + sidecar when timing gates pass.
     */
    fun maybeObservation(
        context: Context,
        busPath: String,
        frame: ByteArray,
        frameIndex: Int,
        bitmap: Bitmap?,
        meta: HikStreamProbe.FrameMeta?,
    ) {
        if (!ENABLED) return
        val tag = sanitize(busPath)
        val cam = state.getOrPut(tag) { CameraState() }
        if (cam.samplesSaved >= SAMPLES_PER_CAMERA) return

        val now = System.currentTimeMillis()
        if (cam.streamStartMs == 0L) {
            cam.streamStartMs = now
        }
        if (now - cam.streamStartMs < BOOTSTRAP_MS) return

        val takeSample = when (cam.samplesSaved) {
            0 -> true
            1 -> now - cam.firstSampleMs >= SAMPLE_GAP_MS
            else -> false
        }
        if (!takeSample) return

        val sampleIdx = cam.samplesSaved
        if (sampleIdx == 0) cam.firstSampleMs = now
        cam.samplesSaved++

        val profile = profileId()
        val stamp = tsFile.format(Date())
        val copyBmp = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
        executor.execute {
            runCatching {
                val root = File(
                    context.getExternalFilesDir(null),
                    "logs/$OUTPUT_DIR/$profile/$tag",
                ).apply { mkdirs() }
                val base = "sample_${sampleIdx}_${stamp}_f$frameIndex"
                File(root, "$base.bin").writeBytes(frame)
                File(root, "$base.txt").writeText(formatSidecar(busPath, frameIndex, sampleIdx, now, meta))
                copyBmp?.let { bmp ->
                    java.io.FileOutputStream(File(root, "$base.png")).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 92, out)
                    }
                    bmp.recycle()
                }
                UvcDebugLogger.log(
                    context,
                    tag,
                    "enhancement observation profile=$profile sample=$sampleIdx/$SAMPLES_PER_CAMERA -> $root/$base.*",
                )
            }.onFailure {
                UvcDebugLogger.log(context, tag, "enhancement observation failed: ${it.message}")
            }
        }
    }

    private fun formatSidecar(
        busPath: String,
        frameIndex: Int,
        sampleIdx: Int,
        wallMs: Long,
        meta: HikStreamProbe.FrameMeta?,
    ): String = buildString {
        appendLine("# enhancement-matrix observation")
        appendLine("profile=${profileId()}")
        appendLine("hostPalette=${CAPTURE_PALETTE.name}")
        appendLine("busPath=$busPath")
        appendLine("sampleIndex=$sampleIdx bootstrapMs=$BOOTSTRAP_MS gapMs=$SAMPLE_GAP_MS")
        appendLine("streamAgeMs=${wallMs - (state[sanitize(busPath)]?.streamStartMs ?: wallMs)}")
        appendLine("frameIndex=$frameIndex wallMs=$wallMs")
        appendLine("enhancement=${HikImageEnhancement.formatKeyBytes(HikEnhancementProfileSource.lastSetWire)}")
        if (meta != null) {
            appendLine("centerC=${"%.2f".format(meta.centerCelsius)}")
            appendLine("alignOffset=${meta.alignOffset} reassembly=${meta.reassembly}")
        }
    }

    private fun sanitize(busPath: String): String =
        busPath.trim('/').replace('/', '-').ifEmpty { "unknown" }
}
