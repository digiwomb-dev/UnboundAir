package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.UnboundAirApplication
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * Tests for the `status` and `scan` commands against the [FakeScanner].
 *
 * The first four tests exercise [StatusCommand] and [ScanCommand] directly,
 * so the command output and written bytes are easy to assert. The last test
 * boots the whole Spring application without a web environment and runs a
 * command end to end, proving that it starts, runs, and terminates on its
 * own (T1.9).
 */
class CommandTest {
    @Test
    fun `status reports the scanner status and firmware version`() {
        val fake = FakeScanner()
        fake.statusWord = "nopaper"
        fake.version = "NB0a.032"
        fake.start()
        try {
            val output = StatusCommand(ScannerClient("127.0.0.1", fake.port)).run()

            assertTrue(output.contains("nopaper"), "the status word must appear in the output")
            assertTrue(output.contains("NB0a.032"), "the firmware version must appear in the output")
            assertEquals(2, fake.connectionCount, "status must open one connection for the status and one for the version")
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `scan writes the raw JPEG byte-identical`(@TempDir dir: Path) {
        val fake = FakeScanner()
        fake.payload = ByteArray(1234) { index -> (index * 17 + 5).toByte() }
        fake.start()
        try {
            val out = dir.resolve("page.jpg")
            val result = ScanCommand(ScannerClient("127.0.0.1", fake.port)).run(300, out)

            assertArrayEquals(fake.payload, Files.readAllBytes(result.path), "the raw JPEG must be written unchanged")
            assertEquals(out, result.path, "the result must name the requested file")
            assertEquals(fake.payload.size, result.size, "the result must report the byte count")
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `scan requests 600 dpi when the firmware allows it`(@TempDir dir: Path) {
        val fake = FakeScanner()
        fake.version = "NB0a.032"
        fake.start()
        try {
            ScanCommand(ScannerClient("127.0.0.1", fake.port)).run(600, dir.resolve("page.jpg"))

            assertTrue(fake.receivedCommands.contains("dpi600"), "a capable firmware must receive the 600 dpi command")
            assertFalse(fake.receivedCommands.contains("dpi300"), "no 300 dpi fallback for a capable firmware")
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `the default file name follows the reference format`() {
        val now = LocalDateTime.of(2026, 9, 22, 14, 35, 0)

        assertEquals("iscan_20260922-143500_300dpi.jpg", ScanCommand.defaultFileName(300, now))
        assertEquals("iscan_20260922-143500_600dpi.jpg", ScanCommand.defaultFileName(600, now))
    }

    @Test
    fun `the application boots, runs a command, and terminates without a web server`() {
        val fake = FakeScanner()
        fake.statusWord = "nopaper"
        fake.start()
        try {
            val context = SpringApplicationBuilder(UnboundAirApplication::class.java)
                .web(WebApplicationType.NONE)
                .run("status", "--host", "127.0.0.1", "--port", fake.port.toString())

            try {
                assertEquals(0, SpringApplication.exit(context), "a successful command must exit with code 0")
            } finally {
                context.close()
            }
        } finally {
            fake.stop()
        }
    }
}
