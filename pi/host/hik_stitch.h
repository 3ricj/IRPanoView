#pragma once

#include "hik_camera.h"

#include <cstdint>
#include <memory>
#include <vector>

namespace irpv::hik {

class EdgeStitchStrategy {
public:
    static constexpr int kTileW = 256;
    static constexpr int kTileH = 192;
    static constexpr int kOutW = 1024;
    static constexpr int kOutH = 192;

    void stitch(const uint16_t tiles[4][kTileW * kTileH], uint16_t* out, size_t out_count) const;
};

class HikHostOrchestrator {
public:
    explicit HikHostOrchestrator(bool demo_mode = false);

    bool start();
    void stop();

    bool composePano(uint16_t* out, size_t out_count, uint8_t health[4]) const;
    const HikCameraWorker* worker(size_t index) const;
    std::vector<CameraStatus> cameraStatuses() const;

    bool setIrConfigAll(double emissivity, double distance_m, double ambient_c);
    bool triggerNucAll();
    void setTemporalAverage(int frames);

    double stitchFps() const { return stitch_fps_; }

private:
    bool demo_mode_;
    mutable std::vector<std::unique_ptr<HikCameraWorker>> workers_;
    EdgeStitchStrategy stitch_;
    int temporal_average_ = 1;
    mutable double stitch_fps_ = 0.0;
};

} // namespace irpv::hik
