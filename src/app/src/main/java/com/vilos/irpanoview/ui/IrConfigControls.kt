package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.camera.hik.HikIrConfigSettings
import kotlin.math.roundToInt

@Composable
fun IrConfigControls(
    emissivity: Double,
    distanceM: Double,
    ambientCelsius: Double,
    onConfigChange: (emissivity: Double, distanceM: Double, ambientCelsius: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var emsSlider by remember(emissivity) {
        mutableFloatStateOf(emissivity.toFloat())
    }
    var distSlider by remember(distanceM) {
        mutableFloatStateOf(distanceM.toFloat())
    }
    var ambientSlider by remember(ambientCelsius) {
        mutableFloatStateOf(ambientCelsius.toFloat())
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Pushed to every active camera via setIrConfig (0x7EF). " +
                "Affects radiometric accuracy for the scene, not display scaling.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = "Emissivity ${formatEmissivity(emsSlider)}",
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = emsSlider,
            onValueChange = { emsSlider = snapEmissivity(it) },
            onValueChangeFinished = {
                onConfigChange(
                    emsSlider.toDouble(),
                    distSlider.toDouble(),
                    ambientSlider.toDouble(),
                )
            },
            valueRange = HikIrConfigSettings.MIN_EMISSIVITY.toFloat()..
                HikIrConfigSettings.MAX_EMISSIVITY.toFloat(),
            steps = ((HikIrConfigSettings.MAX_EMISSIVITY - HikIrConfigSettings.MIN_EMISSIVITY) * 100).roundToInt() - 1,
        )

        Text(
            text = "Distance ${formatDistance(distSlider)} m",
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = distSlider,
            onValueChange = { distSlider = snapDistance(it) },
            onValueChangeFinished = {
                onConfigChange(
                    emsSlider.toDouble(),
                    distSlider.toDouble(),
                    ambientSlider.toDouble(),
                )
            },
            valueRange = HikIrConfigSettings.MIN_DISTANCE_M.toFloat()..
                HikIrConfigSettings.MAX_DISTANCE_M.toFloat(),
            steps = ((HikIrConfigSettings.MAX_DISTANCE_M - HikIrConfigSettings.MIN_DISTANCE_M) * 2).roundToInt() - 1,
        )

        Text(
            text = "Ambient ${formatAmbient(ambientSlider)}°C",
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = ambientSlider,
            onValueChange = { ambientSlider = snapAmbient(it) },
            onValueChangeFinished = {
                onConfigChange(
                    emsSlider.toDouble(),
                    distSlider.toDouble(),
                    ambientSlider.toDouble(),
                )
            },
            valueRange = HikIrConfigSettings.MIN_AMBIENT_C.toFloat()..
                HikIrConfigSettings.MAX_AMBIENT_C.toFloat(),
            steps = ((HikIrConfigSettings.MAX_AMBIENT_C - HikIrConfigSettings.MIN_AMBIENT_C) * 2).roundToInt() - 1,
        )
    }
}

private fun snapEmissivity(value: Float): Float = (value * 100f).roundToInt() / 100f

private fun snapDistance(value: Float): Float = (value * 2f).roundToInt() / 2f

private fun snapAmbient(value: Float): Float = (value * 2f).roundToInt() / 2f

private fun formatEmissivity(value: Float): String = "%.2f".format(value)

private fun formatDistance(value: Float): String =
    if (value == value.roundToInt().toFloat()) {
        value.roundToInt().toString()
    } else {
        "%.1f".format(value)
    }

private fun formatAmbient(value: Float): String = "%.1f".format(value)
