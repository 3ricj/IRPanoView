package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.network.PiControlClient
import com.vilos.irpanoview.network.ThermalStreamReceiver

@Composable
fun ConnectionBanner(
    piHost: String,
    piStatus: PiControlClient.PiStatus,
    streamStats: ThermalStreamReceiver.StreamStats,
    demoMode: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when {
                demoMode -> "Demo pano strip (no Pi)"
                piStatus.connected -> "Pi connected ($piHost) — stitch ${"%.1f".format(piStatus.stitchFps)} fps"
                else -> "Same WiFi as Pi ($piHost) — tap Connect"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Stream ${"%.1f".format(streamStats.fps)} fps · seq ${streamStats.lastSequence}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (streamStats.drops > 0) {
                Text(
                    text = "drops ${streamStats.drops}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        piStatus.lastError?.let { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect, enabled = !demoMode && !piStatus.connected) {
                Text("Connect Pi")
            }
            Button(onClick = onDisconnect, enabled = !demoMode && piStatus.connected) {
                Text("Disconnect")
            }
        }
        if (piStatus.cameras.isNotEmpty()) {
            Text(
                piStatus.cameras.joinToString("  ") { cam ->
                    "Cam${cam.slot}:${if (cam.streaming) "OK" else "—"} ${"%.0f".format(cam.fps)}fps"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
