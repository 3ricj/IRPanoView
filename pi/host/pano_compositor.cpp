#include "pano_compositor.h"

#include <chrono>
#include <cstdio>

namespace irpv::hik {

namespace {

uint64_t steadyNowUs() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

} // namespace

PanoCompositor::PanoCompositor(HikHostOrchestrator& orchestrator) : orchestrator_(orchestrator) {
    geometry_ = PanoGeometry::legacy();
    latest_.geometry = geometry_;
    latest_.pano.resize(geometry_.pixelCount(), 0);
}

PanoCompositor::~PanoCompositor() {
    stop();
}

void PanoCompositor::setTickCallback(TickCallback cb) {
    on_tick_ = std::move(cb);
}

void PanoCompositor::setStitchMode(StitchMode mode) {
    stitch_mode_ = mode;
}

bool PanoCompositor::loadWarpCalib(const std::string& dir_path) {
    auto calib = std::make_unique<PtguiCalib>();
    if (!calib->loadFromDir(dir_path)) {
        return false;
    }
    geometry_ = calib->geometry();
    calib_ = std::move(calib);
    stitch_mode_ = StitchMode::Warp;
    latest_.geometry = geometry_;
    latest_.pano.resize(geometry_.pixelCount(), 0);
    return true;
}

void PanoCompositor::setOffsets(const SlotOffset offsets[4]) {
    for (int i = 0; i < 4; ++i) {
        offsets_[i] = offsets[i];
    }
    use_offsets_ = true;
    for (int i = 0; i < 4; ++i) {
        if (offsets_[i].dx != 0 || offsets_[i].dy != 0) {
            return;
        }
    }
    use_offsets_ = false;
}

void PanoCompositor::setEqualizationEnabled(bool enabled) {
    equalization_enabled_ = enabled;
}

void PanoCompositor::setEqualizationAlpha(double alpha) {
    equalization_alpha_ = alpha;
}

EqualizerState PanoCompositor::equalizerState() const {
    return equalizer_.state();
}

void PanoCompositor::start() {
    stop();
    running_ = true;
    thread_ = std::thread(&PanoCompositor::runLoop, this);
}

void PanoCompositor::stop() {
    running_ = false;
    if (thread_.joinable()) {
        thread_.join();
    }
}

bool PanoCompositor::copyLatest(PanoSnapshot& out) const {
    std::lock_guard<std::mutex> lock(snap_mutex_);
    if (latest_.pano.empty()) {
        return false;
    }
    out = latest_;
    return true;
}

void PanoCompositor::runLoop() {
    const auto interval = std::chrono::milliseconds(1000 / kStreamFps);
    auto next_tick = std::chrono::steady_clock::now();
    auto last_report = next_tick;
    int ticks = 0;

    while (running_) {
        const uint64_t compose_us = steadyNowUs();

        PanoSnapshot snap;
        snap.geometry = geometry_;
        snap.pano.resize(geometry_.pixelCount(), 0);
        snap.compose_us = compose_us;
        snap.compose_seq = ++compose_seq_;

        uint16_t tiles[4][kTileW * kTileH]{};
        bool any = false;

        for (int i = 0; i < 4; ++i) {
            TileSnapshot tile;
            const auto& w = orchestrator_.worker(static_cast<size_t>(i));
            if (w && w->copyLatestSnapshot(tile) && tile.valid) {
                std::copy(tile.pixels.begin(), tile.pixels.end(), tiles[i]);
                snap.slots[i].frame_seq = tile.frame_seq;
                snap.slots[i].usb_frame_us = tile.usb_frame_us;
                snap.slots[i].valid = true;
                snap.health[i] = 1;
                any = true;
                if (tile.usb_frame_us > 0 && compose_us >= tile.usb_frame_us) {
                    snap.slots[i].age_ms = static_cast<uint32_t>((compose_us - tile.usb_frame_us) / 1000);
                }
            } else {
                snap.slots[i].valid = false;
                snap.health[i] = 0;
            }
        }

        if (any) {
            EqualizerConfig eq_cfg;
            eq_cfg.enabled = equalization_enabled_;
            eq_cfg.alpha = equalization_alpha_;
            equalizer_.setConfig(eq_cfg);

            if (stitch_mode_ == StitchMode::Warp && calib_) {
                // Radiometric equalization on raw u16 tiles using warp overlap samples.
                equalizer_.processWarp(tiles, calib_->eqSeams());
                calib_->warp().stitch(tiles, snap.pano.data(), snap.pano.size());
            } else if (use_offsets_) {
                equalizer_.process(tiles, offsets_);
                offset_stitch_.stitch(tiles, offsets_, snap.pano.data(), snap.pano.size());
            } else {
                stitch_.stitch(tiles, snap.pano.data(), snap.pano.size());
            }
        }

        {
            std::lock_guard<std::mutex> lock(snap_mutex_);
            latest_ = std::move(snap);
        }

        if (on_tick_) {
            std::lock_guard<std::mutex> lock(snap_mutex_);
            on_tick_(latest_);
        }

        ++ticks;
        const auto now = std::chrono::steady_clock::now();
        const double elapsed = std::chrono::duration<double>(now - last_report).count();
        if (elapsed >= 1.0) {
            tick_hz_ = ticks / elapsed;
            ticks = 0;
            last_report = now;
        }

        next_tick += interval;
        std::this_thread::sleep_until(next_tick);
    }
}

} // namespace irpv::hik
