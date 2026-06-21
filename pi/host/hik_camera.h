#pragma once

#include "hik_bulk_reassembler.h"
#include "hik_usb_device.h"
#include "hik_uvc_protocol.h"
#include "tile_snapshot.h"

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace irpv::hik {

struct CameraStatus {
    int slot = 0;
    bool streaming = false;
    double fps = 0.0;
    std::string serial;
    std::string error;
    uint64_t bulk_reads = 0;
    uint64_t frames = 0;
};

class HikCameraWorker {
public:
    HikCameraWorker();
    ~HikCameraWorker();

    bool start(const UsbDeviceInfo& info, bool demo_mode);
    void stop();

    bool copyLatestTile(uint16_t* out, size_t out_count);
    bool copyLatestSnapshot(TileSnapshot& out) const;
    CameraStatus status() const;

    bool setIrConfig(double emissivity, double distance_m, double ambient_c);
    bool triggerNuc();

private:
    void runLoop();
    void runDemoLoop();

    UsbDeviceInfo info_{};
    std::unique_ptr<HikUsbDevice> usb_;
    std::unique_ptr<HikUvcProtocol> protocol_;
    HikBulkReassembler reassembler_;

    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> demo_mode_{false};

    mutable std::mutex tile_mutex_;
    TileSnapshot latest_snapshot_;
    std::vector<uint16_t> latest_tile_;
    std::atomic<double> fps_{0.0};
    std::atomic<bool> streaming_{false};
    std::atomic<uint64_t> bulk_reads_{0};
    std::atomic<uint64_t> frames_{0};
    std::string last_error_;
};

} // namespace irpv::hik
