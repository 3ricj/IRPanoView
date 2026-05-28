package com.vilos.irpanoview.camera.hik

/**
 * UVC EOF wire payload layout (256×392 YUYV, 512 B/row, 200704 B total).
 *
 * ```
 * radio   = data[0x000000:0x018000]   // 192 rows — radiometric YUYV-like LE16 temp pairs
 * footer1 = data[0x018000:0x018800]   // 4 rows — binary metadata
 * visible = data[0x018800:0x030800]   // 192 rows — LUT grayscale YUYV (Y varies, UV ≈ 0x80)
 * footer2 = data[0x030800:0x031000]   // 4 rows — ASCII diagnostics
 * ```
 *
 * Radiometric macropixel decode (composite rows 0..191):
 * `mac = y × 512 + (x // 2) × 4` then LE16 at mac (even x) or mac+2 (odd x).
 */
object HikUvcWireLayout {
    const val WIDTH = 256
    const val HEIGHT = 392
    const val ROW_BYTES = WIDTH * 2
    const val IMAGE_ROWS = 192
    const val FOOTER_ROWS = 4

    const val RADIO_OFFSET = 0x0
    const val RADIO_BYTES = 0x18000
    const val FOOTER1_OFFSET = 0x18000
    const val FOOTER1_BYTES = 0x800
    const val VISIBLE_OFFSET = 0x18800
    const val VISIBLE_BYTES = 0x18000
    const val FOOTER2_OFFSET = 0x30800
    const val FOOTER2_BYTES = 0x800
    const val PAYLOAD_BYTES = 0x31000
    const val JUMBO_PLANE_BYTES = RADIO_BYTES
    const val JUMBO_BASE_HEADER_BYTES = 0x1220
    const val JUMBO_TOTAL_MIN_BYTES = JUMBO_BASE_HEADER_BYTES + (JUMBO_PLANE_BYTES * 2)
    const val JUMBO_TOTAL_MAX_BYTES = JUMBO_TOTAL_MIN_BYTES + 0x2000
    const val JUMBO_SWRF_SCAN_BYTES = 64

    const val RADIO_END = FOOTER2_OFFSET

    const val VISIBLE_FIRST_ROW = IMAGE_ROWS + FOOTER_ROWS

    /** Doc 07: `mac = 0x0 + y×512 + (x//2)×4`, LE16 at mac (even x) or mac+2 (odd x). */
    fun readRadioRaw16(frame: ByteArray, y: Int, x: Int): Int {
        return readBandRaw16(frame, RADIO_OFFSET, y, x)
    }

    /** Probe helper: apply the same macropixel read against visible band rows 196..387. */
    fun readVisibleRaw16(frame: ByteArray, y: Int, x: Int): Int {
        return readBandRaw16(frame, VISIBLE_OFFSET, y, x)
    }

    private fun readBandRaw16(frame: ByteArray, baseOffset: Int, y: Int, x: Int): Int {
        require(x in 0 until WIDTH) { "x out of range: $x" }
        require(y in 0 until IMAGE_ROWS) { "y out of range: $y" }
        val mac = baseOffset + y * ROW_BYTES + (x shr 1) * 4
        if (mac + 3 >= frame.size) return 0
        return if ((x and 1) == 0) {
            (frame[mac].toInt() and 0xFF) or ((frame[mac + 1].toInt() and 0xFF) shl 8)
        } else {
            (frame[mac + 2].toInt() and 0xFF) or ((frame[mac + 3].toInt() and 0xFF) shl 8)
        }
    }

    fun writeRadioRaw16(frame: ByteArray, y: Int, x: Int, rawU16: Int) {
        require(x in 0 until WIDTH) { "x out of range: $x" }
        require(y in 0 until IMAGE_ROWS) { "y out of range: $y" }
        val mac = RADIO_OFFSET + y * ROW_BYTES + (x shr 1) * 4
        if (mac + 3 >= frame.size) return
        if ((x and 1) == 0) {
            frame[mac] = (rawU16 and 0xFF).toByte()
            frame[mac + 1] = ((rawU16 shr 8) and 0xFF).toByte()
        } else {
            frame[mac + 2] = (rawU16 and 0xFF).toByte()
            frame[mac + 3] = ((rawU16 shr 8) and 0xFF).toByte()
        }
    }

    fun looksLikeWireRadioBand(frame: ByteArray): Boolean {
        if (frame.size < RADIO_BYTES) return false
        val points = intArrayOf(
            WIDTH / 2, HikTherm.GRID_HEIGHT / 2,
            WIDTH / 4, HikTherm.GRID_HEIGHT / 4,
            3 * WIDTH / 4, HikTherm.GRID_HEIGHT / 4,
            WIDTH / 4, 3 * HikTherm.GRID_HEIGHT / 4,
            3 * WIDTH / 4, 3 * HikTherm.GRID_HEIGHT / 4,
        )
        var plausible = 0
        var i = 0
        while (i < points.size) {
            val raw = readRadioRaw16(frame, points[i + 1], points[i])
            if (HikTherm.isPlausibleCelsius(HikTherm.celsiusFromRawU16(raw))) plausible++
            i += 2
        }
        return plausible >= 3
    }

    fun footer1Bytes(frame: ByteArray): ByteArray {
        if (frame.size < FOOTER1_OFFSET + FOOTER1_BYTES) return ByteArray(0)
        return frame.copyOfRange(FOOTER1_OFFSET, FOOTER1_OFFSET + FOOTER1_BYTES)
    }

    fun footer2Bytes(frame: ByteArray): ByteArray {
        if (frame.size < FOOTER2_OFFSET + FOOTER2_BYTES) return ByteArray(0)
        return frame.copyOfRange(FOOTER2_OFFSET, FOOTER2_OFFSET + FOOTER2_BYTES)
    }

    fun footer2Ascii(frame: ByteArray): String = formatFooter2Ascii(footer2Bytes(frame))

    fun formatFooter2Ascii(footer2: ByteArray): String =
        footer2
            .map { b -> (b.toInt() and 0xFF).toChar() }
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".[]_:-+," }
            .joinToString("")
            .trim()
}
