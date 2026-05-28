package com.vilos.irpanoview.camera

import com.vilos.irpanoview.model.ThermalColorPalette
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * TOPDON composite YUYV (typically 256×392): visible + footers + thermal band + footers.
 *
 * Thermal radiometry uses **16-bit LE in YUYV macropixels** (even: Y0+U×256, odd: Y1+V×256).
 * PC observations show ~15 effective bits on raw16; Android may sit on a 0x8000 pedestal with
 * narrower span until vendor init — still use raw16 for cross-camera alignment.
 */
object TopdonFrameDecoder {

    const val WIDTH = 256
    const val IMAGE_ROWS = 192
    const val INFO_ROWS = 4

    data class CelsiusWindow(
        val minCelsius: Double,
        val maxCelsius: Double,
    ) {
        init {
            require(maxCelsius > minCelsius) { "maxCelsius must be greater than minCelsius" }
        }
    }

    data class DynamicWindowPolicy(
        val hardMinCelsius: Double,
        val hardMaxCelsius: Double,
        val minSpanCelsius: Double = 1.0,
        val lowerPercentile: Double = 0.05,
        val upperPercentile: Double = 0.95,
    ) {
        init {
            require(hardMaxCelsius > hardMinCelsius) { "hardMaxCelsius must be greater than hardMinCelsius" }
            require(minSpanCelsius > 0.0) { "minSpanCelsius must be greater than zero" }
            require(lowerPercentile in 0.0..1.0) { "lowerPercentile must be in [0,1]" }
            require(upperPercentile in 0.0..1.0) { "upperPercentile must be in [0,1]" }
            require(upperPercentile > lowerPercentile) { "upperPercentile must be greater than lowerPercentile" }
        }
    }

    data class CelsiusWindowScan(
        val window: CelsiusWindow?,
        val inRangeCount: Int,
        val totalSamples: Int,
        val minInRangeCelsius: Double?,
        val maxInRangeCelsius: Double?,
    )

    data class NativeRectStats(
        val minNative: Int,
        val maxNative: Int,
        val avgNative: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val sampleCount: Int,
    )

    data class NativePointStats(
        val native: Int,
    )

    data class NativeLineStats(
        val minNative: Int,
        val maxNative: Int,
        val avgNative: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val sampleCount: Int,
    )

    fun expectedBytes(width: Int, height: Int): Int = width * height * 2

    fun previewHeight(frameHeight: Int): Int = IMAGE_ROWS

    /**
     * Strict TC001 contract used by parity mode.
     * Accept only known composite layouts that decode to 256x192 thermal image rows.
     */
    fun isStrictTc001Contract(frameWidth: Int, frameHeight: Int): Boolean {
        if (frameWidth != WIDTH) return false
        if (frameHeight != 384 && frameHeight != 392) return false
        return thermalImageRowRange(frameHeight).count() == IMAGE_ROWS
    }

    fun decodeThermalPreview(
        frame: ByteBuffer,
        frameWidth: Int,
        frameHeight: Int,
        palette: ThermalColorPalette,
        celsiusWindow: CelsiusWindow,
        out: IntArray,
        temperatureModel: TemperatureModel = TemperatureModel.debugFallbackDecode64(),
        nativeToCelsiusOverride: ((Int) -> Double)? = null,
    ): DecodeStats? {
        val outH = previewHeight(frameHeight)
        if (out.size < frameWidth * outH) return null
        if (frame.remaining() < expectedBytes(frameWidth, frameHeight)) return null

        val base = frame.position()
        val thermalRows = thermalImageRowRange(frameHeight)
        if (thermalRows.isEmpty()) return null
        val thermalHeight = thermalRows.count()
        val thermalPlane = extractThermalPlaneRaw16(frame, base, frameWidth, thermalRows)

        val local = scanThermalScalarRange(thermalPlane)
        val centerY = thermalHeight / 2
        val centerX = frameWidth / 2
        val centerRaw = thermalPlane[centerY * frameWidth + centerX]
        val centerPointNative = getPointNativeStats(
            planeRaw16 = thermalPlane,
            frameWidth = frameWidth,
            frameHeight = thermalHeight,
            x = centerX,
            y = centerY,
            model = temperatureModel,
        ).native
        val fullRectStats = getRectNativeStats(
            planeRaw16 = thermalPlane,
            frameWidth = frameWidth,
            frameHeight = thermalHeight,
            rectX0 = 0,
            rectY0 = 0,
            rectX1 = frameWidth - 1,
            rectY1 = thermalHeight - 1,
            model = temperatureModel,
        ) ?: return null
        val centerLineStats = getLineNativeStats(
            planeRaw16 = thermalPlane,
            frameWidth = frameWidth,
            frameHeight = thermalHeight,
            x0 = 0,
            y0 = centerY,
            x1 = frameWidth - 1,
            y1 = centerY,
            model = temperatureModel,
        ) ?: return null
        var outY = 0
        var clippedLowCount = 0
        var clippedHighCount = 0
        var totalSamples = 0
        for (y in 0 until thermalHeight) {
            val rowStart = y * frameWidth
            var x = 0
            while (x < frameWidth) {
                val s0 = thermalPlane[rowStart + x]
                val outRow = outY * frameWidth
                val d0 = celsiusToDisplay(
                    nativeToCelsius(
                        temperatureModel.rawToNativeLike(s0),
                        temperatureModel,
                        nativeToCelsiusOverride,
                    ),
                    celsiusWindow.minCelsius,
                    celsiusWindow.maxCelsius,
                )
                if (d0 <= 0) clippedLowCount++
                if (d0 >= 255) clippedHighCount++
                totalSamples++
                out[outRow + x] = ThermalColormap.color(palette, d0)
                if (x + 1 < frameWidth) {
                    val s1 = thermalPlane[rowStart + x + 1]
                    val d1 = celsiusToDisplay(
                        nativeToCelsius(
                            temperatureModel.rawToNativeLike(s1),
                            temperatureModel,
                            nativeToCelsiusOverride,
                        ),
                        celsiusWindow.minCelsius,
                        celsiusWindow.maxCelsius,
                    )
                    if (d1 <= 0) clippedLowCount++
                    if (d1 >= 255) clippedHighCount++
                    totalSamples++
                    out[outRow + x + 1] = ThermalColormap.color(palette, d1)
                }
                x += 2
            }
            outY++
        }

        return DecodeStats(
            frameHeight = frameHeight,
            previewRows = outY,
            scalarMin = local.first,
            scalarMax = local.second,
            displayMin = local.first,
            displayMax = local.second,
            centerRaw = centerRaw,
            centerPointNative = centerPointNative,
            centerCelsius = nativeToCelsius(
                temperatureModel.rawToNativeLike(centerRaw),
                temperatureModel,
                nativeToCelsiusOverride,
            ),
            centerCelsiusMethod = temperatureModel.name,
            displayWindowMinCelsius = celsiusWindow.minCelsius,
            displayWindowMaxCelsius = celsiusWindow.maxCelsius,
            rectMinCelsius = nativeToCelsius(fullRectStats.minNative, temperatureModel, nativeToCelsiusOverride),
            rectMaxCelsius = nativeToCelsius(fullRectStats.maxNative, temperatureModel, nativeToCelsiusOverride),
            rectAvgCelsius = nativeToCelsius(fullRectStats.avgNative, temperatureModel, nativeToCelsiusOverride),
            rectMinNative = fullRectStats.minNative,
            rectMaxNative = fullRectStats.maxNative,
            rectAvgNative = fullRectStats.avgNative,
            rectMinX = fullRectStats.minX,
            rectMinY = fullRectStats.minY,
            rectMaxX = fullRectStats.maxX,
            rectMaxY = fullRectStats.maxY,
            lineMinNative = centerLineStats.minNative,
            lineMaxNative = centerLineStats.maxNative,
            lineAvgNative = centerLineStats.avgNative,
            lineMinCelsius = nativeToCelsius(centerLineStats.minNative, temperatureModel, nativeToCelsiusOverride),
            lineMaxCelsius = nativeToCelsius(centerLineStats.maxNative, temperatureModel, nativeToCelsiusOverride),
            lineAvgCelsius = nativeToCelsius(centerLineStats.avgNative, temperatureModel, nativeToCelsiusOverride),
            lineMinX = centerLineStats.minX,
            lineMinY = centerLineStats.minY,
            lineMaxX = centerLineStats.maxX,
            lineMaxY = centerLineStats.maxY,
            clippedLowCount = clippedLowCount,
            clippedHighCount = clippedHighCount,
            totalSamples = totalSamples,
            effectiveBits = effectiveBits(local.first, local.second),
        )
    }

    fun scanThermalScalarRange(
        frame: ByteBuffer,
        base: Int,
        frameWidth: Int,
        thermalRows: IntRange,
    ): Pair<Int, Int> {
        var sMin = Int.MAX_VALUE
        var sMax = Int.MIN_VALUE
        for (y in thermalRows) {
            val rowStart = base + y * frameWidth * 2
            var x = 0
            while (x < frameWidth) {
                val mac = rowStart + (x shr 1) * 4
                val s0 = readLe16RawAtMac(frame, mac, x)
                sMin = min(sMin, s0)
                sMax = max(sMax, s0)
                if (x + 1 < frameWidth) {
                    val s1 = readLe16RawAtMac(frame, mac, x + 1)
                    sMin = min(sMin, s1)
                    sMax = max(sMax, s1)
                }
                x += 2
            }
        }
        if (sMax < sMin) return 0 to 65535
        return sMin to sMax
    }

    fun scanThermalScalarRange(
        frame: ByteBuffer,
        frameWidth: Int,
        frameHeight: Int,
    ): Pair<Int, Int>? {
        if (frame.remaining() < expectedBytes(frameWidth, frameHeight)) return null
        val rows = thermalImageRowRange(frameHeight)
        if (rows.isEmpty()) return null
        return scanThermalScalarRange(frame, frame.position(), frameWidth, rows)
    }

    fun scanThermalCelsiusWindow(
        frame: ByteBuffer,
        frameWidth: Int,
        frameHeight: Int,
        temperatureModel: TemperatureModel,
        policy: DynamicWindowPolicy,
        nativeToCelsiusOverride: ((Int) -> Double)? = null,
    ): CelsiusWindow? {
        return scanThermalCelsiusWindowDetailed(
            frame,
            frameWidth,
            frameHeight,
            temperatureModel,
            policy,
            nativeToCelsiusOverride,
        ).window
    }

    fun scanThermalCelsiusWindowDetailed(
        frame: ByteBuffer,
        frameWidth: Int,
        frameHeight: Int,
        temperatureModel: TemperatureModel,
        policy: DynamicWindowPolicy,
        nativeToCelsiusOverride: ((Int) -> Double)? = null,
    ): CelsiusWindowScan {
        if (frame.remaining() < expectedBytes(frameWidth, frameHeight)) {
            return CelsiusWindowScan(null, 0, 0, null, null)
        }
        val rows = thermalImageRowRange(frameHeight)
        if (rows.isEmpty()) return CelsiusWindowScan(null, 0, 0, null, null)
        val base = frame.position()
        var minC = Double.POSITIVE_INFINITY
        var maxC = Double.NEGATIVE_INFINITY
        var inRangeCount = 0
        var totalSamples = 0
        val hist = IntArray(HIST_BINS)
        val hardSpan = policy.hardMaxCelsius - policy.hardMinCelsius
        for (y in rows) {
            val rowStart = base + y * frameWidth * 2
            var x = 0
            while (x < frameWidth) {
                val mac = rowStart + (x shr 1) * 4
                val c0Resolved = nativeToCelsius(
                    temperatureModel.rawToNativeLike(readLe16RawAtMac(frame, mac, x)),
                    temperatureModel,
                    nativeToCelsiusOverride,
                )
                totalSamples++
                if (c0Resolved in policy.hardMinCelsius..policy.hardMaxCelsius) {
                    minC = min(minC, c0Resolved)
                    maxC = max(maxC, c0Resolved)
                    inRangeCount++
                    val idx = (((c0Resolved - policy.hardMinCelsius) / hardSpan) * (HIST_BINS - 1))
                        .toInt()
                        .coerceIn(0, HIST_BINS - 1)
                    hist[idx]++
                }
                if (x + 1 < frameWidth) {
                    val c1Resolved = nativeToCelsius(
                        temperatureModel.rawToNativeLike(readLe16RawAtMac(frame, mac, x + 1)),
                        temperatureModel,
                        nativeToCelsiusOverride,
                    )
                    totalSamples++
                    if (c1Resolved in policy.hardMinCelsius..policy.hardMaxCelsius) {
                        minC = min(minC, c1Resolved)
                        maxC = max(maxC, c1Resolved)
                        inRangeCount++
                        val idx = (((c1Resolved - policy.hardMinCelsius) / hardSpan) * (HIST_BINS - 1))
                            .toInt()
                            .coerceIn(0, HIST_BINS - 1)
                        hist[idx]++
                    }
                }
                x += 2
            }
        }
        if (!minC.isFinite() || !maxC.isFinite()) {
            return CelsiusWindowScan(null, inRangeCount, totalSamples, null, null)
        }
        val lowCut = percentileFromHistogram(hist, inRangeCount, policy.lowerPercentile, policy)
        val highCut = percentileFromHistogram(hist, inRangeCount, policy.upperPercentile, policy)
        val baseMin = max(minC, lowCut)
        val baseMax = min(maxC, highCut)
        val window = if (baseMax - baseMin < policy.minSpanCelsius) {
            val center = (baseMin + baseMax) / 2.0
            val half = policy.minSpanCelsius / 2.0
            val expandedMin = max(policy.hardMinCelsius, center - half)
            val expandedMax = min(policy.hardMaxCelsius, center + half)
            if (expandedMax > expandedMin) {
                CelsiusWindow(expandedMin, expandedMax)
            } else {
                CelsiusWindow(policy.hardMinCelsius, policy.hardMaxCelsius)
            }
        } else {
            CelsiusWindow(baseMin, baseMax)
        }
        return CelsiusWindowScan(window, inRangeCount, totalSamples, minC, maxC)
    }

    fun getRectNativeStats(
        planeRaw16: IntArray,
        frameWidth: Int,
        frameHeight: Int,
        rectX0: Int,
        rectY0: Int,
        rectX1: Int,
        rectY1: Int,
        model: TemperatureModel,
        sideLength: Int = 3,
    ): NativeRectStats? {
        if (rectX1 < rectX0 || rectY1 < rectY0) return null
        if (rectX0 < 0 || rectY0 < 0 || rectX1 >= frameWidth || rectY1 >= frameHeight) return null
        var minNative = Int.MAX_VALUE
        var maxNative = Int.MIN_VALUE
        var minX = rectX0
        var minY = rectY0
        var maxX = rectX0
        var maxY = rectY0
        var sum = 0L
        var count = 0
        var y = rectY0
        while (y <= rectY1) {
            var x = rectX0
            while (x <= rectX1) {
                val nativeLike = getPointNativeStats(
                    planeRaw16 = planeRaw16,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    x = x,
                    y = y,
                    model = model,
                    sideLength = sideLength,
                ).native
                if (nativeLike < minNative) {
                    minNative = nativeLike
                    minX = x
                    minY = y
                }
                if (nativeLike > maxNative) {
                    maxNative = nativeLike
                    maxX = x
                    maxY = y
                }
                sum += nativeLike.toLong()
                count++
                x++
            }
            y++
        }
        if (count <= 0) return null
        val avgNative = ((sum + (count shr 1)) / count).toInt()
        return NativeRectStats(
            minNative = minNative,
            maxNative = maxNative,
            avgNative = avgNative,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            sampleCount = count,
        )
    }

    fun getPointNativeStats(
        planeRaw16: IntArray,
        frameWidth: Int,
        frameHeight: Int,
        x: Int,
        y: Int,
        model: TemperatureModel,
        sideLength: Int = 3,
    ): NativePointStats {
        val safeSide = normalizeSideLength(sideLength)
        val radius = safeSide / 2
        val centerRaw = planeRaw16[y * frameWidth + x]
        val centerNative = model.rawToNativeLike(centerRaw)
        var localMin = Int.MAX_VALUE
        var localMax = Int.MIN_VALUE
        var localSum = 0L
        var sampleCount = 0
        var ny = y - radius
        while (ny <= y + radius) {
            var nx = x - radius
            while (nx <= x + radius) {
                val sampleNative = if (nx in 0 until frameWidth && ny in 0 until frameHeight) {
                    model.rawToNativeLike(planeRaw16[ny * frameWidth + nx])
                } else {
                    // Native behavior uses center-value fill for out-of-bounds neighborhood cells.
                    centerNative
                }
                localMin = min(localMin, sampleNative)
                localMax = max(localMax, sampleNative)
                localSum += sampleNative.toLong()
                sampleCount++
                nx++
            }
            ny++
        }
        val denom = max(1, sampleCount - 2)
        val roundedNumerator = (localSum - localMin - localMax + (denom shr 1))
        val localNative = (roundedNumerator / denom).toInt()
        return NativePointStats(localNative)
    }

    fun getLineNativeStats(
        planeRaw16: IntArray,
        frameWidth: Int,
        frameHeight: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        model: TemperatureModel,
    ): NativeLineStats? {
        if (x0 !in 0 until frameWidth || x1 !in 0 until frameWidth) return null
        if (y0 !in 0 until frameHeight || y1 !in 0 until frameHeight) return null
        val points = bresenhamPoints(x0, y0, x1, y1)
        if (points.isEmpty()) return null
        var minNative = Int.MAX_VALUE
        var maxNative = Int.MIN_VALUE
        var minX = points[0].first
        var minY = points[0].second
        var maxX = points[0].first
        var maxY = points[0].second
        var sum = 0L
        for ((x, y) in points) {
            val nativeLike = sampleLineLocalNative(
                planeRaw16 = planeRaw16,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                x = x,
                y = y,
                x0 = x0,
                y0 = y0,
                x1 = x1,
                y1 = y1,
                model = model,
            )
            if (nativeLike < minNative) {
                minNative = nativeLike
                minX = x
                minY = y
            }
            if (nativeLike > maxNative) {
                maxNative = nativeLike
                maxX = x
                maxY = y
            }
            sum += nativeLike.toLong()
        }
        val count = points.size
        val avgNative = ((sum + (count shr 1)) / count).toInt()
        return NativeLineStats(
            minNative = minNative,
            maxNative = maxNative,
            avgNative = avgNative,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            sampleCount = count,
        )
    }

    private fun sampleLineLocalNative(
        planeRaw16: IntArray,
        frameWidth: Int,
        frameHeight: Int,
        x: Int,
        y: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        model: TemperatureModel,
    ): Int {
        val centerNative = model.rawToNativeLike(planeRaw16[y * frameWidth + x])
        val dx = x1 - x0
        val dy = y1 - y0
        val samples = IntArray(5)
        for (k in -2..2) {
            val idx = k + 2
            val sx: Int
            val sy: Int
            if (dy == 0) {
                sx = x + k
                sy = y
            } else if (dx == 0) {
                sx = x
                sy = y + k
            } else {
                val len = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                val ux = dx / len
                val uy = dy / len
                sx = (x + k * ux).toInt()
                sy = (y + k * uy).toInt()
            }
            samples[idx] = if (sx in 0 until frameWidth && sy in 0 until frameHeight) {
                model.rawToNativeLike(planeRaw16[sy * frameWidth + sx])
            } else {
                centerNative
            }
        }
        var localMin = Int.MAX_VALUE
        var localMax = Int.MIN_VALUE
        var localSum = 0
        for (v in samples) {
            localMin = min(localMin, v)
            localMax = max(localMax, v)
            localSum += v
        }
        return (localSum - localMin - localMax + 1) / 3
    }

    private fun bresenhamPoints(x0: Int, y0: Int, x1: Int, y1: Int): List<Pair<Int, Int>> {
        val points = ArrayList<Pair<Int, Int>>()
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val sx = if (x0 < x1) 1 else -1
        val dy = -kotlin.math.abs(y1 - y0)
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            points.add(x to y)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
        return points
    }

    private fun normalizeSideLength(sideLength: Int): Int {
        val bounded = sideLength.coerceIn(1, 15)
        return (bounded + (bounded and 1) - 1).coerceAtLeast(1)
    }

    private fun scanThermalScalarRange(planeRaw16: IntArray): Pair<Int, Int> {
        if (planeRaw16.isEmpty()) return 0 to 65535
        var sMin = Int.MAX_VALUE
        var sMax = Int.MIN_VALUE
        for (v in planeRaw16) {
            sMin = min(sMin, v)
            sMax = max(sMax, v)
        }
        if (sMax < sMin) return 0 to 65535
        return sMin to sMax
    }

    private fun nativeToCelsius(
        nativeLike: Int,
        model: TemperatureModel,
        nativeToCelsiusOverride: ((Int) -> Double)?,
    ): Double = nativeToCelsiusOverride?.invoke(nativeLike) ?: model.nativeLikeToCelsius(nativeLike)

    private fun extractThermalPlaneRaw16(
        frame: ByteBuffer,
        base: Int,
        frameWidth: Int,
        thermalRows: IntRange,
    ): IntArray {
        val thermalHeight = thermalRows.count()
        val plane = IntArray(frameWidth * thermalHeight)
        var outY = 0
        for (y in thermalRows) {
            val rowStart = base + y * frameWidth * 2
            var x = 0
            while (x < frameWidth) {
                val mac = rowStart + (x shr 1) * 4
                plane[outY * frameWidth + x] = readLe16RawAtMac(frame, mac, x)
                if (x + 1 < frameWidth) {
                    plane[outY * frameWidth + x + 1] = readLe16RawAtMac(frame, mac, x + 1)
                }
                x += 2
            }
            outY++
        }
        return plane
    }

    private fun percentileFromHistogram(
        hist: IntArray,
        sampleCount: Int,
        percentile: Double,
        policy: DynamicWindowPolicy,
    ): Double {
        if (sampleCount <= 0) return policy.hardMinCelsius
        val target = (sampleCount * percentile).toInt().coerceIn(0, sampleCount - 1)
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc > target) {
                val ratio = i.toDouble() / (hist.size - 1).toDouble()
                return policy.hardMinCelsius + ratio * (policy.hardMaxCelsius - policy.hardMinCelsius)
            }
        }
        return policy.hardMaxCelsius
    }

    /** One-time encoding comparison logged to uvc-debug.log */
    fun analyzeEncodings(
        frame: ByteBuffer,
        frameWidth: Int,
        frameHeight: Int,
    ): String {
        val rows = thermalImageRowRange(frameHeight)
        if (rows.isEmpty()) return "no thermal rows"
        val base = frame.position()
        val keys = listOf("y", "raw16", "bias", "m10", "m12", "m14")
        val mins = IntArray(keys.size) { Int.MAX_VALUE }
        val maxs = IntArray(keys.size) { Int.MIN_VALUE }
        for (y in rows) {
            val rowStart = base + y * frameWidth * 2
            for (x in 0 until frameWidth) {
                val mac = rowStart + (x shr 1) * 4
                if (mac + 3 >= frame.limit()) continue
                val y0 = frame.get(mac).toInt() and 0xFF
                val u = frame.get(mac + 1).toInt() and 0xFF
                val y1 = frame.get(mac + 2).toInt() and 0xFF
                val v = frame.get(mac + 3).toInt() and 0xFF
                val yv = if ((x and 1) == 0) y0 else y1
                val raw = readLe16RawAtMac(frame, mac, x)
                val vals = intArrayOf(
                    yv,
                    raw,
                    (raw - 0x8000) and 0xFFFF,
                    raw and 0x3FF,
                    raw and 0xFFF,
                    raw and 0x3FFF,
                )
                for (i in vals.indices) {
                    mins[i] = min(mins[i], vals[i])
                    maxs[i] = max(maxs[i], vals[i])
                }
            }
        }
        return buildString {
            keys.forEachIndexed { i, name ->
                val span = maxs[i] - mins[i]
                val bits = effectiveBits(mins[i], maxs[i])
                append("$name=${mins[i]}..${maxs[i]}(eff$bits) ")
            }
        }.trim()
    }

    fun thermalImageRowRange(frameHeight: Int): IntRange {
        if (frameHeight <= 0) return IntRange.EMPTY
        val start = when (frameHeight) {
            392 -> IMAGE_ROWS + INFO_ROWS
            384 -> IMAGE_ROWS
            else -> frameHeight / 2
        }
        val endExclusive = min(start + IMAGE_ROWS, frameHeight)
        return if (endExclusive > start) start until endExclusive else IntRange.EMPTY
    }

    fun bandRowsForHeight(frameHeight: Int): Int = when (frameHeight) {
        384 -> 192
        392 -> 196
        else -> frameHeight / 2
    }

    fun readLe16Raw(frame: ByteBuffer, base: Int, width: Int, y: Int, x: Int): Int {
        val mac = base + y * width * 2 + (x shr 1) * 4
        if (mac + 3 >= frame.limit()) return 0
        return readLe16RawAtMac(frame, mac, x)
    }

    private fun readLe16RawAtMac(frame: ByteBuffer, mac: Int, x: Int): Int {
        return if ((x and 1) == 0) {
            val lo = frame.get(mac).toInt() and 0xFF
            val hi = frame.get(mac + 1).toInt() and 0xFF
            lo or (hi shl 8)
        } else {
            val lo = frame.get(mac + 2).toInt() and 0xFF
            val hi = frame.get(mac + 3).toInt() and 0xFF
            lo or (hi shl 8)
        }
    }

    fun celsiusFromRaw(
        raw: Int,
        temperatureModel: TemperatureModel = TemperatureModel.debugFallbackDecode64(),
    ): Double = temperatureModel.rawToCelsius(raw)

    fun scalarToDisplay(scalar: Int, displayMin: Int, displayMax: Int): Int {
        if (displayMax <= displayMin) return 0
        return (((scalar - displayMin).toDouble() / (displayMax - displayMin)) * 255.0)
            .toInt()
            .coerceIn(0, 255)
    }

    fun celsiusToDisplay(celsius: Double, displayMinC: Double, displayMaxC: Double): Int {
        if (displayMaxC <= displayMinC) return 0
        return (((celsius - displayMinC) / (displayMaxC - displayMinC)) * 255.0)
            .toInt()
            .coerceIn(0, 255)
    }

    fun effectiveBits(min: Int, max: Int): Int {
        val span = max - min
        if (span <= 0) return 0
        return ceil(log2(span.toDouble() + 1.0)).toInt()
    }

    data class DecodeStats(
        val frameHeight: Int,
        val previewRows: Int,
        val scalarMin: Int,
        val scalarMax: Int,
        val displayMin: Int,
        val displayMax: Int,
        val centerRaw: Int,
        val centerPointNative: Int,
        val centerCelsius: Double,
        val centerCelsiusMethod: String,
        val displayWindowMinCelsius: Double,
        val displayWindowMaxCelsius: Double,
        val rectMinCelsius: Double,
        val rectMaxCelsius: Double,
        val rectAvgCelsius: Double,
        val rectMinNative: Int,
        val rectMaxNative: Int,
        val rectAvgNative: Int,
        val rectMinX: Int,
        val rectMinY: Int,
        val rectMaxX: Int,
        val rectMaxY: Int,
        val lineMinNative: Int,
        val lineMaxNative: Int,
        val lineAvgNative: Int,
        val lineMinCelsius: Double,
        val lineMaxCelsius: Double,
        val lineAvgCelsius: Double,
        val lineMinX: Int,
        val lineMinY: Int,
        val lineMaxX: Int,
        val lineMaxY: Int,
        val clippedLowCount: Int,
        val clippedHighCount: Int,
        val totalSamples: Int,
        val effectiveBits: Int,
    )

    private const val HIST_BINS = 256
}
