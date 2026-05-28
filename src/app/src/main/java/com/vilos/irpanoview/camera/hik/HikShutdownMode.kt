package com.vilos.irpanoview.camera.hik

/**
 * How to tear down a [HikCameraController] session (MasterThermoDocs doc 06).
 *
 * - [SoftPause]: StopChannel — cancel bulk, short join; **keep** USB fd + claimed interfaces.
 * - [GracefulExit]: cancel-only stop per camera (serial), then one alt-0 + release + close.
 * - [UsbDetached]: Physical unplug — cancel (best effort) then close; no alt-0 on dead fd.
 */
enum class HikShutdownMode {
    SoftPause,
    GracefulExit,
    UsbDetached,
}
