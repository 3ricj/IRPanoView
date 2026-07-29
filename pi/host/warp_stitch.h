#pragma once

#include "pano_geometry.h"

#include <cstdint>
#include <string>
#include <vector>

namespace irpv::hik {

inline constexpr int kWarpFixedPoint = 256;

struct WarpPixelEntry {
    uint8_t weight = 0;
    int32_t src_x_q8 = 0;
    int32_t src_y_q8 = 0;
};

class WarpStitchStrategy {
public:
    bool loadBinary(const std::string& path, PanoGeometry& geometry_out, std::vector<std::string>& serials_out);

    const PanoGeometry& geometry() const { return geometry_; }
    bool loaded() const { return loaded_; }

    void stitch(
        const uint16_t tiles[4][kTileW * kTileH],
        uint16_t* out,
        size_t out_count) const;

    static uint16_t bilinearSample(const uint16_t* tile, int32_t x_q8, int32_t y_q8);

private:
    PanoGeometry geometry_{};
    bool loaded_ = false;
    std::vector<std::string> serials_;
    // entries_[pixel * 4 + slot]
    std::vector<WarpPixelEntry> entries_;
};

} // namespace irpv::hik
