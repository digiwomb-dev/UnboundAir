package dev.digiwomb.unboundair.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.processing.PageProcessor
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Integration tests for a page that fails mid-scan (DL-05).
 *
 * DL-05: "discard the failed page and log it, the batch stays open". All three
 * halves of that sentence matter, and a plausible implementation gets one of
 * them wrong:
 *
 * - **the page is discarded** -- a half-transferred scan must not become a
 *   corrupt page in the document;
 * - **the batch stays open** -- this is the one worth guarding. A loop that let
 *   the exception escape would end the service over a single bad sheet, and the
 *   pages already scanned would be lost with it;
 * - **the failure is logged** -- otherwise a page vanishes with no trace and
 *   nobody can explain the gap in the document.
 *
 * The failure is provoked the way the device would produce it: the scanner
 * disappears *during* the transfer. [FakeScanner.goOffline] drops the open
 * connection mid-scan, which is exactly what a device switching itself off
 * between two sheets looks like.
 *
 * Offline (DC-03): a loopback TCP server and a captured logger.
 */
class ScanLoopFailedPageIntegrationTest {
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

    private class MutableClock(
        private var now: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    /**
     * A page that fails while it is being transferred costs that page and
     * nothing else: the loop survives, keeps polling, and the next sheet is
     * scanned normally.
     */
    @Test
    fun `DL-05 a page that fails mid-scan is discarded and the loop carries on`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            // A slow jpegsize gives the test a window in which the scan is in
            // flight and the device can disappear underneath it.
            fake.scanDelayMillis = SCAN_DELAY_MILLIS
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
            fake.start()

            val clock = MutableClock(START)
            val failures = ConcurrentLinkedQueue<String>()
            val loop =
                loopFor(
                    clock,
                    fake,
                    tempDir,
                    listener =
                        object : ScanLoopListener {
                            override fun onPageFailed(reason: String) {
                                failures.add(reason)
                            }
                        },
                )

            val t = thread(start = true) { loop.run() }
            try {
                // Wait until the scan is under way, then pull the device away.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.receivedCommands.contains("jpegsize") }
                fake.goOffline()

                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { failures.isNotEmpty() }

                assertThat(loop.pageCount)
                    .`as`("DL-05: a page that failed in transfer must not be counted or added to the batch")
                    .isZero()
                assertThat(loop.isRunning)
                    .`as`("DL-05: the loop must survive a failed page - one bad sheet may not end the service")
                    .isTrue()

                // The device comes back and offers the sheet again; the loop
                // must pick it up as if nothing had happened.
                fake.comeOnline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }

                assertThat(loop.pageCount)
                    .`as`("after the failure the next page must be scanned normally")
                    .isEqualTo(1)
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    /**
     * The pages already collected must survive a failure: the batch stays open
     * and the good page is still in it.
     */
    @Test
    fun `DL-05 the batch survives a failed page and still delivers the good ones`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
            fake.start()

            val clock = MutableClock(START)
            val delivered = ConcurrentLinkedQueue<ScannedDocument>()
            val failures = ConcurrentLinkedQueue<String>()
            val loop =
                loopFor(
                    clock,
                    fake,
                    tempDir,
                    sink = { delivered.add(it) },
                    listener =
                        object : ScanLoopListener {
                            override fun onPageFailed(reason: String) {
                                failures.add(reason)
                            }
                        },
                )

            val t = thread(start = true) { loop.run() }
            try {
                // One good page first.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }

                // Then a sheet whose transfer will fail: the payload is
                // announced but the device dies while sending it.
                fake.scanDelayMillis = SCAN_DELAY_MILLIS
                fake.insertSheet(TestImages.bytes(ENVELOPE))
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.receivedCommands.count { it == "jpegsize" } >= 2 }
                fake.goOffline()

                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { delivered.isNotEmpty() }

                // Going offline is also the DL-04 close trigger, so the batch
                // is delivered here - and it must contain exactly the page that
                // succeeded, not the one that failed.
                assertThat(delivered.single().pageCount)
                    .`as`("DL-05: the good page must survive; the failed one must not be in the document")
                    .isEqualTo(1)
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    @Test
    fun `DL-05 the discarded page is reported in the log`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.scanDelayMillis = SCAN_DELAY_MILLIS
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
            fake.start()

            val clock = MutableClock(START)
            val failures = ConcurrentLinkedQueue<String>()
            val loop =
                loopFor(
                    clock,
                    fake,
                    tempDir,
                    listener =
                        object : ScanLoopListener {
                            override fun onPageFailed(reason: String) {
                                failures.add(reason)
                            }
                        },
                )

            val t = thread(start = true) { loop.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.receivedCommands.contains("jpegsize") }
                fake.goOffline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { failures.isNotEmpty() }

                assertThat(appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
                    .`as`("DL-05 requires a log entry, so a page never disappears without a trace")
                    .anyMatch { it.contains("page discarded") }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    private fun loopFor(
        clock: MutableClock,
        fake: FakeScanner,
        dir: Path,
        sink: (ScannedDocument) -> Unit = {},
        listener: ScanLoopListener = object : ScanLoopListener {},
    ): ScanLoop =
        ScanLoop(
            client = ScannerClient("127.0.0.1", fake.port),
            batch = Batch(clock, BATCH_TIMEOUT, dir, sink),
            processor = PageProcessor(emptyList()),
            workDir = dir,
            clock = clock,
            pollInterval = POLL_INTERVAL,
            offlinePollInterval = POLL_INTERVAL,
            listener = listener,
            sleeper = { Thread.sleep(SLEEP_MILLIS) },
        )

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /** Long enough that only the offline trigger closes the batch, never the timeout. */
        val BATCH_TIMEOUT: Duration = Duration.ofMinutes(10)

        /** Holds the scan open long enough for the test to pull the device away mid-transfer. */
        const val SCAN_DELAY_MILLIS = 400L

        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
        const val SLEEP_MILLIS = 5L
    }
}
