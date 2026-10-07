package dev.digiwomb.unboundair.output

import dev.digiwomb.unboundair.image.Jbig2Enc
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.cos.COSObjectKey
import org.apache.pdfbox.cos.COSStream
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
 * Unit tests for the JBIG2 branch of [PdfBuilder] (SV-08).
 *
 * Three pages go in through `build(pages, jbig2, target)` and three claims are
 * pinned here, in descending order of how much damage their failure would do:
 *
 * 1. **The three pages share one globals stream.** What SV-08 saves rests on
 *    a single shared `/JBIG2Globals` object; three separate objects with equal
 *    content would be a correct PDF that silently defeats it. The assertion
 *    therefore compares object identity (the indirect reference keys), never
 *    stream contents: equal contents with three separate objects is exactly the
 *    failure being excluded.
 * 2. **Every embedded page stream is byte-identical to the encoder output.**
 *    The builder must not touch the bytes on the way in -- no re-encoding, not
 *    one byte.
 * 3. **The page measures bitmap pixels / dpi x 72 pt (SV-05).** JBIG2 carries no
 *    resolution of its own, so a mistake here produces a correct-looking image
 *    at a wrong physical size.
 *
 * The finished PDF is read back with PDFBox, the independent verifier: a test
 * whose reader is its own writer (OpenPDF reading back what OpenPDF wrote)
 * confirms only the writer's self-consistency, which is not what anyone wanted
 * to know.
 *
 * The real `jbig2` encoder runs here, but only as a fixture-maker: it produces
 * the globals and page streams the builder is expected to embed unchanged.
 * [PdfBuilder] is the thing under test, not the encoder. The clock is pinned in
 * every test, after the project's golden convention.
 *
 * Offline (DC-03): only hand-built PBMs, one committed fixture and the external
 * `jbig2` program, no device, no network.
 */
class PdfBuilderJbig2Test {
    private val builder = PdfBuilder(FIXED_CLOCK)

    @Test
    fun `SV-08 three JBIG2 pages share one globals stream`(
        @TempDir dir: Path,
    ) {
        val sources = smallPages(dir)
        val jbig2 = Jbig2Enc.encode(sources, dir)
        val target = dir.resolve("out.pdf")

        builder.build(sources.map { PdfPage(it, 300) }, jbig2, target)

        Loader.loadPDF(target.toFile()).use { document ->
            assertThat(document.numberOfPages)
                .`as`("three input pages must yield three PDF pages")
                .isEqualTo(3)
            val streams = (0 until 3).map { imageStream(document, it) }
            streams.forEachIndexed { index, stream ->
                assertThat(filterName(stream))
                    .`as`("the image on page %d must be JBIG2-encoded, not recompressed", index + 1)
                    .isEqualTo("JBIG2Decode")
            }
            val keys = streams.map { globalsKey(it) }
            assertThat(keys[1])
                .`as`("page 2 must reference the same globals object as page 1, not a copy with equal content")
                .isEqualTo(keys[0])
            assertThat(keys[2])
                .`as`("page 3 must reference the same globals object as page 1, not a copy with equal content")
                .isEqualTo(keys[0])
        }
    }

    /**
     * The guardrail of the JBIG2 branch. If this fails, the builder is
     * re-encoding on the way in and the lossless promise is void for `bw`.
     */
    @Test
    fun `SV-08 every embedded page stream is byte-identical to the encoder output`(
        @TempDir dir: Path,
    ) {
        val sources = smallPages(dir)
        val jbig2 = Jbig2Enc.encode(sources, dir)
        val target = dir.resolve("out.pdf")

        builder.build(sources.map { PdfPage(it, 300) }, jbig2, target)

        Loader.loadPDF(target.toFile()).use { document ->
            (0 until 3).forEach { index ->
                assertThat(rawBytes(imageStream(document, index)))
                    .`as`("page %d must be embedded unchanged: the builder must not re-encode", index + 1)
                    .isEqualTo(jbig2.pages[index])
            }
        }
    }

    @Test
    fun `SV-08 the page measures bitmap pixels divided by dpi times 72 points`(
        @TempDir dir: Path,
    ) {
        // The committed bw envelope fixture is 1216 x 2494 px.
        val source = materialise(dir, "envelope.pbm")
        val jbig2 = Jbig2Enc.encode(listOf(source), dir)
        val target = dir.resolve("out.pdf")

        builder.build(listOf(PdfPage(source, 300)), jbig2, target)

        Loader.loadPDF(target.toFile()).use { document ->
            val box = document.getPage(0).mediaBox

            assertThat(box.width)
                .`as`("1216 px / 300 dpi * 72 = 291.84 pt: JBIG2 has no resolution, the size comes from the bitmap")
                .isCloseTo(1216f / 300f * 72f, within(TOLERANCE_POINTS))
            assertThat(box.height)
                .`as`("2494 px / 300 dpi * 72 = 598.56 pt")
                .isCloseTo(2494f / 300f * 72f, within(TOLERANCE_POINTS))
        }
    }

    @Test
    fun `SV-08 the same input twice with a pinned clock is byte-identical`(
        @TempDir dir: Path,
    ) {
        val sources = smallPages(dir)
        val jbig2 = Jbig2Enc.encode(sources, dir)
        val pages = sources.map { PdfPage(it, 300) }
        val first = dir.resolve("first.pdf")
        val second = dir.resolve("second.pdf")

        builder.build(pages, jbig2, first)
        builder.build(pages, jbig2, second)

        assertThat(Files.readAllBytes(first))
            .`as`("a pinned clock must make the output reproducible: the precondition for the golden file")
            .isEqualTo(Files.readAllBytes(second))
    }

    @Test
    fun `SV-08 a file that is neither JPEG nor PBM fails naming the path`(
        @TempDir dir: Path,
    ) {
        val bogus = dir.resolve("not_an_image.bin")
        Files.write(bogus, byteArrayOf(0x00, 0x01, 0x02, 0x03))
        // The format guard fires before the bytes are touched, so the content
        // of this output never matters -- only its page count has to match.
        val jbig2 = Jbig2Enc.Jbig2Output(ByteArray(0), listOf(ByteArray(0)))

        assertThatThrownBy { builder.build(listOf(PdfPage(bogus, 300)), jbig2, dir.resolve("out.pdf")) }
            .`as`("an undetectable file must fail loudly, naming the file that caused it")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(bogus.toString())
    }

    /**
     * Mixed pages are rejected, not guessed (#161): a PBM has no business in
     * the JPEG entry point, and silently embedding it as grayscale would be the
     * quiet corruption this guard exists to prevent.
     */
    @Test
    fun `SV-08 a PBM in the JPEG entry point is rejected naming the file`(
        @TempDir dir: Path,
    ) {
        val pbm = writePbm(dir, "page.pbm", PAGE_WIDTH, PAGE_HEIGHT, blockRows(PAGE_WIDTH, PAGE_HEIGHT, 0))

        assertThatThrownBy { builder.build(listOf(PdfPage(pbm, 300)), dir.resolve("out.pdf")) }
            .`as`("a PBM page must not silently enter the JPEG path")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(pbm.toString())
    }

    /**
     * The unknown-magic guard must name the bytes it found, not just the path
     * (SV-08): without the bytes the message does not distinguish an empty
     * file from a corrupt one, and the `describeBytes` truncation to the
     * first two bytes would go unasserted.
     */
    @Test
    fun `SV-08 an unknown magic names the path and the bytes found`(
        @TempDir dir: Path,
    ) {
        val bogus = dir.resolve("not_an_image.bin")
        Files.write(bogus, byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val jbig2 = Jbig2Enc.Jbig2Output(ByteArray(0), listOf(ByteArray(0)))

        assertThatThrownBy { builder.build(listOf(PdfPage(bogus, 300)), jbig2, dir.resolve("out.pdf")) }
            .`as`("an undetectable file must name the file and the bytes that defeated the detection")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(bogus.toString())
            .hasMessageContaining("00 01")
    }

    /**
     * Empty files take the `describeBytes` empty branch (SV-08): the message
     * must say the file is empty rather than printing bytes that are not
     * there.
     */
    @Test
    fun `SV-08 an empty file fails naming the path and the empty-file note`(
        @TempDir dir: Path,
    ) {
        val empty = dir.resolve("empty.bin")
        Files.write(empty, ByteArray(0))

        assertThatThrownBy { builder.build(listOf(PdfPage(empty, 300)), dir.resolve("out.pdf")) }
            .`as`("an empty file must fail loudly, naming the file and the empty-file note")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(empty.toString())
            .hasMessageContaining("an empty file")
    }

    /**
     * A single-byte file exercises the short-file branch of `pageFormat`
     * (SV-08): fewer than two magic bytes is neither JPEG nor PBM, and the
     * message must show the one byte found.
     */
    @Test
    fun `SV-08 a single-byte file fails naming the path and the byte found`(
        @TempDir dir: Path,
    ) {
        val short = dir.resolve("short.bin")
        Files.write(short, byteArrayOf(0x41))

        assertThatThrownBy { builder.build(listOf(PdfPage(short, 300)), dir.resolve("out.pdf")) }
            .`as`("a truncated magic must fail loudly, naming the file and the byte found")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(short.toString())
            .hasMessageContaining("41")
    }

    /**
     * A missing file takes the `IOException` branch of the format detection
     * (SV-08, AU-01): the message must still name the file that caused it.
     */
    @Test
    fun `SV-08, AU-01 a missing file fails naming the path`(
        @TempDir dir: Path,
    ) {
        val missing = dir.resolve("missing.jpg")

        assertThatThrownBy { builder.build(listOf(PdfPage(missing, 300)), dir.resolve("out.pdf")) }
            .`as`("an unreadable file must fail loudly, naming the file that caused it")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(missing.toString())
            .hasMessageContaining("Cannot detect the format of")
    }

    /**
     * The JPEG stream entry point rejects a PBM as well (AU-01): the
     * `build` overload is covered elsewhere, this pins the `writeTo`
     * sibling so a guard removed from one path cannot hide behind the
     * other.
     */
    @Test
    fun `AU-01 a PBM in the JPEG writeTo entry point is rejected naming the file and both formats`(
        @TempDir dir: Path,
    ) {
        val pbm = writePbm(dir, "page.pbm", PAGE_WIDTH, PAGE_HEIGHT, blockRows(PAGE_WIDTH, PAGE_HEIGHT, 0))

        assertThatThrownBy {
            builder.writeTo(
                listOf(PdfPage(pbm, 300)),
                java.io.ByteArrayOutputStream(),
            )
        }.`as`("a PBM page must not silently enter the JPEG stream path")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(pbm.toString())
            .hasMessageContaining("JPEG")
            .hasMessageContaining("PBM")
    }

    /**
     * Mixed pages are rejected, not guessed (SV-08): a JPEG has no business
     * in the JBIG2 entry point, and silently running it through `BitmapInfo`
     * would be the quiet corruption this guard exists to prevent.
     */
    @Test
    fun `SV-08 a JPEG in the JBIG2 build entry point is rejected naming the file and both formats`(
        @TempDir dir: Path,
    ) {
        val jpeg = writeJpegMagic(dir, "page.jpg")
        val jbig2 = Jbig2Enc.Jbig2Output(ByteArray(0), listOf(ByteArray(0)))

        assertThatThrownBy { builder.build(listOf(PdfPage(jpeg, 300)), jbig2, dir.resolve("out.pdf")) }
            .`as`("a JPEG page must not silently enter the JBIG2 path")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(jpeg.toString())
            .hasMessageContaining("JBIG2")
            .hasMessageContaining("JPEG")
    }

    /**
     * The JBIG2 stream entry point rejects a JPEG as well (SV-08): the
     * `build` overload is covered above, this pins the `writeTo` sibling.
     */
    @Test
    fun `SV-08 a JPEG in the JBIG2 writeTo entry point is rejected naming the file and both formats`(
        @TempDir dir: Path,
    ) {
        val jpeg = writeJpegMagic(dir, "page.jpg")
        val jbig2 = Jbig2Enc.Jbig2Output(ByteArray(0), listOf(ByteArray(0)))

        assertThatThrownBy {
            builder.writeTo(
                listOf(PdfPage(jpeg, 300)),
                jbig2,
                java.io.ByteArrayOutputStream(),
            )
        }.`as`("a JPEG page must not silently enter the JBIG2 stream path")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(jpeg.toString())
            .hasMessageContaining("JBIG2")
            .hasMessageContaining("JPEG")
    }

    /**
     * The page-count guard (SV-08): every page needs its stream, and the
     * message must name both counts so the mismatch is diagnosable without
     * counting.
     */
    @Test
    fun `SV-08 a JBIG2 page-count mismatch in writeTo names both numbers`(
        @TempDir dir: Path,
    ) {
        val pbm = writePbm(dir, "page.pbm", PAGE_WIDTH, PAGE_HEIGHT, blockRows(PAGE_WIDTH, PAGE_HEIGHT, 0))
        val jbig2 = Jbig2Enc.Jbig2Output(ByteArray(0), listOf(ByteArray(0), ByteArray(1)))

        assertThatThrownBy {
            builder.writeTo(
                listOf(PdfPage(pbm, 300)),
                jbig2,
                java.io.ByteArrayOutputStream(),
            )
        }.`as`("one page with two encoded streams must fail naming both counts")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2 page(s) for 1 page(s)")
    }

    /**
     * Writes a minimal JPEG-magic file (`FF D8` followed by two filler
     * bytes): enough for the content-based format detection to report
     * JPEG, never parsed as an image because the mixed-document guard
     * fires first.
     */
    private fun writeJpegMagic(
        dir: Path,
        name: String,
    ): Path {
        val target = dir.resolve(name)
        Files.write(target, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        return target
    }

    /**
     * Returns the image stream on [pageIndex].
     *
     * The lookup stays on the high-level [PDImageXObject]: only the filter and
     * the globals reference below drop to the COS level, where the identity
     * assertion lives.
     */
    private fun imageStream(
        document: PDDocument,
        pageIndex: Int,
    ): COSStream {
        val resources = document.getPage(pageIndex).resources
        val name = resources.xObjectNames.first { resources.getXObject(it) is PDImageXObject }
        return (resources.getXObject(name) as PDImageXObject).cosObject
    }

    /**
     * Reads the single filter name of [stream] (for example `JBIG2Decode`).
     *
     * A one-element array is accepted alongside the plain name: the PDF
     * reference allows both shapes for a single filter, and the assertion
     * should pin the filter, not the spelling OpenPDF happened to choose.
     */
    private fun filterName(stream: COSStream): String {
        val filter = stream.getDictionaryObject(COSName.FILTER)
        val single = if (filter is COSArray) filter.getObject(0) else filter
        return (single as COSName).name
    }

    /**
     * Resolves the `/JBIG2Globals` reference of [stream] to its indirect object
     * key.
     *
     * The key comparison in the test -- not the stream contents -- is the whole
     * point: three separate globals objects with equal content would compare
     * equal as bytes while tripling the dictionary the sharing was meant to
     * save. `getItem` is the deliberate choice here: unlike
     * `getDictionaryObject` it does not dereference, so the returned [COSObject]
     * still carries the reference whose identity is asserted.
     */
    private fun globalsKey(stream: COSStream): COSObjectKey {
        val parms =
            when (val base = stream.getDictionaryObject(COSName.DECODE_PARMS)) {
                is COSDictionary -> base
                is COSArray -> base.getObject(0) as COSDictionary
                else -> throw AssertionError("expected DecodeParms dictionary for a JBIG2 image, found $base")
            }
        val reference = parms.getItem(COSName.JBIG2_GLOBALS) as COSObject
        return reference.key
    }

    /**
     * Extracts the raw, still-encoded page stream of [stream].
     *
     * [COSStream.createRawInputStream] is the deliberate choice here: it yields
     * the bytes exactly as they sit in the PDF, *without* running the
     * JBIG2Decode filter. The alternatives would both make the byte-identity
     * test meaningless -- `toByteArray()` and `createInputStream()` decode the
     * stream, so the comparison would be against pixel data rather than against
     * the encoder output, and a re-encoding builder could still pass.
     */
    private fun rawBytes(stream: COSStream): ByteArray = stream.createRawInputStream().use { it.readBytes() }

    /** Builds three small PBM pages with a shared block pattern, slightly shifted per page. */
    private fun smallPages(dir: Path): List<Path> =
        listOf(
            writePbm(dir, "page_a.pbm", PAGE_WIDTH, PAGE_HEIGHT, blockRows(PAGE_WIDTH, PAGE_HEIGHT, 0)),
            writePbm(dir, "page_b.pbm", PAGE_WIDTH, PAGE_HEIGHT, blockRows(PAGE_WIDTH, PAGE_HEIGHT, 1)),
            writePbm(dir, "page_c.pbm", PAGE_WIDTH, PAGE_HEIGHT, blockRows(PAGE_WIDTH, PAGE_HEIGHT, 2)),
        )

    /**
     * Writes a binary PBM (`P4`) file: the header `P4\n<w> <h>\n` followed
     * by the packed row bytes, MSB first, set bit = black.
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
     * Copies the committed bw envelope fixture (golden/envelope_dl_300dpi_bw.pbm)
     * into [dir] under [name] and returns its path.
     */
    private fun materialise(
        dir: Path,
        name: String,
    ): Path {
        val resource =
            javaClass.getResourceAsStream(ENVELOPE_BW_PBM)
                ?: throw IllegalStateException("fixture not found on the classpath: $ENVELOPE_BW_PBM")
        val target = dir.resolve(name)
        Files.copy(resource, target)
        return target
    }

    /**
     * Builds [height] rows of packed bytes with a repeated block pattern;
     * [shift] offsets the pattern so the pages look similar but not
     * identical (shared symbols with slight variation).
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
        /** The committed bw envelope fixture used for the real-dimensions size assertion. */
        const val ENVELOPE_BW_PBM = "/golden/envelope_dl_300dpi_bw.pbm"

        /** Small synthetic pages are 64 x 64 px: enough symbols for a dictionary, fast to encode. */
        const val PAGE_WIDTH = 64
        const val PAGE_HEIGHT = 64

        /** SV-05 allows +/- 1 pt; the conversion is exact, so this is slack, not need. */
        const val TOLERANCE_POINTS = 1.0f

        val FIXED_INSTANT: Instant = Instant.parse("2026-09-27T10:15:30Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }
}
