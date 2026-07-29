#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace irpv {

class RawStreamServer {
public:
    RawStreamServer();
    ~RawStreamServer();

    bool start(uint16_t port);
    void stop();

    // Blocking send (radiometric 1 Hz path).
    bool sendFrame(const std::vector<uint8_t>& irpv_packet);

    // Non-blocking for caller: keeps only the latest packet; send thread drains.
    // Use for high-rate live preview so the compositor never waits on TCP.
    bool queueLatestFrame(std::vector<uint8_t> packet);

    bool hasClient() const { return has_client_; }

private:
    void acceptLoop();
    void sendLoop();
    void closeClientLocked();
    bool sendFrameLocked(const std::vector<uint8_t>& irpv_packet);

    uint16_t port_ = 0;
    int listen_fd_ = -1;
    int client_fd_ = -1;
    std::thread accept_thread_;
    std::thread send_thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> has_client_{false};
    mutable std::mutex client_mutex_;

    std::mutex pending_mutex_;
    std::condition_variable pending_cv_;
    std::vector<uint8_t> pending_;
    bool has_pending_ = false;
};

} // namespace irpv
