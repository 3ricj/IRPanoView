package com.vilos.irpanoview.network

import com.vilos.irpanoview.BuildConfig

object PiConnectionManager {
    /** Default when Pi advertises mDNS on the LAN (see pi/deploy/avahi). */
    const val DEFAULT_PI_HOST = "irpanoview.local"

    const val UDP_PORT = 8765
    const val WS_PORT = 8766

    fun normalizeHost(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifBlank { defaultPiHost() }
    }

    fun defaultPiHost(): String =
        BuildConfig.PI_HOST.ifBlank { DEFAULT_PI_HOST }

    fun wsUrl(host: String = defaultPiHost()): String {
        val h = normalizeHost(host)
        return "ws://$h:$WS_PORT"
    }
}
