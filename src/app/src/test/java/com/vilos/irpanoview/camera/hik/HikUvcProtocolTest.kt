package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HikUvcProtocolTest {

    @Test
    fun packU32Le_writesLittleEndian() {
        val buf = ByteArray(4)
        HikUvcProtocol.packU32Le(buf, 0, 0x01020304)
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), buf)
    }

    @Test
    fun videoParamPayload_hasStreamTokens() {
        val payload = HikVideoParam.buildStreamStartPayload()
        assertEquals(HikVideoParam.WIRE_LEN, payload.size)
        assertEquals(HikUvcConstants.STREAM_FORMAT, readU32(payload, 0))
        assertEquals(HikUvcConstants.STREAM_WIDTH_TOKEN, readU32(payload, 4))
        assertEquals(HikUvcConstants.STREAM_HEIGHT_TOKEN, readU32(payload, 8))
        assertEquals(HikUvcConstants.STREAM_FPS, readU32(payload, 12))
    }

    @Test
    fun patchVideoAdjustLandscape_clearsCorridorAndFlip() {
        val buf = byteArrayOf(0, 1, 2, 3, 4, 5) + ByteArray(35)
        HikUvcProtocol.patchVideoAdjustLandscape(buf)
        assertEquals(0, buf[HikUvcConstants.WIRE_OFF_VIDEO_FLIP_STYLE].toInt())
        assertEquals(0, buf[HikUvcConstants.WIRE_OFF_VIDEO_CORRIDOR].toInt())
        assertEquals(1, buf[HikUvcConstants.WIRE_OFF_VIDEO_DIGITAL_ZOOM].toInt())
    }

    @Test
    fun patchIrConfig_writesSetIrConfigFields() {
        val buf = ByteArray(80)
        HikUvcProtocol.patchIrConfig(buf, emissivityWire = 95, distanceWire = 1000, ambientCelsius = 20.0)
        assertEquals(95, readU32(buf, HikUvcConstants.WIRE_OFF_EMISSIVITY))
        assertEquals(1000, readU32(buf, HikUvcConstants.WIRE_OFF_DISTANCE))
        assertEquals(HikUvcConstants.ENV_TEMP_ENABLE_ON, buf[HikUvcConstants.WIRE_OFF_ENV_TEMP_ENABLE].toInt() and 0xFF)
        assertEquals(12_000, readU32(buf, HikUvcConstants.WIRE_OFF_ENV_TEMP))
    }

    @Test
    fun thermWireBaseline_matchesPromotedOffsets() {
        val baseline = ByteArray(80) { 0 }
        HikUvcProtocol.patchIrConfig(baseline, emissivityWire = 95, distanceWire = 1000, ambientCelsius = 20.0)
        baseline[HikUvcConstants.WIRE_OFF_TEMPERATURE_RANGE] = 2
        baseline[HikUvcConstants.WIRE_OFF_THERM_OVERLAY] = HikUvcConstants.THERM_OVERLAY_OFF.toByte()
        assertEquals(95, readU32(baseline, 16))
        assertEquals(1000, readU32(baseline, 21))
        assertEquals(2, baseline[6].toInt() and 0xFF)
        assertEquals(1, baseline[2].toInt() and 0xFF)
    }

    @Test
    fun packUsbCommonCond_matchesDoc09Layout() {
        val buf = HikUvcProtocol.packUsbCommonCond(channelId = 1, sid = 0)
        assertEquals(HikUvcConstants.USB_COMMON_COND_WIRE_LEN, buf.size)
        assertEquals(12, readU32(buf, 0))
        assertEquals(1, buf[4].toInt() and 0xFF)
        assertEquals(0, buf[5].toInt() and 0xFF)
        for (i in 6 until buf.size) {
            assertEquals(0, buf[i].toInt() and 0xFF)
        }
    }

    @Test
    fun packSerialTransmission_autoShutterLayout() {
        val buf = HikUvcProtocol.packSerialTransmission(HikUvcConstants.SERIAL_CMD_AUTO_SHUTTER, 1)
        assertEquals(HikUvcConstants.SERIAL_TRANSMISSION_WIRE_LEN, buf.size)
        assertEquals(2, buf[0].toInt() and 0xFF)
        assertEquals(HikUvcConstants.SERIAL_CMD_AUTO_SHUTTER, readU32(buf, 3))
        assertEquals(1, readU32(buf, 7))
        assertEquals(0, readU16(buf, 11))
    }

    private fun readU16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}
