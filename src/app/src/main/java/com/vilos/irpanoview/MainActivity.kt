package com.vilos.irpanoview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.vilos.irpanoview.camera.hik.HikShutdownExperiment
import com.vilos.irpanoview.camera.hik.HikUsbLifecycle
import com.vilos.irpanoview.camera.hik.HikUsbStackRecovery
import com.vilos.irpanoview.ui.IRPanoViewApp
import com.vilos.irpanoview.ui.theme.IRPanoViewTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        (application as IRPanoViewApplication).usbCameraRegistry.refresh()
        (application as IRPanoViewApplication).usbCameraRegistry.requestPermissionsForAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            HikUsbStackRecovery.shouldBlockUsbAttachLaunch()
        ) {
            finish()
            return
        }
        enableEdgeToEdge()
        GracefulShutdown.waitForReferencePause()
        GracefulShutdown.resetForResume()
        HikShutdownExperiment.applyLaunchIntent(this, intent)
        val registry = (application as IRPanoViewApplication).usbCameraRegistry
        registry.start()
        handleUsbIntent(intent)
        ensureRuntimePermissions()
        registry.requestPermissionsForAll()
        setContent {
            IRPanoViewTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IRPanoViewApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            HikUsbStackRecovery.shouldBlockUsbAttachLaunch()
        ) {
            return
        }
        handleUsbIntent(intent)
        HikShutdownExperiment.applyLaunchIntent(this, intent)
    }

    override fun onDestroy() {
        // Reference leave fallback if Exit did not run pauseAll (doc 16 Scenario A).
        if (isFinishing && !GracefulShutdown.isPauseCompleted() && !GracefulShutdown.isPauseInFlight()) {
            HikUsbLifecycle.pauseAll(this, "activity finishing")
        }
        super.onDestroy()
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val registry = (application as IRPanoViewApplication).usbCameraRegistry
        registry.refresh()
        registry.requestPermissionsForAll()
    }

    private fun ensureRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.CAMERA
        }
        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }
    }
}
