---
title: Troubleshooting
---

<!-- translated from docs/de/troubleshooting.md @ bc401b7464eac974ad10fdf34794e155cdc2c615 -->


What can go wrong, how to recognise it and what to do then (DO-20). Every entry names an **observation**, its **cause** and the **remedy**.

**The log on stdout is the instrument.** In v1 there is no web interface and no health endpoint; what the service does is in the container log (KL-02). So first: what it looks like when everything is right.

## What a healthy run looks like

**On start two lines arrive:**

```
WARN  [UnboundAirApplication] service started
INFO  [ScanLoop] scanner is reachable again
```

That the first carries `WARN` is not an alarm but the sink: the messages of all commands run through one shared channel (SV-02), and that channel writes at this level.

**The second line says "again" although nothing preceded it** — even on the very first start. This is no hint of a missed outage: the first observation always counts as a state change, so that a service starting against a switched-off device says so at once instead of staying silent (DL-02). If the scanner is off at start, the line reads `scanner is offline, slowing down to PT10S` accordingly.

What is missing, by contrast, is deliberate — no banner, no "Started in 0.4 seconds" line: the framework's startup messages are switched off, because on a command line tool they push the actual answer off the screen (KL-02). So if it stays quiet after these two lines, nothing is stuck.

Silence is **normal** afterwards: the service asks the scanner for its status every three seconds and writes nothing about it, because one line per request would make the log unreadable. Something is written only on a state change (DL-02).

For each scanned page one line arrives with scan duration, transfer duration, size and the dimensions in millimetres after cropping:

```
INFO  [ScanLoop] page 1 scanned in 8123 ms, transferred in 2311 ms, 1048576 bytes, 206.9 x 291.3 mm
```

Two things about it are deliberate. **No timestamp and no PID** — journald and every container runtime stamp each line anyway, and doing it twice would only be wider. And the **millimetres carry one decimal**, because they come from pixels ÷ DPI, with no conversion to a standard format (SV-05).

**The millimetres above are measured on a real A4 sheet, not calculated:** 206.9 × 291.3 instead of 210 × 297. The durations and the file size in the example line, by contrast, are plausible placeholders — how long a scan takes has not been measured systematically yet (OF-03). Scanned pages come out smaller than the paper that was fed, and why is open — OF-05 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) (German only). Anyone expecting nominal dimensions here is looking for a defect that does not exist.

Once the document is finished and delivered, `document … delivered to the output modules` follows, and with the paperless module additionally `paperless-ngx accepted the document; consumption task …` with the task ID from the response (AU-05).

**This is what distinguishes silent-and-working from silent-and-stuck:** if no `page` line appears when a sheet is fed, the service is not scanning. If it appears but no `delivered` follows, the output is where it hangs.

## The service runs but never finds the scanner

**Observation:** no `page` line when a sheet is fed. The log contains **exactly once** `scanner is offline, slowing down to PT10S` and nothing after that. `status` reports an error instead of a status.

**Cause:** either the **host** is not on the scanner's WLAN, or the **container** is not using the host's network. Both look the same in the log, because for the service an unreachable scanner is a regular state and not an error (DL-02): it keeps asking at a slower pace and finds its way back by itself as soon as the device answers — then `scanner is reachable again` appears. That is exactly why the log is so quiet here: one line per state change, not per failed attempt.

**Remedy,** in this order:

1. Is the host holding the connection? The WLAN to the scanner is held by the host, not the container — section 1 in [`operations.md`](operations.md).
2. Is the container sharing the host's network? With a bridge network the container starts and never finds the device — section 2 in [`operations.md`](operations.md).
3. Does the device answer at all? [`cli.md`](cli.md) describes `status`, the command that does nothing but ask.

**Not measured systematically yet, and said plainly here:** with a running service the device stayed reachable past the five minutes — so the 3-second status poll apparently does keep it awake. That is an observation at the device, not a measurement run: OF-01 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) (German only) therefore stays recorded as `beobachtet`, not as settled. So if the device does go offline after a period of quiet, that may be no fault at all.

## A setting has no effect

**Observation:** an `UNBOUNDAIR_…` variable is set, but the service behaves as if it were not. No error message, no hint in the log.

**Cause:** the spelling rule from KL-01. Every dot becomes an underscore, **every hyphen disappears without replacement**. `UNBOUNDAIR_POLL_INTERVAL` is therefore not a typo that Spring reports, but a name it does not know — the setting silently stays on its default.

**Remedy:** `UNBOUNDAIR_POLLINTERVAL`. The complete mapping of every setting is in [`configuration.md`](configuration.md).

This is the most expensive trap in this project, because it costs nothing but time: there is no message by which to recognise it. Whoever changes a setting and sees no effect checks the variable name first.

## The service does not start at all

**Observation:** the container ends immediately with one of the three messages:

```
paperless: no token configured -- set the token or the path to a token file (AU-05)
paperless: token file /run/secrets/paperless-token is not readable; check that it exists and is accessible (AU-05)
paperless: token file /run/secrets/paperless-token is empty; the file must contain the token (AU-05)
```

**Cause:** the token is neither set directly nor present as a readable, non-empty file (AU-05). That the service then does not start at all is deliberate and not a hardship case — which source wins over which, and why, is under "Token-Auflösung" in [`configuration.md`](configuration.md).

**For troubleshooting the second and third message matter most:** a mounted token file wins even against a token set beside it. So if the file is not mounted or is empty, an `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN` standing next to it does not help — the start aborts anyway. This is the most common stumbling block when switching from the variable to a secret.

**Remedy:** check that the file really is at the path `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` names inside the container, and that the service user may read it. The message names the path — and deliberately never the content, so that no part of the secret reaches a log.

## Documents pile up and never arrive

**Observation:** `page` lines arrive, `document … delivered …` does not. The outbox holds directories that do not disappear.

**Cause:** the target does not accept them — paperless is unreachable, the address is wrong, or the token is no longer valid.

**Remedy:** repair the target. **Nothing is lost:** the outbox keeps every document until a module confirms receipt, and retries at a growing interval — first after 30 seconds, then twice as long each time, capped at one hour, an unbounded number of attempts (AU-04). Once the target is back, the backlog drains by itself.

**One exception that counts:** without a persistent volume on the outbox path, everything not yet delivered is gone after a restart. That is why [`operations.md`](operations.md) requires the volume, and not as a recommendation.

## `color-mode = bw` fails

**Observation:** PDFs are created in `gray` and `color`, but not with `bw`. The log contains `closing the batch failed: …` with a message about the `jbig2` program.

**Cause:** the `jbig2` program is missing or failing. A 1-bit page reaches the PDF as JBIG2 (SV-08), and only this external program encodes that — the encoder is never reimplemented. The program is called `jbig2`, not `jbig2enc`; the latter is the name of the source package.

**Remedy:** the container image shipped with the project contains `jbig2` — there this does not occur. Outside the image it is a separate package that has to be installed.

**The pages are not lost in the process.** A failed close leaves the batch open and does not end the service: the pages stay, the loop keeps polling. Once the program is installed, the next trigger closes the batch including the pages already scanned.

## A page is not cropped

These are **two different things**, and they look similar in the result.

**No black border found.** The log contains `no paper found in …, keeping the page uncropped`. This is **normal device behaviour** and not a fault: the scanner does not always produce a border, and for this case SV-01 explicitly requires the file to be carried through byte-identically. An uncropped page is then the correct result.

**The crop was refused.** The log contains `implausible paper box 412x3380 px in …, keeping the page uncropped` — with the dimensions of the rejected box. The plausibility check from SV-02 rejected a result because the box found covered less than 10 % of the image area or was more than 6 times longer than wide, and left the page whole rather than ruining it. These two thresholds are fixed in the code and explicitly not settings — so there is no knob to turn here.

**The pixel dimensions named are the clue:** they show *what* was detected. A very narrow or very small box usually means paper and background could not be separated — for instance with a very dark original.

**Remedy** in the second case: feed the original brighter or straighter and scan again. If the correct crop is recognisable by hand, the page can be reworked with `crop` — the command needs no scanner ([`cli.md`](cli.md)). For the comparison `--keep-raw` helps: then the raw image sits next to the result.

**Not to be confused with the page size:** a PDF only 206.9 mm wide for A4 has no crop defect — see "What a healthy run looks like" above and OF-05. A crop problem shows up as a visible black border or as one of the two log lines, not as millimetres below the nominal format.

## The scanner switches off mid-stack

**Observation:** after a pause in the stack the document is suddenly finished, and the sheets fed afterwards land in a new one.

**Cause:** the device went offline. For the service this is a **regular trigger** to close the batch, and not a fault (DL-04): offline means "the operation is over", so the PDF is built and handed over.

**Why it went offline is not clear in every case.** According to the manual the device switches off after about five minutes without action. Whether the service's 3-second status poll counts as "action" and prevents that is **not measured systematically** — OF-01 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) (German only). With a running service the device was kept awake past the five minutes; nobody should rely on that, though, because the more obvious cause is usually the simpler one: the device was switched off by hand, the battery was flat, or the Wi-Fi was gone.

**Remedy:** none needed — the document is complete. If more pages are to go into one document, they must be fed within the batch timeout window (`unboundair.batch-timeout`, default 20 seconds, see [`configuration.md`](configuration.md)).

**On shutdown the same thing happens deliberately:** the open batch is still completed (DL-07). For that the container runtime's stop timeout must suffice — a running scan can take up to 60 seconds, and a timeout that is too short cuts the batch off instead of closing it. The number is in [`operations.md`](operations.md).

## A page is missing from the document

**Observation:** the PDF has one page fewer than sheets were fed. The log contains `page discarded: …`.

**Cause:** that page failed while scanning or processing. It is discarded and logged, the batch stays open (DL-05) — a broken page ends no document.

**Remedy:** feed the sheet again. As long as the batch timeout window is still running, it lands in the same document.

## The page is cropped at the edges or has a wide white margin

**Observation:** the scan is cropped on the sides, or it sits with a wide white margin on the page — although the template and the set format should actually match.

**Cause:** the set page size (`page-size`) does not match the template. With a target dimension the page box is set to that dimension and the scan is **centred unscaled** inside — it is neither fitted nor stretched (SV-09). If the box is smaller than the scan, it protrudes and is not visible there; if it is larger, the rest stays empty.

**Remedy:** set the matching format, name a free dimension in millimetres, or choose `off` — then the scan's own size from pixels and DPI applies again (SV-05). Which values `page-size` knows is in [`configuration.md`](configuration.md).

**Not an error, but expected:** an A4 scan never fills an A4 box exactly. The device delivers smaller dimensions than the fed paper (A4 measures about 206.9 × 291.3 mm instead of 210 × 297 mm) — observed, cause unknown, see OF-05 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) (German only). A narrow margin around the scan on an A4 box is therefore normal and not a defect.

## A sheet fed sideways stands upright — or does not fit in the feeder at all

These are **two different things**, and only one of them can be set.

**Upright instead of sideways is intentional.** **Observation:** a sheet fed sideways stands upright on the page, with a wide margin above and below. **Cause:** the set format applies as written — the scan is centred unscaled inside, never rotated and never fitted (SV-09). Rotating is deliberately not part of v1 (see „End-Seitengröße einstellbar, unskaliert zentriert (SV-09)" in [`../internal/entscheidungen.md`](../internal/entscheidungen.md) (German only)); in the paperless path, paperless takes over straightening via OCRmyPDF. **Remedy:** explicitly choose a landscape box — the `a6-landscape` preset or a free dimension such as `210x148mm` — or `off` for the scan's own size (SV-05).

**Does not fit through the feeder, no setting helps.** **Observation:** a wide sheet cannot be fed in at all. **Cause:** this is a device limit, not a setting: the observed image width ends at about 208.6 mm (A4 sheet, approx. 2464 px at 300 dpi) **[Scan]** — measured value in [`hardware.md`](hardware.md). An A4 sheet sideways (297 mm) never reaches the PDF on account of the hardware. **Remedy:** none — what does not pass the feeder reaches no setting. **To be honest:** about 208.6 mm is the observed image width from 1–2 scans, not a measured paper limit of the feeder. Whether an A5 sheet sideways (210 mm) still passes is unmeasured — nothing is claimed in any direction here.

## What this page does not answer

**Behaviour at low battery.** The answer `battlow` is known from the reference app but has never occurred on the device — OF-10 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) (German only). Whether the device still scans then, whether the message comes once or continuously, and whether a scan aborts midway is unmeasured. The path is built and tested (SC-05), but it is not described here — what is not measured is not claimed in this project.

**Frequent `devbusy` answers.** Whether the device takes the three-second pace without complaint is open — OF-02 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) (German only). `devbusy` does not lead to an abort; should it occur frequently, `unboundair.poll-interval` is the lever.

Both points are clarified by the `measure` command, which exists for exactly that ([`cli.md`](cli.md)).
