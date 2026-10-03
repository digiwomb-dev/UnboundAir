package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.output.OutputModules
import dev.digiwomb.unboundair.output.outbox.Outbox
import dev.digiwomb.unboundair.output.outbox.OutboxEntry
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How the runner reports delivery outcomes, so a test (issue #71) can observe
 * them without scraping the log.
 */
interface OutboxRunnerListener {
    /** Delivery of [entry] was confirmed by the modules; the outbox deleted it. */
    fun onDelivered(entry: OutboxEntry) {}

    /** Delivery of [entry] failed; [reason] is the exception message or type name. */
    fun onDeliveryFailed(
        entry: OutboxEntry,
        reason: String,
    ) {}
}

/**
 * The active half of AU-04: the thing that turns the outbox's clock.
 *
 * The outbox is passive by design (see [Outbox]) — it persists, names what is
 * due, and records the outcome it is told, but never calls a module. The plan's
 * "Wer die Wiederholung antreibt" decision therefore lives here, in the service
 * layer: this runner is what calls [OutputModules.send] on its behalf.
 *
 * One turn of the loop:
 *
 * 1. ask the outbox for what is [Outbox.due];
 * 2. for each due entry, hand [OutboxEntry.document] to [OutputModules.send];
 * 3. on a clean return record success (AU-04 deletes the entry), on an
 *    exception record failure (AU-04 backs the entry off and retries);
 * 4. wait [pollInterval] and start again.
 *
 * ## Errors are not the end
 *
 * A module talks to a network service and can throw almost anything, so the catch
 * around each entry is broad where [ScanLoop] catches one known device's narrow
 * domain: one thing worse than a failed upload is a service that dies on it.
 * Every exception becomes a [Outbox.recordFailure] for that entry, and the loop
 * moves on. An interrupt means "shut down", so it ends the loop instead of
 * recording a failure — see [deliver].
 *
 * [Outbox.recordSuccess] runs only on a clean return from [OutputModules.send],
 * never in a `finally`, never optimistically: `send` throws on failure precisely
 * so the outbox can tell the two apart, and a success record deletes the entry's
 * directory — recording one after a failed delivery would lose it for good.
 *
 * [run] blocks and owns no thread of its own; the caller starts it
 * (`UnboundAirApplication`, issue #123). Waiting goes through [sleeper], which a
 * test shortens to milliseconds.
 *
 * ## No clock
 *
 * Unlike [ScanLoop], this loop takes no `Clock`. It makes no decision that
 * depends on the time: *what* is due is the outbox's judgement, made against the
 * clock injected there, and *how often* to ask is a plain interval. A clock here
 * would be a second, unused source of time, and the next reader would reasonably
 * wonder which of the two decides a retry.
 */
class OutboxRunner(
    private val outbox: Outbox,
    private val modules: OutputModules,
    private val pollInterval: Duration,
    private val listener: OutboxRunnerListener = object : OutboxRunnerListener {},
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    private val running = AtomicBoolean(false)

    /** Whether the loop is currently running. */
    val isRunning: Boolean
        get() = running.get()

    /**
     * Runs until [stop] is called.
     *
     * Blocking: the caller owns the thread, exactly as with [ScanLoop.run]. The
     * flag is checked after the work and before the wait, so a [stop] during a
     * turn is seen before the sleep starts; an interrupt during the wait ends
     * the loop the same way, with the flag restored.
     */
    fun run() {
        running.set(true)
        while (running.get()) {
            turn()
            if (!running.get()) break
            try {
                sleeper(pollInterval)
            } catch (_: InterruptedException) {
                // An interrupt means "shut down", not "a delivery failed":
                // restore the flag and end the loop.
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Stops the loop at the next flag check. */
    fun stop() {
        running.set(false)
    }

    /**
     * One turn: deliver every entry the outbox says is due (AU-04).
     *
     * The flag is checked per entry so a [stop] — or the one an interrupted
     * delivery sets — ends the pass at the next entry.
     */
    private fun turn() {
        for (entry in outbox.due()) {
            deliver(entry)
            if (!running.get()) break
        }
    }

    /**
     * Delivers one entry and records the outcome in the outbox (AU-04).
     *
     * The catch is deliberately broader than [ScanLoop.scanOnePage]'s: that one
     * talks to a single known device, where a module can throw almost anything.
     * And [Outbox.recordSuccess] runs only on a clean return from
     * [OutputModules.send] — never in a `finally` — because it deletes the
     * entry's directory: recording one after a failed delivery would lose a
     * document for good.
     */
    private fun deliver(entry: OutboxEntry) {
        try {
            modules.send(entry.document)
        } catch (_: InterruptedException) {
            // An interrupt means "shut down", not "the upload failed": restore
            // the flag and end the loop; recording nothing is the point.
            Thread.currentThread().interrupt()
            running.set(false)
            return
        } catch (e: Exception) {
            // AU-04: a failed upload costs this attempt, nothing else — the
            // outbox backs the entry off; the loop moves to the next entry.
            log.warn(
                "delivery of {} failed (attempt {}): {}",
                entry.id,
                entry.metadata.attempts + 1,
                e.message,
            )
            outbox.recordFailure(entry)
            listener.onDeliveryFailed(entry, e.message ?: e::class.simpleName.orEmpty())
            return
        }
        outbox.recordSuccess(entry)
        log.info("document {} delivered to the output modules", entry.id)
        listener.onDelivered(entry)
    }

    private companion object {
        val log = LoggerFactory.getLogger(OutboxRunner::class.java)
    }
}
