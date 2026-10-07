---
title: Hardware
---

<!-- translated from docs/de/hardware.md @ 4272faea778f35a3115dd424971f44e687fab56a -->

# Hardware: Mustek iScan Air (S400W)

The device itself: what the scanner physically can do, which values were measured on it and which quirks it shows. Its counterpart is `docs/de/protocol.md`: that file tells **how** to talk to the device (commands, flow, answer formats); here stands **what** is talked to. Where the two touch, they reference instead of repeating.

Basis is the state from `_input/iscan-air-wissen.md` (local only, never committed), carried over here in our own words.

## Provenance of statements

Every statement carries its provenance. The markers are the same as in `protocol.md`:

**[Gerät]** verified on the real device · **[App/s400w]** from the Windows app or the s400w implementation, not yet tested on the device · **[Handbuch]** from the S400W manual · **[Scan]** observed on 1–2 real scans · **[offen]** still unknown, awaiting measurement.

Without these markers it would be unrecognisable which values were measured and which merely taken from the manual — which is why they stand with every statement, not just disputed ones.

## The device at a glance

- Single-sheet feed: every sheet is fed by hand **[Handbuch]**.
- WLAN-only: operation runs over WLAN; the mini-USB socket only charges, it transfers no data **[Handbuch]**.
- Output format exclusively JPEG **[Handbuch]**.
- Two resolutions: 300 and 600 dpi **[Handbuch]**.
- **No duplex:** the complete command set holds no command for the back **[App/s400w]**. Whoever wants both sides flips the sheet and feeds it again.

## WLAN and connection

- SSID `DIRECT-xxxxxx_iScanAir`, where `xxxxxx` is a device-specific 6-digit code; WPA password `12345678` **[Handbuch]**.
- The scanner hands out addresses from `192.168.18.0/24` via DHCP **[Gerät]** (on the test device the client received `192.168.18.1` **[Gerät]**).
- The Windows app checks exactly that: the SSID starts with `DIRECT-`, the own IP starts with `192.168.18` **[App/s400w]**.
- Up to 8 clients at once, range approx. 18–30 m **[Handbuch]**.
- The scanner's own address and port (default `192.168.18.33`, port `23`) and the connection behaviour belong to the protocol — see `protocol.md`.

## Power on and energy

- Power on: hold the POWER button approx. 3 s, then wait approx. 20 s until the LED blinks blue **[Handbuch]**.
- Permanently blue LED means: a client is connected **[Handbuch]**.
- Auto-off: without action the device switches off after 5 minutes **[Handbuch]**. Whether a running status poll counts as "action" and prevents switch-off is **not** measured **[offen]** — see OF-01 in `docs/internal/offene-fragen.md`.
- Low-battery behaviour: the `battlow` answer is known from the app but was never triggered on the device **[App/s400w]**. Whether the device still scans then, whether it sends the message once or permanently and whether a running scan aborts is **not** measured **[offen]** — see OF-10 in `docs/internal/offene-fragen.md`.

## Scan properties and image data

Observed on real scans (1–2 originals each, no statistics):

- JPEG at 300 dpi, contained in JFIF and EXIF headers, chroma subsampling 4:2:2 (iMCU 16×8), quantisation tables per the IJG standard for quality 50 **[Scan]**.
- Image width depends on the original: A4 yields approx. 2464 px (approx. 208.6 mm at 300 dpi), a narrow paper approx. 1776 px (approx. 150.4 mm); the paper sits right-aligned **[Scan]**.
- The background is near black (luma approx. 2–6), the paper sits at approx. 210–220; a black trailer of approx. 12–18 mm follows at the end, partly also approx. 4 mm at the start **[Scan]**.
- File size as a rule of thumb: an A4 letter at 300 dpi sits at approx. 0.9 MB **[Gerät]**.
- Measured after cropping: A4 approx. 206.9 × 291.3 mm, DL envelope approx. 103.0 × 211.2 mm **[Scan]**. Both values deviate from the paper size — whether the scanner cuts, the feed compresses or the DPI figure does not match the optics is **not** settled **[offen]** — see OF-05 in `docs/internal/offene-fragen.md`.
- No measured values for 600 dpi: duration, file size, header entry and possibly deviating subsampling are **not** measured **[offen]** — see OF-06 in `docs/internal/offene-fragen.md`.

## Known quirks of the device

Behaviour coming from the device that is no protocol error:

- **Padding bytes in answers:** the device appends null bytes and an `H` to short answer words. That is why the client compares answers by prefix — details and byte examples are in `protocol.md`, not here.
- **Page sizes deviate from the paper size** (see above, OF-05) **[Scan]**.
- **`devbusy`:** the device can answer a status query with `devbusy`; the answer is known from the app **[App/s400w]**, but how often it comes with regular polling is **not** measured **[offen]** — see OF-02 in `docs/internal/offene-fragen.md`.
- **Low battery** (`battlow`, see above, OF-10) **[App/s400w]**.

Protocol questions close beside it (answer lengths, pauses, double scans) belong in `protocol.md` or OF-04, OF-07 and OF-08 in `docs/internal/offene-fragen.md` and are not kept here.

## Yet to measure: gaps for `measure`

The `measure` command (BE-04) measures exactly these gaps on the real device. Until systematic measurement the following fields stay empty; invented numbers deliberately stand not here. Per entry it is noted which summary line of `measure` fills it and where the question is kept.

| Quantity | State today | Fills later | Question |
|---|---|---|---|
| Does status polling keep the scanner awake? | unknown **[offen]** (auto-off 5 min **[Handbuch]**) | `went offline: … after … of quiet` | OF-01 |
| `devbusy` frequency at 3 s rhythm | unknown **[offen]** (answer known **[App/s400w]**) | `devbusy answers: …` | OF-02 |
| Scan duration per page and gap between two pages | unknown **[offen]** (only file size approx. 0.9 MB **[Gerät]**) | `gaps between pages` (mean, min, max) plus duration per page in the page log line | OF-03 |
| Page size deviation (cutting, compression or DPI error?) | observed, cause unknown **[Scan]** | remeasure with ruler and reference | OF-05 |
| 600 dpi: duration, file size, header, subsampling | unknown **[offen]** (command known **[App/s400w]**) | 600 dpi scan on the real device | OF-06 |
| Low-battery behaviour | unknown **[offen]** (answer known **[App/s400w]**) | run the device empty | OF-10 |

The questions themselves — status, background, preliminary defaults — stand exclusively in `docs/internal/offene-fragen.md` and are not repeated here. Settled measurements travel from there to here.

## Sources

- S400W iScan Air manual (among others power-on procedure, SSID, auto-off, clients, range) — named as a source, not reproduced: manufacturer manual, available e.g. via manualslib.
- Own observations on the device and on real scans (see markers **[Gerät]** and **[Scan]**).
- "iScan Air" Windows app and s400w implementation for everything marked **[App/s400w]** — interface knowledge only, no manufacturer code in the repo.
