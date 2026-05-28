package com.vilos.irpanoview.camera.hik

import java.util.concurrent.ConcurrentHashMap

data class HikDisplayTelemetrySnapshot(
    val busPath: String,
    val publishSeq: Long,
    val publishFingerprint: Int,
    val uiPostSeq: Long,
    val uiPostFingerprint: Int,
    val viewUpdateSeq: Long,
    val viewUpdateFingerprint: Int,
    val viewDrawSeq: Long,
    val viewDrawFingerprint: Int,
    val publishAgeMs: Long,
    val uiPostAgeMs: Long,
    val viewUpdateAgeMs: Long,
    val viewDrawAgeMs: Long,
    val staleUpdateStreak: Int,
    val staleDrawStreak: Int,
    val staleContentActive: Boolean,
    val seqGapActive: Boolean,
    val dynamicCompOffsetC: Double,
    val dynamicCompEnabled: Boolean,
    val dynamicCompAgeMs: Long,
)

/**
 * Per-bus frame flow telemetry for publish -> ui-post -> view-update -> view-draw.
 * This is debug-only state used by logs and tile diagnostics.
 */
object HikDisplayTelemetryRegistry {
    private data class Entry(
        var publishSeq: Long = 0L,
        var publishFingerprint: Int = 0,
        var publishAtMs: Long = 0L,
        var uiPostSeq: Long = 0L,
        var uiPostFingerprint: Int = 0,
        var uiPostAtMs: Long = 0L,
        var viewUpdateSeq: Long = 0L,
        var viewUpdateFingerprint: Int = 0,
        var viewUpdateAtMs: Long = 0L,
        var viewDrawSeq: Long = 0L,
        var viewDrawFingerprint: Int = 0,
        var viewDrawAtMs: Long = 0L,
        var staleUpdateStreak: Int = 0,
        var staleDrawStreak: Int = 0,
        var staleContentActive: Boolean = false,
        var seqGapActive: Boolean = false,
        var dynamicCompOffsetC: Double = 0.0,
        var dynamicCompEnabled: Boolean = false,
        var dynamicCompAtMs: Long = 0L,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun entry(busPath: String): Entry = entries.getOrPut(busPath) { Entry() }

    fun reportPublish(busPath: String, seq: Long, fingerprint: Int, nowMs: Long) {
        val e = entry(busPath)
        synchronized(e) {
            e.publishSeq = seq
            e.publishFingerprint = fingerprint
            e.publishAtMs = nowMs
        }
    }

    fun reportUiPost(busPath: String, seq: Long, fingerprint: Int, nowMs: Long) {
        val e = entry(busPath)
        synchronized(e) {
            e.uiPostSeq = seq
            e.uiPostFingerprint = fingerprint
            e.uiPostAtMs = nowMs
        }
    }

    fun reportViewUpdate(
        busPath: String,
        seq: Long,
        fingerprint: Int,
        nowMs: Long,
        staleUpdateStreak: Int,
        staleContentActive: Boolean,
    ) {
        val e = entry(busPath)
        synchronized(e) {
            e.viewUpdateSeq = seq
            e.viewUpdateFingerprint = fingerprint
            e.viewUpdateAtMs = nowMs
            e.staleUpdateStreak = staleUpdateStreak
            e.staleContentActive = staleContentActive
        }
    }

    fun reportViewDraw(
        busPath: String,
        seq: Long,
        fingerprint: Int,
        nowMs: Long,
        staleDrawStreak: Int,
    ) {
        val e = entry(busPath)
        synchronized(e) {
            e.viewDrawSeq = seq
            e.viewDrawFingerprint = fingerprint
            e.viewDrawAtMs = nowMs
            e.staleDrawStreak = staleDrawStreak
        }
    }

    fun reportSeqGap(busPath: String, active: Boolean) {
        val e = entry(busPath)
        synchronized(e) {
            e.seqGapActive = active
        }
    }

    fun reportDynamicComp(busPath: String, offsetC: Double, enabled: Boolean, nowMs: Long) {
        val e = entry(busPath)
        synchronized(e) {
            e.dynamicCompOffsetC = offsetC
            e.dynamicCompEnabled = enabled
            e.dynamicCompAtMs = nowMs
        }
    }

    fun snapshot(busPath: String, nowMs: Long = System.currentTimeMillis()): HikDisplayTelemetrySnapshot? {
        val e = entries[busPath] ?: return null
        synchronized(e) {
            return HikDisplayTelemetrySnapshot(
                busPath = busPath,
                publishSeq = e.publishSeq,
                publishFingerprint = e.publishFingerprint,
                uiPostSeq = e.uiPostSeq,
                uiPostFingerprint = e.uiPostFingerprint,
                viewUpdateSeq = e.viewUpdateSeq,
                viewUpdateFingerprint = e.viewUpdateFingerprint,
                viewDrawSeq = e.viewDrawSeq,
                viewDrawFingerprint = e.viewDrawFingerprint,
                publishAgeMs = age(nowMs, e.publishAtMs),
                uiPostAgeMs = age(nowMs, e.uiPostAtMs),
                viewUpdateAgeMs = age(nowMs, e.viewUpdateAtMs),
                viewDrawAgeMs = age(nowMs, e.viewDrawAtMs),
                staleUpdateStreak = e.staleUpdateStreak,
                staleDrawStreak = e.staleDrawStreak,
                staleContentActive = e.staleContentActive,
                seqGapActive = e.seqGapActive,
                dynamicCompOffsetC = e.dynamicCompOffsetC,
                dynamicCompEnabled = e.dynamicCompEnabled,
                dynamicCompAgeMs = age(nowMs, e.dynamicCompAtMs),
            )
        }
    }

    private fun age(nowMs: Long, eventMs: Long): Long =
        if (eventMs > 0L) (nowMs - eventMs).coerceAtLeast(0L) else -1L
}
