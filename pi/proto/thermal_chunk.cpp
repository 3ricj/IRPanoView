#include "thermal_chunk.h"

#include <algorithm>
#include <cstring>

namespace irpv {

std::vector<std::vector<uint8_t>> buildThermalFrameChunks(const std::vector<uint8_t>& frame) {
    std::vector<std::vector<uint8_t>> chunks;
    if (frame.empty()) {
        return chunks;
    }

    const size_t max_data = kMaxUdpPayloadBytes - sizeof(ThermalChunkHeader);
    const uint16_t chunk_count = static_cast<uint16_t>((frame.size() + max_data - 1) / max_data);
    const uint32_t frame_sequence = frame.size() >= 14
        ? *reinterpret_cast<const uint32_t*>(frame.data() + 10)
        : 0;

    for (uint16_t idx = 0; idx < chunk_count; ++idx) {
        const size_t offset = static_cast<size_t>(idx) * max_data;
        const size_t nbytes = std::min(max_data, frame.size() - offset);

        ThermalChunkHeader hdr{};
        hdr.magic = kThermalChunkMagic;
        hdr.frame_sequence = frame_sequence;
        hdr.chunk_index = idx;
        hdr.chunk_count = chunk_count;
        hdr.byte_offset = static_cast<uint32_t>(offset);
        hdr.total_bytes = static_cast<uint32_t>(frame.size());

        std::vector<uint8_t> packet(sizeof(ThermalChunkHeader) + nbytes);
        std::memcpy(packet.data(), &hdr, sizeof(hdr));
        std::memcpy(packet.data() + sizeof(hdr), frame.data() + offset, nbytes);
        chunks.push_back(std::move(packet));
    }

    return chunks;
}

} // namespace irpv
