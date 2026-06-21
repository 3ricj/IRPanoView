package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.camera.hik.HikPreviewSettings
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.network.PiConnectionManager
import com.vilos.irpanoview.vm.IRPanoViewUiState
import com.vilos.irpanoview.vm.IRPanoViewViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsBottomSheet(
    state: IRPanoViewUiState,
    vm: IRPanoViewViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)

            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text(
                "Phone and Pi on the same WiFi AP. Default host is ${PiConnectionManager.DEFAULT_PI_HOST} " +
                    "(mDNS) or set the Pi's LAN IP / DHCP reservation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var piHostField by remember(state.piHost) { mutableStateOf(state.piHost) }
            OutlinedTextField(
                value = piHostField,
                onValueChange = { piHostField = it },
                label = { Text("Pi host") },
                placeholder = { Text(PiConnectionManager.DEFAULT_PI_HOST) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { vm.setPiHost(piHostField) }) {
                    Text("Apply Pi host")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.demoMode,
                    onClick = { vm.setDemoMode(!state.demoMode) },
                    label = { Text(if (state.demoMode) "Demo mode: ON" else "Demo mode: OFF") },
                )
            }

            Text("Display range", style = MaterialTheme.typography.titleMedium)
            Text(
                "Floor and ceiling bound the auto-adjusted palette window locally on the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThermalRangeControls(
                floorCelsius = state.thermalFloorCelsius,
                ceilingCelsius = state.thermalCeilingCelsius,
                onRangeChange = vm::setThermalDisplayRange,
            )

            Text("Calibration", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { vm.triggerManualNuc() },
                enabled = state.piConnected && !state.demoMode,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Black reference (NUC) on Pi")
            }

            Text("Color palette", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThermalColorPalette.entries.forEach { p ->
                    FilterChip(
                        selected = state.palette == p,
                        onClick = { vm.setPalette(p) },
                        label = { Text(p.label) },
                    )
                }
            }

            Text("Temporal averaging", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applied on the Pi before stitching (host-side noise reduction).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var avgSlider by remember(state.temporalAverageFrames) {
                mutableIntStateOf(state.temporalAverageFrames)
            }
            Text("Frames to average: $avgSlider")
            Slider(
                value = avgSlider.toFloat(),
                onValueChange = { avgSlider = it.toInt() },
                onValueChangeFinished = { vm.setTemporalAverageFrames(avgSlider) },
                valueRange = HikPreviewSettings.MIN_TEMPORAL_AVERAGE_FRAMES.toFloat()..
                    HikPreviewSettings.MAX_TEMPORAL_AVERAGE_FRAMES.toFloat(),
                steps = HikPreviewSettings.MAX_TEMPORAL_AVERAGE_FRAMES -
                    HikPreviewSettings.MIN_TEMPORAL_AVERAGE_FRAMES - 1,
            )

            Text("Scene correction (setIrConfig → Pi)", style = MaterialTheme.typography.titleMedium)
            IrConfigControls(
                emissivity = state.irEmissivity,
                distanceM = state.irDistanceM,
                ambientCelsius = state.irAmbientCelsius,
                onConfigChange = vm::setIrConfig,
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}
