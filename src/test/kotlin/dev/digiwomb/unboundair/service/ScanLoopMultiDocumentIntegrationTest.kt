package dev.digiwomb.unboundair.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.processing.ColorMode
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.MonochromeStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.processing.PageSettings
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Integration test for two consecutive black-and-white documents (SV-08, DL-04).
 *
 * This is the whole-loop regression for the defect fixed in #220/#221/#222:
 * closing a two-page bw document leaves the `jbig2` output files (`pages.0000`,
 * `pages.0001`) in the shared work directory, and a later one-page bw document
 * then counted those stale files and failed its encoder count check with
 * `Jbig2EncException` ("produced 2 page file(s) for 1 input page(s)"), killing
 * the `scan-loop` thread and losing the document.
 *
 * The test drives the real [ScanLoop] with a black-and-white processor and the
 * real `jbig2`, closes a two-page document via the offline trigger (DL-04),
 * then opens and closes a one-page document in the same work directory, and
 * asserts the observable symptoms stay away: both documents are delivered with
 * the right page counts, no close fails (no ERROR log, the loop stays alive),
 * and the per-page log numbers restart at 1 for the second document.
 *
 * Offline (DC-03): a loopback TCP server, the committed fixture, and the
 * `jbig2` of the dev container.
 */
class ScanLoopMultiDocumentIntegrationTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachAppender() {
        logger = LoggerFactory.getLogger(ScanLoop::class.java) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `SV-08 DL-04 two consecutive bw documents each deliver their own PDF without an encoder failure`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE), TestImages.bytes(ENVELOPE)))
            fake.start()

            val clock = Clock.fixed(START, ZoneOffset.UTC)
            val delivered = ConcurrentLinkedQueue<ScannedDocument>()
            val loop =
                ScanLoop(
                    client = ScannerClient("127.0.0.1", fake.port),
                    batch = Batch(clock, BATCH_TIMEOUT, tempDir, sink = { delivered.add(it) }),
                    processor =
                        PageProcessor(
                            listOf(
                                CropStep(),
                                GrayscaleStep(),
                                MonochromeStep(PageSettings(colorMode = ColorMode.BW)),
                            ),
                        ),
                    workDir = tempDir,
                    clock = clock,
                    pollInterval = POLL_INTERVAL,
                    offlinePollInterval = POLL_INTERVAL,
                    sleeper = { Thread.sleep(SLEEP_MILLIS) },
                )

            val t = thread(start = true) { loop.run() }
            try {
                // First document: two sheets, then the device goes offline so the
                // batch closes (DL-04) with its two bitmap pages.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 2 }
                fake.goOffline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { delivered.size >= 1 }

                // Second document: the device returns with one more sheet, which
                // must open a fresh batch in the same work directory.
                fake.comeOnline()
                fake.insertSheet(TestImages.bytes(ENVELOPE))
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 3 }
                fake.goOffline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { delivered.size >= 2 }

                assertThat(delivered.map { it.pageCount })
                    .`as`("SV-08: the second document must close on its own, not inherit the first document's encoder files")
                    .containsExactly(2, 1)
                assertThat(loop.isRunning)
                    .`as`("the loop must survive both closes: the second document must not kill the scan-loop thread")
                    .isTrue()
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }

            assertThat(appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage })
                .`as`("no close may fail: the stale-page defect surfaces as a 'closing the batch failed' error")
                .noneMatch { it.contains("closing the batch failed") }

            val pageNumbers =
                appender.list
                    .filter { it.level == Level.INFO }
                    .map { it.formattedMessage }
                    .filter { it.startsWith("page ") }
                    .map { Regex("^page (\\d+) ").find(it)!!.groupValues[1].toInt() }
            assertThat(pageNumbers)
                .`as`("KL-02: the page number restarts at 1 for the second document")
                .containsExactly(1, 2, 1)
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /** Long enough that only the offline trigger closes the batch, never the timeout. */
        val BATCH_TIMEOUT: Duration = Duration.ofMinutes(10)

        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
        const val SLEEP_MILLIS = 5L
    }
}
