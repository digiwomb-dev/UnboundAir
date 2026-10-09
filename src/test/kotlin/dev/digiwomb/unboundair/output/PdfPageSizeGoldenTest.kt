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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Golden-master test for the target page box of [PdfBuilder] (SV-09).
 *
 * Two claims, in this order:
 *
 * - **`off` changes nothing:** the same input assembled with
 *   [TargetPageSize.Off] is byte-identical to the pre-existing goldens
 *   `golden/three_pages_300dpi.pdf` (JPEG path) and
 *   `golden/three_pages_300dpi_bw.pdf` (bw path). This proves the SV-09
 *   change left the default path untouched -- the document a user who never
 *   configures a page size gets is exactly the document they got before.
 * - **`a4` is byte-stable:** the same input assembled with the `a4` target
 *   twice under the pinned clock produces byte-identical output, and the
 *   bytes match the committed references `golden/three_pages_300dpi_a4.pdf`
 *   and `golden/three_pages_300dpi_bw_a4.pdf`, whose sha256 hashes are
 *   listed in `manifest.sha256` and therefore checked by
 *   `GoldenManifestTest` as well.
 *
 * Within each claim, determinism is asserted before content: if two runs
 * disagree, the golden comparison below is meaningless and its failure
 * would send the reader looking in the wrong place.
 *
 * **Version sensitivity.** Like [PdfBwGoldenTest], the bw artefacts depend
 * on the pinned versions of `jpegtran`, `jbig2` and OpenPDF; any of the
 * three moving can turn the bw comparisons red with no production code
 * touched. Whoever regenerates a golden must do so in the dev container
 * (the same image as CI) and review the hashes before committing.
 *
 * Offline (DC-03): committed fixtures, the system `jpegtran` and `jbig2`
 * from the container, a pinned clock, no device, no network.
 */
class PdfPageSizeGoldenTest {
    @Nested
    inner class OffChangesNothing {
        @Test
        fun `SV-09 off leaves the color document byte-identical to the golden PDF`(
            @TempDir dir: Path,
        ) {
            val target = dir.resolve("actual.pdf")

            PdfBuilder(FIXED_CLOCK, TargetPageSize.Off).build(threePages(dir), target)

            assertThat(Files.readAllBytes(target))
                .`as`(
                    "off must leave the system unchanged: the assembled PDF must match " +
                        "golden/$COLOR_GOLDEN_NAME byte for byte",
                ).isEqualTo(goldenBytes(COLOR_GOLDEN_NAME))
        }

        @Test
        fun `SV-09 off leaves the bw document byte-identical to the golden bw PDF`(
            @TempDir dir: Path,
        ) {
            val target = dir.resolve("actual.pdf")

            buildBwDocument(dir.resolve("doc"), TargetPageSize.Off, target)

            assertThat(Files.readAllBytes(target))
                .`as`(
                    "off must leave the system unchanged: the assembled bw PDF must match " +
                        "golden/$BW_GOLDEN_NAME byte for byte",
                ).isEqualTo(goldenBytes(BW_GOLDEN_NAME))
        }
    }

    @Nested
    inner class A4IsByteStable {
        @Test
        fun `SV-09 a4 color pages with a pinned clock produce byte-identical PDFs`(
            @TempDir dir: Path,
        ) {
            val first = dir.resolve("first.pdf")
            val second = dir.resolve("second.pdf")

            PdfBuilder(FIXED_CLOCK, A4).build(threePages(dir.resolve("run1")), first)
            PdfBuilder(FIXED_CLOCK, A4).build(threePages(dir.resolve("run2")), second)

            assertThat(Files.readAllBytes(first))
                .`as`(
                    "two a4 runs over the same input must agree byte for byte; without this the golden " +
                        "comparison below could not tell a regression from run-to-run noise",
                ).isEqualTo(Files.readAllBytes(second))
        }

        @Test
        fun `SV-09 a4 color document is byte-identical to the golden a4 PDF`(
            @TempDir dir: Path,
        ) {
            val target = dir.resolve("actual.pdf")

            PdfBuilder(FIXED_CLOCK, A4).build(threePages(dir), target)

            val produced = Files.readAllBytes(target)
            val golden = goldenBytes(COLOR_A4_GOLDEN_NAME)

            assertThat(produced)
                .`as`(
                    "the a4 PDF must match golden/$COLOR_A4_GOLDEN_NAME byte for byte; if this change was " +
                        "intended, regenerate the golden file, its sha256 and its manifest entry in the same commit",
                ).isEqualTo(golden)
            assertThat(sha256(produced))
                .`as`(
                    "sha256 of the produced a4 PDF (readable control over the byte comparison); " +
                        "expected the golden hash",
                ).isEqualTo(sha256(golden))
        }

        @Test
        fun `SV-09 a4 bw pages with a pinned clock produce byte-identical PDFs`(
            @TempDir dir: Path,
        ) {
            val first = dir.resolve("first.pdf")
            val second = dir.resolve("second.pdf")

            buildBwDocument(dir.resolve("run1"), A4, first)
            buildBwDocument(dir.resolve("run2"), A4, second)

            assertThat(Files.readAllBytes(first))
                .`as`(
                    "two a4 bw runs over the same input must agree byte for byte; without this the golden " +
                        "comparison below could not tell a regression from run-to-run noise",
                ).isEqualTo(Files.readAllBytes(second))
        }

        @Test
        fun `SV-09 a4 bw document is byte-identical to the golden bw a4 PDF`(
            @TempDir dir: Path,
        ) {
            val target = dir.resolve("actual.pdf")

            buildBwDocument(dir.resolve("doc"), A4, target)

            val produced = Files.readAllBytes(target)
            val golden = goldenBytes(BW_A4_GOLDEN_NAME)

            assertThat(produced)
                .`as`(
                    "the a4 bw PDF must match golden/$BW_A4_GOLDEN_NAME byte for byte; if this change was " +
                        "intended, regenerate the golden file, its sha256 and its manifest entry in the same commit",
                ).isEqualTo(golden)
            assertThat(sha256(produced))
                .`as`(
                    "sha256 of the produced a4 bw PDF (readable control over the byte comparison); " +
                        "expected the golden hash",
                ).isEqualTo(sha256(golden))
        }
    }

    /**
     * The document under test: envelope, A4, envelope at 300 dpi.
     *
     * Two different fixtures on purpose, like [PdfGoldenTest]: three copies
     * of one image would let a builder that embeds the first page three
     * times pass unnoticed, and the differing dimensions also pin that each
     * page keeps its own size.
     */
    private fun threePages(dir: Path): List<PdfPage> {
        Files.createDirectories(dir)
        return listOf(
            materialise(ENVELOPE, dir, "page1"),
            materialise(A4_FIXTURE, dir, "page2"),
            materialise(ENVELOPE, dir, "page3"),
        )
    }

    private fun materialise(
        fixture: String,
        dir: Path,
        name: String,
    ): PdfPage {
        val file = dir.resolve("$name.jpg")
        Files.write(file, TestImages.bytes(fixture))
        return PdfPage(file, DPI)
    }

    /**
     * The bw document under test: envelope, A4, envelope at 300 dpi, each
     * through crop, grayscale and monochrome, encoded together with `jbig2`
     * and assembled with OpenPDF under the pinned clock and [targetPageSize].
     */
    private fun buildBwDocument(
        root: Path,
        targetPageSize: TargetPageSize,
        target: Path,
    ) {
        val fixtures = listOf(ENVELOPE, A4_FIXTURE, ENVELOPE)
        val pbms =
            fixtures.mapIndexed { index, fixture ->
                processPage(fixture, root.resolve("page${index + 1}"))
            }
        val encodeDir = root.resolve("jbig2")
        Files.createDirectories(encodeDir)
        val jbig2 = Jbig2Enc.encode(pbms, encodeDir)
        PdfBuilder(FIXED_CLOCK, targetPageSize).build(pbms.map { PdfPage(it, DPI) }, jbig2, target)
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
        const val A4_FIXTURE = "din_a4_300dpi_raw.jpg"
        const val COLOR_GOLDEN_NAME = "three_pages_300dpi.pdf"
        const val BW_GOLDEN_NAME = "three_pages_300dpi_bw.pdf"
        const val COLOR_A4_GOLDEN_NAME = "three_pages_300dpi_a4.pdf"
        const val BW_A4_GOLDEN_NAME = "three_pages_300dpi_bw_a4.pdf"
        const val DPI = 300

        val A4: TargetPageSize = TargetPageSize.parse("a4")

        /**
         * The pinned instant. Any fixed value would do; this one is the
         * project's golden convention (see [PdfGoldenTest]), so the
         * metadata inside the new goldens matches their siblings.
         */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-27T10:15:30Z"), ZoneOffset.UTC)
    }
}
