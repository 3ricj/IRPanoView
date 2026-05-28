package com.vilos.irpanoview.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.vilos.irpanoview.camera.hik.HikDisplayTelemetryRegistry
import com.vilos.irpanoview.util.UvcDebugLogger

/** Displays 256×192 thermal preview bitmap. */
class ThermalFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var bitmap: Bitmap? = null
    private var streamTag: String = "hik-display"

    private var lastUpdateMs = 0L
    private var lastDrawMs = 0L
    private var lastPostedAtMs = 0L
    private var updateFramesSinceLog = 0
    private var drawFramesSinceLog = 0
    private var lastUpdateFpsLogMs = 0L
    private var lastDrawFpsLogMs = 0L
    private var updateStallActive = false
    private var drawStallActive = false
    private var seqGapActive = false

    private var latestUpdateSeq = -1L
    private var latestUpdateFingerprint = 0
    private var lastDrawSeq = -1L
    private var lastDrawFingerprint = 0
    private var staleUpdateStreak = 0
    private var staleDrawStreak = 0
    private var staleContentActive = false

    private val stallWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val hasBitmap = bitmap != null
            if (lastUpdateMs > 0L) {
                val noUpdateMs = now - lastUpdateMs
                if (noUpdateMs >= STALL_THRESHOLD_MS) {
                    if (!updateStallActive) {
                        updateStallActive = true
                        logDisplay("display_stall_no_update noUpdateMs=$noUpdateMs hasBitmap=$hasBitmap")
                    }
                } else if (updateStallActive) {
                    updateStallActive = false
                    logDisplay("display_stall_no_update_recovered noUpdateMs=$noUpdateMs")
                }
            }

            if (lastDrawMs > 0L) {
                val noDrawMs = now - lastDrawMs
                if (noDrawMs >= STALL_THRESHOLD_MS) {
                    if (!drawStallActive) {
                        drawStallActive = true
                        logDisplay("display_stall_no_draw noDrawMs=$noDrawMs hasBitmap=$hasBitmap")
                    }
                } else if (drawStallActive) {
                    drawStallActive = false
                    logDisplay("display_stall_no_draw_recovered noDrawMs=$noDrawMs")
                }
            }

            val seqGap = if (latestUpdateSeq >= 0L && lastDrawSeq >= 0L) {
                latestUpdateSeq - lastDrawSeq
            } else {
                0L
            }
            if (seqGap >= SEQ_GAP_THRESHOLD && now - lastDrawMs >= SEQ_GAP_STALL_MS) {
                if (!seqGapActive) {
                    seqGapActive = true
                    HikDisplayTelemetryRegistry.reportSeqGap(streamTag, true)
                    logDisplay(
                        "display_seq_gap gap=$seqGap updateSeq=$latestUpdateSeq drawSeq=$lastDrawSeq " +
                            "noDrawMs=${now - lastDrawMs}",
                    )
                }
            } else if (seqGapActive) {
                seqGapActive = false
                HikDisplayTelemetryRegistry.reportSeqGap(streamTag, false)
                logDisplay("display_seq_recovered gap=$seqGap updateSeq=$latestUpdateSeq drawSeq=$lastDrawSeq")
            }
            postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /** Tie widget logs to camera bus path. */
    fun setDebugStreamTag(tag: String) {
        streamTag = tag.ifBlank { "hik-display" }
    }

    /** Legacy callers without frame identity metadata. */
    fun updateBitmap(newFrame: Bitmap) {
        updateBitmap(newFrame, frameSeq = -1L, frameFingerprint = 0, postedAtMs = 0L)
    }

    /** [newFrame] is owned by the camera worker — do not recycle here. */
    fun updateBitmap(
        newFrame: Bitmap,
        frameSeq: Long,
        frameFingerprint: Int,
        postedAtMs: Long,
    ) {
        val now = System.currentTimeMillis()
        lastUpdateMs = now
        lastPostedAtMs = postedAtMs
        updateFramesSinceLog++
        if (frameSeq >= 0L) {
            if (frameFingerprint == latestUpdateFingerprint && latestUpdateSeq >= 0L) {
                staleUpdateStreak++
            } else {
                if (staleContentActive) {
                    staleContentActive = false
                    logDisplay(
                        "display_stale_content_recovered updateSeq=$frameSeq drawSeq=$lastDrawSeq " +
                            "hash=${fingerprintHex(frameFingerprint)}",
                    )
                }
                staleUpdateStreak = 0
            }
            latestUpdateSeq = frameSeq
            latestUpdateFingerprint = frameFingerprint
        } else {
            staleUpdateStreak = 0
        }
        if (frameSeq >= 0L &&
            staleUpdateStreak >= STALE_CONTENT_UPDATE_STREAK_THRESHOLD &&
            !staleContentActive &&
            updateFramesSinceLog > 0
        ) {
            staleContentActive = true
            logDisplay(
                "display_stale_content_with_updates updateSeq=$frameSeq drawSeq=$lastDrawSeq " +
                    "streak=$staleUpdateStreak hash=${fingerprintHex(frameFingerprint)}",
            )
        }
        HikDisplayTelemetryRegistry.reportViewUpdate(
            busPath = streamTag,
            seq = latestUpdateSeq,
            fingerprint = latestUpdateFingerprint,
            nowMs = now,
            staleUpdateStreak = staleUpdateStreak,
            staleContentActive = staleContentActive,
        )
        if (lastUpdateFpsLogMs == 0L) {
            lastUpdateFpsLogMs = now
        } else if (now - lastUpdateFpsLogMs >= FPS_LOG_WINDOW_MS) {
            val elapsed = (now - lastUpdateFpsLogMs).coerceAtLeast(1L)
            val fps = updateFramesSinceLog * 1000.0 / elapsed
            val ageSinceDraw = if (lastDrawMs == 0L) -1L else (now - lastDrawMs)
            val seqPart = if (latestUpdateSeq >= 0L) {
                " updateSeq=$latestUpdateSeq updateHash=${fingerprintHex(latestUpdateFingerprint)}"
            } else {
                ""
            }
            val postAgePart = if (lastPostedAtMs > 0L) " ageSincePostMs=${(now - lastPostedAtMs).coerceAtLeast(0L)}" else ""
            logDisplay(
                "display_update_fps=${"%.1f".format(fps)} ageSinceDrawMs=$ageSinceDraw$postAgePart$seqPart",
            )
            updateFramesSinceLog = 0
            lastUpdateFpsLogMs = now
        }
        bitmap = newFrame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val now = System.currentTimeMillis()
        lastDrawMs = now
        drawFramesSinceLog++
        val prevDrawFingerprint = lastDrawFingerprint
        if (latestUpdateSeq >= 0L) {
            lastDrawSeq = latestUpdateSeq
            lastDrawFingerprint = latestUpdateFingerprint
            if (lastDrawFingerprint == prevDrawFingerprint) {
                staleDrawStreak++
            } else {
                staleDrawStreak = 0
            }
        } else {
            staleDrawStreak = 0
        }
        HikDisplayTelemetryRegistry.reportViewDraw(
            busPath = streamTag,
            seq = lastDrawSeq,
            fingerprint = lastDrawFingerprint,
            nowMs = now,
            staleDrawStreak = staleDrawStreak,
        )
        if (lastDrawFpsLogMs == 0L) {
            lastDrawFpsLogMs = now
        } else if (now - lastDrawFpsLogMs >= FPS_LOG_WINDOW_MS) {
            val elapsed = (now - lastDrawFpsLogMs).coerceAtLeast(1L)
            val fps = drawFramesSinceLog * 1000.0 / elapsed
            val ageSinceUpdate = if (lastUpdateMs == 0L) -1L else (now - lastUpdateMs)
            val seqPart = if (lastDrawSeq >= 0L) {
                " drawSeq=$lastDrawSeq drawHash=${fingerprintHex(lastDrawFingerprint)}"
            } else {
                ""
            }
            logDisplay("display_draw_fps=${"%.1f".format(fps)} ageSinceUpdateMs=$ageSinceUpdate$seqPart")
            drawFramesSinceLog = 0
            lastDrawFpsLogMs = now
        }
        srcRect.set(0, 0, bmp.width, bmp.height)
        val bmpAspect = bmp.width.toFloat() / bmp.height
        val viewAspect = width.toFloat() / height
        if (bmpAspect > viewAspect) {
            val h = (width / bmpAspect).toInt()
            val y = (height - h) / 2
            dstRect.set(0, y, width, y + h)
        } else {
            val w = (height * bmpAspect).toInt()
            val x = (width - w) / 2
            dstRect.set(x, 0, x + w, height)
        }
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(stallWatchdog)
        postDelayed(stallWatchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(stallWatchdog)
        HikDisplayTelemetryRegistry.reportSeqGap(streamTag, false)
        bitmap = null
        super.onDetachedFromWindow()
    }

    private fun logDisplay(message: String) {
        UvcDebugLogger.log(context.applicationContext, streamTag, "Hik $message")
    }

    companion object {
        private const val FPS_LOG_WINDOW_MS = 5_000L
        private const val WATCHDOG_INTERVAL_MS = 1_000L
        private const val STALL_THRESHOLD_MS = 3_000L
        private const val STALE_CONTENT_UPDATE_STREAK_THRESHOLD = 120
        private const val SEQ_GAP_THRESHOLD = 4L
        private const val SEQ_GAP_STALL_MS = 1_000L
    }

    private fun fingerprintHex(fp: Int): String = "%08x".format(fp)
}
