package com.vilos.irpanoview.model

/**
 * Canonical quad layout by USB machine-serial suffix (last two digits).
 *
 * Grid cells (2×2, title order):
 * ```
 * Cam 1 | Cam 2
 * Cam 3 | Cam 4
 * ```
 *
 * Pano strip will reuse this left-to-right order later.
 */
object QuadCameraOrder {

    /** Last two digits of machine serial, e.g. EA6744502 → "02". */
    val SERIAL_SUFFIXES = listOf("02", "97", "07", "86")

    /** 1-based camera number, or null if suffix is not in [SERIAL_SUFFIXES]. */
    fun cameraNumber(serial: String?): Int? {
        val suffix = serialSuffix(serial) ?: return null
        val idx = SERIAL_SUFFIXES.indexOf(suffix)
        return if (idx >= 0) idx + 1 else null
    }

    /** Preferred grid cell 0..3 for a known serial; null if unknown. */
    fun cellIndex(serial: String?): Int? = cameraNumber(serial)?.minus(1)

    /** Sort key: known cameras in quad order first, unknown last. */
    fun sortKey(serial: String?): Int = cameraNumber(serial) ?: Int.MAX_VALUE

    fun displayTitle(serial: String?, fallbackLabel: String): String {
        val n = cameraNumber(serial)
        return if (n != null) "Camera $n" else fallbackLabel
    }

    private fun serialSuffix(serial: String?): String? {
        val trimmed = serial?.trim().orEmpty()
        if (trimmed.length < 2) return null
        return trimmed.takeLast(2)
    }
}
