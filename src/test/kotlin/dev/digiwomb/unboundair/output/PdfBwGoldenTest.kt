package dev.digiwomb.unboundair.output

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.Jbig2Enc
import dev.digiwomb.unboundair.image.JpegInfo
import dev.digiwomb.unboundair.processing.ColorMode
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.MonochromeStep
import dev.digiwomb.unboundair.processing.PageImage
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.processing.PageSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Golden-master test for the complete 1-bit document (SV-08).
 *
 * This is the end-to-end proof of SV-08, the bw sibling of [PdfGoldenTest],
 * and it covers the one thing no earlier test in this milestone can: that
 * the **whole chain composed** is stable. Every stage is pinned on its own --
 * `MonochromeGoldenTest` pins the PBM, `Jbig2EncTest` pins the encoder,
 * `PdfBuilderJbig2Test` pins the embedding -- but only this file notices if
 * their *combination* shifts. A threshold change the PBM golden accepts, an
 * encoder flag change that still embeds cleanly, or a page-size drift every
 * stage passes on faithfully would all turn this red and nothing else would.
 *
 * Two claims, in this order:
 *
 * - **Determinism:** the same input, assembled twice with the clock pinned,
 *   produces byte-identical output. Without this, no byte comparison against
 *   a stored file could ever be trusted -- a failure would be
 *   indistinguishable from ordinary run-to-run variation.
 * - **Content:** the bytes match the committed reference in
 *   `golden/three_pages_300dpi_bw.pdf`, whose sha256 is listed in
 *   `manifest.sha256` and therefore checked by `GoldenManifestTest` as well.
 *
 * The order matters. Determinism is asserted first: if it fails, the golden
 * comparison below is meaningless and its failure would send the reader
 * looking in the wrong place.
 *
 * A third test pins the point of the feature itself: the bw document of the
 * same three pages must be measurably smaller than the gray one. SV-08 exists
 * to make documents smaller; if the ratio ever inverts, the feature has
 * stopped earning its keep while every other assertion stays green.
 *
 * **This is the most version-sensitive artefact in the repository.** The
 * bytes depend on the pinned versions of `jpegtran`, `jbig2` (spikes
 * #140/#141, container #143) and OpenPDF; any of the three moving can turn
 * this red with no production code touched. Whoever sees it go red should
 * look at those versions first.
 *
 * **When this test fails after an intentional change,** regenerate the golden
 * file, its sha256 and its manifest entry in the same commit, and say in the
 * message why the bytes moved. A golden file updated without that explanation
 * is worthless.
 *
 * Offline (DC-03): committed fixtures, the system `jpegtran` and `jbig2`
 * from the container, a pinned clock, no device, no network.
 */
class PdfBwGoldenTest {
    @Test
    fun `SV-08 the same bw pages with a pinned clock produce byte-identical PDFs`(
        @TempDir dir: Path,
    ) {
        val first = dir.resolve("first.pdf")
        val second = dir.resolve("second.pdf")

        buildBwDocument(dir.resolve("run1"), first)
        buildBwDocument(dir.resolve("run2"), second)

        assertThat(Files.readAllBytes(first))
            .`as`(
                "two runs over the same input must agree byte for byte; without this the golden " +
                    "comparison below could not tell a regression from run-to-run noise",
            ).isEqualTo(Files.readAllBytes(second))
    }

    @Test
    fun `SV-08 the complete bw document is byte-identical to the golden PDF`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("actual.pdf")

        buildBwDocument(dir.resolve("doc"), target)

        val produced = Files.readAllBytes(target)
        val golden = goldenBytes(GOLDEN_NAME)

        assertThat(produced)
            .`as`(
                "the assembled bw PDF must match golden/$GOLDEN_NAME byte for byte; if this change was " +
                    "intended, regenerate the golden file, its sha256 and its manifest entry in the same commit",
            ).isEqualTo(golden)
        assertThat(sha256(produced))
            .`as`(
                "sha256 of the produced bw PDF (readable control over the byte comparison); " +
                    "expected the golden hash",
            ).isEqualTo(sha256(golden))
    }

    @Test
    fun `SV-08 the bw document is measurably smaller than the gray one`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("bw.pdf")

        buildBwDocument(dir.resolve("doc"), target)

        val bwBytes = Files.readAllBytes(target)
        val grayBytes = goldenBytes(GRAY_GOLDEN_NAME)

        assertThat(bwBytes.size * SIZE_WIN_FACTOR)
            .`as`(
                "the bw document of the same three pages must be smaller than the gray golden " +
                    "($GRAY_GOLDEN_NAME) by at least factor $SIZE_WIN_FACTOR; SV-08 exists to make " +
                    "documents smaller, and an inverted ratio means the feature stopped earning its keep",
            ).isLessThan(grayBytes.size)
    }

    /**
     * The document under test: envelope, A4, envelope at 300 dpi, each
     * through crop, grayscale and monochrome, encoded together with `jbig2`
     * and assembled with OpenPDF under the pinned clock.
     *
     * Two different fixtures on purpose, like [PdfGoldenTest]: three copies
     * of one image would let a builder that embeds the first page three
     * times pass unnoticed, and the differing dimensions also pin that each
     * page keeps its own size.
     */
    private fun buildBwDocument(
        root: Path,
        target: Path,
    ) {
        val fixtures = listOf(ENVELOPE, A4, ENVELOPE)
        val pbms =
            fixtures.mapIndexed { index, fixture ->
                processPage(fixture, root.resolve("page${index + 1}"))
            }
        val encodeDir = root.resolve("jbig2")
        Files.createDirectories(encodeDir)
        val jbig2 = Jbig2Enc.encode(pbms, encodeDir)
        PdfBuilder(FIXED_CLOCK).build(pbms.map { PdfPage(it, DPI) }, jbig2, target)
    }

    /**
     * Runs one fixture through the full bw chain
     * `CropStep` -> `GrayscaleStep` -> `MonochromeStep` (SV-08).
     *
     * Each page gets its own directory: every step writes a fixed file name
     * (`cropped.jpg`, `gray.jpg`, `mono.pbm`) and the chain deletes its
     * intermediates, so a shared directory would let later pages overwrite
     * earlier ones. The source copy lives inside the page directory, which
     * the processor records as pre-existing and therefore never deletes.
     */
    private fun processPage(
        fixture: String,
        pageDir: Path,
    ): Path {
        Files.createDirectories(pageDir)
        val src = TestImages.copy(fixture, pageDir)
        val chain =
            PageProcessor(
                listOf(CropStep(), GrayscaleStep(), MonochromeStep(PageSettings(colorMode = ColorMode.BW))),
            )
        return chain.process(PageImage(src, JpegInfo.read(src)), pageDir).file
    }

    private fun goldenBytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/golden/$name")?.use { it.readAllBytes() }
            ?: throw IllegalStateException("golden file not found on the classpath: /golden/$name")

    /**
     * SHA-256 of [bytes] as lowercase hex, the format of the golden manifest.
     */
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private companion object {
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"
        const val GOLDEN_NAME = "three_pages_300dpi_bw.pdf"
        const val GRAY_GOLDEN_NAME = "three_pages_300dpi.pdf"
        const val DPI = 300

        /**
         * The bw document must beat the gray one by at least this factor.
         * Measured (04.10.2026): 38 942 B vs 1 818 260 B, a factor of 46.7.
         * The factor comes from spike #140's measurement (~36x on raw pages);
         * the PDF overhead actually improves on it. 10 keeps the relation
         * honest with headroom, since the relation - not the absolute number -
         * is what pins the point of the feature.
         */
        const val SIZE_WIN_FACTOR = 10

        /**
         * The pinned instant. Any fixed value would do; this one is the
         * project's golden convention (see [PdfGoldenTest]), so the
         * metadata inside the bw golden matches its gray sibling.
         */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-27T10:15:30Z"), ZoneOffset.UTC)
    }
}
