#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace irpv {

class RawStreamServer {
public:
    RawStreamServer();
    ~RawStreamServer();

    bool start(uint16_t port);
    void stop();

    bool sendFrame(const std::vector<uint8_t>& irpv_packet);
    bool hasClient() const { return has_client_; }

private:
    void acceptLoop();
    void closeClientLocked();

    uint16_t port_ = 0;
    int listen_fd_ = -1;
    int client_fd_ = -1;
    std::thread accept_thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> has_client_{false};
    mutable std::mutex client_mutex_;
};

} // namespace irpv
