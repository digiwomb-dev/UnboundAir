package dev.digiwomb.unboundair.output

/**
 * Registry of output modules selected at runtime from the configured list (AU-03).
 *
 * The registry is built once at startup with all known modules and the names
 * from `unboundair.output.modules`. Only modules whose [OutputModule.name] appears
 * in the configured comma list receive documents. The list is evaluated at runtime
 * -- no `@ConditionalOnProperty` or other Spring conditional bean machinery is used,
 * which keeps a GraalVM native image viable (leitplanke in AGENTS.md).
 *
 * Parsing is tolerant: whitespace around entries is trimmed and empty entries are
 * dropped. An empty or blank list is valid and means no module is selected -- this
 * is the documented default. Names are matched exactly, no case folding.
 *
 * Unknown names fail loudly at construction time. A typo in `UNBOUNDAIR_OUTPUT_MODULES`
 * must not silently disable all uploads. The check mirrors the project's existing
 * pattern (`PdfBuilder.build`, `image/LumaImage.kt`) and `UnboundAirApplication.run`
 * already maps `IllegalArgumentException` to a logged error with exit code 1, so the
 * service refuses to start rather than discovering the typo after the first scan.
 *
 * Order follows the configured list, not the order the modules were supplied.
 *
 * Sending does not catch exceptions from [OutputModule.send]. The contract of the
 * interface is "throw on failure", and the caller is the outbox (AU-04). The outbox
 * is the only place that can decide "delete the directory or schedule a retry".
 * Swallowing a failure here would make the outbox believe delivery succeeded and
 * delete a document that never arrived. Letting the exception propagate keeps the
 * failure visible to the outbox and is the non-obvious, correct choice.
 */
class OutputModules(
    modules: List<OutputModule>,
    configuredNames: List<String>,
) {
    private val selected: List<OutputModule>

    init {
        val knownByName = modules.associateBy { it.name }
        val knownNames = knownByName.keys.toSet()

        val configured =
            configuredNames
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        val unknown = configured.filter { it !in knownNames }
        require(unknown.isEmpty()) {
            "Unknown output module(s): ${unknown.joinToString(", ")}. " +
                "Known modules: ${knownNames.joinToString(", ")}"
        }

        selected = configured.map { name -> knownByName[name]!! }
    }

    /**
     * Delivers [document] to each selected module, in the order configured by the user.
     *
     * Exceptions from a module are not caught -- they propagate to the outbox (AU-04)
     * which decides whether to retry. This is intentional; see the class KDoc.
     */
    fun send(document: OutputDocument) {
        selected.forEach { it.send(document) }
    }
}
