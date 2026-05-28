package com.vilos.irpanoview.camera.hik

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HikUvcBulkReassemblerTest {

    private fun uvcAssembler(): HikUvcBulkReassembler =
        HikUvcBulkReassembler().also { it.skipBoundarySyncForTest() }

    @Test
    fun uvcEof_5020BytePackets_fullFrame() {
        val golden = loadGoldenFrame()
        val packets = HikUvcBulkReassembler.packetizeFrame(
            golden,
            bulkTransferBytes = 5020,
            deviceHighBit = true,
        )

        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcEof_deviceBmMask_80bit() {
        val golden = loadGoldenFrame()
        val packets = HikUvcBulkReassembler.packetizeFrame(
            golden,
            bulkTransferBytes = 5020,
            deviceHighBit = true,
        )
        for (p in packets) {
            val bm = p[1].toInt() and 0xFF
            assertTrue("expected device high bit", bm and 0x80 != 0)
        }

        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcEof_fidFlipWithoutEof() {
        val golden = loadGoldenFrame()
        val packets = HikUvcBulkReassembler.packetizeFrame(golden, bulkTransferBytes = 5034).toMutableList()
        val last = packets.last()
        val noEof = last.copyOf(last.size)
        noEof[1] = (noEof[1].toInt() and HikUvcBulkReassembler.BM_EOF.inv()).toByte()
        packets[packets.lastIndex] = noEof

        val asm = HikUvcBulkReassembler().also { it.releaseAwaitingFrameBoundary() }
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        assertNull(completed)

        val frame2Packets = HikUvcBulkReassembler.packetizeFrame(
            golden,
            bulkTransferBytes = 5034,
            fid = false,
        )
        for (packet in frame2Packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        assertNull(completed)

        val frame3Packets = HikUvcBulkReassembler.packetizeFrame(
            golden,
            bulkTransferBytes = 5034,
            fid = true,
        )
        for (packet in frame3Packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcEof_midStreamJoin_rejectsShortFrame() {
        val golden = loadGoldenFrame()
        val packets = HikUvcBulkReassembler.packetizeFrame(golden, bulkTransferBytes = 5034)
        val asm = uvcAssembler()

        asm.feed(packets[5], packets[5].size)
        var completed: ByteArray? = null
        for (i in 6 until packets.size) {
            completed = asm.feed(packets[i], packets[i].size) ?: completed
        }
        assertNull(completed)

        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcEof_wireLeader_trimTo200704() {
        val golden = loadGoldenFrame()
        val packets = packetizeWirePayload(golden, wireLeader = true, deviceHighBit = true)
        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcEof_eofAt200704_emitsWireWithoutPadding() {
        val golden = loadGoldenFrame()
        val packets = packetizeRawPayload(
            payload = golden,
            bulkTransferBytes = 5020,
            eofOnLast = true,
            deviceHighBit = true,
        )
        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcEof_compositeWireRadioBandDecodes() {
        val wire = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 21.0)
        val packets = packetizeRawPayload(
            payload = wire,
            bulkTransferBytes = 5020,
            eofOnLast = true,
            deviceHighBit = true,
        )
        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertEquals(HikTherm.FRAME_BYTES, completed.size)
        assertTrue(HikTherm.looksLikeRadiometricWire(completed))
        assertEquals(21.0, HikTherm.celsiusAtPixel(completed, 128, 96), 0.05)
    }

    @Test
    fun uvcEof_noMagicPrefixStillDecodes() {
        val golden = loadGoldenFrame()
        golden[0] = 0x7c.toByte()
        golden[1] = 0x14.toByte()
        golden[2] = 0x7a.toByte()
        golden[3] = 0x14.toByte()
        val packets = packetizeRawPayload(
            payload = golden,
            bulkTransferBytes = 5020,
            eofOnLast = true,
            deviceHighBit = true,
        )
        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertEquals(HikTherm.FRAME_BYTES, completed.size)
        assertTrue(HikTherm.looksLikeRadiometricWire(completed))
        assertEquals(
            HikTherm.celsiusAtPixel(golden, HikTherm.GRID_WIDTH / 2, HikTherm.GRID_HEIGHT / 2),
            HikTherm.celsiusAtPixel(completed, HikTherm.GRID_WIDTH / 2, HikTherm.GRID_HEIGHT / 2),
            0.01,
        )
    }

    @Test
    fun uvcEof_headerOnlyGap_betweenFrames() {
        val golden = loadGoldenFrame()
        val packets = HikUvcBulkReassembler.packetizeFrame(golden, bulkTransferBytes = 5034)
        val asm = uvcAssembler()

        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)

        val headerOnly = byteArrayOf(2, (HikUvcBulkReassembler.BM_EOF or 0x80).toByte())
        asm.feed(headerOnly, headerOnly.size)

        var completed2: ByteArray? = null
        for (packet in packets) {
            completed2 = asm.feed(packet, packet.size) ?: completed2
        }
        requireNotNull(completed2)
        assertArrayEquals(golden, completed2)
    }

    @Test
    fun uvcEof_drainSlicePreservesPartial() {
        val golden = loadGoldenFrame()
        val packets = HikUvcBulkReassembler.packetizeFrame(golden, bulkTransferBytes = 5034)
        val asm = uvcAssembler()

        var completed: ByteArray? = null
        val half = packets.size / 2
        for (i in 0 until half) {
            completed = asm.feed(packets[i], packets[i].size) ?: completed
        }
        assertNull(completed)
        assertTrue(asm.stats.payloadBytesInFrame > 0)

        for (i in half until packets.size) {
            completed = asm.feed(packets[i], packets[i].size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcSync_allowsSwrfFidToggle() {
        val asm = HikUvcBulkReassembler().also { it.releaseAwaitingFrameBoundary() }
        val syncEof = byteArrayOf(2, (HikUvcBulkReassembler.BM_EOF or 0x80).toByte())
        asm.feed(syncEof, syncEof.size)

        val swrfStart = ByteArray(64)
        swrfStart[0] = 2
        swrfStart[1] = (HikUvcBulkReassembler.BM_FID or 0x80).toByte()
        swrfStart[2] = 0x73
        swrfStart[3] = 0x77
        swrfStart[4] = 0x82.toByte()
        swrfStart[5] = 0x70.toByte()

        assertNull(asm.feed(swrfStart, swrfStart.size))
        assertEquals("uvc_sync_fid_toggle", asm.consumeLastFramingEvent()?.kind)
    }

    @Test
    fun uvcEof_acceptsAndNormalizesJumboFrame() {
        val asm = HikUvcBulkReassembler().also { it.skipBoundarySyncForTest() }
        asm.retainJumboHeaderSlices = true
        val canonical = HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 24.0)
        val header = ByteArray(HikUvcWireLayout.JUMBO_BASE_HEADER_BYTES) { i -> (i and 0xFF).toByte() }
        header[0] = 0x73
        header[1] = 0x77
        header[2] = 0x82.toByte()
        header[3] = 0x70.toByte()
        val jumbo = buildJumboFrame(canonical, header)

        val packets = packetizeRawPayload(
            payload = jumbo,
            bulkTransferBytes = 5020,
            eofOnLast = true,
            deviceHighBit = true,
        )
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertEquals(HikTherm.FRAME_BYTES, completed.size)
        assertArrayEquals(
            canonical.copyOfRange(HikUvcWireLayout.RADIO_OFFSET, HikUvcWireLayout.RADIO_OFFSET + HikUvcWireLayout.RADIO_BYTES),
            completed.copyOfRange(HikUvcWireLayout.RADIO_OFFSET, HikUvcWireLayout.RADIO_OFFSET + HikUvcWireLayout.RADIO_BYTES),
        )
        assertArrayEquals(
            canonical.copyOfRange(HikUvcWireLayout.VISIBLE_OFFSET, HikUvcWireLayout.VISIBLE_OFFSET + HikUvcWireLayout.VISIBLE_BYTES),
            completed.copyOfRange(HikUvcWireLayout.VISIBLE_OFFSET, HikUvcWireLayout.VISIBLE_OFFSET + HikUvcWireLayout.VISIBLE_BYTES),
        )
        assertTrue(
            completed.copyOfRange(HikUvcWireLayout.FOOTER1_OFFSET, HikUvcWireLayout.FOOTER1_OFFSET + HikUvcWireLayout.FOOTER1_BYTES)
                .all { it == 0.toByte() },
        )
        assertTrue(
            completed.copyOfRange(HikUvcWireLayout.FOOTER2_OFFSET, HikUvcWireLayout.FOOTER2_OFFSET + HikUvcWireLayout.FOOTER2_BYTES)
                .all { it == 0.toByte() },
        )
        assertArrayEquals(header, asm.consumeLastJumboHeader())
    }

    @Test
    fun uvcEof_rejectsMalformedOversizeFrame() {
        val asm = HikUvcBulkReassembler().also { it.skipBoundarySyncForTest() }
        val malformed = ByteArray(HikUvcWireLayout.JUMBO_TOTAL_MIN_BYTES + 48) { 0x11 }
        val packets = packetizeRawPayload(
            payload = malformed,
            bulkTransferBytes = 5020,
            eofOnLast = true,
            deviceHighBit = true,
        )
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        assertNull(completed)
        assertEquals("uvc_size_over", asm.consumeLastFramingEvent()?.kind ?: "")
    }

    @Test
    fun extractUvcWirePayload_wireLeaderAndDirect() {
        val golden = loadGoldenFrame()
        val asm = uvcAssembler()

        assertArrayEquals(golden, asm.extractUvcWirePayload(golden))

        val withLeader = ByteArray(HikUvcBulkReassembler.WIRE_LEADER_BYTES + golden.size)
        withLeader[0] = 0x73
        withLeader[1] = 0x77
        golden.copyInto(withLeader, HikUvcBulkReassembler.WIRE_LEADER_BYTES)
        assertArrayEquals(golden, asm.extractUvcWirePayload(withLeader))
    }

    private fun packetizeRawPayload(
        payload: ByteArray,
        bulkTransferBytes: Int = 5034,
        eofOnLast: Boolean = true,
        deviceHighBit: Boolean = false,
    ): List<ByteArray> {
        val headerLen = 2
        val maxPayload = bulkTransferBytes - headerLen
        val packets = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val chunk = minOf(maxPayload, payload.size - offset)
            val isLast = offset + chunk >= payload.size
            val packet = ByteArray(headerLen + chunk)
            packet[0] = headerLen.toByte()
            var bm = HikUvcBulkReassembler.BM_FID
            if (isLast && eofOnLast) bm = bm or HikUvcBulkReassembler.BM_EOF
            if (deviceHighBit) bm = bm or 0x80
            packet[1] = bm.toByte()
            payload.copyInto(packet, headerLen, offset, offset + chunk)
            packets.add(packet)
            offset += chunk
        }
        return packets
    }

    @Test
    fun feed_reassemblesWireLeaderPrefixOnBulkPath() {
        val golden = loadGoldenFrame()
        val packets = packetizeWirePayload(golden, wireLeader = true)
        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    @Test
    fun uvcEof_reassemblesWireLeaderPrefixOnBulkPath() {
        val golden = loadGoldenFrame()
        val packets = packetizeWirePayload(golden, wireLeader = true, deviceHighBit = true)
        val asm = uvcAssembler()
        var completed: ByteArray? = null
        for (packet in packets) {
            completed = asm.feed(packet, packet.size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    private fun packetizeWirePayload(
        golden: ByteArray,
        wireLeader: Boolean,
        bulkTransferBytes: Int = 5034,
        deviceHighBit: Boolean = false,
    ): List<ByteArray> {
        val payload = if (wireLeader) {
            ByteArray(HikUvcBulkReassembler.WIRE_LEADER_BYTES + golden.size).also {
                it[0] = 0x73
                it[1] = 0x77
                golden.copyInto(it, HikUvcBulkReassembler.WIRE_LEADER_BYTES)
            }
        } else {
            golden
        }
        val headerLen = 2
        val maxPayload = bulkTransferBytes - headerLen
        val packets = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val chunk = minOf(maxPayload, payload.size - offset)
            val isLast = offset + chunk >= payload.size
            val packet = ByteArray(headerLen + chunk)
            packet[0] = headerLen.toByte()
            var bm = HikUvcBulkReassembler.BM_FID
            if (isLast) bm = bm or HikUvcBulkReassembler.BM_EOF
            if (deviceHighBit) bm = bm or 0x80
            packet[1] = bm.toByte()
            payload.copyInto(packet, headerLen, offset, offset + chunk)
            packets.add(packet)
            offset += chunk
        }
        return packets
    }

    @Test
    fun feed_carriesIncompleteUvcHeaderAcrossBulkReads() {
        val golden = loadGoldenFrame()
        val packets = HikUvcBulkReassembler.packetizeFrame(golden, bulkTransferBytes = 5034)
        val first = packets.first()
        val headByte = first.copyOfRange(0, 1)

        val asm = uvcAssembler()
        assertEquals(null, asm.feed(headByte, headByte.size))
        var completed: ByteArray? = asm.feed(first, 1, first.size - 1)
        for (i in 1 until packets.size) {
            completed = asm.feed(packets[i], packets[i].size) ?: completed
        }
        requireNotNull(completed)
        assertArrayEquals(golden, completed)
    }

    private fun packetWire(golden: ByteArray): ByteArray {
        val packets = HikUvcBulkReassembler.packetizeFrame(golden, bulkTransferBytes = 5034)
        var total = 0
        for (p in packets) total += p.size
        val wire = ByteArray(total)
        var off = 0
        for (p in packets) {
            p.copyInto(wire, off)
            off += p.size
        }
        return wire
    }

    private fun feedWireAtChunkSizes(
        wire: ByteArray,
        chunkSizes: IntArray,
        asm: HikUvcBulkReassembler = uvcAssembler(),
    ): ByteArray? {
        var offset = 0
        var completed: ByteArray? = null
        for (size in chunkSizes) {
            completed = asm.feed(wire, offset, size) ?: completed
            offset += size
        }
        return completed
    }

    private fun HikUvcBulkReassembler.feed(transfer: ByteArray, offset: Int, length: Int): ByteArray? {
        val slice = transfer.copyOfRange(offset, offset + length)
        return feed(slice, slice.size)
    }

    private fun loadGoldenFrame(): ByteArray {
        return buildSyntheticGoldenFrame()
    }

    private fun buildSyntheticGoldenFrame(): ByteArray =
        HikUvcWireLayoutTest.buildSyntheticUvcWireFrame(centerC = 22.0)

    private fun buildJumboFrame(canonical: ByteArray, header: ByteArray): ByteArray {
        val radio = canonical.copyOfRange(
            HikUvcWireLayout.RADIO_OFFSET,
            HikUvcWireLayout.RADIO_OFFSET + HikUvcWireLayout.RADIO_BYTES,
        )
        val visible = canonical.copyOfRange(
            HikUvcWireLayout.VISIBLE_OFFSET,
            HikUvcWireLayout.VISIBLE_OFFSET + HikUvcWireLayout.VISIBLE_BYTES,
        )
        val jumbo = ByteArray(header.size + radio.size + visible.size)
        header.copyInto(jumbo, 0)
        radio.copyInto(jumbo, header.size)
        visible.copyInto(jumbo, header.size + radio.size)
        return jumbo
    }
}
