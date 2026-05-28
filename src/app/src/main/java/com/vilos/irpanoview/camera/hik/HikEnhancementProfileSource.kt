package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.BuildConfig

/** Compile-time enhancement profile (Gradle -PhikEnhanceProfile=...). */
object HikEnhancementProfileSource {

    @Volatile
    var lastSetWire: ByteArray = ByteArray(HikImageEnhancement.SET_WIRE_LEN)
        private set

    fun activeProfile(): HikImageEnhancement.Profile =
        HikImageEnhancement.Profile.fromId(BuildConfig.HIK_ENHANCE_PROFILE)

    fun recordSetWire(wire: ByteArray) {
        lastSetWire = wire.copyOf(minOf(wire.size, HikImageEnhancement.SET_WIRE_LEN))
    }
}
