package dev.digiwomb.unboundair.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.service.Batch
import dev.digiwomb.unboundair.service.ScanLoop
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Integration test for the per-page log line (KL-02).
 *
 * KL-02 names four values that must appear for every scanned page: **scan
 * duration, transfer duration, size, and the dimensions in mm after cropping**.
 * The acceptance criterion in `docs/plan.md` is exactly that the log entry of
 * one page carries all four.
 *
 * The test drives the real thing end to end -- a real TCP connection to the
 * [FakeScanner], a real crop through `jpegtran`, the real [ScanLoop] -- and
 * reads the line back out of Logback. Anything less would not prove the values
 * are correct: the durations are only measurable inside the scanner client, and
 * the millimetres only exist after the crop has actually run.
 *
 * The values are asserted individually rather than against one expected string.
 * A single string comparison would pin the wording of the message, so every
 * rephrasing would break the test while a missing value could still slip
 * through if the wording changed with it.
 *
 * Offline (DC-03): a loopback TCP server, the committed fixture, and the
 * `jpegtran` of the dev container.
 */
class PageLogIntegrationTest {
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
    fun `KL-02 the log entry of a scanned page carries all four values`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            // A delay on the scan phase, so the two durations are demonstrably
            // measured separately rather than one of them being zero by
            // accident on a fast loopback connection.
            fake.scanDelayMillis = SCAN_DELAY_MILLIS
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
            fake.start()

            val clock = Clock.fixed(START, ZoneOffset.UTC)
            val loop =
                ScanLoop(
                    client = ScannerClient("127.0.0.1", fake.port),
                    batch = Batch(clock, Duration.ofMinutes(10), tempDir, {}),
                    // The real crop step, so the millimetres describe the page
                    // after cropping as KL-02 requires, not the raw scan.
                    processor = PageProcessor(listOf(CropStep())),
                    workDir = tempDir,
                    clock = clock,
                    pollInterval = POLL_INTERVAL,
                    offlinePollInterval = POLL_INTERVAL,
                    sleeper = { Thread.sleep(SLEEP_MILLIS) },
                )

            val t = thread(start = true) { loop.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }

            val line =
                appender.list
                    .filter { it.level == Level.INFO }
                    .map { it.formattedMessage }
                    .single { it.startsWith("page 1 ") }

            // 1 + 2: both durations, and the scan must reflect the delay the
            // device took, which is what tells the two phases apart.
            val scanMillis =
                Regex("scanned in (\\d+) ms")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            val transferMillis =
                Regex("transferred in (\\d+) ms")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()

            assertThat(scanMillis)
                .`as`("KL-02 value 1 of 4: the scan duration must be in the line -- '%s'", line)
                .isNotNull()
            assertThat(scanMillis!!)
                .`as`("the scan duration must cover the time the device spent pulling the sheet through")
                .isGreaterThanOrEqualTo(SCAN_DELAY_MILLIS)
            assertThat(transferMillis)
                .`as`("KL-02 value 2 of 4: the transfer duration must be in the line -- '%s'", line)
                .isNotNull()

            // 3: the size, which must be that of the processed page.
            val size =
                Regex("(\\d+) bytes")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            assertThat(size)
                .`as`("KL-02 value 3 of 4: the size must be in the line -- '%s'", line)
                .isNotNull()
            assertThat(size!!)
                .`as`("the size must be that of the real page, not a placeholder")
                .isGreaterThan(0)

            // 4: the dimensions in mm after cropping. The envelope fixture is
            // 1776 x 2769 px raw and is cropped to roughly 1216 x 2494 px
            // (SV-01), so at 300 dpi the height lands near 211 mm. Checking a
            // generous range rather than an exact value keeps this test about
            // KL-02 and leaves the exact crop geometry to the SV-01 tests.
            val dimensions = Regex("([\\d.]+) x ([\\d.]+) mm").find(line)
            assertThat(dimensions)
                .`as`("KL-02 value 4 of 4: the dimensions in mm must be in the line -- '%s'", line)
                .isNotNull()

            val widthMm = dimensions!!.groupValues[1].toDouble()
            val heightMm = dimensions.groupValues[2].toDouble()

            assertThat(widthMm)
                .`as`("the width in mm must describe the cropped page, not the raw scan (which is 150 mm wide)")
                .isBetween(80.0, 130.0)
            assertThat(heightMm)
                .`as`("the height in mm must describe the cropped page, close to the 211 mm of a DL envelope")
                .isBetween(190.0, 240.0)
        }
    }

    /**
     * The logged page number is the position within the current document, not
     * the run-wide counter: after the first document closes, the first page of
     * the second document is logged as "page 1" again.
     */
    @Test
    fun `KL-02 the first page of a second document is logged as page 1`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE), TestImages.bytes(ENVELOPE)))
            fake.start()

            val clock = Clock.fixed(START, ZoneOffset.UTC)
            val loop =
                ScanLoop(
                    client = ScannerClient("127.0.0.1", fake.port),
                    batch = Batch(clock, Duration.ofMinutes(10), tempDir, {}),
                    processor = PageProcessor(listOf(CropStep())),
                    workDir = tempDir,
                    clock = clock,
                    pollInterval = POLL_INTERVAL,
                    offlinePollInterval = POLL_INTERVAL,
                    sleeper = { Thread.sleep(SLEEP_MILLIS) },
                )

            val t = thread(start = true) { loop.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }
                fake.goOffline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until {
                    appender.list.any { it.level == Level.INFO && it.formattedMessage.startsWith("scanner is offline") }
                }
                fake.comeOnline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 2 }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }

            val firstPages =
                appender.list
                    .filter { it.level == Level.INFO }
                    .map { it.formattedMessage }
                    .filter { it.startsWith("page 1 ") }

            assertThat(firstPages)
                .`as`("KL-02: each one-page document logs its page as page 1, so two documents log two page-1 lines")
                .hasSize(2)
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /** Makes the scan phase measurably longer than the transfer on loopback. */
        const val SCAN_DELAY_MILLIS = 250L

        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
        const val SLEEP_MILLIS = 5L
    }
}
