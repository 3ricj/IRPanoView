package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vilos.irpanoview.camera.ThermalFrameView

@Composable
fun PanoStreamView(
    modifier: Modifier = Modifier,
    onViewReady: (ThermalFrameView) -> Unit = {},
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            ThermalFrameView(context).also(onViewReady)
        },
    )
}
