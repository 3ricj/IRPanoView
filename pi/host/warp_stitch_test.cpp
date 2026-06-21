#include "ptgui_calib.h"
#include "warp_stitch.h"

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <vector>

namespace fs = std::filesystem;

namespace {

int failures = 0;

void check(bool ok, const char* msg) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", msg);
        ++failures;
    }
}

void testWarpFromCalib(const char* calib_dir) {
    irpv::hik::PtguiCalib calib;
    check(calib.loadFromDir(calib_dir), "load calib dir");

    const int w = calib.geometry().width;
    const int h = calib.geometry().height;
    check(w > 0 && h > 0, "geometry");

    uint16_t tiles[4][irpv::hik::kTileW * irpv::hik::kTileH]{};
    for (int cam = 0; cam < 4; ++cam) {
        for (int y = 0; y < irpv::hik::kTileH; ++y) {
            for (int x = 0; x < irpv::hik::kTileW; ++x) {
                const size_t idx = static_cast<size_t>(y) * irpv::hik::kTileW + static_cast<size_t>(x);
                tiles[cam][idx] = static_cast<uint16_t>(1000 + cam * 100 + x + y);
            }
        }
    }

    std::vector<uint16_t> pano(static_cast<size_t>(w) * static_cast<size_t>(h), 0);
    calib.warp().stitch(tiles, pano.data(), pano.size());

    size_t nonzero = 0;
    for (uint16_t v : pano) {
        if (v != 0) {
            ++nonzero;
        }
    }
    check(nonzero > pano.size() / 4, "warp produced coverage");
    std::fprintf(stderr, "warp test: %dx%d nonzero=%zu/%zu\n", w, h, nonzero, pano.size());
}

} // namespace

int main(int argc, char** argv) {
    const char* calib_dir = (argc > 1) ? argv[1] : "stitch-calib";
    if (!fs::exists(fs::path(calib_dir) / "warp_lut.bin")) {
        std::fprintf(stderr, "Skip: no warp_lut.bin at %s (run tools/stitch bake first)\n", calib_dir);
        return 0;
    }
    testWarpFromCalib(calib_dir);
    return failures == 0 ? 0 : 1;
}
