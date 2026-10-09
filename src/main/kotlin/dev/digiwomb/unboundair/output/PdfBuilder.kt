package dev.digiwomb.unboundair.output

import dev.digiwomb.unboundair.image.BitmapInfo
import dev.digiwomb.unboundair.image.Jbig2Enc
import org.openpdf.text.Document
import org.openpdf.text.Image
import org.openpdf.text.ImgJBIG2
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.PdfDate
import org.openpdf.text.pdf.PdfEncryption
import org.openpdf.text.pdf.PdfName
import org.openpdf.text.pdf.PdfString
import org.openpdf.text.pdf.PdfWriter
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.GregorianCalendar

/**
 * One page of a document: the processed page file and the resolution it was
 * scanned at.
 *
 * The file is a JPEG for the color and grayscale modes, and a binary PBM (`P4`)
 * for the `bw` mode (SV-08); [PdfBuilder] detects which by content, not by
 * extension.
 *
 * The resolution travels with the file rather than being passed once for the
 * whole document, because it is a property of the individual scan (SC-08) and
 * determines that page's size (SV-05). Pages of differing resolution in one
 * document therefore come out at their correct individual sizes.
 *
 * @property file the processed page file, embedded unchanged.
 * @property dpi the resolution the page was actually scanned at.
 */
data class PdfPage(
    val file: Path,
    val dpi: Int,
)

/**
 * Assembles processed pages into a multi-page PDF (AU-01, SV-05).
 *
 * Two guardrails from `docs/internal/plan.md` are realised here, and both are the reason
 * this class is deliberately small:
 *
 * - **Never recompress.** The JPEGs go in exactly as they arrive, through
 *   [Image.getInstance], which stores the compressed stream as-is (`/DCTDecode`)
 *   rather than decoding and re-encoding it. Every pixel in the finished PDF is
 *   the one the scanner produced. Anything going through `java.awt.Image` or
 *   `BufferedImage` would decode the image here and break the promise the whole
 *   pipeline is built on.
 * - **Page size is pixels divided by dpi (SV-05).** No rounding to A4 or any
 *   other standard format. A page measures `pixels / dpi * 72` points, which is
 *   what a viewer and a printer need to reproduce the original dimensions.
 *   OF-05 notes that the scanner's physical sizes do not match the paper
 *   exactly; that is a device question and is deliberately not compensated for
 *   here.
 *
 * **The clock is injected** so the golden-master tests can pin it
 * (`docs/internal/entscheidungen.md`, "PDF-Metadaten-Determinismus"). `CreationDate`
 * otherwise varies per run and no two PDFs of the same input would be
 * byte-identical. In production the clock is the real one and the timestamps
 * are genuine.
 *
 * @property clock source of the document timestamp; pinned in tests.
 * @property targetPageSize the page box to use; [TargetPageSize.Off] keeps
 *   the scan-sized box (SV-05), [TargetPageSize.Fixed] centres the unscaled
 *   content into the configured box (SV-09).
 */
class PdfBuilder(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val targetPageSize: TargetPageSize = TargetPageSize.Off,
) {
    /**
     * Writes [pages] as a multi-page PDF to [target].
     *
     * @param pages the processed pages, in document order. Must not be empty: a
     *   PDF without pages is not a document, and an empty batch should never
     *   have reached this far.
     * @param target the file to write; parent directories are created.
     * @throws IllegalArgumentException [pages] is empty.
     */
    fun build(
        pages: List<PdfPage>,
        target: Path,
    ) {
        require(pages.isNotEmpty()) { "a PDF needs at least one page" }
        target.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(target).use { out -> writeTo(pages, out) }
    }

    /**
     * Writes [pages] as a multi-page PDF to [out].
     *
     * The stream-based form exists so a caller can assemble a document without a
     * temporary file -- the outbox (AU-04) will want exactly that. [out] is not
     * closed here; the caller owns it.
     *
     * @throws IllegalArgumentException [pages] is empty.
     */
    fun writeTo(
        pages: List<PdfPage>,
        out: OutputStream,
    ) {
        require(pages.isNotEmpty()) { "a PDF needs at least one page" }
        val (document, writer) = openDocument(out)
        document.open()
        applyMetadata(writer)
        pages.forEach { page -> renderPage(document, page) }
        document.close()
    }

    /**
     * Writes [pages] with the JBIG2 encoding [jbig2] as a multi-page PDF to
     * [target] (SV-08).
     *
     * The explicit sibling of [build]: the batch encodes the whole document
     * once with `jbig2` and calls this, rather than the builder running the
     * encoder itself. Every page file must be a binary PBM (`P4`); a JPEG here
     * is a mixed document and fails.
     *
     * @param pages the processed pages, in document order. Must not be empty.
     * @param jbig2 the shared globals plus one page stream per page, in
     *   document order; its page count must match [pages].
     * @param target the file to write; parent directories are created.
     * @throws IllegalArgumentException [pages] is empty, the page counts
     *   differ, or a page file is not a PBM.
     */
    fun build(
        pages: List<PdfPage>,
        jbig2: Jbig2Enc.Jbig2Output,
        target: Path,
    ) {
        require(pages.isNotEmpty()) { "a PDF needs at least one page" }
        target.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(target).use { out -> writeTo(pages, jbig2, out) }
    }

    /**
     * Writes [pages] with the JBIG2 encoding [jbig2] as a multi-page PDF to
     * [out] (SV-08).
     *
     * [out] is not closed here; the caller owns it.
     *
     * @param pages the processed pages, in document order. Must not be empty.
     * @param jbig2 the shared globals plus one page stream per page, in
     *   document order; its page count must match [pages].
     * @throws IllegalArgumentException [pages] is empty, the page counts
     *   differ, or a page file is not a PBM.
     */
    fun writeTo(
        pages: List<PdfPage>,
        jbig2: Jbig2Enc.Jbig2Output,
        out: OutputStream,
    ) {
        require(pages.isNotEmpty()) { "a PDF needs at least one page" }
        require(jbig2.pages.size == pages.size) {
            "jbig2 encoded ${jbig2.pages.size} page(s) for ${pages.size} page(s): every page needs its stream"
        }
        val (document, writer) = openDocument(out)
        document.open()
        applyMetadata(writer)
        // The same globals instance is passed for every page on purpose:
        // PdfWriter deduplicates identical globals into a single shared
        // /JBIG2Globals stream by content equality, which is the saving SV-08
        // rests on. Per-page copies would still be correct PDF but would defeat
        // the saving entirely, silently.
        pages.forEachIndexed { index, page -> renderJbig2Page(document, page, jbig2.pages[index], jbig2.globals) }
        document.close()
    }

    /**
     * Opens a document on [out] without closing the stream when done.
     *
     * Shared by both entry points so the metadata and /ID pinning cannot drift
     * apart between the JPEG and the JBIG2 branch.
     *
     * @return the document and its writer; the caller still opens the document,
     *   applies the metadata and closes it.
     */
    private fun openDocument(out: OutputStream): Pair<Document, PdfWriter> {
        val document = Document()
        val writer = PdfWriter.getInstance(document, out)
        // The caller owns the stream: without this, document.close() would
        // close [out] as a side effect.
        writer.setCloseStream(false)
        return document to writer
    }

    /**
     * Draws one page: a page box of the image's physical size with the JPEG
     * across the whole of it.
     *
     * The size must be set before starting the page: OpenPDF applies a page
     * size to the *next* page, so setting it after `newPage()` would size the
     * following page instead -- the first page would come out right and every
     * other page wrong.
     */
    private fun renderPage(
        document: Document,
        page: PdfPage,
    ) {
        val format = pageFormat(page.file)
        require(format == PageFormat.JPEG) {
            "Cannot embed ${page.file} as a JPEG page: it is a ${format.name} file (mixed documents are rejected)"
        }
        // getInstance stores the compressed JPEG stream unchanged. This is the
        // "never recompress" guardrail; anything via java.awt.Image or
        // BufferedImage would decode and re-encode and must not be used here.
        val image = Image.getInstance(Files.readAllBytes(page.file))
        placeImage(document, image, image.plainWidth, image.plainHeight, page.dpi)
    }

    /**
     * Draws one JBIG2 page: a page box of the bitmap's physical size with the
     * encoded page stream across the whole of it (SV-08).
     *
     * The pixel dimensions come from the PBM header via [BitmapInfo], not from
     * the encoded stream: a JBIG2 page stream carries no dimensions of its own,
     * which is why [ImgJBIG2] takes the width and height explicitly.
     *
     * The size must be set before starting the page, for the same reason as in
     * [renderPage]: OpenPDF applies a page size to the *next* page.
     */
    private fun renderJbig2Page(
        document: Document,
        page: PdfPage,
        encoded: ByteArray,
        globals: ByteArray,
    ) {
        val format = pageFormat(page.file)
        require(format == PageFormat.PBM) {
            "Cannot embed ${page.file} as a JBIG2 page: it is a ${format.name} file (mixed documents are rejected)"
        }
        val info = BitmapInfo.read(page.file)
        val image = ImgJBIG2(info.width, info.height, encoded, globals)
        placeImage(document, image, info.width.toFloat(), info.height.toFloat(), page.dpi)
    }

    /**
     * Places one image on its page at its physical size, without scaling (SV-09).
     *
     * The content size is always `pixels / dpi * 72` points (SV-05). With
     * [TargetPageSize.Off] the page box is that size and the image sits at
     * `(0, 0)`, exactly as before. With [TargetPageSize.Fixed] the page box is
     * the configured target taken exactly as configured -- width and height
     * are never swapped and no orientation is inspected, so a landscape scan
     * on `a4` stays upright with white margins -- and the image is centred
     * into it via the offset `((box - content) / 2)` on each axis, which may be
     * negative when the content overflows the box.
     *
     * The image is drawn with `scaleAbsolute(widthPt, heightPt)` rather than
     * `scaleToFit`: `plainWidth` defaults to the raw pixel count (`1px = 1pt`;
     * the JFIF dpi is parsed but never applied, and the cm matrix derives from
     * `plainWidth`/`plainHeight`), so omitting the scale would draw about 4.17x
     * too large at 300 dpi, while `scaleToFit` would shrink the content to fit
     * -- the opposite of unscaled. There is no explicit clipping path:
     * content outside the MediaBox is clipped by the PDF specification (no
     * `/CropBox` is written, so it defaults to the MediaBox per ISO 32000-1
     * section 14.11.2), and a clip path would only change bytes for no gain.
     * Overflow pixels therefore remain in the file -- invisible, not deleted.
     * Offsets are rounded to 1/100pt by `ByteBuffer.formatDouble`, and no
     * white rectangle is painted: the unpainted page is already white.
     *
     * The size must be set before starting the page: OpenPDF applies a page
     * size to the *next* page, so setting it after `newPage()` would size the
     * following page instead.
     *
     * @param pixelWidth the image width in pixels (from the SOF segment for
     *   JPEG, from the PBM header for JBIG2, which carries no dimensions).
     * @param pixelHeight the image height in pixels.
     */
    private fun placeImage(
        document: Document,
        image: Image,
        pixelWidth: Float,
        pixelHeight: Float,
        dpi: Int,
    ) {
        val width = pointsFor(pixelWidth, dpi)
        val height = pointsFor(pixelHeight, dpi)
        val fixed = targetPageSize as? TargetPageSize.Fixed
        if (fixed == null) {
            document.setPageSize(Rectangle(width, height))
            document.newPage()
            image.setAbsolutePosition(0f, 0f)
        } else {
            document.setPageSize(Rectangle(fixed.widthPt, fixed.heightPt))
            document.newPage()
            image.setAbsolutePosition((fixed.widthPt - width) / 2f, (fixed.heightPt - height) / 2f)
        }
        image.scaleAbsolute(width, height)
        document.add(image)
    }

    /**
     * Detects the page format from the file content (SV-08).
     *
     * Detection is by magic bytes, not by extension: the extension is a naming
     * convention of the batch step, and a second place that has to agree with
     * that naming is one place too many.
     *
     * @param file the page file to inspect.
     * @return [PageFormat.JPEG] for `FF D8` (the SOI marker), [PageFormat.PBM]
     *   for `P4` (binary PBM).
     * @throws IllegalArgumentException the file cannot be read or starts with
     *   anything else; the message names [file] and the bytes found.
     */
    private fun pageFormat(file: Path): PageFormat {
        val firstTwo =
            try {
                Files.newInputStream(file).use { input -> input.readNBytes(MAGIC_BYTES) }
            } catch (e: IOException) {
                throw IllegalArgumentException("Cannot detect the format of $file: ${e.message}", e)
            }
        if (firstTwo.size >= MAGIC_BYTES &&
            firstTwo[0] == JPEG_SOI_FIRST &&
            firstTwo[1] == JPEG_SOI_SECOND
        ) {
            return PageFormat.JPEG
        }
        if (firstTwo.size >= MAGIC_BYTES && firstTwo[0] == PBM_MAGIC_FIRST && firstTwo[1] == PBM_MAGIC_SECOND) {
            return PageFormat.PBM
        }
        throw IllegalArgumentException(
            "Cannot detect the format of $file: " +
                "expected FF D8 (JPEG) or P4 (PBM), found ${describeBytes(firstTwo)}",
        )
    }

    /**
     * Describes the first bytes of [bytes] for the unknown-format error: up to
     * two bytes as hex, or an explicit note when the file is empty.
     */
    private fun describeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return "an empty file"
        }
        return bytes.take(MAGIC_BYTES).joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
    }

    /**
     * Converts a pixel count at [dpi] into PostScript points (SV-05).
     *
     * A point is 1/72 inch, so `pixels / dpi` inches is `pixels / dpi * 72`
     * points. No rounding to a standard format: the page is as large as the scan
     * says it is.
     */
    private fun pointsFor(
        pixels: Float,
        dpi: Int,
    ): Float = pixels / dpi.toFloat() * POINTS_PER_INCH

    /**
     * Pins the document metadata from the injected clock.
     *
     * `Document.open()` stamps the info dictionary with the library version as
     * producer and the current time as creation date, so both are overwritten
     * here. `CreationDate` is the scan time -- for a batch, the start of its
     * first page -- and comes from the clock so tests can pin it and compare
     * PDFs byte for byte. `ModDate` is set to the same instant: a field that
     * sometimes appears is exactly what breaks a byte comparison.
     *
     * The trailer /ID is the one remaining source of run-to-run variation: the
     * trailer writer reuses the info dictionary's /FileID entry when present
     * and otherwise generates 16 random bytes, so two saves of the same
     * document would differ. Deriving it from the clock instead makes the
     * output reproducible for a pinned clock, while production still gets a
     * per-document value because the clock moves. The clock alone is not
     * enough -- without the /FileID entry the random fallback applies no
     * matter how the dates are pinned.
     *
     * The producer string is fixed rather than carrying the application version,
     * for the same reason: a version number in the metadata would make every
     * release change every golden file, with no gain.
     */
    private fun applyMetadata(writer: PdfWriter) {
        val created = GregorianCalendar.from(clock.instant().atZone(clock.zone))
        writer.info.apply {
            put(PdfName.PRODUCER, PdfString(PRODUCER))
            put(PdfName.CREATIONDATE, PdfDate(created))
            put(PdfName.MODDATE, PdfDate(created))
            put(PdfName.FILEID, PdfEncryption.createInfoId(documentId(), documentId()))
        }
    }

    /**
     * Derives the 16 document-id bytes from the injected clock.
     *
     * The first 8 bytes are zero; the last 8 are the clock millis in big-endian
     * order. Deterministic for a pinned clock, per-document in production
     * because the clock moves.
     */
    private fun documentId(): ByteArray =
        ByteBuffer
            .allocate(DOCUMENT_ID_BYTES)
            .putLong(0L)
            .putLong(clock.millis())
            .array()

    /**
     * The page formats the builder accepts, detected by content (SV-08).
     */
    private enum class PageFormat {
        JPEG,
        PBM,
    }

    private companion object {
        /** A PostScript point is 1/72 inch. */
        const val POINTS_PER_INCH = 72f

        /** The trailer /ID and /FileID are 16 bytes. */
        const val DOCUMENT_ID_BYTES = 16

        /** The format detection reads the first two bytes (the magic). */
        const val MAGIC_BYTES = 2

        /** The first two bytes of a JPEG: the SOI marker FF D8. */
        val JPEG_SOI_FIRST: Byte = 0xFF.toByte()

        /** The second byte of the JPEG SOI marker. */
        val JPEG_SOI_SECOND: Byte = 0xD8.toByte()

        /** The first byte of the binary PBM magic `P4`. */
        val PBM_MAGIC_FIRST: Byte = 0x50.toByte()

        /** The second byte of the binary PBM magic `P4`. */
        val PBM_MAGIC_SECOND: Byte = 0x34.toByte()

        /**
         * Deliberately without a version number, so a release does not change
         * every byte of every document.
         */
        const val PRODUCER = "UnboundAir"
    }
}
