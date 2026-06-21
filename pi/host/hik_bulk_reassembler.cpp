#include "hik_bulk_reassembler.h"

#include <algorithm>
#include <cstring>

namespace irpv::hik {

void HikBulkReassembler::reset() {
    ring_.clear();
    ring_head_ = 0;
    ring_tail_ = 0;
    uvc_packets_in_flight_ = 0;
    pending_.clear();
    pending_len_ = 0;
    uvc_fid_ = 0;
    awaiting_boundary_eof_ = true;
    awaiting_start_fid_toggle_ = false;
}

void HikBulkReassembler::releaseAwaitingFrameBoundary() {
    uvc_fid_ = 0;
    awaiting_boundary_eof_ = true;
    awaiting_start_fid_toggle_ = false;
    clearUvcAccumulation();
}

void HikBulkReassembler::clearUvcAccumulation() {
    ring_head_ = 0;
    ring_tail_ = 0;
    uvc_packets_in_flight_ = 0;
}

size_t HikBulkReassembler::ringSize() const {
    return ring_tail_ - ring_head_;
}

void HikBulkReassembler::compactRing() {
    if (ring_head_ == 0) {
        return;
    }
    const size_t size = ringSize();
    if (size > 0) {
        std::memmove(ring_.data(), ring_.data() + ring_head_, size);
    }
    ring_head_ = 0;
    ring_tail_ = size;
}

void HikBulkReassembler::appendPayloadRing(const uint8_t* src, size_t len) {
    if (ring_.empty()) {
        ring_.resize(kRingCapacity);
    }
    if (ring_tail_ + len > ring_.size()) {
        compactRing();
    }
    if (ring_tail_ + len > ring_.size()) {
        clearUvcAccumulation();
        return;
    }
    std::memcpy(ring_.data() + ring_tail_, src, len);
    ring_tail_ += len;
}

void HikBulkReassembler::copyRing(size_t src_offset, uint8_t* dest, size_t dest_offset, size_t len) const {
    std::memcpy(dest + dest_offset, ring_.data() + ring_head_ + src_offset, len);
}

int HikBulkReassembler::findSwrfMagicOffset(const uint8_t* buf, size_t len) const {
    const size_t max_scan = std::min(static_cast<size_t>(kJumboSwrfScanBytes), len > 4 ? len - 4 : 0);
    for (size_t i = 0; i <= max_scan; ++i) {
        if (buf[i] == 0x73 && buf[i + 1] == 0x77 && buf[i + 2] == 0x82 && buf[i + 3] == 0x70) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

std::optional<std::vector<uint8_t>> HikBulkReassembler::extractUvcWirePayload(
    const std::vector<uint8_t>& assembled) const {
    if (assembled.empty()) {
        return std::nullopt;
    }
    if (assembled.size() == static_cast<size_t>(kWireLeaderBytes + kTargetFrameBytes) &&
        assembled[0] == 0x73 && assembled[1] == 0x77) {
        return std::vector<uint8_t>(
            assembled.begin() + kWireLeaderBytes,
            assembled.end());
    }
    return assembled;
}

std::optional<std::vector<uint8_t>> HikBulkReassembler::normalizeJumboFrame(
    const std::vector<uint8_t>& assembled) const {
    const int size = static_cast<int>(assembled.size());
    if (size < kJumboTotalMinBytes || size > kJumboTotalMaxBytes) {
        return std::nullopt;
    }
    if (findSwrfMagicOffset(assembled.data(), assembled.size()) < 0) {
        return std::nullopt;
    }
    const int visible_start = size - kJumboPlaneBytes;
    const int temp_start = visible_start - kJumboPlaneBytes;
    if (temp_start <= 0 || visible_start <= temp_start) {
        return std::nullopt;
    }

    std::vector<uint8_t> canonical(static_cast<size_t>(kTargetFrameBytes), 0);
    std::memcpy(canonical.data(), assembled.data() + temp_start, kJumboPlaneBytes);
    std::memcpy(canonical.data() + 0x18800, assembled.data() + visible_start, kJumboPlaneBytes);
    return canonical;
}

std::optional<std::vector<uint8_t>> HikBulkReassembler::normalizeAssembledFrame(
    const std::vector<uint8_t>& assembled) {
    if (static_cast<int>(assembled.size()) == kTargetFrameBytes) {
        return assembled;
    }
    if (auto wire = extractUvcWirePayload(assembled)) {
        if (static_cast<int>(wire->size()) == kTargetFrameBytes) {
            return wire;
        }
    }
    return normalizeJumboFrame(assembled);
}

std::optional<std::vector<uint8_t>> HikBulkReassembler::tryEmitUvcFrame() {
    const size_t size = ringSize();
    if (size == 0) {
        return std::nullopt;
    }

    std::vector<uint8_t> assembled(size);
    copyRing(0, assembled.data(), 0, size);
    auto frame = normalizeAssembledFrame(assembled);
    clearUvcAccumulation();

    if (!frame) {
        if (assembled.size() > static_cast<size_t>(kTargetFrameBytes)) {
            awaiting_start_fid_toggle_ = true;
        }
        return std::nullopt;
    }
    return frame;
}

HikBulkReassembler::UvcFeedResult HikBulkReassembler::processUvcEofPacket(
    const uint8_t* buf, size_t avail, int header_len, int header_info) {
    if ((header_info & kBmErr) != 0) {
        clearUvcAccumulation();
        const size_t consumed = header_len > 0 ? std::min(static_cast<size_t>(header_len), avail) : avail;
        return {consumed, std::nullopt};
    }

    const bool eof = (header_info & kBmEof) != 0;
    const int new_fid = header_info & kBmFid;

    if (awaiting_boundary_eof_) {
        if (eof) {
            uvc_fid_ = new_fid;
            awaiting_boundary_eof_ = false;
            awaiting_start_fid_toggle_ = true;
        }
        return {avail, std::nullopt};
    }

    if (awaiting_start_fid_toggle_) {
        if (new_fid == uvc_fid_) {
            return {avail, std::nullopt};
        }
        awaiting_start_fid_toggle_ = false;
    }

    int data_len = header_len > 0 ? static_cast<int>(avail) - header_len : static_cast<int>(avail);
    if (data_len < 0) {
        data_len = 0;
    }

    std::optional<std::vector<uint8_t>> completed;
    if (header_len >= 2) {
        uvc_fid_ = new_fid;
    }

    if (data_len > 0) {
        const int room = kMaxUvcAssemblyBytes - static_cast<int>(ringSize());
        if (room <= 0) {
            clearUvcAccumulation();
            data_len = 0;
        } else if (data_len > room) {
            data_len = room;
        }
        if (data_len > 0) {
            appendPayloadRing(buf + header_len, static_cast<size_t>(data_len));
            ++uvc_packets_in_flight_;
        }
    }

    if (eof) {
        completed = tryEmitUvcFrame();
    }

    const size_t consumed = header_len > 0
        ? std::min(avail, static_cast<size_t>(header_len + data_len))
        : avail;
    return {consumed, completed};
}

std::optional<HikBulkReassembler::UvcFeedResult> HikBulkReassembler::feedOneUvcPacket(
    const uint8_t* buf, size_t avail) {
    if (avail < 2) {
        return std::nullopt;
    }

    const int header_len = buf[0] & 0xFF;
    if (header_len < 2) {
        clearUvcAccumulation();
        return UvcFeedResult{avail, std::nullopt};
    }
    if (static_cast<size_t>(header_len) > avail) {
        return std::nullopt;
    }

    const int bm_header_info = buf[1] & 0xFF;
    return processUvcEofPacket(buf, avail, header_len, bm_header_info);
}

std::optional<std::vector<uint8_t>> HikBulkReassembler::feed(const uint8_t* data, size_t len) {
    if (len == 0) {
        return std::nullopt;
    }

    std::vector<uint8_t> input;
    if (pending_len_ > 0) {
        input.resize(pending_len_ + len);
        std::memcpy(input.data(), pending_.data(), pending_len_);
        std::memcpy(input.data() + pending_len_, data, len);
        pending_len_ = 0;
    } else {
        input.assign(data, data + len);
    }

    size_t offset = 0;
    std::optional<std::vector<uint8_t>> completed;

    while (offset < input.size()) {
        const size_t avail = input.size() - offset;
        auto result = feedOneUvcPacket(input.data() + offset, avail);
        if (!result) {
            pending_.assign(input.begin() + static_cast<std::ptrdiff_t>(offset), input.end());
            pending_len_ = avail;
            break;
        }

        offset += result->consumed;
        if (result->frame) {
            completed = std::move(*result->frame);
        }
    }

    return completed;
}

} // namespace irpv::hik
