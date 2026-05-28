package com.vilos.irpanoview.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Lists Camera2 external (USB UVC) camera ids, sorted for stable index ↔ bus pairing. */
class ExternalCameraEnumerator(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun hasSystemExternalCameraFeature(): Boolean =
        context.packageManager.hasSystemFeature("android.hardware.camera.external")

    fun sortedExternalCameraIds(): List<String> {
        return try {
            cameraManager.cameraIdList
                .filter { id -> isExternal(id) }
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isExternal(cameraId: String): Boolean {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_EXTERNAL
        } catch (_: Exception) {
            false
        }
    }
}
