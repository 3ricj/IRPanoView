#include "host_state.h"

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>

namespace {

std::atomic<bool> g_running{true};

void onSignal(int) {
    g_running = false;
}

void printUsage(const char* argv0) {
    std::cerr << "Usage: " << argv0
              << " [--demo] [--meta-dest IP] [--meta-port N] [--raw-tcp-port N]"
              << " [--rtsp-port N] [--control-socket PATH] [--offset-file PATH]"
              << " [--stitch-calib DIR] [--stitch-mode auto|warp|offset|edge]"
              << " [--no-equalization] [--eq-alpha N]\n";
}

} // namespace

int main(int argc, char** argv) {
    irpv::HostConfig config;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--demo") == 0) {
            config.demo_mode = true;
        } else if (std::strcmp(argv[i], "--meta-dest") == 0 && i + 1 < argc) {
            config.meta_dest = argv[++i];
        } else if (std::strcmp(argv[i], "--meta-port") == 0 && i + 1 < argc) {
            config.meta_port = static_cast<uint16_t>(std::stoi(argv[++i]));
        } else if (std::strcmp(argv[i], "--raw-tcp-port") == 0 && i + 1 < argc) {
            config.raw_tcp_port = static_cast<uint16_t>(std::stoi(argv[++i]));
        } else if (std::strcmp(argv[i], "--rtsp-port") == 0 && i + 1 < argc) {
            config.rtsp_port = static_cast<uint16_t>(std::stoi(argv[++i]));
        } else if (std::strcmp(argv[i], "--control-socket") == 0 && i + 1 < argc) {
            config.control_socket = argv[++i];
        } else if (std::strcmp(argv[i], "--offset-file") == 0 && i + 1 < argc) {
            config.offset_file = argv[++i];
        } else if (std::strcmp(argv[i], "--stitch-calib") == 0 && i + 1 < argc) {
            config.stitch_calib_dir = argv[++i];
        } else if (std::strcmp(argv[i], "--stitch-mode") == 0 && i + 1 < argc) {
            config.stitch_mode = argv[++i];
        } else if (std::strcmp(argv[i], "--no-equalization") == 0) {
            config.equalization_enabled = false;
        } else if (std::strcmp(argv[i], "--eq-alpha") == 0 && i + 1 < argc) {
            config.equalization_alpha = std::stod(argv[++i]);
        } else if (std::strcmp(argv[i], "--help") == 0) {
            printUsage(argv[0]);
            return 0;
        }
    }

    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);

    irpv::HostState host(config);
    if (!host.start()) {
        std::cerr << "Failed to start host (need at least 1 camera or --demo)\n";
        return 1;
    }

    std::ofstream ready(config.control_socket + ".ready");
    ready << "ok\n";

    while (g_running) {
        std::ifstream cmd_in(config.control_socket + ".cmd");
        std::string line;
        if (std::getline(cmd_in, line) && !line.empty()) {
            const std::string response = host.handleCommand(line);
            std::ofstream cmd_out(config.control_socket + ".resp");
            cmd_out << response << '\n';
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    host.stop();
    return 0;
}
