package com.vilos.irpanoview.camera.hik

/**
 * Accumulates bulk IN chunks and emits fixed-size super-frames without dropping bytes.
 */
internal class HikStreamRing {
    private var buf = ByteArray(0)

    val bufferedBytes: Int
        get() = buf.size

    fun clear() {
        buf = ByteArray(0)
    }

    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val merged = ByteArray(buf.size + length)
        buf.copyInto(merged)
        data.copyInto(merged, buf.size, offset, offset + length)
        buf = merged
    }

    fun takeFrame(frameSize: Int): ByteArray? {
        if (buf.size < frameSize) return null
        val frame = buf.copyOfRange(0, frameSize)
        buf = if (buf.size > frameSize) {
            buf.copyOfRange(frameSize, buf.size)
        } else {
            ByteArray(0)
        }
        return frame
    }

    fun peekFrame(frameSize: Int): ByteArray? = peekFrameAt(0, frameSize)

    fun peekFrameAt(startOffset: Int, frameSize: Int): ByteArray? {
        if (startOffset < 0 || startOffset + frameSize > buf.size) return null
        return buf.copyOfRange(startOffset, startOffset + frameSize)
    }

    fun dropLeading(bytes: Int) {
        if (bytes <= 0) return
        buf = when {
            bytes >= buf.size -> ByteArray(0)
            else -> buf.copyOfRange(bytes, buf.size)
        }
    }
}
