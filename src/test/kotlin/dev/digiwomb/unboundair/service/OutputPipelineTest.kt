package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.config.UnboundAirProperties
import dev.digiwomb.unboundair.output.OutputModules
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.readBytes

/**
 * Unit tests for [outputPipeline] (AU-03, AU-04).
 *
 * The assembly function promises in its KDoc that it is constructible in a
 * plain unit test -- no Spring context, everything arrives as parameters --
 * so this test builds it directly with a fixed clock, a temporary outbox
 * directory, and an explicit zone.
 *
 * 1. Typo guard (AU-03): an unknown module name is rejected at assembly time,
 *    and the message names both the typo and the known modules.
 * 2. Module selection (AU-03): the paperless module is built only when
 *    `paperless` is among the configured names -- an unselected module must
 *    not hold startup hostage by demanding a token nobody selected. Blank
 *    entries are dropped and padded ones trimmed before matching.
 * 3. Sink mapping (AU-04): the sink maps a [ScannedDocument] onto an
 *    [OutputDocument] field by field and hands it to the outbox, which
 *    persists it before the sink returns.
 * 4. Runner wiring (AU-04): the runner arrives unstarted and its interval is
 *    `unboundair.poll-interval`; the outbox sits on the configured path.
 *
 * The runner's interval and the registry's selection are private on purpose,
 * so they are read back through reflection -- the alternative would be timing
 * a real loop or hitting the network, both of which this layer forbids.
 *
 * Offline (DC-03): plain JVM, fixed clock, temporary directories, no device,
 * no network. `RestClient.create()` opens no connection on creation, and no
 * test below ever calls `send` on a real module.
 */
class OutputPipelineTest {
    @Nested
    inner class `AU-03 typo guard` {
        @Test
        fun `AU-03 an unknown module name is rejected naming the typo and the known modules`(
            @TempDir dir: Path,
        ) {
            val properties = properties(outboxDir = dir, modules = listOf("papless"))

            assertThatThrownBy { outputPipeline(properties, clock(), ZONE) }
                .`as`("a typo in unboundair.output.modules must refuse to start, not silently upload nowhere")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("papless")
                .hasMessageContaining("paperless")
        }

        @Test
        fun `AU-03 blank entries are dropped, so a list of blanks selects nothing`(
            @TempDir dir: Path,
        ) {
            val properties = properties(outboxDir = dir, modules = listOf(" ", "", "   "))

            val pipeline = outputPipeline(properties, clock(), ZONE)

            assertThat(selectedNames(pipeline.modules))
                .`as`("entries that trim to nothing must not select a module")
                .isEmpty()
        }

        @Test
        fun `AU-03 a padded entry is trimmed, so it still selects the module it names`(
            @TempDir dir: Path,
        ) {
            val properties =
                properties(
                    outboxDir = dir,
                    modules = listOf("  paperless  ", " "),
                    paperlessToken = TOKEN,
                )

            val pipeline = outputPipeline(properties, clock(), ZONE)

            assertThat(selectedNames(pipeline.modules))
                .`as`("whitespace around the entry is trimmed before matching")
                .containsExactly("paperless")
        }
    }

    @Nested
    inner class `AU-03 module selection` {
        @Test
        fun `AU-03 an unselected paperless module needs no token and selects nothing`(
            @TempDir dir: Path,
        ) {
            val properties = properties(outboxDir = dir, modules = emptyList())

            val pipeline = outputPipeline(properties, clock(), ZONE)

            assertThat(selectedNames(pipeline.modules))
                .`as`("no configured name means no selected module, without demanding a token")
                .isEmpty()
        }

        @Test
        fun `AU-03 a selected paperless module without a token fails at assembly`(
            @TempDir dir: Path,
        ) {
            val properties = properties(outboxDir = dir, modules = listOf("paperless"))

            assertThatThrownBy { outputPipeline(properties, clock(), ZONE) }
                .`as`("a selected paperless module without a token must fail at startup, not at the first upload")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("paperless")
        }

        @Test
        fun `AU-03 a selected paperless module with a token is built`(
            @TempDir dir: Path,
        ) {
            val properties =
                properties(
                    outboxDir = dir,
                    modules = listOf("paperless"),
                    paperlessToken = TOKEN,
                )

            val pipeline = outputPipeline(properties, clock(), ZONE)

            assertThat(selectedNames(pipeline.modules))
                .`as`("the configured module must be registered under its own name")
                .containsExactly("paperless")
        }
    }

    @Nested
    inner class `AU-04 sink mapping` {
        @Test
        fun `AU-04 the sink persists the document field by field before it returns`(
            @TempDir dir: Path,
        ) {
            val outboxDir = dir.resolve("outbox")
            val pdf = dir.resolve("scan.pdf")
            Files.write(pdf, PDF_BYTES)
            val pipeline = outputPipeline(properties(outboxDir = outboxDir), clock(), ZONE)
            val scanned = ScannedDocument(pdf, PAGE_COUNT, START, FINISHED)

            pipeline.sink(scanned)

            val due = pipeline.outbox.due()
            assertThat(due)
                .`as`("the outbox persists before the sink returns, so the document is due immediately")
                .hasSize(1)
            val stored = due.single().document
            assertThat(stored.pageCount)
                .`as`("the page count travels with the document, so a module need not open the PDF")
                .isEqualTo(PAGE_COUNT)
            assertThat(stored.startedAt)
                .`as`("the document timestamp travels with the document (AU-05 file name)")
                .isEqualTo(START)
            assertThat(stored.finishedAt)
                .`as`("the finish time travels with the document")
                .isEqualTo(FINISHED)
            assertThat(stored.pdf.readBytes())
                .`as`("the PDF bytes survive the outbox copy unchanged")
                .isEqualTo(PDF_BYTES)
            assertThat(stored.pdf)
                .`as`("the outbox stores its own copy; the working file is not the persisted one")
                .isNotEqualTo(pdf)
        }
    }

    @Nested
    inner class `AU-04 runner wiring` {
        @Test
        fun `AU-04 the runner arrives unstarted with the poll interval and the outbox on the configured path`(
            @TempDir dir: Path,
        ) {
            val outboxDir = dir.resolve("outbox")
            val interval = Duration.ofSeconds(7)
            val properties = properties(outboxDir = outboxDir, pollInterval = interval)

            val pipeline = outputPipeline(properties, clock(), ZONE)

            assertThat(pipeline.runner.isRunning)
                .`as`("the runner is returned unstarted; the caller owns the thread")
                .isFalse()
            assertThat(runnerInterval(pipeline.runner))
                .`as`("the runner's interval is unboundair.poll-interval; there is no dedicated outbox setting")
                .isEqualTo(interval)
            assertThat(pipeline.outbox.root)
                .`as`("the outbox sits on the configured path")
                .isEqualTo(outboxDir)
        }
    }

    private fun properties(
        outboxDir: Path,
        modules: List<String> = emptyList(),
        paperlessToken: String = "",
        pollInterval: Duration = Duration.ofSeconds(3),
    ): UnboundAirProperties =
        UnboundAirProperties(
            pollInterval = pollInterval,
            output =
                UnboundAirProperties.OutputProperties(
                    modules = modules,
                    paperless =
                        UnboundAirProperties.PaperlessProperties(
                            baseUrl = "http://paperless.example.org",
                            token = paperlessToken,
                        ),
                ),
            outbox = UnboundAirProperties.OutboxProperties(path = outboxDir.toString()),
        )

    private fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    /** The names of the modules the registry selected, in configured order. */
    private fun selectedNames(modules: OutputModules): List<String> {
        val field = OutputModules::class.java.getDeclaredField("selected")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val selected = field.get(modules) as List<dev.digiwomb.unboundair.output.OutputModule>
        return selected.map { it.name }
    }

    /** The runner's interval, which it keeps private on purpose. */
    private fun runnerInterval(runner: OutboxRunner): Duration {
        val field = OutboxRunner::class.java.getDeclaredField("pollInterval")
        field.isAccessible = true
        return field.get(runner) as Duration
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        const val TOKEN = "test-token"
        const val PAGE_COUNT = 3
        val NOW: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val FINISHED: Instant = Instant.parse("2026-09-27T10:00:20Z")
        val PDF_BYTES: ByteArray = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37)
    }
}
