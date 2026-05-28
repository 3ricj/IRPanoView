package com.vilos.irpanoview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.camera.ThermalColormap
import com.vilos.irpanoview.camera.ThermalLevelRegistry
import com.vilos.irpanoview.model.ThermalColorPalette
import kotlinx.coroutines.delay

@Composable
fun ThermalScaleBar(
    palette: ThermalColorPalette,
    modifier: Modifier = Modifier,
) {
    var window by remember { mutableStateOf(ThermalLevelRegistry.displayWindow()) }
    LaunchedEffect(Unit) {
        while (true) {
            window = ThermalLevelRegistry.displayWindow()
            delay(250)
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${palette.label} — cold ← → hot",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            val steps = 256
            val stepW = size.width / steps
            for (i in 0 until steps) {
                drawRect(
                    color = ThermalColormap.composeColor(palette, i),
                    topLeft = Offset(i * stepW, 0f),
                    size = Size(stepW + 1f, size.height),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${"%.1f".format(window.minCelsius)}°C",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${"%.1f".format(window.maxCelsius)}°C",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
