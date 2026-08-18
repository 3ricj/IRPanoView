package com.vilos.irpanoview.network

import com.vilos.irpanoview.BuildConfig
import java.net.Inet4Address
import java.net.InetAddress

object PiConnectionManager {
    /** Default when Pi advertises mDNS on the LAN (see pi/deploy/avahi). */
    const val DEFAULT_PI_HOST = "192.168.4.1"

    /** Pi AP SSID / PSK (pi/deploy/hostapd.conf). */
    const val PI_WIFI_SSID = "IRPanoView"
    const val PI_WIFI_PSK = "irpanoview"
    /** Gateway on the Pi AP. */
    const val PI_AP_GATEWAY = "192.168.4.1"

    /** Legacy UDP IRPV (no longer emitted by Pi host for live view). */
    const val UDP_PORT = 8765
    const val WS_PORT = 8766
    /** Live U8 stitched pano preview (IRPR + IRP8). */
    const val PREVIEW_TCP_PORT = 8769

    fun normalizeHost(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifBlank { defaultPiHost() }
    }

    fun defaultPiHost(): String =
        BuildConfig.PI_HOST.ifBlank { DEFAULT_PI_HOST }

    /**
     * Resolve host to an IPv4 literal. Android often prefers AAAA for *.local /
     * dual-stack names; the Pi preview listener is IPv4-only on the AP path.
     */
    fun resolveIpv4Host(host: String): String {
        val h = normalizeHost(host)
        if (h.matches(IPV4_REGEX)) {
            return h
        }
        val all = InetAddress.getAllByName(h)
        val v4 = all.filterIsInstance<Inet4Address>().firstOrNull()
            ?: throw java.net.UnknownHostException("No IPv4 address for $h")
        return v4.hostAddress ?: throw java.net.UnknownHostException("No IPv4 address for $h")
    }

    fun wsUrl(host: String = defaultPiHost()): String {
        val h = normalizeHost(host)
        val target = runCatching { resolveIpv4Host(h) }.getOrDefault(h)
        return "ws://$target:$WS_PORT"
    }

    private val IPV4_REGEX = Regex(
        """^(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)$""",
    )
}
