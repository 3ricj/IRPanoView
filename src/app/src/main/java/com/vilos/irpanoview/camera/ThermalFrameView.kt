package com.vilos.irpanoview.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

/** Displays a thermal preview bitmap at any width (pano strip). */
class ThermalFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var bitmap: Bitmap? = null

    fun updateBitmap(newFrame: Bitmap) {
        post {
            val old = bitmap
            bitmap = newFrame
            if (old != null && old !== newFrame && !old.isRecycled) {
                old.recycle()
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return
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

    override fun onDetachedFromWindow() {
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
        super.onDetachedFromWindow()
    }
}
