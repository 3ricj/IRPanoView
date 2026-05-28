package com.vilos.irpanoview

import android.app.Application
import com.serenegiant.utils.UVCUtils
import com.vilos.irpanoview.camera.hik.HikShutdownExperiment
import com.vilos.irpanoview.camera.hik.HikStartupMetrics
import com.vilos.irpanoview.camera.hik.HikUsbLifecycle
import com.vilos.irpanoview.camera.hik.HikUsbStackRecovery
import com.vilos.irpanoview.usb.UsbCameraRegistry
import com.vilos.irpanoview.util.AgentDebugLog
import com.vilos.irpanoview.util.PreviewSnapshotLogger

class IRPanoViewApplication : Application() {

    lateinit var usbCameraRegistry: UsbCameraRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        UVCUtils.init(this)
        AgentDebugLog.install(this)
        HikUsbStackRecovery.install(this)
        HikUsbStackRecovery.awaitPostStopSettleBlocking(this)
        HikShutdownExperiment.install(this)
        HikStartupMetrics.markProcessStart(this)
        PreviewSnapshotLogger.markAppStarted(this)
        usbCameraRegistry = UsbCameraRegistry(this)
        HikUsbLifecycle.install(this)
        GracefulShutdown.install(this)
        usbCameraRegistry.start()
    }
}
