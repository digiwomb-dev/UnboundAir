---
title: Configuration
---

<!-- translated from docs/de/configuration.md @ a164e886e12a8e19f94033a5c5d4e93bc636b237 -->


Every `UnboundAir` setting with default and environment variable (DO-09, requirement KL-01). This file is the reference: what the code knows is in here — and what is in here exists in the code.

The defaults live in exactly one place in the code, in `UnboundAirProperties.kt`. When a value changes there, this table moves with it; the slice test `ConfigBindingSliceTest` checks every default against the code and goes red when someone touches only one of the two sides.

## How settings are applied

There are two spellings for the same setting:

- **Property** – lowercase, with dots and hyphens: `unboundair.poll-interval`. That is how it stands in an `application.yml` or as `--unboundair.poll-interval=5` on the command line.
- **Environment variable** – uppercase, with underscores: `UNBOUNDAIR_POLLINTERVAL`. That is the usual way in the container.

**The conversion has a pitfall.** Every **dot** becomes an **underscore**, every **hyphen is dropped without replacement**:

| Property | Environment variable |
|---|---|
| `unboundair.poll-interval` | `UNBOUNDAIR_POLLINTERVAL` |
| `unboundair.offline-poll-interval` | `UNBOUNDAIR_OFFLINEPOLLINTERVAL` |
| `unboundair.output.modules` | `UNBOUNDAIR_OUTPUT_MODULES` |
| `unboundair.outbox.path` | `UNBOUNDAIR_OUTBOX_PATH` |

The obvious mistake is turning the hyphen into an underscore as well: `UNBOUNDAIR_POLL_INTERVAL` is **not** recognised by Spring, and silently so — the setting simply stays on its default. The property test `PropertyNameMappingPropertyTest` pins this rule.

## Time values

Time settings take a bare number as **seconds** (`20` means 20 seconds) or a value with a unit (`500ms`, `2m`, `1h`).

## The settings

### Service loop

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `unboundair.poll-interval` | `UNBOUNDAIR_POLLINTERVAL` | `3` (seconds) | How often the service asks the scanner for its status (DL-01). Each poll opens its own connection. |
| `unboundair.offline-poll-interval` | `UNBOUNDAIR_OFFLINEPOLLINTERVAL` | `10` (seconds) | Poll interval while the scanner is unreachable (DL-02). Talking to a switched-off device every 3 seconds buys nothing. |
| `unboundair.batch-timeout` | `UNBOUNDAIR_BATCHTIMEOUT` | `20` (seconds) | How long to wait for another page after the last one before closing the document (DL-04). |
| `unboundair.idle-minutes` | `UNBOUNDAIR_IDLEMINUTES` | *(unset)* | After how many minutes without a page to poll more slowly (DL-06). Without a value nothing changes — that is the as-shipped state. |

> **These four values are preliminary.** They are estimated, not measured. The `measure` command produces the numbers the final defaults are derived from; until then OF-02 and OF-03 stay open in `docs/internal/offene-fragen.md`, and OF-01 is recorded as `beobachtet`. For the poll interval there is already an indication: at 3 seconds the device stayed reachable past the auto-off limit. Whether a longer interval manages the same — and would therefore allow a gentler default — is unmeasured. Whoever runs the service today and observes better behaviour should adjust the values — that is what makes them configurable.

### Scanner

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `unboundair.scanner.host` | `UNBOUNDAIR_SCANNER_HOST` | `192.168.18.33` | Address of the scanner (SC-06). In real operation a constant of the device. |
| `unboundair.scanner.port` | `UNBOUNDAIR_SCANNER_PORT` | `23` | Port of the scanner (SC-06). |

Both are adjustable so tests can run against the fake scanner on a free port without changing code for it.

### Page processing

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `unboundair.color-mode` | `UNBOUNDAIR_COLORMODE` | `gray` | `gray` converts the page losslessly to grayscale (`jpegtran -grayscale`), `color` leaves it coloured (SV-03). `bw` converts the page to 1-bit black and white — unlike the other two, **not** lossless: 256 grey levels become a single bit per pixel, and what is discarded once cannot be restored (SV-08). |
| `unboundair.bw-threshold` | `UNBOUNDAIR_BWTHRESHOLD` | `128` | Brightness threshold for `bw`, valid `1..255`: what is darker than the threshold turns black. A **lower** value yields a brighter page with less clogged type, a **higher** one a darker, bolder page. Applies only with `color-mode = bw` (SV-08). |
| `unboundair.keep-raw` | `UNBOUNDAIR_KEEPRAW` | `false` | Stores the unprocessed JPEG additionally (SV-06). Meant for debugging, costs double the space. |
| `unboundair.dpi` | `UNBOUNDAIR_DPI` | `300` | Scan resolution in DPI, 300 or 600 (SC-07, SC-08). |
| `unboundair.page-size` | `UNBOUNDAIR_PAGESIZE` | `off` | Target page format of the finished PDF (SV-09). `off` keeps the old behaviour: the page box is the scan size (SV-05). Any other value sets the box to the named format; the content is placed in the centre unscaled, never upscaled, never recompressed. |

`bw` needs the `jbig2` program for the PDF path — without it, no black-and-white page reaches the PDF.

#### Page size

Valid values are `off`, the names `a4`, `a5`, `a6`, `a6-landscape` (landscape), `letter`, `legal`, and free dimensions in whole millimetres such as `210x297mm`. Case does not matter. One point is 1/72 inch, so 1 mm = 72/25.4 points:

| Value | Millimetres | Points |
|---|---|---|
| `a4` | 210×297 | 595.28×841.89 |
| `a5` | 148×210 | 419.53×595.28 |
| `a6` | 105×148 | 297.64×419.53 |
| `a6-landscape` | 148×105 (landscape) | 419.53×297.64 |
| `letter` | 215.9×279.4 (8.5×11 in, inch-exact) | 612×792 |
| `legal` | 215.9×355.6 (8.5×14 in, inch-exact) | 612×1008 |
| e.g. `210x297mm` | free width×height | converted as above |

There is no automatic rotation: the same value always yields the same box. A landscape scan on `a4` stays upright on the portrait box with white margins; whoever wants landscape asks for it explicitly — the `a6-landscape` preset or a free dimension such as `210x148mm`.

Behaviour in both directions: a scan **smaller** than the box keeps a white margin — the content is not stretched. A scan **larger** than the box protrudes beyond it; what lies outside is **cut off, which means invisible, not deleted**: the pixels remain in the file, the page box just does not show them. Whoever needs them physically gone needs a different tool.

That scans come out smaller than the paper format (OF-05 in `docs/internal/offene-fragen.md`, the scan properties in `docs/de/hardware.md`) changes nothing: the box is set as written, not re-measured.

### Output

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `unboundair.output.modules` | `UNBOUNDAIR_OUTPUT_MODULES` | *(empty)* | Comma-separated list of active output modules, e.g. `paperless` (AU-03). Empty means: no module receives documents. |
| `unboundair.outbox.path` | `UNBOUNDAIR_OUTBOX_PATH` | `/var/lib/unboundair/outbox` | Where finished documents sit until a module has accepted them (AU-04). Belongs on a durable volume in the container, or undelivered documents are lost on restart. |
| `unboundair.output.paperless.base-url` | `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL` | *(empty)* | Address of the paperless-ngx instance, e.g. `https://paperless.example.org` (AU-05). No sensible default — without it the module is unusable. |
| `unboundair.output.paperless.token` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN` | *(empty)* | The API token directly (AU-05). Either this or the token file — how the two interact is described under "Token resolution". |
| `unboundair.output.paperless.token-file` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` | *(empty)* | Path to a file holding the token, e.g. a mounted secret (AU-05). |
| `unboundair.output.paperless.tags` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TAGS` | *(empty)* | Numbers of the tags every document gets. Empty means: none. |
| `unboundair.output.paperless.correspondent` | `UNBOUNDAIR_OUTPUT_PAPERLESS_CORRESPONDENT` | *(unset)* | Number of the correspondent. Without a value paperless derives it itself. |
| `unboundair.output.paperless.document-type` | `UNBOUNDAIR_OUTPUT_PAPERLESS_DOCUMENTTYPE` | *(unset)* | Number of the document type. Without a value paperless derives it itself. |

#### Token resolution

The uploaded document knows exactly one token, but the configuration has two sources for it (AU-05): the directly set token and the token file. Resolution (`PaperlessSettings.fromConfigured`) follows three cases: when a token file is set, it wins — even when a token is set alongside it. The file must then be readable with non-empty content, or the service does not start and names the path in the message. When no file is set, the directly set token applies. When neither is set, the service does not start either — with a message naming exactly that.

This early failure is intentional: a module with an empty token would notice the error only at the first document as an opaque 401, long after startup. That a set file silently overrides a simultaneously set token follows the same reasoning: replacing the file content is the intended way to rotate the token — a leftover old token beside it must not invisibly defeat that rotation. A token read from the file is further stripped of surrounding whitespace, so the almost unavoidable trailing newline (`echo "token" > file`) does not break the upload.

## Deliberately not in here

- **The paperless token as a value.** Secrets do not belong in a configuration file. The token arrives as an environment variable (`unboundair.output.paperless.token`) or as a mounted file whose path arrives through an environment variable (`unboundair.output.paperless.token-file`, AU-05). Which source applies and why the service does not start at all without either is described under "Token resolution".
- **The outbox backoff values.** After the first failed attempt the outbox waits 30 seconds, doubling with every further attempt, capped at one hour. These are deliberately not settings but constructor parameters of `Outbox`: they tune an algorithm per instance that nobody needs to adjust in operation — the file would only gain a switch nobody turns.
- **`normalize` (SV-04).** Deferred until it is decided what it should do — see "Open decisions" in `docs/internal/plan.md`.
- **The time zone.** A document's filename uses the container's local time. It is controlled through the usual `TZ` environment variable, not through a setting of its own.
- **Log levels.** Logging runs through Spring Boot's and Logback's standard means (`logging.level.*`), not through `unboundair.*` settings of its own. Output goes to stdout (KL-02).
