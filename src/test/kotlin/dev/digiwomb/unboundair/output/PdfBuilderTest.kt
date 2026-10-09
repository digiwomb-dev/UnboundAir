package dev.digiwomb.unboundair.output

import dev.digiwomb.unboundair.TestImages
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [PdfBuilder] (AU-01, SV-05).
 *
 * Three claims are pinned here, in descending order of how much damage their
 * failure would do:
 *
 * 1. **Every embedded JPEG is byte-identical to its input.** This is the
 *    "never recompress" guardrail of `docs/internal/plan.md`, and it is the one thing a
 *    casual reading of the code cannot confirm: `JPEGFactory.createFromImage`
 *    and `createFromByteArray` differ by one word and by whether the image
 *    survives intact. The test extracts the raw stream back out of the finished
 *    PDF and compares it with the file that went in.
 * 2. **The page measures pixels / dpi x 72 pt (SV-05).** Checked against the
 *    real fixture dimensions, and -- more importantly -- checked to use the
 *    *effective* resolution, so the SC-08 chain is honoured end to end.
 * 3. **Three pages produce three pages, in order.**
 *
 * The clock is pinned in every test. Determinism of the resulting bytes is the
 * subject of the golden-master test (#66); here it merely keeps the metadata
 * predictable.
 *
 * Offline (DC-03): only committed fixtures and PDFBox, no device, no network.
 */
class PdfBuilderTest {
    private val builder = PdfBuilder(FIXED_CLOCK)

    @Test
    fun `AU-01 three pages produce a PDF with three pages`(
        @TempDir dir: Path,
    ) {
        val pages = listOf(page(ENVELOPE, dir, "a"), page(A4, dir, "b"), page(ENVELOPE, dir, "c"))
        val target = dir.resolve("out.pdf")

        builder.build(pages, target)

        Loader.loadPDF(target.toFile()).use { document ->
            assertThat(document.numberOfPages)
                .`as`("three input pages must yield three PDF pages")
                .isEqualTo(3)
        }
    }

    /**
     * The guardrail. If this fails, the pipeline is recompressing somewhere and
     * the entire lossless promise of the project is void.
     */
    @Test
    fun `AU-01 every embedded image is byte-identical to its input file`(
        @TempDir dir: Path,
    ) {
        val first = page(ENVELOPE, dir, "first")
        val second = page(A4, dir, "second")
        val target = dir.resolve("out.pdf")

        builder.build(listOf(first, second), target)

        Loader.loadPDF(target.toFile()).use { document ->
            assertThat(embeddedJpeg(document, 0))
                .`as`("page 1 must be embedded unchanged: no recompression, not one byte")
                .isEqualTo(Files.readAllBytes(first.file))
            assertThat(embeddedJpeg(document, 1))
                .`as`("page 2 must be embedded unchanged")
                .isEqualTo(Files.readAllBytes(second.file))
        }
    }

    @Test
    fun `SV-05 the page measures pixels divided by dpi times 72 points`(
        @TempDir dir: Path,
    ) {
        // The committed envelope fixture is 1776 x 2769 px at 300 dpi.
        val target = dir.resolve("out.pdf")

        builder.build(listOf(page(ENVELOPE, dir, "e", dpi = 300)), target)

        Loader.loadPDF(target.toFile()).use { document ->
            val box = document.getPage(0).mediaBox

            assertThat(box.width)
                .`as`("1776 px / 300 dpi * 72 = 426.24 pt")
                .isCloseTo(1776f / 300f * 72f, within(TOLERANCE_POINTS))
            assertThat(box.height)
                .`as`("2769 px / 300 dpi * 72 = 664.56 pt")
                .isCloseTo(2769f / 300f * 72f, within(TOLERANCE_POINTS))
        }
    }

    /**
     * SV-05 together with SC-08: the page size follows the resolution carried by
     * the page, not a resolution assumed elsewhere. The same pixels at 600 dpi
     * describe a sheet of half the edge length.
     *
     * This is the case that would go unnoticed in production: a 600 dpi request
     * downgraded to 300 by an old firmware produces a document that looks
     * perfectly normal and is silently twice the intended size.
     */
    @Test
    fun `SV-05 the same image at 600 dpi yields a page of half the edge length`(
        @TempDir dir: Path,
    ) {
        val at300 = dir.resolve("300.pdf")
        val at600 = dir.resolve("600.pdf")

        builder.build(listOf(page(ENVELOPE, dir, "lo", dpi = 300)), at300)
        builder.build(listOf(page(ENVELOPE, dir, "hi", dpi = 600)), at600)

        Loader.loadPDF(at300.toFile()).use { low ->
            Loader.loadPDF(at600.toFile()).use { high ->
                assertThat(high.getPage(0).mediaBox.width)
                    .`as`("doubling the resolution must halve the physical width")
                    .isCloseTo(low.getPage(0).mediaBox.width / 2f, within(TOLERANCE_POINTS))
            }
        }
    }

    /**
     * Pages of differing resolution in one document each keep their own size.
     * The resolution is a property of the individual scan (SC-08), not of the
     * batch, so it must not be applied document-wide.
     */
    @Test
    fun `SV-05 pages of different resolution keep their individual sizes`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("mixed.pdf")

        builder.build(
            listOf(page(ENVELOPE, dir, "lo", dpi = 300), page(ENVELOPE, dir, "hi", dpi = 600)),
            target,
        )

        Loader.loadPDF(target.toFile()).use { document ->
            assertThat(document.getPage(1).mediaBox.width)
                .`as`("the second page must not inherit the first page's resolution")
                .isCloseTo(document.getPage(0).mediaBox.width / 2f, within(TOLERANCE_POINTS))
        }
    }

    @Test
    fun `AU-01 the creation date comes from the injected clock`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("out.pdf")

        builder.build(listOf(page(ENVELOPE, dir, "e")), target)

        Loader.loadPDF(target.toFile()).use { document ->
            assertThat(document.documentInformation.creationDate.toInstant())
                .`as`("the timestamp must be the pinned one, otherwise no PDF could be compared byte for byte")
                .isEqualTo(FIXED_INSTANT)
        }
    }

    @Test
    fun `AU-01 a document without pages is rejected`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { builder.build(emptyList(), dir.resolve("empty.pdf")) }
            .`as`("an empty batch must never reach the PDF step; failing loudly beats writing a pageless file")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one page")
    }

    @Test
    fun `AU-01 the target directory is created if it does not exist`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("nested/deeper/out.pdf")

        builder.build(listOf(page(ENVELOPE, dir, "e")), target)

        assertThat(target).exists()
    }

    /**
     * Extracts the raw, still-compressed JPEG stream of the image on [pageIndex].
     *
     * [org.apache.pdfbox.cos.COSStream.createRawInputStream] is the deliberate
     * choice here: it yields the bytes exactly as they sit in the PDF, *without*
     * running the DCTDecode filter. The alternatives would both make this test
     * meaningless -- `toByteArray()` and `createInputStream()` decode the JPEG,
     * so the comparison would be against pixel data rather than against the
     * original file, and a recompressing builder could still pass.
     */
    private fun embeddedJpeg(
        document: PDDocument,
        pageIndex: Int,
    ): ByteArray {
        val resources = document.getPage(pageIndex).resources
        val name =
            resources.xObjectNames.first { resources.getXObject(it) is PDImageXObject }
        val image = resources.getXObject(name) as PDImageXObject
        return image.cosObject.createRawInputStream().use { it.readBytes() }
    }

    /** Materialises a fixture under a distinct name and wraps it as a page. */
    private fun page(
        fixture: String,
        dir: Path,
        name: String,
        dpi: Int = 300,
    ): PdfPage {
        val file = dir.resolve("$name.jpg")
        Files.write(file, TestImages.bytes(fixture))
        return PdfPage(file, dpi)
    }

    private companion object {
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"

        /** SV-05 allows +/- 1 pt; the conversion is exact, so this is slack, not need. */
        const val TOLERANCE_POINTS = 1.0f

        val FIXED_INSTANT: Instant = Instant.parse("2026-09-27T10:15:30Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }
}
