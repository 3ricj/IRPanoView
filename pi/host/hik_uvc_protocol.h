#pragma once

#include "hik_usb_device.h"

#include <cstdint>
#include <functional>
#include <vector>

namespace irpv::hik {

class HikUvcProtocol {
public:
    explicit HikUvcProtocol(HikUsbDevice& usb) : usb_(usb) {}

    bool login(bool cold_bind = true);
    bool waitStreamReady();
    bool initConfig(int emissivity = 95, int distance = 100, int temperature_range = 2);
    bool setVideoParam();
    bool armStream();
    bool disarmStream();
    bool setIrConfig(double emissivity, double distance_m, double ambient_c);
    bool manualShutter();

    std::vector<uint8_t> hardwareServerStatus();

private:
    void uvcSelect(int phase, int sub);
    std::vector<uint8_t> uvcProbe(uint16_t wvalue);
    std::vector<uint8_t> uvcGet(int phase, int sub, uint16_t wvalue, int len);
    bool uvcSet(uint16_t wvalue, const uint8_t* data, int len);
    bool uvcGetModifySet(int phase, int sub, uint16_t wvalue, int len, const std::function<void(uint8_t*, int)>& patch);
    int pollCommandState();
    bool waitCommandIdle(int timeout_ms = 5000);
    void loginBindPreamble(int rounds = 4);
    std::vector<uint8_t> deviceInfoBlock();
    bool isDeviceConfigBound();
    void extensionArmTopview();
    void capabilitiesInitBurst();
    void capabilitiesRefresh();
    static void packU32Le(uint8_t* buf, int off, uint32_t v);

    HikUsbDevice& usb_;
};

} // namespace irpv::hik
