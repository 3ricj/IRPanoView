#pragma once

#include "hik_stitch.h"

#include <cstdint>
#include <string>

namespace irpv::hik {

struct SlotOffset {
    int dx = 0;
    int dy = 0;
};

class OffsetStitchStrategy {
public:
    static constexpr int kTileW = EdgeStitchStrategy::kTileW;
    static constexpr int kTileH = EdgeStitchStrategy::kTileH;
    static constexpr int kOutW = EdgeStitchStrategy::kOutW;
    static constexpr int kOutH = EdgeStitchStrategy::kOutH;

    void stitch(
        const uint16_t tiles[4][kTileW * kTileH],
        const SlotOffset offsets[4],
        uint16_t* out,
        size_t out_count) const;

    static bool saveOffsets(const std::string& path, const SlotOffset offsets[4], const char* serials[4]);
    static bool loadOffsets(const std::string& path, SlotOffset offsets[4]);
};

} // namespace irpv::hik
