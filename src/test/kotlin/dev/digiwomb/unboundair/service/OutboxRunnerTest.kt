package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.output.OutputDocument
import dev.digiwomb.unboundair.output.OutputModule
import dev.digiwomb.unboundair.output.OutputModules
import dev.digiwomb.unboundair.output.outbox.Outbox
import dev.digiwomb.unboundair.output.outbox.OutboxEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [OutboxRunner] (AU-04): the success and failure paths of one pass.
 *
 * The integration test ([OutboxRetryIntegrationTest]) proves the loop around a real
 * outbox delivers across restarts; this file proves the runner mechanics the
 * integration test does not touch: the listener events, the recorded failure reason,
 * the empty pass as a no-op, the wait between passes as a recorded sleeper value,
 * and the shutdown paths (`stop` mid-pass, interrupts during the wait and during a
 * delivery).
 *
 * **No threads, no sleeping, no waiting.** Single passes run synchronously via
 * [OutboxRunner.deliverDue]; the looping [OutboxRunner.run] runs on the test thread
 * with a recording sleeper that stops the loop after a fixed number of waits, so the
 * interval becomes an ordinary assertion over recorded values. Time comes from fixed
 * clocks per outbox.
 *
 * Offline (DC-03): a temporary directory, fixed clocks, fake modules. No device, no
 * network. The stored "PDF" is a few arbitrary bytes: no test here reads the
 * document, so nothing needs a valid PDF.
 */
class OutboxRunnerTest {
    @Nested
    inner class SuccessfulDelivery {
        @Test
        fun `AU-04 a due entry is delivered and its success recorded`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            val entry = outbox.accept(document(dir, "source.pdf", START))
            val module = RecordingModule()
            val events = RecordingListener()
            val runner = runnerOf(outbox, module, events)

            runner.deliverDue()

            assertThat(module.received)
                .`as`("the due entry reaches the module exactly once")
                .hasSize(1)
            assertThat(outbox.due())
                .`as`("a delivered entry is not due again")
                .isEmpty()
            assertThat(Files.exists(entry.directory))
                .`as`("success deletes the entry directory; the module holds the only copy now")
                .isFalse()
            assertThat(events.delivered.map { it.id })
                .`as`("the runner reports the confirmed delivery")
                .containsExactly(entry.id)
            assertThat(events.failed)
                .`as`("nothing failed")
                .isEmpty()
        }

        @Test
        fun `AU-04 every due entry is delivered in a single pass`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            val first = outbox.accept(document(dir, "first.pdf", START))
            val second = outbox.accept(document(dir, "second.pdf", START.plusSeconds(60)))
            val module = RecordingModule()
            val events = RecordingListener()

            runnerOf(outbox, module, events).deliverDue()

            assertThat(module.received)
                .`as`("one pass delivers every due entry, not just the first")
                .hasSize(2)
            assertThat(outbox.due())
                .`as`("both delivered entries are gone")
                .isEmpty()
            assertThat(events.delivered.map { it.id })
                .`as`("the runner reports both deliveries")
                .containsExactlyInAnyOrder(first.id, second.id)
        }
    }

    @Nested
    inner class FailedDelivery {
        @Test
        fun `AU-04 a failing delivery is recorded as failed and the entry stays for the next pass`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START), backoffInitial = BACKOFF)
            val entry = outbox.accept(document(dir, "source.pdf", START))
            val events = RecordingListener()
            val runner =
                runnerOf(outbox, FailingModule(IOException("fake delivery failure")), events)

            runner.deliverDue()

            assertThat(Files.exists(entry.directory))
                .`as`("a throwing module must not delete the document; the outbox is the only copy until success")
                .isTrue()
            assertThat(outbox.due())
                .`as`("at the same instant the backoff has not elapsed, so nothing is due again yet")
                .isEmpty()
            assertThat(events.delivered)
                .`as`("nothing was delivered")
                .isEmpty()
            assertThat(events.failed.map { it.id })
                .`as`("the runner reports the failed delivery")
                .containsExactly(entry.id)
            assertThat(events.failedReasons)
                .`as`("the runner reports the exception message as the reason")
                .containsExactly("fake delivery failure")

            val later = Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1)), backoffInitial = BACKOFF)
            val pending = later.due()
            assertThat(pending)
                .`as`("past the backoff the failed entry is due again for the next pass")
                .hasSize(1)
            assertThat(pending.single().metadata.attempts)
                .`as`("the failed pass is counted")
                .isEqualTo(1)
        }

        @Test
        fun `AU-04 a failure without a message reports the exception type as the reason`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            outbox.accept(document(dir, "source.pdf", START))
            val events = RecordingListener()
            val runner = runnerOf(outbox, FailingModule(RuntimeException()), events)

            runner.deliverDue()

            assertThat(events.failed)
                .`as`("the failure is reported even without a message to quote")
                .hasSize(1)
            assertThat(events.failedReasons)
                .`as`("a reason is always reported; without a message it is the exception type")
                .containsExactly("RuntimeException")
            assertThat(outbox.due())
                .`as`("the entry backs off all the same")
                .isEmpty()
        }
    }

    @Nested
    inner class EmptyPass {
        @Test
        fun `AU-04 an empty pass calls no module and records nothing`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            val module = RecordingModule()
            val events = RecordingListener()

            runnerOf(outbox, module, events).deliverDue()

            assertThat(module.received)
                .`as`("with nothing due no module is called")
                .isEmpty()
            assertThat(events.delivered)
                .`as`("with nothing due no delivery is reported")
                .isEmpty()
            assertThat(events.failed)
                .`as`("with nothing due no failure is reported")
                .isEmpty()
        }
    }

    @Nested
    inner class PollInterval {
        @Test
        fun `AU-04 the wait between passes is the configured interval, observed on the recorded sleeper values`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            val slept = mutableListOf<Duration>()
            lateinit var runner: OutboxRunner
            runner =
                OutboxRunner(
                    outbox,
                    OutputModules(listOf(RecordingModule()), listOf("fake")),
                    pollInterval = INTERVAL,
                    sleeper = {
                        slept.add(it)
                        assertThat(runner.isRunning)
                            .`as`("the loop flag is set while the loop runs")
                            .isTrue()
                        if (slept.size >= 2) runner.stop()
                    },
                )

            runner.run()

            assertThat(slept)
                .`as`("the loop waits the configured interval between passes, never a hardcoded one")
                .containsExactly(INTERVAL, INTERVAL)
            assertThat(runner.isRunning)
                .`as`("the stop during the wait ended the loop")
                .isFalse()
        }
    }

    @Nested
    inner class Shutdown {
        @Test
        fun `AU-04 a stop during a pass ends the pass at the next entry`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            outbox.accept(document(dir, "first.pdf", START))
            outbox.accept(document(dir, "second.pdf", START.plusSeconds(60)))
            val module = RecordingModule()
            lateinit var runner: OutboxRunner
            val stopAfterFirst =
                object : OutboxRunnerListener {
                    override fun onDelivered(entry: OutboxEntry) {
                        runner.stop()
                    }
                }
            runner = runnerOf(outbox, module, stopAfterFirst)

            runner.deliverDue()

            assertThat(module.received)
                .`as`("the stop ends the pass at the next entry; the second entry waits for the next pass")
                .hasSize(1)
            assertThat(outbox.due())
                .`as`("the undelivered entry is still due")
                .hasSize(1)
        }

        @Test
        fun `AU-04 an interrupt during the wait ends the loop without recording anything`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            val module = RecordingModule()
            val events = RecordingListener()
            val runner =
                OutboxRunner(
                    outbox,
                    OutputModules(listOf(module), listOf("fake")),
                    pollInterval = INTERVAL,
                    listener = events,
                    sleeper = { throw InterruptedException("shut down") },
                )

            runner.run()

            try {
                assertThat(Thread.currentThread().isInterrupted)
                    .`as`("the loop restores the interrupt flag instead of swallowing it")
                    .isTrue()
            } finally {
                Thread.interrupted()
            }
            assertThat(module.received)
                .`as`("an interrupt is a shutdown, not a delivery")
                .isEmpty()
            assertThat(events.delivered).isEmpty()
            assertThat(events.failed).isEmpty()
        }

        @Test
        fun `AU-04 an interrupted delivery records nothing and stops the loop`(
            @TempDir dir: Path,
        ) {
            val outbox = outboxAt(dir, START)
            outbox.accept(document(dir, "source.pdf", START))
            val events = RecordingListener()
            val runner = runnerOf(outbox, FailingModule(InterruptedException("shut down")), events)

            runner.deliverDue()

            try {
                assertThat(Thread.currentThread().isInterrupted)
                    .`as`("the interrupted delivery restores the interrupt flag instead of swallowing it")
                    .isTrue()
            } finally {
                Thread.interrupted()
            }
            assertThat(outbox.due())
                .`as`("an interrupt is a shutdown, not a failed attempt: the entry stays due without a backoff")
                .hasSize(1)
            assertThat(
                outbox
                    .due()
                    .single()
                    .metadata.attempts,
            ).`as`("the interrupted delivery counts no attempt")
                .isEqualTo(0)
            assertThat(events.delivered)
                .`as`("an interrupted delivery reports no success")
                .isEmpty()
            assertThat(events.failed)
                .`as`("an interrupted delivery reports no failure either")
                .isEmpty()
        }
    }

    private fun outboxAt(
        dir: Path,
        now: Instant,
    ): Outbox = Outbox(dir.resolve("outbox"), clockAt(now), backoffInitial = BACKOFF)

    private fun runnerOf(
        outbox: Outbox,
        module: OutputModule,
        listener: OutboxRunnerListener,
    ): OutboxRunner =
        OutboxRunner(
            outbox,
            OutputModules(listOf(module), listOf("fake")),
            pollInterval = Duration.ofMillis(1),
            listener = listener,
        )

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    /** A source file with arbitrary bytes; no test here reads the document, so it needs no valid PDF. */
    private fun document(
        dir: Path,
        name: String,
        startedAt: Instant,
    ): OutputDocument {
        val file = dir.resolve(name)
        Files.write(file, "not a pdf, and no test reads it".toByteArray())
        return OutputDocument(file, 1, startedAt, startedAt.plusSeconds(45))
    }

    /** A module that records every delivered document. */
    private class RecordingModule : OutputModule {
        override val name: String = "fake"
        val received = mutableListOf<OutputDocument>()

        override fun send(document: OutputDocument) {
            received.add(document)
        }
    }

    /** A module that fails on every delivery with the given exception. */
    private class FailingModule(
        private val failure: Exception,
    ) : OutputModule {
        override val name: String = "fake"

        override fun send(document: OutputDocument): Unit = throw failure
    }

    /** Records runner callbacks so tests assert on events instead of scraping the log. */
    private class RecordingListener : OutboxRunnerListener {
        val delivered = mutableListOf<OutboxEntry>()
        val failed = mutableListOf<OutboxEntry>()
        val failedReasons = mutableListOf<String>()

        override fun onDelivered(entry: OutboxEntry) {
            delivered.add(entry)
        }

        override fun onDeliveryFailed(
            entry: OutboxEntry,
            reason: String,
        ) {
            failed.add(entry)
            failedReasons.add(reason)
        }
    }

    private companion object {
        val START: Instant = Instant.parse("2026-10-03T09:00:00Z")

        /** Short backoff for tests: the redelivery is reached by advancing the fixed clock, never by waiting. */
        val BACKOFF: Duration = Duration.ofMillis(10)

        /** The loop interval asserted via the recorded sleeper values, never slept for real. */
        val INTERVAL: Duration = Duration.ofSeconds(7)
    }
}
