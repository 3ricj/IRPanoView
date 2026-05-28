package com.vilos.irpanoview.ui.layout

import com.vilos.irpanoview.model.CameraSlot

/**
 * Places [slots] into a 4-cell grid by [CameraSlot.preferredCell], resolving collisions
 * by probing the next free index clockwise.
 */
fun placeInGrid(slots: List<CameraSlot>): Array<CameraSlot?> {
    val out = arrayOfNulls<CameraSlot>(4)
    val ordered = slots.sortedWith(compareBy({ it.preferredCell }, { it.identity.stableKey }))
    for (s in ordered) {
        var c = s.preferredCell.coerceIn(0, 3)
        var tries = 0
        while (out[c] != null && tries < 4) {
            c = (c + 1) % 4
            tries++
        }
        if (out[c] == null) out[c] = s
    }
    return out
}
