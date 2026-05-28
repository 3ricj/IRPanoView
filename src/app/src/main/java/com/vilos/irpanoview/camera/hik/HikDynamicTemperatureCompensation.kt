package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.model.QuadCameraOrder
import kotlin.math.roundToInt

/**
 * Real-time dynamic compensation across quad seams.
 *
 * Computes pairwise seam deltas in camera order 1->2->3->4 and updates downstream
 * offsets so adjacent overlap strips align in Celsius.
 */
object HikDynamicTemperatureCompensation {
    private const val MAX_DYNAMIC_OFFSET_C = 2.0

    data class Snapshot(
        val dynamicOffsetC: Double,
        val pairDelta12C: Double?,
        val pairDelta23C: Double?,
        val pairDelta34C: Double?,
        val sampleCount: Int,
    )

    private data class CameraEdgeMeans(
        val leftRawC: Double,
        val rightRawC: Double,
        val sampleCount: Int,
    )

    private val lock = Any()
    private val meansByCamera = HashMap<Int, CameraEdgeMeans>()
    private val offsetByCamera = HashMap<Int, Double>()

    fun currentOffsetC(serial: String?): Double {
        val cameraNumber = QuadCameraOrder.cameraNumber(serial) ?: return 0.0
        synchronized(lock) {
            return (offsetByCamera[cameraNumber] ?: 0.0).coerceIn(-MAX_DYNAMIC_OFFSET_C, MAX_DYNAMIC_OFFSET_C)
        }
    }

    fun resetAll() {
        synchronized(lock) {
            meansByCamera.clear()
            offsetByCamera.clear()
        }
    }

    fun updateFromFrame(
        serial: String?,
        frame: ByteArray,
        overlapColumnsN: Int,
        centerBandRatio: Double,
    ): Snapshot {
        val cameraNumber = QuadCameraOrder.cameraNumber(serial)
        if (cameraNumber == null) {
            return Snapshot(
                dynamicOffsetC = 0.0,
                pairDelta12C = null,
                pairDelta23C = null,
                pairDelta34C = null,
                sampleCount = 0,
            )
        }

        val overlap = overlapColumnsN.coerceIn(1, HikTherm.GRID_WIDTH / 2)
        val ratio = centerBandRatio.coerceIn(0.05, 1.0)
        val centerRows = (HikTherm.GRID_HEIGHT * ratio).roundToInt().coerceIn(1, HikTherm.GRID_HEIGHT)
        val yStart = ((HikTherm.GRID_HEIGHT - centerRows) / 2).coerceAtLeast(0)
        val yEndExclusive = (yStart + centerRows).coerceAtMost(HikTherm.GRID_HEIGHT)
        val leftEndExclusive = overlap
        val rightStart = HikTherm.GRID_WIDTH - overlap

        var leftSum = 0.0
        var rightSum = 0.0
        var count = 0
        var y = yStart
        while (y < yEndExclusive) {
            var xLeft = 0
            while (xLeft < leftEndExclusive) {
                leftSum += HikTherm.celsiusAtPixel(frame, xLeft, y, serial)
                xLeft++
            }
            var xRight = rightStart
            while (xRight < HikTherm.GRID_WIDTH) {
                rightSum += HikTherm.celsiusAtPixel(frame, xRight, y, serial)
                xRight++
            }
            count += overlap
            y++
        }
        val safeCount = count.coerceAtLeast(1)
        val means = CameraEdgeMeans(
            leftRawC = leftSum / safeCount,
            rightRawC = rightSum / safeCount,
            sampleCount = count,
        )

        synchronized(lock) {
            meansByCamera[cameraNumber] = means
            val workingOffsets = HashMap(offsetByCamera)
            val delta12 = solvePairDelta(1, 2, workingOffsets)
            val delta23 = solvePairDelta(2, 3, workingOffsets)
            val delta34 = solvePairDelta(3, 4, workingOffsets)
            offsetByCamera.clear()
            workingOffsets.forEach { (camera, offsetC) ->
                offsetByCamera[camera] = offsetC.coerceIn(-MAX_DYNAMIC_OFFSET_C, MAX_DYNAMIC_OFFSET_C)
            }
            return Snapshot(
                dynamicOffsetC = offsetByCamera[cameraNumber] ?: 0.0,
                pairDelta12C = delta12,
                pairDelta23C = delta23,
                pairDelta34C = delta34,
                sampleCount = means.sampleCount,
            )
        }
    }

    private fun solvePairDelta(
        upstreamCamera: Int,
        downstreamCamera: Int,
        workingOffsets: MutableMap<Int, Double>,
    ): Double? {
        val upstream = meansByCamera[upstreamCamera] ?: return null
        val downstream = meansByCamera[downstreamCamera] ?: return null
        val upstreamCorrected = upstream.rightRawC + (workingOffsets[upstreamCamera] ?: 0.0)
        val downstreamCorrected = downstream.leftRawC + (workingOffsets[downstreamCamera] ?: 0.0)
        val delta = upstreamCorrected - downstreamCorrected
        workingOffsets[downstreamCamera] = ((workingOffsets[downstreamCamera] ?: 0.0) + delta)
            .coerceIn(-MAX_DYNAMIC_OFFSET_C, MAX_DYNAMIC_OFFSET_C)
        return delta
    }
}
