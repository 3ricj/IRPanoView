#include "hik_wire_layout.h"

#include "hik_constants.h"

namespace irpv::hik {

uint16_t HikWireLayout::readRadioRaw16(const uint8_t* frame, size_t frame_len, int y, int x) {
    if (x < 0 || x >= kWidth || y < 0 || y >= kHeight) {
        return 0;
    }
    const int mac = kRadioOffset + y * kRowBytes + (x >> 1) * 4;
    if (static_cast<size_t>(mac + 3) >= frame_len) {
        return 0;
    }
    if ((x & 1) == 0) {
        return static_cast<uint16_t>(frame[mac] | (frame[mac + 1] << 8));
    }
    return static_cast<uint16_t>(frame[mac + 2] | (frame[mac + 3] << 8));
}

void HikWireLayout::extractRadioGrid(const uint8_t* frame, size_t frame_len, uint16_t* out, size_t out_count) {
    const size_t expected = static_cast<size_t>(kWidth) * kHeight;
    if (out_count < expected) {
        return;
    }
    for (int y = 0; y < kHeight; ++y) {
        for (int x = 0; x < kWidth; ++x) {
            out[static_cast<size_t>(y) * kWidth + x] = readRadioRaw16(frame, frame_len, y, x);
        }
    }
}

} // namespace irpv::hik
