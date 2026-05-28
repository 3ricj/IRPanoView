package com.vilos.irpanoview.camera.hik

/**
 * Hik TC002C UVC wire thermometry (MasterThermoDocs doc 07).
 *
 * One frame = [FRAME_BYTES] (256×392 YUYV composite). Radiometry is macropixel-packed
 * in composite rows 0..191 at byte 0 — not a separate header or flat u16 grid.
 */
object HikTherm {
    /** Canonical UVC EOF payload: 256×392×2 = 200704 B. */
    const val FRAME_BYTES = 200_704

    const val TEMP_BIAS_U16 = 0x37C0
    const val TEMP_SCALE = 64
    const val KELVIN_OFFSET_C = 273.15

    const val GRID_WIDTH = 256
    const val GRID_HEIGHT = 192
    const val GRID_SAMPLES = GRID_WIDTH * GRID_HEIGHT

    const val WINDEX_XU = 0x0A00
    const val BULK_EP_DEFAULT = 0x81

    fun classifyFrameBytes(n: Int): String = when (n) {
        FRAME_BYTES -> "hik_uvc_wire"
        else -> "unknown"
    }

    fun isUvcWireFrame(frame: ByteArray): Boolean = frame.size == FRAME_BYTES

    fun rawU16AtPixel(frame: ByteArray, x: Int, y: Int): Int {
        require(x in 0 until GRID_WIDTH && y in 0 until GRID_HEIGHT) { "pixel out of range: ($x, $y)" }
        return HikUvcWireLayout.readRadioRaw16(frame, y, x)
    }

    /** Probe helper: reads visible band with radiometric macropixel indexing for swap checks. */
    fun visibleRawU16AtPixel(frame: ByteArray, x: Int, y: Int): Int {
        require(x in 0 until GRID_WIDTH && y in 0 until GRID_HEIGHT) { "pixel out of range: ($x, $y)" }
        return HikUvcWireLayout.readVisibleRaw16(frame, y, x)
    }

    /** Probe helper: legacy flat LE16 indexing from a byte base (for main-branch parity checks). */
    fun rawU16FlatAtOffset(frame: ByteArray, baseOffset: Int, x: Int, y: Int): Int {
        require(x in 0 until GRID_WIDTH && y in 0 until GRID_HEIGHT) { "pixel out of range: ($x, $y)" }
        val sampleIndex = y * GRID_WIDTH + x
        val byteOffset = baseOffset + sampleIndex * 2
        if (byteOffset < 0 || byteOffset + 1 >= frame.size) {
            return 0
        }
        return (frame[byteOffset].toInt() and 0xFF) or ((frame[byteOffset + 1].toInt() and 0xFF) shl 8)
    }

    fun writeRawU16AtPixel(frame: ByteArray, x: Int, y: Int, rawU16: Int) {
        require(x in 0 until GRID_WIDTH && y in 0 until GRID_HEIGHT) { "pixel out of range: ($x, $y)" }
        HikUvcWireLayout.writeRadioRaw16(frame, y, x, rawU16)
    }

    const val PLAUSIBLE_MIN_C = -40.0
    const val PLAUSIBLE_MAX_C = 120.0

    fun isPlausibleCelsius(c: Double): Boolean = c in PLAUSIBLE_MIN_C..PLAUSIBLE_MAX_C

    fun storedU16FromRaw(rawU16: Int): Int = (rawU16 + TEMP_BIAS_U16) and 0xFFFF

    fun storedU16FromRaw(rawU16: Int, serial: String?): Int =
        (rawU16 + TEMP_BIAS_U16 + HikRadiometricCalibration.biasDeltaU16(serial)) and 0xFFFF

    fun celsiusFromStoredU16(storedU16: Int): Double =
        storedU16.toDouble() / TEMP_SCALE - KELVIN_OFFSET_C

    fun celsiusFromRawU16(rawU16: Int, serial: String? = null): Double =
        celsiusFromStoredU16(storedU16FromRaw(rawU16, serial))

    fun celsiusAtPixel(frame: ByteArray, x: Int, y: Int, serial: String? = null): Double =
        celsiusFromRawU16(rawU16AtPixel(frame, x, y), serial)

    /** Offline/tests only — never gate live streaming on this. */
    fun looksLikeRadiometricWire(frame: ByteArray): Boolean {
        if (!isUvcWireFrame(frame)) return false
        return HikUvcWireLayout.looksLikeWireRadioBand(frame)
    }
}
