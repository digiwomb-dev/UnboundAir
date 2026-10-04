package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.service.OutboxRunner
import dev.digiwomb.unboundair.service.ScanLoop
import kotlin.concurrent.thread

/**
 * The `run` command (BE-05): owns the service threads.
 *
 * Deliberately thin. Polling, batching, delivery and retry already exist in
 * [ScanLoop] and [OutboxRunner]; this command adds the one thing the pieces
 * cannot have themselves: **the threads, and the ability to end them.**
 *
 * [run] starts both loops on their own threads and blocks until [stop] is
 * called. [stop] is safe to call from a different thread than the one in
 * [run] (the signal handler of work order 6 does exactly that): both
 * [ScanLoop.stop] and [OutboxRunner.stop] are built for that with atomics,
 * and [run] joins the threads [stop] ends.
 *
 * Shutdown order matters and must stay as written: the loop stops first so
 * the open batch closes and reaches the outbox, **then** the runner finishes
 * delivering it. Reversing the order can leave the just-closed document
 * undelivered until the next start.
 *
 * @property loop the assembled scan loop; assembled by the composition root,
 *   not here.
 * @property runner the assembled outbox runner; assembled by the composition
 *   root, not here.
 * @property warn sink for the start and stop lines; the per-page logging
 *   already lives in [ScanLoop] (KL-02) and is not duplicated here.
 */
class RunCommand(
    private val loop: ScanLoop,
    private val runner: OutboxRunner,
    private val warn: (String) -> Unit = {},
) {
    @Volatile
    private var loopThread: Thread? = null

    @Volatile
    private var runnerThread: Thread? = null

    /**
     * Starts both loops and blocks until [stop] is called.
     *
     * The runner thread starts first so no closed document waits for a
     * runner that is not yet listening; then the loop thread starts and
     * this thread joins both.
     */
    fun run() {
        warn("service started")
        val runnerWorker = thread(start = true, name = "outbox-runner") { runner.run() }
        val loopWorker = thread(start = true, name = "scan-loop") { loop.run() }
        runnerThread = runnerWorker
        loopThread = loopWorker
        try {
            loopWorker.join()
            runnerWorker.join()
        } finally {
            loopThread = null
            runnerThread = null
        }
        warn("service stopped")
    }

    /**
     * Ends both loops and returns once they have ended.
     *
     * Safe to call from any thread. The loop stops first so the open batch
     * closes into the outbox, then the runner stops after it had the chance
     * to deliver that document. Interrupts the joining [run] thread's waits
     * only indirectly: both loops notice the flag at their next check, and
     * the join here waits for the same threads [run] is blocked on.
     */
    fun stop() {
        loop.stop()
        runner.stop()
        try {
            loopThread?.join(JOIN_MILLIS)
            runnerThread?.join(JOIN_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /** How long [stop] waits per thread before returning. */
        const val JOIN_MILLIS = 10_000L
    }
}
