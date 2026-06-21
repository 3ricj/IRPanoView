#include "hik_bulk_reassembler.h"
#include "hik_constants.h"
#include "hik_usb_device.h"
#include "hik_uvc_protocol.h"
#include "hik_wire_layout.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;

namespace {

constexpr double kKelvinOffsetC = 273.15;
constexpr int kTempBiasU16 = 0x37C0;
constexpr int kTempScale = 64;

double celsiusFromRaw(uint16_t raw) {
    const int stored = (static_cast<int>(raw) + kTempBiasU16) & 0xFFFF;
    return static_cast<double>(stored) / kTempScale - kKelvinOffsetC;
}

struct TileStats {
    uint16_t min_raw = 0xFFFF;
    uint16_t max_raw = 0;
    uint16_t center_raw = 0;
    double min_c = 0;
    double max_c = 0;
    double center_c = 0;
    bool any_nonzero = false;
};

TileStats computeTileStats(const uint16_t* tile, size_t count) {
    TileStats s;
    for (size_t i = 0; i < count; ++i) {
        const uint16_t v = tile[i];
        if (v != 0) {
            s.any_nonzero = true;
        }
        s.min_raw = std::min(s.min_raw, v);
        s.max_raw = std::max(s.max_raw, v);
    }
    if (count > 0) {
        const size_t center = count / 2;
        s.center_raw = tile[center];
    }
    s.min_c = celsiusFromRaw(s.min_raw);
    s.max_c = celsiusFromRaw(s.max_raw);
    s.center_c = celsiusFromRaw(s.center_raw);
    return s;
}

std::string sanitizeSerial(const std::string& serial) {
    std::string out;
    out.reserve(serial.size());
    for (char c : serial) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            out.push_back(c);
        } else if (c == '-' || c == '_') {
            out.push_back(c);
        }
    }
    if (out.empty()) {
        out = "unknown";
    }
    return out;
}

bool writeBinary(const fs::path& path, const void* data, size_t len) {
    std::ofstream out(path, std::ios::binary);
    if (!out) {
        return false;
    }
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(len));
    return out.good();
}

struct BulkDiag {
    int bad_header = 0;
    int eof_count = 0;
    int fid_toggle = 0;
    int max_flight = 0;
    int max_avail = 0;
    std::vector<std::string> first_packets;
};

void recordPacketDiag(BulkDiag& diag, const uint8_t* data, size_t len) {
    if (diag.first_packets.size() < 5) {
        char line[128];
        int hdr = len >= 1 ? (data[0] & 0xFF) : -1;
        int bm = len >= 2 ? (data[1] & 0xFF) : -1;
        std::snprintf(line, sizeof(line), "len=%zu hdr=%d bm=%02x", len, hdr, bm);
        diag.first_packets.emplace_back(line);
    }
    if (len < 2) {
        ++diag.bad_header;
        return;
    }
    const int header_len = data[0] & 0xFF;
    if (header_len < 2 || static_cast<size_t>(header_len) >= len) {
        ++diag.bad_header;
        return;
    }
    const int bm = data[1] & 0xFF;
    if ((bm & 0x02) != 0) {
        ++diag.eof_count;
    }
}

void writeJson(const fs::path& path, const irpv::hik::UsbDeviceInfo& info, const TileStats& stats,
               size_t wire_bytes, int bulk_packets, int frames, const std::string& error,
               const BulkDiag* diag = nullptr) {
    std::ofstream out(path);
    out << "{\n"
        << "  \"slot\": " << info.slot << ",\n"
        << "  \"serial\": \"" << info.serial << "\",\n"
        << "  \"bus\": \"" << info.bus_path << "\",\n"
        << "  \"bulk_packets\": " << bulk_packets << ",\n"
        << "  \"frames\": " << frames << ",\n"
        << "  \"wire_bytes\": " << wire_bytes << ",\n"
        << "  \"error\": " << (error.empty() ? "null" : ("\"" + error + "\"")) << ",\n";
    if (diag) {
        out << "  \"diag\": {\n"
            << "    \"bad_header\": " << diag->bad_header << ",\n"
            << "    \"eof_count\": " << diag->eof_count << ",\n"
            << "    \"first_packets\": [";
        for (size_t i = 0; i < diag->first_packets.size(); ++i) {
            if (i > 0) {
                out << ", ";
            }
            out << "\"" << diag->first_packets[i] << "\"";
        }
        out << "]\n  },\n";
    }
    out << "  \"tile\": {\n"
        << "    \"raw_min\": " << stats.min_raw << ",\n"
        << "    \"raw_max\": " << stats.max_raw << ",\n"
        << "    \"raw_center\": " << stats.center_raw << ",\n"
        << "    \"any_nonzero\": " << (stats.any_nonzero ? "true" : "false") << ",\n"
        << "    \"celsius_min\": " << stats.min_c << ",\n"
        << "    \"celsius_max\": " << stats.max_c << ",\n"
        << "    \"celsius_center\": " << stats.center_c << "\n"
        << "  }\n"
        << "}\n";
}

struct CaptureResult {
    bool ok = false;
    std::string error;
    int bulk_packets = 0;
    int frames = 0;
    std::vector<uint8_t> wire;
    std::vector<uint16_t> tile;
};

CaptureResult captureOneCamera(const irpv::hik::UsbDeviceInfo& info, int wait_ms, BulkDiag* diag_out = nullptr) {
    CaptureResult result;
    irpv::hik::HikUsbDevice usb;
    if (!usb.open(info)) {
        result.error = "open failed";
        return result;
    }

    irpv::hik::HikUvcProtocol protocol(usb);
    if (!protocol.login(true)) {
        result.error = "login failed";
        usb.close();
        return result;
    }
    if (!protocol.waitStreamReady()) {
        result.error = "wait ready failed";
        usb.close();
        return result;
    }
    if (!protocol.initConfig()) {
        result.error = "initConfig failed";
        usb.close();
        return result;
    }
    if (!protocol.setVideoParam()) {
        result.error = "video param failed";
        usb.close();
        return result;
    }
    if (!protocol.armStream()) {
        result.error = "arm stream failed";
        usb.close();
        return result;
    }

    irpv::hik::HikBulkReassembler reassembler;
    reassembler.beginStream();

    BulkDiag diag;

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(wait_ms);
    while (std::chrono::steady_clock::now() < deadline) {
        auto packet = usb.bulkRead(usb.bulkEndpoint(), 16384, irpv::hik::kBulkTimeoutMs);
        if (!packet) {
            continue;
        }
        ++result.bulk_packets;
        recordPacketDiag(diag, packet->data(), packet->size());
        auto frame = reassembler.push(packet->data(), packet->size());
        if (!frame) {
            continue;
        }
        ++result.frames;
        result.wire = std::move(*frame);
        break;
    }

    usb.selectAlt0();
    protocol.disarmStream();
    usb.close();

    if (result.wire.empty()) {
        if (result.error.empty()) {
            result.error = "no frame in window";
        }
        if (diag_out) {
            *diag_out = std::move(diag);
        }
        return result;
    }

    result.tile.resize(static_cast<size_t>(irpv::hik::kGridWidth) * irpv::hik::kGridHeight);
    irpv::hik::HikWireLayout::extractRadioGrid(
        result.wire.data(), result.wire.size(), result.tile.data(), result.tile.size());
    result.ok = true;
    result.error.clear();
    return result;
}

void printUsage(const char* argv0) {
    std::fprintf(stderr,
                 "Usage: %s [--out DIR] [--wait SECONDS] [--slot N]\n"
                 "  Connect each Hik camera, wait, save one frame per camera, exit.\n",
                 argv0);
}

} // namespace

int main(int argc, char** argv) {
    fs::path out_dir = fs::path(std::getenv("HOME") ? std::getenv("HOME") : ".") / "irpv-capture";
    int wait_ms = 5000;
    int only_slot = 0;

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out_dir = argv[++i];
        } else if (std::strcmp(argv[i], "--wait") == 0 && i + 1 < argc) {
            wait_ms = static_cast<int>(std::stod(argv[++i]) * 1000.0);
        } else if (std::strcmp(argv[i], "--slot") == 0 && i + 1 < argc) {
            only_slot = std::stoi(argv[++i]);
        } else if (std::strcmp(argv[i], "--help") == 0) {
            printUsage(argv[0]);
            return 0;
        } else {
            printUsage(argv[0]);
            return 1;
        }
    }

    std::error_code ec;
    fs::create_directories(out_dir, ec);
    if (ec) {
        std::fprintf(stderr, "Failed to create output dir: %s\n", out_dir.string().c_str());
        return 1;
    }

    const auto devices = irpv::hik::enumerateHikDevices();
    std::fprintf(stderr, "Found %zu Hik camera(s)\n", devices.size());
    for (const auto& dev : devices) {
        std::fprintf(stderr, "  slot=%d serial=%s bus=%s\n", dev.slot, dev.serial.c_str(), dev.bus_path.c_str());
    }
    if (devices.empty()) {
        return 1;
    }

    int saved = 0;
    int failed = 0;

    for (const auto& dev : devices) {
        if (only_slot > 0 && dev.slot != only_slot) {
            continue;
        }
        if (dev.serial.empty()) {
            std::fprintf(stderr, "SKIP slot %d: empty serial at %s\n", dev.slot, dev.bus_path.c_str());
            continue;
        }
        std::fprintf(stderr, "\n=== slot %d serial %s ===\n", dev.slot, dev.serial.c_str());
        BulkDiag diag;
        const auto result = captureOneCamera(dev, wait_ms, &diag);

        const std::string tag = "slot" + std::to_string(dev.slot) + "-" + sanitizeSerial(dev.serial);
        const TileStats stats = result.tile.empty()
            ? TileStats{}
            : computeTileStats(result.tile.data(), result.tile.size());

        if (result.ok) {
            const fs::path wire_path = out_dir / (tag + "-wire.bin");
            const fs::path tile_path = out_dir / (tag + "-tile-u16.bin");
            const fs::path json_path = out_dir / (tag + ".json");

            writeBinary(wire_path, result.wire.data(), result.wire.size());
            writeBinary(tile_path, result.tile.data(), result.tile.size() * sizeof(uint16_t));
            writeJson(json_path, dev, stats, result.wire.size(), result.bulk_packets, result.frames, "", nullptr);

            std::fprintf(stderr,
                         "OK: %s wire=%zu bulk=%d frames=%d raw=%u..%u (%.1f..%.1f C)\n",
                         tag.c_str(),
                         result.wire.size(),
                         result.bulk_packets,
                         result.frames,
                         stats.min_raw,
                         stats.max_raw,
                         stats.min_c,
                         stats.max_c);
            ++saved;
        } else {
            const fs::path json_path = out_dir / (tag + ".json");
            writeJson(json_path, dev, stats, 0, result.bulk_packets, result.frames, result.error, &diag);
            std::fprintf(stderr, "FAIL slot %d: %s (bulk_packets=%d bad_hdr=%d eof=%d)\n",
                         dev.slot, result.error.c_str(), result.bulk_packets, diag.bad_header, diag.eof_count);
            ++failed;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1500));
    }

    std::fprintf(stderr, "\nDone: %d saved, %d failed -> %s\n", saved, failed, out_dir.string().c_str());
    return failed > 0 ? 1 : 0;
}
