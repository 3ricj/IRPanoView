package com.vilos.irpanoview.camera

import android.graphics.Bitmap
import com.vilos.irpanoview.model.ThermalColorPalette

/**
 * Fast windowed-u8 → ARGB bitmap.
 * Always returns a freshly allocated Bitmap whose pixel storage is independent
 * of our scratch buffer (createBitmap(int[]) shares the array — that caused
 * the one-frame flicker then crash).
 */
class PreviewU8Blitter {
    private var argb = IntArray(0)

    fun blit(
        pixels: ByteArray,
        width: Int,
        height: Int,
        palette: ThermalColorPalette,
    ): Bitmap {
        val count = width * height
        require(width > 0 && height > 0 && pixels.size >= count) {
            "bad preview size ${width}x$height pixels=${pixels.size}"
        }
        if (argb.size != count) {
            argb = IntArray(count)
        }
        val lut = ThermalColormap.lut(palette)
        for (i in 0 until count) {
            argb[i] = lut[pixels[i].toInt() and 0xFF]
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, width, 0, 0, width, height)
        return bmp
    }

    fun release() {
        argb = IntArray(0)
    }
}
