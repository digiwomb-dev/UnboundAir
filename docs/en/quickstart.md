---
title: Quickstart
---

<!-- translated from docs/de/quickstart.md @ 7778b4a8869530cd4dd79c9d39c9f7ea84c00719 -->


From nothing to the first PDF in paperless-ngx (DO-18). This page gets **one** path working — not clean continuous operation. What belongs to real operation is in [`operations.md`](operations.md), and the end of this page says when to read on there.

## 1. What you need

- A **Mustek iScan Air S400W**. None of this transfers to other scanners — the device speaks its own protocol, which was reimplemented in this project.
- A **machine on the scanner's WLAN**, with a **container runtime** (Docker or Podman).
- A **paperless-ngx instance with API token**. In v1 this is the only output module; without a token the service does not start.

**The one prerequisite that bites first most often:** the WLAN connection to the scanner is held by the **host**, not the container. The scanner brings up its own WLAN, the host connects to it, and the container shares that connection. That is why `--network=host` appears below — with a bridge network the container starts and never finds the device.

## 2. Put the token in a file

```sh
printf '%s' 'TOKEN-HIER-EINSETZEN' | install -m 600 /dev/stdin /srv/unboundair/paperless-token.txt
```

A file rather than an environment variable, because that is the form the rest of the documentation uses — and because a token in a variable appears in every process list and every `inspect`. `printf` instead of `echo`, so no line break slips in.

## 3. Start the container

```sh
docker run --network=host \
  -v unboundair-outbox:/var/lib/unboundair/outbox \
  -v /srv/unboundair/paperless-token.txt:/run/secrets/paperless-token:ro \
  -e UNBOUNDAIR_OUTPUT_MODULES=paperless \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token \
  -e TZ=Europe/Berlin \
  ghcr.io/digiwomb-dev/unboundair:nightly \
  run
```

**What you must adjust:** the address of your paperless instance, the path of the token file left of the colon, and the timezone. The scanner address only if it deviates from `192.168.18.33` (`UNBOUNDAIR_SCANNER_HOST`).

**What the lines that do not look like configuration do:** `--network=host` gives the container the host's WLAN connection. The volume on the outbox is the reason a restart loses no documents — without a persistent volume everything not yet delivered is gone. And `run` at the end is the command: without it there is only the help text and exit code 1, because no command does not mean "take the service" ([`cli.md`](cli.md)).

**The `nightly` tag is the development state.** Releases carry versions (`1.2.0`, plus `latest` except for pre-releases).

**A trap that costs nothing but time:** in the `UNBOUNDAIR_…` variables **every hyphen disappears without replacement**. `UNBOUNDAIR_POLL_INTERVAL` is silently ignored, the correct form is `UNBOUNDAIR_POLLINTERVAL`. There is no error message for it — the complete rule is in [`configuration.md`](configuration.md).

## 4. Feed a sheet

Now there is nothing more to do: the service asks the scanner for its status every three seconds, notices the fed sheet and scans by itself. No button press, no manufacturer software.

**Between pages the log is silent** — and that is correct. On start there is one `service started` line, after which exactly one line arrives per scanned page:

```
INFO  [ScanLoop] page 1 scanned in 8123 ms, transferred in 2311 ms, 1048576 bytes, 206.9 x 291.3 mm
```

Scan duration, transfer duration, size and the dimensions after cropping. That an A4 sheet measures 206.9 × 291.3 mm there rather than 210 × 297 is known and not a crop defect ([`troubleshooting.md`](troubleshooting.md)).

## 5. Where it went

After the last page the service waits **20 seconds** for another sheet. If none comes, it builds the PDF and hands it over:

```
INFO  [PaperlessModule] paperless-ngx accepted the document; consumption task 6f2a1c74-9b3e-4d58-9c21-7a5e0f3b8d44
INFO  [OutboxRunner] document 1758545700123 delivered to the output modules
```

After that the document is in paperless, as `scan-20260922-143500.pdf`. The `consumption task` is paperless' own processing — that carries on there for a moment. The long number is the identifier in the outbox; it is the document's start time in milliseconds and appears only in the log and in the file system.

**The batch timeout window is the whole trick with multi-page documents:** every sheet fed within those 20 seconds after the last page belongs to the **same** PDF. Whoever wants a ten-page document feeds ten sheets briskly one after another. Whoever wants two separate documents waits in between. The duration is configurable (`unboundair.batch-timeout`), and it is provisional — the final value comes from a measurement on the real device.

If the scanner switches off in between, that closes the document as well. This is not a fault but the intended end of an operation.

## From here on

This path works — but something is still missing for continuous operation.

- **[`operations.md`](operations.md)** — what must hold on the host: a WLAN profile that does not bend the default route, the packet filter, a stop timeout so a running scan still finishes on shutdown, and updates. **That is the page you need next** once this has worked.
- **[`configuration.md`](configuration.md)** — every setting with its default and environment variable. This page deliberately carries no settings table: a second one would drift apart.
- **[`troubleshooting.md`](troubleshooting.md)** — when nothing arrives, a setting has no effect, or the service does not start at all.
- **[`cli.md`](cli.md)** — the other four commands, such as `status` to check the connection and `crop` to crop without a device.
