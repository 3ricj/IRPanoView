#pragma once

#include <cstddef>
#include <cstdint>

namespace irpv::hik {

struct PanoGeometry {
    int width = 1024;
    int height = 192;

    size_t pixelCount() const {
        return static_cast<size_t>(width) * static_cast<size_t>(height);
    }

    static PanoGeometry legacy() { return PanoGeometry{1024, 192}; }
};

inline constexpr int kTileW = 256;
inline constexpr int kTileH = 192;

} // namespace irpv::hik
