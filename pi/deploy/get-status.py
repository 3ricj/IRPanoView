#!/usr/bin/env python3
import time
from pathlib import Path

base = Path("/home/ericj/run/irpanoview/control.sock")
Path(str(base) + ".cmd").write_text('{"cmd":"get_status"}\n', encoding="utf-8")
time.sleep(0.3)
resp = Path(str(base) + ".resp")
print(resp.read_text(encoding="utf-8") if resp.exists() else "no response")
