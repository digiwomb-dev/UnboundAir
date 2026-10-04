package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.OutputModule
import dev.digiwomb.unboundair.output.OutputModules
import dev.digiwomb.unboundair.output.outbox.METADATA_FILE_NAME
import dev.digiwomb.unboundair.output.outbox.Outbox
import dev.digiwomb.unboundair.output.outbox.OutboxEntry
import dev.digiwomb.unboundair.output.outbox.readMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Integration test for the AU-04 retry loop: [Outbox] + [OutboxRunner] + a fake [OutputModule].
 *
 * The unit test ([OutboxTest]) proves the store persists and backs off; this file proves the
 * loop around it delivers: a throwing module keeps the document, a later attempt removes it,
 * the state survives a restart, and one failing document does not block another.
 *
 * **No threads, no sleeping.** Each pass runs synchronously: the runner's `sleeper` stops the
 * loop after exactly one turn (`sleeper = { runner.stop() }`), so `run()` returns after one
 * pass over the due entries. Time comes from fixed clocks per outbox; a redelivery is
 * expressed by building the next outbox with a later fixed clock — which is exactly what a
 * restart would do, and avoids spinning on a backoff that has not elapsed yet.
 *
 * Offline (DC-03): a committed golden PDF as the source document, fixed clocks, a temporary
 * directory. No device, no network.
 *
 * Lives in `service`, not beside the outbox, because the runner it drives lives here: the
 * layer guard forbids `output..` to reach into `service..`, and a test in the outbox package
 * is bound by that rule like any other class. Every other integration test of the loop sits
 * here for the same reason.
 */
class OutboxRetryIntegrationTest {
    @Nested
    inner class FailingModule {
        @Test
        fun `AU-04 a failing module keeps the document and the attempts go up`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START), backoffInitial = BACKOFF)
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))
            val events = RecordingListener()
            val runner = onePassRunner(outbox, OutputModules(listOf(alwaysFailing()), listOf("fake")), events)

            runner.run()

            assertThat(Files.exists(entry.directory))
                .`as`("a throwing module must not delete the document; the outbox is the only copy until success")
                .isTrue()
            assertThat(Files.exists(entry.directory.resolve(METADATA_FILE_NAME)))
                .`as`("the metadata stays beside the document so the retry state survives a crash")
                .isTrue()
            assertThat(readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!.attempts)
                .`as`("the failed pass is counted on disk, not only in memory")
                .isEqualTo(1)
            assertThat(outbox.due())
                .`as`("at the same instant the backoff has not elapsed, so nothing is due again yet")
                .isEmpty()
            assertThat(events.failed).`as`("the runner reports the failed delivery").hasSize(1)
            assertThat(events.delivered).`as`("nothing was delivered").isEmpty()
        }
    }

    @Nested
    inner class RetryDelivers {
        @Test
        fun `AU-04 a later attempt delivers the document and removes the entry`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START), backoffInitial = BACKOFF)
            outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))
            val module = FlakyModule(failuresLeft = 1)
            val modules = OutputModules(listOf(module), listOf("fake"))

            // First pass: the module throws, the entry backs off.
            onePassRunner(outbox, modules).run()
            assertThat(module.deliveries).`as`("the first attempt fails before any delivery").isEmpty()

            // Second pass: the clock has advanced past the backoff, the module succeeds now.
            val later = Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1)), backoffInitial = BACKOFF)
            val pending = later.due()
            assertThat(pending).`as`("past the backoff the entry is due again").hasSize(1)
            onePassRunner(later, modules).run()

            assertThat(module.deliveries)
                .`as`("the retry delivered exactly once")
                .hasSize(1)
            assertThat(module.deliveries.single())
                .`as`("the module saw the PDF bytes intact; the outbox never re-encodes")
                .isEqualTo(goldenPdfBytes())
            assertThat(Files.exists(pending.single().directory))
                .`as`("the entry directory is gone only after the module confirmed delivery")
                .isFalse()
            assertThat(later.due()).`as`("a delivered entry is not due again").isEmpty()
        }
    }

    @Nested
    inner class Restart {
        @Test
        fun `AU-04 a restart over the same directory picks the pending document up`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START), backoffInitial = BACKOFF)
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))
            onePassRunner(outbox, OutputModules(listOf(alwaysFailing()), listOf("fake"))).run()

            // The process dies here; a new outbox over the same directory is the restart.
            val restarted =
                Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1)), backoffInitial = BACKOFF)
            val recovered = restarted.due()

            assertThat(recovered)
                .`as`("the restart finds the pending document again — that is the AU-04 promise in the form that matters")
                .hasSize(1)
            assertThat(recovered.single().id).`as`("the recovered entry is the same document").isEqualTo(entry.id)
            assertThat(recovered.single().metadata.attempts)
                .`as`("the attempt counter survives the restart instead of resetting")
                .isEqualTo(1)

            val module = FlakyModule(failuresLeft = 0)
            onePassRunner(restarted, OutputModules(listOf(module), listOf("fake"))).run()
            assertThat(module.deliveries).`as`("the restarted outbox delivers the recovered document").hasSize(1)
            assertThat(Files.exists(entry.directory))
                .`as`("after the confirmed delivery the entry directory is gone")
                .isFalse()
        }
    }

    @Nested
    inner class IndependentDocuments {
        @Test
        fun `AU-04 a document that always fails does not block the delivery of another`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START), backoffInitial = BACKOFF)
            val badStart = START
            val goodStart = START.plusSeconds(60)
            val bad = outbox.accept(OutputDocument(pdfSource(dir, "bad.pdf"), PAGE_COUNT, badStart, FINISH))
            val good = outbox.accept(OutputDocument(pdfSource(dir, "good.pdf"), PAGE_COUNT, goodStart, FINISH))
            val module = FailsOnStartModule(badStart)
            val events = RecordingListener()

            onePassRunner(outbox, OutputModules(listOf(module), listOf("fake")), events).run()

            assertThat(module.deliveredStarts)
                .`as`("the good document was delivered in the same pass the bad one failed in")
                .containsExactly(goodStart)
            assertThat(Files.exists(good.directory))
                .`as`("the delivered entry is removed")
                .isFalse()
            assertThat(Files.exists(bad.directory))
                .`as`("the failing entry keeps its directory with metadata beside the PDF")
                .isTrue()
            assertThat(Files.exists(bad.directory.resolve(METADATA_FILE_NAME))).isTrue()
            assertThat(readMetadata(bad.directory.resolve(METADATA_FILE_NAME))!!.attempts)
                .`as`("the failing entry recorded its attempt")
                .isEqualTo(1)
            assertThat(events.delivered.map { it.id })
                .`as`("the runner reported exactly the good delivery")
                .containsExactly(good.id)
            assertThat(events.failed.map { it.id })
                .`as`("the runner reported exactly the bad failure")
                .containsExactly(bad.id)
        }
    }

    /** Builds a runner that performs exactly one pass over the due entries and then stops. */
    private fun onePassRunner(
        outbox: Outbox,
        modules: OutputModules,
        listener: OutboxRunnerListener = object : OutboxRunnerListener {},
    ): OutboxRunner {
        lateinit var runner: OutboxRunner
        runner =
            OutboxRunner(
                outbox,
                modules,
                pollInterval = Duration.ofMillis(1),
                listener = listener,
                // One pass is the whole test step: stop instead of waiting for the next poll.
                sleeper = { runner.stop() },
            )
        return runner
    }

    private fun alwaysFailing(): OutputModule = FlakyModule(failuresLeft = Int.MAX_VALUE)

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    /** Writes the golden PDF's bytes into [dir] under [name] and returns the path, as the document's source file. */
    private fun pdfSource(
        dir: Path,
        name: String,
    ): Path {
        val file = dir.resolve(name)
        Files.write(file, goldenPdfBytes())
        return file
    }

    private fun goldenPdfBytes(): ByteArray =
        javaClass.getResourceAsStream("/golden/$GOLDEN_PDF")?.use { it.readAllBytes() }
            ?: throw IllegalStateException("golden PDF not found on the classpath: /golden/$GOLDEN_PDF")

    /** A fake module that throws [failuresLeft] times and then records the delivered PDF bytes. */
    private class FlakyModule(
        var failuresLeft: Int,
    ) : OutputModule {
        override val name: String = "fake"
        val deliveries = mutableListOf<ByteArray>()

        override fun send(document: OutputDocument) {
            if (failuresLeft > 0) {
                failuresLeft--
                throw IOException("fake delivery failure")
            }
            deliveries.add(Files.readAllBytes(document.pdf))
        }
    }

    /** A fake module that fails every document with the given start instant and delivers the rest. */
    private class FailsOnStartModule(
        private val failingStart: Instant,
    ) : OutputModule {
        override val name: String = "fake"
        val deliveredStarts = mutableListOf<Instant>()

        override fun send(document: OutputDocument) {
            if (document.startedAt == failingStart) throw IOException("fake delivery failure for $failingStart")
            deliveredStarts.add(document.startedAt)
        }
    }

    /** Records runner callbacks so tests assert on events instead of scraping the log. */
    private class RecordingListener : OutboxRunnerListener {
        val delivered = mutableListOf<OutboxEntry>()
        val failed = mutableListOf<OutboxEntry>()

        override fun onDelivered(entry: OutboxEntry) {
            delivered.add(entry)
        }

        override fun onDeliveryFailed(
            entry: OutboxEntry,
            reason: String,
        ) {
            failed.add(entry)
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-10-03T09:00:00Z")
        val FINISH: Instant = START.plusSeconds(45)

        /** Short backoff for tests: the redelivery is reached by advancing the fixed clock, never by waiting. */
        val BACKOFF: Duration = Duration.ofMillis(10)

        const val PAGE_COUNT = 3
        const val GOLDEN_PDF = "three_pages_300dpi.pdf"
    }
}
