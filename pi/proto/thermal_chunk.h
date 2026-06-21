#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace irpv {

inline constexpr uint32_t kThermalChunkMagic = 0x43505249u; // "IRPC"
inline constexpr size_t kMaxUdpPayloadBytes = 60000;

#pragma pack(push, 1)
struct ThermalChunkHeader {
    uint32_t magic;
    uint32_t frame_sequence;
    uint16_t chunk_index;
    uint16_t chunk_count;
    uint32_t byte_offset;
    uint32_t total_bytes;
};
#pragma pack(pop)

static_assert(sizeof(ThermalChunkHeader) == 20, "thermal chunk header size");

std::vector<std::vector<uint8_t>> buildThermalFrameChunks(const std::vector<uint8_t>& frame);

} // namespace irpv
