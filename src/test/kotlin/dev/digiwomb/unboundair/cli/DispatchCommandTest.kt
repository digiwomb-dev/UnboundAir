package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.UnboundAirApplication
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
 * Tests for the subcommand dispatch of [UnboundAirApplication] (issue #21: the dispatch
 * around `crop`, the new `--color-mode` and `--keep-raw` options, and the updated usage
 * text), plus the processing-warning forwarding (issue #18: SV-02). "Integration"
 * layer of docs/teststrategie.md: every test boots the full Spring context without a web
 * environment, passes arguments exactly as they would appear on the command line, and
 * asserts the exit code plus the files written to disk.
 *
 * The crop dispatch test needs no scanner at all (`crop` is a pure file operation,
 * BE-03); the parse-error tests fail before any scanner connection is attempted, so they
 * are scanner-free as well. Only the two successful scan tests (SV-03, SV-06) run
 * against the [FakeScanner] loopback TCP server, which is started before the boot and
 * stopped afterwards.
 *
 * Every file written by the app lands in the test's `@TempDir` via absolute `--out`
 * paths, so nothing pollutes the repository. The assertions stay at byte and file
 * existence level (e.g. SOI/EOI JPEG markers, raw file byte-equal to the payload); the
 * test package is `cli`, which may not import the `image` layer (ArchUnit guard), so no
 * image structure is inspected here.
 *
 * Offline (DC-03): only the loopback [FakeScanner] and the system `jpegtran` of the dev
 * container are involved.
 */
class DispatchCommandTest {
    /**
     * BE-03 -- `crop IN OUT` with two positional arguments runs the crop step and writes
     * the result to the requested path; the input file is never touched.
     */
    @Test
    fun `BE-03 crop with two positional arguments runs CropCommand and writes the output file`(
        @TempDir dir: Path,
    ) {
        val input = TestImages.copy(ENVELOPE, dir)
        val output = dir.resolve("cropped.jpg")

        val code = exitCode("crop", input.toString(), output.toString())

        assertThat(code)
            .`as`("a crop with input and output path must succeed with exit code 0")
            .isEqualTo(0)
        assertThat(Files.exists(output))
            .`as`("the crop result must be written to the second positional argument")
            .isTrue()
        assertThat(Files.readAllBytes(output))
            .`as`("the written file must be a non-empty JPEG")
            .isNotEmpty()
            .startsWith(*jpegSoi)
            .endsWith(*jpegEoi)
        assertThat(Files.readAllBytes(input))
            .`as`("crop must never modify its input file")
            .isEqualTo(TestImages.bytes(ENVELOPE))
    }

    /**
     * SV-02 -- processing warnings reach the user (issue #18): the `crop` dispatch
     * wires the processing warning sink to `System.err`. The `dark_page.jpg` fixture
     * contains no paper, so the crop finds none and must carry the page through
     * uncropped: the command still succeeds (exit code 0) and the output file is a
     * byte-for-byte copy of the input, while the log carries the warning. This is the
     * SV-02 acceptance from docs/plan.md -- a dark test image stays uncropped and the
     * log carries a warning -- proved through the real sink, end to end.
     */
    @Test
    fun `SV-02 crop of a dark page without paper exits 0 and reports the no-paper warning in the log`(
        @TempDir dir: Path,
    ) {
        val input = TestImages.copy(DARK_PAGE, dir)
        val output = dir.resolve("dark.jpg")

        val result = exitCodeWithDiagnostics("crop", input.toString(), output.toString())

        assertThat(result.first)
            .`as`("a crop that finds no paper must still succeed with exit code 0")
            .isEqualTo(0)
        assertThat(result.second)
            .`as`("the SV-02 acceptance is a warning in the log: the dispatch must report it (KL-02: through SLF4J)")
            .contains("no paper")
        assertThat(Files.readAllBytes(output))
            .`as`("the page is carried through uncropped: the output must be the input, byte-for-byte")
            .isEqualTo(TestImages.bytes(DARK_PAGE))
    }

    /**
     * BE-03 -- `crop` requires exactly two positional arguments; a missing one is a
     * usage error, not a scanner failure.
     */
    @Test
    fun `BE-03 crop with a single positional argument fails with exit code 1`(
        @TempDir dir: Path,
    ) {
        val input = TestImages.copy(ENVELOPE, dir)

        val code = exitCode("crop", input.toString())

        assertThat(code)
            .`as`("crop without the output path must fail with exit code 1")
            .isEqualTo(1)
    }

    /**
     * SV-03 -- an invalid `--color-mode` value is rejected while the arguments are
     * parsed, before the dispatch ever talks to a scanner: no scanner is running in
     * this test, and the error must be the parse error, not a connection failure.
     */
    @Test
    fun `SV-03 scan with an invalid color-mode value fails with exit code 1 before any scanner access`() {
        val result = exitCodeWithDiagnostics("scan", "--color-mode", "bogus")

        assertThat(result.first)
            .`as`("an invalid --color-mode value must be rejected with exit code 1")
            .isEqualTo(1)
        assertThat(result.second)
            .`as`("the failure must be the parse error, reported before any scanner connection")
            .contains("Invalid --color-mode")
    }

    /**
     * SV-03 -- `--color-mode color` is accepted through the full `scan` command against
     * the [FakeScanner]: the option is parsed, the scan runs, and the processed page is
     * written to the requested `--out` path.
     */
    @Test
    fun `SV-03 scan with --color-mode color against the fake scanner writes the processed page`(
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
                    "--color-mode",
                    "color",
                    "--out",
                    out.toString(),
                )

            assertThat(result.first)
                .`as`("--color-mode color must be accepted and the scan must succeed")
                .isEqualTo(0)
            assertThat(Files.exists(out))
                .`as`("the processed page must be written to the requested --out path")
                .isTrue()
            assertThat(Files.readAllBytes(out))
                .`as`("the written page must be a non-empty JPEG")
                .isNotEmpty()
                .startsWith(*jpegSoi)
                .endsWith(*jpegEoi)
        } finally {
            fake.stop()
        }
    }

    /**
     * SV-06 -- `--keep-raw` is accepted through the full `scan` command: in addition to
     * the processed page, the raw JPEG is written under the derived name (the target
     * name with `_raw` inserted before the extension) and is byte-equal to the scanner
     * payload.
     */
    @Test
    fun `SV-06 scan with --keep-raw additionally writes the raw JPEG next to the processed page`(
        @TempDir dir: Path,
    ) {
        val payload = TestImages.bytes(ENVELOPE)
        val fake = FakeScanner()
        fake.version = "NB0a.032"
        fake.payload = payload
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
                    "--keep-raw",
                    "--out",
                    out.toString(),
                )

            assertThat(result.first)
                .`as`("--keep-raw must be accepted and the scan must succeed")
                .isEqualTo(0)
            assertThat(Files.exists(out))
                .`as`("the processed page must exist")
                .isTrue()
            val raw = out.resolveSibling("page_raw.jpg")
            assertThat(Files.exists(raw))
                .`as`("with --keep-raw the raw file must exist next to the processed page")
                .isTrue()
            assertThat(Files.readAllBytes(raw))
                .`as`("the raw file must be byte-equal to the scanner payload")
                .isEqualTo(payload)
        } finally {
            fake.stop()
        }
    }

    /**
     * BE-02 -- an option the parser does not know is a usage error, not a scanner
     * failure: `--bogus` is rejected while parsing, before the dispatch runs.
     */
    @Test
    fun `BE-02 scan with an unknown option fails with exit code 1`() {
        val result = exitCodeWithDiagnostics("scan", "--bogus")

        assertThat(result.first)
            .`as`("an unknown option must be rejected with exit code 1")
            .isEqualTo(1)
        assertThat(result.second)
            .`as`("the failure must be the parse error naming the unknown option")
            .contains("Unknown option")
    }

    /**
     * BE-03 -- a command the application does not know prints the updated usage text
     * (the acceptance criterion of issue #21) and fails with exit code 1: the usage
     * must document the new `crop` command and the new `--color-mode`/`--keep-raw`
     * scan options.
     */
    @Test
    fun `BE-03 an unknown command prints the updated usage text and fails with exit code 1`() {
        val result = exitCodeWithDiagnostics("frobnicate")

        assertThat(result.first)
            .`as`("an unknown command must fail with exit code 1")
            .isEqualTo(1)
        assertThat(result.second)
            .`as`("the usage must document the crop command and the new scan options")
            .contains("crop IN OUT")
            .contains("--color-mode")
            .contains("--keep-raw")
    }

    /**
     * KL-02 -- the result of a command stays plain text on stdout.
     *
     * Since KL-02 the diagnostics travel through SLF4J, and Logback prefixes every line
     * with a level and a logger name. A command's *answer* must not go the same way:
     * `unboundair crop in out` is meant to be usable in a pipeline, and a line reading
     * `INFO [UnboundAirApplication] Saved: ...` would break every such use.
     *
     * This test guards the boundary from the other side than the diagnostics tests above:
     * it asserts that stdout carries exactly the bare result line and none of the log
     * decoration. Without it, nothing stops a later change from routing results through
     * the logger too, which would look tidy in the code and break the interface.
     */
    @Test
    fun `KL-02 the result of a command is bare text on stdout, not a log line`(
        @TempDir dir: Path,
    ) {
        val input = TestImages.copy(ENVELOPE, dir)
        val output = dir.resolve("cropped.jpg")

        val (code, stdout) = exitCodeWithStdout("crop", input.toString(), output.toString())

        assertThat(code).isEqualTo(0)
        assertThat(stdout.trim())
            .`as`("stdout must be exactly the result line, so the command can be piped")
            .isEqualTo("Saved: $output")
        assertThat(stdout)
            .`as`("the result must not carry a log level or a logger name")
            .doesNotContain("INFO")
            .doesNotContain("[UnboundAirApplication]")
    }

    /**
     * Boots the [UnboundAirApplication] with the given command line arguments and
     * returns its exit code. The context is closed after the exit code is taken,
     * mirroring what the real `main` does.
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
     * Like [exitCode], but with only `System.out` captured, so a test can assert what a
     * command writes to the channel a caller would pipe. Diagnostics are deliberately not
     * captured here: they must not appear in this stream in the first place.
     */
    private fun exitCodeWithStdout(vararg args: String): Pair<Int, String> {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        return try {
            exitCode(*args) to captured.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }

    /**
     * Like [exitCode], but with the diagnostic output of the boot captured, so tests can
     * assert on what the dispatch reports (processing warnings, parse errors, usage
     * text). The original streams are restored even if the boot fails.
     *
     * **Both streams are captured on purpose.** Since KL-02 these messages travel through
     * SLF4J, and Logback's console appender writes to stdout, not stderr: stdout is what a
     * container runtime and journald collect. Capturing only stderr would make every one
     * of these tests fail; capturing only stdout would tie them to that choice of
     * appender. What the tests actually care about is that the message reaches the user
     * at all, so both channels are captured and searched together.
     *
     * The distinction that still matters, and that [exitCodeWithStdout] pins, is the
     * opposite direction: a command's *result* must stay plain text on stdout so it can be
     * piped.
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

        /** A dark fixture without any paper: the crop finds none and warns (SV-02). */
        const val DARK_PAGE = "dark_page.jpg"

        /** JPEG start-of-image marker: `FF D8 FF`. */
        val jpegSoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        /** JPEG end-of-image marker: `FF D9`. */
        val jpegEoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
