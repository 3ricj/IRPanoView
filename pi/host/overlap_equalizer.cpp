#include "overlap_equalizer.h"
#include "warp_stitch.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace irpv::hik {

namespace {

uint16_t addBiasU16(uint16_t raw, int32_t bias) {
    const int v = static_cast<int>(raw) + static_cast<int>(bias);
    if (v < 0) {
        return 0;
    }
    if (v > 65535) {
        return 65535;
    }
    return static_cast<uint16_t>(v);
}

double trimmedMean(std::vector<int32_t>& vals) {
    if (vals.empty()) {
        return 0.0;
    }
    std::sort(vals.begin(), vals.end());
    const size_t lo = (vals.size() * 5) / 100;
    const size_t hi = (vals.size() * 95) / 100;
    const size_t begin = lo;
    const size_t end = std::max(begin + 1, hi);
    int64_t sum = 0;
    size_t count = 0;
    for (size_t i = begin; i < end && i < vals.size(); ++i) {
        sum += vals[i];
        ++count;
    }
    return count > 0 ? static_cast<double>(sum) / static_cast<double>(count) : 0.0;
}

bool sampleTile(const uint16_t* tile, int tx, int ty, uint16_t& out) {
    if (tx < 0 || tx >= kTileW || ty < 0 || ty >= kTileH) {
        return false;
    }
    out = tile[static_cast<size_t>(ty) * kTileW + static_cast<size_t>(tx)];
    return true;
}

void applyBiases(uint16_t tiles[4][kTileW * kTileH], const int32_t slot_bias[4]) {
    const size_t tile_pixels = static_cast<size_t>(kTileW) * kTileH;
    for (int cam = 0; cam < 4; ++cam) {
        const int32_t bias = slot_bias[cam];
        if (bias == 0) {
            continue;
        }
        for (size_t p = 0; p < tile_pixels; ++p) {
            tiles[cam][p] = addBiasU16(tiles[cam][p], bias);
        }
    }
}

} // namespace

int OverlapEqualizer::overlapWidth(const SlotOffset offsets[4], int seam_index) {
    if (seam_index < 0 || seam_index >= kSeamCount) {
        return 0;
    }
    return std::max(0, offsets[seam_index].dx - offsets[seam_index + 1].dx);
}

void OverlapEqualizer::chainBiasesFromSeams(const int32_t ema_seam[3], int anchor_slot, int32_t out_bias[4]) {
    for (int i = 0; i < 4; ++i) {
        out_bias[i] = 0;
    }
    if (anchor_slot < 0 || anchor_slot >= 4) {
        return;
    }
    out_bias[anchor_slot] = 0;

    for (int slot = 0; slot < anchor_slot; ++slot) {
        int32_t sum = 0;
        for (int seam = slot; seam < anchor_slot; ++seam) {
            sum += ema_seam[seam];
        }
        out_bias[slot] = -sum;
    }

    for (int slot = anchor_slot + 1; slot < 4; ++slot) {
        int32_t sum = 0;
        for (int seam = anchor_slot; seam < slot; ++seam) {
            sum += ema_seam[seam];
        }
        out_bias[slot] = sum;
    }
}

void OverlapEqualizer::process(
    uint16_t tiles[4][kTileW * kTileH],
    const SlotOffset offsets[4]) {
    int32_t slot_bias[4]{};
    chainBiasesFromSeams(state_.ema_seam_delta, config_.anchor_slot, slot_bias);
    for (int i = 0; i < 4; ++i) {
        state_.ema_bias_u16[i] = slot_bias[i];
    }

    if (!config_.enabled) {
        return;
    }

    const double alpha = std::max(0.001, std::min(1.0, config_.alpha));

    for (int seam = 0; seam < kSeamCount; ++seam) {
        const int width = overlapWidth(offsets, seam);
        state_.last_sample_counts[seam] = 0;
        state_.last_seam_delta[seam] = state_.ema_seam_delta[seam];

        if (width <= 0) {
            continue;
        }

        const int left_cam = seam;
        const int right_cam = seam + 1;
        const int overlap_x0 = (right_cam * kTileW) + offsets[right_cam].dx;

        std::vector<int32_t> deltas;
        deltas.reserve(static_cast<size_t>(width) * kTileH);

        for (int y = 0; y < kTileH; ++y) {
            for (int x = overlap_x0; x < overlap_x0 + width; ++x) {
                const int left_tx = x - left_cam * kTileW - offsets[left_cam].dx;
                const int left_ty = y - offsets[left_cam].dy;
                const int right_tx = x - right_cam * kTileW - offsets[right_cam].dx;
                const int right_ty = y - offsets[right_cam].dy;

                uint16_t left_raw = 0;
                uint16_t right_raw = 0;
                if (!sampleTile(tiles[left_cam], left_tx, left_ty, left_raw)) {
                    continue;
                }
                if (!sampleTile(tiles[right_cam], right_tx, right_ty, right_raw)) {
                    continue;
                }
                deltas.push_back(static_cast<int32_t>(left_raw) - static_cast<int32_t>(right_raw));
            }
        }

        state_.last_sample_counts[seam] = static_cast<int>(deltas.size());
        if (static_cast<int>(deltas.size()) < config_.min_samples) {
            continue;
        }

        const int32_t seam_delta = static_cast<int32_t>(std::lround(trimmedMean(deltas)));
        state_.last_seam_delta[seam] = seam_delta;
        state_.ema_seam_delta[seam] = static_cast<int32_t>(
            std::lround(alpha * static_cast<double>(seam_delta) +
                        (1.0 - alpha) * static_cast<double>(state_.ema_seam_delta[seam])));
    }

    chainBiasesFromSeams(state_.ema_seam_delta, config_.anchor_slot, slot_bias);
    for (int i = 0; i < 4; ++i) {
        state_.ema_bias_u16[i] = slot_bias[i];
    }

    applyBiases(tiles, slot_bias);
}

void OverlapEqualizer::processWarp(
    uint16_t tiles[4][kTileW * kTileH],
    const std::vector<EqSeamPair>& seams) {
    int32_t slot_bias[4]{};
    chainBiasesFromSeams(state_.ema_seam_delta, config_.anchor_slot, slot_bias);
    for (int i = 0; i < 4; ++i) {
        state_.ema_bias_u16[i] = slot_bias[i];
    }

    if (!config_.enabled) {
        return;
    }

    const double alpha = std::max(0.001, std::min(1.0, config_.alpha));

    for (int seam = 0; seam < kSeamCount && seam < static_cast<int>(seams.size()); ++seam) {
        const EqSeamPair& pair = seams[static_cast<size_t>(seam)];
        state_.last_sample_counts[seam] = 0;
        state_.last_seam_delta[seam] = state_.ema_seam_delta[seam];

        if (pair.samples.empty()) {
            continue;
        }

        const int left_cam = pair.left_slot;
        const int right_cam = pair.right_slot;
        if (left_cam < 0 || left_cam >= 4 || right_cam < 0 || right_cam >= 4) {
            continue;
        }

        std::vector<int32_t> deltas;
        deltas.reserve(pair.samples.size());

        for (const EqSeamSample& s : pair.samples) {
            uint16_t left_raw = WarpStitchStrategy::bilinearSample(tiles[left_cam], s.left_x_q8, s.left_y_q8);
            uint16_t right_raw = WarpStitchStrategy::bilinearSample(tiles[right_cam], s.right_x_q8, s.right_y_q8);
            deltas.push_back(static_cast<int32_t>(left_raw) - static_cast<int32_t>(right_raw));
        }

        state_.last_sample_counts[seam] = static_cast<int>(deltas.size());
        if (static_cast<int>(deltas.size()) < config_.min_samples) {
            continue;
        }

        const int32_t seam_delta = static_cast<int32_t>(std::lround(trimmedMean(deltas)));
        state_.last_seam_delta[seam] = seam_delta;
        state_.ema_seam_delta[seam] = static_cast<int32_t>(
            std::lround(alpha * static_cast<double>(seam_delta) +
                        (1.0 - alpha) * static_cast<double>(state_.ema_seam_delta[seam])));
    }

    chainBiasesFromSeams(state_.ema_seam_delta, config_.anchor_slot, slot_bias);
    for (int i = 0; i < 4; ++i) {
        state_.ema_bias_u16[i] = slot_bias[i];
    }

    applyBiases(tiles, slot_bias);
}

} // namespace irpv::hik
