#pragma once

#include "eq_seam.h"
#include "pano_geometry.h"
#include "stitch_offsets.h"

#include <cstdint>
#include <vector>

namespace irpv::hik {

struct EqualizerConfig {
    bool enabled = true;
    double alpha = 0.05;
    int min_samples = 50;
    int anchor_slot = 1; // 0-based; slot 2 is reference
};

struct EqualizerState {
    int32_t ema_seam_delta[3]{};
    int32_t ema_bias_u16[4]{};
    int32_t last_seam_delta[3]{};
    int last_sample_counts[3]{};
};

class OverlapEqualizer {
public:
    static constexpr int kSeamCount = 3;

    void setConfig(const EqualizerConfig& config) { config_ = config; }
    const EqualizerConfig& config() const { return config_; }

    const EqualizerState& state() const { return state_; }

    // Legacy rectangular overlap (offset stitch mode).
    void process(
        uint16_t tiles[4][kTileW * kTileH],
        const SlotOffset offsets[4]);

    // Radiometric equalization using precomputed warp overlap sample pairs (u16 tiles).
    void processWarp(
        uint16_t tiles[4][kTileW * kTileH],
        const std::vector<EqSeamPair>& seams);

    static int overlapWidth(const SlotOffset offsets[4], int seam_index);
    static void chainBiasesFromSeams(const int32_t ema_seam[3], int anchor_slot, int32_t out_bias[4]);

private:
    EqualizerConfig config_{};
    EqualizerState state_{};
};

} // namespace irpv::hik
