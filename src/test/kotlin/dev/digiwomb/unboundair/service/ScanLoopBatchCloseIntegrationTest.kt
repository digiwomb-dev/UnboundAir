package dev.digiwomb.unboundair.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.Jbig2Enc
import dev.digiwomb.unboundair.processing.ColorMode
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.MonochromeStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.processing.PageSettings
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatNoException
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Integration tests for a batch that cannot be closed (DL-05).
 *
 * A failed close -- for example the `jbig2` encoder failing (SV-08) -- must
 * neither kill the service loop nor escape from `stop` into the shutdown
 * hook: the failure is logged, the batch stays open for a retry, and polling
 * carries on.
 *
 * Offline (DC-03): a loopback TCP server and a captured logger.
 */
class ScanLoopBatchCloseIntegrationTest {
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
    }

    /**
     * A close that fails when the device goes offline is logged and the loop
     * keeps polling: the exception must not kill the `scan-loop` thread.
     */
    @Test
    fun `DL-05 a failed batch close is logged and the loop keeps polling`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
            fake.start()

            val clock = MutableClock(START)
            val loop =
                ScanLoop(
                    client = ScannerClient("127.0.0.1", fake.port),
                    batch = Batch(clock, BATCH_TIMEOUT, tempDir, {}),
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

            val original = Jbig2Enc.program
            Jbig2Enc.program = "no-such-jbig2-binary"
            try {
                val t = thread(start = true) { loop.run() }
                try {
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }
                    fake.goOffline()
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until {
                        appender.list.any { it.level == Level.ERROR && it.formattedMessage.contains("closing the batch failed") }
                    }
                    assertThat(loop.isRunning)
                        .`as`("DL-05: a failed batch close must not kill the loop")
                        .isTrue()
                    val polls = loop.pollCount
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount > polls }
                } finally {
                    fake.comeOnline()
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }
            } finally {
                Jbig2Enc.program = original
            }
        }
    }

    /**
     * `stop` must return normally even when the open batch cannot be closed:
     * the failure is logged instead of escaping into the shutdown hook.
     */
    @Test
    fun `DL-07 stop returns normally even when the open batch cannot be closed`(
        @TempDir tempDir: Path,
    ) {
        val clock = MutableClock(START)
        val batch = Batch(clock, BATCH_TIMEOUT, tempDir, {})
        batch.addPage(pbm(), DPI)

        val original = Jbig2Enc.program
        Jbig2Enc.program = "no-such-jbig2-binary"
        try {
            val loop =
                ScanLoop(
                    client = ScannerClient("127.0.0.1", UNUSED_PORT),
                    batch = batch,
                    processor = PageProcessor(emptyList()),
                    workDir = tempDir,
                    clock = clock,
                    pollInterval = POLL_INTERVAL,
                    offlinePollInterval = POLL_INTERVAL,
                    sleeper = { Thread.sleep(SLEEP_MILLIS) },
                )
            assertThatNoException()
                .`as`("DL-07: stop must not throw when the open batch cannot be closed")
                .isThrownBy { loop.stop() }
            assertThat(batch.isOpen)
                .`as`("the batch stays open for a retry")
                .isTrue()
        } finally {
            Jbig2Enc.program = original
        }
    }

    /**
     * Builds a small binary PBM (`P4`) page, mirroring the `pbm` helper of
     * [BatchTest]: the header `P4\n<w> <h>\n` followed by the packed row
     * bytes, MSB first.
     */
    private fun pbm(
        width: Int = 64,
        height: Int = 64,
    ): ByteArray {
        val rowBytes = (width + 7) / 8
        val body =
            (0 until height)
                .map { y -> ByteArray(rowBytes) { x -> (((x + y) % 4) * 0x55).toByte() } }
                .fold(byteArrayOf()) { acc, row -> acc + row }
        return "P4\n$width $height\n".toByteArray(Charsets.US_ASCII) + body
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
        const val DPI = 300

        /** Never bound: the client is only constructed, never used. */
        const val UNUSED_PORT = 9
    }
}
