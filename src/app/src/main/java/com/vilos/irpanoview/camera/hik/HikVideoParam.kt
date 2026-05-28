package com.vilos.irpanoview.camera.hik

/**
 * Builds USB_VIDEO_PARAM (0xBBC) wire layout — 0xA8 bytes (MasterThermoDocs doc 06).
 * Host-side packing only; delivery is via pure USB control path when wire map is closed.
 */
object HikVideoParam {
    const val WIRE_LEN = 0xA8

    fun buildStreamStartPayload(): ByteArray {
        val buf = ByteArray(WIRE_LEN)
        packU32(buf, 0, HikUvcConstants.STREAM_FORMAT)
        packU32(buf, 4, HikUvcConstants.STREAM_WIDTH_TOKEN)
        packU32(buf, 8, HikUvcConstants.STREAM_HEIGHT_TOKEN)
        packU32(buf, 12, HikUvcConstants.STREAM_FPS)
        return buf
    }

    private fun packU32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
