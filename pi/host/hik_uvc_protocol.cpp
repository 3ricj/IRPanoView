#include "hik_uvc_protocol.h"

#include "hik_constants.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <functional>
#include <thread>

namespace irpv::hik {

namespace {

constexpr int kBmClassIfOut = 0x21;
constexpr int kBmClassIfIn = 0xA1;
constexpr int kUvcSetCur = 0x01;
constexpr int kUvcGetCur = 0x81;
constexpr int kUvcGetLen = 0x85;
constexpr int kUvcVsProbe = 0x0100;
constexpr int kUvcVsCommit = 0x0200;
constexpr int kUvcFormatIndexYuy2 = 1;
constexpr int kUvcFrameIndexHik = 9;
constexpr int kFrameInterval100ns = 10'000'000 / kStreamFps;

void copyMin(std::vector<uint8_t>& dst, const std::vector<uint8_t>& src) {
    const size_t n = std::min(dst.size(), src.size());
    std::memcpy(dst.data(), src.data(), n);
}

} // namespace

void HikUvcProtocol::packU32Le(uint8_t* buf, int off, uint32_t v) {
    buf[off] = static_cast<uint8_t>(v & 0xFF);
    buf[off + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    buf[off + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    buf[off + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

void HikUvcProtocol::uvcSelect(int phase, int sub) {
    const uint8_t payload[2] = {static_cast<uint8_t>(phase), static_cast<uint8_t>(sub)};
    usb_.controlWrite(kBmReqOut, kBreqSet, kWvalueSelect, usb_.windex(), payload, 2);
}

std::vector<uint8_t> HikUvcProtocol::uvcProbe(uint16_t wvalue) {
    return usb_.controlRead(kBmReqIn, kBreqProbe, wvalue, usb_.windex(), 4);
}

std::vector<uint8_t> HikUvcProtocol::uvcGet(int phase, int sub, uint16_t wvalue, int len) {
    uvcSelect(phase, sub);
    uvcProbe(wvalue);
    return usb_.controlRead(kBmReqIn, kBreqGet, wvalue, usb_.windex(), len);
}

bool HikUvcProtocol::uvcSet(uint16_t wvalue, const uint8_t* data, int len) {
    uvcProbe(wvalue);
    return usb_.controlWrite(kBmReqOut, kBreqSet, wvalue, usb_.windex(), data, len) >= 0;
}

bool HikUvcProtocol::uvcGetModifySet(int phase, int sub, uint16_t wvalue, int len, const std::function<void(uint8_t*, int)>& patch) {
    auto raw = uvcGet(phase, sub, wvalue, len);
    std::vector<uint8_t> buf(static_cast<size_t>(len), 0);
    copyMin(buf, raw);
    patch(buf.data(), len);
    return uvcSet(wvalue, buf.data(), len);
}

int HikUvcProtocol::pollCommandState() {
    uvcProbe(kWvalueStatus);
    auto data = usb_.controlRead(kBmReqIn, kBreqGet, kWvalueStatus, usb_.windex(), 1);
    return data.empty() ? 0xFF : (data[0] & 0xFF);
}

bool HikUvcProtocol::waitCommandIdle(int timeout_ms) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (std::chrono::steady_clock::now() < deadline) {
        if (pollCommandState() == kCommandStateIdle) {
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(kPollIntervalMs));
    }
    return false;
}

std::vector<uint8_t> HikUvcProtocol::hardwareServerStatus() {
    return uvcGet(0x01, 0x04, kWvalueMisc, 3);
}

std::vector<uint8_t> HikUvcProtocol::deviceInfoBlock() {
    return uvcGet(0x01, 0x01, kWvalueMisc, kDeviceInfoLen);
}

bool HikUvcProtocol::isDeviceConfigBound() {
    auto hs = hardwareServerStatus();
    if (hs.size() >= 3 && !(hs[0] == 0x00 && hs[1] == 0x02)) {
        return true;
    }
    auto probe = uvcProbe(kWvalueCapabilities);
    if (probe.size() < 2) {
        return false;
    }
    const int cap = (probe[0] & 0xFF) | ((probe[1] & 0xFF) << 8);
    return cap > 2 && cap != 512 && cap != 513 && cap != 514;
}

void HikUvcProtocol::extensionArmTopview() {
    uvcProbe(kWvalueExtensionVersion);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueExtensionVersion, usb_.windex(), 4);
    uvcProbe(kWvalueStatus);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueStatus, usb_.windex(), 1);
    const uint8_t sel1[2] = {0x02, 0x00};
    uvcSet(kWvalueSelect, sel1, 2);
    uvcProbe(kWvalueTherm);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueTherm, usb_.windex(), 14);
    uvcSet(kWvalueSelect, sel1, 2);
    uvcProbe(kWvalueMisc);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueMisc, usb_.windex(), 3);
}

void HikUvcProtocol::capabilitiesInitBurst() {
    uvcProbe(kWvalueCapabilities);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueCapabilities, usb_.windex(), 5);
    uvcProbe(kWvalueCapabilities);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueCapabilities, usb_.windex(), kCapabilitiesBlobLen);
}

void HikUvcProtocol::capabilitiesRefresh() {
    uvcSelect(0x17, 0x1D);
    uvcProbe(kWvalueCapabilities);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueCapabilities, usb_.windex(), 5);
    uvcProbe(kWvalueCapabilities);
    usb_.controlRead(kBmReqIn, kBreqGet, kWvalueCapabilities, usb_.windex(), kCapabilitiesBlobLen);
}

void HikUvcProtocol::loginBindPreamble(int rounds) {
    extensionArmTopview();
    for (int i = 0; i < rounds; ++i) {
        capabilitiesInitBurst();
    }
}

bool HikUvcProtocol::login(bool cold_bind) {
    (void)cold_bind;
    loginBindPreamble();
    (void)hardwareServerStatus();
    auto info = deviceInfoBlock();
    if (static_cast<int>(info.size()) < kDeviceInfoLen) {
        std::fprintf(stderr, "login: device info %zu bytes (need %d)\n", info.size(), kDeviceInfoLen);
        return false;
    }
    capabilitiesRefresh();
    return true;
}

bool HikUvcProtocol::waitStreamReady() {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kReadyTimeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        auto last = hardwareServerStatus();
        if (last.size() >= 3) {
            const int status = last[2] & 0xFF;
            if (status == 2 || status == kHwServerReadyStatus) {
                return true;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(kPollIntervalMs));
    }
    return false;
}

bool HikUvcProtocol::initConfig(int emissivity, int distance, int temperature_range) {
    uvcGetModifySet(0x02, 0x06, kWvalueImage, 41, [](uint8_t* buf, int len) {
        if (len > 2) {
            buf[2] = 0;
        }
    });
    uvcGetModifySet(0x02, 0x05, kWvalueImage, 176, [](uint8_t*, int) {});
    return uvcGetModifySet(0x03, 0x01, kWvalueTherm, kThermWireLen, [&](uint8_t* buf, int len) {
        if (len > kWireOffThermOverlay) {
            buf[kWireOffThermOverlay] = static_cast<uint8_t>(kThermOverlayOff);
        }
        if (len > kWireOffTemperatureRange) {
            buf[kWireOffTemperatureRange] = static_cast<uint8_t>(temperature_range);
        }
        if (len > kWireOffEmissivity + 3) {
            packU32Le(buf, kWireOffEmissivity, static_cast<uint32_t>(emissivity));
        }
        if (len > kWireOffDistance + 3) {
            packU32Le(buf, kWireOffDistance, static_cast<uint32_t>(distance));
        }
    }) && waitCommandIdle();
}

bool HikUvcProtocol::setVideoParam() {
    auto hs = hardwareServerStatus();
    if (hs.size() < 3 || (hs[2] & 0xFF) < 2) {
        return false;
    }

    const auto patch = [](uint8_t* buf, int len) {
        if (len > 12) {
            packU32Le(buf, 0, kStreamFormat);
            packU32Le(buf, 4, kStreamWidthToken);
            packU32Le(buf, 8, kStreamHeightToken);
            packU32Le(buf, 12, kStreamFps);
        }
    };

    struct Route {
        int phase;
        int sub;
        uint16_t wvalue;
    };
    static const Route kRoutes[] = {
        {0x04, 0x01, kWvalueVideoParam},
        {0x0B, 0x01, kWvalueVideoParam},
        {0x04, 0x01, kWvalueExtensionVersion},
        {0x0B, 0x01, kWvalueMisc},
        {0x0B, 0x01, kWvalueExtensionVersion},
        {0x02, 0x03, kWvalueImage},
        {0x02, 0x00, kWvalueImage},
    };

    for (const auto& route : kRoutes) {
        if (uvcGetModifySet(route.phase, route.sub, route.wvalue, kVideoParamWireLen, patch)) {
            std::fprintf(stderr, "setVideoParam ok route (%02x,%02x) w=0x%04x\n",
                         route.phase, route.sub, route.wvalue);
            (void)waitCommandIdle(1500);
            return true;
        }
    }

    const uint8_t sel[2] = {0x02, 0x00};
    if (uvcSet(kWvalueSelect, sel, 2)) {
        std::fprintf(stderr, "setVideoParam: select-only fallback\n");
        return true;
    }

    std::fprintf(stderr, "setVideoParam failed all routes\n");
    return false;
}

bool HikUvcProtocol::armStream() {
    if (!usb_.selectStreamingAlt()) {
        std::fprintf(stderr, "armStream: selectStreamingAlt failed\n");
        return false;
    }

    usb_.selectAlt0();
    const uint16_t widx = usb_.streamingWindex();
    auto probe_len_data = usb_.controlRead(kBmClassIfIn, kUvcGetLen, kUvcVsProbe, widx, 2);
    int probe_len = 34;
    if (probe_len_data.size() >= 2) {
        probe_len = (probe_len_data[0] & 0xFF) | ((probe_len_data[1] & 0xFF) << 8);
        probe_len = std::clamp(probe_len, 26, 48);
    }
    std::vector<uint8_t> probe(static_cast<size_t>(probe_len), 0);
    auto cur = usb_.controlRead(kBmClassIfIn, kUvcGetCur, kUvcVsProbe, widx, probe_len);
    copyMin(probe, cur);
    if (probe.size() >= 4) {
        probe[2] = static_cast<uint8_t>(kUvcFormatIndexYuy2);
        probe[3] = static_cast<uint8_t>(kUvcFrameIndexHik);
    }
    if (probe.size() >= 8) {
        packU32Le(probe.data(), 4, kFrameInterval100ns);
    }
    if (probe.size() >= 26) {
        packU32Le(probe.data(), 22, kFrameBytes);
    }
    if (usb_.controlWrite(kBmClassIfOut, kUvcSetCur, kUvcVsProbe, widx, probe.data(), probe_len) < 0) {
        std::fprintf(stderr, "armStream: VS_PROBE failed\n");
        return false;
    }
    if (usb_.controlWrite(kBmClassIfOut, kUvcSetCur, kUvcVsCommit, widx, probe.data(), probe_len) < 0) {
        std::fprintf(stderr, "armStream: VS_COMMIT failed\n");
        return false;
    }
    pollCommandState();
    if (!usb_.selectStreamingAlt()) {
        std::fprintf(stderr, "armStream: selectStreamingAlt after commit failed\n");
        return false;
    }
    return true;
}

bool HikUvcProtocol::disarmStream() {
    if (!usb_.isOpen()) {
        return false;
    }

    usb_.selectAlt0();

    const int iface = usb_.streamingInterface();
    if (iface < 0) {
        return false;
    }

    const uint16_t windex_candidates[] = {
        static_cast<uint16_t>(iface << 8),
        static_cast<uint16_t>(iface),
        static_cast<uint16_t>((0 << 8) | iface),
    };

    bool decommitted = false;
    for (const uint16_t widx : windex_candidates) {
        auto probe_len_data = usb_.controlRead(kBmClassIfIn, kUvcGetLen, kUvcVsProbe, widx, 2);
        int probe_len = 34;
        if (probe_len_data.size() >= 2) {
            probe_len = (probe_len_data[0] & 0xFF) | ((probe_len_data[1] & 0xFF) << 8);
            probe_len = std::clamp(probe_len, 26, 48);
        }

        std::vector<uint8_t> probe(static_cast<size_t>(probe_len), 0);
        auto cur = usb_.controlRead(kBmClassIfIn, kUvcGetCur, kUvcVsProbe, widx, probe_len);
        if (cur.empty()) {
            continue;
        }
        copyMin(probe, cur);
        if (probe.size() >= 26) {
            packU32Le(probe.data(), 22, 0);
        }
        if (probe.size() >= 30) {
            packU32Le(probe.data(), 26, 0);
        }
        if (usb_.controlWrite(kBmClassIfOut, kUvcSetCur, kUvcVsProbe, widx, probe.data(), probe_len) < 0) {
            continue;
        }
        if (usb_.controlWrite(kBmClassIfOut, kUvcSetCur, kUvcVsCommit, widx, probe.data(), probe_len) < 0) {
            continue;
        }
        decommitted = true;
        break;
    }

    usb_.selectAlt0();
    return decommitted;
}

bool HikUvcProtocol::setIrConfig(double emissivity, double distance_m, double ambient_c) {
    const int emissivity_wire = static_cast<int>(emissivity * 100.0 + 0.5);
    const int distance_wire = static_cast<int>(distance_m * 100.0 + 0.5);
    const int ambient_wire = static_cast<int>(ambient_c * 100.0 + 0.5) + 10000;
    return uvcGetModifySet(0x03, 0x01, kWvalueTherm, kThermWireLen, [&](uint8_t* buf, int len) {
        if (len > kWireOffEmissivity + 3) {
            packU32Le(buf, kWireOffEmissivity, static_cast<uint32_t>(emissivity_wire));
        }
        if (len > kWireOffDistance + 3) {
            packU32Le(buf, kWireOffDistance, static_cast<uint32_t>(distance_wire));
        }
        if (len > kWireOffEnvTempEnable) {
            buf[kWireOffEnvTempEnable] = static_cast<uint8_t>(kEnvTempEnableOn);
        }
        if (len > kWireOffEnvTemp + 3) {
            packU32Le(buf, kWireOffEnvTemp, static_cast<uint32_t>(ambient_wire));
        }
    }) && waitCommandIdle();
}

bool HikUvcProtocol::manualShutter() {
    uvcSelect(kSelectPhaseImageManualCorrect, kSelectSubImageManualCorrect);
    uint8_t cond[kUsbCommonCondWireLen] = {};
    packU32Le(cond, 0, kUsbCommonCondWireLen);
    cond[4] = 1;
    return uvcSet(kWvalueImage, cond, kUsbCommonCondWireLen) && waitCommandIdle();
}

} // namespace irpv::hik
