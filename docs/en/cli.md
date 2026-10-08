---
title: Command line
---

<!-- translated from docs/de/cli.md @ 6366896f79672ef934dbfc69a8f8558eaaa7de2b -->


The five subcommands of `UnboundAir` and the options they accept (DO-19, requirements BE-01 to BE-05). This file describes what a command **does** — whether it touches the scanner, what it writes, when it is finished. Which setting carries which default is in [`configuration.md`](configuration.md) and only there.

You invoke either the jar or the container; the command is the same:

```sh
java -jar unboundair.jar status
docker run --network=host ghcr.io/digiwomb-dev/unboundair:nightly status
```

**Without a command there is only the help text and exit code 1.** No command does not mean "take the service", it is an error — which is why both deployment examples in [`operations.md`](operations.md) name `run` explicitly.

## The commands at a glance

| Command | Scanner needed | Writes | Requirement |
|---|---|---|---|
| `status` | yes | status and firmware version on stdout | BE-01 |
| `scan` | yes | the processed page as a file, with `--keep-raw` the raw image as well | BE-02 |
| `crop IN OUT` | **no** | the cropped file | BE-03 |
| `measure` | yes | a summary on stdout; the pages land in a temporary directory and are deleted | BE-04 |
| `run` | yes | PDFs into the outbox, from there to the output modules | BE-05 |

## `status` — is the device alive?

Asks the scanner for its state and its firmware version and writes both to stdout (BE-01):

```
Status: scanready
Firmware: NB0a.032
```

This is the first command for any suspicion that the scanner is unreachable: it needs nothing but the network connection, changes nothing, and answers the question every other command takes for granted. If it answers with an error instead of a status, the problem is in the network and not in the configuration — continue in the "Network" section of [`operations.md`](operations.md).

Status and firmware are fetched over **two** connections, not one: one connection per operation is the rule from SC-02, and the device does not tolerate bundling.

## `scan` — one page, triggered by hand

Scans exactly one page, processes it (crop, then color mode) and writes it as a file (BE-02). Then the command ends — it does not wait for further pages and builds no PDF; that is the job of `run`.

```sh
java -jar unboundair.jar scan --dpi 600 --out page.jpg
```

Without `--out` a name with timestamp and resolution is created, such as `iscan_20260922-143500_300dpi.jpg`. The resolution in the name is the **actual** one, not the requested one: an old firmware forces 600 DPI down to 300 (SC-07, SC-08), and a file name claiming 600 would be a lie. With `--keep-raw` the unmodified scanner image is placed next to it, with `_raw` before the extension (SV-06) — intended for comparison when a crop looks wrong.

**In `bw` the default name ends in `.pbm`,** because the page is then a 1-bit image and no longer a JPEG file. An explicitly given `--out` name, by contrast, stays exactly as it was given — the file belongs to the caller, even when the name does not match the content. The result line always names the path that was actually written.

## `crop` — cropping without a device

Crops an existing JPEG file and writes the result (BE-03):

```sh
java -jar unboundair.jar crop input.jpg output.jpg
```

**The only command that needs no scanner.** That makes it the way to try the crop on an image that already exists — for instance on a raw image kept with `--keep-raw`. Both arguments are mandatory, in this order; if one is missing, the command aborts with a message.

`crop` deliberately ignores `--color-mode`, `--bw-threshold` and `--keep-raw`. It is a pure image operation and must not change the color of a page; those options belong to scanning. If the crop finds no black border, the file is preserved byte-identically (SV-01) — that is not a failure but the second case of the requirement.

## `measure` — measuring the device

Runs the service loop for a limited time and reports what the device did (BE-04):

```sh
java -jar unboundair.jar measure --minutes 10 --poll-seconds 3
```

The summary names the number of pages, the gaps between them (average, shortest, longest), the number of `devbusy` answers, whether and when the device went offline, and conspicuously short gaps as a possible double scan. Each line carries the open question it aims at — OF-01 to OF-04 in [`../internal/offene-fragen.md`](../internal/offene-fragen.md) (German only).

**`measure` hands nothing to an output module.** That is explicitly demanded and not a side effect: a measuring run must not put test scans into a real document archive. The pages are created in a temporary directory that is deleted after the run — they are waste of the measurement, not its result.

This command is the reason the four time values in [`configuration.md`](configuration.md) are marked as provisional: they are estimated, and `measure` delivers the numbers the final ones come from.

## `run` — the service

Continuous operation (BE-05): the loop asks the scanner for its status regularly, scans a fed page by itself, collects pages within the batch timeout window into one document, builds the PDF and places it in the outbox. From there the outbox runner picks it up and hands it to the active output modules.

`run` is the command that stands in a container. It therefore takes its values from the settings rather than from flags — poll interval, batch timeout window, outbox path, modules and paperless access are all in [`configuration.md`](configuration.md). On shutdown with SIGTERM or SIGINT it completes the open batch first (DL-07); what that means for the stop timeout is in [`operations.md`](operations.md).

An unreachable scanner is a regular state for `run`, not an error: the loop then keeps asking at the larger interval and finds its way back by itself (DL-02).

## The options

They apply to the commands where they make sense; the others ignore them.

| Option | Default | Effect |
|---|---|---|
| `--host HOST` | `192.168.18.33` | Address of the scanner (SC-06). |
| `--port PORT` | `23` | Port of the scanner (SC-06). |
| `--dpi 300\|600` | `300` | Resolution for `scan` and `run` (SC-07). |
| `--out FILE` | name with timestamp | Target file for `scan`. |
| `--color-mode gray\|color\|bw` | `gray` | Color mode for `scan` and `run` (SV-03, SV-08). |
| `--bw-threshold N` | `128` | Luma threshold for `bw`, valid `1..255` (SV-08). |
| `--keep-raw` | off | Also stores the raw image for `scan` and `run` (SV-06). |
| `--minutes N` | `10` | Duration of a `measure` run. |
| `--poll-seconds N` | `3` | Poll interval during `measure`. |

**A flag wins over the setting, the setting over the device constant.** This holds for `--host` and `--port` just as for `--dpi`, `--color-mode`, `--bw-threshold` and `--keep-raw`: if the flag is not named, the setting from [`configuration.md`](configuration.md) counts, and only when that is absent too, the built-in default. This order is the reason `run` in a container can reach an address other than the built-in one at all — nobody can type a flag there.

`--minutes` and `--poll-seconds` fall outside this rule: they have no counterpart under `unboundair.*`, because they steer a single measuring run and not operation. That `--poll-seconds` carries the same default value as `unboundair.poll-interval` is deliberate — `measure` is meant to measure the behaviour the service will show.

An unknown option aborts the invocation with a message instead of ignoring it. That is a difference from the environment variables, where a typo silently stays on the default — the trap from KL-01, described in [`configuration.md`](configuration.md).

## Against the fake scanner

Every command that touches the scanner can be aimed at the built-in fake — that is exactly what `--host` and `--port` are flags for:

```sh
java -jar unboundair.jar status --host 127.0.0.1 --port 2323
```

How the fake is started and what it answers is in [`development.md`](development.md). This file does not repeat it.
