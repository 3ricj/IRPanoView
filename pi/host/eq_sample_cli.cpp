#include "hik_stitch.h"
#include "overlap_equalizer.h"
#include "stitch_offsets.h"
#include "thermal_renderer.h"
#include "tile_snapshot.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "third_party/stb_image_write.h"

namespace fs = std::filesystem;

namespace {

std::string defaultOffsetPath() {
    const char* home = std::getenv("HOME");
    if (!home || !home[0]) {
        return "/tmp/position_shift.txt";
    }
    return std::string(home) + "/position_shift.txt";
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

bool writeJpeg(const fs::path& path, int width, int height, const std::vector<uint8_t>& rgb) {
    if (rgb.size() < static_cast<size_t>(width) * static_cast<size_t>(height) * 3) {
        return false;
    }
    return stbi_write_jpg(path.string().c_str(), width, height, 3, rgb.data(), 92) != 0;
}

void printUsage(const char* argv0) {
    std::fprintf(stderr,
        "Usage: %s [--out DIR] [--wait SECONDS] [--eq-alpha N] [--offsets PATH]\n"
        "  Run all cameras with overlap equalization, save Jet JPG per serial after wait.\n",
        argv0);
}

} // namespace

int main(int argc, char** argv) {
    fs::path out_dir = fs::path(std::getenv("HOME") ? std::getenv("HOME") : ".") / "eq-samples";
    int wait_ms = 5000;
    double eq_alpha = 0.05;
    std::string offset_path = defaultOffsetPath();

    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--out") == 0 && i + 1 < argc) {
            out_dir = argv[++i];
        } else if (std::strcmp(argv[i], "--wait") == 0 && i + 1 < argc) {
            wait_ms = static_cast<int>(std::stod(argv[++i]) * 1000.0);
        } else if (std::strcmp(argv[i], "--eq-alpha") == 0 && i + 1 < argc) {
            eq_alpha = std::stod(argv[++i]);
        } else if (std::strcmp(argv[i], "--offsets") == 0 && i + 1 < argc) {
            offset_path = argv[++i];
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

    irpv::hik::HikHostOrchestrator orchestrator(false);
    if (!orchestrator.start()) {
        std::fprintf(stderr, "Failed to start cameras (stop irpanoview-host first)\n");
        return 1;
    }

    irpv::hik::SlotOffset offsets[4]{};
    if (irpv::hik::OffsetStitchStrategy::loadOffsets(offset_path, offsets)) {
        std::fprintf(stderr, "Loaded offsets from %s\n", offset_path.c_str());
    } else {
        std::fprintf(stderr, "No offsets loaded from %s (using 0,0)\n", offset_path.c_str());
    }

    irpv::hik::OverlapEqualizer equalizer;
    irpv::hik::EqualizerConfig eq_cfg;
    eq_cfg.enabled = true;
    eq_cfg.alpha = eq_alpha;
    equalizer.setConfig(eq_cfg);

    irpv::ThermalRenderer renderer;
    irpv::ThermalRenderConfig render_cfg;
    render_cfg.auto_range = true;
    renderer.setConfig(render_cfg);

    constexpr int kTileW = irpv::hik::kTileW;
    constexpr int kTileH = irpv::hik::kTileH;
    const size_t tile_pixels = static_cast<size_t>(kTileW) * static_cast<size_t>(kTileH);

    std::fprintf(stderr, "Running equalization for %.1f s (alpha=%.3f)...\n",
        wait_ms / 1000.0, eq_alpha);

    uint16_t final_tiles[4][kTileW * kTileH]{};
    bool have_final = false;
    int frames = 0;
    std::string serials[4];

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(wait_ms);
    const auto interval = std::chrono::milliseconds(1000 / irpv::hik::kStreamFps);
    auto next_tick = std::chrono::steady_clock::now();

    while (std::chrono::steady_clock::now() < deadline) {
        uint16_t tiles[4][kTileW * kTileH]{};
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
            equalizer.process(tiles, offsets);
            for (int i = 0; i < 4; ++i) {
                std::copy(tiles[i], tiles[i] + tile_pixels, final_tiles[i]);
                const auto* w = orchestrator.worker(static_cast<size_t>(i));
                if (w && !w->status().serial.empty()) {
                    serials[i] = w->status().serial;
                }
            }
            have_final = true;
            ++frames;
        }
        next_tick += interval;
        std::this_thread::sleep_until(next_tick);
    }

    orchestrator.stop();

    if (!have_final) {
        std::fprintf(stderr, "No frames captured during wait window\n");
        return 1;
    }

    const auto eq_state = equalizer.state();
    std::fprintf(stderr, "Processed %d frames; eq_bias_u16=[%d,%d,%d,%d]\n",
        frames,
        eq_state.ema_bias_u16[0],
        eq_state.ema_bias_u16[1],
        eq_state.ema_bias_u16[2],
        eq_state.ema_bias_u16[3]);

    std::vector<uint16_t> all_pixels;
    all_pixels.reserve(tile_pixels * 4);
    for (int i = 0; i < 4; ++i) {
        all_pixels.insert(all_pixels.end(), final_tiles[i], final_tiles[i] + tile_pixels);
    }

    double window_min = 20.0;
    double window_max = 40.0;
    renderer.resolveWindowForPixels(all_pixels.data(), all_pixels.size(), window_min, window_max);
    std::fprintf(stderr, "Shared Jet window: %.2f..%.2f C\n", window_min, window_max);

    int saved = 0;
    for (int i = 0; i < 4; ++i) {
        const std::string serial = serials[i].empty() ? ("slot" + std::to_string(i + 1)) : serials[i];
        const std::string tag = sanitizeSerial(serial);

        std::vector<uint8_t> rgb;
        if (!renderer.renderJetRgb(final_tiles[i], kTileW, kTileH, window_min, window_max, rgb)) {
            std::fprintf(stderr, "SKIP slot %d: render failed\n", i + 1);
            continue;
        }

        const fs::path jpg_path = out_dir / (tag + ".jpg");
        if (!writeJpeg(jpg_path, kTileW, kTileH, rgb)) {
            std::fprintf(stderr, "FAIL slot %d serial %s: write %s\n", i + 1, serial.c_str(), jpg_path.string().c_str());
            continue;
        }
        std::fprintf(stderr, "Saved %s (slot %d, bias=%d)\n", jpg_path.string().c_str(), i + 1, eq_state.ema_bias_u16[i]);
        ++saved;
    }

    const fs::path meta_path = out_dir / "eq-meta.json";
    {
        std::ofstream meta(meta_path);
        meta << "{\n"
             << "  \"frames\": " << frames << ",\n"
             << "  \"wait_s\": " << (wait_ms / 1000.0) << ",\n"
             << "  \"eq_alpha\": " << eq_alpha << ",\n"
             << "  \"window_min_c\": " << window_min << ",\n"
             << "  \"window_max_c\": " << window_max << ",\n"
             << "  \"eq_bias_u16\": ["
             << eq_state.ema_bias_u16[0] << ", "
             << eq_state.ema_bias_u16[1] << ", "
             << eq_state.ema_bias_u16[2] << ", "
             << eq_state.ema_bias_u16[3] << "],\n"
             << "  \"seam_delta\": ["
             << eq_state.ema_seam_delta[0] << ", "
             << eq_state.ema_seam_delta[1] << ", "
             << eq_state.ema_seam_delta[2] << "]\n"
             << "}\n";
    }

    std::fprintf(stderr, "\nDone: %d JPG(s) -> %s\n", saved, out_dir.string().c_str());
    return saved > 0 ? 0 : 1;
}
