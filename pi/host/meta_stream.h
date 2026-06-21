#pragma once

#include "thermal_meta.h"
#include "thermal_frame.h"

#include <cstdint>
#include <string>

namespace irpv {

class MetaStreamSender {
public:
    MetaStreamSender();
    ~MetaStreamSender();

    bool bind(uint16_t port);
    void setDestination(const std::string& ip, uint16_t port);
    bool send(const LatencyMetaPacket& packet);

private:
    int sock_ = -1;
    std::string dest_ip_ = "255.255.255.255";
    uint16_t dest_port_ = kDefaultMetaUdpPort;
};

} // namespace irpv
