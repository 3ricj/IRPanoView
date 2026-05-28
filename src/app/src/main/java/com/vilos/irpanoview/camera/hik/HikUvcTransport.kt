package com.vilos.irpanoview.camera.hik

/** USB control-plane transport for Hik DeviceConfig (injected by host). */
interface HikUvcTransport {
    val windex: Int

    fun controlWrite(bm: Int, breq: Int, wvalue: Int, windex: Int, data: ByteArray)

    fun controlRead(bm: Int, breq: Int, wvalue: Int, windex: Int, length: Int): ByteArray
}
