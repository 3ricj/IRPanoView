#!/usr/bin/env python3
"""Receive and summarize IRPanoView UDP thermal frames."""

import argparse
import socket
import struct
import sys

MAGIC = 0x56505249
HEADER_FMT = "<IBBHIIQ"
HEADER_SIZE = struct.calcsize(HEADER_FMT)


def main() -> int:
    parser = argparse.ArgumentParser(description="Dump IRPanoView thermal UDP frames")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--count", type=int, default=10)
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    print(f"Listening on UDP {args.port}...", file=sys.stderr)

    last_seq = -1
    received = 0
    while received < args.count:
        data, addr = sock.recvfrom(65535)
        if len(data) < HEADER_SIZE:
            continue
        magic, ver, flags, width, height, seq, ts_us = struct.unpack(HEADER_FMT, data[:HEADER_SIZE])
        if magic != MAGIC:
            continue
        if seq <= last_seq:
            continue
        last_seq = seq
        payload_bytes = width * height * 2
        print(f"from={addr[0]} seq={seq} {width}x{height} ver={ver} flags={flags} payload={payload_bytes}B ts_us={ts_us}")
        received += 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
