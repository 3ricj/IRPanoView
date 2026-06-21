#include "ptgui_calib.h"

#include <cstdio>
#include <fstream>
#include <sstream>

namespace irpv::hik {

namespace {

std::string jsonStringField(const std::string& json, const char* key) {
    const std::string needle = std::string("\"") + key + "\": \"";
    const size_t pos = json.find(needle);
    if (pos == std::string::npos) {
        return {};
    }
    const size_t start = pos + needle.size();
    const size_t end = json.find('"', start);
    if (end == std::string::npos) {
        return {};
    }
    return json.substr(start, end - start);
}

int jsonIntField(const std::string& json, const char* key, int fallback) {
    const std::string needle = std::string("\"") + key + "\": ";
    const size_t pos = json.find(needle);
    if (pos == std::string::npos) {
        return fallback;
    }
    return std::stoi(json.substr(pos + needle.size()));
}

} // namespace

bool PtguiCalib::loadCalibJson(const std::string& path) {
    std::ifstream in(path);
    if (!in) {
        return false;
    }
    std::ostringstream ss;
    ss << in.rdbuf();
    const std::string json = ss.str();

    calib_hash_ = jsonStringField(json, "calib_hash");
    source_pts_ = jsonStringField(json, "source_pts");
    const int w = jsonIntField(json, "output_width", 0);
    const int h = jsonIntField(json, "output_height", 0);
    if (w > 0 && h > 0) {
        geometry_.width = w;
        geometry_.height = h;
    }

    // Parse "slot_serials": ["EA6744407", ...]
    const std::string arr_key = "\"slot_serials\": [";
    const size_t arr_pos = json.find(arr_key);
    if (arr_pos != std::string::npos) {
        size_t i = arr_pos + arr_key.size();
        for (int slot = 0; slot < 4; ++slot) {
            while (i < json.size() && (json[i] == ' ' || json[i] == '\n')) {
                ++i;
            }
            if (i >= json.size() || json[i] != '"') {
                break;
            }
            ++i;
            const size_t start = i;
            while (i < json.size() && json[i] != '"') {
                ++i;
            }
            slot_serials_[slot] = json.substr(start, i - start);
            ++i;
            while (i < json.size() && json[i] != '"' && json[i] != ']') {
                ++i;
            }
            if (i < json.size() && json[i] == ',') {
                ++i;
            }
        }
    }
    return true;
}

bool PtguiCalib::loadWarpLut(const std::string& path) {
    PanoGeometry geom{};
    std::vector<std::string> serials;
    if (!warp_.loadBinary(path, geom, serials)) {
        return false;
    }
    geometry_ = geom;
    for (int i = 0; i < 4 && i < static_cast<int>(serials.size()); ++i) {
        if (slot_serials_[i].empty()) {
            slot_serials_[i] = serials[static_cast<size_t>(i)];
        }
    }
    return true;
}

bool PtguiCalib::loadEqSeamSamples(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return false;
    }
    uint32_t num_cams = 0;
    uint32_t seam_count = 0;
    in.read(reinterpret_cast<char*>(&num_cams), 4);
    in.read(reinterpret_cast<char*>(&seam_count), 4);
    if (!in || num_cams != 4) {
        return false;
    }

    eq_seams_.clear();
    eq_seams_.resize(seam_count);
    for (uint32_t s = 0; s < seam_count; ++s) {
        uint32_t seam_idx = 0;
        uint32_t left_slot = 0;
        uint32_t right_slot = 0;
        uint32_t sample_count = 0;
        in.read(reinterpret_cast<char*>(&seam_idx), 4);
        in.read(reinterpret_cast<char*>(&left_slot), 4);
        in.read(reinterpret_cast<char*>(&right_slot), 4);
        in.read(reinterpret_cast<char*>(&sample_count), 4);
        if (!in) {
            return false;
        }
        EqSeamPair pair;
        pair.left_slot = static_cast<int>(left_slot);
        pair.right_slot = static_cast<int>(right_slot);
        pair.samples.resize(sample_count);
        for (uint32_t i = 0; i < sample_count; ++i) {
            EqSeamSample sample{};
            in.read(reinterpret_cast<char*>(&sample.left_x_q8), 4);
            in.read(reinterpret_cast<char*>(&sample.left_y_q8), 4);
            in.read(reinterpret_cast<char*>(&sample.right_x_q8), 4);
            in.read(reinterpret_cast<char*>(&sample.right_y_q8), 4);
            if (!in) {
                return false;
            }
            pair.samples[i] = sample;
        }
        eq_seams_[seam_idx] = std::move(pair);
    }
    return true;
}

bool PtguiCalib::loadFromDir(const std::string& dir_path) {
    const std::string dir = dir_path.back() == '/' ? dir_path.substr(0, dir_path.size() - 1) : dir_path;
    if (!loadCalibJson(dir + "/calib.json")) {
        std::fprintf(stderr, "ptgui calib: missing calib.json in %s\n", dir.c_str());
        return false;
    }
    if (!loadWarpLut(dir + "/warp_lut.bin")) {
        std::fprintf(stderr, "ptgui calib: failed to load warp_lut.bin\n");
        return false;
    }
    if (!loadEqSeamSamples(dir + "/eq_seam_samples.bin")) {
        std::fprintf(stderr, "ptgui calib: failed to load eq_seam_samples.bin\n");
        return false;
    }
    std::fprintf(stderr, "Loaded PTGUI stitch calib %s (%dx%d hash=%s)\n",
        dir.c_str(), geometry_.width, geometry_.height, calib_hash_.c_str());
    for (int i = 0; i < 4; ++i) {
        std::fprintf(stderr, "  slot %d serial=%s\n", i + 1, slot_serials_[i].c_str());
    }
    return true;
}

const std::string& PtguiCalib::slotSerial(int slot) const {
    static const std::string kEmpty;
    if (slot < 0 || slot >= 4) {
        return kEmpty;
    }
    return slot_serials_[slot];
}

bool PtguiCalib::matchSerials(const char* live_serials[4], std::string& error) const {
    for (int i = 0; i < 4; ++i) {
        const char* live = live_serials[i] ? live_serials[i] : "";
        if (slot_serials_[i] != live) {
            error = "slot " + std::to_string(i + 1) + " expected " + slot_serials_[i] + " got " + live;
            return false;
        }
    }
    return true;
}

} // namespace irpv::hik
