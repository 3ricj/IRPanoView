#include "hik_libusb_globals.h"

#include <android/log.h>

#define LOG_TAG "HikLibusb"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hik {

namespace {
std::mutex g_initMutex;
libusb_context* g_ctx = nullptr;
std::thread g_eventThread;
std::atomic<bool> g_eventRunning{false};

void eventLoop() {
    while (g_eventRunning.load()) {
        if (g_ctx != nullptr) {
            timeval tv{};
            tv.tv_sec = 0;
            tv.tv_usec = 50'000;
            libusb_handle_events_timeout_completed(g_ctx, &tv, nullptr);
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }
}
}  // namespace

bool ensureLibusbInit() {
    std::lock_guard<std::mutex> lock(g_initMutex);
    if (g_ctx != nullptr) {
        return true;
    }
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    const int rc = libusb_init(&g_ctx);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_init failed rc=%d", rc);
        g_ctx = nullptr;
        return false;
    }
    if (!g_eventRunning.exchange(true)) {
        g_eventThread = std::thread(eventLoop);
    }
    return true;
}

libusb_context* libusbContext() { return g_ctx; }

void pumpEventsOnce(int timeoutMs) {
    if (g_ctx == nullptr) {
        return;
    }
    timeval tv{};
    tv.tv_sec = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;
    libusb_handle_events_timeout_completed(g_ctx, &tv, nullptr);
}

}  // namespace hik
