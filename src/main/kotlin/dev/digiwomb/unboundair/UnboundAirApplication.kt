package dev.digiwomb.unboundair

import dev.digiwomb.unboundair.cli.CropCommand
import dev.digiwomb.unboundair.cli.ScanCommand
import dev.digiwomb.unboundair.cli.StatusCommand
import dev.digiwomb.unboundair.processing.ColorMode
import dev.digiwomb.unboundair.processing.PageSettings
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.scanner.ScannerException
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import java.nio.file.Path

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
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class UnboundAirApplication :
    ApplicationRunner,
    ExitCodeGenerator {
    private var commandExitCode = 0

    /**
     * Dispatches the first argument to a subcommand (`status`, `scan`, or
     * `crop`).
     *
     * Failures are reported on stderr and set a non-zero exit code; they do
     * not throw, so the process always terminates normally.
     */
    override fun run(args: ApplicationArguments) {
        try {
            dispatch(parseCliArgs(args.sourceArgs))
        } catch (e: ScannerException) {
            System.err.println(e.message)
            commandExitCode = 1
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            commandExitCode = 1
        }
    }

    override fun getExitCode(): Int = commandExitCode

    private fun dispatch(cli: CliArgs) {
        val client = ScannerClient(cli.host, cli.port) { warning -> System.err.println(warning) }
        when (cli.command) {
            "status" -> {
                println(StatusCommand(client).run())
            }

            "scan" -> {
                val settings = PageSettings(colorMode = cli.colorMode, keepRaw = cli.keepRaw)
                val result =
                    ScanCommand(client, settings) { warning -> System.err.println(warning) }.run(cli.dpi, cli.out?.let { Path.of(it) })
                val message =
                    if (result.rawPath != null) {
                        "Saved: ${result.path} (${result.size} bytes), raw: ${result.rawPath}"
                    } else {
                        "Saved: ${result.path} (${result.size} bytes)"
                    }
                println(message)
            }

            "crop" -> {
                // The crop command is a pure image operation (BE-03): it runs
                // only the crop step and must never change the color of a page,
                // so it deliberately ignores --color-mode and --keep-raw and
                // uses the default settings. Those flags are scan-specific.
                require(cli.positional.size == 2) { "crop requires two arguments: <input> <output>" }
                val command = CropCommand { warning -> System.err.println(warning) }
                val result = command.run(Path.of(cli.positional[0]), Path.of(cli.positional[1]))
                println("Saved: $result")
            }

            else -> {
                System.err.println(USAGE)
                commandExitCode = 1
            }
        }
    }

    private fun parseCliArgs(raw: Array<String>): CliArgs {
        var command: String? = null
        val positional = mutableListOf<String>()
        var host = ScannerClient.DEFAULT_HOST
        var port = ScannerClient.DEFAULT_PORT
        var dpi = 300
        var out: String? = null
        var colorMode = ColorMode.GRAY
        var keepRaw = false

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
        return CliArgs(command, host, port, dpi, out, colorMode, keepRaw, positional.toList())
    }

    /**
     * Maps the `--color-mode` value to a [ColorMode] (SV-03): `gray` is the
     * default, `color` keeps the page in its scanned color.
     */
    private fun parseColorMode(value: String): ColorMode =
        when (value) {
            "gray" -> ColorMode.GRAY
            "color" -> ColorMode.COLOR
            else -> throw IllegalArgumentException("Invalid --color-mode: $value (expected 'gray' or 'color')")
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
        val host: String,
        val port: Int,
        val dpi: Int,
        val out: String?,
        val colorMode: ColorMode,
        val keepRaw: Boolean,
        val positional: List<String>,
    )

    private companion object {
        val USAGE: String =
            "Usage: unboundair.jar <command> [options]\n" +
                "\n" +
                "Commands:\n" +
                "  status                            Show scanner status and firmware version.\n" +
                "  scan [--dpi 300|600] [--out FILE] Scan one page and write the processed JPEG.\n" +
                "  crop IN OUT                       Crop an existing JPEG file (no scanner needed).\n" +
                "\n" +
                "Options:\n" +
                "  --host HOST                       Scanner host (default ${ScannerClient.DEFAULT_HOST}).\n" +
                "  --port PORT                       Scanner port (default ${ScannerClient.DEFAULT_PORT}).\n" +
                "  --dpi 300|600                     Scan resolution (default 300).\n" +
                "  --out FILE                        Target file for scan (default: a timestamped file).\n" +
                "  --color-mode gray|color           Color mode of scan (default gray).\n" +
                "  --keep-raw                        Also store the raw JPEG of scan.\n"
    }
}

fun main(args: Array<String>) {
    val context =
        SpringApplicationBuilder(UnboundAirApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(*args)
    System.exit(SpringApplication.exit(context))
}
