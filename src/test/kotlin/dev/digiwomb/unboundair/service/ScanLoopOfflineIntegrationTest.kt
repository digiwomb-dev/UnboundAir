package dev.digiwomb.unboundair.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
 * Integration tests for the loop's behaviour when the scanner disappears
 * (DL-02, DL-04).
 *
 * Three claims are pinned, and the middle one is the reason this file exists:
 *
 * - an unreachable scanner slows the polling to `offlinePollInterval` (DL-02);
 * - **exactly one log entry per state change** (DL-02). A loop that logged
 *   every failed poll would write a line every few seconds for as long as the
 *   scanner stays off -- thousands a day, all identical, burying everything
 *   worth reading. This is the acceptance criterion of DL-02 in the plan, and
 *   it is asserted by counting real Logback events rather than by inspecting
 *   the suppressor;
 * - going offline closes an open batch (DL-04). The five-minute auto-off of the
 *   device is a regular end of a document, not a failure.
 *
 * Reachability is toggled with [FakeScanner.goOffline] and
 * [FakeScanner.comeOnline], which keep the **same port**, so the client stays
 * valid across the cycle and the test covers a real off-and-on rather than two
 * unrelated devices.
 *
 * Offline (DC-03): a loopback TCP server and a captured logger, nothing else.
 */
class ScanLoopOfflineIntegrationTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    /**
     * Captures what [ScanLoop] logs.
     *
     * A [ListAppender] on the real logger is used rather than redirecting
     * stdout: it yields the events as objects, so the test can count entries
     * and filter by level instead of pattern-matching formatted text that a
     * change of log pattern would break.
     */
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

    @Test
    fun `DL-02 an unreachable scanner is polled at the offline interval`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val clock = MutableClock(START)
            val recorded = ConcurrentLinkedQueue<Duration>()
            val loop = loopFor(clock, fake, tempDir) { recorded.add(it) }

            val t = thread(start = true) { loop.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= 1 }

                val baseline = recorded.size
                fake.goOffline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { recorded.size > baseline + 1 }

                assertThat(recorded.toList().drop(baseline + 1))
                    .`as`("DL-02: while the scanner cannot be reached, the loop must back off to the offline interval")
                    .isNotEmpty()
                    .allMatch { it == OFFLINE_POLL_INTERVAL }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    /**
     * The acceptance criterion of DL-02: one entry per state change, not one
     * per poll.
     */
    @Test
    fun `DL-02 a full offline and online cycle logs exactly one entry per change`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()

            val clock = MutableClock(START)
            // A real, tiny sleep here rather than the recording no-op the other
            // tests use: this test wants the loop to keep polling across three
            // phases, and a loop that never sleeps burns a core and races ahead
            // of the fake scanner rebinding its port.
            val loop = loopFor(clock, fake, tempDir, sleeper = { Thread.sleep(SLEEP_MILLIS) })

            val t = thread(start = true) { loop.run() }
            try {
                // Each phase waits for the *state* to be observed, not for a
                // number of polls. Counting polls would be a race: the loop can
                // turn several times while the fake is still rebinding its port,
                // and the wait would finish before the change ever happened.
                fun infoMessages() = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }

                // Phase 1: reachable.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { infoMessages().size == 1 }
                val afterReachable = loop.pollCount

                // Phase 2: offline, observed over many polls.
                fake.goOffline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { infoMessages().size == 2 }
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= afterReachable + EXTRA_POLLS }

                // Phase 3: back on the same port, again over many polls.
                fake.comeOnline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { infoMessages().size == 3 }
                val afterOnline = loop.pollCount
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pollCount >= afterOnline + EXTRA_POLLS }

                val messages = infoMessages()

                assertThat(messages)
                    .`as`(
                        "DL-02 demands exactly one entry per state change. Three phases were observed over " +
                            "at least %d polls, so three entries are correct and one per poll would be wrong.",
                        loop.pollCount,
                    ).hasSize(3)
                assertThat(messages[0]).contains("reachable")
                assertThat(messages[1]).contains("offline")
                assertThat(messages[2]).contains("reachable")
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }
        }
    }

    @Test
    fun `DL-04 the scanner going offline closes the open batch`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(
                listOf(
                    dev.digiwomb.unboundair.TestImages
                        .bytes(ENVELOPE),
                ),
            )
            fake.start()

            val clock = MutableClock(START)
            val delivered = ConcurrentLinkedQueue<ScannedDocument>()
            val loop = loopFor(clock, fake, tempDir, sink = { delivered.add(it) })

            val t = thread(start = true) { loop.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 1 }
                assertThat(delivered)
                    .`as`("precondition: the batch window has not expired, so nothing is delivered yet")
                    .isEmpty()

                // The device switches itself off. DL-04 calls this a regular
                // trigger: the document is finished, not failed.
                fake.goOffline()

                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { delivered.isNotEmpty() }

                assertThat(delivered.single().pageCount)
                    .`as`("DL-04: the page scanned before the scanner disappeared must still reach the sink")
                    .isEqualTo(1)
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
            sleeper = sleeper,
        )

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /** Distinct from the regular pace, so the recorded queue tells them apart. */
        val OFFLINE_POLL_INTERVAL: Duration = Duration.ofMillis(70)

        /** Long enough that only the offline trigger can close the batch, never the timeout. */
        val BATCH_TIMEOUT: Duration = Duration.ofMinutes(10)

        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L

        /**
         * Polls to observe within a phase before concluding that nothing more
         * is logged. One poll would prove nothing: the claim is that repeated
         * polls in the *same* state stay silent.
         */
        const val EXTRA_POLLS = 5

        /** Keeps the loop from spinning free while a phase is being observed. */
        const val SLEEP_MILLIS = 5L
    }
}
