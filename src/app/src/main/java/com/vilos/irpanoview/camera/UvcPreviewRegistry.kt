package com.vilos.irpanoview.camera

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

/** Tracks live UVC decoders so Settings can force a snapshot pull. */
object UvcPreviewRegistry {
    private val suppliers = ConcurrentHashMap<String, () -> Bitmap?>()

    fun register(busPath: String, supplier: () -> Bitmap?) {
        suppliers[busPath] = supplier
    }

    fun unregister(busPath: String) {
        suppliers.remove(busPath)
    }

    fun activePaths(): Set<String> = suppliers.keys.toSet()

    fun currentBitmap(busPath: String): Bitmap? = suppliers[busPath]?.invoke()
}
