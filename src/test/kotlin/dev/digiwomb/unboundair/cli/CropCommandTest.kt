package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Tests for the `crop` command (BE-03, "integration" layer of docs/internal/teststrategie.md).
 *
 * The acceptance criterion of BE-03 is that the command reproduces the SV-01
 * result on the real test images. This test drives [CropCommand.run] on a
 * copy of each committed fixture (via [TestImages.copy]) and checks the
 * written file purely by its bytes, so the assertions stay independent of
 * the `image` package: the command lives in the `cli` layer, which the
 * architecture guard (ArchitectureRulesTest) restricts to `scanner` and
 * `processing` only.
 *
 * 1. `envelope_dl_300dpi_raw.jpg` carries a background border: the command
 *    must write the SV-01 crop, byte-equal to the committed golden file
 *    `golden/envelope_dl_300dpi_crop.jpg` (the recorded `jpegtran` output of
 *    the exact 1216x2494 window at +560+56, hash pinned by the golden
 *    manifest and re-verified by EnvelopeCropGoldenTest).
 * 2. `din_a4_300dpi_raw.jpg` has no black border, so the crop changes
 *    nothing and the command must copy the input unchanged (byte-equal, and
 *    the input file must survive — a move would make it vanish).
 * 3. A bare output path without a parent directory component (the
 *    reviewer's `crop in.jpg out.jpg`) is accepted, not a
 *    NullPointerException; the file is written to the working directory
 *    and removed afterwards.
 * 4. A missing or non-regular input file is rejected with an
 *    [IllegalArgumentException] before any processing starts.
 *
 * Offline (DC-03): no scanner and no network access; the only outside
 * dependency is the system `jpegtran` of the dev container, the same one
 * the production path uses.
 */
class CropCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private val command = CropCommand()

    /**
     * BE-03 -- envelope. The paper does not fill the whole frame, so the
     * crop runs: the written file must be byte-equal to the SV-01 golden
     * result of the exact same window on the exact same fixture.
     */
    @Test
    fun `BE-03 crop writes the DL envelope byte-equal to the SV-01 golden result`() {
        val input = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val output = tempDir.resolve("envelope_crop.jpg")

        val result = command.run(input, output)

        assertThat(result)
            .`as`("the result must name the requested output path")
            .isEqualTo(output)
        assertThat(Files.exists(output))
            .`as`("the output file must exist after the crop")
            .isTrue()
        val produced = Files.readAllBytes(output)
        val golden = goldenBytes()
        assertThat(produced)
            .`as`(
                "the crop of the DL envelope must be byte-equal to the SV-01 golden file " +
                    "($GOLDEN_FILE); the command runs the very same lossless crop, so the " +
                    "stored DCT coefficients come out untouched",
            ).isEqualTo(golden)
        assertThat(sha256(produced))
            .`as`(
                "sha256 of the produced crop (readable control over the byte comparison); " +
                    "expected the golden hash",
            ).isEqualTo(sha256(golden))
        assertThat(Files.readAllBytes(input))
            .`as`("the command must never modify its input file")
            .isEqualTo(TestImages.bytes("envelope_dl_300dpi_raw.jpg"))
    }

    /**
     * BE-03 -- A4. The paper fills the whole frame, so there is nothing to
     * cut: the command must write the input unchanged (byte-equal copy).
     * The input file must also still exist afterwards, which pins down the
     * copy-not-move behavior of the pass-through branch.
     */
    @Test
    fun `BE-03 crop leaves an A4 page byte-equal to its input`() {
        val input = TestImages.copy("din_a4_300dpi_raw.jpg", tempDir)
        val output = tempDir.resolve("a4_out.jpg")

        val result = command.run(input, output)

        assertThat(result)
            .`as`("the result must name the requested output path")
            .isEqualTo(output)
        assertThat(Files.readAllBytes(output))
            .`as`("no black border means no crop: the output must be byte-equal to the input")
            .isEqualTo(Files.readAllBytes(input))
        assertThat(Files.exists(input))
            .`as`("a pass-through must copy the input, never move it away")
            .isTrue()
    }

    /**
     * BE-03 -- bare output path, regression for the reviewer defect: a
     * destination without a parent directory component (e.g. `crop in.jpg
     * out.jpg`) used to crash with a NullPointerException, because
     * `Path.parent` is `null` for a bare file name and the command passed
     * it straight to `Files.createDirectories`. The guard must simply skip
     * directory creation, mirroring the `target.parent?.let { ... }` of
     * [ScanCommand].
     *
     * The output is deliberately a bare relative path, which is resolved
     * against the test process's working directory; the A4 fixture keeps
     * the test independent of `jpegtran` (the whole-frame pass-through
     * never runs it), and a `finally` block removes the file again so the
     * working directory is left as found.
     */
    @Test
    fun `BE-03 crop accepts a bare output path without a parent directory`() {
        val input = TestImages.copy("din_a4_300dpi_raw.jpg", tempDir)
        val output = Path.of(BARE_OUTPUT_NAME)

        try {
            val result = command.run(input, output)

            assertThat(result)
                .`as`("the result must name the requested output path")
                .isEqualTo(output)
            assertThat(Files.exists(output))
                .`as`(
                    "a bare output path (null parent) must not crash; " +
                        "the file is written to the working directory",
                ).isTrue()
            assertThat(Files.readAllBytes(output))
                .`as`("no black border means no crop: the output must be byte-equal to the input")
                .isEqualTo(Files.readAllBytes(input))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    /**
     * BE-03 -- invalid input. A path that does not exist, or a path that is
     * a directory, must be rejected with an [IllegalArgumentException] before
     * any processing starts, naming the offending path in its message.
     */
    @Test
    fun `BE-03 crop rejects a missing or non-regular input path`() {
        val missing = tempDir.resolve("does-not-exist.jpg")
        val directory = Files.createTempDirectory(tempDir, "not-a-file")
        val output = tempDir.resolve("out.jpg")

        assertThatThrownBy { command.run(missing, output) }
            .`as`("a missing input file must be rejected before any processing")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(missing.fileName.toString())
        assertThatThrownBy { command.run(directory, output) }
            .`as`("a directory is not a regular input file and must be rejected")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(directory.fileName.toString())
        assertThat(Files.exists(output))
            .`as`("no output may be written when the input is invalid")
            .isFalse()
    }

    /**
     * Reads the SV-01 golden crop file from the classpath (same pattern as
     * EnvelopeCropGoldenTest).
     *
     * @return the full byte content of the golden file.
     * @throws IllegalStateException the golden file does not exist on the
     *   classpath, naming it in the message.
     */
    private fun goldenBytes(): ByteArray {
        val resource =
            javaClass.getResourceAsStream(GOLDEN_FILE)
                ?: throw IllegalStateException("golden file not found on the classpath: $GOLDEN_FILE")
        return resource.use { it.readAllBytes() }
    }

    /**
     * SHA-256 of [bytes] as lowercase hex, the format of the golden manifest.
     */
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private companion object {
        const val GOLDEN_FILE = "/golden/envelope_dl_300dpi_crop.jpg"

        /**
         * The bare file name of the regression test's output. It has no
         * parent directory component, so `Path.parent` is `null`. The name
         * is deliberately distinctive, never a plain `out.jpg`, so the test
         * can neither be confused with nor overwrite an unrelated file that
         * exists in the working directory under a common name.
         */
        const val BARE_OUTPUT_NAME = "unboundair-bare-out.jpg"
    }
}
