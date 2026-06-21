#include "thermal_frame.h"

#include <cstring>

#include <zlib.h>

namespace irpv {

std::vector<uint8_t> buildThermalFrame(
    uint32_t sequence,
    uint64_t timestamp_us,
    const uint16_t* pixels,
    size_t pixel_count,
    const uint8_t camera_health[kCameraCount],
    bool include_health) {
    const uint16_t width = kPanoWidth;
    const uint16_t height = kPanoHeight;
    const size_t expected = static_cast<size_t>(width) * height;
    if (pixel_count < expected) {
        return {};
    }

    ThermalFrameHeader hdr{};
    hdr.magic = kThermalMagic;
    hdr.version = kThermalVersion;
    hdr.flags = include_health ? kFlagPerCameraHealth : 0;
    hdr.width = width;
    hdr.height = height;
    hdr.sequence = sequence;
    hdr.timestamp_us = timestamp_us;

    std::vector<uint8_t> out(thermalFrameBytes(width, height, include_health));
    std::memcpy(out.data(), &hdr, sizeof(hdr));
    std::memcpy(out.data() + sizeof(hdr), pixels, thermalPayloadBytes(width, height));
    if (include_health) {
        std::memcpy(out.data() + sizeof(hdr) + thermalPayloadBytes(width, height), camera_health, kCameraCount);
    }
    return out;
}

std::vector<uint8_t> buildThermalFrameV2(
    uint32_t sequence,
    uint64_t timestamp_us,
    const uint16_t* pixels,
    size_t pixel_count,
    const uint8_t camera_health[kCameraCount],
    const ThermalFrameMetaV2& meta,
    bool include_health,
    uint16_t width,
    uint16_t height) {
    const size_t expected = static_cast<size_t>(width) * height;
    if (pixel_count < expected || width == 0 || height == 0) {
        return {};
    }

    ThermalFrameHeader hdr{};
    hdr.magic = kThermalMagic;
    hdr.version = kThermalVersion2;
    hdr.flags = kFlagHasMetaV2 | (include_health ? kFlagPerCameraHealth : 0);
    hdr.width = width;
    hdr.height = height;
    hdr.sequence = sequence;
    hdr.timestamp_us = timestamp_us;

    const size_t payload_bytes = thermalPayloadBytes(width, height);
    std::vector<uint8_t> out(thermalFrameBytes(width, height, include_health, true));
    size_t offset = 0;
    std::memcpy(out.data() + offset, &hdr, sizeof(hdr));
    offset += sizeof(hdr);
    std::memcpy(out.data() + offset, &meta, sizeof(meta));
    offset += sizeof(meta);
    std::memcpy(out.data() + offset, pixels, payload_bytes);
    offset += payload_bytes;
    if (include_health) {
        std::memcpy(out.data() + offset, camera_health, kCameraCount);
    }
    return out;
}

std::vector<uint8_t> buildThermalCameraFrame(
    uint32_t sequence,
    uint64_t timestamp_us,
    const uint16_t* pixels,
    size_t pixel_count,
    const ThermalFrameMetaV2& meta,
    uint8_t slot,
    uint8_t camera_count,
    const char* serial,
    uint16_t width,
    uint16_t height,
    bool deflate_payload) {
    const size_t expected = static_cast<size_t>(width) * height;
    if (pixel_count < expected || width == 0 || height == 0) {
        return {};
    }

    const size_t payload_bytes = thermalPayloadBytes(width, height);

    // Optionally zlib-deflate the pixel payload to cut network traffic (~2.5x
    // on smooth radiometric data). Header/meta/camera-info stay uncompressed so
    // the client reads dims/identity without inflating.
    std::vector<uint8_t> compressed;
    bool use_deflate = false;
    if (deflate_payload) {
        uLongf bound = compressBound(static_cast<uLong>(payload_bytes));
        compressed.resize(bound);
        uLongf clen = bound;
        const int rc = compress2(
            compressed.data(), &clen,
            reinterpret_cast<const Bytef*>(pixels), static_cast<uLong>(payload_bytes), 6);
        if (rc == Z_OK && clen < payload_bytes) {
            compressed.resize(clen);
            use_deflate = true;
        }
    }

    ThermalFrameHeader hdr{};
    hdr.magic = kThermalMagic;
    hdr.version = kThermalVersion2;
    hdr.flags = kFlagHasMetaV2 | kFlagCameraInfo | (use_deflate ? kFlagPayloadDeflate : 0);
    hdr.width = width;
    hdr.height = height;
    hdr.sequence = sequence;
    hdr.timestamp_us = timestamp_us;

    ThermalCameraInfo info{};
    info.slot = slot;
    info.camera_count = camera_count;
    if (serial != nullptr) {
        std::strncpy(info.serial, serial, kCameraSerialBytes - 1);
    }

    const size_t body_bytes = use_deflate ? compressed.size() : payload_bytes;
    const size_t total = sizeof(hdr) + kThermalMetaV2Bytes + kThermalCameraInfoBytes + body_bytes;
    std::vector<uint8_t> out(total);
    size_t offset = 0;
    std::memcpy(out.data() + offset, &hdr, sizeof(hdr));
    offset += sizeof(hdr);
    std::memcpy(out.data() + offset, &meta, sizeof(meta));
    offset += sizeof(meta);
    std::memcpy(out.data() + offset, &info, sizeof(info));
    offset += sizeof(info);
    if (use_deflate) {
        std::memcpy(out.data() + offset, compressed.data(), compressed.size());
    } else {
        std::memcpy(out.data() + offset, pixels, payload_bytes);
    }
    return out;
}

} // namespace irpv
