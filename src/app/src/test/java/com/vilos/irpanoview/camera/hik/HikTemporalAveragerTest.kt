package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HikTemporalAveragerTest {

    private lateinit var averager: HikTemporalAverager

    @Before
    fun setUp() {
        HikGridDecodeTuning.set(0, 0)
        averager = HikTemporalAverager()
    }

    @Test
    fun blend_singleFrame_isIdentity() {
        val frame = syntheticFrame(centerRaw = 0x4000)
        val out = averager.blend(frame, targetFrames = 3)
        assertEquals(
            HikTherm.celsiusAtPixel(frame, 128, 96),
            HikTherm.celsiusAtPixel(out, 128, 96),
            0.01,
        )
    }

    @Test
    fun blend_twoFrames_averagesCenter() {
        val a = syntheticFrame(centerRaw = 0x4000)
        val b = syntheticFrame(centerRaw = 0x5000)
        averager.blend(a, targetFrames = 3)
        val out = averager.blend(b, targetFrames = 3)
        val expectedRaw = (0x4000 + 0x5000) / 2
        assertEquals(
            HikTherm.celsiusFromRawU16(expectedRaw),
            HikTherm.celsiusAtPixel(out, 128, 96),
            0.05,
        )
    }

    @Test
    fun blend_dropsOldestWhenDepthExceeded() {
        val frames = listOf(0x3000, 0x4000, 0x5000, 0x7000).map { syntheticFrame(it) }
        averager.blend(frames[0], targetFrames = 3)
        averager.blend(frames[1], targetFrames = 3)
        averager.blend(frames[2], targetFrames = 3)
        val out = averager.blend(frames[3], targetFrames = 3)
        val expectedRaw = (0x4000 + 0x5000 + 0x7000) / 3
        assertEquals(
            HikTherm.celsiusFromRawU16(expectedRaw),
            HikTherm.celsiusAtPixel(out, 128, 96),
            0.05,
        )
    }

    @Test
    fun reset_clearsHistory() {
        averager.blend(syntheticFrame(0x4000), targetFrames = 3)
        averager.reset()
        val centerRaw = 0x6000
        val out = averager.blend(syntheticFrame(centerRaw), targetFrames = 3)
        val cx = 128
        val cy = 96
        val expectedRaw = centerRaw + (cy * HikTherm.GRID_WIDTH + cx) % 7
        assertEquals(
            HikTherm.celsiusFromRawU16(expectedRaw),
            HikTherm.celsiusAtPixel(out, cx, cy),
            0.01,
        )
    }

    private fun syntheticFrame(centerRaw: Int): ByteArray {
        val frame = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame()
        var i = 0
        for (y in 0 until HikTherm.GRID_HEIGHT) {
            for (x in 0 until HikTherm.GRID_WIDTH) {
                HikUvcWireLayout.writeRadioRaw16(frame, y, x, centerRaw + (i % 7))
                i++
            }
        }
        return frame
    }
}
