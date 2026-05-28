package com.vilos.irpanoview.camera.hik

/** UVC extension + wire constants (MasterThermoDocs / py_re_framework hik_uvc.py). */
object HikUvcConstants {
    const val WINDEX_XU = 0x0A00

    const val WVALUE_CAPABILITIES = 0x1700
    const val WVALUE_CAP_FLAGS = 0x0800
    const val WVALUE_SELECT = 0x0500
    const val WVALUE_MISC = 0x0100
    const val WVALUE_IMAGE = 0x0200
    const val WVALUE_THERM = 0x0300
    const val WVALUE_STATUS = 0x0600
    const val WVALUE_EXTENSION_VERSION = 0x0400

    const val BM_REQ_OUT = 0x21
    const val BM_REQ_IN = 0xA1
    const val BREQ_SET = 0x01
    const val BREQ_PROBE = 0x85
    const val BREQ_GET = 0x81

    const val CAPABILITIES_BLOB_LEN = 265

    /** 0x7ED image video adjust wire map (ConvertData @ libhcusbdk_image_adjust_wire.md). */
    const val WIRE_OFF_VIDEO_DIGITAL_ZOOM = 1
    const val WIRE_OFF_VIDEO_FLIP_STYLE = 2
    const val WIRE_OFF_VIDEO_CORRIDOR = 4

    const val WIRE_OFF_THERM_OVERLAY = 2
    const val WIRE_OFF_TEMPERATURE_RANGE = 6
    const val WIRE_OFF_EMISSIVITY = 16
    const val WIRE_OFF_DISTANCE = 21
    const val WIRE_OFF_ENV_TEMP_ENABLE = 0x4B
    const val WIRE_OFF_ENV_TEMP = 0x4C

    const val THERM_OVERLAY_OFF = 1
    /** Ambient correction on (`setIrConfig` sets this when pushing scene params). */
    const val ENV_TEMP_ENABLE_ON = 2
    const val HW_SERVER_READY_STATUS = 3

    const val STREAM_FORMAT = 0x67
    const val STREAM_FPS = 25

    /** Max ms to assemble one super-frame in a tight drain loop (~3 frame periods @ 25 Hz). */
    const val FRAME_DRAIN_MS = 120

    /** Longer budget while waiting for the first super-frame (hub may be busy). */
    const val FIRST_FRAME_DRAIN_MS = 600

    /** Per-transfer timeout while draining one frame (parallel 2-cam path). */
    const val BULK_DRAIN_TRANSFER_MS = 40

    /** Short bulk timeout while [stopRequested] — exit drain loop without wedging shutdown. */
    const val BULK_SHUTDOWN_TRANSFER_MS = 20

    /** Max time to drain bulk IN before UVC alt-0 on Exit (E4). */
    const val BULK_SHUTDOWN_FLUSH_MS = 200L

    /** Stop flush after this many consecutive empty bulk reads. */
    const val BULK_SHUTDOWN_FLUSH_EMPTY_STREAK = 2

    /** Max wait to reap cancelled [UsbRequest]s (reference libuvc ~3 s). */
    const val BULK_CANCEL_DRAIN_MS = 3_000L
    const val STREAM_WIDTH_TOKEN = 8
    const val STREAM_HEIGHT_TOKEN = 0x3122

    const val CONTROL_TIMEOUT_MS = 3_000
    const val BULK_TIMEOUT_MS = 12_000
    /** Per-packet budget (ms) while round-robinning bulk IN across active streams. */
    const val BULK_PACKET_BUDGET_MS = 120
    /** Typical UVC super-frame payload transfer count on TC002C Android bulk. */
    const val UVC_PACKETS_PER_FRAME = 41

    /** Max ms to hold [HikBulkGate] per transfer — must stay short for fair round-robin. */
    const val BULK_TRANSFER_CAP_MS = 200

    /** Reset UVC reassembler if a super-frame does not complete within this window. */
    const val PARTIAL_FRAME_TIMEOUT_MS = 2_500

    fun bulkSteadyTimeoutMs(): Int {
        val n = HikMultiCamPolicy.ACTIVE_STREAM_LIMIT.coerceAtLeast(1)
        return BULK_PACKET_BUDGET_MS * UVC_PACKETS_PER_FRAME * n
    }

    /** Base wait before re-arm / session restart while awaiting the first super-frame. */
    const val FIRST_FRAME_DEADLINE_BASE_MS = 3_000
    /** Extra budget per other active stream (hub round-robin), not full [ACTIVE_STREAM_LIMIT]. */
    const val FIRST_FRAME_DEADLINE_PER_CAM_MS = 1_500
    const val FIRST_FRAME_DEADLINE_MAX_MS = 8_000
    /** Hotplug: re-arm early if no bytes after startup (re-arm usually fixes within ~4 s). */
    const val FIRST_FRAME_EARLY_REARM_MS = 2_000

    fun bulkFirstFrameTimeoutMs(): Int {
        val active = HikBulkGate.activeStreamingWorkers().coerceAtLeast(1)
        return minOf(
            FIRST_FRAME_DEADLINE_MAX_MS,
            FIRST_FRAME_DEADLINE_BASE_MS + (active - 1) * FIRST_FRAME_DEADLINE_PER_CAM_MS,
        )
    }
    /** Payload reached [HikTherm.FRAME_BYTES] but EOF bit not seen yet (hub mutex delay). */
    const val BULK_EOF_TAIL_MS = 3_000
    /** When ring is within this many bytes of a full super-frame, keep reading under hub load. */
    const val BULK_NEAR_COMPLETE_BYTES = 512
    const val BULK_NEAR_COMPLETE_TAIL_MS = 5_000

    /** Empirical inter-frame gaps on TC002C Android bulk (~5020 B packets). */
    val KNOWN_WIRE_GAPS = intArrayOf(5548, 5546, 546, 544, 504, 456, 448, 112, 82, 80, 64, 32)

    /** Minimum bytes to decode radiometric grid (header + u16 grid). */
    const val MIN_GRID_BYTES = HikUvcWireLayout.RADIO_BYTES
    const val READY_TIMEOUT_MS = 30_000
    const val POLL_INTERVAL_MS = 150L

    /** After disarm+release on bound relaunch, poll GET 0x7DE for cold stub before cold bind. */
    const val BOUND_UNBOUND_WAIT_MS = 3_000L
    /** Settle after interface release before reclaim (ms). */
    const val BOUND_RELAUNCH_SETTLE_MS = 250L
    /** Bulk flush budget after reclaim on bound relaunch (ms). */
    const val BOUND_RELAUNCH_FLUSH_MS = 2_000L

    /** Manual NUC / black reference — MasterThermoDocs 09, kind 2025 @ wValue 0x0200 sub 0x04. */
    const val SELECT_PHASE_IMAGE_MANUAL_CORRECT = 0x02
    const val SELECT_SUB_IMAGE_MANUAL_CORRECT = 0x04
    const val USB_COMMON_COND_WIRE_LEN = 12
    const val COMMAND_STATE_IDLE = 1

    /** Serial wrapper SET 0x838 — wValue 0x0300 sub 0x10, 269 B wire (libhcusbdk_serial_0x838_wire.md). */
    const val SELECT_PHASE_SERIAL = 0x03
    const val SELECT_SUB_SERIAL = 0x10
    const val SERIAL_TRANSMISSION_WIRE_LEN = 269
    const val SERIAL_CMD_AUTO_SHUTTER = 0x2001

    /** Delay after all cameras are streaming before synced black reference. */
    const val BLACK_REFERENCE_DELAY_MS = 500L
}
