#include "host_state.h"

#include "overlap_equalizer.h"
#include "stitch_offsets.h"
#include "thermal_meta.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>

namespace irpv {

namespace {

uint64_t steadyNowUs() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

std::string defaultOffsetPath(const std::string& configured) {
    if (!configured.empty()) {
        return configured;
    }
    const char* home = std::getenv("HOME");
    if (!home || !home[0]) {
        return "/tmp/position_shift.txt";
    }
    return std::string(home) + "/position_shift.txt";
}

std::string defaultStitchCalibPath(const std::string& configured) {
    if (!configured.empty()) {
        return configured;
    }
    const char* home = std::getenv("HOME");
    if (!home || !home[0]) {
        return "/tmp/stitch-calib";
    }
    return std::string(home) + "/stitch-calib";
}

} // namespace

HostState::HostState(HostConfig config)
    : config_(std::move(config)),
      orchestrator_(config_.demo_mode),
      compositor_(orchestrator_) {}

bool HostState::start() {
    orchestrator_.setTemporalAverage(config_.temporal_average);
    if (!orchestrator_.start()) {
        return false;
    }

    applyRenderConfig();

    const std::string calib_dir = defaultStitchCalibPath(config_.stitch_calib_dir);
    bool use_warp = false;
    if (config_.stitch_mode == "warp") {
        use_warp = compositor_.loadWarpCalib(calib_dir);
        if (!use_warp) {
            std::fprintf(stderr, "Failed to load warp calib from %s\n", calib_dir.c_str());
            orchestrator_.stop();
            return false;
        }
    } else if (config_.stitch_mode == "auto") {
        use_warp = compositor_.loadWarpCalib(calib_dir);
        if (use_warp) {
            std::fprintf(stderr, "Auto-loaded warp stitch calib from %s\n", calib_dir.c_str());
        }
    } else if (config_.stitch_mode == "offset") {
        compositor_.setStitchMode(hik::StitchMode::Offset);
    } else {
        compositor_.setStitchMode(hik::StitchMode::Edge);
    }

    if (!use_warp) {
        const std::string offset_path = defaultOffsetPath(config_.offset_file);
        hik::SlotOffset offsets[4]{};
        if (hik::OffsetStitchStrategy::loadOffsets(offset_path, offsets)) {
            compositor_.setOffsets(offsets);
            compositor_.setStitchMode(hik::StitchMode::Offset);
            std::fprintf(stderr, "Loaded stitch offsets from %s\n", offset_path.c_str());
            for (int i = 0; i < 4; ++i) {
                std::fprintf(stderr, "  slot %d dx=%d dy=%d\n", i + 1, offsets[i].dx, offsets[i].dy);
            }
            std::fprintf(stderr, "Overlap widths (px): 1-2=%d 2-3=%d 3-4=%d\n",
                hik::OverlapEqualizer::overlapWidth(offsets, 0),
                hik::OverlapEqualizer::overlapWidth(offsets, 1),
                hik::OverlapEqualizer::overlapWidth(offsets, 2));
        }
    } else {
        const char* live_serials[4]{};
        const auto statuses = orchestrator_.cameraStatuses();
        for (const auto& s : statuses) {
            if (s.slot >= 1 && s.slot <= 4) {
                live_serials[s.slot - 1] = s.serial.c_str();
            }
        }
        std::string serial_err;
        if (compositor_.warpCalib() && !compositor_.warpCalib()->matchSerials(live_serials, serial_err)) {
            std::fprintf(stderr, "WARNING: calib serial mismatch: %s\n", serial_err.c_str());
        }
    }

    const auto geom = compositor_.geometry();
    std::fprintf(stderr, "Pano output: %dx%d stitch_mode=%s\n",
        geom.width, geom.height,
        use_warp ? "warp" : (config_.stitch_mode == "offset" ? "offset" : "edge"));

    compositor_.setEqualizationEnabled(config_.equalization_enabled);
    compositor_.setEqualizationAlpha(config_.equalization_alpha);
    std::fprintf(stderr, "Overlap equalization: %s alpha=%.3f\n",
        config_.equalization_enabled ? "on" : "off",
        config_.equalization_alpha);

    if (!raw_server_.start(config_.raw_tcp_port)) {
        orchestrator_.stop();
        return false;
    }
    if (!preview_server_.start(config_.preview_tcp_port)) {
        raw_server_.stop();
        orchestrator_.stop();
        return false;
    }
    if (!meta_sender_.bind(config_.meta_port)) {
        preview_server_.stop();
        raw_server_.stop();
        orchestrator_.stop();
        return false;
    }
    meta_sender_.setDestination(config_.meta_dest, config_.meta_port);

    if (!rtsp_.start(config_.rtsp_port, "/thermal", config_.video_bitrate, geom.width, geom.height)) {
        preview_server_.stop();
        raw_server_.stop();
        orchestrator_.stop();
        return false;
    }

    std::fprintf(stderr, "Preview U8 TCP port %u (live aiming)\n",
        static_cast<unsigned>(config_.preview_tcp_port));

    compositor_.setTickCallback([this](const hik::PanoSnapshot& snap) { onCompositorTick(snap); });
    compositor_.start();
    return true;
}

void HostState::stop() {
    compositor_.stop();
    rtsp_.stop();
    preview_server_.stop();
    raw_server_.stop();
    orchestrator_.stop();
}

void HostState::applyRenderConfig() {
    ThermalRenderConfig rc{};
    rc.floor_c = config_.display_floor_c;
    rc.ceiling_c = config_.display_ceiling_c;
    rc.auto_range = config_.display_auto_range;
    renderer_.setConfig(rc);
}

void HostState::onCompositorTick(const hik::PanoSnapshot& snap) {
    if (snap.pano.empty()) {
        return;
    }

    const uint32_t video_seq = ++video_seq_;
    const uint64_t encode_submit_us = steadyNowUs();
    const int pano_w = snap.geometry.width > 0 ? snap.geometry.width : compositor_.geometry().width;
    const int pano_h = snap.geometry.height > 0 ? snap.geometry.height : compositor_.geometry().height;

    if (rtsp_.hasClient()) {
        if (renderer_.renderJetNv12(snap.pano.data(), snap.pano.size(), pano_w, pano_h, nv12_buf_)) {
            rtsp_.pushNv12Frame(nv12_buf_.data(), nv12_buf_.size(), snap.compose_us);
        }
    }

    // Raw U8 live preview (no encode). Latest tick only; single TCP client.
    if (preview_server_.hasClient()) {
        double min_c = 0.0;
        double max_c = 0.0;
        if (renderer_.renderDisplayU8(
                snap.pano.data(), snap.pano.size(), pano_w, pano_h, preview_u8_buf_, min_c, max_c)) {
            PreviewFrameHeader hdr{};
            hdr.magic = kPreviewMagic;
            hdr.width = static_cast<uint16_t>(pano_w);
            hdr.height = static_cast<uint16_t>(pano_h);
            hdr.sequence = ++preview_seq_;
            hdr.timestamp_us = snap.compose_us;
            hdr.min_c = static_cast<float>(min_c);
            hdr.max_c = static_cast<float>(max_c);
            preview_packet_.resize(sizeof(PreviewFrameHeader) + preview_u8_buf_.size());
            std::memcpy(preview_packet_.data(), &hdr, sizeof(hdr));
            std::memcpy(
                preview_packet_.data() + sizeof(hdr),
                preview_u8_buf_.data(),
                preview_u8_buf_.size());
            // Latest-only async send — never stall the compositor on TCP.
            preview_server_.queueLatestFrame(preview_packet_);
        }
    }

    LatencyMetaPacket meta{};
    meta.video_seq = video_seq;
    meta.compose_us = snap.compose_us;
    meta.encode_submit_us = encode_submit_us;
    for (int i = 0; i < 4; ++i) {
        meta.slot_frame_seq[i] = snap.slots[i].frame_seq;
        meta.slot_usb_frame_us[i] = snap.slots[i].valid ? snap.slots[i].usb_frame_us : 0;
    }
    meta_sender_.send(meta);

    const double raw_period_s = config_.raw_emit_fps > 0.0 ? (1.0 / config_.raw_emit_fps) : 10.0;
    const uint64_t raw_period_us = static_cast<uint64_t>(raw_period_s * 1e6);
    const bool raw_due = last_raw_emit_us_ == 0 || (encode_submit_us - last_raw_emit_us_) >= raw_period_us;
    if (raw_due) {
        // Always advance the emit clock — otherwise a missing client leaves raw_due
        // true forever and we rebuild 4 compressed tiles every compose tick (~80ms).
        last_raw_emit_us_ = encode_submit_us;
        if (raw_server_.hasClient()) {
            // Per-camera raw emit: send one tagged IRPV frame per camera (256x192,
            // unstitched). Stitching happens in post on the client side now.
            std::string serials[4];
            for (const auto& s : orchestrator_.cameraStatuses()) {
                if (s.slot >= 1 && s.slot <= 4) {
                    serials[s.slot - 1] = s.serial;
                }
            }
            const uint32_t group_seq = ++raw_seq_;
            hik::TileSnapshot tile;
            for (int slot = 0; slot < 4; ++slot) {
                const hik::HikCameraWorker* w = orchestrator_.worker(static_cast<size_t>(slot));
                if (w == nullptr || !w->copyLatestSnapshot(tile) || !tile.valid || tile.pixels.empty()) {
                    continue;
                }
                ThermalFrameMetaV2 frame_meta{};
                frame_meta.compose_us = snap.compose_us;
                frame_meta.emit_us = encode_submit_us;
                frame_meta.emit_seq = group_seq;
                frame_meta.slot_frame_seq[slot] = tile.frame_seq;
                frame_meta.slot_usb_frame_us[slot] = tile.usb_frame_us;
                auto packet = buildThermalCameraFrame(
                    group_seq,
                    encode_submit_us,
                    tile.pixels.data(),
                    tile.pixels.size(),
                    frame_meta,
                    static_cast<uint8_t>(slot + 1),
                    static_cast<uint8_t>(4),
                    serials[slot].c_str(),
                    static_cast<uint16_t>(hik::kTileW),
                    static_cast<uint16_t>(hik::kTileH));
                if (!packet.empty()) {
                    raw_server_.sendFrame(packet);
                }
            }
        }
    } else {
        ++raw_dropped_ticks_;
    }

    {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        stats_.compositor_hz = compositor_.tickHz();
        stats_.video_seq = video_seq;
        stats_.raw_emit_seq = raw_seq_;
        stats_.last_raw_emit_us = last_raw_emit_us_;
        stats_.last_encode_submit_us = encode_submit_us;
        stats_.raw_dropped_ticks = raw_dropped_ticks_;
        stats_.encoder_queue_bytes = rtsp_.queueDepth();
        stats_.raw_client_connected = raw_server_.hasClient();
        stats_.preview_client_connected = preview_server_.hasClient();
    }
}

LatencyStats HostState::latencyStats() const {
    std::lock_guard<std::mutex> lock(stats_mutex_);
    return stats_;
}

std::string HostState::statusJson() const {
    const auto lat = latencyStats();
    std::ostringstream oss;
    oss << "{\"cmd\":\"status\",\"cameras\":[";
    const auto statuses = orchestrator_.cameraStatuses();
    for (size_t i = 0; i < statuses.size(); ++i) {
        if (i > 0) {
            oss << ',';
        }
        const auto& s = statuses[i];
        oss << "{\"slot\":" << s.slot
            << ",\"streaming\":" << (s.streaming ? "true" : "false")
            << ",\"fps\":" << s.fps
            << ",\"bulk_reads\":" << s.bulk_reads
            << ",\"frames\":" << s.frames
            << ",\"serial\":\"" << s.serial << "\""
            << ",\"error\":" << (s.error.empty() ? "null" : ("\"" + s.error + "\""))
            << '}';
    }
    oss << "],\"compositor_hz\":" << lat.compositor_hz
        << ",\"raw_emit_fps\":" << config_.raw_emit_fps
        << ",\"rtsp_port\":" << config_.rtsp_port
        << ",\"raw_tcp_port\":" << config_.raw_tcp_port
        << ",\"preview_tcp_port\":" << config_.preview_tcp_port
        << ",\"meta_port\":" << config_.meta_port
        << ",\"raw_client\":" << (lat.raw_client_connected ? "true" : "false")
        << ",\"preview_client\":" << (lat.preview_client_connected ? "true" : "false")
        << ",\"demo\":" << (config_.demo_mode ? "true" : "false");
    const std::string offset_path = defaultOffsetPath(config_.offset_file);
    hik::SlotOffset offsets[4]{};
    if (hik::OffsetStitchStrategy::loadOffsets(offset_path, offsets)) {
        oss << ",\"offsets\":[";
        for (int i = 0; i < 4; ++i) {
            if (i > 0) {
                oss << ',';
            }
            oss << "{\"slot\":" << (i + 1) << ",\"dx\":" << offsets[i].dx << ",\"dy\":" << offsets[i].dy << '}';
        }
        oss << "],\"offset_file\":\"" << offset_path << "\"";
    }
    const auto eq = compositor_.equalizerState();
    oss << ",\"eq_enabled\":" << (config_.equalization_enabled ? "true" : "false")
        << ",\"eq_alpha\":" << config_.equalization_alpha
        << ",\"eq_bias_u16\":[";
    for (int i = 0; i < 4; ++i) {
        if (i > 0) {
            oss << ',';
        }
        oss << eq.ema_bias_u16[i];
    }
    oss << "],\"eq_seam_delta\":[";
    for (int i = 0; i < 3; ++i) {
        if (i > 0) {
            oss << ',';
        }
        oss << eq.last_seam_delta[i];
    }
    oss << "],\"eq_sample_counts\":[";
    for (int i = 0; i < 3; ++i) {
        if (i > 0) {
            oss << ',';
        }
        oss << eq.last_sample_counts[i];
    }
    oss << ']';
    const auto geom = compositor_.geometry();
    oss << ",\"pano_width\":" << geom.width
        << ",\"pano_height\":" << geom.height;
    if (compositor_.warpCalib()) {
        oss << ",\"calib_hash\":\"" << compositor_.warpCalib()->calibHash() << "\""
            << ",\"stitch_mode\":\"warp\"";
    } else {
        oss << ",\"stitch_mode\":\"offset\"";
    }
    oss << '}';
    return oss.str();
}

std::string HostState::latencyStatsJson() const {
    const auto lat = latencyStats();
    std::ostringstream oss;
    oss << "{\"cmd\":\"latency_stats\""
        << ",\"compositor_hz\":" << lat.compositor_hz
        << ",\"video_seq\":" << lat.video_seq
        << ",\"raw_emit_seq\":" << lat.raw_emit_seq
        << ",\"last_raw_emit_us\":" << lat.last_raw_emit_us
        << ",\"last_encode_submit_us\":" << lat.last_encode_submit_us
        << ",\"raw_dropped_ticks\":" << lat.raw_dropped_ticks
        << ",\"encoder_queue_bytes\":" << lat.encoder_queue_bytes
        << ",\"raw_client\":" << (lat.raw_client_connected ? "true" : "false")
        << ",\"raw_emit_fps\":" << config_.raw_emit_fps << '}';
    return oss.str();
}

std::string HostState::handleCommand(const std::string& json_line) {
    std::lock_guard<std::mutex> lock(cmd_mutex_);
    if (json_line.find("\"get_status\"") != std::string::npos || json_line.find("\"cmd\":\"get_status\"") != std::string::npos) {
        return statusJson();
    }
    if (json_line.find("\"get_latency_stats\"") != std::string::npos) {
        return latencyStatsJson();
    }
    if (json_line.find("\"set_ir_config\"") != std::string::npos) {
        auto readDouble = [&](const char* key, double fallback) {
            const std::string needle = std::string("\"") + key + "\":";
            const size_t pos = json_line.find(needle);
            if (pos == std::string::npos) {
                return fallback;
            }
            return std::stod(json_line.substr(pos + needle.size()));
        };
        config_.ir_emissivity = readDouble("emissivity", config_.ir_emissivity);
        config_.ir_distance_m = readDouble("distance_m", config_.ir_distance_m);
        config_.ir_ambient_c = readDouble("ambient_c", config_.ir_ambient_c);
        orchestrator_.setIrConfigAll(config_.ir_emissivity, config_.ir_distance_m, config_.ir_ambient_c);
        return "{\"cmd\":\"ack\",\"ok\":true}";
    }
    if (json_line.find("\"trigger_nuc\"") != std::string::npos) {
        const bool ok = orchestrator_.triggerNucAll();
        return std::string("{\"cmd\":\"ack\",\"ok\":") + (ok ? "true" : "false") + "}";
    }
    if (json_line.find("\"set_temporal_average\"") != std::string::npos) {
        const size_t pos = json_line.find("\"frames\":");
        if (pos != std::string::npos) {
            config_.temporal_average = std::stoi(json_line.substr(pos + 9));
            orchestrator_.setTemporalAverage(config_.temporal_average);
        }
        return "{\"cmd\":\"ack\",\"ok\":true}";
    }
    if (json_line.find("\"set_raw_emit_fps\"") != std::string::npos) {
        const size_t pos = json_line.find("\"fps\":");
        if (pos != std::string::npos) {
            double fps = std::stod(json_line.substr(pos + 6));
            fps = std::max(0.1, std::min(1.0, fps));
            config_.raw_emit_fps = fps;
        }
        return "{\"cmd\":\"ack\",\"ok\":true,\"raw_emit_fps\":" + std::to_string(config_.raw_emit_fps) + "}";
    }
    if (json_line.find("\"set_display_range\"") != std::string::npos) {
        auto readDouble = [&](const char* key, double fallback) {
            const std::string needle = std::string("\"") + key + "\":";
            const size_t pos = json_line.find(needle);
            if (pos == std::string::npos) {
                return fallback;
            }
            return std::stod(json_line.substr(pos + needle.size()));
        };
        config_.display_floor_c = readDouble("floor_c", config_.display_floor_c);
        config_.display_ceiling_c = readDouble("ceiling_c", config_.display_ceiling_c);
        if (json_line.find("\"auto\":true") != std::string::npos) {
            config_.display_auto_range = true;
        } else if (json_line.find("\"auto\":false") != std::string::npos) {
            config_.display_auto_range = false;
        }
        applyRenderConfig();
        return "{\"cmd\":\"ack\",\"ok\":true}";
    }
    if (json_line.find("\"set_equalization\"") != std::string::npos) {
        if (json_line.find("\"enabled\":false") != std::string::npos) {
            config_.equalization_enabled = false;
        } else if (json_line.find("\"enabled\":true") != std::string::npos) {
            config_.equalization_enabled = true;
        }
        const size_t alpha_pos = json_line.find("\"alpha\":");
        if (alpha_pos != std::string::npos) {
            double alpha = std::stod(json_line.substr(alpha_pos + 8));
            config_.equalization_alpha = std::max(0.001, std::min(1.0, alpha));
        }
        compositor_.setEqualizationEnabled(config_.equalization_enabled);
        compositor_.setEqualizationAlpha(config_.equalization_alpha);
        return "{\"cmd\":\"ack\",\"ok\":true,\"eq_enabled\":" +
            std::string(config_.equalization_enabled ? "true" : "false") +
            ",\"eq_alpha\":" + std::to_string(config_.equalization_alpha) + "}";
    }
    return "{\"cmd\":\"error\",\"message\":\"unknown command\"}";
}

} // namespace irpv
