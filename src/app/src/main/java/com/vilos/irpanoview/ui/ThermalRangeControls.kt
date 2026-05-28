package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.camera.ThermalDisplaySettings
import kotlin.math.roundToInt

@Composable
fun ThermalRangeControls(
    floorCelsius: Double,
    ceilingCelsius: Double,
    onRangeChange: (floorC: Double, ceilingC: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sensorMin = ThermalDisplaySettings.sensorMinC.toFloat()
    val sensorMax = ThermalDisplaySettings.sensorMaxC.toFloat()
    val minSpan = ThermalDisplaySettings.MIN_SPAN_C.toFloat()

    var floorSlider by remember(floorCelsius) {
        mutableFloatStateOf(floorCelsius.toFloat())
    }
    var ceilingSlider by remember(ceilingCelsius) {
        mutableFloatStateOf(ceilingCelsius.toFloat())
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Floor ${formatTemp(floorSlider)}°C",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = floorSlider,
                    onValueChange = { candidate ->
                        val snapped = snapTemp(candidate)
                        val maxFloor = (ceilingSlider - minSpan).coerceAtLeast(sensorMin)
                        floorSlider = snapped.coerceIn(sensorMin, maxFloor)
                    },
                    onValueChangeFinished = {
                        onRangeChange(floorSlider.toDouble(), ceilingSlider.toDouble())
                    },
                    valueRange = sensorMin..(sensorMax - minSpan),
                    steps = sliderSteps(sensorMin, sensorMax - minSpan),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ceiling ${formatTemp(ceilingSlider)}°C",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = ceilingSlider,
                    onValueChange = { candidate ->
                        val snapped = snapTemp(candidate)
                        val minCeiling = (floorSlider + minSpan).coerceAtMost(sensorMax)
                        ceilingSlider = snapped.coerceIn(minCeiling, sensorMax)
                    },
                    onValueChangeFinished = {
                        onRangeChange(floorSlider.toDouble(), ceilingSlider.toDouble())
                    },
                    valueRange = (sensorMin + minSpan)..sensorMax,
                    steps = sliderSteps(sensorMin + minSpan, sensorMax),
                )
            }
        }
    }
}

private fun snapTemp(value: Float): Float = (value * 2f).roundToInt() / 2f

private fun formatTemp(value: Float): String = "%.1f".format(value)

private fun sliderSteps(min: Float, max: Float): Int {
    val span = max - min
    if (span <= 0f) return 0
    return (span * 2f).roundToInt().coerceAtLeast(1) - 1
}
