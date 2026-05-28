#pragma once

#include <libusb.h>

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>

namespace hik {

struct BulkCancelResult {
    int cancelCalled = 0;
    int drained = 0;
    int64_t elapsedMs = 0;
    bool inFlightAfter = false;
};

struct StopResult {
    bool ok = false;
    int64_t elapsedMs = 0;
    int cancelCalled = 0;
};

class Session {
public:
    bool Open(int fd);
    void Close();

    int ClaimInterface(int iface);
    int ReleaseInterface(int iface);
    int SetInterfaceAlt(int iface, int alt);

    int ControlWrite(uint8_t bm, uint8_t bReq, uint16_t wValue, uint16_t wIndex, const uint8_t* data, int len);
    int ControlRead(uint8_t bm, uint8_t bReq, uint16_t wValue, uint16_t wIndex, uint8_t* data, int len);

    int StartBulkIn(uint8_t ep, int packetSize);
    /** Cancel async bulk; [maxWaitMs] caps in-flight join (soft pause ~200, full exit ~3000). */
    BulkCancelResult CancelBulkIn(int maxWaitMs = 3000);
    int PollBulkChunk(uint8_t* out, int maxLen, int timeoutMs);
    bool HasQueuedBulk() const;

    /** Cancel-only stop — no alt-0. */
    StopResult StopChannelSoft(int vsInterface);
    /** Cancel with full wait — no alt-0. */
    StopResult StopChannelFull(int vsInterface);
    /** Reference leave: cancel + alt-0 + release_interface; keep fd open. */
    StopResult StopChannelReference(int vsInterface, const int* ifaces, int ifaceCount);
    /** alt-0 + release claimed ifaces + libusb_close — once per full exit close phase. */
    void CloseHandle(int vsInterface, const int* ifaces = nullptr, int ifaceCount = 0);

    libusb_device_handle* handle() const { return handle_; }
    const std::vector<int>& claimedInterfaces() const { return claimed_; }
    uint8_t bulkEp() const { return bulkEp_; }

private:
    static void BulkCallback(libusb_transfer* transfer);

    void FreeBulkTransfer();
    void ReleaseAllClaimedInterfaces();
    void EnqueueBulkBytes(const uint8_t* data, int len);
    bool DequeueBulkBytes(uint8_t* out, int maxLen, int& outLen);

    libusb_device_handle* handle_ = nullptr;
    std::vector<int> claimed_;
    uint8_t bulkEp_ = 0x81;
    int bulkPacketSize_ = 512;

    libusb_transfer* bulkTransfer_ = nullptr;
    std::vector<uint8_t> bulkBuffer_;
    mutable std::mutex bulkMutex_;
    std::deque<std::vector<uint8_t>> bulkQueue_;
    std::atomic<bool> bulkStop_{false};
    std::atomic<bool> bulkInFlight_{false};
};

}  // namespace hik
