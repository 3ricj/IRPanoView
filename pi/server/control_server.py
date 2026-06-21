#!/usr/bin/env python3
"""WebSocket control bridge for irpanoview-host (port 8766)."""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
from pathlib import Path

try:
    import websockets
except ImportError:
    print("Install: pip3 install websockets", file=sys.stderr)
    raise

CONTROL_DIR = Path(os.environ.get("IRPV_CONTROL_DIR", "/run/irpanoview"))
CMD_FILE = CONTROL_DIR / "control.sock.cmd"
RESP_FILE = CONTROL_DIR / "control.sock.resp"
READY_FILE = CONTROL_DIR / "control.sock.ready"

WS_HOST = os.environ.get("IRPV_WS_HOST", "0.0.0.0")
WS_PORT = int(os.environ.get("IRPV_WS_PORT", "8766"))


def send_host_command(payload: dict) -> dict:
    CONTROL_DIR.mkdir(parents=True, exist_ok=True)
    CMD_FILE.write_text(json.dumps(payload), encoding="utf-8")
    for _ in range(50):
        if RESP_FILE.exists():
            text = RESP_FILE.read_text(encoding="utf-8").strip()
            RESP_FILE.unlink(missing_ok=True)
            return json.loads(text) if text else {"cmd": "error", "message": "empty response"}
        time.sleep(0.02)
    return {"cmd": "error", "message": "host timeout"}


async def handle_client(websocket):  # noqa: ANN001
    await websocket.send(json.dumps({"cmd": "hello", "ready": READY_FILE.exists()}))
    async for message in websocket:
        try:
            payload = json.loads(message)
        except json.JSONDecodeError:
            await websocket.send(json.dumps({"cmd": "error", "message": "invalid json"}))
            continue
        response = await asyncio.to_thread(send_host_command, payload)
        await websocket.send(json.dumps(response))


async def main() -> None:
    async with websockets.serve(handle_client, WS_HOST, WS_PORT, ping_interval=20, ping_timeout=20):
        print(f"IRPanoView control WS on ws://{WS_HOST}:{WS_PORT}")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
