package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.service.OutboxRunner
import dev.digiwomb.unboundair.service.ScanLoop
import java.util.concurrent.atomic.AtomicBoolean
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
 * [run] (the shutdown hook does exactly that): it guards itself with an
 * atomic rather than relying on the guards inside [ScanLoop.stop] and
 * [OutboxRunner.stop], and [run] joins the threads [stop] ends.
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
 * @property warn sink for the start, stop and shutdown lines; the per-page logging
 *   already lives in [ScanLoop] (KL-02) and is not duplicated here.
 */
class RunCommand(
    private val loop: ScanLoop,
    private val runner: OutboxRunner,
    private val warn: (String) -> Unit = {},
) {
    /** Guards [stop] so stopping twice is harmless, whoever calls it. */
    private val stopping = AtomicBoolean(false)

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
     *
     * A JVM shutdown hook is registered before the threads start and removed
     * when this method returns, so the hook never outlives the command and
     * commands sharing one JVM (tests) cannot interfere through each other's
     * hooks. Removing a hook while the JVM is already shutting down throws
     * [IllegalStateException]; that is expected and swallowed.
     */
    fun run() {
        warn("service started")
        val hook = Thread({ onShutdownSignal() }, SHUTDOWN_HOOK_NAME)
        Runtime.getRuntime().addShutdownHook(hook)
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
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
        warn("service stopped")
    }

    /**
     * Ends both loops and returns once they have ended.
     *
     * Safe to call from any thread, and safe to call twice: a signal can
     * arrive while [stop] is already running, and the runtime's own shutdown
     * may trigger the same path, so the first call wins and later ones return
     * at once. The loop stops first so the open batch closes into the outbox,
     * then the runner stops after it had the chance to deliver that document.
     *
     * The joins have deliberately **no** timeout: a page in flight takes up
     * to 60 s (DO-03), and the JVM ends as soon as the shutdown hook returns,
     * so returning early would truncate `document.pdf`. If the runtime's stop
     * timeout is too low for that, it is an operational setting, not
     * something to paper over here. [ScanLoop.stop] closes the batch
     * synchronously at a safe point, so the in-flight scan is left alone and
     * still persisted before this returns.
     */
    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        loop.stop()
        runner.stop()
        try {
            loopThread?.join()
            runnerThread?.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * The shutdown hook body (DL-07): closes the open batch into the outbox
     * and returns only once it is persisted.
     *
     * This runs on the JVM's hook thread, not on the thread blocked in [run].
     * It delegates to [stop], so it waits for the same threads [run] is
     * blocked on: the batch is closed synchronously by [ScanLoop.stop] and
     * the runner has finished its pass before this returns.
     */
    private fun onShutdownSignal() {
        warn("shutdown signal received, closing open batch")
        stop()
        warn("shutdown complete, open batch handed to the outbox")
    }

    private companion object {
        /** Name of the JVM shutdown hook registered by [run]. */
        const val SHUTDOWN_HOOK_NAME = "unboundair-shutdown-hook"
    }
}
