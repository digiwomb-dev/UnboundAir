package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.JpegInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Golden master test for the full processing chain (SV-03 with the SV-01
 * crop as its first step, "golden-master" layer of docs/teststrategie.md).
 *
 * The committed golden file `golden/envelope_dl_300dpi_chain_gray.jpg` is the
 * real output of the production chain [CropStep] -> [GrayscaleStep] run
 * through [PageProcessor] on the fixture `envelope_dl_300dpi_raw.jpg`,
 * recorded once in the dev container with the system `jpegtran`; its hash is
 * pinned by `golden/manifest.sha256` (checked by GoldenManifestTest). This
 * test runs the very same chain through the production path
 * ([PageProcessor.process], whose steps invoke the container's system
 * `jpegtran`) on a copy of the fixture and requires the result to be
 * byte-equal to the golden file.
 *
 * Why byte equality: the plan never re-compresses an image (fixed decision
 * from docs/plan.md) — both steps must transform the stored DCT coefficients
 * without touching their values, so "visually identical" is not a criterion
 * the code is held to; only bit-identical output is. Byte-exact comparison
 * against a recorded output guards against silent regressions where
 * "approximately right" would not be detectable: a step that sneaks in a
 * re-encode, drops or rewrites a coefficient, or adds a marker would change
 * at least one byte. The test additionally asserts the exact two control
 * facts of the run: a single luma component (the grayscale step really ran)
 * and a clean working directory (the intermediate `cropped.jpg` was deleted),
 * while the focus stays on the byte equality.
 *
 * Offline (DC-03): `jpegtran` is a system dependency provided by the
 * container image; the test uses no network access.
 */
class ChainGrayGoldenTest {
    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var workDir: Path

    /**
     * SV-03 -- the chain `CropStep` -> [GrayscaleStep] on the DL envelope
     * must produce a file that is byte-equal to the golden file: the same
     * steps, the same `jpegtran`, the same source fixture.
     */
    @Test
    fun `SV-03 the chain output is byte-equal to the golden file`() {
        val source = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val image = PageImage(source, JpegInfo.read(source))

        val result = PageProcessor(listOf(CropStep(), GrayscaleStep())).process(image, workDir)

        val produced = Files.readAllBytes(result.file)
        val golden = goldenBytes()

        assertThat(produced)
            .`as`(
                "the produced chain output must be byte-equal to the golden file " +
                    "($GOLDEN_FILE); the lossless crop and grayscale transform the " +
                    "coefficients without re-compression",
            ).isEqualTo(golden)
        assertThat(sha256(produced))
            .`as`(
                "sha256 of the produced chain output (readable control over the byte comparison); " +
                    "expected the golden hash",
            ).isEqualTo(sha256(golden))

        assertThat(result.info.components)
            .`as`("the grayscale step converts the three color components to a single luma")
            .isEqualTo(1)
        assertThat(Files.exists(workDir.resolve("cropped.jpg")))
            .`as`("the intermediate crop output must be deleted by the chain cleanup")
            .isFalse()
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
        const val GOLDEN_FILE = "/golden/envelope_dl_300dpi_chain_gray.jpg"
    }
}
