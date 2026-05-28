package com.vilos.irpanoview.camera.hik

/** Doc 07 decode uses fixed offsets only; tuning knobs are disabled. */
object HikGridDecodeTuning {
    fun set(@Suppress("UNUSED_PARAMETER") pixel: Int, @Suppress("UNUSED_PARAMETER") byte: Int) = Unit

    fun effectiveHex(): String = "doc07_macropixel@0x0"
}
