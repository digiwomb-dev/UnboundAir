---
title: Output Modules
---

<!-- translated from docs/de/output-modules.md @ 4d51b9bd5860349e1adb6b8bd9c6fc07e1f00f99 -->


How a finished document travels from the service to the output modules (AU-02, AU-03, AU-04). This file describes the general interface and the chain; what the individual settings mean is in `configuration.md`. v1 ships exactly one module, paperless-ngx — its specifics are in the "The paperless-ngx module" section below.

The chain is deliberately narrow: a finished document is first stored, then handed over, and the entry is deleted only once a module has confirmed delivery. No further state sits in between — which is why a restart loses nothing.

## The `OutputModule` interface

The interface lives in `output/OutputModule.kt` and is small on purpose: one property and one method.

- `name: String` — the module name. It is the string matched against `unboundair.output.modules`.
- `fun send(document: OutputDocument)` — delivers the document to the service the module connects.

The striking point is what `send` does **not** have: it returns nothing. Returning means "accepted", failure means "throw an exception". This shape is decided, not left to the module author — the only caller is the outbox (AU-04), and its only decision reads "delete directory or schedule another attempt". A `try`/`catch` in one place suffices for that. A result type would force every module author to build a success value nobody reads; an ignored return value fails silently, while an uncaught exception cannot be overlooked. A module that cannot deliver therefore throws; the outbox keeps the document and tries again.

The interface is deliberately a pure Kotlin interface: no Spring annotation, no injected settings. A module receives its settings through the constructor — the same pattern the rest of the core uses for its values (the `PageSettings` pattern).

## The `OutputDocument` type

The document type lives in `output/OutputDocument.kt`. It carries four values:

| Value | Meaning |
|---|---|
| `pdf: Path` | The built multi-page PDF. |
| `pageCount: Int` | How many pages it holds. Deliberately redundant with the PDF itself: a module should not have to open the document first to log what it is delivering. |
| `startedAt: Instant` | When the batch's **first** page began scanning. That is the document's timestamp throughout: both the PDF's `CreationDate` and the filename `scan-YYYYMMDD-HHMMSS.pdf` (AU-05) derive from it. |
| `finishedAt: Instant` | When the batch was closed. Serves diagnosis: the span to `startedAt` tells how long the whole document took. |

They are the same four values `service.ScannedDocument` carries; the batch's sink maps one type onto the other because `output` must not see `service` per the layer plan — a few lines of mapping are the cheaper price.

The metadata travels **beside** the file rather than being read back out of the PDF: the outbox has to store it next to the document anyway so it survives a restart (AU-04), and a re-sent document's filename is rebuilt from `startedAt` (AU-05) — the outbox stores the file as `document.pdf`, so the name cannot be read off it.

The filename itself (`documentName`) is built from `startedAt` in the container's time zone; that zone is controlled through the usual `TZ` environment variable, not through a setting of its own.

## Runtime selection through `OutputModules`

Selection lives in `output/OutputModules.kt`. It is built once at startup from all known modules and the names from `unboundair.output.modules`: only modules whose `name` stands in the configured comma-separated list receive documents.

Evaluation happens at runtime — no Spring annotation decides the selection, which keeps the code open for a GraalVM native image. The details:

- The list is lenient in reading: spaces around entries are trimmed, empty entries drop out. An empty list is valid and selects nothing — that is the documented default of `unboundair.output.modules` (no module receives documents).
- Names compare exactly, with no case conversion.
- Order follows the configured list, not the order the modules were passed in.
- An unknown name refuses to start the service at all: the constructor throws an `IllegalArgumentException` naming the unknown name and the known modules — a typo in `UNBOUNDAIR_OUTPUT_MODULES` must not silently switch off every upload.

That `send` does not catch module exceptions belongs here too: the interface contract reads "throw on failure", and only the outbox may decide "delete directory or schedule another attempt". Swallowing at this point would let the outbox believe delivery succeeded and delete a never-arrived document. Passing the exception on keeps the error visible to the outbox — the inconspicuous but right choice.

Assembling the chain lives in `service/OutputPipeline.kt` (`outputPipeline`): it checks unknown names against the catalogue of names it can build, builds only the selected modules — an unselected module must never hold up startup, say for a missing token — and then lays outbox and `OutboxRunner` over them. The runner asks at the `unboundair.poll-interval` rhythm; there is no outbox setting of its own for that.

## The outbox in the chain

The outbox lives in `output/outbox/Outbox.kt`, the driving runner in `service/OutboxRunner.kt`. The chain runs like this:

1. The finished document goes to the pipeline's sink (`OutputPipeline.sink`), which maps it onto `OutputDocument` and hands it to `Outbox.accept`.
2. `accept` stores completely — entry directory, PDF, metadata — **before** it returns. Only upon that deposit may the caller hand the document to a module.
3. The `OutboxRunner` asks in a loop what is due (`Outbox.due`) and hands every due document to `OutputModules.send`.
4. On clean return the runner reports success (`recordSuccess`): the entry leaves the pending state and its directory is deleted — and only then. On an exception it reports failure (`recordFailure`): the entry stays, its attempts and next attempt date are written forward.

The core is the order: first store, then hand over; delete only after confirmed delivery. Until then the outbox is the only copy. Because `recover` re-reads the same directories when the outbox is built — including written-forward attempts and dates — a restart finds its pending work again: nothing once accepted is lost to a crash. The outbox itself is laid out passively — no thread, no timer, no knowledge of modules; it stores, names what is due and records reported results. The runner in the service layer turns the clock.

The waiting times stand as constructor parameters on the outbox, not as settings — they tune an algorithm per instance that nobody needs to adjust in operation, which is why `configuration.md` deliberately knows no switch for them. The values (`Outbox` defaults, pinned in `backoffDelay`): 30 seconds after the first failed attempt, doubling with every further one, capped at one hour, unlimited attempts. With the defaults the sequence reads 30 s, 1 min, 2 min, … capped at 1 h.

## The paperless-ngx module

The module lives in `output/paperless/PaperlessModule.kt` and uploads the finished PDF into a paperless-ngx instance (AU-05, AU-06). What the individual settings mean is in `configuration.md` under "Output"; what the module does with them is here.

The module name is `paperless` — exactly that string belongs in `unboundair.output.modules` (environment variable `UNBOUNDAIR_OUTPUT_MODULES`), or the module receives no documents. Selection compares names exactly, and an unknown name refuses startup.

The call is a POST to `{baseUrl}/api/documents/post_document/`, where `baseUrl` comes from `unboundair.output.paperless.base-url`. Authentication runs through the `Authorization: Token ...` header. The PDF travels as a multipart part with the field name `document`.

The multipart part carries the filename `scan-YYYYMMDD-HHMMSS.pdf`, rebuilt from `startedAt` in the container's time zone (controlled through `TZ`). The name is rebuilt because the outbox stores the file as `document.pdf`, so the name cannot be taken from it. The name matters because paperless derives the document title from it.

`title` and `created` are deliberately **not** sent, so paperless derives both itself from the filename. That is a firm plan decision, not a gap: there are no settings for it, and a switch for it is expressly not planned.

The optional fields travel only when configured: `correspondent` from `unboundair.output.paperless.correspondent` and `document_type` from `unboundair.output.paperless.document-type` travel only when a value is set; `tags` from `unboundair.output.paperless.tags` travel as repeated form fields, one per tag number. Without correspondent or document type paperless derives the detail itself; without tags the module simply sends none.

The token (AU-05) comes from `unboundair.output.paperless.token` or from the file under `unboundair.output.paperless.token-file` — environment variable or file. When a token file is set, it wins even when a token is set alongside it; when neither is set, the service does not start at all. The details are in `configuration.md` under "Token resolution" and are deliberately not repeated here. The token lands only in the request header; logs and error messages name endpoint and status, never the token — `PaperlessSettings` masks it even in its own string representation.

For success and failure the interface contract applies (AU-06): a 2xx answer delivers the UUID of the paperless processing task, logged at INFO (`paperless-ngx accepted the document; consumption task ...`) so the document can be found again in paperless. Any other status and any transport error lead to an exception — the outbox keeps the document and tries again later.

A brief example with environment variables suffices for setup:

```sh
UNBOUNDAIR_OUTPUT_MODULES=paperless
UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org
UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token
```

The spelling rule from `configuration.md` applies: every dot becomes an underscore, every hyphen is dropped without replacement — so `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL`, not `..._BASE_URL`.

## Writing a new module

This is the guide to building a module from scratch. The verifiable model is `RecordingModule` in `src/test/kotlin/dev/digiwomb/unboundair/output/OutputModulesTest.kt` — what stands here suffices to write one.

**1. Implement the interface.** Create a class fulfilling `OutputModule`: a fixed `name` and a `send` that throws on failure and simply returns on success. Settings arrive through the constructor, not through Spring injection — the `PageSettings` pattern:

```kotlin
class ArchiveModule(
    private val endpoint: String,
) : OutputModule {
    override val name: String = "archive"

    override fun send(document: OutputDocument) {
        // ...
    }
}
```

The name is the contract with the configuration: exactly that string must later stand in `unboundair.output.modules`, or selection never picks the module — or startup fails loudly on a typo.

**2. Register the module in `service/OutputPipeline.kt` so the configuration knows it.** The assembly function builds the known modules and checks the configured names against its catalogue (`KNOWN_MODULE_NAMES`). A module neither built nor named there can never reach selection — the entry is therefore part of building, not optional. The restraint already applying to the included module holds: only build what is among the configured names — an unselected module must never hold up startup.

**3. Configure and verify.** Take the module name into `unboundair.output.modules` (environment variable `UNBOUNDAIR_OUTPUT_MODULES`) and start the service. An unknown name refuses startup and names the typo as well as the known modules; a module missing from the list simply receives no documents.
