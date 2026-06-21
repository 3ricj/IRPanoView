#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="${PREFIX:-/usr/local}"

sudo apt-get install -y \
    libusb-1.0-0-dev \
    gstreamer1.0-tools \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-rtsp \
    libgstreamer1.0-dev \
    libgstreamer-plugins-base1.0-dev \
    libgstreamer-plugins-bad1.0-dev 2>/dev/null || true

echo "Building irpanoview-host..."
cmake -S "$ROOT" -B "$ROOT/build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$ROOT/build" -j"$(nproc)"
sudo install -m755 "$ROOT/build/irpanoview-host" "$PREFIX/bin/irpanoview-host"

sudo mkdir -p /opt/irpanoview /run/irpanoview
sudo install -m755 "$ROOT/server/control_server.py" /opt/irpanoview/control_server.py
sudo pip3 install --break-system-packages websockets 2>/dev/null || sudo pip3 install websockets

sudo install -m644 "$ROOT/deploy/irpanoview-host.service" /etc/systemd/system/
sudo install -m644 "$ROOT/deploy/irpanoview-control.service" /etc/systemd/system/
sudo install -m644 "$ROOT/deploy/irpanoview-blacklist-uvcvideo.conf" /etc/modprobe.d/
sudo install -m644 "$ROOT/deploy/99-irpanoview-cameras.rules" /etc/udev/rules.d/
sudo install -m644 "$ROOT/deploy/avahi-irpanoview.service" /etc/avahi/services/
sudo udevadm control --reload-rules
sudo modprobe -r uvcvideo 2>/dev/null || true
sudo systemctl restart avahi-daemon 2>/dev/null || true
sudo systemctl daemon-reload
# Host/control services are installed but not auto-enabled — start manually when needed.
# sudo systemctl enable --now irpanoview-host irpanoview-control

echo "Install complete."
echo "Shared WiFi: join Pi and phone to the same AP; use irpanoview.local or Pi LAN IP in the app."
echo "Standalone hotspot (future): see deploy/hostapd.conf and deploy/dnsmasq.conf"
