package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.UnboundAirApplication
import dev.digiwomb.unboundair.scanner.FakeScanner
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Integration test for `run` (BE-05) through configuration (SC-06): the command starts unattended,
 * the FakeScanner offers one sheet, and the finished document reaches the outbox as `document.pdf`
 * with its `metadata.json` (DL-03, AU-04).
 *
 * The address reaches the client **through the property only**. No `--host`/`--port` flag is
 * passed anywhere; if someone later breaks the `cli.host ?: properties.scanner.host` mapping back
 * to the device constants, this test is the tripwire — and it cannot pass by accident, because
 * the fake listens on a random free port per run that is reachable only via the property.
 *
 * The properties travel the way a container passes them — as Spring properties (the `builder
 * .properties(...)` call binds exactly what `UNBOUNDAIR_SCANNER_HOST` et al. would bind), not as
 * command line tokens: `parseCliArgs` rejects every `--`-prefixed token as an unknown option, so
 * a literal `--unboundair.scanner.host=…` argument could never reach the dispatch. Only the
 * command itself (`run`) goes through `argv`, mirroring `DispatchCommandTest`.
 *
 * Timing is structural, not tuned (docs/teststrategie.md: wait for the state, not for a count).
 * The loop scans on its first turn and the batch closes on the second (`batch-timeout` shorter
 * than `poll-interval`), while the outbox runner — on the same interval, offset by half a
 * period — deletes the delivered entry on its *next* pass. The entry is therefore observable for
 * most of one `poll-interval`, seconds rather than milliseconds, and Awaitility samples it at
 * its default pace. `poll-interval` is deliberately *longer* than production here (10 s): hurrying
 * it would shrink the observation window instead of buying speed.
 *
 * Stopping the command is part of the test: `RunCommand.stop` is reachable in production only
 * through the shutdown hook (DL-07, work order 6 — deliberately not tested here), so the test
 * ends the worker threads the only other way that is deterministic: it interrupts them by name
 * until the boot thread returns, then asserts it did. An interrupt always lands — the client's
 * pauses rethrow it instead of swallowing it, and both sleepers throw — so a hanging thread
 * fails here on a bounded wait instead of stalling the whole suite.
 *
 * Offline (DC-03): loopback FakeScanner, committed fixtures, the `jpegtran` of the dev
 * container. No paperless module is configured, so nothing uploads — the delivery path is work
 * order 7's job; here the document only has to reach the outbox.
 */
class RunCommandIntegrationTest {
    @Test
    fun `BE-05 run scans one page through the configured address into the outbox (SC-06, DL-03, AU-04)`(
        @TempDir dir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
            fake.start()
            val outboxRoot = dir.resolve("outbox")
            Files.createDirectories(outboxRoot)

            val contextRef = AtomicReference<ConfigurableApplicationContext>()
            val bootFailure = AtomicReference<Throwable>()
            val unexpectedDeath = AtomicReference<Throwable>()
            val boot =
                thread(start = true, isDaemon = true, name = BOOT_THREAD_NAME) {
                    try {
                        contextRef.set(
                            SpringApplicationBuilder(UnboundAirApplication::class.java)
                                .web(WebApplicationType.NONE)
                                .properties(
                                    "unboundair.scanner.host=127.0.0.1",
                                    "unboundair.scanner.port=${fake.port}",
                                    "unboundair.poll-interval=10s",
                                    "unboundair.offline-poll-interval=1s",
                                    "unboundair.batch-timeout=2s",
                                    "unboundair.outbox.path=$outboxRoot",
                                ).run("run"),
                        )
                    } catch (e: Throwable) {
                        bootFailure.set(e)
                    }
                }
            try {
                val delivered = awaitDeliveredDocument(outboxRoot, boot, contextRef, bootFailure)

                assertThat(delivered.pdf)
                    .`as`("DL-03: the scanned page must reach the outbox as document.pdf (AU-04)")
                    .isNotEmpty()
                    .startsWith(*PDF_MAGIC)
                val metadata = jsonMapper.readTree(delivered.metadataJson)
                assertThat(metadata.path("pageCount").asInt())
                    .`as`("AU-04: the one scanned page must be recorded as a one-page document")
                    .isEqualTo(1)
                assertThat(metadata.path("attempts").asInt())
                    .`as`("AU-04: with no module configured the delivery succeeds on the first attempt")
                    .isEqualTo(0)

                terminate(boot, unexpectedDeath)
                boot.join(BOOT_JOIN_MILLIS)
                assertThat(boot.isAlive)
                    .`as`("BE-05: run must terminate once stopped, so a hanging thread fails here")
                    .isFalse()
                assertThat(unexpectedDeath.get())
                    .`as`("no worker may die from anything but the stop signal")
                    .isNull()

                val context =
                    checkNotNull(contextRef.get()) {
                        "the run command returned without handing back its Spring context"
                    }
                assertThat(SpringApplication.exit(context))
                    .`as`("BE-05: an unattended run that delivers its document must exit 0")
                    .isEqualTo(0)
                context.close()
            } finally {
                terminateQuietly(boot, unexpectedDeath)
                runCatching { contextRef.get()?.close() }
            }
        }
    }

    /**
     * Waits for a complete outbox entry — a directory holding both `document.pdf` and
     * `metadata.json` — and captures its bytes before the runner delivers (and deletes) it.
     *
     * Returns early when the boot already ended without delivering: then no entry can ever
     * appear, and waiting out the full bound would only waste the failure.
     */
    private fun awaitDeliveredDocument(
        outboxRoot: Path,
        boot: Thread,
        contextRef: AtomicReference<ConfigurableApplicationContext>,
        bootFailure: AtomicReference<Throwable>,
    ): DeliveredDocument {
        var delivered: DeliveredDocument? = null
        await().atMost(ENTRY_AWAIT_SECONDS, TimeUnit.SECONDS).until {
            if (delivered != null) return@until true
            if (!boot.isAlive && (contextRef.get() != null || bootFailure.get() != null)) return@until true
            delivered = readCompleteEntry(outboxRoot)
            delivered != null
        }
        return checkNotNull(delivered) {
            "no document reached the outbox: boot failure=${bootFailure.get()}, " +
                "the scanner address must arrive via unboundair.scanner.host/port (SC-06)"
        }
    }

    /**
     * Reads the first complete entry, or null when none is observable right now.
     *
     * A vanished read (the runner deleted the entry between listing and reading) is also
     * null: with a single sheet queued no second entry can appear, so the wait simply runs
     * into its bound instead of failing on a confusing stack trace.
     */
    private fun readCompleteEntry(outboxRoot: Path): DeliveredDocument? {
        if (!Files.isDirectory(outboxRoot)) return null
        return try {
            Files.list(outboxRoot).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .filter { Files.exists(it.resolve(PDF_FILE_NAME)) }
                    .filter { Files.exists(it.resolve(METADATA_FILE_NAME)) }
                    .findFirst()
                    .map {
                        DeliveredDocument(
                            Files.readAllBytes(it.resolve(PDF_FILE_NAME)),
                            Files.readString(it.resolve(METADATA_FILE_NAME), Charsets.UTF_8),
                        )
                    }.orElse(null)
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Ends the service threads and returns once the boot thread left `run`.
     *
     * The workers are interrupted by name on every poll until the boot thread is gone; a
     * single interrupt could land in socket I/O and merely arm the flag, but every loop turn
     * ends in a sleeper or a client pause that throws on it, so repeated interrupts terminate
     * both threads within a bounded time. Pacing comes from Awaitility's poll interval —
     * never from `Thread.sleep` (docs/entscheidungen.md).
     *
     * The interrupt kills `scan-loop` with an *uncaught* exception (its loop has no shutdown
     * path but `stop`, which only the shutdown hook may call), and Gradle blames an uncaught
     * exception from any thread on the running test. Each worker therefore gets a handler
     * first that swallows exactly the kill signal; anything else is recorded in
     * [unexpectedDeath] and asserted after the termination, so a real defect cannot hide
     * behind the stop.
     */
    private fun terminate(
        boot: Thread,
        unexpectedDeath: AtomicReference<Throwable>,
    ) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        await()
            .atMost(TERMINATE_AWAIT_SECONDS, TimeUnit.SECONDS)
            .pollInterval(TERMINATE_POLL_MILLIS, TimeUnit.MILLISECONDS)
            .until {
                serviceThreads().forEach {
                    it.uncaughtExceptionHandler =
                        Thread.UncaughtExceptionHandler { thread, error ->
                            if (!isKillSignal(error)) {
                                unexpectedDeath.set(error)
                                defaultHandler?.uncaughtException(thread, error)
                            }
                        }
                    it.interrupt()
                }
                !boot.isAlive
            }
    }

    /** Best-effort [terminate] for the `finally` block: never masks the real failure. */
    private fun terminateQuietly(
        boot: Thread,
        unexpectedDeath: AtomicReference<Throwable>,
    ) {
        runCatching { terminate(boot, unexpectedDeath) }
        runCatching { boot.join(BOOT_JOIN_MILLIS) }
    }

    /**
     * Whether [error] is the stop signal rather than a defect: an interrupt surfacing from a
     * sleeper, or the client's pause rethrowing it as an `IllegalStateException` instead of
     * swallowing it.
     */
    private fun isKillSignal(error: Throwable): Boolean =
        error is InterruptedException ||
            (error is IllegalStateException && error.cause is InterruptedException)

    /**
     * The live workers of the `run` command, found by the names `RunCommand` gives them.
     * The names are private there, so this mirrors them — if they are renamed, the
     * termination wait times out and names the coupling instead of hanging silently.
     */
    private fun serviceThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter {
            (it.name == SCAN_LOOP_THREAD_NAME || it.name == OUTBOX_RUNNER_THREAD_NAME) && it.isAlive
        }

    /** An outbox entry captured before the runner deletes it. */
    private data class DeliveredDocument(
        val pdf: ByteArray,
        val metadataJson: String,
    )

    private companion object {
        /** The committed DL envelope fixture: a real color JPEG with a black border. */
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"

        /**
         * What the outbox names its files (AU-04). Literals, not imports: `cli` may not
         * reach into `output` (docs/plan.md layer table, enforced by the guard).
         */
        const val PDF_FILE_NAME = "document.pdf"
        const val METADATA_FILE_NAME = "metadata.json"

        /** The first bytes of every PDF: `%PDF`. */
        val PDF_MAGIC = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())

        /** The boot thread running the Spring context with the `run` command. */
        const val BOOT_THREAD_NAME = "run-command-boot"

        /** Mirrors the worker names `RunCommand` assigns (see [serviceThreads]). */
        const val SCAN_LOOP_THREAD_NAME = "scan-loop"
        const val OUTBOX_RUNNER_THREAD_NAME = "outbox-runner"

        /**
         * Generous on purpose (docs/teststrategie.md): the scan lands after roughly one
         * `poll-interval`, and the bound is never exhausted when everything works.
         */
        const val ENTRY_AWAIT_SECONDS = 90L

        /** Interrupts always land within one loop turn; 30 s is three times that. */
        const val TERMINATE_AWAIT_SECONDS = 30L
        const val TERMINATE_POLL_MILLIS = 50L
        const val BOOT_JOIN_MILLIS = 5_000L

        val jsonMapper = JsonMapper.builder().build()
    }
}
