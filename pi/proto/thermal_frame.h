#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace irpv {

inline constexpr uint32_t kThermalMagic = 0x56505249u; // "IRPV"
inline constexpr uint8_t kThermalVersion = 1;
inline constexpr uint8_t kThermalVersion2 = 2;
inline constexpr uint8_t kFlagPerCameraHealth = 0x01;
inline constexpr uint8_t kFlagHasMetaV2 = 0x02;
inline constexpr uint8_t kFlagCameraInfo = 0x04;
inline constexpr uint8_t kFlagPayloadDeflate = 0x08; // payload is zlib-deflated

inline constexpr uint16_t kDefaultRawTcpPort = 8767;
inline constexpr uint16_t kDefaultMetaUdpPort = 8768;
inline constexpr uint16_t kDefaultRtspPort = 8554;
inline constexpr uint16_t kDefaultPreviewTcpPort = 8769;

// Preview stream payload magic (inside IRPR length-framed packet).
inline constexpr uint32_t kPreviewMagic = 0x38505249u; // "IRP8"

#pragma pack(push, 1)
struct PreviewFrameHeader {
    uint32_t magic;       // kPreviewMagic
    uint16_t width;
    uint16_t height;
    uint32_t sequence;
    uint64_t timestamp_us;
    float min_c;
    float max_c;
};
#pragma pack(pop)
static_assert(sizeof(PreviewFrameHeader) == 28, "preview header size");

inline constexpr uint16_t kPanoWidth = 1024;
inline constexpr uint16_t kPanoHeight = 192;
inline constexpr uint16_t kTileWidth = 256;
inline constexpr uint16_t kTileHeight = 192;
inline constexpr int kCameraCount = 4;

inline constexpr uint16_t kDefaultUdpPort = 8765;
inline constexpr uint16_t kDefaultWsPort = 8766;

#pragma pack(push, 1)
struct ThermalFrameHeader {
    uint32_t magic;
    uint8_t version;
    uint8_t flags;
    uint16_t width;
    uint16_t height;
    uint32_t sequence;
    uint64_t timestamp_us;
};
#pragma pack(pop)

static_assert(sizeof(ThermalFrameHeader) == 22, "thermal header size");

#pragma pack(push, 1)
struct ThermalFrameMetaV2 {
    uint64_t compose_us = 0;
    uint64_t emit_us = 0;
    uint32_t slot_frame_seq[4]{};
    uint64_t slot_usb_frame_us[4]{};
    uint32_t emit_seq = 0;
    uint32_t flags = 0;
};
#pragma pack(pop)

static_assert(sizeof(ThermalFrameMetaV2) == 72, "thermal meta v2 size");

inline constexpr size_t kThermalMetaV2Bytes = sizeof(ThermalFrameMetaV2);

inline constexpr size_t kCameraSerialBytes = 16;

// Per-camera identity block (present when kFlagCameraInfo is set). Placed
// directly after the MetaV2 block and before the pixel payload.
#pragma pack(push, 1)
struct ThermalCameraInfo {
    uint8_t slot = 0;          // 1..kCameraCount
    uint8_t camera_count = 0;  // total cameras in this group (e.g. 4)
    uint16_t reserved = 0;
    char serial[kCameraSerialBytes]{}; // null-padded ASCII
};
#pragma pack(pop)

static_assert(sizeof(ThermalCameraInfo) == 20, "thermal camera info size");

inline constexpr size_t kThermalCameraInfoBytes = sizeof(ThermalCameraInfo);

inline size_t thermalPayloadBytes(uint16_t width, uint16_t height) {
    return static_cast<size_t>(width) * height * 2u;
}

inline size_t thermalFrameBytes(uint16_t width, uint16_t height, bool health, bool meta_v2 = false) {
    size_t n = sizeof(ThermalFrameHeader) + thermalPayloadBytes(width, height);
    if (meta_v2) {
        n += kThermalMetaV2Bytes;
    }
    if (health) {
        n += kCameraCount;
    }
    return n;
}

std::vector<uint8_t> buildThermalFrame(
    uint32_t sequence,
    uint64_t timestamp_us,
    const uint16_t* pixels,
    size_t pixel_count,
    const uint8_t camera_health[kCameraCount],
    bool include_health);

std::vector<uint8_t> buildThermalFrameV2(
    uint32_t sequence,
    uint64_t timestamp_us,
    const uint16_t* pixels,
    size_t pixel_count,
    const uint8_t camera_health[kCameraCount],
    const ThermalFrameMetaV2& meta,
    bool include_health,
    uint16_t width = kPanoWidth,
    uint16_t height = kPanoHeight);

// Builds a single-camera frame: Header(v2) | MetaV2 | CameraInfo | payload.
// No health trailer (identity lives in the CameraInfo block instead).
std::vector<uint8_t> buildThermalCameraFrame(
    uint32_t sequence,
    uint64_t timestamp_us,
    const uint16_t* pixels,
    size_t pixel_count,
    const ThermalFrameMetaV2& meta,
    uint8_t slot,
    uint8_t camera_count,
    const char* serial,
    uint16_t width = kTileWidth,
    uint16_t height = kTileHeight,
    bool deflate_payload = true);

} // namespace irpv
