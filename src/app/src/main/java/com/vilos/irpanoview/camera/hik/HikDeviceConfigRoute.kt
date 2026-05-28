package com.vilos.irpanoview.camera.hik

/**
 * Maps UVC SELECT tuple to DeviceConfig GET/SET wire channel.
 * Reference: py_re_framework/core/hik_uvc.py DeviceConfigRoute
 */
data class HikDeviceConfigRoute(
    val phase: Int,
    val sub: Int,
    val dataWvalue: Int,
    val getWireLen: Int,
    val setWireLen: Int,
    val cmdId: Int,
    val name: String,
) {
    companion object {
        val ROUTE_HARDWARE_SERVER = HikDeviceConfigRoute(
            phase = 0x01, sub = 0x04, dataWvalue = HikUvcConstants.WVALUE_MISC,
            getWireLen = 3, setWireLen = 3, cmdId = 0x7DE, name = "hardware_server",
        )
        val ROUTE_DEVICE_INFO = HikDeviceConfigRoute(
            phase = 0x01, sub = 0x01, dataWvalue = HikUvcConstants.WVALUE_MISC,
            getWireLen = 488, setWireLen = 488, cmdId = 0x7DB, name = "device_info",
        )
        val ROUTE_VIDEO_ADJUST = HikDeviceConfigRoute(
            phase = 0x02, sub = 0x06, dataWvalue = HikUvcConstants.WVALUE_IMAGE,
            getWireLen = 31, setWireLen = 41, cmdId = 0x7ED, name = "image_video_adjust",
        )
        val ROUTE_IMAGE_ENHANCE = HikDeviceConfigRoute(
            phase = 0x02, sub = 0x05, dataWvalue = HikUvcConstants.WVALUE_IMAGE,
            getWireLen = 79, setWireLen = 176, cmdId = 0x7EB, name = "image_enhancement",
        )
        val ROUTE_THERM_BASIC = HikDeviceConfigRoute(
            phase = 0x03, sub = 0x01, dataWvalue = HikUvcConstants.WVALUE_THERM,
            getWireLen = 80, setWireLen = 80, cmdId = 0x7EF, name = "therm_basic",
        )
    }
}
