#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace irpv {

class UdpStreamer {
public:
    UdpStreamer();
    ~UdpStreamer();

    bool bind(uint16_t port);
    void setDestination(const std::string& ip, uint16_t port);
    bool send(const std::vector<uint8_t>& packet);

private:
    int sock_ = -1;
    std::string dest_ip_ = "255.255.255.255";
    uint16_t dest_port_ = 8765;
};

} // namespace irpv
