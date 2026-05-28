package com.vilos.irpanoview.camera.hik

/** UVC FID/EOF assembly → 200704 B wire payload (doc 07). */
enum class HikBulkFramingMode {
    UVC_EOF,
    ;

    val logTag: String get() = "uvc_eof"

    val preservePartialAcrossDrainSlices: Boolean get() = true

    companion object {
        val active: HikBulkFramingMode = UVC_EOF
    }
}
