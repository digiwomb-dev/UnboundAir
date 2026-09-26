package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.UnboundAirApplication
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/**
 * Tests for the `status` (BE-01) and `scan` (BE-02) commands against the [FakeScanner].
 *
 * The first four tests exercise [StatusCommand] and [ScanCommand] directly,
 * so the command output and written bytes are easy to assert. The last test
 * boots the whole Spring application without a web environment and runs a
 * command end to end, proving that it starts, runs, and terminates on its
 * own (BE-01).
 */
class CommandTest {
    @Test
    fun `BE-01 status reports the scanner status and firmware version`() {
        val fake = FakeScanner()
        fake.statusWord = "nopaper"
        fake.version = "NB0a.032"
        fake.start()
        try {
            val output = StatusCommand(ScannerClient("127.0.0.1", fake.port)).run()

            assertThat(output)
                .`as`("the status word must appear in the output")
                .contains("nopaper")
            assertThat(output)
                .`as`("the firmware version must appear in the output")
                .contains("NB0a.032")
            assertThat(fake.connectionCount)
                .`as`("status must open one connection for the status and one for the version")
                .isEqualTo(2)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `BE-02 scan writes the raw JPEG byte-identical`(
        @TempDir dir: Path,
    ) {
        val fake = FakeScanner()
        fake.payload = ByteArray(1234) { index -> (index * 17 + 5).toByte() }
        fake.start()
        try {
            val out = dir.resolve("page.jpg")
            val result = ScanCommand(ScannerClient("127.0.0.1", fake.port)).run(300, out)

            assertThat(Files.readAllBytes(result.path))
                .`as`("the raw JPEG must be written unchanged")
                .isEqualTo(fake.payload)
            assertThat(result.path)
                .`as`("the result must name the requested file")
                .isEqualTo(out)
            assertThat(result.size)
                .`as`("the result must report the byte count")
                .isEqualTo(fake.payload.size)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `BE-02 scan requests 600 dpi when the firmware allows it`(
        @TempDir dir: Path,
    ) {
        val fake = FakeScanner()
        fake.version = "NB0a.032"
        fake.start()
        try {
            ScanCommand(ScannerClient("127.0.0.1", fake.port)).run(600, dir.resolve("page.jpg"))

            assertThat(fake.receivedCommands)
                .`as`("a capable firmware must receive the 600 dpi command")
                .contains("dpi600")
            assertThat(fake.receivedCommands)
                .`as`("no 300 dpi fallback for a capable firmware")
                .doesNotContain("dpi300")
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `BE-02 the default file name follows the reference format`() {
        val now = LocalDateTime.of(2026, 9, 22, 14, 35, 0)

        assertThat(ScanCommand.defaultFileName(300, now)).isEqualTo("iscan_20260922-143500_300dpi.jpg")
        assertThat(ScanCommand.defaultFileName(600, now)).isEqualTo("iscan_20260922-143500_600dpi.jpg")
    }

    @Test
    fun `BE-01 the application boots, runs a command, and terminates without a web server`() {
        val fake = FakeScanner()
        fake.statusWord = "nopaper"
        fake.start()
        try {
            val context =
                SpringApplicationBuilder(UnboundAirApplication::class.java)
                    .web(WebApplicationType.NONE)
                    .run("status", "--host", "127.0.0.1", "--port", fake.port.toString())

            try {
                assertThat(SpringApplication.exit(context))
                    .`as`("a successful command must exit with code 0")
                    .isEqualTo(0)
            } finally {
                context.close()
            }
        } finally {
            fake.stop()
        }
    }
}
