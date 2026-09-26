package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.JpegInfo
import dev.digiwomb.unboundair.image.JpegTran
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Golden master test for the lossless crop (SV-01, "golden-master" layer of
 * docs/teststrategie.md).
 *
 * The committed golden file `golden/envelope_dl_300dpi_crop.jpg` is the real
 * output of `jpegtran -copy all -crop 1216x2494+560+56` on the fixture
 * `envelope_dl_300dpi_raw.jpg`, recorded once in the dev container; its hash
 * is pinned by `golden/manifest.sha256` (checked by GoldenManifestTest). This
 * test runs the very same crop through the production path
 * ([JpegTran.crop], which invokes the container's system `jpegtran`) and
 * requires the result to be byte-equal to the golden file.
 *
 * Why byte equality: the plan never re-compresses an image (fixed decision
 * from docs/plan.md) — the crop must copy the stored DCT coefficients
 * untouched, so "visually identical" is not a criterion the code is held to;
 * only bit-identical output is. Byte-exact comparison against a recorded
 * output guards against silent regressions where "approximately right" would
 * not be detectable: a crop that sneaks in a re-encode, drops or rewrites a
 * coefficient, or adds a marker would change at least one byte.
 *
 * The test asserts the byte equality itself, a sha256 match as a readable
 * control, and the exact dimensions of the result (1216x2494), so a
 * truncated or coincidentally similar file cannot slip through unnoticed.
 *
 * Offline (DC-03): `jpegtran` is a system dependency provided by the
 * container image; the test uses no network access.
 */
class EnvelopeCropGoldenTest {
    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var workDir: Path

    /**
     * SV-01 -- the crop of the DL envelope must be byte-equal to the golden
     * file: the same window, the same `jpegtran`, the same source fixture.
     */
    @Test
    fun `SV-01 the crop of the DL envelope is byte-equal to the golden file`() {
        val source = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val target = workDir.resolve("cropped.jpg")
        JpegTran.crop(source, target, 1216, 2494, 560, 56)

        val produced = Files.readAllBytes(target)
        val golden = goldenBytes()

        assertThat(produced)
            .`as`(
                "the produced crop must be byte-equal to the golden file " +
                    "($GOLDEN_FILE); the lossless crop copies the coefficients without re-compression",
            ).isEqualTo(golden)
        assertThat(sha256(produced))
            .`as`(
                "sha256 of the produced crop (readable control over the byte comparison); " +
                    "expected the golden hash",
            ).isEqualTo(sha256(golden))

        assertThat(JpegInfo.read(target).width)
            .`as`("the cropped image must have the exact width of the crop window")
            .isEqualTo(1216)
        assertThat(JpegInfo.read(target).height)
            .`as`("the cropped image must have the exact height of the crop window")
            .isEqualTo(2494)
    }

    /**
     * Reads the golden file from the classpath.
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
    }
}
