#pragma once

#include "pano_geometry.h"

#include <cstdint>
#include <vector>

namespace irpv::hik {

struct TileSnapshot {
    static constexpr size_t kPixelCount = static_cast<size_t>(kGridWidth) * kGridHeight;

    std::vector<uint16_t> pixels;
    uint64_t usb_frame_us = 0;
    uint32_t frame_seq = 0;
    bool valid = false;
};

struct SlotMeta {
    uint32_t frame_seq = 0;
    uint64_t usb_frame_us = 0;
    uint32_t age_ms = 0;
    bool valid = false;
};

struct PanoSnapshot {
    std::vector<uint16_t> pano;
    PanoGeometry geometry{};
    uint64_t compose_us = 0;
    uint32_t compose_seq = 0;
    SlotMeta slots[4]{};
    uint8_t health[4]{};
};

} // namespace irpv::hik
