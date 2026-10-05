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
import org.junit.jupiter.api.Nested
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
 * Integration tests for [ScanLoop] pacing and failure paths (DL-01 to DL-06).
 *
 * What is pinned, beyond what the per-aspect loop tests already cover:
 * - the poll interval and the offline-poll interval are actually used
 *   (asserted via the recorded sleeper values, never via elapsed time);
 * - offline wins over idle when both apply (DL-02 over DL-06);
 * - the idle rule fires exactly at `idleAfter` and never when unconfigured;
 * - a page aborted mid-scan is dropped while the batch stays open (DL-05);
 * - the state-change log reports each transition exactly once (DL-02),
 *   observed both as log events and as listener values.
 *
 * Why a recording sleeper keeps this deterministic: the loop takes its wait
 * function as a constructor value, so the tests inject one that only writes
 * down the requested durations. A pacing rule becomes an ordinary assertion
 * over recorded values instead of a race against real time, while the loop
 * still runs on its own thread and Awaitility synchronises on state, never
 * on sleeps or poll counts alone.
 *
 * Offline (DC-03): a loopback TCP fake scanner and a captured logger only.
 */
class ScanLoopPacingIntegrationTest {
    private class MutableClock(
        @Volatile private var now: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    private fun loopFor(
        clock: MutableClock,
        fake: FakeScanner,
        dir: Path,
        sink: (ScannedDocument) -> Unit = {},
        idleAfter: Duration? = null,
        idleInterval: Duration = IDLE_INTERVAL,
        listener: ScanLoopListener = object : ScanLoopListener {},
        sleeper: (Duration) -> Unit = {},
    ): ScanLoop =
        ScanLoop(
            client = ScannerClient("127.0.0.1", fake.port),
            batch = Batch(clock, BATCH_TIMEOUT, dir, sink),
            processor = PageProcessor(emptyList()),
            workDir = dir,
            clock = clock,
            pollInterval = POLL_INTERVAL,
            offlinePollInterval = OFFLINE_POLL_INTERVAL,
            idleAfter = idleAfter,
            idleInterval = idleInterval,
            listener = listener,
            sleeper = sleeper,
        )

    @Nested
    inner class Pacing {
        @Test
        fun `DL-01 reachable polls wait the configured pollInterval and report every status`(
            @TempDir tempDir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.statusWord = "nopaper"
                fake.start()

                val clock = MutableClock(START)
                val recorded = ConcurrentLinkedQueue<Duration>()
                val statuses = ConcurrentLinkedQueue<String>()
                val loop =
                    loopFor(
                        clock,
                        fake,
                        tempDir,
                        listener =
                            object : ScanLoopListener {
                                override fun onStatus(status: String) {
                                    statuses.add(status)
                                }
                            },
                        sleeper = { recorded.add(it) },
                    )

                val t = thread(start = true) { loop.run() }
                try {
                    await()
                        .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                        .until { loop.pollCount >= POLLS_TO_OBSERVE }
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }

                // Compared after the loop has ended: while it runs, pollCount
                // and the listener queue may legitimately differ by one turn.
                assertThat(recorded)
                    .`as`("DL-01: while reachable every wait must be the configured poll interval")
                    .isNotEmpty()
                    .allMatch { it == POLL_INTERVAL }
                assertThat(statuses)
                    .`as`("DL-01: the listener must see one status per poll, no poll silent")
                    .hasSize(loop.pollCount)
                    .allMatch { it == "nopaper" }
            }
        }

        @Test
        fun `DL-02 offline polls wait the offlinePollInterval`(
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
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= 1 }

                    val baseline = recorded.size
                    fake.goOffline()
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { recorded.size > baseline + 1 }

                    // The first entry after the switch may still belong to the
                    // turn that was already under way while reachable.
                    assertThat(recorded.toList().drop(baseline + 1))
                        .`as`("DL-02: while unreachable every wait must be the offline interval")
                        .isNotEmpty()
                        .allMatch { it == OFFLINE_POLL_INTERVAL }
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }
            }
        }

        @Test
        fun `DL-02 offline wins over idle when both apply`(
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
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= 1 }

                    // Precondition: the loop is idle and paces at the idle
                    // interval before the device disappears.
                    clock.advance(IDLE_AFTER.multipliedBy(4))
                    val idleBaseline = recorded.size
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { recorded.size > idleBaseline }
                    assertThat(recorded.toList().drop(idleBaseline))
                        .`as`("precondition: the loop must be idle before the device goes offline")
                        .isNotEmpty()
                        .allMatch { it == IDLE_INTERVAL }

                    val offlineBaseline = recorded.size
                    fake.goOffline()
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { recorded.size > offlineBaseline + 1 }

                    assertThat(recorded.toList().drop(offlineBaseline + 1))
                        .`as`("DL-02 over DL-06: an unreachable device paces at the offline interval, not the idle one")
                        .isNotEmpty()
                        .allMatch { it == OFFLINE_POLL_INTERVAL }
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }
            }
        }
    }

    @Nested
    inner class IdleRule {
        @Test
        fun `DL-06 without the setting the pace never changes`(
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
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= 1 }

                    val baseline = recorded.size
                    clock.advance(Duration.ofHours(1))
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { recorded.size > baseline }

                    assertThat(recorded.toList())
                        .`as`("DL-06 default off: an hour of quiet without idleAfter must not change a single wait")
                        .isNotEmpty()
                        .allMatch { it == POLL_INTERVAL }
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }
            }
        }

        @Test
        fun `DL-06 the idle rule fires exactly at idleAfter`(
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
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= 1 }

                    // Exactly the boundary, not past it: the rule is
                    // `quietFor >= idleAfter`, so the very first turn at the
                    // boundary must already pace idle.
                    val baseline = recorded.size
                    clock.advance(IDLE_AFTER)
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { recorded.size > baseline }

                    assertThat(recorded.toList().drop(baseline))
                        .`as`("DL-06: at exactly idleAfter of quiet the loop must already pace at the idle interval")
                        .isNotEmpty()
                        .allMatch { it == IDLE_INTERVAL }
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }
            }
        }
    }

    @Nested
    inner class FailurePaths {
        @Test
        fun `DL-05 a page aborted mid-scan is dropped and the batch stays open`(
            @TempDir tempDir: Path,
        ) {
            FakeScanner().use { fake ->
                // A slow jpegsize keeps the transfer in flight long enough for
                // the test to pull the device away underneath it.
                fake.scanDelayMillis = SCAN_DELAY_MILLIS
                fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
                fake.start()

                val clock = MutableClock(START)
                val failures = ConcurrentLinkedQueue<String>()
                val delivered = ConcurrentLinkedQueue<ScannedDocument>()
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
                        sleeper = {},
                    )

                val t = thread(start = true) { loop.run() }
                try {
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.receivedCommands.contains("jpegsize") }
                    fake.goOffline()

                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { failures.isNotEmpty() }

                    assertThat(loop.pageCount)
                        .`as`("DL-05: the aborted page must not be counted or added to the batch")
                        .isZero()
                    assertThat(delivered)
                        .`as`("DL-05: a failed page must not produce a document on its own")
                        .isEmpty()
                    assertThat(loop.isRunning)
                        .`as`("DL-05: the loop must survive the failed page")
                        .isTrue()

                    // The device comes back with the sheet still in its tray;
                    // the open batch must accept it as if nothing happened.
                    fake.comeOnline()
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }

                    assertThat(loop.pageCount)
                        .`as`("DL-05: after the failure the next page must be scanned normally")
                        .isEqualTo(1)
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }
            }
        }
    }

    @Nested
    inner class StateChanges {
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
        fun `DL-02 a full offline and online cycle reports each transition exactly once`(
            @TempDir tempDir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.statusWord = "nopaper"
                fake.start()

                val clock = MutableClock(START)
                val reachability = ConcurrentLinkedQueue<Boolean>()
                val loop =
                    loopFor(
                        clock,
                        fake,
                        tempDir,
                        listener =
                            object : ScanLoopListener {
                                override fun onReachabilityChanged(reachable: Boolean) {
                                    reachability.add(reachable)
                                }
                            },
                        sleeper = { Thread.sleep(SLEEP_MILLIS) },
                    )

                // A real, tiny sleep here rather than the recording no-op: the
                // loop must keep turning across three phases without racing
                // ahead of the fake rebinding its port.
                val t = thread(start = true) { loop.run() }
                try {
                    // Phase 1: reachable, observed as state, not poll count.
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { reachability.toList() == listOf(true) }
                    val afterReachable = loop.pollCount

                    // Phase 2: offline, then keep polling in the same state.
                    fake.goOffline()
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { reachability.toList() == listOf(true, false) }
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= afterReachable + EXTRA_POLLS }

                    // Phase 3: back on the same port, then keep polling.
                    fake.comeOnline()
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { reachability.toList() == listOf(true, false, true) }
                    val afterOnline = loop.pollCount
                    await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= afterOnline + EXTRA_POLLS }

                    assertThat(reachability.toList())
                        .`as`(
                            "DL-02: three transitions over at least %d polls must report exactly three changes - " +
                                "repeats of the same state stay silent",
                            loop.pollCount,
                        ).containsExactly(true, false, true)

                    val messages =
                        appender.list
                            .filter { it.level == Level.INFO }
                            .map { it.formattedMessage }
                    assertThat(messages)
                        .`as`("DL-02: the state-change log must carry exactly one entry per transition")
                        .hasSize(3)
                    assertThat(messages[0]).contains("reachable")
                    assertThat(messages[1]).contains("offline")
                    assertThat(messages[2]).contains("reachable")
                } finally {
                    loop.stop()
                    t.join(THREAD_JOIN_MILLIS)
                }
            }
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")

        /** Short enough that several turns happen quickly; the value is never waited out. */
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /** Distinct from the regular and idle paces, so the recorded queue tells all three apart. */
        val OFFLINE_POLL_INTERVAL: Duration = Duration.ofMillis(70)

        /**
         * Several poll intervals long so regular turns pass before the rule
         * kicks in; the idle pace is distinct from both other paces for the
         * same reason as above.
         */
        val IDLE_AFTER: Duration = Duration.ofMillis(50)
        val IDLE_INTERVAL: Duration = Duration.ofMillis(100)

        /** Long enough that only the test's own trigger can close the batch, never the timeout. */
        val BATCH_TIMEOUT: Duration = Duration.ofMinutes(10)

        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"

        /** Holds the scan open long enough for the test to pull the device away mid-transfer. */
        const val SCAN_DELAY_MILLIS = 400L

        const val POLLS_TO_OBSERVE = 3

        /**
         * Polls to observe within a state before concluding that nothing more
         * is reported. One poll would prove nothing: the claim is that
         * repeated polls in the *same* state stay silent.
         */
        const val EXTRA_POLLS = 3

        /** Keeps the loop from spinning free while a phase is being observed. */
        const val SLEEP_MILLIS = 5L

        /**
         * Generous: a poll costs about 0.7 s of prescribed SC-02 pauses no
         * matter how short the interval is, and the bound is never waited out
         * when things work.
         */
        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
    }
}
