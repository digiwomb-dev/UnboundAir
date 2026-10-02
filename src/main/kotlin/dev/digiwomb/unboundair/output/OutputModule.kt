package dev.digiwomb.unboundair.output

/**
 * The interface an output module implements (AU-02).
 *
 * A module receives a finished [OutputDocument] -- the PDF plus its metadata -- and
 * delivers it. v1 ships one module, paperless-ngx (AU-06); new modules join by
 * implementing this interface and being listed in `unboundair.output.modules`,
 * without any change to the core (AU-03). Which modules are active is evaluated at
 * runtime from that list; no annotation decides, which keeps the code compatible
 * with a GraalVM native image (`docs/plan.md`, "Module per Laufzeit-Auswahl").
 *
 * This is a plain Kotlin interface on purpose: no Spring stereotype, no injected
 * properties. A module takes its settings through its constructor, the same way
 * the rest of the core takes its values (the `PageSettings` pattern).
 *
 * @property name the module name, the string matched against
 *   `unboundair.output.modules`.
 */
interface OutputModule {
    val name: String

    /**
     * Delivers [document] to the service this module wraps.
     *
     * Returning means the module accepted the document; failing means throwing.
     * That shape is decided, not left to the implementer: the only caller is the
     * outbox (AU-04), and its single decision is "delete the directory or schedule
     * a retry" -- a `try`/`catch` expresses that in one place. A sealed result type
     * would force every module author to construct a success value nobody reads,
     * and an ignored return value fails silently, whereas an uncaught exception
     * cannot. A module that cannot deliver throws; the outbox keeps the document
     * and retries.
     */
    fun send(document: OutputDocument)
}
