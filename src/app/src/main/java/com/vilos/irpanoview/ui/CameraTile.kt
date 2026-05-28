package com.vilos.irpanoview.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vilos.irpanoview.IRPanoViewApplication
import com.vilos.irpanoview.camera.ExternalCameraPreview
import com.vilos.irpanoview.camera.UvcCameraController
import com.vilos.irpanoview.camera.UvcUsbPreview
import com.vilos.irpanoview.camera.hik.HikMultiCamPolicy
import com.vilos.irpanoview.camera.hik.HikDisplayTelemetryRegistry
import com.vilos.irpanoview.camera.hik.HikProtocolSupport
import com.vilos.irpanoview.camera.hik.HikTherm
import com.vilos.irpanoview.camera.hik.HikUsbPreview
import com.vilos.irpanoview.model.CameraIdentity
import com.vilos.irpanoview.model.CameraSlot
import com.vilos.irpanoview.model.ConnectionState
import com.vilos.irpanoview.camera.ThermalColormap
import com.vilos.irpanoview.model.ThermalColorPalette
import com.vilos.irpanoview.ui.layout.placeInGrid
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

private val ThermalPreviewAspectRatio =
    HikTherm.GRID_WIDTH.toFloat() / HikTherm.GRID_HEIGHT

@Composable
private fun ThermalPreviewFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(ThermalPreviewAspectRatio)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(Color.Black),
    ) {
        content()
    }
}

/** Size thermal preview to the largest fit box so the full 256×192 frame stays visible. */
@Composable
private fun ThermalPreviewFitArea(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    content: @Composable () -> Unit,
) {
    // Keep one stable layout tree — branching composables here disposes HikUsbPreview and
    // tears down the USB worker when toggling single-cam focus.
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val frameModifier = if (expanded && maxWidth / maxHeight > ThermalPreviewAspectRatio) {
            Modifier.fillMaxHeight().aspectRatio(ThermalPreviewAspectRatio)
        } else {
            Modifier.fillMaxWidth().aspectRatio(ThermalPreviewAspectRatio)
        }
        ThermalPreviewFrame(modifier = frameModifier, content = content)
    }
}

@Composable
fun CameraTile(
    slot: CameraSlot,
    palette: ThermalColorPalette,
    debugStatsEnabled: Boolean,
    modifier: Modifier = Modifier,
    expandedPreview: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val usbRegistry = (context.applicationContext as IRPanoViewApplication).usbCameraRegistry
    val usbList by usbRegistry.devices.collectAsStateWithLifecycle()
    val usbDevice = usbList.firstOrNull { it.device.deviceName == slot.identity.busPath }?.device
    val useCamera2 = slot.externalCameraId != null &&
        slot.connection == ConnectionState.Connected &&
        slot.hasUsbPermission
    val useUvc = !useCamera2 && slot.hasUsbPermission && usbDevice != null
    var uvcStatus by remember(slot.identity.stableKey) { mutableStateOf<String?>(null) }
    var hikFlowDebug by remember(slot.identity.stableKey) { mutableStateOf<String?>(null) }
    var hikCorrectionLabel by remember(slot.identity.stableKey) { mutableStateOf<String?>(null) }
    val serial = remember(usbDevice, slot.hasUsbPermission, slot.identity.serial) {
        CameraIdentity.resolveSerial(usbDevice, slot.hasUsbPermission, slot.identity.serial)
    }
    val isHikDevice = usbDevice?.let { HikProtocolSupport.isHikThermalDevice(it.vendorId, it.productId) } == true

    LaunchedEffect(slot.identity.busPath, useUvc, isHikDevice, debugStatsEnabled) {
        while (true) {
            if (useUvc && isHikDevice && debugStatsEnabled) {
                val snap = HikDisplayTelemetryRegistry.snapshot(slot.identity.busPath)
                hikFlowDebug = snap?.let {
                    "bus=${slot.identity.busPath} drawSeq=${it.viewDrawSeq} drawAge=${it.viewDrawAgeMs}ms " +
                        "updAge=${it.viewUpdateAgeMs}ms hash=${"%08x".format(it.viewDrawFingerprint)} " +
                        "gap=${it.seqGapActive} stale=${it.staleContentActive}"
                }
                val offset = snap?.dynamicCompOffsetC ?: 0.0
                hikCorrectionLabel = formatCorrection(offset)
            } else if (useUvc && isHikDevice) {
                hikFlowDebug = null
                val snap = HikDisplayTelemetryRegistry.snapshot(slot.identity.busPath)
                val offset = snap?.dynamicCompOffsetC ?: 0.0
                hikCorrectionLabel = formatCorrection(offset)
            } else {
                hikFlowDebug = null
                hikCorrectionLabel = null
            }
            delay(250)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(slot.identity.stableKey) {
                detectTapGestures(onTap = { onClick() })
            },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            ThermalPreviewFitArea(expanded = expandedPreview) {
                if (useCamera2) {
                    ExternalCameraPreview(
                        cameraId = slot.externalCameraId!!,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (useUvc) {
                    val isHik = HikProtocolSupport.isHikThermalDevice(usbDevice!!.vendorId, usbDevice.productId)
                    if (isHik && !slot.hikStreamEnabled) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(placeholderBrush(palette, slot.identity.stableKey.hashCode())),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = buildString {
                                    append("Stream paused\n")
                                    append("${HikMultiCamPolicy.ACTIVE_STREAM_LIMIT}-cam rollout")
                                    slot.hikStreamRank?.let { append("\nBus rank ${it + 1}") }
                                },
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    } else if (isHik) {
                        HikUsbPreview(
                            device = usbDevice,
                            palette = palette,
                            streamEnabled = slot.hikStreamEnabled,
                            modifier = Modifier.fillMaxSize(),
                            onStatusChange = { uvcStatus = it },
                        )
                    } else {
                        UvcUsbPreview(
                            device = usbDevice,
                            palette = palette,
                            modifier = Modifier.fillMaxSize(),
                            onStatusChange = { uvcStatus = it },
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(placeholderBrush(palette, slot.identity.stableKey.hashCode())),
                    )
                }
            }
        }
        CameraDebugPanel(
            slot = slot,
            serial = serial,
            uvcStatus = uvcStatus,
            hikFlowDebug = hikFlowDebug,
            hikCorrectionLabel = hikCorrectionLabel,
            usbDevicePresent = usbDevice != null,
            contextHasCamera2 = UvcCameraController.hasSystemCamera2External(context),
            isHikDevice = isHikDevice,
        )
    }
}

@Composable
private fun CameraDebugPanel(
    slot: CameraSlot,
    serial: String?,
    uvcStatus: String?,
    hikFlowDebug: String?,
    hikCorrectionLabel: String?,
    usbDevicePresent: Boolean,
    contextHasCamera2: Boolean,
    isHikDevice: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val titleLine = buildString {
            append(slot.identity.titleWithSerial(serial))
            if (isHikDevice && !hikCorrectionLabel.isNullOrBlank()) {
                append(" · ")
                append(hikCorrectionLabel)
            }
        }
        Text(
            text = titleLine,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val statusLine = buildString {
            if (!slot.hasUsbPermission) append("USB permission needed")
            else if (slot.externalCameraId == null && !usbDevicePresent) append("USB device missing")
            else if (slot.externalCameraId == null && !contextHasCamera2) {
                if (!slot.hikStreamEnabled && slot.hikStreamRank != null) {
                    append("Paused (${HikMultiCamPolicy.ACTIVE_STREAM_LIMIT}-cam mode, rank ${slot.hikStreamRank + 1})")
                } else if (uvcStatus != null) append(uvcStatus)
            } else if (slot.externalCameraId == null) append("No Camera2 preview")
        }
        if (statusLine.isNotEmpty()) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!hikFlowDebug.isNullOrEmpty()) {
            Text(
                text = hikFlowDebug,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatCorrection(offsetC: Double): String {
    val sign = if (offsetC >= 0.0) "+" else "-"
    return "$sign${"%.1f".format(offsetC.absoluteValue)}c"
}

private fun placeholderBrush(palette: ThermalColorPalette, seed: Int): Brush {
    val c1 = ThermalColormap.composeColor(palette, 0)
    val c2 = ThermalColormap.composeColor(palette, 255)
    val shift = (seed and 0xFF) / 512f
    return Brush.linearGradient(
        colors = listOf(c1, c2.copy(alpha = 0.6f + shift)),
    )
}

@Composable
fun EmptyCameraCell(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxHeight(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            ThermalPreviewFrame(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.DarkGray.copy(alpha = 0.35f)),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No camera",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(6.dp),
        ) {
            Text(
                "Empty slot",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun AdaptiveCameraGrid(
    slots: List<CameraSlot>,
    palette: ThermalColorPalette,
    debugStatsEnabled: Boolean,
    modifier: Modifier = Modifier,
    focusedStableKey: String? = null,
    onClickSlot: (String) -> Unit,
) {
    if (slots.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No cameras connected.\nPlug in USB thermal cameras (hub port = identity).\nSettings → Grant USB or Demo streams.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val placed = remember(slots) { placeInGrid(slots) }
    val columnGap = 4.dp
    val cellPadding = 2.dp
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columnWidth = (maxWidth - columnGap * 3) / 4
        for (idx in 0..3) {
            val slot = placed[idx]
            val stableKey = slot?.identity?.stableKey
            val isFocusedMode = focusedStableKey != null
            val isThisFocused = stableKey != null && stableKey == focusedStableKey
            val cellModifier = when {
                !isFocusedMode -> Modifier
                    .fillMaxHeight()
                    .width(columnWidth)
                    .offset(x = (columnWidth + columnGap) * idx)
                    .padding(horizontal = cellPadding)
                isThisFocused -> Modifier.fillMaxSize()
                else -> Modifier.size(0.dp)
            }
            Box(modifier = cellModifier) {
                if (slot != null) {
                    key(stableKey) {
                        CameraTile(
                            slot = slot,
                            palette = palette,
                            debugStatsEnabled = debugStatsEnabled,
                            modifier = Modifier.fillMaxSize(),
                            expandedPreview = isThisFocused,
                            onClick = { onClickSlot(slot.identity.stableKey) },
                        )
                    }
                } else if (!isFocusedMode) {
                    EmptyCameraCell(Modifier.fillMaxSize())
                }
            }
        }
    }
}
