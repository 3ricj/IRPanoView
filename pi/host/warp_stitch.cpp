#include "warp_stitch.h"

#include <algorithm>
#include <cstring>
#include <fstream>

namespace irpv::hik {

namespace {

constexpr char kWarpMagic[4] = {'I', 'R', 'P', 'W'};

} // namespace

uint16_t WarpStitchStrategy::bilinearSample(const uint16_t* tile, int32_t x_q8, int32_t y_q8) {
    // Fixed-point bilinear (8 fractional bits). Avoids float floor/lround on the hot path.
    // Floor division for possibly-negative Q8 coords.
    const int32_t x0 = x_q8 >= 0 ? (x_q8 >> 8) : -(((-x_q8) + 255) >> 8);
    const int32_t y0 = y_q8 >= 0 ? (y_q8 >> 8) : -(((-y_q8) + 255) >> 8);
    if (x0 < 0 || y0 < 0 || x0 >= kTileW - 1 || y0 >= kTileH - 1) {
        const int cx = std::max(0, std::min(static_cast<int>(x0), kTileW - 1));
        const int cy = std::max(0, std::min(static_cast<int>(y0), kTileH - 1));
        return tile[static_cast<size_t>(cy) * kTileW + static_cast<size_t>(cx)];
    }
    const int fx = static_cast<int>(x_q8 - (x0 << 8)); // 0..255
    const int fy = static_cast<int>(y_q8 - (y0 << 8));
    const size_t row0 = static_cast<size_t>(y0) * kTileW + static_cast<size_t>(x0);
    const size_t row1 = row0 + static_cast<size_t>(kTileW);
    const uint32_t v00 = tile[row0];
    const uint32_t v10 = tile[row0 + 1];
    const uint32_t v01 = tile[row1];
    const uint32_t v11 = tile[row1 + 1];
    const uint32_t v0 = v00 * static_cast<uint32_t>(256 - fx) + v10 * static_cast<uint32_t>(fx);
    const uint32_t v1 = v01 * static_cast<uint32_t>(256 - fx) + v11 * static_cast<uint32_t>(fx);
    // Round: + 0.5 in Q16 before shift.
    const uint32_t v = (v0 * static_cast<uint32_t>(256 - fy) + v1 * static_cast<uint32_t>(fy) + 32768u) >> 16;
    return static_cast<uint16_t>(v);
}

bool WarpStitchStrategy::loadBinary(const std::string& path, PanoGeometry& geometry_out, std::vector<std::string>& serials_out) {
    loaded_ = false;
    entries_.clear();
    serials_.clear();

    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return false;
    }

    char magic[4]{};
    in.read(magic, 4);
    if (std::memcmp(magic, kWarpMagic, 4) != 0) {
        return false;
    }

    uint32_t version = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t num_cams = 0;
    uint32_t serial_len = 0;
    in.read(reinterpret_cast<char*>(&version), 4);
    in.read(reinterpret_cast<char*>(&width), 4);
    in.read(reinterpret_cast<char*>(&height), 4);
    in.read(reinterpret_cast<char*>(&num_cams), 4);
    in.read(reinterpret_cast<char*>(&serial_len), 4);
    if (!in || version != 1 || num_cams != 4 || width == 0 || height == 0) {
        return false;
    }

    in.seekg(64, std::ios::beg);
    std::string serial_blob(serial_len, '\0');
    in.read(serial_blob.data(), static_cast<std::streamsize>(serial_len));
    if (!in) {
        return false;
    }

    serials_.clear();
    size_t start = 0;
    for (size_t i = 0; i <= serial_blob.size(); ++i) {
        if (i == serial_blob.size() || serial_blob[i] == '\n') {
            if (i > start) {
                serials_.push_back(serial_blob.substr(start, i - start));
            }
            start = i + 1;
        }
    }
    if (serials_.size() != 4) {
        return false;
    }

    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    entries_.resize(pixel_count * 4);
    for (size_t p = 0; p < pixel_count; ++p) {
        for (uint32_t slot = 0; slot < 4; ++slot) {
            WarpPixelEntry e{};
            uint8_t w = 0;
            int32_t sx = 0;
            int32_t sy = 0;
            in.read(reinterpret_cast<char*>(&w), 1);
            in.read(reinterpret_cast<char*>(&sx), 4);
            in.read(reinterpret_cast<char*>(&sy), 4);
            if (!in) {
                return false;
            }
            e.weight = w;
            e.src_x_q8 = sx;
            e.src_y_q8 = sy;
            entries_[p * 4 + slot] = e;
        }
    }

    geometry_ = PanoGeometry{static_cast<int>(width), static_cast<int>(height)};
    geometry_out = geometry_;
    serials_out = serials_;
    loaded_ = true;
    return true;
}

void WarpStitchStrategy::stitch(
    const uint16_t tiles[4][kTileW * kTileH],
    uint16_t* out,
    size_t out_count) const {
    if (!loaded_ || out_count < geometry_.pixelCount()) {
        return;
    }

    const size_t pixel_count = geometry_.pixelCount();
    const WarpPixelEntry* ent = entries_.data();
    for (size_t p = 0; p < pixel_count; ++p) {
        uint32_t acc = 0;
        uint32_t wsum = 0;
        for (int slot = 0; slot < 4; ++slot) {
            const WarpPixelEntry& e = ent[p * 4 + static_cast<size_t>(slot)];
            if (e.weight == 0) {
                continue;
            }
            const uint32_t sample = bilinearSample(tiles[slot], e.src_x_q8, e.src_y_q8);
            acc += sample * static_cast<uint32_t>(e.weight);
            wsum += e.weight;
        }
        out[p] = wsum > 0 ? static_cast<uint16_t>(acc / wsum) : 0;
    }
}

} // namespace irpv::hik
