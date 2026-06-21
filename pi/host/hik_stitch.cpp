#include "hik_stitch.h"

#include "hik_constants.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <string>

namespace irpv::hik {

void EdgeStitchStrategy::stitch(const uint16_t tiles[4][kTileW * kTileH], uint16_t* out, size_t out_count) const {
    const size_t expected = static_cast<size_t>(kOutW) * kOutH;
    if (out_count < expected) {
        return;
    }
    for (int cam = 0; cam < 4; ++cam) {
        for (int y = 0; y < kTileH; ++y) {
            for (int x = 0; x < kTileW; ++x) {
                out[static_cast<size_t>(y) * kOutW + cam * kTileW + x] =
                    tiles[cam][static_cast<size_t>(y) * kTileW + x];
            }
        }
    }
}

HikHostOrchestrator::HikHostOrchestrator(bool demo_mode) : demo_mode_(demo_mode) {}

bool HikHostOrchestrator::start() {
    stop();
    std::vector<UsbDeviceInfo> devices;
    if (demo_mode_) {
        for (int i = 0; i < 4; ++i) {
            UsbDeviceInfo info;
            info.slot = i + 1;
            info.serial = "DEMO0" + std::to_string(i + 1);
            devices.push_back(info);
        }
    } else {
        const auto found = enumerateHikDevices();
        std::fprintf(stderr, "enumerate: found %zu Hik camera(s)\n", found.size());
        for (const auto& dev : found) {
            std::fprintf(stderr, "  slot=%d serial=%s bus=%s\n", dev.slot, dev.serial.c_str(), dev.bus_path.c_str());
        }
        if (found.empty()) {
            return false;
        }
        std::array<bool, 4> filled{};
        for (const auto& dev : found) {
            if (dev.slot >= 1 && dev.slot <= 4 && !filled[static_cast<size_t>(dev.slot - 1)]) {
                devices.push_back(dev);
                filled[static_cast<size_t>(dev.slot - 1)] = true;
            }
        }
        for (const auto& dev : found) {
            if (devices.size() >= 4) {
                break;
            }
            if (dev.slot >= 1 && dev.slot <= 4 && filled[static_cast<size_t>(dev.slot - 1)]) {
                continue;
            }
            UsbDeviceInfo copy = dev;
            for (int s = 1; s <= 4; ++s) {
                if (!filled[static_cast<size_t>(s - 1)]) {
                    copy.slot = s;
                    devices.push_back(copy);
                    filled[static_cast<size_t>(s - 1)] = true;
                    break;
                }
            }
        }
        // No minimum-camera requirement: run with however many enumerated.
        // Missing slots stay empty (nullptr worker) and render black in the pano;
        // the per-camera raw stream simply omits absent slots.
    }

    workers_.clear();
    workers_.resize(4);
    int started = 0;
    for (const auto& dev : devices) {
        const int idx = dev.slot - 1;
        if (idx < 0 || idx >= 4) {
            continue;
        }
        auto worker = std::make_unique<HikCameraWorker>();
        if (!worker->start(dev, demo_mode_)) {
            std::fprintf(stderr, "camera slot %d (%s) failed: %s — skipping\n",
                dev.slot, dev.serial.c_str(), worker->status().error.c_str());
            continue;
        }
        workers_[static_cast<size_t>(idx)] = std::move(worker);
        ++started;
    }
    if (started == 0) {
        std::fprintf(stderr, "no cameras started\n");
        stop();
        return false;
    }
    std::fprintf(stderr, "started %d/4 camera slot(s)\n", started);
    return true;
}

void HikHostOrchestrator::stop() {
    for (auto& w : workers_) {
        if (w) {
            w->stop();
        }
    }
    workers_.clear();
}

bool HikHostOrchestrator::composePano(uint16_t* out, size_t out_count, uint8_t health[4]) const {
    if (workers_.size() != 4) {
        return false;
    }
    uint16_t tiles[4][EdgeStitchStrategy::kTileW * EdgeStitchStrategy::kTileH]{};
    bool any = false;
    for (int i = 0; i < 4; ++i) {
        const auto& worker = workers_[static_cast<size_t>(i)];
        const bool ok = worker && worker->copyLatestTile(
            tiles[i],
            EdgeStitchStrategy::kTileW * EdgeStitchStrategy::kTileH);
        health[i] = ok ? 1 : 0;
        any = any || ok;
    }
    stitch_.stitch(tiles, out, out_count);
    static auto last = std::chrono::steady_clock::now();
    static int frames = 0;
    ++frames;
    const auto now = std::chrono::steady_clock::now();
    const double elapsed = std::chrono::duration<double>(now - last).count();
    if (elapsed >= 1.0) {
        stitch_fps_ = frames / elapsed;
        frames = 0;
        last = now;
    }
    return any;
}

const HikCameraWorker* HikHostOrchestrator::worker(size_t index) const {
    if (index >= workers_.size()) {
        return nullptr;
    }
    return workers_[index].get();
}

std::vector<CameraStatus> HikHostOrchestrator::cameraStatuses() const {
    std::vector<CameraStatus> out;
    out.reserve(workers_.size());
    for (const auto& w : workers_) {
        if (w) {
            out.push_back(w->status());
        }
    }
    return out;
}

bool HikHostOrchestrator::setIrConfigAll(double emissivity, double distance_m, double ambient_c) {
    bool ok = true;
    for (auto& w : workers_) {
        if (w) {
            ok = w->setIrConfig(emissivity, distance_m, ambient_c) && ok;
        }
    }
    return ok;
}

bool HikHostOrchestrator::triggerNucAll() {
    bool ok = true;
    for (auto& w : workers_) {
        if (w) {
            ok = w->triggerNuc() && ok;
        }
    }
    return ok;
}

void HikHostOrchestrator::setTemporalAverage(int frames) {
    temporal_average_ = std::max(1, frames);
}

} // namespace irpv::hik
