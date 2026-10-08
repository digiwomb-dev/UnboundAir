---
title: UnboundAir
description: Turns a Mustek iScan Air S400W into a "load and done" scanner — no button presses, no manufacturer software.
template: splash
hero:
  tagline: Feed a sheet, get a finished PDF in paperless-ngx. No button presses, no manufacturer software.
  actions:
    - text: Quickstart
      link: /UnboundAir/en/quickstart/
      icon: right-arrow
      variant: primary
    - text: Operations
      link: /UnboundAir/en/operations/
      icon: document
      variant: minimal
    - text: Repository
      link: https://github.com/digiwomb-dev/UnboundAir
      icon: github
      variant: minimal
---

<!-- translated from docs/de/index.md @ 0a027978f81c432d2a2a09b4425680d203ca24ae -->


## What it is

A service that turns a **Mustek iScan Air (S400W)** into a "load and done" scanner. Feed a sheet — the service notices, scans by itself, crops the black border losslessly, joins several pages into one PDF and uploads it to paperless-ngx.

No button press on the device, no manufacturer software, no Windows application. Runs as a container.

Two things are non-negotiable: **nothing is ever recompressed** — cropping and grayscale run exclusively through `jpegtran`, the JPEGs travel into the PDF unchanged. And **nothing about the protocol is invented**: what is not known about the device is built configurable and recorded as an open question instead of being guessed.

## Who it is for

For whoever owns **this one device**. The scanner speaks its own undocumented protocol, which was reimplemented here — none of this transfers to other scanners. Anyone with a different model finds no tool here, at most a case study.

Also needed are a machine that holds the scanner's WLAN, a container runtime and a paperless-ngx instance with an API token.

## How far along it is

**v1 is not finished yet.** What works: scanning without button presses, lossless cropping, grayscale/colour/1-bit, multi-page PDFs, the batch timeout window, an outbox with retries across restarts, upload to paperless-ngx, container images for `arm64` and `amd64`.

What does not yet: a web interface, rotation and straightening, further output modules, a native image.

What is being worked on right now is in the [Milestones](https://github.com/digiwomb-dev/UnboundAir/milestones) and [Issues](https://github.com/digiwomb-dev/UnboundAir/issues) — first-hand, instead of going stale here.

## Where to start

- **[Quickstart](quickstart.md)** — from nothing to the first PDF in paperless, in five steps.
- **[Operations](operations.md)** — what must hold on the host if it is to run permanently.
- **[Configuration](configuration.md)** — every setting with its default and environment variable.
- **[Troubleshooting](troubleshooting.md)** — when nothing arrives or a setting has no effect.
