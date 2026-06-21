#include "hik_usb_device.h"

#include "hik_constants.h"

#include <libusb-1.0/libusb.h>

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>

namespace irpv::hik {

namespace {

int slotFromSerialSuffix(const std::string& serial) {
    // Pano left-to-right: serial suffix 07, 86, 02, 97 → slots 1–4
    static const char* suffixes[] = {"07", "86", "02", "97"};
    if (serial.size() < 2) {
        return 0;
    }
    const std::string suffix = serial.substr(serial.size() - 2);
    for (int i = 0; i < 4; ++i) {
        if (suffix == suffixes[i]) {
            return i + 1;
        }
    }
    return 0;
}

void detachKernelDrivers(libusb_device_handle* handle, const libusb_config_descriptor* cfg) {
    if (!handle || !cfg) {
        return;
    }
    for (int i = 0; i < cfg->bNumInterfaces; ++i) {
        for (int a = 0; a < cfg->interface[i].num_altsetting; ++a) {
            const int iface = cfg->interface[i].altsetting[a].bInterfaceNumber;
            if (libusb_kernel_driver_active(handle, iface) == 1) {
                libusb_detach_kernel_driver(handle, iface);
            }
        }
    }
}

std::string sysfsSerial(uint8_t bus, uint8_t address) {
    namespace fs = std::filesystem;
    for (const auto& entry : fs::directory_iterator("/sys/bus/usb/devices")) {
        const auto base = entry.path();
        std::ifstream busnum_file(base / "busnum");
        std::ifstream devnum_file(base / "devnum");
        int busnum = 0;
        int devnum = 0;
        if (!(busnum_file >> busnum) || !(devnum_file >> devnum)) {
            continue;
        }
        if (busnum != static_cast<int>(bus) || devnum != static_cast<int>(address)) {
            continue;
        }
        std::ifstream serial_file(base / "serial");
        std::string serial;
        if (serial_file >> serial) {
            return serial;
        }
    }
    return {};
}

} // namespace

HikUsbDevice::HikUsbDevice() {
    libusb_init(&ctx_);
}

HikUsbDevice::~HikUsbDevice() {
    close();
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
}

bool HikUsbDevice::open(const UsbDeviceInfo& info) {
    close();
    info_ = info;

    libusb_device** list = nullptr;
    const ssize_t count = libusb_get_device_list(ctx_, &list);
    if (count < 0) {
        return false;
    }

    libusb_device* target = nullptr;
    for (ssize_t i = 0; i < count; ++i) {
        libusb_device_descriptor desc{};
        if (libusb_get_device_descriptor(list[i], &desc) != 0) {
            continue;
        }
        if (desc.idVendor != kVid || desc.idProduct != kPid) {
            continue;
        }
        const uint8_t bus = libusb_get_bus_number(list[i]);
        const uint8_t addr = libusb_get_device_address(list[i]);
        if (info.bus != 0 && info.address != 0) {
            if (bus == info.bus && addr == info.address) {
                target = list[i];
                break;
            }
        }
        if (!info.bus_path.empty()) {
            std::ostringstream oss;
            oss << static_cast<int>(bus) << "-" << static_cast<int>(addr);
            if (info.bus_path.find(oss.str()) != std::string::npos) {
                target = list[i];
                break;
            }
        }
    }

    if (!target) {
        libusb_free_device_list(list, 1);
        return false;
    }

    if (libusb_open(target, &handle_) != 0) {
        libusb_free_device_list(list, 1);
        return false;
    }
    libusb_free_device_list(list, 1);

    libusb_config_descriptor* cfg = nullptr;
    if (libusb_get_active_config_descriptor(target, &cfg) == 0 && cfg != nullptr) {
        detachKernelDrivers(handle_, cfg);
        libusb_free_config_descriptor(cfg);
    }

    if (!claimInterfaces()) {
        close();
        return false;
    }
    return true;
}

void HikUsbDevice::close() {
    if (handle_) {
        selectAlt0();
        if (streaming_interface_ >= 0) {
            libusb_release_interface(handle_, streaming_interface_);
        }
        if (control_interface_ >= 0 && control_interface_ != streaming_interface_) {
            libusb_release_interface(handle_, control_interface_);
        }
        libusb_close(handle_);
        handle_ = nullptr;
    }
    streaming_interface_ = -1;
    control_interface_ = -1;
}

bool HikUsbDevice::claimInterfaces() {
    libusb_device* dev = libusb_get_device(handle_);
    libusb_config_descriptor* cfg = nullptr;
    if (libusb_get_active_config_descriptor(dev, &cfg) != 0 || cfg == nullptr) {
        return false;
    }

    streaming_interface_ = -1;
    control_interface_ = 0;
    bulk_endpoint_ = kBulkEp;
    for (int i = 0; i < cfg->bNumInterfaces; ++i) {
        const libusb_interface& iface = cfg->interface[i];
        for (int a = 0; a < iface.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = iface.altsetting[a];
            for (int e = 0; e < alt.bNumEndpoints; ++e) {
                const libusb_endpoint_descriptor& ep = alt.endpoint[e];
                if ((ep.bEndpointAddress & 0x80) && (ep.bmAttributes & 0x03) == LIBUSB_TRANSFER_TYPE_BULK) {
                    streaming_interface_ = alt.bInterfaceNumber;
                    bulk_endpoint_ = ep.bEndpointAddress;
                    break;
                }
            }
        }
    }
    libusb_free_config_descriptor(cfg);

    if (streaming_interface_ < 0) {
        return false;
    }
    if (control_interface_ >= 0 && control_interface_ != streaming_interface_) {
        libusb_claim_interface(handle_, control_interface_);
    }
    return libusb_claim_interface(handle_, streaming_interface_) == 0;
}

bool HikUsbDevice::selectStreamingAlt() {
    libusb_device* dev = libusb_get_device(handle_);
    libusb_config_descriptor* cfg = nullptr;
    if (libusb_get_active_config_descriptor(dev, &cfg) != 0 || cfg == nullptr) {
        return false;
    }
    int best_alt = 0;
    for (int i = 0; i < cfg->bNumInterfaces; ++i) {
        if (cfg->interface[i].num_altsetting <= 1) {
            continue;
        }
        if (cfg->interface[i].altsetting[0].bInterfaceNumber == streaming_interface_) {
            best_alt = cfg->interface[i].num_altsetting - 1;
            break;
        }
    }
    libusb_free_config_descriptor(cfg);
    return libusb_set_interface_alt_setting(handle_, streaming_interface_, best_alt) == 0;
}

bool HikUsbDevice::selectAlt0() {
    if (streaming_interface_ < 0 || handle_ == nullptr) {
        return false;
    }
    return libusb_set_interface_alt_setting(handle_, streaming_interface_, 0) == 0;
}

int HikUsbDevice::controlWrite(uint8_t bm, uint8_t breq, uint16_t wvalue, uint16_t windex, const uint8_t* data, int len) {
    if (!handle_) {
        return -1;
    }
    return libusb_control_transfer(handle_, bm, breq, wvalue, windex, const_cast<uint8_t*>(data), len, kControlTimeoutMs);
}

std::vector<uint8_t> HikUsbDevice::controlRead(uint8_t bm, uint8_t breq, uint16_t wvalue, uint16_t windex, int len) {
    std::vector<uint8_t> out(len);
    if (!handle_) {
        return {};
    }
    const int rc = libusb_control_transfer(handle_, bm, breq, wvalue, windex, out.data(), len, kControlTimeoutMs);
    if (rc < 0) {
        return {};
    }
    out.resize(static_cast<size_t>(rc));
    return out;
}

std::optional<std::vector<uint8_t>> HikUsbDevice::bulkRead(int endpoint, int max_len, int timeout_ms) {
    if (!handle_) {
        return std::nullopt;
    }
    std::vector<uint8_t> buf(static_cast<size_t>(max_len));
    int transferred = 0;
    const int rc = libusb_bulk_transfer(handle_, endpoint, buf.data(), max_len, &transferred, timeout_ms);
    if (rc != 0 || transferred <= 0) {
        return std::nullopt;
    }
    buf.resize(static_cast<size_t>(transferred));
    return buf;
}

std::vector<UsbDeviceInfo> enumerateHikDevices() {
    libusb_context* ctx = nullptr;
    libusb_init(&ctx);
    libusb_device** list = nullptr;
    const ssize_t count = libusb_get_device_list(ctx, &list);
    std::vector<UsbDeviceInfo> out;
    if (count < 0) {
        libusb_exit(ctx);
        return out;
    }

    for (ssize_t i = 0; i < count; ++i) {
        libusb_device_descriptor desc{};
        if (libusb_get_device_descriptor(list[i], &desc) != 0) {
            continue;
        }
        if (desc.idVendor != kVid || desc.idProduct != kPid) {
            continue;
        }
        UsbDeviceInfo info;
        info.bus = libusb_get_bus_number(list[i]);
        info.address = libusb_get_device_address(list[i]);
        std::ostringstream oss;
        oss << "/dev/bus/usb/" << static_cast<int>(info.bus) << "/" << static_cast<int>(info.address);
        info.bus_path = oss.str();

        libusb_device_handle* h = nullptr;
        if (libusb_open(list[i], &h) == 0) {
            libusb_config_descriptor* cfg = nullptr;
            if (libusb_get_active_config_descriptor(list[i], &cfg) == 0 && cfg != nullptr) {
                detachKernelDrivers(h, cfg);
                libusb_free_config_descriptor(cfg);
            }
            char serial[256] = {};
            if (libusb_get_string_descriptor_ascii(h, desc.iSerialNumber, reinterpret_cast<unsigned char*>(serial), sizeof(serial)) >= 0) {
                info.serial = serial;
            }
            libusb_close(h);
        }
        if (info.serial.empty()) {
            info.serial = sysfsSerial(info.bus, info.address);
        }
        info.slot = slotFromSerialSuffix(info.serial);
        out.push_back(std::move(info));
    }

    libusb_free_device_list(list, 1);
    libusb_exit(ctx);
    std::sort(out.begin(), out.end(), [](const UsbDeviceInfo& a, const UsbDeviceInfo& b) {
        const int sa = a.slot > 0 ? a.slot : 99;
        const int sb = b.slot > 0 ? b.slot : 99;
        if (sa != sb) {
            return sa < sb;
        }
        return a.bus_path < b.bus_path;
    });
    return out;
}

} // namespace irpv::hik
