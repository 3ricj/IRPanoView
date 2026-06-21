#pragma once

#include "hik_stitch.h"
#include "overlap_equalizer.h"
#include "pano_geometry.h"
#include "ptgui_calib.h"
#include "stitch_offsets.h"
#include "tile_snapshot.h"
#include "warp_stitch.h"

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>

namespace irpv::hik {

enum class StitchMode {
    Edge,
    Offset,
    Warp,
};

class PanoCompositor {
public:
    using TickCallback = std::function<void(const PanoSnapshot&)>;

    explicit PanoCompositor(HikHostOrchestrator& orchestrator);
    ~PanoCompositor();

    void setTickCallback(TickCallback cb);
    void setOffsets(const SlotOffset offsets[4]);
    void setStitchMode(StitchMode mode);
    bool loadWarpCalib(const std::string& dir_path);
    const PanoGeometry& geometry() const { return geometry_; }
    const PtguiCalib* warpCalib() const { return calib_.get(); }
    StitchMode stitchMode() const { return stitch_mode_; }

    void setEqualizationEnabled(bool enabled);
    void setEqualizationAlpha(double alpha);
    EqualizerState equalizerState() const;
    void start();
    void stop();

    bool copyLatest(PanoSnapshot& out) const;
    double tickHz() const { return tick_hz_; }

private:
    void runLoop();

    HikHostOrchestrator& orchestrator_;
    TickCallback on_tick_;
    std::thread thread_;
    std::atomic<bool> running_{false};

    mutable std::mutex snap_mutex_;
    PanoSnapshot latest_;
    uint32_t compose_seq_ = 0;

    PanoGeometry geometry_ = PanoGeometry::legacy();
    StitchMode stitch_mode_ = StitchMode::Edge;
    EdgeStitchStrategy stitch_;
    OffsetStitchStrategy offset_stitch_;
    std::unique_ptr<PtguiCalib> calib_;
    OverlapEqualizer equalizer_;
    SlotOffset offsets_[4]{};
    bool use_offsets_ = false;
    bool equalization_enabled_ = true;
    double equalization_alpha_ = 0.05;
    mutable double tick_hz_ = 0.0;
};

} // namespace irpv::hik
