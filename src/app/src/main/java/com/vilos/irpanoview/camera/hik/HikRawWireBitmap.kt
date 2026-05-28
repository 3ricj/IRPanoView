package com.vilos.irpanoview.camera.hik

import android.graphics.Bitmap

/**
 * Render one complete UVC-assembled wire frame as grayscale (256 bytes/row, top=start, bottom=end).
 */
object HikRawWireBitmap {

    const val DISPLAY_WIDTH = 256

    fun toBitmap(payload: ByteArray): Bitmap {
        if (payload.isEmpty()) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val width = DISPLAY_WIDTH
        val height = (payload.size + width - 1) / width
        val pixels = IntArray(width * height)
        for (i in payload.indices) {
            val v = payload[i].toInt() and 0xFF
            pixels[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
