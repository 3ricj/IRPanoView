#pragma once

#include <libusb.h>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>

namespace hik {

bool ensureLibusbInit();
libusb_context* libusbContext();
void pumpEventsOnce(int timeoutMs);

}  // namespace hik
