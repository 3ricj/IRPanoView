#!/usr/bin/env bash
# Enable irpanoview-host to start automatically on boot.
#
# The service runs as user 'ericj' (NOT root) so USB camera access works the
# same as a manual start. Tolerates fewer than 4 cameras and retries forever
# until at least one camera enumerates.
#
# Usage:   sudo bash enable-host-boot.sh
# Disable: sudo systemctl disable --now irpanoview-host
# Logs:    journalctl -u irpanoview-host -f

set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "Please run with sudo:  sudo bash $0" >&2
    exit 1
fi

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
UNIT_SRC="${SRC_DIR}/irpanoview-host.service"
UNIT_DST=/etc/systemd/system/irpanoview-host.service

if [[ ! -f "${UNIT_SRC}" ]]; then
    echo "Unit file not found: ${UNIT_SRC}" >&2
    exit 1
fi

# Stop any manually-started host so it doesn't fight for USB / TCP port 8767.
echo "Stopping any running irpanoview-host instances..."
pkill -x irpanoview-host 2>/dev/null || true
sleep 2

echo "Installing ${UNIT_DST}..."
install -m 0644 "${UNIT_SRC}" "${UNIT_DST}"

systemctl daemon-reload
systemctl enable irpanoview-host.service
systemctl restart irpanoview-host.service

echo "Waiting for cameras to enumerate / host to come up..."
sleep 14
systemctl --no-pager --full status irpanoview-host.service || true

echo
echo "Done. The host will now start on every boot."
echo "Follow logs:  journalctl -u irpanoview-host -f"
