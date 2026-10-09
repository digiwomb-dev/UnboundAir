package dev.digiwomb.unboundair.output

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/**
 * Unit tests for [OutputModules] (AU-02, AU-03).
 *
 * AU-02 and AU-03 are claims about extensibility, so the registry here is driven
 * by modules that exist nowhere in the core:
 *
 * 1. A module that exists **only in the test** registers and receives the document
 *    *and* its metadata -- the page count and both instants travel with the file
 *    (AU-02). That is the literal acceptance criterion of AU-02: a new module joins
 *    without a single change to the core.
 * 2. A module not named in the configured list receives nothing (AU-03).
 * 3. An unknown name fails loudly at construction, naming the typo and the known
 *    modules (AU-03).
 * 4. An empty list is valid, and blank entries are trimmed and dropped: a list that
 *    trims to nothing selects nothing -- the documented default of
 *    `unboundair.output.modules` (AU-03).
 * 5. Delivery order follows the configured list, not the order the modules were
 *    supplied (AU-03).
 * 6. A module exception propagates instead of being swallowed (AU-02, the "throw on
 *    failure" contract of [OutputModule.send]); the modules scheduled behind the
 *    failing one are not called.
 *
 * The fakes record what they receive; none of them reads the PDF.
 * [OutputDocument.pdf] therefore points at a file that does not exist, which is fine
 * and keeps the test from touching the filesystem at all.
 *
 * Offline (DC-03): plain JVM, fakes, no device, no network.
 */
class OutputModulesTest {
    /**
     * A module that exists only in the test -- the whole point of AU-02. It records
     * every document it receives and, if given a [log], appends its name there so a
     * test can observe the delivery order across several modules.
     */
    private class RecordingModule(
        override val name: String,
        private val log: MutableList<String> = mutableListOf(),
    ) : OutputModule {
        val received: MutableList<OutputDocument> = mutableListOf()

        override fun send(document: OutputDocument) {
            log.add(name)
            received.add(document)
        }
    }

    /**
     * A module that fails on every delivery. The [IllegalStateException] is what a
     * real module throws when the service behind it refuses the upload.
     */
    private class FailingModule(
        override val name: String,
        private val log: MutableList<String> = mutableListOf(),
    ) : OutputModule {
        override fun send(document: OutputDocument) {
            log.add(name)
            throw IllegalStateException("upload to $name failed")
        }
    }

    /**
     * The literal acceptance criterion of AU-02: a module that exists only in the
     * test plugs into the registry and receives the document *and* its metadata.
     */
    @Test
    fun `AU-02 a module that exists only in the test receives the document and its metadata`() {
        val module = RecordingModule("paperless")
        val registry = OutputModules(listOf(module), listOf("paperless"))

        registry.send(document())

        assertThat(module.received)
            .`as`("the configured module must receive exactly the one document that was sent")
            .containsExactly(document())
        assertThat(module.received.single().pdf)
            .`as`("the PDF path travels with the document")
            .isEqualTo(PDF)
        assertThat(module.received.single().pageCount)
            .`as`("the page count travels with the document, so a module need not open the PDF")
            .isEqualTo(PAGE_COUNT)
        assertThat(module.received.single().startedAt)
            .`as`("the document timestamp travels with the document")
            .isEqualTo(START)
        assertThat(module.received.single().finishedAt)
            .`as`("the finish time travels with the document")
            .isEqualTo(FINISHED)
    }

    /**
     * AU-03: only modules named in the configured list receive documents. The
     * unconfigured module is a real, registered module -- it is selected out at
     * startup, not disabled per document.
     */
    @Test
    fun `AU-03 a module not named in the configured list receives nothing`() {
        val paperless = RecordingModule("paperless")
        val archive = RecordingModule("archive")
        val registry = OutputModules(listOf(paperless, archive), listOf("paperless"))

        registry.send(document())

        assertThat(paperless.received)
            .`as`("the configured module is selected")
            .hasSize(1)
        assertThat(archive.received)
            .`as`("the unconfigured module is registered but must not receive the document")
            .isEmpty()
    }

    /**
     * AU-03: a typo in the configured list must not silently disable all uploads.
     * Construction fails loudly; the message names the unknown entry and lists the
     * known modules so the operator can fix the environment variable. The
     * known-module order comes from a Set and is not part of the contract, so the
     * message is asserted per token, not verbatim.
     */
    @Test
    fun `AU-03 an unknown module name fails loudly at startup`() {
        assertThatThrownBy {
            OutputModules(
                listOf(RecordingModule("paperless"), RecordingModule("archive")),
                listOf("papless"),
            )
        }.`as`("a typo in UNBOUNDAIR_OUTPUT_MODULES must fail at startup, not after the first scan")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("papless")
            .hasMessageContaining("paperless")
            .hasMessageContaining("archive")
    }

    /**
     * AU-03 together with the documented default (`docs/de/configuration.md`:
     * `unboundair.output.modules` empty means no module receives documents).
     * Both an empty list and a list of blanks are therefore valid and select
     * nothing.
     */
    @Test
    fun `AU-03 an empty or blank list is valid and selects nothing`() {
        val paperless = RecordingModule("paperless")
        val archive = RecordingModule("archive")

        OutputModules(listOf(paperless, archive), emptyList()).send(document())
        OutputModules(listOf(paperless, archive), listOf(" ", "", "   ")).send(document())

        assertThat(paperless.received)
            .`as`("neither the empty list nor a list of blanks may select this module")
            .isEmpty()
        assertThat(archive.received)
            .`as`("nor the other module")
            .isEmpty()
    }

    /**
     * AU-03: entries are trimmed before matching, so a hand-edited list with stray
     * whitespace still selects exactly the one module the user meant -- no more,
     * no less.
     */
    @Test
    fun `AU-03 padded and blank entries are trimmed, so only the real module is selected`() {
        val paperless = RecordingModule("paperless")
        val archive = RecordingModule("archive")
        val registry =
            OutputModules(
                listOf(paperless, archive),
                listOf("  paperless  ", " ", ""),
            )

        registry.send(document())

        assertThat(paperless.received)
            .`as`("whitespace around the entry is trimmed before matching")
            .hasSize(1)
        assertThat(archive.received)
            .`as`("the blank entries must not select anything else")
            .isEmpty()
    }

    /**
     * AU-03: the user's list is the contract. Supplying the modules in a different
     * order must not change the order in which they are called -- the shared log
     * makes that order observable.
     */
    @Test
    fun `AU-03 delivery order follows the configured list, not the supply order`() {
        val order = mutableListOf<String>()
        val paperless = RecordingModule("paperless", order)
        val archive = RecordingModule("archive", order)
        val registry = OutputModules(listOf(archive, paperless), listOf("paperless", "archive"))

        registry.send(document())

        assertThat(order)
            .`as`("supplied second but configured first: delivery must follow the configuration")
            .containsExactly("paperless", "archive")
    }

    /**
     * AU-02's failure contract on the registry side: a module that cannot deliver
     * throws, and [OutputModules.send] must let that exception propagate rather
     * than swallow it -- the outbox (AU-04) is the only place that may decide
     * "delete the document or schedule a retry".
     *
     * The current implementation walks the selected modules with a `forEach`, which
     * stops at the first exception: modules scheduled after the failing one are not
     * called. That is asserted, not guessed.
     */
    @Test
    fun `AU-02 a module exception propagates instead of being swallowed`() {
        val order = mutableListOf<String>()
        val failing = FailingModule("paperless", order)
        val archive = RecordingModule("archive", order)
        val registry = OutputModules(listOf(failing, archive), listOf("paperless", "archive"))

        assertThatThrownBy { registry.send(document()) }
            .`as`("the failure must stay visible; only the outbox may turn it into a retry")
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("paperless")

        assertThat(order)
            .`as`("the failing module was called exactly once, and the walk stopped with it")
            .containsExactly("paperless")
        assertThat(archive.received)
            .`as`("scheduled after the failing module: not reached, so not called")
            .isEmpty()
    }

    /**
     * A finished document with pinned values. The PDF path points at a file that
     * does not exist; that is fine, none of the fakes reads it, and it keeps the
     * test from touching the filesystem at all.
     */
    private fun document(): OutputDocument = OutputDocument(PDF, PAGE_COUNT, START, FINISHED)

    private companion object {
        val PDF: Path = Path.of("/var/lib/unboundair/documents/scan-20260927-100000.pdf")

        const val PAGE_COUNT = 7

        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val FINISHED: Instant = Instant.parse("2026-09-27T10:00:20Z")
    }
}
