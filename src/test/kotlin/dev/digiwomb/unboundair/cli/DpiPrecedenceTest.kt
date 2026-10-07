package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.UnboundAirApplication
import dev.digiwomb.unboundair.scanner.FakeScanner
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Tests that the CLI flags win over the configured properties and that an omitted flag
 * falls back to them (issue #223: SC-07, SC-08).
 *
 * The precedence rule already existed for `--host`/`--port` (SC-06); `--dpi`, `--color-mode`,
 * `--bw-threshold` and `--keep-raw` used to carry hardcoded defaults in the argument parser,
 * which made the properties unreachable. Now a missing flag is `null` and the dispatch falls
 * back to the property, for `scan` and for `run` alike.
 *
 * "Integration" layer of docs/internal/teststrategie.md: every test boots the full Spring context
 * without a web environment and observes which dpi command reached the loopback [FakeScanner].
 * Properties travel the way a container passes them — via `builder.properties(...)`, not as
 * command line tokens, because `parseCliArgs` rejects every `--`-prefixed token it does not
 * know (see `RunCommandIntegrationTest`).
 *
 * Offline (DC-03): only the loopback [FakeScanner] and the system `jpegtran` of the dev
 * container are involved.
 */
class DpiPrecedenceTest {
    @Nested
    inner class Scan {
        @Test
        fun `SC-07 scan without --dpi falls back to the configured unboundair dpi`(
            @TempDir dir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.payload = TestImages.bytes(ENVELOPE)
                fake.start()

                val out = dir.resolve("page.jpg")
                val code =
                    scanExitCode(
                        mapOf(
                            "unboundair.scanner.host" to "127.0.0.1",
                            "unboundair.scanner.port" to fake.port.toString(),
                            "unboundair.dpi" to "600",
                        ),
                        "scan",
                        "--out",
                        out.toString(),
                    )

                assertThat(code)
                    .`as`("the scan must succeed so the dpi observation proves something")
                    .isEqualTo(0)
                assertThat(fake.receivedCommands)
                    .`as`("SC-07: without --dpi the configured unboundair.dpi=600 must reach the scanner")
                    .contains("dpi600")
            }
        }

        @Test
        fun `SC-07 scan with --dpi overrides the configured unboundair dpi`(
            @TempDir dir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.payload = TestImages.bytes(ENVELOPE)
                fake.start()

                val out = dir.resolve("page.jpg")
                val code =
                    scanExitCode(
                        mapOf(
                            "unboundair.scanner.host" to "127.0.0.1",
                            "unboundair.scanner.port" to fake.port.toString(),
                            "unboundair.dpi" to "600",
                        ),
                        "scan",
                        "--dpi",
                        "300",
                        "--out",
                        out.toString(),
                    )

                assertThat(code)
                    .`as`("the scan must succeed so the dpi observation proves something")
                    .isEqualTo(0)
                assertThat(fake.receivedCommands)
                    .`as`("SC-07: an explicit --dpi 300 must win over the configured unboundair.dpi=600")
                    .contains("dpi300")
                assertThat(fake.receivedCommands)
                    .`as`("SC-07: the overridden property value must never reach the scanner")
                    .doesNotContain("dpi600")
            }
        }
    }

    @Nested
    inner class Run {
        @Test
        fun `SC-08 run without --dpi falls back to the configured unboundair dpi`(
            @TempDir dir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
                fake.start()
                val outboxRoot = dir.resolve("outbox")
                Files.createDirectories(outboxRoot)

                val boot = bootRun(fake, outboxRoot, mapOf("unboundair.dpi" to "600"), listOf("run"))
                try {
                    await()
                        .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                        .until { fake.receivedCommands.contains("dpi600") }

                    assertThat(fake.receivedCommands)
                        .`as`("SC-08: without --dpi the configured unboundair.dpi=600 must reach the scanner")
                        .contains("dpi600")
                } finally {
                    stopRun(boot)
                }
            }
        }

        @Test
        fun `SC-08 run with --dpi overrides the configured unboundair dpi`(
            @TempDir dir: Path,
        ) {
            FakeScanner().use { fake ->
                fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
                fake.start()
                val outboxRoot = dir.resolve("outbox")
                Files.createDirectories(outboxRoot)

                val boot =
                    bootRun(
                        fake,
                        outboxRoot,
                        mapOf("unboundair.dpi" to "600"),
                        listOf("run", "--dpi", "300"),
                    )
                try {
                    await()
                        .atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                        .until { fake.receivedCommands.contains("dpi300") }

                    assertThat(fake.receivedCommands)
                        .`as`("SC-08: an explicit --dpi 300 must win over the configured unboundair.dpi=600")
                        .contains("dpi300")
                    assertThat(fake.receivedCommands)
                        .`as`("SC-08: the overridden property value must never reach the scanner")
                        .doesNotContain("dpi600")
                } finally {
                    stopRun(boot)
                }
            }
        }
    }

    /**
     * Boots the [UnboundAirApplication] for one synchronous `scan` with the given Spring
     * properties and command line arguments, and returns its exit code.
     */
    private fun scanExitCode(
        properties: Map<String, String>,
        vararg args: String,
    ): Int {
        val builder =
            SpringApplicationBuilder(UnboundAirApplication::class.java)
                .web(WebApplicationType.NONE)
        properties.forEach { (key, value) -> builder.properties("$key=$value") }
        val context = builder.run(*args)
        return try {
            SpringApplication.exit(context)
        } finally {
            context.close()
        }
    }

    /** The boot thread of a `run` command, with its context and failure captured. */
    private data class RunBoot(
        val thread: Thread,
        val contextRef: AtomicReference<ConfigurableApplicationContext?>,
        val bootFailure: AtomicReference<Throwable?>,
        val unexpectedDeath: AtomicReference<Throwable?>,
    )

    /**
     * Starts `run` on a daemon thread the way `RunCommandIntegrationTest` does: the scanner
     * address and the outbox travel via Spring properties, only the command itself (plus the
     * flags under test) goes through `argv`.
     */
    private fun bootRun(
        fake: FakeScanner,
        outboxRoot: Path,
        extraProperties: Map<String, String>,
        argv: List<String>,
    ): RunBoot {
        val contextRef = AtomicReference<ConfigurableApplicationContext?>(null)
        val bootFailure = AtomicReference<Throwable?>(null)
        val unexpectedDeath = AtomicReference<Throwable?>(null)
        val boot =
            thread(start = true, isDaemon = true, name = BOOT_THREAD_NAME) {
                try {
                    val builder =
                        SpringApplicationBuilder(UnboundAirApplication::class.java)
                            .web(WebApplicationType.NONE)
                            .properties(
                                "unboundair.scanner.host=127.0.0.1",
                                "unboundair.scanner.port=${fake.port}",
                                "unboundair.outbox.path=$outboxRoot",
                            )
                    extraProperties.forEach { (key, value) -> builder.properties("$key=$value") }
                    contextRef.set(builder.run(*argv.toTypedArray()))
                } catch (e: Throwable) {
                    bootFailure.set(e)
                }
            }
        return RunBoot(boot, contextRef, bootFailure, unexpectedDeath)
    }

    /**
     * Ends the `run` boot the only deterministic way the tests have (see
     * `RunCommandIntegrationTest.terminate`): interrupt the workers by name until the boot
     * thread is gone, then take the exit code and close the context.
     */
    private fun stopRun(boot: RunBoot) {
        try {
            terminate(boot)
            boot.thread.join(BOOT_JOIN_MILLIS)
            assertThat(boot.thread.isAlive)
                .`as`("run must terminate once stopped, so a hanging thread fails here")
                .isFalse()
            assertThat(boot.unexpectedDeath.get())
                .`as`("no worker may die from anything but the stop signal")
                .isNull()
            val context =
                checkNotNull(boot.contextRef.get()) {
                    "the run command returned without handing back its Spring context"
                }
            assertThat(SpringApplication.exit(context))
                .`as`("an observed run must exit 0")
                .isEqualTo(0)
            context.close()
        } finally {
            runCatching { terminate(boot) }
            runCatching { boot.thread.join(BOOT_JOIN_MILLIS) }
            runCatching { boot.contextRef.get()?.close() }
        }
    }

    private fun terminate(boot: RunBoot) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        await()
            .atMost(TERMINATE_AWAIT_SECONDS, TimeUnit.SECONDS)
            .pollInterval(TERMINATE_POLL_MILLIS, TimeUnit.MILLISECONDS)
            .until {
                serviceThreads().forEach {
                    it.uncaughtExceptionHandler =
                        Thread.UncaughtExceptionHandler { thread, error ->
                            if (!isKillSignal(error)) {
                                boot.unexpectedDeath.set(error)
                                defaultHandler?.uncaughtException(thread, error)
                            }
                        }
                    it.interrupt()
                }
                !boot.thread.isAlive
            }
    }

    private fun isKillSignal(error: Throwable): Boolean =
        error is InterruptedException ||
            (error is IllegalStateException && error.cause is InterruptedException)

    private fun serviceThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter {
            (it.name == SCAN_LOOP_THREAD_NAME || it.name == OUTBOX_RUNNER_THREAD_NAME) && it.isAlive
        }

    private companion object {
        /** The committed DL envelope fixture: a real color JPEG with a black border. */
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"

        /** The boot thread running the Spring context with the `run` command. */
        const val BOOT_THREAD_NAME = "run-command-boot"

        /** Mirrors the worker names `RunCommand` assigns. */
        const val SCAN_LOOP_THREAD_NAME = "scan-loop"
        const val OUTBOX_RUNNER_THREAD_NAME = "outbox-runner"

        const val AWAIT_SECONDS = 90L
        const val TERMINATE_AWAIT_SECONDS = 30L
        const val TERMINATE_POLL_MILLIS = 50L
        const val BOOT_JOIN_MILLIS = 5_000L
    }
}
