package com.vilos.irpanoview.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView

@Composable
fun ExternalCameraPreview(
    cameraId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var error by remember(cameraId) { mutableStateOf<String?>(null) }

    val controller = remember(cameraId) {
        Camera2PreviewController(context.applicationContext, cameraId)
    }

    DisposableEffect(cameraId) {
        onDispose { controller.shutdown() }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    controller.attach(this) { msg -> error = msg }
                }
            },
        )
        error?.let { msg ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
