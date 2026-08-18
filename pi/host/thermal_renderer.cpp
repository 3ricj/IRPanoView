#include "thermal_renderer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace irpv {

namespace {

inline constexpr int kTempBiasU16 = 0x37C0;
inline constexpr int kTempScale = 64;
inline constexpr double kKelvinOffsetC = 273.15;
inline constexpr double kDefaultMinSpanC = 2.0;
inline constexpr int kWindowRefreshFrames = 25;

struct YuvPixel {
    uint8_t y = 0;
    uint8_t u = 0;
    uint8_t v = 0;
};

float jetChannel(float t, float center) {
    return std::clamp(1.5f - std::fabs(4.f * t - center), 0.f, 1.f);
}

uint8_t toByte(float v) {
    return static_cast<uint8_t>(std::clamp(static_cast<int>(std::lround(v * 255.f)), 0, 255));
}

YuvPixel jetYuv(uint8_t display) {
    const float t = display / 255.f;
    const uint8_t r = toByte(jetChannel(t, 3.f));
    const uint8_t g = toByte(jetChannel(t, 2.f));
    const uint8_t b = toByte(jetChannel(t, 1.f));
    const float rf = r / 255.f;
    const float gf = g / 255.f;
    const float bf = b / 255.f;
    return YuvPixel{
        toByte(0.299f * rf + 0.587f * gf + 0.114f * bf),
        toByte(-0.169f * rf - 0.331f * gf + 0.5f * bf + 0.5f),
        toByte(0.5f * rf - 0.419f * gf - 0.081f * bf + 0.5f),
    };
}

std::array<YuvPixel, 256> buildJetLut() {
    std::array<YuvPixel, 256> lut{};
    for (int i = 0; i < 256; ++i) {
        lut[static_cast<size_t>(i)] = jetYuv(static_cast<uint8_t>(i));
    }
    return lut;
}

const std::array<YuvPixel, 256> kJetLut = buildJetLut();

double nthPercentile(std::vector<double>& samples, double p) {
    if (samples.empty()) {
        return 0.0;
    }
    const size_t idx = std::clamp(
        static_cast<size_t>(std::lround((p / 100.0) * static_cast<double>(samples.size() - 1))),
        static_cast<size_t>(0),
        samples.size() - 1);
    std::nth_element(samples.begin(), samples.begin() + static_cast<std::ptrdiff_t>(idx), samples.end());
    return samples[idx];
}

} // namespace

double ThermalRenderer::celsiusFromRaw(uint16_t raw) {
    const int stored = (static_cast<int>(raw) + kTempBiasU16) & 0xFFFF;
    return stored / static_cast<double>(kTempScale) - kKelvinOffsetC;
}

void ThermalRenderer::jetRgbFromDisplay(int display, uint8_t& r, uint8_t& g, uint8_t& b) {
    const float t = static_cast<float>(display) / 255.f;
    r = toByte(jetChannel(t, 3.f));
    g = toByte(jetChannel(t, 2.f));
    b = toByte(jetChannel(t, 1.f));
}

void ThermalRenderer::resolveWindowForPixels(
    const uint16_t* pixels,
    size_t pixel_count,
    double& out_min,
    double& out_max) const {
    resolveWindow(pixels, pixel_count, out_min, out_max);
}

void ThermalRenderer::resolveWindow(
    const uint16_t* pano,
    size_t pixel_count,
    double& out_min,
    double& out_max) const {
    // Always derive the LUT window from scene radiometrics. Floor/ceiling are soft
    // caps (clip interest), not hard LUT endpoints — e.g. ceiling=100 with scene
    // max=60 maps 0..255 across the real ~60 C span, not an empty stretch to 100.
    std::vector<double> samples;
    samples.reserve(pixel_count / 16 + 1);
    for (size_t i = 0; i < pixel_count; i += 16) {
        const uint16_t raw = pano[i];
        if (raw == 0) {
            continue; // uncovered warp/edge — exclude from range stats
        }
        samples.push_back(celsiusFromRaw(raw));
    }
    if (samples.empty()) {
        out_min = config_.floor_c;
        out_max = std::max(config_.ceiling_c, config_.floor_c + kDefaultMinSpanC);
        return;
    }

    auto work = samples;
    const double p_lo = nthPercentile(work, 5.0);
    work = samples;
    const double p_hi = nthPercentile(work, 95.0);
    work = samples;
    const double p99 = nthPercentile(work, 99.5);
    const double span_max = *std::max_element(samples.begin(), samples.end());
    double scene_min = p_lo;
    double scene_max = std::max(p_hi, std::max(p99, span_max - 0.5));

    if (config_.auto_range) {
        out_min = scene_min;
        out_max = scene_max;
    } else {
        out_min = std::max(scene_min, config_.floor_c);
        out_max = std::min(scene_max, config_.ceiling_c);
        if (out_max <= out_min) {
            // Entire scene outside the interest band — pin to nearest edge.
            if (scene_max <= config_.floor_c) {
                out_min = config_.floor_c;
                out_max = config_.floor_c + kDefaultMinSpanC;
            } else if (scene_min >= config_.ceiling_c) {
                out_max = config_.ceiling_c;
                out_min = config_.ceiling_c - kDefaultMinSpanC;
            } else {
                out_min = config_.floor_c;
                out_max = std::max(config_.ceiling_c, config_.floor_c + kDefaultMinSpanC);
            }
        }
    }

    if (out_max - out_min < kDefaultMinSpanC) {
        const double mid = (out_min + out_max) / 2.0;
        out_min = mid - kDefaultMinSpanC / 2.0;
        out_max = mid + kDefaultMinSpanC / 2.0;
    }
}

bool ThermalRenderer::renderJetNv12(
    const uint16_t* pano,
    size_t pixel_count,
    int width,
    int height,
    std::vector<uint8_t>& nv12_out) {
    if (width <= 0 || height <= 0) {
        return false;
    }
    const size_t expected = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (pixel_count < expected) {
        return false;
    }

    static int frame_counter = 0;
    static double cached_min = 20.0;
    static double cached_max = 40.0;
    if ((frame_counter++ % kWindowRefreshFrames) == 0) {
        resolveWindow(pano, expected, cached_min, cached_max);
    }
    const double span = std::max(cached_max - cached_min, 1e-6);

    const size_t nv12_bytes = expected * 3 / 2;
    if (nv12_out.size() != nv12_bytes) {
        nv12_out.assign(nv12_bytes, 16);
    }
    uint8_t* y_plane = nv12_out.data();
    uint8_t* uv_plane = nv12_out.data() + expected;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t idx = static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x);
            const double c = celsiusFromRaw(pano[idx]);
            const int display = std::clamp(
                static_cast<int>(std::lround(((c - cached_min) / span) * 255.0)),
                0,
                255);
            const auto& pix = kJetLut[static_cast<size_t>(display)];
            y_plane[idx] = pix.y;

            if ((y & 1) == 0 && (x & 1) == 0) {
                const size_t uv_idx = static_cast<size_t>(y / 2) * static_cast<size_t>(width) + static_cast<size_t>(x);
                uv_plane[uv_idx] = pix.u;
                uv_plane[uv_idx + 1] = pix.v;
            }
        }
    }
    return true;
}

bool ThermalRenderer::renderDisplayU8(
    const uint16_t* pano,
    size_t pixel_count,
    int width,
    int height,
    std::vector<uint8_t>& u8_out,
    double& out_min_c,
    double& out_max_c) {
    if (width <= 0 || height <= 0 || !pano) {
        return false;
    }
    const size_t expected = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (pixel_count < expected) {
        return false;
    }

    // Manual (clipped) mode: refresh every frame so floor/ceiling edits are instant
    // and the scene-derived span stays current. Auto: periodic percentile refresh.
    static int frame_counter = 0;
    static double cached_min = 20.0;
    static double cached_max = 40.0;
    static bool cached_auto = true;
    static double cached_floor = 20.0;
    static double cached_ceiling = 40.0;
    const bool need_refresh = (!config_.auto_range) ||
        (cached_auto != config_.auto_range) ||
        (cached_floor != config_.floor_c) ||
        (cached_ceiling != config_.ceiling_c) ||
        ((frame_counter++ % kWindowRefreshFrames) == 0);
    if (need_refresh) {
        resolveWindow(pano, expected, cached_min, cached_max);
        cached_auto = config_.auto_range;
        cached_floor = config_.floor_c;
        cached_ceiling = config_.ceiling_c;
    }
    out_min_c = cached_min;
    out_max_c = cached_max;
    const double span = std::max(cached_max - cached_min, 1e-6);

    u8_out.resize(expected);
    for (size_t i = 0; i < expected; ++i) {
        const double c = celsiusFromRaw(pano[i]);
        u8_out[i] = static_cast<uint8_t>(std::clamp(
            static_cast<int>(std::lround(((c - cached_min) / span) * 255.0)),
            0,
            255));
    }
    return true;
}

bool ThermalRenderer::renderJetRgb(
    const uint16_t* pixels,
    int width,
    int height,
    double min_c,
    double max_c,
    std::vector<uint8_t>& rgb_out) const {
    if (width <= 0 || height <= 0 || !pixels) {
        return false;
    }
    const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
    const double span = std::max(max_c - min_c, 1e-6);
    rgb_out.resize(count * 3);
    for (size_t i = 0; i < count; ++i) {
        const double c = celsiusFromRaw(pixels[i]);
        const int display = std::clamp(
            static_cast<int>(std::lround(((c - min_c) / span) * 255.0)),
            0,
            255);
        uint8_t r = 0;
        uint8_t g = 0;
        uint8_t b = 0;
        jetRgbFromDisplay(display, r, g, b);
        rgb_out[i * 3] = r;
        rgb_out[i * 3 + 1] = g;
        rgb_out[i * 3 + 2] = b;
    }
    return true;
}

} // namespace irpv
