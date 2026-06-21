#pragma once

#include "eq_seam.h"
#include "pano_geometry.h"
#include "warp_stitch.h"

#include <cstdint>
#include <string>
#include <vector>

namespace irpv::hik {

class PtguiCalib {
public:
    bool loadFromDir(const std::string& dir_path);

    const PanoGeometry& geometry() const { return geometry_; }
    const std::string& calibHash() const { return calib_hash_; }
    const std::string& sourcePts() const { return source_pts_; }
    const WarpStitchStrategy& warp() const { return warp_; }
    const std::vector<EqSeamPair>& eqSeams() const { return eq_seams_; }
    const std::string& slotSerial(int slot) const;

    bool matchSerials(const char* live_serials[4], std::string& error) const;

private:
    bool loadCalibJson(const std::string& path);
    bool loadWarpLut(const std::string& path);
    bool loadEqSeamSamples(const std::string& path);

    PanoGeometry geometry_{};
    std::string calib_hash_;
    std::string source_pts_;
    std::string slot_serials_[4];
    WarpStitchStrategy warp_;
    std::vector<EqSeamPair> eq_seams_;
};

} // namespace irpv::hik
