package com.vilos.irpanoview.model

/**
 * Official-style false-color maps for thermal visualization (applied after tone mapping).
 * Not the same as the raw YUV palette — these tint the *display* scalar field.
 */
enum class ThermalColorPalette(val label: String) {
    WhiteHot("White hot"),
    BlackHot("Black hot"),
    Ironbow("Ironbow"),
    Rainbow("Rainbow"),
    RedHot("Red hot"),
    Lava("Lava"),
    Jet("Jet"),
    Plasma("Plasma"),
    Turbo("Turbo"),
    Cividis("Cividis"),
    Twilight("Twilight"),
    GreysInverted("Greys (inverted)"),
    Inferno("Inferno"),
    Magma("Magma"),
    Hot("Hot"),
    Viridis("Viridis"),
    Grayscale("Grayscale");

    companion object {
        fun default() = Jet
    }
}


/**
 * Stable identity for a USB camera on Android.
 *
 * **Use [busPath] / [stableKey]** for multi-camera binding — serial is not sent on
 * the vendor UVC init path for this hardware family.
 */
data class CameraIdentity(
    /** Persistent id, e.g. `usb:/dev/bus/usb/002/005` */
    val stableKey: String,
    /** [android.hardware.usb.UsbDevice.getDeviceName] — same port → same path */
    val busPath: String,
    /** USB string descriptor if OS exposes it; do not rely on for binding */
    val serial: String?,
    val vendorId: Int,
    val productId: Int,
    val displayLabel: String,
) {
    companion object {
        fun fromUsb(
            device: android.hardware.usb.UsbDevice,
            serial: String? = safeSerial(device),
        ): CameraIdentity {
            val path = device.deviceName ?: "unknown"
            return CameraIdentity(
                stableKey = stableKeyFromBusPath(path),
                busPath = path,
                serial = serial,
                vendorId = device.vendorId,
                productId = device.productId,
                displayLabel = displayLabelFromBusPath(path),
            )
        }

        fun stableKeyFromBusPath(deviceName: String) = "usb:$deviceName"

        fun displayLabelFromBusPath(deviceName: String): String {
            val parts = deviceName.trim('/').split('/')
            if (parts.size >= 2) {
                return "Bus ${parts[parts.size - 2]}:${parts[parts.size - 1]}"
            }
            return deviceName
        }

        private fun safeSerial(device: android.hardware.usb.UsbDevice): String? {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    device.serialNumber?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (_: SecurityException) {
                null
            }
        }

        /** Read serial when USB permission is granted; otherwise returns [cached]. */
        fun resolveSerial(
            device: android.hardware.usb.UsbDevice?,
            hasUsbPermission: Boolean,
            cached: String? = null,
        ): String? {
            if (device != null && hasUsbPermission) {
                return safeSerial(device) ?: cached
            }
            return cached
        }
    }

    /** Title line: `Camera 2 · EA6473497` when serial matches [QuadCameraOrder]. */
    fun titleWithSerial(serial: String? = this.serial): String = buildString {
        append(QuadCameraOrder.displayTitle(serial, displayLabel))
        serial?.let { append(" · ").append(it) }
    }
}

enum class ConnectionState { Disconnected, Connected }

data class CameraSlot(
    val identity: CameraIdentity,
    val connection: ConnectionState,
    /** Preferred grid cell 0..3; persisted. */
    val preferredCell: Int,
    /** Camera2 external id paired by sorted bus index; null if no preview yet */
    val externalCameraId: String? = null,
    val hasUsbPermission: Boolean = false,
    /** Hik path only: false when [HikMultiCamPolicy] defers this bus path. */
    val hikStreamEnabled: Boolean = true,
    /** 0-based rank among sorted bus paths; null for non-Hik slots. */
    val hikStreamRank: Int? = null,
    /** 1-based quad position when serial suffix matches [QuadCameraOrder]. */
    val cameraNumber: Int? = null,
)

