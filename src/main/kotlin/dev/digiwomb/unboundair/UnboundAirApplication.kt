package dev.digiwomb.unboundair

import dev.digiwomb.unboundair.cli.CropCommand
import dev.digiwomb.unboundair.cli.MeasureCommand
import dev.digiwomb.unboundair.cli.RunCommand
import dev.digiwomb.unboundair.cli.ScanCommand
import dev.digiwomb.unboundair.cli.StatusCommand
import dev.digiwomb.unboundair.config.UnboundAirProperties
import dev.digiwomb.unboundair.processing.ColorMode
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.MonochromeStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.processing.PageSettings
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.scanner.ScannerException
import dev.digiwomb.unboundair.service.Batch
import dev.digiwomb.unboundair.service.ScanLoop
import dev.digiwomb.unboundair.service.outputPipeline
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/**
 * Command line entry point and subcommand dispatcher.
 *
 * The application runs without a web environment ([WebApplicationType.NONE]),
 * so starting it boots the Spring context, runs exactly one subcommand, and
 * the process then ends on its own instead of a servlet container keeping it
 * alive.
 *
 * [ConfigurationPropertiesScan] registers
 * [dev.digiwomb.unboundair.config.UnboundAirProperties] as a bean (KL-01).
 * Scanning is used rather than `@EnableConfigurationProperties` listing the
 * class explicitly, because the settings class carries its own defaults and
 * nothing else needs to be named at the registration site. Note that the
 * annotation alone is what makes `@ConfigurationProperties` take effect: the
 * annotation on the data class is inert without it.
 *
 * **Which channel carries what.** The distinction is deliberate and survives the
 * move to SLF4J (KL-02):
 *
 * - A command's **result** -- the scanner status, the path of a written file --
 *   is the answer the caller asked for. It goes to stdout as plain text through
 *   [println], so `unboundair status` can be piped into another program. Turning
 *   these into log lines would prefix them with a level and a logger name and
 *   break every such use.
 * - **Diagnostics** -- warnings from the processing chain, failures, the usage
 *   text -- go through the logger. These are the lines an operator reads in
 *   `journalctl`, and they are what KL-02 is about.
 *
 * Commands and processing steps still never log by themselves. They report
 * through their `warn: (String) -> Unit` sink, and this class decides that the
 * sink means [Logger.warn]. That keeps the core testable without a logging
 * framework and lets a later web UI route the same messages elsewhere.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class UnboundAirApplication(
    private val properties: UnboundAirProperties,
) : ApplicationRunner,
    ExitCodeGenerator {
    private var commandExitCode = 0

    /**
     * Dispatches the first argument to a subcommand (`status`, `scan`,
     * `measure`, `crop`, or `run`).
     *
     * Failures are reported on stderr and set a non-zero exit code; they do
     * not throw, so the process always terminates normally.
     */
    override fun run(args: ApplicationArguments) {
        try {
            dispatch(parseCliArgs(args.sourceArgs))
        } catch (e: ScannerException) {
            log.error(e.message)
            commandExitCode = 1
        } catch (e: IllegalArgumentException) {
            log.error(e.message)
            commandExitCode = 1
        }
    }

    override fun getExitCode(): Int = commandExitCode

    private fun dispatch(cli: CliArgs) {
        // SC-06: an explicit --host/--port flag wins over the configured
        // property, which in turn wins over the device constants. The other
        // commands are run by a human who could type the flag; `run` starts
        // in a container from the environment, so without this mapping it
        // could only ever reach the default address.
        val host = cli.host ?: properties.scanner.host
        val port = cli.port ?: properties.scanner.port
        val client = ScannerClient(host, port, ::warn)
        when (cli.command) {
            "status" -> {
                println(StatusCommand(client).run())
            }

            "scan" -> {
                val settings =
                    PageSettings(colorMode = cli.colorMode, keepRaw = cli.keepRaw, bwThreshold = cli.bwThreshold)
                val result =
                    ScanCommand(client, settings, ::warn).run(cli.dpi, cli.out?.let { Path.of(it) })
                val message =
                    if (result.rawPath != null) {
                        "Saved: ${result.path} (${result.size} bytes), raw: ${result.rawPath}"
                    } else {
                        "Saved: ${result.path} (${result.size} bytes)"
                    }
                println(message)
            }

            "measure" -> {
                // BE-04: a measuring run writes its pages to a temporary
                // directory and hands nothing to an output module. The
                // directory is removed afterwards - the scans are a by-product
                // of the measurement, not something anyone wants to keep.
                val workDir = Files.createTempDirectory("unboundair-measure")
                try {
                    val report =
                        MeasureCommand(
                            client = client,
                            pollInterval = Duration.ofSeconds(cli.pollSeconds.toLong()),
                        ).run(workDir, Duration.ofMinutes(cli.minutes.toLong()))
                    println(report.format())
                } finally {
                    runCatching { deleteRecursively(workDir) }
                }
            }

            "crop" -> {
                // The crop command is a pure image operation (BE-03): it runs
                // only the crop step and must never change the color of a page,
                // so it deliberately ignores --color-mode and --keep-raw and
                // uses the default settings. Those flags are scan-specific.
                require(cli.positional.size == 2) { "crop requires two arguments: <input> <output>" }
                val command = CropCommand(warn = ::warn)
                val result = command.run(Path.of(cli.positional[0]), Path.of(cli.positional[1]))
                println("Saved: $result")
            }

            "run" -> {
                // BE-05: the service. The output side is assembled by
                // outputPipeline, which already reads modules, outbox path
                // and paperless settings from the properties; what remains
                // here is the scan side, mapped from the same properties.
                // Signal handling arrives in work order 6; until then run
                // blocks until the process ends.
                val clock = Clock.systemDefaultZone()
                val pipeline = outputPipeline(properties, clock, clock.zone, ::warn)
                val settings =
                    PageSettings(
                        colorMode = parseColorMode(properties.colorMode),
                        bwThreshold = properties.bwThreshold,
                    )
                val processor =
                    PageProcessor(listOf(CropStep(settings), GrayscaleStep(settings), MonochromeStep(settings)))
                val loop =
                    ScanLoop(
                        client = client,
                        batch =
                            Batch(
                                clock,
                                properties.batchTimeout,
                                Files.createTempDirectory("unboundair-batch"),
                                pipeline.sink,
                            ),
                        processor = processor,
                        workDir = Files.createTempDirectory("unboundair-work"),
                        clock = clock,
                        pollInterval = properties.pollInterval,
                        offlinePollInterval = properties.offlinePollInterval,
                        idleAfter = properties.idleMinutes?.let { Duration.ofMinutes(it.toLong()) },
                    )
                RunCommand(loop, pipeline.runner, ::warn).run()
            }

            else -> {
                log.error(USAGE)
                commandExitCode = 1
            }
        }
    }

    /**
     * The warning sink handed to every command (SV-02).
     *
     * A single method reference rather than a lambda per call site, so all
     * warnings demonstrably take the same route and a change of channel happens
     * in exactly one place.
     */
    private fun warn(message: String) {
        log.warn(message)
    }

    /** Removes the temporary working directory of a measuring run. */
    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.delete(path) } }
        }
    }

    private fun parseCliArgs(raw: Array<String>): CliArgs {
        var command: String? = null
        val positional = mutableListOf<String>()
        // Nullable on purpose: null means "no flag given", and the dispatch
        // falls back to the configured property (SC-06). Defaulting to the
        // device constants here would make the properties unreachable.
        var host: String? = null
        var port: Int? = null
        var dpi = 300
        var out: String? = null
        var colorMode = ColorMode.GRAY
        var keepRaw = false
        var bwThreshold = PageSettings().bwThreshold
        var minutes = DEFAULT_MEASURE_MINUTES
        var pollSeconds = DEFAULT_POLL_SECONDS

        var i = 0
        while (i < raw.size) {
            when (val token = raw[i]) {
                "--host" -> {
                    host = valueAfter(raw, i, "--host")
                    i++
                }

                "--port" -> {
                    port = intAfter(raw, i, "--port")
                    i++
                }

                "--dpi" -> {
                    dpi = intAfter(raw, i, "--dpi")
                    i++
                }

                "--out" -> {
                    out = valueAfter(raw, i, "--out")
                    i++
                }

                "--color-mode" -> {
                    colorMode = parseColorMode(valueAfter(raw, i, "--color-mode"))
                    i++
                }

                "--keep-raw" -> {
                    keepRaw = true
                }

                "--bw-threshold" -> {
                    bwThreshold = intAfter(raw, i, "--bw-threshold")
                    i++
                }

                "--minutes" -> {
                    minutes = intAfter(raw, i, "--minutes")
                    i++
                }

                "--poll-seconds" -> {
                    pollSeconds = intAfter(raw, i, "--poll-seconds")
                    i++
                }

                else -> {
                    if (token.startsWith("--")) {
                        throw IllegalArgumentException("Unknown option: $token")
                    }
                    if (command == null) {
                        command = token
                    } else {
                        positional.add(token)
                    }
                }
            }
            i++
        }
        return CliArgs(
            command,
            host,
            port,
            dpi,
            out,
            colorMode,
            keepRaw,
            bwThreshold,
            minutes,
            pollSeconds,
            positional.toList(),
        )
    }

    /**
     * Maps the `--color-mode` value to a [ColorMode] (SV-03): `gray` is the
     * default, `color` keeps the page in its scanned color, `bw` thresholds
     * the page to 1-bit black and white.
     */
    private fun parseColorMode(value: String): ColorMode =
        when (value) {
            "gray" -> ColorMode.GRAY
            "color" -> ColorMode.COLOR
            "bw" -> ColorMode.BW
            else -> throw IllegalArgumentException("Invalid --color-mode: $value (expected 'gray', 'color' or 'bw')")
        }

    private fun valueAfter(
        raw: Array<String>,
        index: Int,
        flag: String,
    ): String = raw.getOrNull(index + 1) ?: throw IllegalArgumentException("Missing value for $flag")

    private fun intAfter(
        raw: Array<String>,
        index: Int,
        flag: String,
    ): Int =
        valueAfter(raw, index, flag).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid number for $flag: ${raw.getOrNull(index + 1)}")

    private data class CliArgs(
        val command: String?,
        val host: String?,
        val port: Int?,
        val dpi: Int,
        val out: String?,
        val colorMode: ColorMode,
        val keepRaw: Boolean,
        val bwThreshold: Int,
        val minutes: Int,
        val pollSeconds: Int,
        val positional: List<String>,
    )

    private companion object {
        val log: Logger = LoggerFactory.getLogger(UnboundAirApplication::class.java)

        /** Long enough to observe the device's five-minute auto-off (OF-01). */
        const val DEFAULT_MEASURE_MINUTES = 10

        /** The provisional poll interval of DL-01, which measure exists to validate. */
        const val DEFAULT_POLL_SECONDS = 3

        val USAGE: String =
            "Usage: unboundair.jar <command> [options]\n" +
                "\n" +
                "Commands:\n" +
                "  status                            Show scanner status and firmware version.\n" +
                "  scan [--dpi 300|600] [--out FILE] Scan one page and write the processed JPEG.\n" +
                "  crop IN OUT                       Crop an existing JPEG file (no scanner needed).\n" +
                "  measure [--minutes N]             Measure the device; sends nothing to an output module.\n" +
                "  run                               Run the service: poll the scanner and deliver documents.\n" +
                "\n" +
                "Options:\n" +
                "  --host HOST                       Scanner host (default ${ScannerClient.DEFAULT_HOST}).\n" +
                "  --port PORT                       Scanner port (default ${ScannerClient.DEFAULT_PORT}).\n" +
                "  --dpi 300|600                     Scan resolution (default 300).\n" +
                "  --out FILE                        Target file for scan (default: a timestamped file).\n" +
                "  --color-mode gray|color|bw        Color mode of scan (default gray).\n" +
                "  --bw-threshold N                  Luma threshold for --color-mode bw (default ${PageSettings().bwThreshold}).\n" +
                "  --keep-raw                        Also store the raw JPEG of scan.\n" +
                "  --minutes N                       Duration of measure (default $DEFAULT_MEASURE_MINUTES).\n" +
                "  --poll-seconds N                  Poll interval of measure (default $DEFAULT_POLL_SECONDS).\n"
    }
}

fun main(args: Array<String>) {
    val context =
        SpringApplicationBuilder(UnboundAirApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(*args)
    System.exit(SpringApplication.exit(context))
}
