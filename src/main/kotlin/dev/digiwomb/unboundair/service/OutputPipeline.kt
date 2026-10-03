package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.config.UnboundAirProperties
import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.OutputModule
import dev.digiwomb.unboundair.output.OutputModules
import dev.digiwomb.unboundair.output.outbox.Outbox
import dev.digiwomb.unboundair.output.paperless.PaperlessModule
import dev.digiwomb.unboundair.output.paperless.PaperlessSettings
import org.springframework.web.client.RestClient
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId

/**
 * The assembled output side of the service (AU-03, AU-04): the known modules behind their runtime registry, the
 * persistent outbox in front of them, and the runner that drives delivery.
 *
 * This function **assembles** the chain and returns it; it starts no thread and touches no command. Owning the thread
 * is the caller's job (`run` arrives with BE-05 in a later milestone), which is why the [OutboxRunner] is returned
 * unstarted: a composition function that blocked on [OutboxRunner.run] could never hand its own result back.
 *
 * Everything arrives as parameters, nothing is read from the environment: no `System.getenv`, no `Clock.systemUTC()`,
 * no `ZoneId.systemDefault()`. The caller decides, which is what keeps this function constructible in a plain unit
 * test without a Spring context.
 *
 * The assembly order mirrors the data flow in reverse, innermost first:
 *
 * 1. Reject unknown module names against [KNOWN_MODULE_NAMES], so a typo refuses to start instead of silently
 *    uploading nowhere. [OutputModules] makes the same check, but it can only see the modules it was handed -- and
 *    step 2 hands it only the *selected* ones, so on a typo it would report "Known modules: " with an empty list and
 *    tell the operator that no module exists at all. The catalogue of names lives where the modules are built.
 * 2. Build the known modules. Today that is exactly one, the paperless-ngx module, and it is built **only when
 *    `paperless` is among the configured names**: [PaperlessSettings.fromConfigured] throws when no token is set, and
 *    a user who runs with no output module at all must not be forced to configure a paperless token just to satisfy a
 *    module they never selected.
 * 3. Build the [Outbox] over the configured path with the given clock and warning sink.
 * 4. Build the [OutboxRunner] over the outbox and the registry. Its interval is `unboundair.poll-interval`: there is
 *    no dedicated outbox setting, and inventing one here would preempt the configuration issue that owns new knobs.
 *    Reusing the status-check interval is the honest fit -- the runner's loop *is* a periodic status check, asking
 *    the outbox what is due -- until that issue says otherwise.
 * 5. The [OutputPipeline.sink] maps a finished [ScannedDocument] onto an [OutputDocument] field by field and hands it
 *    to [Outbox.accept]. The mapping is a few lines of copying rather than a shared type because `output` may not see
 *    `service` (docs/plan.md, "Dokument-Typ der Modul-Schnittstelle").
 *
 * @param properties the bound configuration; only `output.*`, `outbox.path` and `poll-interval` are read here.
 * @param clock the time source for the outbox (due dates, backoff); injected so tests control it.
 * @param zone the time zone the paperless file name is rendered in; the container's `TZ` zone at the call site.
 * @param warn where the outbox reports recoverable trouble (unreadable entries found during recovery).
 * @param restClient the HTTP client the paperless module uploads with. Defaults to a plain [RestClient.create], which
 *   is what production wants; it is a parameter because a test against WireMock must pin HTTP/1.1 -- WireMock resets
 *   the connection when the JDK client probes for an HTTP/2 upgrade, and without this seam the end-to-end tests could
 *   not drive the assembled chain at all and would have to rebuild their own, which is the one thing they must not do.
 * @return the assembled pipeline: outbox, registry, runner, and the batch sink.
 */
fun outputPipeline(
    properties: UnboundAirProperties,
    clock: Clock,
    zone: ZoneId,
    warn: (String) -> Unit = {},
    restClient: RestClient = RestClient.create(),
): OutputPipeline {
    val configured =
        properties.output.modules
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // Checked here, before anything is constructed, because the lazy construction below would otherwise rob
    // OutputModules of the facts it needs for its own check: handed only the modules that were selected, it would
    // answer a typo with "Known modules: " and an empty list -- telling the operator no module exists at all. The
    // catalogue of names belongs where the modules are built, so this is the place that can name them.
    val unknown = configured.filter { it !in KNOWN_MODULE_NAMES }
    require(unknown.isEmpty()) {
        "Unknown output module(s): ${unknown.joinToString(", ")}. Known modules: ${KNOWN_MODULE_NAMES.joinToString(", ")}"
    }

    val paperless = properties.output.paperless
    val knownModules =
        buildList<OutputModule> {
            // Lazy on purpose: fromConfigured throws without a token, and an unselected module must never hold startup hostage.
            if (PAPERLESS_MODULE_NAME in configured) {
                add(
                    PaperlessModule(
                        PaperlessSettings.fromConfigured(
                            paperless.baseUrl,
                            paperless.token,
                            paperless.tokenFile,
                            paperless.tags,
                            paperless.correspondent,
                            paperless.documentType,
                        ),
                        zone,
                        restClient,
                    ),
                )
            }
        }
    val modules = OutputModules(knownModules, properties.output.modules)
    val outbox = Outbox(Path.of(properties.outbox.path), clock, warn = warn)
    val runner = OutboxRunner(outbox, modules, pollInterval = properties.pollInterval)
    return OutputPipeline(outbox, modules, runner)
}

/**
 * What [outputPipeline] returns: the assembled output chain plus its entry point.
 *
 * @property outbox the persistence buffer between a finished document and the modules (AU-04).
 * @property modules the runtime-selected output modules (AU-03).
 * @property runner the loop that delivers whatever the outbox names as due; returned unstarted, the caller owns the thread.
 */
class OutputPipeline(
    val outbox: Outbox,
    val modules: OutputModules,
    val runner: OutboxRunner,
) {
    /**
     * The batch sink: hands a finished document to the outbox.
     *
     * The outbox persists before this returns, so by the time the sink completes the document survives a crash and the
     * runner will deliver it. The return value of [Outbox.accept] is deliberately dropped: the sink contract is fire
     * and forget, and the entry is observable through [outbox] for anyone who needs it.
     */
    val sink: (ScannedDocument) -> Unit = { scanned ->
        outbox.accept(OutputDocument(scanned.pdf, scanned.pageCount, scanned.startedAt, scanned.finishedAt))
    }
}

/** The name under which the paperless-ngx module registers itself; mirrors [PaperlessModule.name]. */
private const val PAPERLESS_MODULE_NAME = "paperless"

/** Every module name this assembly knows how to build -- the catalogue a typo is reported against. */
private val KNOWN_MODULE_NAMES = listOf(PAPERLESS_MODULE_NAME)
