package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.service.Batch
import dev.digiwomb.unboundair.service.ScanLoop
import dev.digiwomb.unboundair.service.ScanLoopListener
import dev.digiwomb.unboundair.service.ScannedDocument
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * What a measuring run observed (BE-04).
 *
 * The fields exist to answer specific open questions from
 * `docs/offene-fragen.md`, not because they were easy to collect:
 *
 * - [pages], [gaps] answer **OF-03** (how long does a scan take, how much time
 *   passes between two sheets) -- the numbers the final `batch-timeout` default
 *   is derived from;
 * - [devbusyCount] answers **OF-02** (does a 3-second poll make the device say
 *   `devbusy`, and how often);
 * - [offlineAt], [quietBeforeOffline] answer **OF-01** (does polling keep the
 *   device awake, or does it switch itself off after five minutes anyway);
 * - [suspiciousGaps] answers **OF-04** (does the device report `scanready`
 *   again right after a scan and produce a duplicate page).
 *
 * @property pages how many pages were scanned.
 * @property gaps the spans between the end of one page and the end of the next.
 * @property devbusyCount how many `devbusy` answers arrived.
 * @property failures how many pages failed and were discarded.
 * @property offlineAt when the device first became unreachable, or `null` if it
 *   stayed reachable for the whole run.
 * @property quietBeforeOffline how long nothing happened before the device went
 *   offline. This is the number OF-01 turns on: if it is close to five minutes
 *   of *polling*, polling does not keep the device awake.
 * @property suspiciousGaps gaps shorter than [DOUBLE_SCAN_THRESHOLD], the
 *   candidates for a double scan (OF-04).
 */
data class MeasureReport(
    val pages: Int,
    val gaps: List<Duration>,
    val devbusyCount: Int,
    val failures: Int,
    val offlineAt: Instant?,
    val quietBeforeOffline: Duration?,
    val suspiciousGaps: List<Duration>,
) {
    /** Mean gap between two pages, or `null` with fewer than two pages. */
    val averageGap: Duration?
        get() = if (gaps.isEmpty()) null else Duration.ofMillis(gaps.sumOf { it.toMillis() } / gaps.size)

    val shortestGap: Duration?
        get() = gaps.minOrNull()

    val longestGap: Duration?
        get() = gaps.maxOrNull()

    /**
     * The summary as it is printed at the end of a run.
     *
     * Plain text on stdout rather than log lines: this is the *result* of the
     * command, the thing the operator reads off the screen and copies into
     * `docs/hardware.md`. Every line names the open question it answers, so the
     * numbers can be traced back without the command's documentation at hand.
     */
    fun format(): String {
        val lines = mutableListOf<String>()
        lines += "Measurement summary"
        lines += "  pages scanned:       $pages"
        lines += "  pages failed:        $failures"
        lines += "  devbusy answers:     $devbusyCount   (OF-02)"

        if (gaps.isEmpty()) {
            lines += "  gaps between pages:  none (fewer than two pages)   (OF-03)"
        } else {
            lines += "  gaps between pages:  ${gaps.size}   (OF-03)"
            lines += "    average:           ${format(averageGap)}"
            lines += "    shortest:          ${format(shortestGap)}"
            lines += "    longest:           ${format(longestGap)}"
        }

        lines +=
            if (offlineAt == null) {
                "  went offline:        no   (OF-01)"
            } else {
                "  went offline:        at $offlineAt after ${format(quietBeforeOffline)} of quiet   (OF-01)"
            }

        lines +=
            if (suspiciousGaps.isEmpty()) {
                "  possible double scans: none   (OF-04)"
            } else {
                "  possible double scans: ${suspiciousGaps.size} (gaps under ${DOUBLE_SCAN_THRESHOLD.toMillis()} ms)   (OF-04)"
            }

        return lines.joinToString("\n")
    }

    private fun format(duration: Duration?): String = if (duration == null) "-" else "${duration.toMillis()} ms"

    companion object {
        /**
         * A gap below this is treated as a possible double scan (OF-04).
         *
         * Two seconds is a guess and is meant to be: nobody lays a new sheet
         * that fast, but the real device has never been measured. The threshold
         * only decides what the report *flags* for a human to look at -- it
         * changes no behaviour, which is why guessing is acceptable here and
         * would not be in the service itself.
         */
        val DOUBLE_SCAN_THRESHOLD: Duration = Duration.ofSeconds(2)
    }
}

/**
 * The measuring run (BE-04): scan automatically, hand nothing to an output
 * module, and report what the device did.
 *
 * `measure` exists because most of `docs/offene-fragen.md` cannot be answered
 * by reasoning -- only by watching the real device. It drives the same
 * [ScanLoop] the service uses rather than a copy of it, so the numbers describe
 * the behaviour that will actually ship. The observation happens through
 * [ScanLoopListener], which is why the loop needed that seam.
 *
 * **Nothing is handed to an output module.** The sink counts documents and
 * discards them. That is the explicit demand of BE-04: a measuring run must not
 * put test scans into someone's document archive.
 *
 * @property client the scanner to measure.
 * @property clock source of all timestamps.
 * @property pollInterval the interval to measure with; the point of the run is
 *   often to find out whether a given interval provokes `devbusy` (OF-02).
 */
class MeasureCommand(
    private val client: ScannerClient,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val pollInterval: Duration = Duration.ofSeconds(3),
    private val offlinePollInterval: Duration = Duration.ofSeconds(10),
) {
    /**
     * Runs until [duration] has passed or the device has been offline for
     * [stopAfterOffline].
     *
     * A measuring run ends by itself: the operator starts it, feeds sheets, and
     * walks away. Waiting for the device to switch itself off is the point of
     * OF-01, so going offline does not end the run immediately -- the run keeps
     * going for a moment to confirm the device stays gone.
     *
     * @param workDir where pages are written; deleted by the caller.
     * @param duration the longest the run may take.
     * @param stopAfterOffline how long to keep observing after the device
     *   disappeared before concluding the run.
     */
    fun run(
        workDir: Path,
        duration: Duration,
        stopAfterOffline: Duration = Duration.ofSeconds(30),
    ): MeasureReport {
        Files.createDirectories(workDir)

        val pageEnds = ConcurrentLinkedQueue<Instant>()
        val devbusy = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val documents = AtomicInteger(0)

        // AtomicReference rather than a plain var: the listener runs on the
        // loop's thread while this method reads the values from its own, and a
        // local cannot carry @Volatile. Without this the run could finish and
        // report an offline it had already observed as null.
        val offlineAt = AtomicReference<Instant?>(null)
        val lastEventAt = AtomicReference(clock.instant())

        val listener =
            object : ScanLoopListener {
                override fun onStatus(status: String) {
                    if (status == "devbusy") devbusy.incrementAndGet()
                }

                override fun onPageScanned(
                    pageNumber: Int,
                    bytes: Int,
                ) {
                    val now = clock.instant()
                    pageEnds.add(now)
                    lastEventAt.set(now)
                }

                override fun onPageFailed(reason: String) {
                    failures.incrementAndGet()
                }

                override fun onReachabilityChanged(reachable: Boolean) {
                    // Only the FIRST time: OF-01 asks when the device switched
                    // itself off, and a later reconnect must not overwrite it.
                    if (!reachable) {
                        offlineAt.compareAndSet(null, clock.instant())
                    }
                }

                override fun onBatchClosed(document: ScannedDocument) {
                    // Counted, then dropped. BE-04: a measuring run hands
                    // nothing to an output module.
                    documents.incrementAndGet()
                }
            }

        // A batch timeout longer than the run: the run measures the device, and
        // a document being cut in half partway through would only add noise.
        val batch = Batch(clock, duration.plusDays(1), workDir.resolve("batch"), sink = { })
        val loop =
            ScanLoop(
                client = client,
                batch = batch,
                // No processing: the measurement is about the device's timing,
                // and running jpegtran on every page would add this machine's
                // speed to numbers that are supposed to describe the scanner.
                processor = PageProcessor(emptyList()),
                workDir = workDir,
                clock = clock,
                pollInterval = pollInterval,
                offlinePollInterval = offlinePollInterval,
                listener = listener,
            )

        val startedAt = clock.instant()
        val worker = thread(start = true) { loop.run() }
        try {
            while (true) {
                val now = clock.instant()
                if (Duration.between(startedAt, now) >= duration) break
                val wentOffline = offlineAt.get()
                if (wentOffline != null && Duration.between(wentOffline, now) >= stopAfterOffline) break
                Thread.sleep(TICK_MILLIS)
            }
        } finally {
            loop.stop()
            worker.join(JOIN_MILLIS)
        }

        return report(pageEnds.toList(), devbusy.get(), failures.get(), offlineAt.get(), lastEventAt.get())
    }

    private fun report(
        ends: List<Instant>,
        devbusyCount: Int,
        failures: Int,
        offlineAt: Instant?,
        lastEventAt: Instant,
    ): MeasureReport {
        val gaps = ends.zipWithNext { previous, next -> Duration.between(previous, next) }
        return MeasureReport(
            pages = ends.size,
            gaps = gaps,
            devbusyCount = devbusyCount,
            failures = failures,
            offlineAt = offlineAt,
            quietBeforeOffline = offlineAt?.let { Duration.between(lastEventAt, it) },
            suspiciousGaps = gaps.filter { it < MeasureReport.DOUBLE_SCAN_THRESHOLD },
        )
    }

    private companion object {
        /** How often the run checks whether it is done. */
        const val TICK_MILLIS = 50L
        const val JOIN_MILLIS = 10_000L
    }
}
