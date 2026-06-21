#include "warp_stitch.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>

namespace irpv::hik {

namespace {

constexpr char kWarpMagic[4] = {'I', 'R', 'P', 'W'};

} // namespace

uint16_t WarpStitchStrategy::bilinearSample(const uint16_t* tile, int32_t x_q8, int32_t y_q8) {
    const float x = static_cast<float>(x_q8) / static_cast<float>(kWarpFixedPoint);
    const float y = static_cast<float>(y_q8) / static_cast<float>(kWarpFixedPoint);
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    if (x0 < 0 || y0 < 0 || x0 >= kTileW - 1 || y0 >= kTileH - 1) {
        const int cx = std::max(0, std::min(x0, kTileW - 1));
        const int cy = std::max(0, std::min(y0, kTileH - 1));
        return tile[static_cast<size_t>(cy) * kTileW + static_cast<size_t>(cx)];
    }
    const float fx = x - static_cast<float>(x0);
    const float fy = y - static_cast<float>(y0);
    const auto at = [&](int tx, int ty) -> float {
        return static_cast<float>(tile[static_cast<size_t>(ty) * kTileW + static_cast<size_t>(tx)]);
    };
    const float v00 = at(x0, y0);
    const float v10 = at(x0 + 1, y0);
    const float v01 = at(x0, y0 + 1);
    const float v11 = at(x0 + 1, y0 + 1);
    const float v0 = v00 + (v10 - v00) * fx;
    const float v1 = v01 + (v11 - v01) * fx;
    const float v = v0 + (v1 - v0) * fy;
    const int rounded = static_cast<int>(std::lround(v));
    if (rounded < 0) {
        return 0;
    }
    if (rounded > 65535) {
        return 65535;
    }
    return static_cast<uint16_t>(rounded);
}

uint16_t WarpStitchStrategy::tileMeanU16(const uint16_t* tile, size_t count) {
    if (count == 0) {
        return 0;
    }
    uint64_t sum = 0;
    for (size_t i = 0; i < count; ++i) {
        sum += tile[i];
    }
    return static_cast<uint16_t>(sum / count);
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

    const size_t tile_pixels = static_cast<size_t>(kTileW) * kTileH;
    uint16_t means[4]{};
    for (int cam = 0; cam < 4; ++cam) {
        means[cam] = tileMeanU16(tiles[cam], tile_pixels);
    }

    const size_t pixel_count = geometry_.pixelCount();
    for (size_t p = 0; p < pixel_count; ++p) {
        uint64_t acc = 0;
        uint32_t wsum = 0;
        for (int slot = 0; slot < 4; ++slot) {
            const WarpPixelEntry& e = entries_[p * 4 + static_cast<size_t>(slot)];
            if (e.weight == 0) {
                continue;
            }
            const uint16_t sample = bilinearSample(tiles[slot], e.src_x_q8, e.src_y_q8);
            acc += static_cast<uint64_t>(sample) * static_cast<uint64_t>(e.weight);
            wsum += e.weight;
        }
        if (wsum > 0) {
            out[p] = static_cast<uint16_t>(acc / wsum);
        } else {
            out[p] = 0;
        }
    }
}

} // namespace irpv::hik
