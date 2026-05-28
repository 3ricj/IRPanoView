#include "hik_libusb_session.h"

#include "hik_libusb_globals.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "HikLibusb", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HikLibusb", __VA_ARGS__)

namespace hik {

namespace {
constexpr int kControlTimeoutMs = 3000;
constexpr int kBulkTimeoutMs = 200;
constexpr int kBulkXferBytes = 16384;
constexpr int kStopJoinMs = 3000;
constexpr int kSoftCancelJoinMs = 200;

int64_t NowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}
}  // namespace

bool Session::Open(int fd) {
    if (!ensureLibusbInit()) {
        return false;
    }
    libusb_device_handle* devHandle = nullptr;
    const int rc = libusb_wrap_sys_device(libusbContext(), static_cast<intptr_t>(fd), &devHandle);
    if (rc != LIBUSB_SUCCESS || devHandle == nullptr) {
        LOGE("wrap_sys_device fd=%d rc=%d", fd, rc);
        return false;
    }
    handle_ = devHandle;
    LOGI("open fd=%d", fd);
    return true;
}

void Session::Close() {
    CancelBulkIn(kStopJoinMs);
    CloseHandle(-1);
}

int Session::ClaimInterface(int iface) {
    if (handle_ == nullptr) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    const int rc = libusb_claim_interface(handle_, iface);
    if (rc == LIBUSB_SUCCESS) {
        claimed_.push_back(iface);
    }
    return rc;
}

int Session::ReleaseInterface(int iface) {
    if (handle_ == nullptr) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    const int rc = libusb_release_interface(handle_, iface);
    claimed_.erase(
        std::remove(claimed_.begin(), claimed_.end(), iface),
        claimed_.end());
    return rc;
}

int Session::SetInterfaceAlt(int iface, int alt) {
    if (handle_ == nullptr) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    return libusb_set_interface_alt_setting(handle_, iface, alt);
}

int Session::ControlWrite(uint8_t bm, uint8_t bReq, uint16_t wValue, uint16_t wIndex, const uint8_t* data, int len) {
    if (handle_ == nullptr) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    return libusb_control_transfer(
        handle_, bm, bReq, wValue, wIndex,
        const_cast<uint8_t*>(data), static_cast<uint16_t>(len), kControlTimeoutMs);
}

int Session::ControlRead(uint8_t bm, uint8_t bReq, uint16_t wValue, uint16_t wIndex, uint8_t* data, int len) {
    if (handle_ == nullptr) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    return libusb_control_transfer(
        handle_, bm, bReq, wValue, wIndex, data, static_cast<uint16_t>(len), kControlTimeoutMs);
}

void Session::BulkCallback(libusb_transfer* transfer) {
    auto* self = static_cast<Session*>(transfer->user_data);
    if (self == nullptr) {
        return;
    }
    self->bulkInFlight_.store(false);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && transfer->actual_length > 0) {
        self->EnqueueBulkBytes(transfer->buffer, transfer->actual_length);
    }
    if (!self->bulkStop_.load() && self->handle_ != nullptr) {
        libusb_fill_bulk_transfer(
            transfer, self->handle_, self->bulkEp_,
            transfer->buffer, static_cast<int>(self->bulkBuffer_.size()),
            BulkCallback, self, kBulkTimeoutMs);
        const int rc = libusb_submit_transfer(transfer);
        if (rc == LIBUSB_SUCCESS) {
            self->bulkInFlight_.store(true);
        }
    }
}

int Session::StartBulkIn(uint8_t ep, int packetSize) {
    bulkStop_.store(false);
    bulkEp_ = ep;
    bulkPacketSize_ = packetSize > 0 ? packetSize : 512;
    if (bulkTransfer_ == nullptr) {
        bulkBuffer_.assign(kBulkXferBytes, 0);
        bulkTransfer_ = libusb_alloc_transfer(0);
        if (bulkTransfer_ == nullptr) {
            return LIBUSB_ERROR_NO_MEM;
        }
    }
    if (handle_ == nullptr) {
        return LIBUSB_ERROR_NO_DEVICE;
    }
    libusb_fill_bulk_transfer(
        bulkTransfer_, handle_, bulkEp_,
        bulkBuffer_.data(), static_cast<int>(bulkBuffer_.size()),
        BulkCallback, this, kBulkTimeoutMs);
    const int rc = libusb_submit_transfer(bulkTransfer_);
    if (rc == LIBUSB_SUCCESS) {
        bulkInFlight_.store(true);
    }
    return rc;
}

BulkCancelResult Session::CancelBulkIn(int maxWaitMs) {
    const int64_t start = NowMs();
    BulkCancelResult result;
    bulkStop_.store(true);
    if (bulkTransfer_ != nullptr && bulkInFlight_.load()) {
        if (libusb_cancel_transfer(bulkTransfer_) == LIBUSB_SUCCESS) {
            result.cancelCalled = 1;
        }
        const int waitMs = maxWaitMs > 0 ? maxWaitMs : kStopJoinMs;
        const int64_t deadline = start + waitMs;
        while (bulkInFlight_.load() && NowMs() < deadline) {
            pumpEventsOnce(20);
        }
    }
    result.elapsedMs = NowMs() - start;
    result.inFlightAfter = bulkInFlight_.load();
    {
        std::lock_guard<std::mutex> lock(bulkMutex_);
        bulkQueue_.clear();
    }
    return result;
}

void Session::EnqueueBulkBytes(const uint8_t* data, int len) {
    if (len <= 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(bulkMutex_);
    bulkQueue_.emplace_back(data, data + len);
}

bool Session::DequeueBulkBytes(uint8_t* out, int maxLen, int& outLen) {
    std::lock_guard<std::mutex> lock(bulkMutex_);
    if (bulkQueue_.empty()) {
        outLen = 0;
        return false;
    }
    auto chunk = std::move(bulkQueue_.front());
    bulkQueue_.pop_front();
    outLen = std::min(maxLen, static_cast<int>(chunk.size()));
    if (outLen > 0) {
        std::memcpy(out, chunk.data(), static_cast<size_t>(outLen));
    }
    return true;
}

int Session::PollBulkChunk(uint8_t* out, int maxLen, int timeoutMs) {
    const int64_t deadline = NowMs() + timeoutMs;
    int outLen = 0;
    while (NowMs() < deadline) {
        if (DequeueBulkBytes(out, maxLen, outLen)) {
            return outLen;
        }
        if (bulkStop_.load()) {
            return 0;
        }
        pumpEventsOnce(10);
    }
    return 0;
}

bool Session::HasQueuedBulk() const {
    std::lock_guard<std::mutex> lock(bulkMutex_);
    return !bulkQueue_.empty();
}

StopResult Session::StopChannelSoft(int /*vsInterface*/) {
    const int64_t start = NowMs();
    StopResult result;
    const BulkCancelResult cancel = CancelBulkIn(kSoftCancelJoinMs);
    result.cancelCalled = cancel.cancelCalled;
    result.ok = !bulkInFlight_.load();
    result.elapsedMs = NowMs() - start;
    LOGI("StopChannelSoft elapsed=%lld cancel=%d",
         static_cast<long long>(result.elapsedMs), result.cancelCalled);
    return result;
}

StopResult Session::StopChannelFull(int /*vsInterface*/) {
    const int64_t start = NowMs();
    StopResult result;
    const BulkCancelResult cancel = CancelBulkIn(kStopJoinMs);
    result.cancelCalled = cancel.cancelCalled;
    result.ok = !bulkInFlight_.load();
    result.elapsedMs = NowMs() - start;
    LOGI("StopChannelFull elapsed=%lld cancel=%d",
         static_cast<long long>(result.elapsedMs), result.cancelCalled);
    return result;
}

StopResult Session::StopChannelReference(int vsInterface, const int* ifaces, int ifaceCount) {
    const int64_t start = NowMs();
    StopResult result;
    const BulkCancelResult cancel = CancelBulkIn(kSoftCancelJoinMs);
    result.cancelCalled = cancel.cancelCalled;
    if (handle_ != nullptr && vsInterface >= 0) {
        const int altRc = libusb_set_interface_alt_setting(handle_, vsInterface, 0);
        LOGI("StopChannelReference alt0 iface=%d rc=%d", vsInterface, altRc);
    }
    if (handle_ != nullptr && ifaces != nullptr && ifaceCount > 0) {
        for (int i = ifaceCount - 1; i >= 0; --i) {
            const int rc = libusb_release_interface(handle_, ifaces[i]);
            LOGI("StopChannelReference release iface=%d rc=%d", ifaces[i], rc);
            claimed_.erase(
                std::remove(claimed_.begin(), claimed_.end(), ifaces[i]),
                claimed_.end());
        }
    }
    result.ok = !bulkInFlight_.load();
    result.elapsedMs = NowMs() - start;
    LOGI("StopChannelReference elapsed=%lld cancel=%d",
         static_cast<long long>(result.elapsedMs), result.cancelCalled);
    return result;
}

void Session::ReleaseAllClaimedInterfaces() {
    if (handle_ == nullptr) {
        claimed_.clear();
        return;
    }
    for (auto it = claimed_.rbegin(); it != claimed_.rend(); ++it) {
        const int rc = libusb_release_interface(handle_, *it);
        LOGI("release_interface iface=%d rc=%d", *it, rc);
    }
    claimed_.clear();
}

void Session::CloseHandle(int vsInterface, const int* ifaces, int ifaceCount) {
    CancelBulkIn(kStopJoinMs);
    if (handle_ != nullptr && vsInterface >= 0) {
        const int altRc = libusb_set_interface_alt_setting(handle_, vsInterface, 0);
        LOGI("set_alt iface=%d alt=0 rc=%d", vsInterface, altRc);
    }
    FreeBulkTransfer();
    if (handle_ != nullptr && ifaces != nullptr && ifaceCount > 0) {
        for (int i = ifaceCount - 1; i >= 0; --i) {
            const int rc = libusb_release_interface(handle_, ifaces[i]);
            LOGI("release_interface iface=%d rc=%d", ifaces[i], rc);
        }
        claimed_.clear();
    } else {
        ReleaseAllClaimedInterfaces();
    }
    if (handle_ != nullptr) {
        libusb_close(handle_);
        handle_ = nullptr;
    }
}

void Session::FreeBulkTransfer() {
    if (bulkTransfer_ != nullptr) {
        libusb_free_transfer(bulkTransfer_);
        bulkTransfer_ = nullptr;
    }
    bulkInFlight_.store(false);
}

}  // namespace hik
