#include "overlap_equalizer.h"
#include "pano_geometry.h"

#include <cmath>
#include <cstdio>
#include <cstring>

namespace {

int failures = 0;

void check(bool ok, const char* msg) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", msg);
        ++failures;
    }
}

void testOverlapWidth() {
    irpv::hik::SlotOffset offsets[4] = {
        {16, 3},
        {0, 0},
        {-23, -2},
        {-32, -6},
    };
    check(irpv::hik::OverlapEqualizer::overlapWidth(offsets, 0) == 16, "seam0 width");
    check(irpv::hik::OverlapEqualizer::overlapWidth(offsets, 1) == 23, "seam1 width");
    check(irpv::hik::OverlapEqualizer::overlapWidth(offsets, 2) == 9, "seam2 width");
}

void testChainBiases() {
    const int32_t seams[3] = {100, 50, 25};
    int32_t bias[4]{};
    irpv::hik::OverlapEqualizer::chainBiasesFromSeams(seams, 1, bias);
    check(bias[0] == -100, "anchor slot2 bias0");
    check(bias[1] == 0, "anchor slot2 bias1");
    check(bias[2] == 50, "anchor slot2 bias2");
    check(bias[3] == 75, "anchor slot2 bias3");
}

void fillTile(uint16_t* tile, uint16_t value) {
    const size_t n = static_cast<size_t>(irpv::hik::kTileW) * irpv::hik::kTileH;
    for (size_t i = 0; i < n; ++i) {
        tile[i] = value;
    }
}

void testProcessCancelsSeamStep() {
    uint16_t tiles[4][irpv::hik::kTileW * irpv::hik::kTileH]{};
    fillTile(tiles[0], 1100);
    fillTile(tiles[1], 1000);
    fillTile(tiles[2], 1000);
    fillTile(tiles[3], 1000);

    irpv::hik::SlotOffset offsets[4] = {
        {16, 0},
        {0, 0},
        {0, 0},
        {0, 0},
    };

    irpv::hik::OverlapEqualizer eq;
    irpv::hik::EqualizerConfig cfg;
    cfg.enabled = true;
    cfg.alpha = 1.0;
    cfg.min_samples = 50;
    cfg.anchor_slot = 1;
    eq.setConfig(cfg);
    eq.process(tiles, offsets);

    const auto& st = eq.state();
    check(st.ema_seam_delta[0] == 100, "measured seam0 delta");
    check(st.ema_bias_u16[0] == -100, "applied slot1 bias");

    const int overlap_x0 = 256;
    const int overlap_x1 = 271;
    int64_t left_sum = 0;
    int64_t right_sum = 0;
    int count = 0;
    for (int y = 0; y < irpv::hik::kTileH; ++y) {
        for (int x = overlap_x0; x <= overlap_x1; ++x) {
            const int left_tx = x - offsets[0].dx;
            const int right_tx = x - 256;
            left_sum += tiles[0][static_cast<size_t>(y) * irpv::hik::kTileW + left_tx];
            right_sum += tiles[1][static_cast<size_t>(y) * irpv::hik::kTileW + right_tx];
            ++count;
        }
    }
    check(count > 0, "overlap samples");
    const int left_mean = static_cast<int>(left_sum / count);
    const int right_mean = static_cast<int>(right_sum / count);
    check(std::abs(left_mean - right_mean) <= 1, "overlap means match after equalization");
}

} // namespace

int main() {
    testOverlapWidth();
    testChainBiases();
    testProcessCancelsSeamStep();
    if (failures == 0) {
        std::fprintf(stderr, "overlap_equalizer_test: all passed\n");
        return 0;
    }
    std::fprintf(stderr, "overlap_equalizer_test: %d failure(s)\n", failures);
    return 1;
}
