package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HikFrameAlignerTest {

    @Before
    fun resetGridTuning() {
        HikGridDecodeTuning.set(0, 0)
    }

    @Test
    fun score_prefersUniformRoomTempGrid() {
        val frame = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 22.0)
        val good = HikFrameAligner.score(frame)
        assertTrue(good >= HikFrameAligner.MIN_ACCEPT_SCORE)

        val noise = ByteArray(HikTherm.FRAME_BYTES) { (it * 37).toByte() }
        assertTrue(HikFrameAligner.score(noise) < good)
    }

    @Test
    fun searchBestOffset_findsGridAfterSlip() {
        val good = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 22.0)
        val slip = 4
        val ring = HikStreamRing()
        ring.append(ByteArray(slip) { ((it * 17 + 3) and 0xFF).toByte() }, 0, slip)
        ring.append(good, 0, good.size)

        val result = HikFrameAligner.searchBestOffset(ring, HikTherm.FRAME_BYTES)
        requireNotNull(result)
        assertTrue(result.byteOffset in intArrayOf(0, 4))
        assertTrue(result.score >= HikFrameAligner.MIN_ACCEPT_SCORE)
    }

    @Test
    fun searchBestOffset_acceptsNearCompleteThermalGrid() {
        val good = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 22.0)
        val ring = HikStreamRing()
        ring.append(good, 0, good.size)

        val result = HikFrameAligner.searchBestOffset(ring, HikTherm.FRAME_BYTES)
        requireNotNull(result)
        assertEquals(0, result.byteOffset)
        assertTrue(result.score >= HikFrameAligner.MIN_ACCEPT_SCORE)
    }
}
