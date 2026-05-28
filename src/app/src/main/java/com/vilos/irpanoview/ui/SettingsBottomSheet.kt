package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.camera.hik.HikPreviewSettings
import com.vilos.irpanoview.model.QuadCameraOrder
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.util.PreviewSnapshotLogger
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

            Text("Display range", style = MaterialTheme.typography.titleMedium)
            Text(
                "Floor and ceiling bound the auto-adjusted palette window. " +
                    "The live display window shrinks within these limits based on scene content.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThermalRangeControls(
                floorCelsius = state.thermalFloorCelsius,
                ceilingCelsius = state.thermalCeilingCelsius,
                onRangeChange = vm::setThermalDisplayRange,
            )

            Text("Calibration", style = MaterialTheme.typography.titleMedium)
            if (!HikPreviewSettings.BLACK_REFERENCE_DESCOPED) {
                Text(
                    "Manual NUC (0x7E9) runs a one-shot shutter flat-field update. " +
                        "Auto-shutter (0x838/0x2001) is enabled when streaming starts and " +
                        "triggers NUC automatically when the firmware decides it is needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { vm.triggerManualNuc() },
                    enabled = state.usbDeviceCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Black reference (NUC)")
                }
            } else {
                Text(
                    "NUC / shutter calibration is temporarily disabled in this build " +
                        "(host path lacked command ack and stream recovery during flat-field).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "USB cameras bind by bus path (${state.usbDeviceCount} detected). " +
                    "Cell choice is stored per stable id.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { vm.refreshUsb() },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Refresh USB / request permission") }
            Text(
                PreviewSnapshotLogger.pullHint(LocalContext.current),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { vm.saveDebugSnapshots() },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Save debug snapshots now") }

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
                "Average the last N radiometric frames per camera (host-side noise reduction). " +
                    "Uses fewer frames at startup or after NUC until the buffer fills.",
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

            Text("Dynamic seam compensation", style = MaterialTheme.typography.titleMedium)
            Text(
                "Real-time dynamic temperature compensation aligns seams by marching Camera 1->2->3->4 " +
                    "using edge-strip means across a center row band.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.dynamicCompEnabled,
                    onClick = { vm.setDynamicCompEnabled(!state.dynamicCompEnabled) },
                    label = { Text(if (state.dynamicCompEnabled) "Enabled" else "Disabled") },
                )
            }
            var overlapSlider by remember(state.dynamicCompOverlapColumns) {
                mutableIntStateOf(state.dynamicCompOverlapColumns)
            }
            Text("Overlap edge columns (N): $overlapSlider")
            Slider(
                value = overlapSlider.toFloat(),
                onValueChange = { overlapSlider = it.toInt() },
                onValueChangeFinished = { vm.setDynamicCompOverlapColumns(overlapSlider) },
                valueRange = HikPreviewSettings.DYNAMIC_COMP_MIN_OVERLAP_COLUMNS.toFloat()..
                    HikPreviewSettings.DYNAMIC_COMP_MAX_OVERLAP_COLUMNS.toFloat(),
                steps = HikPreviewSettings.DYNAMIC_COMP_MAX_OVERLAP_COLUMNS -
                    HikPreviewSettings.DYNAMIC_COMP_MIN_OVERLAP_COLUMNS - 1,
            )
            var centerBandPercentSlider by remember(state.dynamicCompCenterBandRatio) {
                mutableIntStateOf((state.dynamicCompCenterBandRatio * 100.0).toInt())
            }
            Text("Center band rows: ${centerBandPercentSlider}%")
            Slider(
                value = centerBandPercentSlider.toFloat(),
                onValueChange = { centerBandPercentSlider = it.toInt() },
                onValueChangeFinished = {
                    vm.setDynamicCompCenterBandRatio(centerBandPercentSlider / 100.0)
                },
                valueRange = (HikPreviewSettings.DYNAMIC_COMP_MIN_CENTER_BAND_RATIO * 100.0).toFloat()..
                    (HikPreviewSettings.DYNAMIC_COMP_MAX_CENTER_BAND_RATIO * 100.0).toFloat(),
                steps = ((HikPreviewSettings.DYNAMIC_COMP_MAX_CENTER_BAND_RATIO -
                    HikPreviewSettings.DYNAMIC_COMP_MIN_CENTER_BAND_RATIO) * 100.0).toInt() - 1,
            )

            Text("Debug", style = MaterialTheme.typography.titleMedium)
            Text(
                "Show per-camera debug stats line (bus path, draw sequence, age, and staleness flags).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.debugStatsEnabled,
                    onClick = { vm.setDebugStatsEnabled(!state.debugStatsEnabled) },
                    label = { Text(if (state.debugStatsEnabled) "Debug stats: ON" else "Debug stats: OFF") },
                )
            }

            if (state.slots.isNotEmpty()) {
                Text("Grid position (1–4)", style = MaterialTheme.typography.titleMedium)
                state.slots.forEach { slot ->
                    var menuOpen by remember(slot.identity.stableKey) { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            QuadCameraOrder.displayTitle(slot.identity.serial, slot.identity.displayLabel),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text("Cell ${slot.preferredCell + 1} ▾")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                (0..3).forEach { cell ->
                                    DropdownMenuItem(
                                        text = { Text("Cell ${cell + 1}") },
                                        onClick = {
                                            vm.setPreferredCell(slot.identity.stableKey, cell)
                                            menuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text("Scene correction (setIrConfig)", style = MaterialTheme.typography.titleMedium)
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
