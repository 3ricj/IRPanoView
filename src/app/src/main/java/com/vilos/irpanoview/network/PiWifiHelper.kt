package com.vilos.irpanoview.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reach the Pi without system WiFi picker dialogs.
 * Probe IPv4 192.168.4.1 only; clear any leftover process network bind first.
 */
object PiWifiHelper {
    private const val TAG = "PiWifiHelper"

    /** Undo any prior bindProcessToNetwork (leftover WifiNetworkSpecifier). */
    fun clearProcessNetworkBind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.bindProcessToNetwork(null)
            Log.i(TAG, "cleared process network bind")
        } catch (e: Exception) {
            Log.w(TAG, "clear bind failed: ${e.message}")
        }
    }

    fun currentSsid(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }
        val info = caps.transportInfo as? WifiInfo
        val raw = info?.ssid ?: return null
        return raw.trim('"').takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    suspend fun piReachable(): Boolean = withContext(Dispatchers.IO) {
        // NEVER probe preview port 8769 — it is single-client and a probe steals the live stream.
        try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(
                    InetSocketAddress(PiConnectionManager.PI_AP_GATEWAY, PiConnectionManager.WS_PORT),
                    1_500,
                )
                Log.i(TAG, "Pi reachable on ${PiConnectionManager.WS_PORT}")
                true
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "probe ${PiConnectionManager.PI_AP_GATEWAY}:${PiConnectionManager.WS_PORT} → " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            false
        }
    }
}
