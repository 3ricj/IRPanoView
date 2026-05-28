package com.vilos.irpanoview.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal Camera2 preview on a [TextureView] for USB external cameras.
 */
class Camera2PreviewController(
    private val context: Context,
    private val cameraId: String,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("Cam2-$cameraId").apply { start() }
    private val handler = Handler(thread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var textureView: TextureView? = null
    private val opened = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun attach(textureView: TextureView, onFailure: (String) -> Unit) {
        this.textureView = textureView
        if (textureView.isAvailable) {
            openCamera(textureView.surfaceTexture!!, textureView.width, textureView.height, onFailure)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    openCamera(st, w, h, onFailure)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    close()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(st: SurfaceTexture, viewW: Int, viewH: Int, onFailure: (String) -> Unit) {
        if (!opened.compareAndSet(false, true)) return
        try {
            val size = choosePreviewSize(viewW.coerceAtLeast(320), viewH.coerceAtLeast(240))
            st.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(st)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    device.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                session.setRepeatingRequest(request, null, handler)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                onFailure("Capture session failed")
                                close()
                            }
                        },
                        handler,
                    )
                }

                override fun onDisconnected(device: CameraDevice) {
                    close()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    onFailure("Camera error $error")
                    close()
                }
            }, handler)
        } catch (e: SecurityException) {
            opened.set(false)
            onFailure("Camera permission required")
        } catch (e: Exception) {
            opened.set(false)
            onFailure(e.message ?: "Open failed")
        }
    }

    private fun choosePreviewSize(viewW: Int, viewH: Int): Size {
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val choices = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            choices
                .filter { it.width <= 1280 }
                .minByOrNull { kotlin.math.abs(it.width * viewH - it.height * viewW) }
                ?: Size(viewW, viewH)
        } catch (_: Exception) {
            Size(viewW, viewH)
        }
    }

    fun close() {
        if (!opened.getAndSet(false)) return
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        textureView?.surfaceTextureListener = null
        textureView = null
    }

    fun shutdown() {
        close()
        thread.quitSafely()
    }
}
