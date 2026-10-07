package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.BitmapInfo
import dev.digiwomb.unboundair.image.JpegInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Golden master test for the black-and-white chain (SV-08, the counterpart of
 * [ChainGrayGoldenTest], "golden-master" layer of docs/internal/teststrategie.md).
 *
 * The committed golden file `golden/envelope_dl_300dpi_bw.pbm` is the real
 * output of the production chain [CropStep] -> [GrayscaleStep] ->
 * [MonochromeStep] (with `ColorMode.BW`) run through [PageProcessor] on the
 * fixture `envelope_dl_300dpi_raw.jpg`, recorded once in the dev container;
 * its hash is pinned by `golden/manifest.sha256` (checked by
 * GoldenManifestTest). This test runs the very same chain through the
 * production path ([PageProcessor.process]) on a copy of the fixture and
 * requires the result (`mono.pbm` in the working directory, the only file
 * the chain cleanup keeps) to be byte-equal to the golden file.
 *
 * Why this test exists next to the unit test: the unit test checks the
 * threshold arithmetic on hand-built images of a few pixels; this one runs
 * 1216 x 2494 real pixels through `jpegtran` and the threshold and pins the
 * result. It catches what no hand-written assertion would think to check: a
 * change in how `LumaImage` rounds, a different `jpegtran` version
 * grayscaling differently, an iMCU alignment shift upstream in the crop. The
 * test additionally asserts the exact two control facts of the run: the
 * dimensions inherited from the SV-01 crop and the row-padding invariant
 * (`ceil(width / 8)` bytes per row) from the unit test at full scale, while
 * the focus stays on the byte equality.
 *
 * This test is also the precondition for the JBIG2 golden file (work order
 * 8): if the PBM is not byte-stable, nothing downstream of it can be.
 *
 * The file depends on the pinned versions of the dev container image:
 * `jpegtran` (grayscale step) and, for the whole bw path this file is the
 * precondition of, `jbig2` — as recorded in the spikes #140/#141 and the dev
 * container issue #143.
 *
 * Why byte equality (never recompress): the plan never re-compresses an
 * image (fixed decision from docs/plan.md) — the crop and the grayscale
 * transform the stored DCT coefficients without touching their values, and
 * the threshold packs the resulting luma deterministically, so "visually
 * identical" is not a criterion the code is held to; only bit-identical
 * output is. When the golden file moves, it must be regenerated with its
 * sha256 and the manifest line in the same commit.
 *
 * Offline (DC-03): `jpegtran` is a system dependency provided by the
 * container image; the test uses no network access.
 */
class MonochromeGoldenTest {
    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var workDir: Path

    /**
     * SV-08 -- the chain `CropStep` -> [GrayscaleStep] -> [MonochromeStep]
     * (with `ColorMode.BW`) on the DL envelope must produce a PBM file that
     * is byte-equal to the golden file: the same steps, the same
     * `jpegtran`, the same source fixture, the same threshold.
     */
    @Test
    fun `SV-08 the bw chain output is byte-equal to the golden file`() {
        val source = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val image = PageImage(source, JpegInfo.read(source))

        val settings = PageSettings(colorMode = ColorMode.BW)
        val result =
            PageProcessor(listOf(CropStep(), GrayscaleStep(), MonochromeStep(settings)))
                .process(image, workDir)

        val produced = Files.readAllBytes(result.file)
        val golden = goldenBytes()

        assertThat(produced)
            .`as`(
                "the produced bw chain output must be byte-equal to the golden file " +
                    "($GOLDEN_FILE); the lossless crop and grayscale transform the " +
                    "coefficients without re-compression and the threshold packs deterministically",
            ).isEqualTo(golden)
        assertThat(sha256(produced))
            .`as`(
                "sha256 of the produced bw chain output (readable control over the byte comparison); " +
                    "expected the golden hash",
            ).isEqualTo(sha256(golden))

        val info = result.info as BitmapInfo
        assertThat(info.width)
            .`as`("the bw chain keeps the cropped envelope width from the SV-01 crop")
            .isEqualTo(EXPECTED_WIDTH)
        assertThat(info.height)
            .`as`("the bw chain keeps the cropped envelope height from the SV-01 crop")
            .isEqualTo(EXPECTED_HEIGHT)

        val header = "P4\n${info.width} ${info.height}\n".toByteArray(Charsets.US_ASCII)
        assertThat(produced.size - header.size)
            .`as`(
                "the PBM payload must be exactly ceil(width / 8) * height bytes " +
                    "(each row starts on a byte boundary)",
            ).isEqualTo(((info.width + 7) / 8) * info.height)
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
        const val GOLDEN_FILE = "/golden/envelope_dl_300dpi_bw.pbm"
        const val EXPECTED_WIDTH = 1216
        const val EXPECTED_HEIGHT = 2494
    }
}
