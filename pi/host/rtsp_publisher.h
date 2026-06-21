#pragma once

#include "thermal_frame.h"

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

struct _GstElement;
struct _GMainLoop;

namespace irpv {

class RtspPublisher {
public:
    RtspPublisher();
    ~RtspPublisher();

    bool start(uint16_t port, const std::string& mount_path, int bitrate, int width = kPanoWidth, int height = kPanoHeight);
    void stop();

    bool pushNv12Frame(const uint8_t* nv12, size_t bytes, uint64_t pts_us);
    bool hasClient() const;
    int queueDepth() const { return queue_depth_; }
    bool pushNv12FrameOnContext(const uint8_t* nv12, size_t bytes);

private:
    static void onMediaConfigure(void* factory, void* media, void* user_data);
    static void onMediaUnprepared(void* media, void* user_data);

    void serverThreadMain();
    void storeAppSrc(_GstElement* appsrc);
    void clearAppSrc();

    uint16_t port_ = kDefaultRtspPort;
    std::string mount_ = "/thermal";
    int bitrate_ = 1500000;
    int width_ = kPanoWidth;
    int height_ = kPanoHeight;

    _GstElement* appsrc_ = nullptr;
    mutable std::mutex appsrc_mutex_;
    std::atomic<int> queue_depth_{0};

    _GMainLoop* loop_ = nullptr;
    void* server_ = nullptr;
    std::thread server_thread_;
    std::atomic<bool> running_{false};
    std::atomic<uint64_t> frame_num_{0};
};

} // namespace irpv
