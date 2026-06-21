#!/bin/bash
# One-time (sudo): thermal-only USB setup — blacklist uvcvideo, Hik camera permissions.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"

sudo install -m644 "$ROOT/irpanoview-blacklist-uvcvideo.conf" /etc/modprobe.d/
sudo install -m644 "$ROOT/99-irpanoview-cameras.rules" /etc/udev/rules.d/
sudo udevadm control --reload-rules

echo "Blacklisting uvcvideo and unloading if present..."
sudo modprobe -r uvcvideo 2>/dev/null || true

sudo udevadm trigger --subsystem-match=usb --attr-match=idVendor=2bdf --action=add

echo "Done. uvcvideo will not load on boot."
echo "Hik cameras:"
lsusb -d 2bdf:0102 2>/dev/null || echo "  (none plugged)"
echo "Driver state:"
lsusb -t 2>/dev/null | grep -E 'Hub|Video|2bdf' || true
