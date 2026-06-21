#include "udp_streamer.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstring>

namespace irpv {

UdpStreamer::UdpStreamer() = default;

UdpStreamer::~UdpStreamer() {
    if (sock_ >= 0) {
        close(sock_);
        sock_ = -1;
    }
}

bool UdpStreamer::bind(uint16_t port) {
    if (sock_ >= 0) {
        close(sock_);
    }
    sock_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock_ < 0) {
        return false;
    }
    int yes = 1;
    setsockopt(sock_, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);
    return ::bind(sock_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0;
}

void UdpStreamer::setDestination(const std::string& ip, uint16_t port) {
    dest_ip_ = ip;
    dest_port_ = port;
}

bool UdpStreamer::send(const std::vector<uint8_t>& packet) {
    if (sock_ < 0) {
        return false;
    }
    sockaddr_in dest{};
    dest.sin_family = AF_INET;
    dest.sin_port = htons(dest_port_);
    inet_pton(AF_INET, dest_ip_.c_str(), &dest.sin_addr);
    const ssize_t sent = sendto(sock_, packet.data(), packet.size(), 0, reinterpret_cast<sockaddr*>(&dest), sizeof(dest));
    return sent == static_cast<ssize_t>(packet.size());
}

} // namespace irpv
