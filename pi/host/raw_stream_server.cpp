#include "raw_stream_server.h"

#include "thermal_tcp.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <cstring>
#include <poll.h>

namespace irpv {

RawStreamServer::RawStreamServer() = default;

RawStreamServer::~RawStreamServer() {
    stop();
}

bool RawStreamServer::start(uint16_t port) {
    stop();
    port_ = port;
    listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd_ < 0) {
        return false;
    }

    int yes = 1;
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port_);
    if (bind(listen_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        close(listen_fd_);
        listen_fd_ = -1;
        return false;
    }
    if (listen(listen_fd_, 1) != 0) {
        close(listen_fd_);
        listen_fd_ = -1;
        return false;
    }

    running_ = true;
    accept_thread_ = std::thread(&RawStreamServer::acceptLoop, this);
    return true;
}

void RawStreamServer::stop() {
    running_ = false;
    if (listen_fd_ >= 0) {
        shutdown(listen_fd_, SHUT_RDWR);
        close(listen_fd_);
        listen_fd_ = -1;
    }
    {
        std::lock_guard<std::mutex> lock(client_mutex_);
        closeClientLocked();
    }
    if (accept_thread_.joinable()) {
        accept_thread_.join();
    }
    has_client_ = false;
}

void RawStreamServer::closeClientLocked() {
    if (client_fd_ >= 0) {
        shutdown(client_fd_, SHUT_RDWR);
        close(client_fd_);
        client_fd_ = -1;
    }
    has_client_ = false;
}

void RawStreamServer::acceptLoop() {
    while (running_) {
        pollfd pfd{};
        pfd.fd = listen_fd_;
        pfd.events = POLLIN;
        const int ready = poll(&pfd, 1, 1000);
        if (ready <= 0) {
            continue;
        }
        const int fd = accept(listen_fd_, nullptr, nullptr);
        if (fd < 0) {
            continue;
        }
        std::lock_guard<std::mutex> lock(client_mutex_);
        closeClientLocked();
        client_fd_ = fd;
        has_client_ = true;
    }
}

bool RawStreamServer::sendFrame(const std::vector<uint8_t>& irpv_packet) {
    if (irpv_packet.empty()) {
        return false;
    }
    std::lock_guard<std::mutex> lock(client_mutex_);
    if (client_fd_ < 0) {
        return false;
    }

    RawTcpHeader hdr{};
    hdr.magic = kRawTcpMagic;
    hdr.length = static_cast<uint32_t>(irpv_packet.size());

    if (send(client_fd_, &hdr, sizeof(hdr), MSG_NOSIGNAL) != static_cast<ssize_t>(sizeof(hdr))) {
        closeClientLocked();
        return false;
    }
    size_t sent = 0;
    while (sent < irpv_packet.size()) {
        const ssize_t rc = send(
            client_fd_,
            irpv_packet.data() + sent,
            irpv_packet.size() - sent,
            MSG_NOSIGNAL);
        if (rc <= 0) {
            closeClientLocked();
            return false;
        }
        sent += static_cast<size_t>(rc);
    }
    return true;
}

} // namespace irpv
