#!/usr/bin/env python3
import signal
import socket
import subprocess
import time

HOST = "/home/ericj/IRPanoView/pi/build/irpanoview-host"
SOCK = "/home/ericj/run/irpanoview/control.sock"

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("0.0.0.0", 8765))
s.settimeout(1.0)

p = subprocess.Popen(
    [HOST, "--udp-dest", "127.0.0.1", "--control-socket", SOCK],
    stderr=subprocess.PIPE,
    text=True,
)

chunks = 0
deadline = time.time() + 20
while time.time() < deadline:
    try:
        d, _ = s.recvfrom(65535)
        if d[:4] in (b"IRPC", b"IRPV"):
            chunks += 1
            if chunks == 1:
                print(f"first {d[:4].decode()} len={len(d)}")
    except socket.timeout:
        pass

print(f"chunks={chunks}")
p.send_signal(signal.SIGINT)
try:
    _, err = p.communicate(timeout=5)
except subprocess.TimeoutExpired:
    p.kill()
    _, err = p.communicate()
if err:
    print("--- stderr ---")
    print(err[-3000:])
