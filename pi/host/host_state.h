#pragma once

#include "hik_stitch.h"
#include "meta_stream.h"
#include "pano_compositor.h"
#include "raw_stream_server.h"
#include "rtsp_publisher.h"
#include "thermal_frame.h"
#include "thermal_renderer.h"

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

namespace irpv {

struct HostConfig {
    bool demo_mode = false;
    std::string meta_dest = "255.255.255.255";
    uint16_t meta_port = kDefaultMetaUdpPort;
    uint16_t raw_tcp_port = kDefaultRawTcpPort;
    uint16_t preview_tcp_port = kDefaultPreviewTcpPort;
    uint16_t rtsp_port = kDefaultRtspPort;
    uint16_t control_port = kDefaultWsPort;
    std::string control_socket = "/run/irpanoview/control.sock";
    int temporal_average = 1;
    double ir_emissivity = 0.95;
    double ir_distance_m = 1.0;
    double ir_ambient_c = 22.0;
    double raw_emit_fps = 1.0;
    int video_bitrate = 1500000;
    double display_floor_c = 20.0;
    double display_ceiling_c = 40.0;
    bool display_auto_range = true;
    std::string offset_file; // empty = $HOME/position_shift.txt
    std::string stitch_calib_dir; // empty = try $HOME/stitch-calib
    std::string stitch_mode = "auto"; // auto | offset | warp | edge
    bool equalization_enabled = true;
    double equalization_alpha = 0.05;
};

struct LatencyStats {
    double compositor_hz = 0.0;
    uint32_t video_seq = 0;
    uint32_t raw_emit_seq = 0;
    uint64_t last_raw_emit_us = 0;
    uint64_t last_encode_submit_us = 0;
    uint32_t raw_dropped_ticks = 0;
    int encoder_queue_bytes = 0;
    bool raw_client_connected = false;
    bool preview_client_connected = false;
};

class HostState {
public:
    explicit HostState(HostConfig config);

    bool start();
    void stop();

    hik::HikHostOrchestrator& orchestrator() { return orchestrator_; }
    const HostConfig& config() const { return config_; }
    LatencyStats latencyStats() const;

    std::string statusJson() const;
    std::string latencyStatsJson() const;
    std::string handleCommand(const std::string& json_line);

private:
    void onCompositorTick(const hik::PanoSnapshot& snap);
    void applyRenderConfig();

    HostConfig config_;
    hik::HikHostOrchestrator orchestrator_;
    hik::PanoCompositor compositor_;
    RawStreamServer raw_server_;
    RawStreamServer preview_server_;
    MetaStreamSender meta_sender_;
    ThermalRenderer renderer_;
    RtspPublisher rtsp_;

    mutable std::mutex cmd_mutex_;
    mutable std::mutex stats_mutex_;
    LatencyStats stats_;

    std::vector<uint8_t> nv12_buf_;
    std::vector<uint8_t> preview_u8_buf_;
    std::vector<uint8_t> preview_packet_;
    uint32_t video_seq_ = 0;
    uint32_t raw_seq_ = 0;
    uint32_t preview_seq_ = 0;
    uint64_t last_raw_emit_us_ = 0;
    uint32_t raw_dropped_ticks_ = 0;
};

} // namespace irpv
