package com.vilos.irpanoview.camera.hik

/**
 * 0x7EB image enhancement wire map (176 B SET payload).
 *
 * Wire bytes follow libHCUSBSDK ConvertData @ 0x001a74ac compact packing.
 * These are **not**
 * the JNA struct host offsets (e.g. byPaletteMode is JNA +0x14 but wire byte 5).
 */
object HikImageEnhancement {

    const val SET_WIRE_LEN = 176
    const val GET_WIRE_LEN = 79

    const val WIRE_OFF_HIGH_LIGHT_LEVEL = 0x01
    const val WIRE_OFF_GENERAL_LEVEL = 0x02
    const val WIRE_OFF_FRAME_NR_LEVEL = 0x03
    const val WIRE_OFF_INTER_FRAME_NR_LEVEL = 0x04
    const val WIRE_OFF_PALETTE_MODE = 0x05
    const val WIRE_OFF_NOISE_REDUCE_MODE = 0x06
    const val WIRE_OFF_LSE_DETAIL_LEVEL = 0x07
    const val WIRE_OFF_LSE_DETAIL_ENABLED = 0x08
    const val WIRE_OFF_HIGH_LIGHT_MODE = 0x11
    const val WIRE_OFF_HOOK_EDGE_MODE = 0x12
    const val WIRE_OFF_HOOK_EDGE_LEVEL = 0x13
    const val WIRE_OFF_WIDE_TEMP_MODE = 0x14
    const val WIRE_OFF_WIDE_TEMP_WORK = 0x15
    const val WIRE_OFF_BIRD_WATCHING = 0x16
    const val WIRE_OFF_AI_SUPER_RESOLUTION = 0x17
    const val WIRE_OFF_ISP_AGC_MODE = 0x18

    const val PALETTE_WHITE_HOT = 0x02

    /** TopInfrared default when DDE slider is mid: ddeConfig * 100 / 4 with ddeConfig=2 -> 50. */
    const val DEFAULT_LSE_DETAIL_LEVEL = 50

    enum class Profile(val id: String) {
        /** White-hot palette only; leave NR/DDE/AI at device GET values. */
        BASELINE("baseline"),
        /** TopInfrared initConfig parity: white-hot + DDE on @ default level. */
        DDE_ON("dde_on"),
        /** DDE on with level byte 100. */
        DDE_MAX("dde_max"),
        /** Enable byAISuperResolution. */
        AI_SR("ai_sr"),
        /** Bump frame NR level byte to 100. */
        NR_FRAME("nr_frame"),
        /** Bump inter-frame NR level byte to 100. */
        NR_INTER("nr_inter"),
        /** DDE max + AI SR + both NR level bytes 100. */
        FULL("full"),
        ;

        companion object {
            fun fromId(id: String): Profile =
                entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: BASELINE
        }
    }

    data class Settings(
        val whiteHot: Boolean = true,
        val lseDetailEnabled: Boolean? = null,
        val lseDetailLevel: Int? = null,
        val aiSuperResolution: Boolean? = null,
        val noiseReduceMode: Int? = null,
        val frameNoiseReduceLevel: Int? = null,
        val interFrameNoiseReduceLevel: Int? = null,
    )

    fun settingsFor(profile: Profile): Settings = when (profile) {
        Profile.BASELINE -> Settings(whiteHot = true)
        Profile.DDE_ON -> Settings(whiteHot = true, lseDetailEnabled = true, lseDetailLevel = DEFAULT_LSE_DETAIL_LEVEL)
        Profile.DDE_MAX -> Settings(whiteHot = true, lseDetailEnabled = true, lseDetailLevel = 100)
        Profile.AI_SR -> Settings(whiteHot = true, aiSuperResolution = true)
        Profile.NR_FRAME -> Settings(whiteHot = true, frameNoiseReduceLevel = 100)
        Profile.NR_INTER -> Settings(whiteHot = true, interFrameNoiseReduceLevel = 100)
        Profile.FULL -> Settings(
            whiteHot = true,
            lseDetailEnabled = true,
            lseDetailLevel = 100,
            aiSuperResolution = true,
            frameNoiseReduceLevel = 100,
            interFrameNoiseReduceLevel = 100,
        )
    }

    /** Apply [settings] onto a 176 B SET buffer (typically seeded from GET 0x7EA). */
    fun patch(buf: ByteArray, settings: Settings) {
        require(buf.size >= SET_WIRE_LEN) {
            "enhancement SET buffer needs $SET_WIRE_LEN B, got ${buf.size}"
        }
        if (settings.whiteHot) {
            buf[WIRE_OFF_PALETTE_MODE] = PALETTE_WHITE_HOT.toByte()
        }
        settings.lseDetailLevel?.let { level ->
            buf[WIRE_OFF_LSE_DETAIL_LEVEL] = level.coerceIn(0, 255).toByte()
        }
        settings.lseDetailEnabled?.let { enabled ->
            buf[WIRE_OFF_LSE_DETAIL_ENABLED] = if (enabled) 1 else 0
        }
        settings.noiseReduceMode?.let { mode ->
            buf[WIRE_OFF_NOISE_REDUCE_MODE] = mode.coerceIn(0, 255).toByte()
        }
        settings.frameNoiseReduceLevel?.let { level ->
            buf[WIRE_OFF_FRAME_NR_LEVEL] = level.coerceIn(0, 255).toByte()
        }
        settings.interFrameNoiseReduceLevel?.let { level ->
            buf[WIRE_OFF_INTER_FRAME_NR_LEVEL] = level.coerceIn(0, 255).toByte()
        }
        settings.aiSuperResolution?.let { enabled ->
            buf[WIRE_OFF_AI_SUPER_RESOLUTION] = if (enabled) 1 else 0
        }
    }

    fun patch(buf: ByteArray, profile: Profile) {
        patch(buf, settingsFor(profile))
    }

    /** Compact log line for debug / adb pull validation. */
    fun formatKeyBytes(buf: ByteArray): String {
        if (buf.isEmpty()) return "empty"
        fun b(off: Int): Int = if (off in buf.indices) buf[off].toInt() and 0xFF else -1
        return buildString {
            append("pal=").append(b(WIRE_OFF_PALETTE_MODE))
            append(" ddeEn=").append(b(WIRE_OFF_LSE_DETAIL_ENABLED))
            append(" ddeLvl=").append(b(WIRE_OFF_LSE_DETAIL_LEVEL))
            append(" nrMode=").append(b(WIRE_OFF_NOISE_REDUCE_MODE))
            append(" nrF=").append(b(WIRE_OFF_FRAME_NR_LEVEL))
            append(" nrI=").append(b(WIRE_OFF_INTER_FRAME_NR_LEVEL))
            append(" aiSr=").append(b(WIRE_OFF_AI_SUPER_RESOLUTION))
        }
    }

    fun formatWireHead(buf: ByteArray, n: Int = 32): String =
        buf.take(n.coerceAtMost(buf.size)).joinToString("") { "%02x".format(it) }
}
