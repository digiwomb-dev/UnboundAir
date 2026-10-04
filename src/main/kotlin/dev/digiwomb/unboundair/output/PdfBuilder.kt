package dev.digiwomb.unboundair.output

import org.openpdf.text.Document
import org.openpdf.text.Image
import org.openpdf.text.Rectangle
import org.openpdf.text.pdf.PdfDate
import org.openpdf.text.pdf.PdfEncryption
import org.openpdf.text.pdf.PdfName
import org.openpdf.text.pdf.PdfString
import org.openpdf.text.pdf.PdfWriter
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.GregorianCalendar

/**
 * One page of a document: the processed JPEG and the resolution it was scanned
 * at.
 *
 * The resolution travels with the file rather than being passed once for the
 * whole document, because it is a property of the individual scan (SC-08) and
 * determines that page's size (SV-05). Pages of differing resolution in one
 * document therefore come out at their correct individual sizes.
 *
 * @property file the processed JPEG, embedded unchanged.
 * @property dpi the resolution the page was actually scanned at.
 */
data class PdfPage(
    val file: Path,
    val dpi: Int,
)

/**
 * Assembles processed pages into a multi-page PDF (AU-01, SV-05).
 *
 * Two guardrails from `docs/plan.md` are realised here, and both are the reason
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
 * (`docs/entscheidungen.md`, "PDF-Metadaten-Determinismus"). `CreationDate`
 * otherwise varies per run and no two PDFs of the same input would be
 * byte-identical. In production the clock is the real one and the timestamps
 * are genuine.
 *
 * @property clock source of the document timestamp; pinned in tests.
 */
class PdfBuilder(
    private val clock: Clock = Clock.systemDefaultZone(),
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
        val document = Document()
        val writer = PdfWriter.getInstance(document, out)
        // The caller owns the stream: without this, document.close() would
        // close [out] as a side effect.
        writer.setCloseStream(false)
        document.open()
        applyMetadata(writer)
        pages.forEach { page -> renderPage(document, page) }
        document.close()
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
        // getInstance stores the compressed JPEG stream unchanged. This is the
        // "never recompress" guardrail; anything via java.awt.Image or
        // BufferedImage would decode and re-encode and must not be used here.
        val image = Image.getInstance(Files.readAllBytes(page.file))
        val width = pointsFor(image.plainWidth, page.dpi)
        val height = pointsFor(image.plainHeight, page.dpi)

        document.setPageSize(Rectangle(width, height))
        document.newPage()
        image.setAbsolutePosition(0f, 0f)
        image.scaleToFit(width, height)
        document.add(image)
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

    private companion object {
        /** A PostScript point is 1/72 inch. */
        const val POINTS_PER_INCH = 72f

        /** The trailer /ID and /FileID are 16 bytes. */
        const val DOCUMENT_ID_BYTES = 16

        /**
         * Deliberately without a version number, so a release does not change
         * every byte of every document.
         */
        const val PRODUCER = "UnboundAir"
    }
}
