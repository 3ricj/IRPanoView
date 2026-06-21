#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace irpv::hik {

/** Port of GitHub HikUvcBulkReassembler.kt — libuvc-style FID/EOF assembly. */
class HikBulkReassembler {
public:
    void reset();
    void releaseAwaitingFrameBoundary();
    void beginStream() { releaseAwaitingFrameBoundary(); }

    std::optional<std::vector<uint8_t>> feed(const uint8_t* data, size_t len);
    std::optional<std::vector<uint8_t>> push(const uint8_t* data, size_t len) { return feed(data, len); }

private:
    static constexpr size_t kRingCapacity = 512 * 1024;
    static constexpr size_t kPendingCapacity = 16 * 1024;
    static constexpr int kTargetFrameBytes = 200704;
    static constexpr int kJumboTotalMinBytes = 0x1220 + 0x18000 * 2;
    static constexpr int kJumboTotalMaxBytes = kJumboTotalMinBytes + 0x2000;
    static constexpr int kJumboPlaneBytes = 0x18000;
    static constexpr int kJumboSwrfScanBytes = 64;
    static constexpr int kMaxUvcAssemblyBytes = (0x31000 * 3) / 2;
    static constexpr int kWireLeaderBytes = 2;

    static constexpr int kBmFid = 0x01;
    static constexpr int kBmEof = 0x02;
    static constexpr int kBmErr = 0x40;

    std::vector<uint8_t> ring_;
    size_t ring_head_ = 0;
    size_t ring_tail_ = 0;

    int uvc_packets_in_flight_ = 0;
    int uvc_fid_ = 0;
    bool awaiting_boundary_eof_ = true;
    bool awaiting_start_fid_toggle_ = false;

    std::vector<uint8_t> pending_;
    size_t pending_len_ = 0;

    struct UvcFeedResult {
        size_t consumed = 0;
        std::optional<std::vector<uint8_t>> frame;
    };

    std::optional<UvcFeedResult> feedOneUvcPacket(const uint8_t* buf, size_t avail);
    UvcFeedResult processUvcEofPacket(const uint8_t* buf, size_t avail, int header_len, int header_info);

    void clearUvcAccumulation();
    size_t ringSize() const;
    void appendPayloadRing(const uint8_t* src, size_t len);
    void compactRing();
    void copyRing(size_t src_offset, uint8_t* dest, size_t dest_offset, size_t len) const;

    std::optional<std::vector<uint8_t>> tryEmitUvcFrame();
    std::optional<std::vector<uint8_t>> normalizeAssembledFrame(const std::vector<uint8_t>& assembled);
    std::optional<std::vector<uint8_t>> extractUvcWirePayload(const std::vector<uint8_t>& assembled) const;
    std::optional<std::vector<uint8_t>> normalizeJumboFrame(const std::vector<uint8_t>& assembled) const;
    int findSwrfMagicOffset(const uint8_t* buf, size_t len) const;
};

} // namespace irpv::hik
