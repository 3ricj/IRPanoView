#pragma once

#include <cstdint>

namespace irpv::hik {

inline constexpr uint16_t kVid = 0x2BDF;
inline constexpr uint16_t kPid = 0x0102;
inline constexpr uint16_t kWindexXu = 0x0A00;

inline constexpr uint16_t kWvalueCapabilities = 0x1700;
inline constexpr uint16_t kWvalueSelect = 0x0500;
inline constexpr uint16_t kWvalueMisc = 0x0100;
inline constexpr uint16_t kWvalueImage = 0x0200;
inline constexpr uint16_t kWvalueTherm = 0x0300;
inline constexpr uint16_t kWvalueStatus = 0x0600;
inline constexpr uint16_t kWvalueExtensionVersion = 0x0400;
inline constexpr uint16_t kWvalueVideoParam = 0x0BBC;

inline constexpr uint8_t kBmReqOut = 0x21;
inline constexpr uint8_t kBmReqIn = 0xA1;
inline constexpr uint8_t kBreqSet = 0x01;
inline constexpr uint8_t kBreqProbe = 0x85;
inline constexpr uint8_t kBreqGet = 0x81;

inline constexpr int kCapabilitiesBlobLen = 265;
inline constexpr int kWireOffThermOverlay = 2;
inline constexpr int kWireOffTemperatureRange = 6;
inline constexpr int kWireOffEmissivity = 16;
inline constexpr int kWireOffDistance = 21;
inline constexpr int kWireOffEnvTempEnable = 0x4B;
inline constexpr int kWireOffEnvTemp = 0x4C;
inline constexpr int kThermOverlayOff = 1;
inline constexpr int kEnvTempEnableOn = 2;
inline constexpr int kHwServerReadyStatus = 3;
inline constexpr int kCommandStateIdle = 0;

inline constexpr uint8_t kStreamFormat = 0x67;
inline constexpr int kStreamFps = 25;
inline constexpr int kStreamWidthToken = 8;
inline constexpr int kStreamHeightToken = 0x3122;

inline constexpr int kFrameBytes = 200704;
inline constexpr int kGridWidth = 256;
inline constexpr int kGridHeight = 192;
inline constexpr int kBulkEp = 0x81;

inline constexpr int kControlTimeoutMs = 3000;
inline constexpr int kBulkTimeoutMs = 8000;
inline constexpr int kBulkTimeoutIdleMs = 500;
inline constexpr int kReadyTimeoutMs = 30000;
inline constexpr int kPollIntervalMs = 150;

inline constexpr int kThermWireLen = 80;
inline constexpr int kVideoParamWireLen = 0xA8;
inline constexpr int kDeviceInfoLen = 488;

inline constexpr int kSelectPhaseSerial = 0x03;
inline constexpr int kSelectSubSerial = 0x01;
inline constexpr int kSerialCmdAutoShutter = 0x2001;
inline constexpr int kSelectPhaseImageManualCorrect = 0x02;
inline constexpr int kSelectSubImageManualCorrect = 0x04;
inline constexpr int kUsbCommonCondWireLen = 12;

inline constexpr int kSerialTransmissionWireLen = 269;

} // namespace irpv::hik
