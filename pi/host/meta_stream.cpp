#include "meta_stream.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstring>

namespace irpv {

MetaStreamSender::MetaStreamSender() = default;

MetaStreamSender::~MetaStreamSender() {
    if (sock_ >= 0) {
        close(sock_);
        sock_ = -1;
    }
}

bool MetaStreamSender::bind(uint16_t port) {
    (void)port;
    if (sock_ >= 0) {
        close(sock_);
    }
    sock_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock_ < 0) {
        return false;
    }
    int yes = 1;
    setsockopt(sock_, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));
    return true;
}

void MetaStreamSender::setDestination(const std::string& ip, uint16_t port) {
    dest_ip_ = ip;
    dest_port_ = port;
}

bool MetaStreamSender::send(const LatencyMetaPacket& packet) {
    if (sock_ < 0) {
        return false;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(dest_port_);
    if (inet_pton(AF_INET, dest_ip_.c_str(), &addr.sin_addr) != 1) {
        return false;
    }
    return sendto(sock_, &packet, sizeof(packet), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) ==
           static_cast<ssize_t>(sizeof(packet));
}

} // namespace irpv
