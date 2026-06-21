#include "stitch_offsets.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

namespace irpv::hik {

namespace {

uint32_t tileMeanU16(const uint16_t* tile, size_t count) {
    if (count == 0) {
        return 0;
    }
    uint64_t sum = 0;
    for (size_t i = 0; i < count; ++i) {
        sum += tile[i];
    }
    return static_cast<uint32_t>(sum / count);
}

} // namespace

void OffsetStitchStrategy::stitch(
    const uint16_t tiles[4][kTileW * kTileH],
    const SlotOffset offsets[4],
    uint16_t* out,
    size_t out_count) const {
    const size_t expected = static_cast<size_t>(kOutW) * kOutH;
    if (out_count < expected) {
        return;
    }

    const size_t tile_pixels = static_cast<size_t>(kTileW) * kTileH;
    uint16_t means[4]{};
    for (int cam = 0; cam < 4; ++cam) {
        means[cam] = static_cast<uint16_t>(tileMeanU16(tiles[cam], tile_pixels));
    }

    std::fill(out, out + expected, static_cast<uint16_t>(0));
    std::vector<bool> any_wrote(expected, false);

    for (int cam = 0; cam < 4; ++cam) {
        const int dx = offsets[cam].dx;
        const int dy = offsets[cam].dy;
        for (int y = 0; y < kTileH; ++y) {
            const int dest_y = y + dy;
            if (dest_y < 0 || dest_y >= kOutH) {
                continue;
            }
            for (int x = 0; x < kTileW; ++x) {
                const int dest_x = cam * kTileW + x + dx;
                if (dest_x < 0 || dest_x >= kOutW) {
                    continue;
                }
                const size_t out_idx = static_cast<size_t>(dest_y) * kOutW + static_cast<size_t>(dest_x);
                out[out_idx] = tiles[cam][static_cast<size_t>(y) * kTileW + x];
                any_wrote[out_idx] = true;
            }
        }
    }

    // Fill only pixels no camera landed on (shift-exposed gaps), within each slot band.
    for (int cam = 0; cam < 4; ++cam) {
        const int band_x0 = cam * kTileW;
        const int band_x1 = band_x0 + kTileW;
        for (int y = 0; y < kOutH; ++y) {
            for (int x = band_x0; x < band_x1; ++x) {
                const size_t out_idx = static_cast<size_t>(y) * kOutW + static_cast<size_t>(x);
                if (!any_wrote[out_idx]) {
                    out[out_idx] = means[cam];
                }
            }
        }
    }
}

bool OffsetStitchStrategy::saveOffsets(const std::string& path, const SlotOffset offsets[4], const char* serials[4]) {
    std::ofstream out(path);
    if (!out) {
        return false;
    }
    out << "# irpanoview slot offsets (dx, dy pixels); pano band fill uses per-camera mean u16\n";
    for (int i = 0; i < 4; ++i) {
        const char* serial = (serials && serials[i]) ? serials[i] : "";
        out << "slot=" << (i + 1) << " dx=" << offsets[i].dx << " dy=" << offsets[i].dy << " serial=" << serial << '\n';
    }
    return true;
}

bool OffsetStitchStrategy::loadOffsets(const std::string& path, SlotOffset offsets[4]) {
    std::ifstream in(path);
    if (!in) {
        return false;
    }
    SlotOffset loaded[4]{};
    bool any = false;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') {
            continue;
        }
        int slot = 0;
        int dx = 0;
        int dy = 0;
        if (std::sscanf(line.c_str(), " slot=%d dx=%d dy=%d", &slot, &dx, &dy) >= 3 ||
            std::sscanf(line.c_str(), "slot=%d dx=%d dy=%d", &slot, &dx, &dy) >= 3) {
            if (slot >= 1 && slot <= 4) {
                loaded[slot - 1] = SlotOffset{dx, dy};
                any = true;
            }
        }
    }
    if (!any) {
        return false;
    }
    for (int i = 0; i < 4; ++i) {
        offsets[i] = loaded[i];
    }
    return true;
}

} // namespace irpv::hik
