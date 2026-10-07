---
title: Operations
---

<!-- translated from docs/de/operations.md @ f9a5f790388ce3797548136df941dd37cab19c6b -->

# Operations

The service runs as a container (DO-03). This file describes what the host and the container must provide for that — independent of any particular container runtime. Commands below are examples, each marked as such.

> **Status note (milestone 5):** Where container images are published to (registry, image name) is not decided yet; a concrete deployment example arrives with milestone 6. Where an image name would stand below, a placeholder stands instead.

## 1. Host prerequisites: scanner WLAN, NetworkManager, nftables

The host — not the container — keeps the WLAN connection to the scanner. The scanner opens its own WLAN; the host joins it, and the container shares the host's connection (see "Network").

**NetworkManager profile for the scanner WLAN.** The profile is bound to the WLAN interface toward the scanner and reconnects automatically — without a retry limit — without bending the host's default route:

- bound to `wlan0`
- `connection.autoconnect yes`
- `connection.autoconnect-retries 0`
- `ipv4.never-default yes`

`never-default` is what holds it all together: the scanner hands out addresses from a private network via DHCP, but the way to the internet stays on the host's other interface. Without this setting, every connection to the scanner would steal the default route.

**Packet filter (nftables) on `wlan0`.** The scanner WLAN is a foreign network, allowed nothing beyond the necessary:

- inbound: only `established`/`related` plus DHCP
- outbound: only `192.168.18.33:23` (the scanner: address and port per SC-06) plus DHCP
- no forwarding

Exactly one thing can happen on this interface: the service talks to the scanner, and DHCP brings along the address for it. Everything else stays out.

## 2. Network: the container reaches the scanner through the host

The container must be able to reach `192.168.18.33:23` over the host's WLAN. What exactly to configure depends on the container runtime — the requirement is runtime-neutral: the container shares the host's network access such that this address with this port is reachable.

One way there is the runtime's host network (e.g. with `… --network=host …` as an example, analogously for other runtimes). What counts is not the option but the result: from inside the container, `192.168.18.33:23` is reachable as long as the host itself sits in the scanner WLAN. If the host's WLAN connection drops, the scanner is offline from the service's point of view — a regular state (DL-02, DL-04), not an error.

## 3. Persistence: the outbox lives on a durable volume

Finished documents land in the outbox first (`unboundair.outbox.path`, default `/var/lib/unboundair/outbox`) before an output module accepts them — deleted only after success (AU-04). The outbox must therefore live on a persistent volume, e.g. as a named volume or as a mounted host path mapped onto the outbox path in the container (concrete option per runtime, same mapping in spirit).

Without this volume, every restart loses all not-yet-delivered documents: what sat in the outbox but no module had accepted is gone after the restart. With a volume the outbox survives the restart, and delivery resumes (AU-04).

## 4. Configuration and secrets

**Reference: [`configuration.md`](configuration.md).** This file deliberately does not repeat the settings table: every setting with default and environment variable lives there (DO-09, KL-01), and only there — a second table would drift with no test watching it.

Inside the container, settings go through `UNBOUNDAIR_…` environment variables. The KL-01 spelling applies with its pitfall: every dot becomes an underscore, every hyphen is dropped without replacement. `unboundair.poll-interval` as a variable is thus `UNBOUNDAIR_POLLINTERVAL` — `UNBOUNDAIR_POLL_INTERVAL` (underscore instead of dropped hyphen) is silently not recognised by Spring, and the setting simply stays on its default. The full mapping is in [`configuration.md`](configuration.md).

**Secrets belong neither in plain-text environment variables nor in files in the image.** The paperless token arrives in the container as a mounted file; its path is announced through the `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` environment variable (AU-05, KL-01). The file is thus mounted through a secret mechanism of the respective runtime (example: a secret or file mount onto the path the variable names); the service reads the token from there. How token and token file interact — file wins when set; without either, the service does not start at all — is described under "Token resolution" in [`configuration.md`](configuration.md).

## 5. Stopping: the open batch is still completed

On shutdown (SIGTERM/SIGINT) the service still completes the open batch and hands it to the outbox (DL-07). What that means: a batch already holding pages is finished as a document and stored in the outbox instead of being discarded. Outbox delivery to the modules continues afterwards for as long as the process lives — what is no longer delivered during shutdown stays behind thanks to the volume and is delivered after the restart.

**Operational consequence: the container runtime's stop timeout must cover this.** A running scan can take up to 60 s (the `jpegsize` timeout per SC-02) — only then is the page complete and the batch closable. With a shorter stop timeout the runtime kills the container early, and the open batch is cut off instead of completed. So: choose a stop timeout generously above 60 s (e.g. as an example `… --stop-timeout=90 …` or the corresponding setting of the runtime in use), so a running scan can still finish.

## 6. Logs, restart, update

**Logs** go to stdout (KL-02, journald-friendly). Per page they carry scan duration, transfer duration, size and dimensions in mm after cropping, among others; a paperless upload's task UUID (AU-05) is in the log as well. Nothing is written into files in the container that would need backup — the runtime collects the logs from stdout (example: `… logs …` of the runtime in use).

**Restart:** the service is built for restarts. The outbox on the volume (see "Persistence") makes sure undelivered documents are retried after the restart — with growing intervals (start 30 s, doubling, capped at 1 h, unlimited attempts) and nothing to do about it. The runtime should restart the container on crash (example: a restart policy like "restart on failure"); polling then finds its way back into the scanner state by itself (DL-01, DL-02).

**Update:** rolling out a new image means starting a new container with the same settings — the same `UNBOUNDAIR_…` variables, the same token file mount, the same volume on the outbox. Order out of consideration for the open batch: first stop the old container cleanly (with the stop timeout from "Stopping", so the batch still reaches the outbox), then start the new one. The outbox on the volume makes the update lossless: what the old container had not yet delivered, the new one delivers.
