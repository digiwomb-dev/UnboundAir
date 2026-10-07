---
title: Scanner Protocol
---

<!-- translated from docs/de/protocol.md @ e766871c0e8b920fc88c75a6ea0190e88d096b7f -->


Technical basis for the scanner client (SC-01 through SC-08). This file describes **how to talk to the device**: connection, commands, answers, scan flow and timing. **[Gerät]**

The device itself — WLAN, feed, measurements, quirks — belongs in `hardware.md` (DO-02). **[Gerät]** What is still unresolved belongs in `docs/internal/offene-fragen.md` and is only referenced here, not repeated. **[Gerät]**

## Provenance of statements

Every statement carries its provenance marker. **[Gerät]**

- **[Gerät]** — verified on the real device **[Gerät]**
- **[App/s400w]** — from the Windows app or the s400w implementation, not yet tested on the device **[App/s400w]**
- **[Handbuch]** — from the manual **[Handbuch]**
- **[Scan]** — observed on 1–2 real scans **[Scan]**
- **[offen]** — still unknown **[offen]**

Nothing is invented: where the source says `[offen]`, this file says `[offen]`. **[offen]**

## Connection

The scanner listens on `192.168.18.33`, port `23`, over TCP, without authentication and without encryption. **[Gerät]**

One TCP connection is opened per operation, not per command. **[App/s400w]** An operation is either a single status query or a complete scan (`status` → DPI → `scan` → `jpegsize` → `jpegdata`). **[App/s400w]** The scan sends `status` again inside its connection; that opens no new connection. **[App/s400w]**

## Commands

Every command is 4 bytes long, in send order; stored in the Windows app as Int32 little endian. **[Gerät]** This is verified for `status`, `version`, `dpi300`, `scan`, `jpegsize` and `jpegdata`; for the remaining commands the statement comes from the app or s400w. **[App/s400w]**

| Command | Bytes (send order) | Answer (prefix) | Provenance |
|---|---|---|---|
| `status` | `00 60 00 50` | `scanready` / `nopaper` **[Gerät]** / `devbusy` / `battlow` **[App/s400w]** | **[Gerät]** **[App/s400w]** |
| `version` | `30 30 20 20` | e.g. `NB0a.032` **[Gerät]** | **[Gerät]** |
| `dpi300` | `40 30 20 10` | `dpistd` **[Gerät]** | **[Gerät]** |
| `dpi600` | `80 70 60 50` | `dpifine` **[App/s400w]** | **[App/s400w]** |
| `scan` (start) | `00 20 00 10` | `scango` **[Gerät]** | **[Gerät]** |
| `jpegsize` | `00 D0 00 C0` | `jpegsize` + uint32 LE (12 bytes total) **[Gerät]** | **[Gerät]** |
| `jpegdata` | `00 F0 00 E0` | exactly `size` bytes of JPEG **[Gerät]** | **[Gerät]** |
| `preview` | `40 40 30 30` | raw lines until `previewend` (unneeded for this service) **[App/s400w]** | **[App/s400w]** |
| `clean` | `80 80 70 70` | `cleango` / `cleanend` **[App/s400w]** | **[App/s400w]** |
| `calibrate` | `00 B0 00 A0` | `calgo` / `calibrate` **[App/s400w]** | **[App/s400w]** |

Provenance per row: the `status` answers `scanready` and `nopaper` are verified on the device. **[Gerät]** The answers `devbusy` and `battlow` are known from the app or s400w, untested on the device. **[App/s400w]** The `version` answer (e.g. `NB0a.032`) was observed on the device. **[Gerät]** The commands `dpi300`, `scan`, `jpegsize` and `jpegdata` with their answers are verified on the device. **[Gerät]** The commands `dpi600`, `preview`, `clean` and `calibrate` with their answers come from the app or s400w and are untested on the device. **[App/s400w]**

The device knows no duplex command: the complete command set holds no command for double-sided scanning. **[App/s400w]** The back is scanned by flipping the sheet and feeding it again. **[App/s400w]**

## Answers and padding bytes

Status and confirmation answers are 11 bytes long: a word, padded with `\x00` bytes, terminated with `H`. **[Gerät]** Examples are `nopaper\x00\x00\x00H`, `scanready\x00H`, `dpistd\x00\x00\x00\x00H` and `scango\x00\x00\x00\x00H`. **[Gerät]**

Answers are therefore always compared by prefix; no fixed length or particular padding is assumed. **[Gerät]** The Windows app and s400w compare the same way. **[App/s400w]**

The `version` answer does not follow this 11-byte schema: the reference sends `NB0a.032\x00`, i.e. 9 bytes without a trailing `H`. **[Gerät]** Whether the real device sends it the same way or the reference is imprecise at this point is unresolved — see OF-07 in `docs/internal/offene-fragen.md`. **[offen]** The prefix comparison therefore assumes no fixed length and no particular padding (SC-03). **[App/s400w]**

The `jpegsize` answer is 12 bytes long (`jpegsize` prefix plus uint32 little endian) and is read across several read operations as needed until all 12 bytes are present. **[Gerät]**

The `jpegdata` answer delivers exactly `size` bytes of JPEG payload, where `size` is the previously read size. **[Gerät]**

## Scan flow

The flow matches the s400w `scan` command without preview; AirScan uses it the same way. **[Gerät]** The steps in fixed order: **[Gerät]**

1. Send `status` — the answer must be `scanready`. **[Gerät]**
2. Send `dpi300` or `dpi600` — the answer is `dpistd` or `dpifine`. **[Gerät]**
3. Send `scan` — the answer is `scango`. **[Gerät]**
4. Send `jpegsize` — timeout 60 s, because the sheet is being fed in that time; several read operations as needed until 12 bytes are present. **[Gerät]**
5. Send `jpegdata` — read exactly `size` bytes, timeout 30 s per read operation. **[Gerät]**

## Pauses and timeouts

s400w waits 200 ms before and after every send. **[App/s400w]** The timeouts are: normal 10 s, image data 30 s per read operation, `jpegsize` 60 s. **[App/s400w]**

The reference code deviates from the 200 ms rule in two places and waits 500 ms: after sending `jpegdata` and after sending `status` in the pure status query. **[App/s400w]** Whether the longer pause is needed or mere caution is unresolved — see OF-08 in `docs/internal/offene-fragen.md`. **[offen]** The longer wait before reading the bulk data is not shortened without measurement. **[App/s400w]**

## Firmware check before DPI switch

s400w only switches DPI when the number after the dot in the version string is ≥ 26. **[App/s400w]** The test device reports `NB0a.032`, i.e. 32, and is thus switchable. **[Gerät]** The version string itself was observed on the device, the rule comes from the app or s400w. **[Gerät]** **[App/s400w]**

With older firmware the scanner stays at 300 dpi; the caller is warned and learns the actually used resolution (SC-07, SC-08). **[App/s400w]**

## Polling

The Mustek app does not poll the status periodically over TCP but only on actions. **[App/s400w]** AirScan polls roughly every 8 s. **[App/s400w]** How more frequent polling affects the device is unknown. **[offen]** Open points on this — whether polling prevents auto-off (OF-01), whether a 3 s rhythm triggers `devbusy` (OF-02) — are in `docs/internal/offene-fragen.md` and will be settled with `measure`. **[offen]**

## Scope

Image data (JPEG format, dimensions, background, file size) and cropping do not belong here. **[Scan]** They were observed on real scans and are pinned elsewhere with the test images (TE-02). **[Scan]** Device, WLAN and measurement values belong in `hardware.md` (DO-02). **[Handbuch]** The paperless-ngx upload interface belongs to the output module, not to the scanner protocol. **[App/s400w]**
