package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.UnboundAirApplication
import dev.digiwomb.unboundair.processing.PageSettings
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
 * Tests for the `status` (BE-01) and `scan` (BE-02) commands against the [FakeScanner]
 * ("integration" layer of docs/internal/teststrategie.md).
 *
 * The status and scan tests exercise [StatusCommand] and [ScanCommand] directly,
 * so the command output and written bytes are easy to assert. `scan` runs the
 * real processing chain (crop plus grayscale), so the fake scanner's payload is
 * the committed DL envelope fixture ([TestImages.bytes]) instead of a synthetic
 * byte pattern: only a real JPEG can be run through `jpegtran`.
 *
 * The keep-raw tests (SV-06) pin the file split of `scan`: without the flag only
 * the processed page is written, with the flag the raw JPEG is stored
 * additionally, byte-equal to the scanner payload, under the derived name
 * ([ScanCommand.rawFileName]). The last test boots the whole Spring application
 * without a web environment and runs a command end to end, proving that it
 * starts, runs, and terminates on its own (BE-01).
 *
 * Offline (DC-03): the [FakeScanner] is a loopback TCP server, and the only
 * outside dependency is the system `jpegtran` of the dev container — the same
 * program the production path uses.
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

    /**
     * BE-02 -- the old M1 test of the raw-bytes write, reworked: `scan` now runs
     * the processing chain, so without `keep-raw` the requested file holds the
     * *processed* page (cropped and grayscale) and no raw file exists at all.
     */
    @Test
    fun `BE-02 scan without keep-raw writes only the processed page`(
        @TempDir dir: Path,
    ) {
        val payload = TestImages.bytes(ENVELOPE)
        val fake = FakeScanner()
        fake.payload = payload
        fake.start()
        try {
            val out = dir.resolve("page.jpg")
            val result = ScanCommand(ScannerClient("127.0.0.1", fake.port)).run(300, out)

            assertThat(result.path)
                .`as`("the result must name the requested file")
                .isEqualTo(out)
            assertThat(result.rawPath)
                .`as`("without keep-raw no raw file is written")
                .isNull()
            val raw = out.resolveSibling(ScanCommand.rawFileName(out.fileName.toString()))
            assertThat(Files.exists(raw))
                .`as`("without keep-raw no raw sibling file may exist next to the page")
                .isFalse()
            val produced = Files.readAllBytes(out)
            assertThat(produced)
                .`as`("the processed page must exist, be a non-empty JPEG, and start with the SOI marker")
                .isNotEmpty()
                .startsWith(*jpegSoi)
            assertThat(produced)
                .`as`("the processed page must end with the JPEG EOI marker")
                .endsWith(*jpegEoi)
            assertThat(produced)
                .`as`(
                    "the written file must be the processed page (cropped, grayscale), " +
                        "not a copy of the scanner payload",
                ).isNotEqualTo(payload)
        } finally {
            fake.stop()
        }
    }

    /**
     * SV-06 -- with `keep-raw` the raw JPEG is additionally stored next to the
     * processed page, byte-equal to the scanner payload: the debug copy must be
     * exactly what the device sent.
     */
    @Test
    fun `SV-06 keep-raw additionally writes the raw JPEG byte-equal to the scanner payload`(
        @TempDir dir: Path,
    ) {
        val payload = TestImages.bytes(ENVELOPE)
        val fake = FakeScanner()
        fake.payload = payload
        fake.start()
        try {
            val out = dir.resolve("page.jpg")
            val settings = PageSettings(keepRaw = true)
            val result = ScanCommand(ScannerClient("127.0.0.1", fake.port), settings).run(300, out)

            assertThat(result.path)
                .`as`("the result must name the requested file")
                .isEqualTo(out)
            val raw = out.resolveSibling(ScanCommand.rawFileName(out.fileName.toString()))
            assertThat(result.rawPath)
                .`as`("keep-raw must name the raw file derived from the target name")
                .isEqualTo(raw)
            assertThat(Files.exists(out))
                .`as`("the processed page must exist next to the raw file")
                .isTrue()
            assertThat(Files.exists(raw))
                .`as`("with keep-raw the raw file must exist next to the processed page")
                .isTrue()
            assertThat(Files.readAllBytes(raw))
                .`as`("the raw file must be byte-equal to the scanner payload")
                .isEqualTo(payload)
            assertThat(Files.readAllBytes(out))
                .`as`(
                    "the processed page must be the processed bytes, not a second copy of the raw payload",
                ).isNotEqualTo(payload)
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
        fake.payload = TestImages.bytes(ENVELOPE)
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

    private companion object {
        /** The committed DL envelope fixture: a real color JPEG with a black border. */
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"

        /** JPEG start-of-image marker: `FF D8 FF`. */
        val jpegSoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        /** JPEG end-of-image marker: `FF D9`. */
        val jpegEoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
