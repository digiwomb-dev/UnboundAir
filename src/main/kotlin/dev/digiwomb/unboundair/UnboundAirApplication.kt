package dev.digiwomb.unboundair

import dev.digiwomb.unboundair.cli.ScanCommand
import dev.digiwomb.unboundair.cli.StatusCommand
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.scanner.ScannerException
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Path

/**
 * Command line entry point and subcommand dispatcher.
 *
 * The application runs without a web environment ([WebApplicationType.NONE]),
 * so starting it boots the Spring context, runs exactly one subcommand, and
 * the process then ends on its own instead of a servlet container keeping it
 * alive.
 */
@SpringBootApplication
class UnboundAirApplication :
    ApplicationRunner,
    ExitCodeGenerator {
    private var commandExitCode = 0

    /**
     * Dispatches the first argument to a subcommand (`status` or `scan`).
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
                val result = ScanCommand(client).run(cli.dpi, cli.out?.let { Path.of(it) })
                println("Saved: ${result.path} (${result.size} bytes)")
            }

            else -> {
                System.err.println(USAGE)
                commandExitCode = 1
            }
        }
    }

    private fun parseCliArgs(raw: Array<String>): CliArgs {
        var command: String? = null
        var host = ScannerClient.DEFAULT_HOST
        var port = ScannerClient.DEFAULT_PORT
        var dpi = 300
        var out: String? = null

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

                else -> {
                    if (token.startsWith("--")) {
                        throw IllegalArgumentException("Unknown option: $token")
                    }
                    if (command == null) {
                        command = token
                    }
                }
            }
            i++
        }
        return CliArgs(command, host, port, dpi, out)
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
    )

    private companion object {
        val USAGE: String =
            "Usage: unboundair.jar <command> [options]\n" +
                "\n" +
                "Commands:\n" +
                "  status                            Show scanner status and firmware version.\n" +
                "  scan [--dpi 300|600] [--out FILE] Scan one page and write the raw JPEG.\n" +
                "\n" +
                "Options:\n" +
                "  --host HOST                       Scanner host (default ${ScannerClient.DEFAULT_HOST}).\n" +
                "  --port PORT                       Scanner port (default ${ScannerClient.DEFAULT_PORT}).\n"
    }
}

fun main(args: Array<String>) {
    val context =
        SpringApplicationBuilder(UnboundAirApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(*args)
    System.exit(SpringApplication.exit(context))
}
