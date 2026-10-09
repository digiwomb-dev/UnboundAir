package dev.digiwomb.unboundair.output

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.Jbig2Enc
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO

/**
 * Unit tests for the fixed target page box of [PdfBuilder] (SV-09).
 *
 * The finished PDF is read back with PDFBox, the independent verifier: OpenPDF
 * writes, PDFBox checks. A test whose reader is its own writer would confirm
 * only the writer's self-consistency, which is not what anyone wanted to know.
 *
 * Placement is asserted on the content stream's `cm` matrix (`a b c d e f cm`):
 * `a`/`d` are the drawn width/height in points, `e`/`f` the offset of the
 * image origin, `b`/`c` the shear (zero means upright, never rotated). The
 * offsets are rounded to 1/100 pt by OpenPDF's number formatting, which is why
 * the tolerance here is 0.01 pt -- deliberately tighter than the 1 pt slack of
 * the SV-05 size tests.
 *
 * Offline (DC-03): committed fixtures, a locally synthesised landscape JPEG and
 * hand-built PBMs, a pinned clock, no device, no network.
 */
class PdfPageSizeTest {
    @Nested
    inner class OffKeepsScanSize {
        @Test
        fun `SV-09 off sizes the box as pixels divided by dpi, drawn from the origin`(
            @TempDir dir: Path,
        ) {
            // The committed envelope fixture is 1776 x 2769 px at 300 dpi.
            val target = dir.resolve("out.pdf")

            PdfBuilder(FIXED_CLOCK).build(listOf(page(ENVELOPE, dir, "e", dpi = 300)), target)

            Loader.loadPDF(target.toFile()).use { document ->
                val box = document.getPage(0).mediaBox
                assertThat(box.width)
                    .`as`("off must keep SV-05: 1776 px / 300 dpi * 72 = 426.24 pt")
                    .isCloseTo(426.24f, within(TOLERANCE_POINTS))
                assertThat(box.height)
                    .`as`("off must keep SV-05: 2769 px / 300 dpi * 72 = 664.56 pt")
                    .isCloseTo(664.56f, within(TOLERANCE_POINTS))
                val matrix = imageMatrix(document.getPage(0))
                assertThat(matrix[0])
                    .`as`("drawn width must be the physical width")
                    .isCloseTo(426.24f, within(TOLERANCE_POINTS))
                assertThat(matrix[3])
                    .`as`("drawn height must be the physical height")
                    .isCloseTo(664.56f, within(TOLERANCE_POINTS))
                assertThat(matrix[4])
                    .`as`("off draws from the origin: no centering offset on x")
                    .isCloseTo(0f, within(TOLERANCE_POINTS))
                assertThat(matrix[5])
                    .`as`("off draws from the origin: no centering offset on y")
                    .isCloseTo(0f, within(TOLERANCE_POINTS))
            }
        }
    }

    @Nested
    inner class FixedCentersUnscaledContent {
        @Test
        fun `SV-09 a fixed target sets the MediaBox to the target size exactly`(
            @TempDir dir: Path,
        ) {
            val target = dir.resolve("out.pdf")
            val fixed = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(page(ENVELOPE, dir, "e")), target)

            Loader.loadPDF(target.toFile()).use { document ->
                val box = document.getPage(0).mediaBox
                assertThat(box.width)
                    .`as`("the box must be the target as written: 210 mm in points")
                    .isCloseTo(fixed.widthPt, within(TOLERANCE_POINTS))
                assertThat(box.height)
                    .`as`("the box must be the target as written: 297 mm in points")
                    .isCloseTo(fixed.heightPt, within(TOLERANCE_POINTS))
                assertThat(box.width)
                    .`as`("cross-check: the target itself must be A4, 210 mm = 595.28 pt")
                    .isCloseTo(210f * 72f / 25.4f, within(TOLERANCE_POINTS))
                assertThat(box.height)
                    .`as`("cross-check: the target itself must be A4, 297 mm = 841.89 pt")
                    .isCloseTo(297f * 72f / 25.4f, within(TOLERANCE_POINTS))
            }
        }

        @Test
        fun `SV-09 smaller-than-box content is centered with a positive offset and not enlarged`(
            @TempDir dir: Path,
        ) {
            // Envelope at 300 dpi is 426.24 x 664.56 pt: smaller than A4 on both axes.
            val target = dir.resolve("out.pdf")
            val fixed = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(page(ENVELOPE, dir, "e")), target)

            Loader.loadPDF(target.toFile()).use { document ->
                val matrix = imageMatrix(document.getPage(0))
                assertThat(matrix[0])
                    .`as`("the content must keep its physical width, not be enlarged to fill the box")
                    .isCloseTo(426.24f, within(TOLERANCE_POINTS))
                assertThat(matrix[3])
                    .`as`("the content must keep its physical height, not be enlarged to fill the box")
                    .isCloseTo(664.56f, within(TOLERANCE_POINTS))
                assertThat(matrix[4])
                    .`as`("the x offset must centre the content: (box - content) / 2")
                    .isCloseTo((fixed.widthPt - 426.24f) / 2f, within(TOLERANCE_POINTS))
                assertThat(matrix[5])
                    .`as`("the y offset must centre the content: (box - content) / 2")
                    .isCloseTo((fixed.heightPt - 664.56f) / 2f, within(TOLERANCE_POINTS))
                assertThat(matrix[4].toDouble())
                    .`as`("a smaller content leaves a white margin: the x offset is positive")
                    .isGreaterThan(0.0)
                assertThat(matrix[5].toDouble())
                    .`as`("a smaller content leaves a white margin: the y offset is positive")
                    .isGreaterThan(0.0)
            }
        }

        @Test
        fun `SV-09 larger-than-box content keeps its size with a negative offset and is not shrunk`(
            @TempDir dir: Path,
        ) {
            // Envelope at 300 dpi is 426.24 x 664.56 pt: larger than A6 (297.64 x 419.53 pt) on both axes.
            val target = dir.resolve("out.pdf")
            val fixed = TargetPageSize.parse("a6") as TargetPageSize.Fixed

            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(page(ENVELOPE, dir, "e")), target)

            Loader.loadPDF(target.toFile()).use { document ->
                val box = document.getPage(0).mediaBox
                assertThat(box.width)
                    .`as`("the box stays the target even when the content overflows it")
                    .isCloseTo(fixed.widthPt, within(TOLERANCE_POINTS))
                val matrix = imageMatrix(document.getPage(0))
                assertThat(matrix[0])
                    .`as`("overflowing content must keep its physical width, not be shrunk to fit")
                    .isCloseTo(426.24f, within(TOLERANCE_POINTS))
                assertThat(matrix[3])
                    .`as`("overflowing content must keep its physical height, not be shrunk to fit")
                    .isCloseTo(664.56f, within(TOLERANCE_POINTS))
                assertThat(matrix[4].toDouble())
                    .`as`("overflowing content sticks out past the edge: the x offset is negative")
                    .isLessThan(0.0)
                assertThat(matrix[5].toDouble())
                    .`as`("overflowing content sticks out past the edge: the y offset is negative")
                    .isLessThan(0.0)
                assertThat(matrix[4])
                    .`as`("the negative x offset still centres: (box - content) / 2")
                    .isCloseTo((fixed.widthPt - 426.24f) / 2f, within(TOLERANCE_POINTS))
                assertThat(matrix[5])
                    .`as`("the negative y offset still centres: (box - content) / 2")
                    .isCloseTo((fixed.heightPt - 664.56f) / 2f, within(TOLERANCE_POINTS))
            }
        }
    }

    @Nested
    inner class NoOrientationAutomation {
        @Test
        fun `SV-09 a landscape scan on a4 stays upright on portrait A4 with white margins`(
            @TempDir dir: Path,
        ) {
            // 600 x 200 px at 300 dpi: 144 x 48 pt of landscape content.
            val file = landscapeJpeg(dir, "landscape.jpg", 600, 200)
            val target = dir.resolve("out.pdf")
            val fixed = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(PdfPage(file, 300)), target)

            Loader.loadPDF(target.toFile()).use { document ->
                val box = document.getPage(0).mediaBox
                assertThat(box.width.toDouble())
                    .`as`("a4 is portrait: the box must be narrower than tall, never rotated to fit the scan")
                    .isLessThan(box.height.toDouble())
                val matrix = imageMatrix(document.getPage(0))
                assertThat(matrix[0])
                    .`as`("the landscape width must be drawn as-is, not fitted to the narrow box")
                    .isCloseTo(144f, within(TOLERANCE_POINTS))
                assertThat(matrix[3])
                    .`as`("the landscape height must be drawn as-is, not fitted to the tall box")
                    .isCloseTo(48f, within(TOLERANCE_POINTS))
                assertThat(matrix[1])
                    .`as`("no rotation: the shear term b of the cm matrix is zero")
                    .isCloseTo(0f, within(TOLERANCE_POINTS))
                assertThat(matrix[2])
                    .`as`("no rotation: the shear term c of the cm matrix is zero")
                    .isCloseTo(0f, within(TOLERANCE_POINTS))
                assertThat(matrix[4].toDouble())
                    .`as`("white margin left and right: the x offset is positive")
                    .isGreaterThan(0.0)
                assertThat(matrix[5].toDouble())
                    .`as`("white margin above and below: the y offset is positive")
                    .isGreaterThan(0.0)
            }
        }

        @Test
        fun `SV-09 the same landscape scan on a6-landscape lands on a landscape box`(
            @TempDir dir: Path,
        ) {
            val file = landscapeJpeg(dir, "landscape.jpg", 600, 200)
            val target = dir.resolve("out.pdf")
            val fixed = TargetPageSize.parse("a6-landscape") as TargetPageSize.Fixed

            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(PdfPage(file, 300)), target)

            Loader.loadPDF(target.toFile()).use { document ->
                val box = document.getPage(0).mediaBox
                assertThat(box.width.toDouble())
                    .`as`("a6-landscape is landscape: the box must be wider than tall")
                    .isGreaterThan(box.height.toDouble())
                val matrix = imageMatrix(document.getPage(0))
                assertThat(matrix[0])
                    .`as`("the drawn width is the physical width on a landscape box too")
                    .isCloseTo(144f, within(TOLERANCE_POINTS))
                assertThat(matrix[3])
                    .`as`("the drawn height is the physical height on a landscape box too")
                    .isCloseTo(48f, within(TOLERANCE_POINTS))
                assertThat(matrix[4])
                    .`as`("the x offset centres into the landscape box")
                    .isCloseTo((fixed.widthPt - 144f) / 2f, within(TOLERANCE_POINTS))
                assertThat(matrix[5])
                    .`as`("the y offset centres into the landscape box")
                    .isCloseTo((fixed.heightPt - 48f) / 2f, within(TOLERANCE_POINTS))
            }
        }
    }

    @Nested
    inner class EmbeddedBytesUnchanged {
        @Test
        fun `SV-09 the embedded JPEG stream is byte-identical to the input`(
            @TempDir dir: Path,
        ) {
            val page = page(ENVELOPE, dir, "e")
            val target = dir.resolve("out.pdf")
            val fixed = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(page), target)

            Loader.loadPDF(target.toFile()).use { document ->
                assertThat(embeddedRawBytes(document, 0))
                    .`as`("the fixed box must not touch the image bytes: no recompression, not one byte")
                    .isEqualTo(Files.readAllBytes(page.file))
            }
        }

        @Test
        fun `SV-09 the embedded JBIG2 page stream is byte-identical to the encoder output`(
            @TempDir dir: Path,
        ) {
            // 96 x 48 px: non-square on purpose so swapped axes could not hide.
            val source = writePbm(dir, "page.pbm", 96, 48, blockRows(96, 48, 0))
            val jbig2 = Jbig2Enc.encode(listOf(source), dir)
            val target = dir.resolve("out.pdf")
            val fixed = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(PdfPage(source, 300)), jbig2, target)

            Loader.loadPDF(target.toFile()).use { document ->
                val box = document.getPage(0).mediaBox
                assertThat(box.width)
                    .`as`("the JBIG2 path takes the same fixed box")
                    .isCloseTo(fixed.widthPt, within(TOLERANCE_POINTS))
                assertThat(embeddedRawBytes(document, 0))
                    .`as`("the JBIG2 path must not touch the encoded bytes either")
                    .isEqualTo(jbig2.pages[0])
                val matrix = imageMatrix(document.getPage(0))
                assertThat(matrix[0])
                    .`as`("JBIG2 content keeps its physical width: 96 px / 300 dpi * 72 = 23.04 pt")
                    .isCloseTo(96f / 300f * 72f, within(TOLERANCE_POINTS))
                assertThat(matrix[3])
                    .`as`("JBIG2 content keeps its physical height: 48 px / 300 dpi * 72 = 11.52 pt")
                    .isCloseTo(48f / 300f * 72f, within(TOLERANCE_POINTS))
                assertThat(matrix[4])
                    .`as`("JBIG2 content is centred like JPEG content")
                    .isCloseTo((fixed.widthPt - 96f / 300f * 72f) / 2f, within(TOLERANCE_POINTS))
            }
        }
    }

    /**
     * Extracts the raw, still-encoded image stream of the image on [pageIndex].
     *
     * [COSStream.createRawInputStream] is the deliberate choice here: it yields
     * the bytes exactly as they sit in the PDF, *without* running the
     * DCTDecode/JBIG2Decode filter. The alternatives would both make the
     * byte-identity tests meaningless -- `toByteArray()` and
     * `createInputStream()` decode the stream, so the comparison would be
     * against pixel data rather than against the original file, and a
     * recompressing builder could still pass.
     */
    private fun embeddedRawBytes(
        document: PDDocument,
        pageIndex: Int,
    ): ByteArray {
        val resources = document.getPage(pageIndex).resources
        val name =
            resources.xObjectNames.first { resources.getXObject(it) is PDImageXObject }
        val image = resources.getXObject(name) as PDImageXObject
        return image.cosObject.createRawInputStream().use { it.readBytes() }
    }

    /**
     * Reads the single `cm` matrix placing the image on [page]: `a b c d e f`,
     * where `a`/`d` are the drawn width/height in points, `e`/`f` the offset of
     * the image origin, and `b`/`c` the shear (zero when upright).
     *
     * The content stream is parsed as text: with one image per page there is
     * exactly one `cm`, and asserting that pins that the builder draws the page
     * with a single unscaled placement rather than extra transforms.
     */
    private fun imageMatrix(page: PDPage): FloatArray {
        val bytes = contentBytes(page)
        val text = bytes.toString(Charsets.US_ASCII)
        val matches =
            Regex("""([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+cm""")
                .findAll(text)
                .toList()
        assertThat(matches)
            .`as`("one image per page is drawn with exactly one cm placement, no extra transforms")
            .hasSize(1)
        return FloatArray(6) { index -> matches[0].groupValues[index + 1].toFloat() }
    }

    /** Concatenates the decoded content stream(s) of [page].
     *
     * Decoding is correct here, unlike for the image XObjects above: the
     * content stream is ASCII text that OpenPDF Flate-compresses, so the raw
     * bytes are not parseable. Decoding restores the operators without touching
     * the image bytes they place. */
    private fun contentBytes(page: PDPage): ByteArray {
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
        fun decoded(stream: COSStream): ByteArray = stream.createInputStream().use { it.readBytes() }
        return when (contents) {
            is COSStream -> decoded(contents)
            is COSArray -> {
                var out = byteArrayOf()
                for (i in 0 until contents.size()) {
                    val obj = contents.getObject(i)
                    val stream = if (obj is COSObject) obj.`object` as COSStream else obj as COSStream
                    out += decoded(stream)
                }
                out
            }
            else -> throw AssertionError("expected a content stream for the page, found $contents")
        }
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

    /**
     * Writes a landscape JPEG fixture-maker locally (offline, DC-03): both
     * committed fixtures are portrait, and the no-rotation pin needs landscape
     * content. The black corner block gives the encoder something to see; the
     * dimensions are what the assertions rest on.
     */
    private fun landscapeJpeg(
        dir: Path,
        name: String,
        width: Int,
        height: Int,
    ): Path {
        require(width > height) { "the orientation pin needs landscape content: $width x $height is not landscape" }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width / 2, height / 2)
        graphics.dispose()
        val target = dir.resolve(name)
        check(ImageIO.write(image, "JPEG", target.toFile())) { "no JPEG writer: cannot synthesise $name" }
        return target
    }

    /**
     * Writes a binary PBM (`P4`) file: the header `P4\n<w> <h>\n` followed by
     * the packed row bytes, MSB first, set bit = black.
     */
    private fun writePbm(
        dir: Path,
        name: String,
        width: Int,
        height: Int,
        rows: List<ByteArray>,
    ): Path {
        require(rows.size == height) { "need one row per height unit" }
        val target = dir.resolve(name)
        val header = "P4\n$width $height\n".toByteArray(Charsets.US_ASCII)
        val body = rows.fold(byteArrayOf()) { acc, row -> acc + row }
        Files.write(target, header + body)
        return target
    }

    /**
     * Builds [height] rows of packed bytes with a repeated block pattern;
     * [shift] offsets the pattern so pages look similar but not identical.
     */
    private fun blockRows(
        width: Int,
        height: Int,
        shift: Int,
    ): List<ByteArray> {
        val rowBytes = (width + 7) / 8
        return (0 until height).map { y ->
            ByteArray(rowBytes) { x ->
                (((x + y + shift) % 4) * 0x55).toByte()
            }
        }
    }

    private companion object {
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"

        /**
         * Offsets are rounded to 1/100 pt by the writer, so the placement pins
         * need the 0.01 pt tolerance -- deliberately tighter than the 1 pt
         * slack the SV-05 size tests allow.
         */
        const val TOLERANCE_POINTS = 0.01f

        val FIXED_INSTANT: Instant = Instant.parse("2026-09-27T10:15:30Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }
}
