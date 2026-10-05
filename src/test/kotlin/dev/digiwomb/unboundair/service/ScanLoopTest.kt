package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tests that [ScanLoop] scans with its configured resolution (issue #223: SC-07, SC-08).
 * "Integration" layer of docs/teststrategie.md: a real [ScannerClient] talks over a real
 * TCP socket to the loopback [FakeScanner], and the test observes which dpi command arrived.
 *
 * Offline (DC-03): only the loopback [FakeScanner] is involved. The processor runs an empty
 * chain, so no external program is called.
 */
class ScanLoopTest {
    private class MutableClock(
        private var now: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now
    }

    @Nested
    inner class Dpi {
        @Test
        fun `SC-08 the loop scans with the configured dpi`(
            @TempDir tempDir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
                fake.start()

                val loop = loopFor(fake, tempDir, dpi = 600)

                val t = thread(start = true) { loop.run() }
                try {
                    await()
                        .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                        .until { loop.pageCount >= 1 }
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }

                assertThat(fake.receivedCommands)
                    .`as`("SC-08: a loop configured with dpi 600 must ask the scanner for 600 dpi")
                    .contains("dpi600")
            }
        }

        @Test
        fun `SC-08 the loop defaults to 300 dpi without a configured value`(
            @TempDir tempDir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
                fake.start()

                val loop = loopFor(fake, tempDir)

                val t = thread(start = true) { loop.run() }
                try {
                    await()
                        .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                        .until { loop.pageCount >= 1 }
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }

                assertThat(fake.receivedCommands)
                    .`as`("SC-08: a loop without a configured dpi must ask the scanner for 300 dpi")
                    .contains("dpi300")
                assertThat(fake.receivedCommands)
                    .`as`("SC-08: the default loop must never ask for 600 dpi on its own")
                    .doesNotContain("dpi600")
            }
        }
    }

    private fun loopFor(
        fake: FakeScanner,
        dir: Path,
        dpi: Int = 300,
    ): ScanLoop {
        val clock = MutableClock(START)
        return ScanLoop(
            client = ScannerClient("127.0.0.1", fake.port),
            batch = Batch(clock, BATCH_TIMEOUT, dir, {}),
            processor = PageProcessor(emptyList()),
            workDir = dir,
            clock = clock,
            pollInterval = POLL_INTERVAL,
            offlinePollInterval = POLL_INTERVAL,
            dpi = dpi,
            sleeper = { Thread.sleep(SLEEP_MILLIS) },
        )
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /** Long enough that only `stop` closes the batch, never the timeout. */
        val BATCH_TIMEOUT: Duration = Duration.ofMinutes(10)

        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
        const val SLEEP_MILLIS = 5L
    }
}
