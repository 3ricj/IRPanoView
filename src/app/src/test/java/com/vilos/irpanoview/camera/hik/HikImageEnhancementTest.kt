package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Test

class HikImageEnhancementTest {

    @Test
    fun patch_baseline_setsWhiteHotAtWireByte5_notByte1() {
        val buf = ByteArray(HikImageEnhancement.SET_WIRE_LEN) { 0 }
        HikImageEnhancement.patch(buf, HikImageEnhancement.Profile.BASELINE)
        assertEquals(HikImageEnhancement.PALETTE_WHITE_HOT, buf[HikImageEnhancement.WIRE_OFF_PALETTE_MODE].toInt() and 0xFF)
        assertEquals(0, buf[HikImageEnhancement.WIRE_OFF_HIGH_LIGHT_LEVEL].toInt() and 0xFF)
    }

    @Test
    fun patch_ddeOn_enablesDetailEnhancement() {
        val buf = ByteArray(HikImageEnhancement.SET_WIRE_LEN) { 0 }
        HikImageEnhancement.patch(buf, HikImageEnhancement.Profile.DDE_ON)
        assertEquals(1, buf[HikImageEnhancement.WIRE_OFF_LSE_DETAIL_ENABLED].toInt() and 0xFF)
        assertEquals(
            HikImageEnhancement.DEFAULT_LSE_DETAIL_LEVEL,
            buf[HikImageEnhancement.WIRE_OFF_LSE_DETAIL_LEVEL].toInt() and 0xFF,
        )
    }

    @Test
    fun patch_aiSr_setsWireByte0x17() {
        val buf = ByteArray(HikImageEnhancement.SET_WIRE_LEN) { 0 }
        HikImageEnhancement.patch(buf, HikImageEnhancement.Profile.AI_SR)
        assertEquals(1, buf[HikImageEnhancement.WIRE_OFF_AI_SUPER_RESOLUTION].toInt() and 0xFF)
    }

    @Test
    fun patch_full_setsAllEnhancementFlags() {
        val buf = ByteArray(HikImageEnhancement.SET_WIRE_LEN) { 0 }
        HikImageEnhancement.patch(buf, HikImageEnhancement.Profile.FULL)
        assertEquals(1, buf[HikImageEnhancement.WIRE_OFF_LSE_DETAIL_ENABLED].toInt() and 0xFF)
        assertEquals(100, buf[HikImageEnhancement.WIRE_OFF_LSE_DETAIL_LEVEL].toInt() and 0xFF)
        assertEquals(100, buf[HikImageEnhancement.WIRE_OFF_FRAME_NR_LEVEL].toInt() and 0xFF)
        assertEquals(100, buf[HikImageEnhancement.WIRE_OFF_INTER_FRAME_NR_LEVEL].toInt() and 0xFF)
        assertEquals(1, buf[HikImageEnhancement.WIRE_OFF_AI_SUPER_RESOLUTION].toInt() and 0xFF)
    }

    @Test
    fun profileFromId_unknownFallsBackToBaseline() {
        assertEquals(HikImageEnhancement.Profile.BASELINE, HikImageEnhancement.Profile.fromId("nope"))
    }
}
