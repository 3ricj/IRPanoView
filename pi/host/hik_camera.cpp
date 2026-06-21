#include "hik_camera.h"

#include "hik_constants.h"
#include "hik_wire_layout.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <thread>

namespace irpv::hik {

HikCameraWorker::HikCameraWorker() {
    latest_tile_.resize(static_cast<size_t>(kGridWidth) * kGridHeight, 0);
    latest_snapshot_.pixels.resize(TileSnapshot::kPixelCount, 0);
}

namespace {

uint64_t steadyNowUs() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

} // namespace

HikCameraWorker::~HikCameraWorker() {
    stop();
}

bool HikCameraWorker::start(const UsbDeviceInfo& info, bool demo_mode) {
    stop();
    info_ = info;
    demo_mode_ = demo_mode;
    running_ = true;
    if (demo_mode) {
        thread_ = std::thread(&HikCameraWorker::runDemoLoop, this);
        return true;
    }

    usb_ = std::make_unique<HikUsbDevice>();
    if (!usb_->open(info)) {
        last_error_ = "open failed";
        running_ = false;
        return false;
    }
    protocol_ = std::make_unique<HikUvcProtocol>(*usb_);
    if (!protocol_->login(true)) {
        last_error_ = "login failed";
    } else if (!protocol_->waitStreamReady()) {
        last_error_ = "wait ready failed";
    } else if (!protocol_->initConfig()) {
        last_error_ = "initConfig failed";
    } else if (!protocol_->setVideoParam()) {
        last_error_ = "video param failed";
    } else if (!protocol_->armStream()) {
        last_error_ = "arm stream failed";
    } else {
        streaming_ = true;
        reassembler_.beginStream();
        thread_ = std::thread(&HikCameraWorker::runLoop, this);
        return true;
    }
    usb_->close();
    protocol_.reset();
    usb_.reset();
    running_ = false;
    return false;
}

void HikCameraWorker::stop() {
    running_ = false;
    if (thread_.joinable()) {
        thread_.join();
    }
    if (usb_) {
        if (protocol_) {
            protocol_->disarmStream();
        }
        usb_->selectAlt0();
        usb_->close();
    }
    protocol_.reset();
    usb_.reset();
    streaming_ = false;
}

bool HikCameraWorker::copyLatestTile(uint16_t* out, size_t out_count) {
    const size_t expected = static_cast<size_t>(kGridWidth) * kGridHeight;
    if (out_count < expected) {
        return false;
    }
    std::lock_guard<std::mutex> lock(tile_mutex_);
    if (latest_tile_.size() < expected) {
        return false;
    }
    std::copy(latest_tile_.begin(), latest_tile_.begin() + static_cast<std::ptrdiff_t>(expected), out);
    return streaming_;
}

bool HikCameraWorker::copyLatestSnapshot(TileSnapshot& out) const {
    std::lock_guard<std::mutex> lock(tile_mutex_);
    if (latest_snapshot_.pixels.size() < TileSnapshot::kPixelCount) {
        return false;
    }
    out = latest_snapshot_;
    return streaming_;
}

CameraStatus HikCameraWorker::status() const {
    CameraStatus s;
    s.slot = info_.slot;
    s.streaming = streaming_;
    s.fps = fps_;
    s.serial = info_.serial;
    s.error = last_error_;
    s.bulk_reads = bulk_reads_;
    s.frames = frames_;
    return s;
}

bool HikCameraWorker::setIrConfig(double emissivity, double distance_m, double ambient_c) {
    if (!protocol_) {
        return demo_mode_;
    }
    return protocol_->setIrConfig(emissivity, distance_m, ambient_c);
}

bool HikCameraWorker::triggerNuc() {
    if (!protocol_) {
        return demo_mode_;
    }
    return protocol_->manualShutter();
}

void HikCameraWorker::runLoop() {
    reassembler_.beginStream();
    int frames = 0;
    int idle_reads = 0;
    auto last_report = std::chrono::steady_clock::now();

    while (running_) {
        const int timeout_ms = idle_reads > 0 ? kBulkTimeoutIdleMs : kBulkTimeoutMs;
        auto packet = usb_->bulkRead(usb_->bulkEndpoint(), 16384, timeout_ms);
        if (!packet) {
            if (++idle_reads > 50) {
                last_error_ = "bulk idle — re-arming stream";
                streaming_ = false;
                if (protocol_ && protocol_->setVideoParam() && protocol_->armStream()) {
                    reassembler_.beginStream();
                    streaming_ = true;
                    last_error_.clear();
                    idle_reads = 0;
                }
            }
            continue;
        }
        idle_reads = 0;
        ++bulk_reads_;
        auto frame = reassembler_.push(packet->data(), packet->size());
        if (!frame) {
            continue;
        }
        ++frames_;
        const uint64_t usb_us = steadyNowUs();
        std::vector<uint16_t> tile(static_cast<size_t>(kGridWidth) * kGridHeight);
        HikWireLayout::extractRadioGrid(frame->data(), frame->size(), tile.data(), tile.size());
        {
            std::lock_guard<std::mutex> lock(tile_mutex_);
            latest_snapshot_.pixels = tile;
            latest_tile_ = tile;
            latest_snapshot_.usb_frame_us = usb_us;
            latest_snapshot_.frame_seq = frames_;
            latest_snapshot_.valid = true;
        }
        ++frames;
        const auto now = std::chrono::steady_clock::now();
        const auto elapsed = std::chrono::duration<double>(now - last_report).count();
        if (elapsed >= 1.0) {
            fps_ = frames / elapsed;
            frames = 0;
            last_report = now;
        }
    }
}

void HikCameraWorker::runDemoLoop() {
    streaming_ = true;
    int t = 0;
    int frames = 0;
    const auto start = std::chrono::steady_clock::now();
    auto last_report = start;
    constexpr double kKelvinOffsetC = 273.15;
    constexpr int kTempBiasU16 = 0x37C0;
    constexpr int kTempScale = 64;
    while (running_) {
        std::vector<uint16_t> tile(static_cast<size_t>(kGridWidth) * kGridHeight);
        const double base_c = 22.0 + static_cast<double>(info_.slot) * 1.5;
        for (int y = 0; y < kGridHeight; ++y) {
            for (int x = 0; x < kGridWidth; ++x) {
                const double wave = std::sin((x + info_.slot * 40 + t) * 0.05) * 4.0;
                const double gradient = (static_cast<double>(y) / kGridHeight) * 3.0;
                const double celsius = base_c + wave + gradient;
                const int stored = static_cast<int>((celsius + kKelvinOffsetC) * kTempScale);
                const int raw = (stored - kTempBiasU16) & 0xFFFF;
                tile[static_cast<size_t>(y) * kGridWidth + x] = static_cast<uint16_t>(raw);
            }
        }
        const uint64_t usb_us = steadyNowUs();
        {
            std::lock_guard<std::mutex> lock(tile_mutex_);
            latest_tile_ = tile;
            latest_snapshot_.pixels = tile;
            latest_snapshot_.usb_frame_us = usb_us;
            latest_snapshot_.frame_seq = static_cast<uint32_t>(t);
            latest_snapshot_.valid = true;
        }
        ++t;
        ++frames;
        const auto now = std::chrono::steady_clock::now();
        const auto elapsed = std::chrono::duration<double>(now - last_report).count();
        if (elapsed >= 1.0) {
            fps_ = frames / elapsed;
            frames = 0;
            last_report = now;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1000 / kStreamFps));
    }
}

} // namespace irpv::hik
