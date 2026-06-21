#include "hik_stitch.h"
#include "overlap_equalizer.h"
#include "rtsp_publisher.h"
#include "stitch_offsets.h"
#include "thermal_renderer.h"
#include "tile_snapshot.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>
#include <termios.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

std::atomic<bool> g_running{true};

void onSignal(int) {
    g_running = false;
}

std::string defaultOffsetPath() {
    const char* home = std::getenv("HOME");
    if (!home || !home[0]) {
        return "/tmp/position_shift.txt";
    }
    return std::string(home) + "/position_shift.txt";
}

struct TerminalRaw {
    termios old{};
    bool active = false;

    bool enable() {
        if (tcgetattr(STDIN_FILENO, &old) != 0) {
            return false;
        }
        termios raw = old;
        raw.c_lflag &= static_cast<tcflag_t>(~(ICANON | ECHO));
        raw.c_cc[VMIN] = 0;
        raw.c_cc[VTIME] = 1;
        if (tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw) != 0) {
            return false;
        }
        active = true;
        return true;
    }

    void restore() {
        if (active) {
            tcsetattr(STDIN_FILENO, TCSAFLUSH, &old);
            active = false;
        }
    }

    ~TerminalRaw() {
        restore();
    }
};

int readKey() {
    char c = 0;
    if (read(STDIN_FILENO, &c, 1) != 1) {
        return -1;
    }
    if (c != '\x1b') {
        return static_cast<unsigned char>(c);
    }
    char seq[2]{};
    if (read(STDIN_FILENO, &seq[0], 1) != 1) {
        return static_cast<unsigned char>(c);
    }
    if (seq[0] != '[') {
        return static_cast<unsigned char>(c);
    }
    if (read(STDIN_FILENO, &seq[1], 1) != 1) {
        return static_cast<unsigned char>(c);
    }
    switch (seq[1]) {
    case 'A':
        return 1000;
    case 'B':
        return 1001;
    case 'C':
        return 1002;
    case 'D':
        return 1003;
    default:
        return static_cast<unsigned char>(c);
    }
}

void printHelp() {
    std::fprintf(stderr,
        "irpanoview-align: RTSP thermal align tool\n"
        "  1-4     select camera slot\n"
        "  arrows  shift selected camera (1 px)\n"
        "  s       save offsets to ~/position_shift.txt\n"
        "  l       reload offsets from file\n"
        "  r       reset selected slot to 0,0\n"
        "  q       quit\n"
        "  View: rtsp://<pi>:8554/thermal :rtsp-tcp :network-caching=0\n\n");
}

void printState(int selected, const irpv::hik::SlotOffset offsets[4], const irpv::hik::HikHostOrchestrator& orch) {
    std::fprintf(stderr, "\033[K");
    for (int i = 0; i < 4; ++i) {
        const auto* w = orch.worker(static_cast<size_t>(i));
        const char* serial = (w && !w->status().serial.empty()) ? w->status().serial.c_str() : "?";
        std::fprintf(stderr, "  slot %d %s dx=%d dy=%d%s\n", i + 1, serial, offsets[i].dx, offsets[i].dy,
            (i == selected) ? "  <--" : "");
    }
    std::fprintf(stderr, "selected=slot %d  (q=quit s=save)\n", selected + 1);
}

} // namespace

int main(int argc, char** argv) {
    std::string save_path = defaultOffsetPath();
    uint16_t rtsp_port = irpv::kDefaultRtspPort;

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--save") == 0 && i + 1 < argc) {
            save_path = argv[++i];
        } else if (std::strcmp(argv[i], "--rtsp-port") == 0 && i + 1 < argc) {
            rtsp_port = static_cast<uint16_t>(std::stoi(argv[++i]));
        } else if (std::strcmp(argv[i], "--help") == 0) {
            std::fprintf(stderr, "Usage: %s [--save PATH] [--rtsp-port N]\n", argv[0]);
            return 0;
        }
    }

    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);

    printHelp();

    irpv::hik::HikHostOrchestrator orchestrator(false);
    if (!orchestrator.start()) {
        std::cerr << "Failed to start cameras (need 4 USB Hik cameras; stop irpanoview-host first)\n";
        return 1;
    }

    irpv::hik::SlotOffset offsets[4]{};
    if (irpv::hik::OffsetStitchStrategy::loadOffsets(save_path, offsets)) {
        std::fprintf(stderr, "Loaded offsets from %s\n", save_path.c_str());
    }

    irpv::ThermalRenderer renderer;
    irpv::RtspPublisher rtsp;
    if (!rtsp.start(rtsp_port, "/thermal", 1500000)) {
        std::cerr << "Failed to start RTSP on port " << rtsp_port << '\n';
        orchestrator.stop();
        return 1;
    }

    irpv::hik::OffsetStitchStrategy stitch;
    irpv::hik::OverlapEqualizer equalizer;
    irpv::hik::EqualizerConfig eq_cfg;
    eq_cfg.enabled = true;
    eq_cfg.alpha = 0.05;
    equalizer.setConfig(eq_cfg);
    std::vector<uint16_t> pano(irpv::hik::OffsetStitchStrategy::kOutW * irpv::hik::OffsetStitchStrategy::kOutH);
    std::vector<uint8_t> nv12;
    irpv::hik::SlotOffset stream_offsets[4]{};

    std::thread stream_thread([&]() {
        const auto interval = std::chrono::milliseconds(1000 / irpv::hik::kStreamFps);
        auto next_tick = std::chrono::steady_clock::now();
        while (g_running) {
            uint16_t tiles[4][irpv::hik::OffsetStitchStrategy::kTileW * irpv::hik::OffsetStitchStrategy::kTileH]{};
            bool any = false;
            for (int i = 0; i < 4; ++i) {
                const auto* w = orchestrator.worker(static_cast<size_t>(i));
                irpv::hik::TileSnapshot tile;
                if (w && w->copyLatestSnapshot(tile) && tile.valid) {
                    std::copy(tile.pixels.begin(), tile.pixels.end(), tiles[i]);
                    any = true;
                }
            }
            if (any) {
                for (int i = 0; i < 4; ++i) {
                    stream_offsets[i] = offsets[i];
                }
                equalizer.process(tiles, stream_offsets);
                stitch.stitch(tiles, stream_offsets, pano.data(), pano.size());
                if (rtsp.hasClient() && renderer.renderJetNv12(
                        pano.data(),
                        pano.size(),
                        irpv::hik::OffsetStitchStrategy::kOutW,
                        irpv::hik::OffsetStitchStrategy::kOutH,
                        nv12)) {
                    rtsp.pushNv12Frame(nv12.data(), nv12.size(), 0);
                }
            }
            next_tick += interval;
            std::this_thread::sleep_until(next_tick);
        }
    });

    TerminalRaw term;
    if (!term.enable()) {
        std::cerr << "Warning: stdin is not a TTY; run on Pi console or ssh -t\n";
    }

    int selected = 0;
    printState(selected, offsets, orchestrator);

    while (g_running) {
        const int key = readKey();
        if (key < 0) {
            continue;
        }
        if (key == 'q' || key == 'Q') {
            break;
        }
        if (key >= '1' && key <= '4') {
            selected = key - '1';
            printState(selected, offsets, orchestrator);
            continue;
        }
        if (key == 's' || key == 'S') {
            const char* serials[4]{};
            std::string serial_storage[4];
            for (int i = 0; i < 4; ++i) {
                const auto* w = orchestrator.worker(static_cast<size_t>(i));
                if (w) {
                    serial_storage[i] = w->status().serial;
                    serials[i] = serial_storage[i].c_str();
                }
            }
            if (irpv::hik::OffsetStitchStrategy::saveOffsets(save_path, offsets, serials)) {
                std::fprintf(stderr, "\nSaved offsets to %s\n", save_path.c_str());
            } else {
                std::fprintf(stderr, "\nFailed to save %s\n", save_path.c_str());
            }
            printState(selected, offsets, orchestrator);
            continue;
        }
        if (key == 'l' || key == 'L') {
            if (irpv::hik::OffsetStitchStrategy::loadOffsets(save_path, offsets)) {
                std::fprintf(stderr, "\nReloaded %s\n", save_path.c_str());
            } else {
                std::fprintf(stderr, "\nNo offsets loaded from %s\n", save_path.c_str());
            }
            printState(selected, offsets, orchestrator);
            continue;
        }
        if (key == 'r' || key == 'R') {
            offsets[selected] = {};
            printState(selected, offsets, orchestrator);
            continue;
        }
        if (key == 1000) {
            --offsets[selected].dy;
            printState(selected, offsets, orchestrator);
        } else if (key == 1001) {
            ++offsets[selected].dy;
            printState(selected, offsets, orchestrator);
        } else if (key == 1002) {
            ++offsets[selected].dx;
            printState(selected, offsets, orchestrator);
        } else if (key == 1003) {
            --offsets[selected].dx;
            printState(selected, offsets, orchestrator);
        }
    }

    g_running = false;
    if (stream_thread.joinable()) {
        stream_thread.join();
    }
    rtsp.stop();
    orchestrator.stop();
    std::fprintf(stderr, "\nStopped.\n");
    return 0;
}
