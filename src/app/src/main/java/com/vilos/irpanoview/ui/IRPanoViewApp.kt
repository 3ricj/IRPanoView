package com.vilos.irpanoview.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vilos.irpanoview.BuildConfig
import com.vilos.irpanoview.GracefulShutdown
import com.vilos.irpanoview.camera.hik.HikShutdownExperiment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vilos.irpanoview.vm.IRPanoViewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IRPanoViewApp(
    vm: IRPanoViewViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val exitRecipe = HikShutdownExperiment.activeRecipeState

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.onAppResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = { GracefulShutdown.request(context, "user exit") }) {
                        Text("Exit ${exitRecipe.id}·${BuildConfig.SHUTDOWN_BUILD_STAMP}")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.setSettingsOpen(true) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            AdaptiveCameraGrid(
                slots = state.slots,
                palette = state.palette,
                debugStatsEnabled = state.debugStatsEnabled,
                focusedStableKey = state.focusedStableKey,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onClickSlot = vm::toggleFocusOn,
            )
            ThermalScaleBar(
                palette = state.palette,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.settingsOpen) {
        SettingsBottomSheet(
            state = state,
            vm = vm,
            onDismiss = { vm.setSettingsOpen(false) },
        )
    }
}
