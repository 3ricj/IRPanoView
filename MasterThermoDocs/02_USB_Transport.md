# 02 — USB transport (UVC DeviceConfig)

All configuration commands on the TC002C path use a **multi-phase UVC extension pattern** on a single extension unit. Payloads are read and written with standard USB control transfers.

---

## Extension unit

| Field | Typical value | Notes |
|-------|---------------|-------|
| `wIndex` | **0x0A00** | Derived at session bind; do not assume without opening the device |
| Direction IN | `bmRequestType = 0xA1` | Device-to-host |
| Direction OUT | `bmRequestType = 0x21` | Host-to-device |
| `bRequest` (SET data) | **0x01** | Write payload |
| `bRequest` (GET data) | **0x81** | Read payload |
| `bRequest` (PROBE) | **0x85** | Length/capability probe (4 bytes) |

---

## Phase pattern

Most DeviceConfig operations follow this sequence:

### 1. SELECT (optional for routed GET/SET)

Arm the extension unit for a logical route:

```
OUT  bm=0x21  bRequest=0x01  wValue=0x0500  wIndex=<unit>  len=2
Data: [phase, sub]
```

| phase | sub | Route | wValue for data |
|------:|----:|-------|-----------------|
| 0x01 | 0x04 | Hardware server | 0x0100 |
| 0x01 | 0x01 | Device info | 0x0100 |
| 0x02 | 0x06 | Image video adjust | 0x0200 |
| 0x02 | 0x05 | Image enhancement | 0x0200 |
| 0x03 | 0x01 | Therm basic param | 0x0300 |
| 0x17 | 0x1D | Capabilities refresh | 0x1700 |

### 2. PROBE

Query allowed transfer size before data phase:

```
IN   bm=0xA1  bRequest=0x85  wValue=<data channel>  wIndex=<unit>  len=4
```

Returns 4-byte probe block. Always PROBE before GET/SET.

### 3. GET (read current config)

```
IN   bm=0xA1  bRequest=0x81  wValue=<data channel>  wIndex=<unit>  len=<wire_len>
```

Wire length is command-specific (see per-command docs). GET may return fewer bytes than the full host struct; pad or truncate when mapping to host memory.

### 4. SET (write config)

```
PROBE (same wValue)
OUT  bm=0x21  bRequest=0x01  wValue=<data channel>  wIndex=<unit>  len=<wire_len>
Data: <payload>
```

Direct SET on therm basic param uses **wValue = 0x0300** without an extra SELECT on some paths; routed GET/modify/SET always SELECT first.

---

## Data channel wValue map (P0 commands)

| wValue | Channel | Commands |
|--------|---------|----------|
| **0x0100** | Misc / server | Hardware server **0x7DE**, device info **0x7DB** |
| **0x0200** | Image | Video adjust **0x7ED**, enhancement **0x7EB**, contrast **0x7E5** |
| **0x0300** | Thermometry | Basic param **0x7EF** GET/SET |
| **0x0600** | Status | Command completion poll (1 byte) |
| **0x1700** | Capabilities | Capability block refresh (**5 B** header + **265 B** blob) |

Therm GET and SET both use **wValue = 0x0300**.

---

## Command state poll

After SET operations, poll until the device finishes processing:

```
PROBE  wValue=0x0600
IN     bm=0xA1  bRequest=0x81  wValue=0x0600  wIndex=<unit>  len=1
```

Returns a single status byte. Poll in a short loop (~150 ms interval) until idle. Failure to poll can cause subsequent SETs to stall.

---

## Wire vs host struct sizes

The host may hold full structs in memory but sends a smaller **compact wire map** on USB:

| Command | Host struct | Wire SET length |
|---------|-------------|-----------------|
| Image video adjust **0x7ED** | ~44 B | **41** |
| Image enhancement **0x7EB** | larger | **176** |
| Therm basic **0x7EF** | **260 (0x104)** | **80 (0x50)** |
| Manual shutter **0x7E9** (Control) | **12** | **12** |

Always size control transfers to the **wire** length, not the full host struct size.

---

## Bulk streaming (separate from control)

Video frames use **bulk IN**, not isochronous UVC video:

| Property | Value |
|----------|-------|
| Typical endpoint | **0x81** |
| Bytes per frame | **201,248** |
| Rate | ~**25** fps |

Endpoint address comes from the USB configuration descriptor at stream open. If bulk reads fail after successful bind, verify the correct interface is claimed and the alternate setting matches the streaming configuration.

---

## Capabilities refresh (bind path)

Two related patterns on **wValue 0x1700**:

### Init capability bursts (protocol init / before login GETs)

During protocol initialization, repeat **~4** times **without** SELECT:

1. PROBE **wValue 0x1700** (4 B)
2. GET **wValue 0x1700**, length **5**
3. PROBE **wValue 0x1700**
4. GET **wValue 0x1700**, length **265**

On an unbound (cold) device, PROBE u16 may read **2** or **512**; payloads remain stub-sized until the full bind sequence completes.

### Bind-path refresh (after GET 0x7DB)

After hardware-server and device-info GETs, issue:

1. SELECT **(0x17, 0x1D)** on **wValue 0x0500**
2. PROBE **wValue 0x1700**
3. GET **wValue 0x1700**, length **5**
4. PROBE **wValue 0x1700**
5. GET **wValue 0x1700**, length **265**

The **265 B** blob carries a capability bitfield (JSON-like header observed on bound units). Repeat the SELECT refresh if re-enumerating mid-session.

See [03_Session_Initialization](03_Session_Initialization.md) Step 3 for full bind order.

---

## Host notes

- Claim the USB interfaces required for control and bulk before I/O (often interfaces **0** and **1**).
- Store `wIndex` from the opened device context; **0x0A00** is the common value on TC002C Duo.
- Use one USB handle for both control and bulk on the streaming interface.
