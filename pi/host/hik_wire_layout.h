#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace irpv::hik {

class HikWireLayout {
public:
    static constexpr int kWidth = 256;
    static constexpr int kHeight = 192;
    static constexpr int kRowBytes = 512;
    static constexpr int kRadioOffset = 0;

    static uint16_t readRadioRaw16(const uint8_t* frame, size_t frame_len, int y, int x);
    static void extractRadioGrid(const uint8_t* frame, size_t frame_len, uint16_t* out, size_t out_count);
};

} // namespace irpv::hik
