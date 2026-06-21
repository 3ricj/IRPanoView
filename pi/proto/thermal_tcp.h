#pragma once

#include <cstdint>

namespace irpv {

inline constexpr uint32_t kRawTcpMagic = 0x52505249u; // "IRPR" little-endian

#pragma pack(push, 1)
struct RawTcpHeader {
    uint32_t magic;
    uint32_t length;
};
#pragma pack(pop)

static_assert(sizeof(RawTcpHeader) == 8, "raw tcp header size");

} // namespace irpv
