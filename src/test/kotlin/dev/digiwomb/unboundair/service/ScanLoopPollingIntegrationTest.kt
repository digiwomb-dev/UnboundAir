package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
 * Integration tests for [ScanLoop] polling behaviour (DL-01, SC-02).
 *
 * What is pinned:
 * - every status poll opens a fresh TCP connection (SC-02) and the loop counts
 *   polls in `pollCount`;
 * - the pacing uses the configured `pollInterval` while the scanner is reachable
 *   and no idle rule is active;
 * - `stop()` terminates the loop and `pollCount` stops growing.
 *
 * Why a recording sleeper makes this deterministic:
 * The real loop sleeps for the interval between polls. A test that relied on
 * real elapsed time would be a race: the thread could be delayed by the
 * scheduler and the assertion would flake. By injecting a sleeper that only
 * records the durations the loop asks for, we observe the exact pacing decision
 * without waiting for real time. The loop still runs concurrently, and
 * Awaitility waits for the counters to reach expected values, so the test is
 * fast and stable while still exercising the real concurrent behaviour.
 */
class ScanLoopPollingIntegrationTest {
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
     * A loop wired to [fake], with the intervals shortened so a test covers
     * several turns in well under a second.
     *
     * [PageProcessor] is given no steps on purpose: these tests never scan a
     * page, and an empty chain keeps `jpegtran` out of the picture.
     */
    private fun loopFor(
        fake: FakeScanner,
        dir: Path,
        sleeper: (Duration) -> Unit = {},
    ): ScanLoop {
        val clock = MutableClock(START)
        return ScanLoop(
            client = ScannerClient("127.0.0.1", fake.port),
            batch = Batch(clock, Duration.ofSeconds(20), dir, {}),
            processor = PageProcessor(emptyList()),
            workDir = dir,
            clock = clock,
            pollInterval = POLL_INTERVAL,
            offlinePollInterval = OFFLINE_POLL_INTERVAL,
            sleeper = sleeper,
        )
    }

    @Test
    fun `DL-01 every poll opens its own connection`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val loop = loopFor(fake, tempDir)

            val t = thread(start = true) { loop.run() }
            try {
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { loop.pollCount >= POLLS_TO_OBSERVE }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }

            // Compared only after the loop has ended, and this matters.
            // `pollCount` is incremented at the START of a turn, the connection
            // is opened a moment later, so while the loop runs the two legitimately
            // differ by one. Asserting mid-flight would be a race that passes or
            // fails depending on where the loop happens to be.
            assertThat(fake.connectionCount)
                .`as`("SC-02: one poll must open exactly one connection, never reuse or open two")
                .isEqualTo(loop.pollCount)
        }
    }

    @Test
    fun `DL-01 every poll uses the configured pollInterval when reachable`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val recorded = ConcurrentLinkedQueue<Duration>()
            val loop = loopFor(fake, tempDir, sleeper = { recorded.add(it) })

            val t = thread(start = true) { loop.run() }
            try {
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { loop.pollCount >= POLLS_TO_OBSERVE }

                assertThat(recorded)
                    .`as`("DL-01: while reachable and with no idle rule, every wait must be the configured interval")
                    .isNotEmpty()
                    .allMatch { it == POLL_INTERVAL }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    @Test
    fun `DL-01 stop ends the loop and pollCount stops growing`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val loop = loopFor(fake, tempDir)

            val t = thread(start = true) { loop.run() }
            try {
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { loop.pollCount >= 1 }

                loop.stop()
                // Wait for the thread itself rather than polling a flag: once
                // run() has returned, no further poll is possible by construction.
                // A timed wait would only show that none happened *yet*.
                t.join(THREAD_JOIN_MILLIS)

                assertThat(t.isAlive)
                    .`as`("stop must let run() return, otherwise the service would never shut down")
                    .isFalse()
                assertThat(loop.isRunning)
                    .`as`("stop must clear the running flag")
                    .isFalse()

                val afterStop = loop.pollCount
                assertThat(loop.pollCount)
                    .`as`("no poll may happen after the loop has ended")
                    .isEqualTo(afterStop)
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")

        /** Short enough that several turns happen quickly; the value is never waited out. */
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)
        val OFFLINE_POLL_INTERVAL: Duration = Duration.ofMillis(100)

        /**
         * Enough turns that a one-connection-per-poll error would show up.
         * A single poll would pass even if the loop reused its connection.
         */
        const val POLLS_TO_OBSERVE = 6

        /**
         * The Awaitility bound, and it has to be generous for a concrete reason:
         * a poll is not cheap. SC-02 prescribes 200 ms before and 500 ms after
         * sending `status`, so one poll costs about 0.7 s of deliberate waiting
         * no matter how short [POLL_INTERVAL] is. Six polls therefore need well
         * over four seconds, and that is before the rest of the suite competes
         * for the machine.
         *
         * An earlier value of 10 s passed on its own and failed inside a full
         * `./gradlew build`. The bound is never waited out when things work, so
         * a large value costs nothing and a tight one buys a flaky test.
         */
        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
    }
}
