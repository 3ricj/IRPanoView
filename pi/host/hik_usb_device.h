#pragma once

#include "hik_constants.h"

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

struct libusb_context;
struct libusb_device;
struct libusb_device_handle;

namespace irpv::hik {

struct UsbDeviceInfo {
    std::string bus_path;
    std::string serial;
    int slot = 0;
    uint8_t bus = 0;
    uint8_t address = 0;
};

class HikUsbDevice {
public:
    HikUsbDevice();
    ~HikUsbDevice();

    HikUsbDevice(const HikUsbDevice&) = delete;
    HikUsbDevice& operator=(const HikUsbDevice&) = delete;

    bool open(const UsbDeviceInfo& info);
    void close();

    bool isOpen() const { return handle_ != nullptr; }
    const UsbDeviceInfo& info() const { return info_; }
    uint16_t windex() const { return windex_; }
    uint16_t streamingWindex() const {
        return static_cast<uint16_t>(streaming_interface_ < 0 ? 0 : (streaming_interface_ << 8));
    }
    int streamingInterface() const { return streaming_interface_; }
    int bulkEndpoint() const { return bulk_endpoint_; }

    int controlWrite(uint8_t bm, uint8_t breq, uint16_t wvalue, uint16_t windex, const uint8_t* data, int len);
    std::vector<uint8_t> controlRead(uint8_t bm, uint8_t breq, uint16_t wvalue, uint16_t windex, int len);
    std::optional<std::vector<uint8_t>> bulkRead(int endpoint, int max_len, int timeout_ms);

    bool selectStreamingAlt();
    bool selectAlt0();

private:
    bool claimInterfaces();
    bool resolveEndpoints();

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    UsbDeviceInfo info_{};
    uint16_t windex_ = kWindexXu;
    int streaming_interface_ = -1;
    int control_interface_ = -1;
    int bulk_endpoint_ = kBulkEp;
};

std::vector<UsbDeviceInfo> enumerateHikDevices();

} // namespace irpv::hik
