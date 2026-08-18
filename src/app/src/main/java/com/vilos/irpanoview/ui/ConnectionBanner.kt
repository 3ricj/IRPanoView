package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.network.PiControlClient
import com.vilos.irpanoview.network.PreviewStreamReceiver

@Composable
fun ConnectionBanner(
    piHost: String,
    wifiSsid: String?,
    statusLine: String,
    piStatus: PiControlClient.PiStatus,
    streamStats: PreviewStreamReceiver.StreamStats,
    demoMode: Boolean,
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
                else -> statusLine
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = buildString {
                    append("Stream ${"%.1f".format(streamStats.fps)} fps")
                    if (streamStats.lastSequence >= 0) {
                        append(" · seq ${streamStats.lastSequence}")
                    }
                    append(" · $piHost")
                    wifiSsid?.let { append(" · WiFi $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
