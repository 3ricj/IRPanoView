#pragma once

#include <cstdint>

namespace irpv {

inline constexpr uint32_t kLatencyMetaMagic = 0x4d505249u; // "IRPM"
inline constexpr uint16_t kLatencyMetaVersion = 1;

#pragma pack(push, 1)
struct LatencyMetaPacket {
    uint32_t magic = kLatencyMetaMagic;
    uint16_t version = kLatencyMetaVersion;
    uint16_t byte_size = sizeof(LatencyMetaPacket);
    uint32_t video_seq = 0;
    uint64_t compose_us = 0;
    uint64_t encode_submit_us = 0;
    uint32_t slot_frame_seq[4]{};
    uint64_t slot_usb_frame_us[4]{};
};
#pragma pack(pop)

static_assert(sizeof(LatencyMetaPacket) == 76, "latency meta packet size");

} // namespace irpv
