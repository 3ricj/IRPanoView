#pragma once

#include <cstdint>
#include <vector>

namespace irpv::hik {

struct EqSeamSample {
    int32_t left_x_q8 = 0;
    int32_t left_y_q8 = 0;
    int32_t right_x_q8 = 0;
    int32_t right_y_q8 = 0;
};

struct EqSeamPair {
    int left_slot = 0;
    int right_slot = 0;
    std::vector<EqSeamSample> samples;
};

} // namespace irpv::hik
