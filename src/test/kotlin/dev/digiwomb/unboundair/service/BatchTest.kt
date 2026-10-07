package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.Jbig2Enc
import dev.digiwomb.unboundair.image.Jbig2EncException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for [Batch] (DL-03, DL-04).
 *
 * DL-04 names two triggers for closing a batch, and both get a test: the batch
 * timeout since the last page, and the scanner going offline. The second is a
 * regular trigger, not an error -- the device switches itself off after five
 * minutes and that just means the job is done.
 *
 * **No sleeping anywhere.** Time comes from a [MutableClock] the test advances
 * by hand. A test that slept for the real timeout would be slow, and one that
 * slept for a shortened timeout would be a race dressed up as a test.
 *
 * Offline (DC-03): committed fixtures, a fake clock, a temporary directory.
 */
class BatchTest {
    /**
     * A clock the test moves deliberately.
     *
     * [Clock.fixed] cannot express "20 seconds later", and [Clock.offset] would
     * need a new instance per step. Both would obscure what the test is
     * actually doing, which is stepping time across a threshold.
     */
    private class MutableClock(
        private var now: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    @Test
    fun `DL-03 a page opens the batch and further pages join it`(
        @TempDir dir: Path,
    ) {
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir)

        batch.addPage(jpeg(), DPI)
        assertThat(batch.isOpen).`as`("the first page opens the batch").isTrue()
        assertThat(batch.startedInstant)
            .`as`("the document timestamp is the start of the first page (AU-05)")
            .isEqualTo(START)

        clock.advance(Duration.ofSeconds(5))
        batch.addPage(jpeg(), DPI)
        clock.advance(Duration.ofSeconds(5))
        batch.addPage(jpeg(), DPI)

        assertThat(batch.pageCount)
            .`as`("pages arriving inside the window belong to the same document")
            .isEqualTo(3)
        assertThat(batch.startedInstant)
            .`as`("later pages must not move the document timestamp")
            .isEqualTo(START)
    }

    @Test
    fun `DL-04 the batch closes when the timeout elapses after the last page`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        batch.addPage(jpeg(), DPI)

        clock.advance(TIMEOUT.minusSeconds(1))
        assertThat(batch.closeIfDue())
            .`as`("one second before the window expires the batch must stay open")
            .isNull()
        assertThat(delivered).isEmpty()

        clock.advance(Duration.ofSeconds(1))
        val document = batch.closeIfDue()

        assertThat(document).`as`("the elapsed timeout must close the batch").isNotNull()
        assertThat(delivered)
            .`as`("the closed document goes to the sink exactly once")
            .hasSize(1)
        assertThat(delivered.single().pageCount).isEqualTo(2)
        assertThat(Files.exists(delivered.single().pdf))
            .`as`("the PDF must exist where the document says it is")
            .isTrue()
        assertThat(batch.isOpen)
            .`as`("after closing, the batch is empty and ready for the next document")
            .isFalse()
    }

    /**
     * The timeout counts from the **last** page, not from the first. A batch
     * fed steadily for ten minutes must not close in the middle just because it
     * started long ago.
     */
    @Test
    fun `DL-04 each new page restarts the timeout window`(
        @TempDir dir: Path,
    ) {
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir)

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT.minusSeconds(1))
        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT.minusSeconds(1))

        assertThat(batch.closeIfDue())
            .`as`("well past the first page, but only just past the second: the batch stays open")
            .isNull()
        assertThat(batch.pageCount).isEqualTo(2)
    }

    @Test
    fun `DL-04 the scanner going offline closes the batch immediately`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(Duration.ofSeconds(1))

        // The offline trigger: no timeout has elapsed, and the batch closes
        // anyway. DL-04 treats the auto-off as a regular end, not a failure.
        val document = batch.close()

        assertThat(document)
            .`as`("offline must close the batch without waiting for the timeout")
            .isNotNull()
        assertThat(delivered).hasSize(1)
        assertThat(delivered.single().pageCount).isEqualTo(1)
    }

    @Test
    fun `DL-04 closing an empty batch produces nothing`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val batch = batchOf(MutableClock(START), dir) { delivered.add(it) }

        assertThat(batch.close())
            .`as`("a scanner that goes offline without a scan must not produce an empty document")
            .isNull()
        assertThat(batch.closeIfDue())
            .`as`("nor may an empty batch ever be due")
            .isNull()
        assertThat(delivered).isEmpty()
    }

    @Test
    fun `DL-04 closing twice delivers the document only once`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)

        batch.closeIfDue()
        batch.close()
        batch.closeIfDue()

        assertThat(delivered)
            .`as`("the offline trigger and the timeout may fire together; the document must go out once")
            .hasSize(1)
    }

    /**
     * After a document is delivered the batch must be usable again: the next
     * sheet starts a new document rather than reopening the old one.
     */
    @Test
    fun `DL-03 the next page after a close starts a fresh document`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        clock.advance(Duration.ofSeconds(30))
        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        assertThat(delivered).hasSize(2)
        assertThat(delivered[1].startedAt)
            .`as`("the second document must carry its own start time, not the first one's")
            .isNotEqualTo(delivered[0].startedAt)
        assertThat(delivered.map { it.pageCount })
            .`as`("one page each: the second document must not inherit the first one's pages")
            .containsExactly(1, 1)
    }

    @Test
    fun `AU-05 the document is named after the start of its first page`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(Instant.parse("2026-09-27T14:05:09Z"))
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        assertThat(
            delivered
                .single()
                .pdf.fileName
                .toString(),
        ).`as`("AU-05 fixes the shape scan-YYYYMMDD-HHMMSS.pdf, taken from the first page")
            .isEqualTo("scan-20260927-140509.pdf")
    }

    @Test
    fun `DL-04 the document reports the span from the first page to the close`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(Duration.ofSeconds(7))
        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        val document = delivered.single()
        assertThat(document.startedAt).isEqualTo(START)
        assertThat(Duration.between(document.startedAt, document.finishedAt))
            .`as`("7 s to the second page plus the 20 s window")
            .isEqualTo(Duration.ofSeconds(7).plus(TIMEOUT))
    }

    /**
     * A later bw batch with fewer pages closes after an earlier one.
     *
     * The `jbig2` program writes `pages.sym`, `pages.0000`, ... next to its
     * output base; encoding into the shared work directory leaves those
     * behind, so a later batch with fewer pages sees stale page files and its
     * count check fails. Each close encodes in a private subdirectory instead.
     */
    @Test
    fun `SV-08 a later bw batch with fewer pages closes after an earlier one left page files behind`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val batch = batchOf(MutableClock(START), dir) { delivered.add(it) }
        batch.addPage(pbm(seed = 0), DPI)
        batch.addPage(pbm(seed = 1), DPI)
        batch.close()
        batch.addPage(pbm(seed = 2), DPI)
        val document = batch.close()
        assertThat(document)
            .`as`("the second bw batch must close even though the first one left encoder files behind")
            .isNotNull()
        assertThat(delivered).hasSize(2)
        assertThat(delivered[1].pageCount).isEqualTo(1)
        assertThat(imageFilters(delivered[1].pdf)).containsExactly("JBIG2Decode")
        assertThat(batch.isOpen).isFalse()
    }

    private fun batchOf(
        clock: Clock,
        dir: Path,
        sink: (ScannedDocument) -> Unit = {},
    ) = Batch(clock, TIMEOUT, dir, sink)

    /**
     * The bw batch goes through the JBIG2 entry point, not the JPEG one.
     *
     * Three bitmap pages are added and the batch is closed with the real
     * `jbig2` encoder (SV-08). The PDF is read back with PDFBox, the
     * independent verifier: every page must carry a `JBIG2Decode` image. A
     * mutant that drops the encoder call or routes bitmaps through the plain
     * `build(pages, target)` entry point either throws on the PBM pages or
     * produces `DCTDecode` images instead, so both shapes fail here.
     *
     * Offline (DC-03): hand-built PBM bytes plus the local `jbig2` program,
     * no device, no network.
     */
    @Test
    fun `SV-08 a bw batch is built through the JBIG2 entry point`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val batch = batchOf(MutableClock(START), dir) { delivered.add(it) }

        batch.addPage(pbm(seed = 0), DPI)
        batch.addPage(pbm(seed = 1), DPI)
        batch.addPage(pbm(seed = 2), DPI)

        val document = batch.close()

        assertThat(document)
            .`as`("three bitmap pages must close into one document")
            .isNotNull()
        assertThat(delivered.single().pageCount).isEqualTo(3)
        assertThat(Files.exists(delivered.single().pdf)).isTrue()
        assertThat(imageFilters(delivered.single().pdf))
            .`as`("every page of a bw batch must be JBIG2-encoded, not passed through the JPEG entry point")
            .containsExactly("JBIG2Decode", "JBIG2Decode", "JBIG2Decode")
        assertThat(batch.isOpen)
            .`as`("after closing, the batch is empty and ready for the next document")
            .isFalse()
    }

    /**
     * A batch whose page files disagree on their format is refused, not
     * guessed: per-format grouping could silently reorder or split the
     * document, and a wrong document is far worse than a refused one.
     *
     * The rejection happens before the reset, so both pages survive it: the
     * batch stays open for an inspection or a retry instead of destroying the
     * work it just refused to deliver (DL-05's spirit).
     */
    @Test
    fun `SV-08 a mixed-format batch is rejected naming the offending file and keeps its pages`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val batch = batchOf(MutableClock(START), dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        batch.addPage(pbm(), DPI)

        assertThatThrownBy { batch.close() }
            .`as`("a JPEG page followed by a bitmap page must not become a document")
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("mixed")
            .hasMessageContaining("page-002.pbm")
        assertThat(delivered)
            .`as`("the refused batch must deliver nothing")
            .isEmpty()
        assertThat(batch.pageCount)
            .`as`("the pages survive the rejection: the reset stays after the build on purpose")
            .isEqualTo(2)
        assertThat(batch.isOpen)
            .`as`("the batch stays open for a retry or an inspection")
            .isTrue()
    }

    /**
     * The mirror of the previous test: the offender is whichever page
     * disagrees with the first, not always the last one added.
     */
    @Test
    fun `SV-08 a JPEG after a bitmap is rejected naming the JPEG file`(
        @TempDir dir: Path,
    ) {
        val batch = batchOf(MutableClock(START), dir)

        batch.addPage(pbm(), DPI)
        batch.addPage(jpeg(), DPI)

        assertThatThrownBy { batch.close() }
            .`as`("a bitmap page followed by a JPEG page must not become a document either")
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("mixed")
            .hasMessageContaining("page-002.jpg")
        assertThat(batch.pageCount)
            .`as`("the pages survive the rejection")
            .isEqualTo(2)
        assertThat(batch.isOpen).isTrue()
    }

    /**
     * When the encoder fails, the batch keeps its pages and stays open.
     *
     * The `jbig2` program name is pointed at a binary that does not exist, so
     * the encode fails without touching the disk; the name is restored in a
     * `finally` block, after which the very same batch still closes into its
     * two-page document. That retry is the point: a failure that cleared the
     * batch would have nothing left to retry with.
     */
    @Test
    fun `SV-08 an encoder failure leaves the pages intact and the batch open`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val batch = batchOf(MutableClock(START), dir) { delivered.add(it) }

        batch.addPage(pbm(seed = 0), DPI)
        batch.addPage(pbm(seed = 1), DPI)

        val original = Jbig2Enc.program
        Jbig2Enc.program = "no-such-jbig2-binary"
        try {
            assertThatThrownBy { batch.close() }
                .`as`("a missing encoder program must fail the close loudly")
                .isInstanceOf(Jbig2EncException::class.java)
        } finally {
            Jbig2Enc.program = original
        }

        assertThat(delivered)
            .`as`("the failed close must deliver nothing")
            .isEmpty()
        assertThat(batch.pageCount)
            .`as`("the pages survive the encoder failure")
            .isEqualTo(2)
        assertThat(batch.isOpen)
            .`as`("the batch stays open for a retry")
            .isTrue()

        val document = batch.close()

        assertThat(document)
            .`as`("the retry with the encoder back in place closes the same two pages")
            .isNotNull()
        assertThat(delivered.single().pageCount).isEqualTo(2)
    }

    /**
     * The format detection reads the file, not the name: a page file whose
     * content changed after it was added fails the close naming that file,
     * and the page survives the failure like any other refused build.
     */
    @Test
    fun `SV-08 a page file with an unknown format fails naming the file and keeps its page`(
        @TempDir dir: Path,
    ) {
        val batch = batchOf(MutableClock(START), dir)

        batch.addPage(jpeg(), DPI)
        Files.write(dir.resolve("page-001.jpg"), byteArrayOf(0x00, 0x01, 0x02, 0x03))

        assertThatThrownBy { batch.close() }
            .`as`("a page file that is neither JPEG nor PBM must fail loudly")
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("page-001.jpg")
        assertThat(batch.pageCount)
            .`as`("the page survives the failed close")
            .isEqualTo(1)
        assertThat(batch.isOpen).isTrue()
    }

    /**
     * The file name follows the content: `page-NNN.jpg` for a JPEG
     * (`FF D8`), `page-NNN.pbm` for a binary bitmap (`P4`, SV-08). The
     * stored bytes are the given bytes, so the order on disk is the page
     * order.
     */
    @Test
    fun `DL-03 addPage names the file after its content`(
        @TempDir dir: Path,
    ) {
        val batch = batchOf(MutableClock(START), dir)
        val first = jpeg()
        val second = pbm()

        batch.addPage(first, DPI)
        batch.addPage(second, DPI)

        assertThat(dir.resolve("page-001.jpg"))
            .`as`("a JPEG page is stored as page-001.jpg")
            .exists()
        assertThat(Files.readAllBytes(dir.resolve("page-001.jpg")))
            .`as`("the stored JPEG bytes are the given bytes")
            .isEqualTo(first)
        assertThat(dir.resolve("page-002.pbm"))
            .`as`("a bitmap page is stored as page-002.pbm")
            .exists()
        assertThat(Files.readAllBytes(dir.resolve("page-002.pbm")))
            .`as`("the stored PBM bytes are the given bytes")
            .isEqualTo(second)
    }

    /**
     * Anything that starts with neither `FF D8` (JPEG) nor `P4` (binary
     * PBM) is not a page at all and fails here, at the earliest honest
     * point, with a message naming the bytes found. The failed add leaves
     * no page behind: a two-byte check that accepted short or empty input
     * would smuggle a corrupt file into the batch.
     */
    @Test
    fun `DL-03 addPage rejects bytes that are neither JPEG nor PBM`(
        @TempDir dir: Path,
    ) {
        val batch = batchOf(MutableClock(START), dir)

        assertThatThrownBy { batch.addPage(byteArrayOf(0x00, 0x01, 0x02, 0x03), DPI) }
            .`as`("four unknown bytes are not a page")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("FF D8")
            .hasMessageContaining("P4")
            .hasMessageContaining("00 01")
        assertThatThrownBy { batch.addPage(byteArrayOf(0xFF.toByte()), DPI) }
            .`as`("a single JPEG magic byte without its partner is not a page either")
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { batch.addPage(byteArrayOf(), DPI) }
            .`as`("empty input is not a page")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("an empty file")
        assertThat(batch.pageCount)
            .`as`("rejected adds leave no page behind")
            .isEqualTo(0)
        assertThat(batch.isOpen).isFalse()
    }

    /**
     * Reads the single image filter name of every page of [pdf] (for example
     * `JBIG2Decode` or `DCTDecode`).
     *
     * The lookup stays on the high-level [PDImageXObject]: only the filter
     * drops to the COS level, and a one-element array is accepted alongside
     * the plain name, because the PDF reference allows both shapes for a
     * single filter.
     */
    private fun imageFilters(pdf: Path): List<String> =
        Loader.loadPDF(pdf.toFile()).use { document ->
            (0 until document.numberOfPages).map { index ->
                val resources = document.getPage(index).resources
                val name = resources.xObjectNames.first { resources.getXObject(it) is PDImageXObject }
                val stream = (resources.getXObject(name) as PDImageXObject).cosObject
                val filter = stream.getDictionaryObject(COSName.FILTER)
                val single = if (filter is COSArray) filter.getObject(0) else filter
                (single as COSName).name
            }
        }

    /**
     * Builds a small binary PBM (`P4`) page: the header `P4\n<w> <h>\n`
     * followed by the packed row bytes, MSB first. [seed] shifts the block
     * pattern so pages look similar but not identical.
     */
    private fun pbm(
        seed: Int = 0,
        width: Int = 64,
        height: Int = 64,
    ): ByteArray {
        val rowBytes = (width + 7) / 8
        val body =
            (0 until height)
                .map { y -> ByteArray(rowBytes) { x -> (((x + y + seed) % 4) * 0x55).toByte() } }
                .fold(byteArrayOf()) { acc, row -> acc + row }
        return "P4\n$width $height\n".toByteArray(Charsets.US_ASCII) + body
    }

    /** A real JPEG, so the PDF assembly in `close` has something valid to embed. */
    private fun jpeg(): ByteArray = TestImages.bytes("envelope_dl_300dpi_raw.jpg")

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        const val DPI = 300
    }
}
