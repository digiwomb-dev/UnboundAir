# UnboundAir

Turns a Mustek iScan Air (S400W) into a "load and done" scanner: feed a sheet, the service scans by itself, crops the black border losslessly, joins several pages into one PDF and hands it to an output module. The first module uploads to [paperless-ngx](https://docs.paperless-ngx.com/).

No button presses, no manufacturer software, no Windows application. Kotlin and Spring Boot, running as a container.

[![Nightly](https://github.com/digiwomb-dev/UnboundAir/actions/workflows/nightly.yml/badge.svg)](https://github.com/digiwomb-dev/UnboundAir/actions/workflows/nightly.yml)

> **Under construction – v1 is not finished yet.** An overview is below under ["Status"](#status); what is being worked on right now is in the [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) and [Issues](https://github.com/digiwomb-dev/UnboundAir/issues) – first-hand, instead of going stale here.

## What it should do

1. Switch the scanner on, the machine joins its WLAN.
2. Feed a sheet – the service notices and scans without further action.
3. Further sheets within a time window belong to the same document.
4. Window expired or scanner off: build the PDF and hand it to the configured output modules.

Two things are non-negotiable: **nothing is ever recompressed** – cropping and grayscale run exclusively through `jpegtran`, the JPEGs travel into the PDF unchanged. And **nothing about the protocol is invented**: what is not known about the device is built configurable and kept in `docs/internal/offene-fragen.md` instead of being guessed.

## What it does

- **Scanning without button presses:** the service polls the device regularly, notices a fed sheet and scans by itself.
- **Lossless cropping:** the device's black border drops away via `jpegtran` – without recompressing the JPEG.
- **Grayscale, colour or 1-bit black and white** (`gray`, `color`, `bw`). The first two are lossless; `bw` expressly is not and needs `jbig2`.
- **Multi-page PDFs:** pages fed within a time window land in one document. Window expired or scanner off: the PDF is built.
- **Outbox with retries:** finished documents sit on disk until a module has accepted them – neither a restart nor an unreachable destination loses anything.
- **paperless-ngx as output module,** through an interface further modules can dock onto.
- **Running as a container,** for `linux/arm64` and `linux/amd64`.

## Prerequisites

- A **Mustek iScan Air S400W** – none of this transfers to other devices.
- A **machine holding the scanner's WLAN** (host), with a **container runtime** (Docker or Podman).
- A **paperless-ngx instance with API token** – the only output module in v1. Without a token the service does not start.
- For `color-mode = bw` additionally `jbig2`; it is included in the container.

What to set up on the host – WLAN profile, packet filter, volume, secret – is in [`docs/en/operations.md`](docs/en/operations.md).

## Status

| Running | Not built yet |
|---|---|
| Scanning, lossless cropping, grayscale/colour/1-bit | Web UI |
| Multi-page PDFs, time window, batch completion | Rotation and straightening |
| Outbox with retries across restarts | `normalize` (SV-04, deliberately open) |
| paperless-ngx upload | further output modules |
| Container images for `arm64` and `amd64` | Native image |

## Quick start

Prerequisites see above. Pull the image and start it — `nightly` runs the development state; releases carry versions (`1.2.0`, plus `latest` except for pre-releases):

```sh
docker run --network=host \
  -v unboundair-outbox:/var/lib/unboundair/outbox \
  -v /path/to/tokenfile:/run/secrets/paperless-token:ro \
  -e UNBOUNDAIR_OUTPUT_MODULES=paperless \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org \
  -e UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token \
  -e TZ=Europe/Berlin \
  ghcr.io/digiwomb-dev/unboundair:nightly \
  run
```

Adjust address, token file and timezone. Scanner address only on deviation from `192.168.18.33` (`UNBOUNDAIR_SCANNER_HOST`).

Feed a sheet – the service scans by itself, waits briefly for further pages and drops the finished PDF into paperless-ngx (visible there and in the container log on stdout). If nothing arrives, [`docs/en/operations.md`](docs/en/operations.md) helps with operation — it also holds the complete examples (Compose file, Quadlet).

Otherwise: [`docs/en/configuration.md`](docs/en/configuration.md) for every setting, [`docs/en/operations.md`](docs/en/operations.md) for real operation, [`docs/en/development.md`](docs/en/development.md) for building and testing.

## Building yourself

Whoever wants to change instead of run: build the image yourself — how is in [`docs/en/development.md`](docs/en/development.md).

## Guide through the documentation

The docs are in English and German. Depending on what you are up to:

| You want to … | Read |
|---|---|
| know what gets built and why | [`docs/internal/plan.md`](docs/internal/plan.md) (German only) – mission, firm decisions, all requirements with IDs and acceptance criteria |
| see the current state | [GitHub issues](https://github.com/digiwomb-dev/UnboundAir/issues) and [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) – open tasks, what is in progress and what is done |
| build and test yourself | [`docs/en/development.md`](docs/en/development.md) – dev container, build, test run |
| look up a setting | [`docs/en/configuration.md`](docs/en/configuration.md) – every setting with default, environment variable and meaning |
| understand or write an output module | [`docs/en/output-modules.md`](docs/en/output-modules.md) – the module interface, the chain through the outbox, the paperless module and the guide for a module of your own |
| know how testing works | [`docs/internal/teststrategie.md`](docs/internal/teststrategie.md) (German only) – the eight test layers, the tools per layer and the reasons |
| know why something was decided so | [`docs/internal/entscheidungen.md`](docs/internal/entscheidungen.md) (German only) – reasons for the firm decisions, including the measured numbers |
| know what is still unclear about the device | [`docs/internal/offene-fragen.md`](docs/internal/offene-fragen.md) (German only) – open points with status, provenance and how the code deals with them |
| know what the scanner sends over the wire | [`docs/en/protocol.md`](docs/en/protocol.md) – the TCP protocol on port 23: messages, flows, what was measured and what is still open |
| know what the hardware can and cannot do | [`docs/en/hardware.md`](docs/en/hardware.md) – device, WLAN behaviour, measured scan properties |
| run the service as a container | [`docs/en/operations.md`](docs/en/operations.md) – host prerequisites, network, volume, secrets, shutdown, logs |
| contribute to the project | [`CONTRIBUTING.md`](CONTRIBUTING.md) – language, commits, git flow, issue convention, guardrails |
| report a security hole | [`SECURITY.md`](SECURITY.md) – the private reporting route, token handling, scope |

## Why it exists

The scanner is WLAN-only and speaks its own undocumented TCP protocol on port 23. The bundled Windows application wants clicks for every page; SANE and eSCL are no help because the device speaks neither.

The protocol knowledge comes from our own analysis on the device, from the manual and from [AirScan](https://github.com/markosjal/AirScan), which holds the CC0-licensed s400w implementation. Only protocol knowledge was taken from it, no code – and no manufacturer code.

## State of the art

- Kotlin, Spring Boot, Gradle with Kotlin DSL
- OpenPDF for PDF generation (Apache PDFBox only as independent verifier in tests)
- `jpegtran` from libjpeg-turbo for lossless image operations
- Runs as a container; automated tests run offline against a fake scanner

The exact versions are in `docs/internal/plan.md` under "Feste Entscheidungen".

## Licence and provenance

UnboundAir is under the Apache License 2.0 – see [`LICENSE`](LICENSE).

On the provenance of the protocol knowledge: s400w is CC0-licensed, only protocol knowledge was taken from it, no code. AirScan is named as a source. The repository holds no manufacturer code: no Mustek binaries, no installers.

