package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.UnboundAirApplication
import dev.digiwomb.unboundair.processing.PageSettings
import dev.digiwomb.unboundair.scanner.FakeScanner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the `--color-mode bw` option and the `--bw-threshold` setting
 * (issue #167: SV-08 -- the bw option and the threshold reach the settings).
 * "Integration" layer of docs/teststrategie.md: every test boots the full
 * Spring context without a web environment, passes arguments exactly as they
 * would appear on the command line, and asserts the exit code plus the files
 * written to disk.
 *
 * The parse-error tests fail while the arguments are parsed, before the
 * dispatch ever talks to a scanner, so they are scanner-free. The successful
 * scan tests run against the [FakeScanner] loopback TCP server, which is
 * started before the boot and stopped afterwards.
 *
 * Every file written by the app lands in the test's `@TempDir` via absolute
 * `--out` paths, so nothing pollutes the repository. The assertions stay at
 * exit-code, stdout/diagnostics and file-byte level (PBM magic bytes, JPEG
 * SOI marker, byte equality of outputs); the test package is `cli`, which may
 * not import the `image` layer (ArchUnit guard), so no image structure is
 * inspected here.
 *
 * Offline (DC-03): only argument parsing plus the loopback [FakeScanner] and
 * the system `jpegtran` of the dev container are involved.
 */
class BwOptionTest {
    /**
     * SV-08 -- `--color-mode bw` is accepted and reaches [PageSettings] as
     * `ColorMode.BW`: the bw scan against the [FakeScanner] succeeds and the
     * output file starts with the binary PBM magic `P4` -- the observable
     * proof the BW flow went through.
     */
    @Test
    fun `SV-08 scan with --color-mode bw writes a binary PBM file`(
        @TempDir dir: Path,
    ) {
        val fake = FakeScanner()
        fake.version = "NB0a.032"
        fake.payload = TestImages.bytes(ENVELOPE)
        fake.start()
        try {
            val out = dir.resolve("page.pbm")
            val result =
                exitCodeWithDiagnostics(
                    "scan",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    fake.port.toString(),
                    "--color-mode",
                    "bw",
                    "--out",
                    out.toString(),
                )

            assertThat(result.first)
                .`as`("--color-mode bw must be accepted and the scan must succeed")
                .isEqualTo(0)
            assertThat(Files.exists(out))
                .`as`("the bw page must be written to the requested --out path")
                .isTrue()
            assertThat(Files.readAllBytes(out))
                .`as`("the bw output must be a binary PBM starting with the P4 magic")
                .isNotEmpty()
                .startsWith(*pbmMagic)
        } finally {
            fake.stop()
        }
    }

    /**
     * SV-08 -- an invalid `--color-mode` value is rejected while the arguments
     * are parsed, and the message names all three values: a user who typed
     * `bq` is actually told that `bw` exists.
     */
    @Test
    fun `SV-08 scan with an invalid color-mode value names all three modes`() {
        val result = exitCodeWithDiagnostics("scan", "--color-mode", "bq")

        assertThat(result.first)
            .`as`("an invalid --color-mode value must be rejected with exit code 1")
            .isEqualTo(1)
        assertThat(result.second)
            .`as`("the parse error must name the bw mode alongside gray and color")
            .contains("bw")
    }

    /**
     * SV-08 -- `--bw-threshold` is read, not merely accepted: two bw scans
     * with thresholds 64 and 200 both succeed, but the PBM files are
     * byte-different (the envelope fixture has pixels on both sides of both
     * thresholds).
     */
    @Test
    fun `SV-08 scans with different bw thresholds produce byte-different PBM files`(
        @TempDir dir: Path,
    ) {
        val low = scanBw(dir.resolve("low.pbm"), "64")
        val high = scanBw(dir.resolve("high.pbm"), "200")

        assertThat(low.first)
            .`as`("a bw scan with --bw-threshold 64 must succeed")
            .isEqualTo(0)
        assertThat(high.first)
            .`as`("a bw scan with --bw-threshold 200 must succeed")
            .isEqualTo(0)
        assertThat(Files.readAllBytes(dir.resolve("low.pbm")))
            .`as`("different thresholds must pack different bits for mixed-luma pages")
            .isNotEqualTo(Files.readAllBytes(dir.resolve("high.pbm")))
    }

    /**
     * SV-08 -- the default threshold is 128 and comes from [PageSettings],
     * not from a literal in the application class: a bw scan with no
     * `--bw-threshold` and one with the explicit
     * `--bw-threshold ${PageSettings().bwThreshold}` produce byte-identical
     * files. If the app hardcoded a second copy of the default and
     * [PageSettings] moved, this fails.
     */
    @Test
    fun `SV-08 omitting --bw-threshold uses the PageSettings default`(
        @TempDir dir: Path,
    ) {
        val default = scanBw(dir.resolve("default.pbm"), null)
        val explicit = scanBw(dir.resolve("explicit.pbm"), PageSettings().bwThreshold.toString())

        assertThat(default.first)
            .`as`("a bw scan without --bw-threshold must succeed")
            .isEqualTo(0)
        assertThat(explicit.first)
            .`as`("a bw scan with the explicit default threshold must succeed")
            .isEqualTo(0)
        assertThat(Files.readAllBytes(dir.resolve("default.pbm")))
            .`as`("the omitted threshold must equal the PageSettings default")
            .isEqualTo(Files.readAllBytes(dir.resolve("explicit.pbm")))
    }

    /**
     * SV-08 -- a non-numeric `--bw-threshold` fails while parsing and names
     * the flag, before any scanner connection is attempted.
     */
    @Test
    fun `SV-08 scan with a non-numeric bw threshold fails naming the flag`() {
        val result = exitCodeWithDiagnostics("scan", "--bw-threshold", "abc")

        assertThat(result.first)
            .`as`("a non-numeric --bw-threshold must be rejected with exit code 1")
            .isEqualTo(1)
        assertThat(result.second)
            .`as`("the parse error must name the --bw-threshold flag")
            .contains("Invalid number for --bw-threshold")
    }

    /**
     * SV-08 -- out-of-range thresholds are rejected by the range check (the
     * `bwThreshold must be within 1..255` message): the diagnostics name the
     * setting and the offending value.
     */
    @Test
    fun `SV-08 scan with an out-of-range bw threshold fails with the range message`() {
        val zero = exitCodeWithDiagnostics("scan", "--bw-threshold", "0")
        val over = exitCodeWithDiagnostics("scan", "--bw-threshold", "300")

        assertThat(zero.first)
            .`as`("--bw-threshold 0 must be rejected with exit code 1")
            .isEqualTo(1)
        assertThat(zero.second)
            .`as`("the range error must name the setting and the offending value 0")
            .contains("bwThreshold")
            .contains("0")
        assertThat(over.first)
            .`as`("--bw-threshold 300 must be rejected with exit code 1")
            .isEqualTo(1)
        assertThat(over.second)
            .`as`("the range error must name the setting and the offending value 300")
            .contains("bwThreshold")
            .contains("300")
    }

    /**
     * SV-08 -- the usage text names all three color modes and the new
     * threshold option (an unknown command prints the usage and fails with
     * exit code 1).
     */
    @Test
    fun `SV-08 the usage text names all three color modes and the bw threshold option`() {
        val result = exitCodeWithDiagnostics("frobnicate")

        assertThat(result.first)
            .`as`("an unknown command must fail with exit code 1")
            .isEqualTo(1)
        assertThat(result.second)
            .`as`("the usage must document the gray, color and bw modes plus --bw-threshold")
            .contains("gray|color|bw")
            .contains("--bw-threshold")
    }

    /**
     * SV-08 -- `--bw-threshold` without `--color-mode bw` is accepted and
     * inert: it describes a mode that is off, so refusing the combination
     * would be surprising. The default gray scan still succeeds and writes a
     * JPEG, not a PBM.
     */
    @Test
    fun `SV-08 --bw-threshold without --color-mode bw is accepted and inert`(
        @TempDir dir: Path,
    ) {
        val fake = FakeScanner()
        fake.version = "NB0a.032"
        fake.payload = TestImages.bytes(ENVELOPE)
        fake.start()
        try {
            val out = dir.resolve("page.jpg")
            val result =
                exitCodeWithDiagnostics(
                    "scan",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    fake.port.toString(),
                    "--bw-threshold",
                    "64",
                    "--out",
                    out.toString(),
                )

            assertThat(result.first)
                .`as`("--bw-threshold without --color-mode bw must be accepted and the scan must succeed")
                .isEqualTo(0)
            assertThat(Files.readAllBytes(out))
                .`as`("the default gray mode must still write a JPEG, not a PBM")
                .isNotEmpty()
                .startsWith(*jpegSoi)
        } finally {
            fake.stop()
        }
    }

    /**
     * Runs one bw `scan` against the loopback [FakeScanner] and returns the
     * exit code with the captured diagnostics; a `null` threshold omits the
     * flag. The scanned page is written to [out].
     */
    private fun scanBw(
        out: Path,
        threshold: String?,
    ): Pair<Int, String> {
        val fake = FakeScanner()
        fake.version = "NB0a.032"
        fake.payload = TestImages.bytes(ENVELOPE)
        fake.start()
        try {
            val args =
                mutableListOf("scan", "--host", "127.0.0.1", "--port", fake.port.toString(), "--color-mode", "bw")
            if (threshold != null) {
                args += listOf("--bw-threshold", threshold)
            }
            args += listOf("--out", out.toString())
            return exitCodeWithDiagnostics(*args.toTypedArray())
        } finally {
            fake.stop()
        }
    }

    /**
     * Boots the [UnboundAirApplication] with the given command line arguments
     * and returns its exit code. The context is closed after the exit code is
     * taken, mirroring what the real `main` does.
     */
    private fun exitCode(vararg args: String): Int {
        val context =
            SpringApplicationBuilder(UnboundAirApplication::class.java)
                .web(WebApplicationType.NONE)
                .run(*args)
        return try {
            SpringApplication.exit(context)
        } finally {
            context.close()
        }
    }

    /**
     * Like [exitCode], but with the diagnostic output of the boot captured, so
     * tests can assert on what the dispatch reports (parse errors, usage
     * text). The original streams are restored even if the boot fails.
     *
     * **Both streams are captured on purpose.** Since KL-02 these messages
     * travel through SLF4J, and Logback's console appender writes to stdout,
     * not stderr: stdout is what a container runtime and journald collect.
     * Capturing only stderr would make every one of these tests fail;
     * capturing only stdout would tie them to that choice of appender. What
     * the tests actually care about is that the message reaches the user at
     * all, so both channels are captured and searched together.
     */
    private fun exitCodeWithDiagnostics(vararg args: String): Pair<Int, String> {
        val originalOut = System.out
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        val stream = PrintStream(captured, true, Charsets.UTF_8)
        System.setOut(stream)
        System.setErr(stream)
        return try {
            exitCode(*args) to captured.toString(Charsets.UTF_8)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private companion object {
        /** The committed DL envelope fixture: a real color JPEG with a black border. */
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"

        /** Binary PBM magic: `P4`. */
        val pbmMagic = byteArrayOf(0x50.toByte(), 0x34.toByte())

        /** JPEG start-of-image marker: `FF D8 FF`. */
        val jpegSoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }
}
