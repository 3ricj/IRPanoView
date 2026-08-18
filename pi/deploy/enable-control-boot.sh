#!/usr/bin/env bash
# Enable irpanoview-control (WebSocket :8766) to start automatically on boot.
#
# Android "Waiting for Pi at 192.168.4.1" means this service is down — the app
# probes TCP 8766 only. The host preview on 8769 can still work without it.
#
# Usage:   sudo bash enable-control-boot.sh
# Disable: sudo systemctl disable --now irpanoview-control
# Logs:    journalctl -u irpanoview-control -f

set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "Please run with sudo:  sudo bash $0" >&2
    exit 1
fi

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
UNIT_SRC="${SRC_DIR}/irpanoview-control.service"
UNIT_DST=/etc/systemd/system/irpanoview-control.service
CTRL_PY=/home/ericj/IRPanoView/pi/server/control_server.py

if [[ ! -f "${UNIT_SRC}" ]]; then
    echo "Unit file not found: ${UNIT_SRC}" >&2
    exit 1
fi

if [[ ! -f "${CTRL_PY}" ]]; then
    echo "control_server.py not found: ${CTRL_PY}" >&2
    exit 1
fi

# Stop any manually-started control server so it doesn't fight for :8766.
echo "Stopping any running control_server.py instances..."
pkill -f '/IRPanoView/pi/server/control_server.py' 2>/dev/null || true
pkill -f '/opt/irpanoview/control_server.py' 2>/dev/null || true
sleep 1

echo "Installing ${UNIT_DST}..."
install -m 0644 "${UNIT_SRC}" "${UNIT_DST}"

systemctl daemon-reload
systemctl enable irpanoview-control.service
systemctl restart irpanoview-control.service

sleep 1
systemctl --no-pager --full status irpanoview-control.service || true

echo
if ss -ltn | grep -q ':8766 '; then
    echo "OK: listening on :8766"
else
    echo "WARN: :8766 not listening yet — check: journalctl -u irpanoview-control -n 40" >&2
fi

echo "Done. Control will now start on every boot."
echo "Follow logs:  journalctl -u irpanoview-control -f"
