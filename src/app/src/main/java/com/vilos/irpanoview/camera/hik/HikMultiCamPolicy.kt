package com.vilos.irpanoview.camera.hik

import com.vilos.irpanoview.model.QuadCameraOrder

/**
 * Roll out multi-camera Hik streaming incrementally: 1 → 2 → 3 → 4.
 *
 * When USB serials are known, cameras are ordered by [QuadCameraOrder] (suffix
 * 02 → 97 → 07 → 86). Unknown serials fall back to sorted bus path.
 *
 * Bump [ACTIVE_STREAM_LIMIT] after each step is stable on hardware.
 */
object HikMultiCamPolicy {

    const val ACTIVE_STREAM_LIMIT = 4

    fun sortedBusPaths(devices: List<String>): List<String> = devices.filter { it.isNotBlank() }.sorted()

    fun sortedBusPathsByCameraOrder(busPathAndSerial: List<Pair<String, String?>>): List<String> =
        busPathAndSerial
            .filter { it.first.isNotBlank() }
            .sortedWith(compareBy({ QuadCameraOrder.sortKey(it.second) }, { it.first }))
            .map { it.first }

    fun isStreamEnabled(busPath: String, allSortedBusPaths: List<String>): Boolean {
        if (busPath.isBlank()) return false
        val rank = allSortedBusPaths.indexOf(busPath)
        return rank in 0 until ACTIVE_STREAM_LIMIT
    }

    /** 0 = first (lowest bus id); -1 if not in list. */
    fun streamRank(busPath: String, allSortedBusPaths: List<String>): Int =
        allSortedBusPaths.indexOf(busPath)
}
