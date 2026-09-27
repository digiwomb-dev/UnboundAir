package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.processing.pageImage
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.scanner.ScannerException
import dev.digiwomb.unboundair.scanner.ScannerOfflineException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the loop reports what it is doing, so a caller can observe it without
 * scraping the log.
 *
 * `measure` (BE-04) needs the same events the log carries, but as values it can
 * count and time. Handing them out here keeps that command from re-implementing
 * the loop, and keeps the loop from growing a measuring mode of its own.
 */
interface ScanLoopListener {
    /** A status answer arrived. Called for every poll, changed or not. */
    fun onStatus(status: String) {}

    /** A page was scanned and added to the batch. */
    fun onPageScanned(
        pageNumber: Int,
        bytes: Int,
    ) {}

    /** A page failed and was discarded; the batch stays open (DL-05). */
    fun onPageFailed(reason: String) {}

    /** The scanner became unreachable or reachable again. */
    fun onReachabilityChanged(reachable: Boolean) {}

    /** A batch was closed and handed to the sink. */
    fun onBatchClosed(document: ScannedDocument) {}
}

/**
 * The service loop: polls the scanner, scans what is offered, and lets the
 * batch decide when a document is done (DL-01 to DL-06).
 *
 * One turn of the loop:
 *
 * 1. ask for the status (one connection per poll, SC-02);
 * 2. on `scanready`, scan the page, run it through the processing chain, and
 *    append it to the batch (DL-03);
 * 3. ask the batch whether its window has expired (DL-04);
 * 4. wait for the interval that fits the current state and start again.
 *
 * Which interval that is depends on the state, and this is the whole of the
 * pacing logic:
 *
 * - reachable and working: [pollInterval] (DL-01, default 3 s);
 * - unreachable: [offlinePollInterval] (DL-02, default 10 s), because hammering
 *   a device that is switched off is pointless;
 * - idle for longer than [idleAfter]: [idleInterval] (DL-06), off by default
 *   until the measurements of OF-01 say what it should be.
 *
 * ## Errors are not the end
 *
 * DL-05 requires a failed page to be discarded with a log entry while the batch
 * stays open, and DL-02 treats being offline as a state rather than a failure.
 * The loop therefore catches [ScannerException] per turn: a scan that dies
 * mid-page costs that page, nothing else. Only [stop] ends the loop.
 *
 * ## Time
 *
 * Timestamps come from [clock], waiting goes through [sleeper]. Both are
 * injected so a test can shorten the intervals to milliseconds and still
 * exercise the real concurrent behaviour, rather than simulating it.
 */
class ScanLoop(
    private val client: ScannerClient,
    private val batch: Batch,
    private val processor: PageProcessor,
    private val workDir: Path,
    private val clock: Clock,
    private val pollInterval: Duration,
    private val offlinePollInterval: Duration,
    private val idleAfter: Duration? = null,
    private val idleInterval: Duration = offlinePollInterval,
    private val listener: ScanLoopListener = object : ScanLoopListener {},
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    private val running = AtomicBoolean(false)
    private val pollCounter = AtomicInteger(0)
    private val pageCounter = AtomicInteger(0)

    /** Reports state changes exactly once each (DL-02). */
    private val reachability = StateChangeLog<Boolean>()

    /** Wall-clock instant of the last page, for the idle rule (DL-06). */
    @Volatile
    private var lastActivity = clock.instant()

    /** How many status polls have been made. */
    val pollCount: Int
        get() = pollCounter.get()

    /** How many pages have been scanned successfully. */
    val pageCount: Int
        get() = pageCounter.get()

    /** Whether the loop is currently running. */
    val isRunning: Boolean
        get() = running.get()

    /**
     * Runs until [stop] is called.
     *
     * Blocking: the caller owns the thread. `run` (BE-05) will hand it the main
     * thread; tests start it on one of their own.
     */
    fun run() {
        running.set(true)
        lastActivity = clock.instant()
        while (running.get()) {
            val reachable = pollOnce()
            if (!running.get()) break
            sleeper(intervalFor(reachable))
        }
    }

    /**
     * Stops the loop and closes the open batch (the DL-07 groundwork).
     *
     * The batch is closed here rather than left to the caller so no scanned page
     * is ever dropped on shutdown, which is what DL-07 will require in milestone
     * 5.
     */
    fun stop() {
        running.set(false)
        batch.close()?.let { listener.onBatchClosed(it) }
    }

    /**
     * One turn: poll, possibly scan, and let the batch decide.
     *
     * @return whether the scanner was reachable this turn.
     */
    private fun pollOnce(): Boolean {
        pollCounter.incrementAndGet()
        val status =
            try {
                client.queryStatus().also { noteReachable(true) }
            } catch (e: ScannerOfflineException) {
                noteReachable(false)
                // Offline is the second DL-04 trigger: the device switching
                // itself off means the document is finished.
                batch.close()?.let { listener.onBatchClosed(it) }
                return false
            }

        listener.onStatus(status)

        if (status == "scanready") {
            scanOnePage()
        }

        batch.closeIfDue()?.let { listener.onBatchClosed(it) }
        return true
    }

    /**
     * Scans one page and appends it to the batch (DL-03).
     *
     * A failure here costs the page and nothing else (DL-05): the exception is
     * logged, the batch stays open, and the next turn carries on. Swallowing it
     * is the point -- a single bad sheet must not end a running service.
     */
    private fun scanOnePage() {
        val pageDir = Files.createTempDirectory(workDir, "page-")
        try {
            val scan = client.scan(DEFAULT_DPI)
            val raw = pageDir.resolve("raw.jpg")
            Files.write(raw, scan.bytes)

            val processed = processor.process(pageImage(raw), pageDir) { log.warn(it) }
            batch.addPage(Files.readAllBytes(processed.file), scan.dpi)

            lastActivity = clock.instant()
            val number = pageCounter.incrementAndGet()
            listener.onPageScanned(number, scan.bytes.size)
        } catch (e: ScannerException) {
            // DL-05: discard the page, keep the batch, carry on.
            log.warn("page discarded: {}", e.message)
            listener.onPageFailed(e.message ?: e::class.simpleName.orEmpty())
        } catch (e: java.io.IOException) {
            log.warn("page discarded: {}", e.message)
            listener.onPageFailed(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            runCatching { deleteRecursively(pageDir) }
        }
    }

    /**
     * Records reachability and logs only when it changes (DL-02).
     *
     * The suppression itself lives in [StateChangeLog], where it is unit-tested
     * without a scanner.
     */
    private fun noteReachable(reachable: Boolean) {
        if (!reachability.observe(reachable)) return
        if (reachable) {
            log.info("scanner is reachable again")
        } else {
            log.info("scanner is offline, slowing down to {}", offlinePollInterval)
        }
        listener.onReachabilityChanged(reachable)
    }

    /**
     * The wait before the next poll.
     *
     * Offline wins over idle: an unreachable device is the stronger signal, and
     * both intervals exist to avoid pointless traffic.
     */
    private fun intervalFor(reachable: Boolean): Duration {
        if (!reachable) return offlinePollInterval
        val idle = idleAfter ?: return pollInterval
        val quietFor = Duration.between(lastActivity, clock.instant())
        return if (quietFor >= idle) idleInterval else pollInterval
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.delete(path) } }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ScanLoop::class.java)

        /**
         * The service scans at 300 dpi. 600 is a deliberate choice for a single
         * `scan` command, not something a running service should decide on its
         * own: it quadruples both the transfer and the memory of a page (OF-06).
         */
        const val DEFAULT_DPI = 300
    }
}
