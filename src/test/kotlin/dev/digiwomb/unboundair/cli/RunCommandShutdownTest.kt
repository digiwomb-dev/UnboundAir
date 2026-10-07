package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.config.UnboundAirProperties
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.service.Batch
import dev.digiwomb.unboundair.service.ScanLoop
import dev.digiwomb.unboundair.service.outputPipeline
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The DL-07 acceptance, word for word: *"Beenden mit offenem Batch → das PDF liegt in der Outbox."*
 * (AU-04: the outbox is the persistence buffer the PDF lands in.)
 *
 * One sheet is scanned so a batch is open, then the service is stopped. The assertion is that the
 * document directory appears in the outbox with `document.pdf` and `metadata.json`, and that the
 * PDF holds the page that was scanned.
 *
 * Why the batch must still be open: if the batch timeout were allowed to elapse, the batch would
 * close on its own (DL-04) and the test would pass without the shutdown path ever running — a test
 * proving the wrong thing. Two things keep the window from firing here: the clock is frozen, so
 * `closeIfDue` can never become due, and the timeout itself is an hour, so even a clock that moved
 * could not expire it during the run. The assertion that the outbox is still empty *before* the
 * stop is what separates this test from DL-04's: the document may only appear *after* the stop.
 *
 * How the shutdown is triggered: **not** by a real signal, and the registered hook is **not** run
 * by killing the JVM — the test process is the Gradle worker, and killing it fails the build.
 * Instead the command runs on its own thread and the test calls the same `stop()` path the hook
 * body (`onShutdownSignal`, which only delegates to `stop()`) calls, from the test thread. That
 * exercises the cross-thread stop, the ordering (loop first so the open batch closes into the
 * outbox, then the runner) and the waiting — everything DL-07 actually requires. The signal
 * registration itself (`Runtime.addShutdownHook`, one line of JDK API) is deliberately not
 * exercised here; nobody should read more into this test than that.
 *
 * The runner interval is deliberately **not** shortened: with no output module configured,
 * `OutputModules.send` is a no-op success, so a fast runner would deliver-and-delete the entry
 * before the assertion ever sees it, while an hourly runner would hang `stop()`'s unbounded join
 * in its sleep. Ten seconds is the deterministic middle: the runner's only pass runs on the empty
 * outbox at startup, sleeps through the shutdown, and exits without ever seeing the entry.
 *
 * Offline (DC-03): loopback [FakeScanner], committed fixtures, the `jpegtran` of the dev
 * container. No output module is configured, so nothing uploads.
 */
class RunCommandShutdownTest {
    private class FrozenClock(
        private var now: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = FrozenClock(now, zone)

        override fun instant(): Instant = now
    }

    @Test
    fun `DL-07 stopping with an open batch leaves the one-page PDF in the outbox (AU-04)`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(A4)))
            fake.start()

            val clock = FrozenClock(START)
            val outboxRoot = tempDir.resolve("outbox")
            val properties =
                UnboundAirProperties(
                    // Deliberately long: see the class KDoc. The scan loop below gets its own
                    // millisecond intervals; this property only drives the outbox runner.
                    pollInterval = RUNNER_POLL_INTERVAL,
                    outbox = UnboundAirProperties.OutboxProperties(path = outboxRoot.toString()),
                )
            val pipeline = outputPipeline(properties, clock, ZoneOffset.UTC, warn = {})
            val loop =
                ScanLoop(
                    client = ScannerClient("127.0.0.1", fake.port),
                    batch = Batch(clock, BATCH_TIMEOUT, tempDir.resolve("batch"), pipeline.sink),
                    // The production chain, not an empty one: crop then grayscale, the same
                    // steps the service uses, so the SV-05 size check below reads real output.
                    processor = PageProcessor(listOf(CropStep(), GrayscaleStep())),
                    workDir = Files.createDirectories(tempDir.resolve("work")),
                    clock = clock,
                    pollInterval = SCAN_POLL_INTERVAL,
                    offlinePollInterval = SCAN_POLL_INTERVAL,
                    sleeper = { Thread.sleep(SLEEP_MILLIS) },
                )
            val command = RunCommand(loop, pipeline.runner)

            val commandThread = thread(start = true, name = "run-command") { command.run() }
            try {
                // One sheet goes in; the batch is now open and, with the clock frozen and an
                // hour-long window, cannot close on its own (DL-04 is locked out by construction).
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }

                // The DL-04 separator: nothing may have reached the outbox yet. If the document
                // were already here, the shutdown path below would prove nothing.
                assertThat(pdfsIn(outboxRoot))
                    .`as`("DL-07: the batch is still open, so the outbox must still be empty before the stop")
                    .isEmpty()

                // The hook's body path, from the test thread: closes the open batch into the
                // outbox and returns only once it is persisted. A real signal would kill the
                // Gradle worker, so the one line of JDK API that registers the hook stays
                // untested by design (see the class KDoc).
                command.stop()
            } finally {
                // Belt and braces: stop() is idempotent, so a second call from here is harmless
                // even on the failure paths above.
                command.stop()
                commandThread.join(THREAD_JOIN_MILLIS)
            }

            assertThat(commandThread.isAlive)
                .`as`("DL-07: a shutdown that leaves the command thread running is a failure even if the file is there")
                .isFalse()

            val pdfs = pdfsIn(outboxRoot)
            assertThat(pdfs)
                .`as`("DL-07: stopping with an open batch must leave exactly one document in the outbox (AU-04)")
                .hasSize(1)

            val entryDir = pdfs.single().parent
            assertThat(Files.exists(entryDir.resolve("metadata.json")))
                .`as`("AU-04: the outbox entry keeps its metadata beside the PDF")
                .isTrue()
            assertThat(Files.size(entryDir.resolve("metadata.json")))
                .`as`("AU-04: the persisted metadata must not be empty")
                .isGreaterThan(0)

            Loader.loadPDF(pdfs.single().toFile()).use { pdf ->
                assertThat(pdf.numberOfPages)
                    .`as`("DL-07: one sheet was scanned, so the shutdown document holds exactly one page")
                    .isEqualTo(1)

                // SV-05 on the persisted bytes, read back with PDFBox — the independent checker
                // (docs/plan.md): the sheet is the A4 fixture, 2464 px wide at 300 dpi.
                assertThat(pdf.getPage(0).mediaBox.width)
                    .`as`("SV-05: page size is pixels / dpi * 72 pt, taken from the real scan")
                    .isCloseTo(2464f / 300f * 72f, within(TOLERANCE_POINTS))
            }
        }
    }

    private fun pdfsIn(outboxRoot: Path): List<Path> {
        if (!Files.exists(outboxRoot)) return emptyList()
        Files.walk(outboxRoot).use { walk ->
            return walk.filter { it.fileName.toString() == "document.pdf" }.toList()
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")

        /** The scan loop polls every few milliseconds; production keeps seconds. */
        val SCAN_POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /**
         * Long enough that it cannot fire during the run (with the frozen clock it could never
         * fire at all): the batch may only close through the shutdown path, never on its own.
         */
        val BATCH_TIMEOUT: Duration = Duration.ofHours(1)

        /**
         * The outbox runner's interval (see the class KDoc): its only pass runs on the empty
         * outbox at startup, then it sleeps through the shutdown. Short enough that `stop()`'s
         * unbounded join returns promptly.
         */
        val RUNNER_POLL_INTERVAL: Duration = Duration.ofSeconds(10)

        const val A4 = "din_a4_300dpi_raw.jpg"
        const val TOLERANCE_POINTS = 1.0f

        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 30_000L
        const val SLEEP_MILLIS = 5L
    }
}
