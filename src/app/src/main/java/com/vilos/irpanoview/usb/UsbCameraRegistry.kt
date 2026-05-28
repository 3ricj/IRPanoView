package com.vilos.irpanoview.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.vilos.irpanoview.camera.hik.HikStartupMetrics
import com.vilos.irpanoview.camera.hik.HikUsbStackRecovery
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks supported thermal USB devices. Stable identity is [UsbDevice.getDeviceName]
 * (bus path, e.g. `/dev/bus/usb/002/005`) — not serial (not reliable on wire for this SKU).
 */
class UsbCameraRegistry(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<UsbDeviceState>>(emptyList())
    val devices: StateFlow<List<UsbDeviceState>> = _devices.asStateFlow()

    private val pendingDetachedPaths = ConcurrentHashMap.newKeySet<String>()

    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.parcelableDevice()
                    refresh()
                    if (dev != null && SupportedUsbCameras.matches(dev)) {
                        HikUsbStackRecovery.onHubAttached()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = intent.parcelableDevice()
                    if (dev != null && SupportedUsbCameras.matches(dev)) {
                        dev.deviceName?.let { pendingDetachedPaths.add(it) }
                    }
                    val prevCount = _devices.value.size
                    refresh()
                    if (prevCount > 0 && _devices.value.isEmpty()) {
                        HikUsbStackRecovery.onHubFullyDetached()
                    }
                }
                ACTION_USB_PERMISSION -> refresh()
            }
        }
    }

    /** Bus paths detached since the last consume (for [HikShutdownMode.UsbDetached]). */
    fun consumeDetachedPaths(): Set<String> {
        if (pendingDetachedPaths.isEmpty()) return emptySet()
        val snap = pendingDetachedPaths.toSet()
        pendingDetachedPaths.clear()
        return snap
    }

    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }
        refresh()
    }

    fun stop() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
    }

    fun refresh() {
        val list = usbManager.deviceList.values
            .filter { SupportedUsbCameras.matches(it) }
            .sortedBy { it.deviceName ?: "" }
            .map { dev ->
                UsbDeviceState(
                    device = dev,
                    hasPermission = usbManager.hasPermission(dev),
                )
            }
        _devices.value = list
        if (list.isNotEmpty()) {
            HikUsbStackRecovery.notifyHubDevicesPresent(context)
        }
        val permitted = list.count { it.hasPermission }
        HikStartupMetrics.mark(
            context,
            busPath = null,
            phase = "usb_refresh devices=${list.size} permitted=$permitted",
        )
    }

    fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val requestCode = (device.deviceName ?: device.deviceId.toString()).hashCode() and 0x7fffffff
        val pi = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    fun findDevice(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    fun requestPermissionsForAll() {
        refresh()
        _devices.value.forEach { if (!it.hasPermission) requestPermission(it.device) }
    }

    data class UsbDeviceState(
        val device: UsbDevice,
        val hasPermission: Boolean,
    )

    companion object {
        const val ACTION_USB_PERMISSION = "com.vilos.irpanoview.USB_PERMISSION"

        @Suppress("DEPRECATION")
        private fun Intent.parcelableDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }
}