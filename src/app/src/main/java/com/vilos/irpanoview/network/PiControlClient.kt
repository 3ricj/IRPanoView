package com.vilos.irpanoview.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PiControlClient {

    data class CameraHealth(
        val slot: Int,
        val streaming: Boolean,
        val fps: Double,
        val serial: String?,
        val error: String?,
    )

    data class PiStatus(
        val connected: Boolean = false,
        val cameras: List<CameraHealth> = emptyList(),
        /** Host compositor tick rate (status field compositor_hz). */
        val compositorHz: Double = 0.0,
        val demo: Boolean = false,
        val equalizationEnabled: Boolean = true,
        val previewClient: Boolean = false,
        val lastError: String? = null,
    ) {
        /** Alias for UI that still labels "stitch fps". */
        val stitchFps: Double get() = compositorHz
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val _status = MutableStateFlow(PiStatus())
    val status: StateFlow<PiStatus> = _status.asStateFlow()

    fun connect(url: String = PiConnectionManager.wsUrl()) {
        disconnect()
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = _status.value.copy(connected = true, lastError = null)
                sendJson(JSONObject().put("cmd", "get_status"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = _status.value.copy(connected = false, lastError = t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = _status.value.copy(connected = false)
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        _status.value = PiStatus()
    }

    fun setIrConfig(emissivity: Double, distanceM: Double, ambientC: Double) {
        sendJson(
            JSONObject()
                .put("cmd", "set_ir_config")
                .put("emissivity", emissivity)
                .put("distance_m", distanceM)
                .put("ambient_c", ambientC),
        )
    }

    fun triggerNuc() {
        sendJson(JSONObject().put("cmd", "trigger_nuc"))
    }

    fun setTemporalAverage(frames: Int) {
        sendJson(
            JSONObject()
                .put("cmd", "set_temporal_average")
                .put("frames", frames),
        )
    }

    fun setDisplayRange(floorC: Double, ceilingC: Double, auto: Boolean) {
        sendJson(
            JSONObject()
                .put("cmd", "set_display_range")
                .put("floor_c", floorC)
                .put("ceiling_c", ceilingC)
                .put("auto", auto),
        )
        android.util.Log.i(
            "PiControl",
            "set_display_range floor=$floorC ceiling=$ceilingC auto=$auto",
        )
    }

    fun setEqualization(enabled: Boolean, alpha: Double = 0.05) {
        sendJson(
            JSONObject()
                .put("cmd", "set_equalization")
                .put("enabled", enabled)
                .put("alpha", alpha),
        )
    }

    fun requestStatus() {
        sendJson(JSONObject().put("cmd", "get_status"))
    }

    private fun sendJson(obj: JSONObject) {
        webSocket?.send(obj.toString())
    }

    private fun parseMessage(text: String) {
        runCatching {
            val json = JSONObject(text)
            when (json.optString("cmd")) {
                "status" -> {
                    val cameras = mutableListOf<CameraHealth>()
                    val arr = json.optJSONArray("cameras")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            cameras += CameraHealth(
                                slot = c.optInt("slot"),
                                streaming = c.optBoolean("streaming"),
                                fps = c.optDouble("fps"),
                                serial = c.optString("serial").ifBlank { null },
                                error = c.optString("error").takeIf { it.isNotBlank() && it != "null" },
                            )
                        }
                    }
                    val hz = when {
                        json.has("compositor_hz") -> json.optDouble("compositor_hz")
                        else -> json.optDouble("stitch_fps")
                    }
                    _status.value = _status.value.copy(
                        connected = true,
                        cameras = cameras,
                        compositorHz = hz,
                        demo = json.optBoolean("demo"),
                        equalizationEnabled = if (json.has("eq_enabled")) {
                            json.optBoolean("eq_enabled")
                        } else {
                            _status.value.equalizationEnabled
                        },
                        previewClient = json.optBoolean("preview_client"),
                        lastError = null,
                    )
                }
                "ack" -> {
                    if (json.has("eq_enabled")) {
                        _status.value = _status.value.copy(
                            equalizationEnabled = json.optBoolean("eq_enabled"),
                        )
                    }
                }
                "hello" -> requestStatus()
            }
        }
    }
}
