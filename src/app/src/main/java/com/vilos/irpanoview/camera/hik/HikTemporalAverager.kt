package com.vilos.irpanoview.camera.hik

import java.util.ArrayDeque

/**
 * Rolling average of radiometric u16 grid samples (stored-domain, post bias).
 *
 * Uses only as many past frames as are available (startup / after reset).
 */
class HikTemporalAverager {

    private val ring = ArrayDeque<IntArray>()
    private val sumGrid = IntArray(HikTherm.GRID_SAMPLES)
    private val scratchFrame = ByteArray(HikTherm.FRAME_BYTES)

    fun reset() {
        ring.clear()
    }

    /**
     * Push [frame] into the ring and return a wire frame whose radiometry grid is the
     * integer mean of up to [targetFrames] most recent samples.
     */
    fun blend(frame: ByteArray, targetFrames: Int): ByteArray {
        val depth = targetFrames.coerceIn(
            HikPreviewSettings.MIN_TEMPORAL_AVERAGE_FRAMES,
            HikPreviewSettings.MAX_TEMPORAL_AVERAGE_FRAMES,
        )
        ring.addLast(extractStoredGrid(frame))
        while (ring.size > depth) {
            ring.removeFirst()
        }

        val count = ring.size
        sumGrid.fill(0)
        for (layer in ring) {
            for (i in layer.indices) {
                sumGrid[i] += layer[i]
            }
        }

        scratchFrame.fill(0)
        frame.copyInto(scratchFrame, endIndex = minOf(frame.size, scratchFrame.size))
        var i = 0
        for (y in 0 until HikTherm.GRID_HEIGHT) {
            for (x in 0 until HikTherm.GRID_WIDTH) {
                val avgStored = (sumGrid[i] + count / 2) / count
                val raw = (avgStored - HikTherm.TEMP_BIAS_U16) and 0xFFFF
                HikTherm.writeRawU16AtPixel(scratchFrame, x, y, raw)
                i++
            }
        }
        return scratchFrame
    }

    private fun extractStoredGrid(frame: ByteArray): IntArray {
        val out = IntArray(HikTherm.GRID_SAMPLES)
        var i = 0
        for (y in 0 until HikTherm.GRID_HEIGHT) {
            for (x in 0 until HikTherm.GRID_WIDTH) {
                out[i++] = HikTherm.storedU16FromRaw(HikTherm.rawU16AtPixel(frame, x, y))
            }
        }
        return out
    }
}
