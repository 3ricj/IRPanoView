package com.vilos.irpanoview.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class TopdonFrameDecoderTest {

    @Test
    fun pointStats_evenSideLength_normalizesToOdd() {
        val width = 5
        val height = 5
        val plane = IntArray(width * height) { idx -> idx + 1 }
        val model = TemperatureModel.debugFallbackDecode64()

        val s3 = TopdonFrameDecoder.getPointNativeStats(
            planeRaw16 = plane,
            frameWidth = width,
            frameHeight = height,
            x = 2,
            y = 2,
            model = model,
            sideLength = 3,
        )
        val s4 = TopdonFrameDecoder.getPointNativeStats(
            planeRaw16 = plane,
            frameWidth = width,
            frameHeight = height,
            x = 2,
            y = 2,
            model = model,
            sideLength = 4,
        )

        assertEquals(s3.native, s4.native)
    }

    @Test
    fun pointStats_edgeUsesCenterFillForOutOfBounds() {
        val width = 3
        val height = 3
        val plane = intArrayOf(
            10, 20, 30,
            40, 50, 60,
            70, 80, 90,
        )
        val model = TemperatureModel.debugFallbackDecode64()

        val p = TopdonFrameDecoder.getPointNativeStats(
            planeRaw16 = plane,
            frameWidth = width,
            frameHeight = height,
            x = 0,
            y = 0,
            model = model,
            sideLength = 3,
        )

        // 3x3 around (0,0) with center-fill OOB:
        // [10,10,10,10,10,20,10,40,50], trimmed -> remove 10 and 50.
        // (170 - 10 - 50 + 3) / 7 = 16.
        assertEquals(16, p.native)
    }

    @Test
    fun thermalRowRangeMatchesKnownFrameLayouts() {
        assertEquals(192 until 384, TopdonFrameDecoder.thermalImageRowRange(384))
        assertEquals(196 until 388, TopdonFrameDecoder.thermalImageRowRange(392))
    }

    @Test
    fun rectStats_uniformPlaneRemainsUniform() {
        val width = 4
        val height = 4
        val plane = IntArray(width * height) { 100 }
        val model = TemperatureModel.debugFallbackDecode64()

        val r = TopdonFrameDecoder.getRectNativeStats(
            planeRaw16 = plane,
            frameWidth = width,
            frameHeight = height,
            rectX0 = 0,
            rectY0 = 0,
            rectX1 = 3,
            rectY1 = 3,
            model = model,
            sideLength = 3,
        )!!

        assertEquals(100, r.minNative)
        assertEquals(100, r.maxNative)
        assertEquals(100, r.avgNative)
    }

    @Test
    fun lineStats_horizontalUsesNativeLikeTrimmedWindow() {
        val width = 5
        val height = 3
        val plane = intArrayOf(
            0, 0, 0, 0, 0,
            10, 20, 30, 40, 50,
            0, 0, 0, 0, 0,
        )
        val model = TemperatureModel.debugFallbackDecode64()

        val line = TopdonFrameDecoder.getLineNativeStats(
            planeRaw16 = plane,
            frameWidth = width,
            frameHeight = height,
            x0 = 0,
            y0 = 1,
            x1 = 4,
            y1 = 1,
            model = model,
        )!!

        assertEquals(13, line.minNative)
        assertEquals(47, line.maxNative)
        assertEquals(30, line.avgNative)
        assertEquals(0, line.minX)
        assertEquals(1, line.minY)
        assertEquals(4, line.maxX)
        assertEquals(1, line.maxY)
        assertEquals(5, line.sampleCount)
    }
}
