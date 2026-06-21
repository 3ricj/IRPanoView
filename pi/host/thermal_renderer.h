#pragma once

#include "thermal_frame.h"

#include <cstdint>
#include <vector>

namespace irpv {

struct ThermalRenderConfig {
    double floor_c = 20.0;
    double ceiling_c = 40.0;
    bool auto_range = true;
};

class ThermalRenderer {
public:
    void setConfig(const ThermalRenderConfig& config) { config_ = config; }
    const ThermalRenderConfig& config() const { return config_; }

    bool renderJetNv12(
        const uint16_t* pano,
        size_t pixel_count,
        int width,
        int height,
        std::vector<uint8_t>& nv12_out);

    // RGB888 row-major; shared min/max_c keeps colormap consistent across tiles.
    bool renderJetRgb(
        const uint16_t* pixels,
        int width,
        int height,
        double min_c,
        double max_c,
        std::vector<uint8_t>& rgb_out) const;

    void resolveWindowForPixels(
        const uint16_t* pixels,
        size_t pixel_count,
        double& out_min,
        double& out_max) const;

private:
    static double celsiusFromRaw(uint16_t raw);
    static void jetRgbFromDisplay(int display, uint8_t& r, uint8_t& g, uint8_t& b);
    void resolveWindow(
        const uint16_t* pano,
        size_t pixel_count,
        double& out_min,
        double& out_max) const;

    ThermalRenderConfig config_;
};

} // namespace irpv
