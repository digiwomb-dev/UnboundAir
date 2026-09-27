package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.TestImages
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
 * Integration tests for [ScanLoop] idle pacing (DL-06).
 *
 * What is pinned:
 * - with the idle feature switched off (`idleAfter = null`, the default), the
 *   polling pace never changes no matter how long the scanner has been quiet:
 *   after a full hour of an empty tray, every wait is still the configured
 *   `pollInterval`;
 * - with the feature on, a quiet time that has passed `idleAfter` slows the
 *   loop from `pollInterval` to `idleInterval`;
 * - a successfully scanned page refreshes `lastActivity`, which restarts the
 *   quiet time at zero and brings the loop back to the regular interval.
 *
 * Why the "default off" case is the most important one:
 * The idle interval is a guess until the `measure` command (OF-01) has
 * produced real numbers on the actual device. Until then the production
 * default must change nothing at all, and a regression that applied the idle
 * rule even when it is not configured would make a perfectly working scanner
 * behave as if it were a broken, sluggish one. The first test pins exactly
 * that boundary.
 *
 * Why a recording sleeper and a mutable clock keep this deterministic:
 * The loop computes its pace on its own thread from the injected clock. The
 * test advances that same clock instance, and a recording sleeper makes every
 * pace decision visible without waiting for real time. Because the loop
 * thread and the test race, every change of the clock or the tray is followed
 * by a baseline (`recorded.size`) and an Awaitility wait for a genuinely new
 * entry; assertions then only look at the entries recorded after that
 * baseline, not at the whole queue.
 */
class ScanLoopIdleIntegrationTest {
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
     * The [clock] is supplied by the test, because the test must advance the
     * very instance the loop reads from. [idleAfter] is `null` (feature off)
     * unless a test opts in.
     *
     * [PageProcessor] is given no steps on purpose: the idle rule is about
     * pacing, not about images, and an empty chain keeps `jpegtran` out of
     * the picture.
     */
    private fun loopFor(
        clock: MutableClock,
        fake: FakeScanner,
        dir: Path,
        idleAfter: Duration? = null,
        idleInterval: Duration = IDLE_INTERVAL,
        sleeper: (Duration) -> Unit = {},
    ): ScanLoop =
        ScanLoop(
            client = ScannerClient("127.0.0.1", fake.port),
            batch = Batch(clock, BATCH_TIMEOUT, dir, {}),
            processor = PageProcessor(emptyList()),
            workDir = dir,
            clock = clock,
            pollInterval = POLL_INTERVAL,
            offlinePollInterval = OFFLINE_POLL_INTERVAL,
            idleAfter = idleAfter,
            idleInterval = idleInterval,
            sleeper = sleeper,
        )

    @Test
    fun `DL-06 without the setting the interval never changes`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val clock = MutableClock(START)
            val recorded = ConcurrentLinkedQueue<Duration>()
            val loop = loopFor(clock, fake, tempDir, sleeper = { recorded.add(it) })

            val t = thread(start = true) { loop.run() }
            try {
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { loop.pollCount >= 1 }

                val baseline = recorded.size
                clock.advance(Duration.ofHours(1))
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { recorded.size > baseline }

                assertThat(recorded.toList())
                    .`as`(
                        "DL-06 default off: with idleAfter unset, an hour of quiet must not change a " +
                            "single wait - the pace must be the configured interval, before and after",
                    ).isNotEmpty()
                    .allMatch { it == POLL_INTERVAL }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    @Test
    fun `DL-06 with the setting the loop slows down`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val clock = MutableClock(START)
            val recorded = ConcurrentLinkedQueue<Duration>()
            val loop =
                loopFor(
                    clock,
                    fake,
                    tempDir,
                    idleAfter = IDLE_AFTER,
                    sleeper = { recorded.add(it) },
                )

            val t = thread(start = true) { loop.run() }
            try {
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { loop.pollCount >= 1 }

                val baseline = recorded.size
                clock.advance(IDLE_AFTER.multipliedBy(4))
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { recorded.size > baseline }

                assertThat(recorded.toList().drop(baseline))
                    .`as`(
                        "DL-06: once the quiet time has passed idleAfter, every wait must be the idle " +
                            "interval instead of the regular one",
                    ).isNotEmpty()
                    .contains(IDLE_INTERVAL)
                    .allMatch { it == IDLE_INTERVAL }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    @Test
    fun `DL-06 a scanned page resets the idleness`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val clock = MutableClock(START)
            val recorded = ConcurrentLinkedQueue<Duration>()
            val loop =
                loopFor(
                    clock,
                    fake,
                    tempDir,
                    idleAfter = IDLE_AFTER,
                    sleeper = { recorded.add(it) },
                )

            val t = thread(start = true) { loop.run() }
            try {
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { loop.pollCount >= 1 }

                // First let the loop go idle, and check the precondition.
                val idleBaseline = recorded.size
                clock.advance(IDLE_AFTER.multipliedBy(4))
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { recorded.size > idleBaseline }
                assertThat(recorded.toList().drop(idleBaseline))
                    .`as`("precondition: the loop must be polling at the idle pace before the page arrives")
                    .allMatch { it == IDLE_INTERVAL }

                // Now a page arrives; the scan must bring the loop back to the regular pace.
                fake.loadSheets(listOf(TestImages.bytes("envelope_dl_300dpi_raw.jpg")))
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { loop.pageCount >= 1 }

                val fresh = recorded.size
                await()
                    .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                    .until { recorded.size > fresh }

                assertThat(recorded.toList().drop(fresh))
                    .`as`(
                        "DL-06: a scanned page refreshes lastActivity, so the quiet time restarts at " +
                            "zero and every subsequent wait must be the regular interval again",
                    ).isNotEmpty()
                    .allMatch { it == POLL_INTERVAL }
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

        /**
         * Deliberately different from [IDLE_INTERVAL]. All three paces must be
         * distinct values: if the offline and the idle interval were the same,
         * a loop that wrongly treated an idle scanner as an unreachable one
         * would record the expected duration anyway and the test would pass
         * while the behaviour was wrong.
         */
        val OFFLINE_POLL_INTERVAL: Duration = Duration.ofMillis(70)

        /**
         * The enabled cases. `idleAfter` is several `pollInterval`s long so a
         * few regular turns pass before the rule kicks in; `idleInterval` is
         * distinct from both other paces, so the recorded queue tells all
         * three apart.
         */
        val IDLE_AFTER: Duration = Duration.ofMillis(50)
        val IDLE_INTERVAL: Duration = Duration.ofMillis(100)

        val BATCH_TIMEOUT: Duration = Duration.ofSeconds(20)

        /** Generous: the bound only has to be reached on a loaded machine, never waited out. */
        const val AWAIT_SECONDS = 10L
        const val THREAD_JOIN_MILLIS = 5_000L
    }
}
