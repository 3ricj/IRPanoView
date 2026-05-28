package com.vilos.irpanoview.camera.hik

import android.hardware.usb.UsbDevice

/**
 * Pure-USB stream delivery (MasterThermoDocs doc 06 steps 6-7).
 *
 * Sequence matches runtime trace init observation 20260523-133829:
 * negotiatePreStream → SET 0xBBC (libuvc VS commit) → poll 0x0600 → bulk IN.
 */
object HikStreamArm {

    data class Result(
        val preStreamOk: Boolean,
        val videoParamSet: Boolean,
        val altInterfaceSet: Boolean,
        val detail: String,
    )

    fun attemptArm(
        transport: HikUvcTransport,
        usb: HikUsbLink,
        device: UsbDevice,
    ): Result {
        HikUvcProtocol.negotiatePreStream(transport)
        val hs = HikUvcProtocol.hardwareServerStatus(transport)
        val status = hs.getOrNull(2)?.toInt()?.and(0xFF)
        if (status == null || status < 2) {
            return Result(false, false, false, "hardware server not ready (status=$status)")
        }

        // 0xBBC payload (0xA8 B) - host packing; wire delivery is UVC VS path below.
        HikVideoParam.buildStreamStartPayload()

        val vs = HikUvcStream.armStream(transport, usb, device)
        if (!vs.ok) {
            return Result(
                preStreamOk = true,
                videoParamSet = false,
                altInterfaceSet = vs.altInterfaceSet,
                detail = "hwStatus=$status vsFail=${vs.detail}",
            )
        }

        HikUvcProtocol.pollCommandState(transport)

        return Result(
            preStreamOk = true,
            videoParamSet = true,
            altInterfaceSet = vs.altInterfaceSet,
            detail = "hwStatus=$status ${vs.detail}",
        )
    }
}
