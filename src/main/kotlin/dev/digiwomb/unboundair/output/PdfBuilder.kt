package dev.digiwomb.unboundair.output

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.OutputStream
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
 *   [JPEGFactory.createFromByteArray], which stores the compressed stream as-is
 *   rather than decoding and re-encoding it. Every pixel in the finished PDF is
 *   the one the scanner produced. Anything that decodes an image here would
 *   break the promise the whole pipeline is built on.
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
        PDDocument().use { document ->
            pages.forEach { page -> document.addPage(renderPage(document, page)) }
            applyMetadata(document)
            // The trailer /ID is the one remaining source of run-to-run
            // variation: PDFBox seeds it from the current time and a random
            // number, so two saves of the same document differ in 32 bytes.
            // Deriving it from the clock instead makes the output reproducible
            // for a pinned clock, while production still gets a per-document
            // value because the clock moves.
            document.documentId = clock.millis()
            document.save(out)
        }
    }

    /**
     * Builds one PDF page: a page box of the image's physical size with the
     * JPEG drawn across the whole of it.
     */
    private fun renderPage(
        document: PDDocument,
        page: PdfPage,
    ): PDPage {
        // createFromByteArray stores the compressed JPEG stream unchanged. This
        // is the "never recompress" guardrail; createFromImage would decode and
        // re-encode and must not be used here.
        val image = JPEGFactory.createFromByteArray(document, Files.readAllBytes(page.file))
        val width = pointsFor(image.width, page.dpi)
        val height = pointsFor(image.height, page.dpi)

        val pdPage = PDPage(PDRectangle(width, height))
        PDPageContentStream(document, pdPage).use { content ->
            content.drawImage(image, 0f, 0f, width, height)
        }
        return pdPage
    }

    /**
     * Converts a pixel count at [dpi] into PostScript points (SV-05).
     *
     * A point is 1/72 inch, so `pixels / dpi` inches is `pixels / dpi * 72`
     * points. No rounding to a standard format: the page is as large as the scan
     * says it is.
     */
    private fun pointsFor(
        pixels: Int,
        dpi: Int,
    ): Float = pixels.toFloat() / dpi.toFloat() * POINTS_PER_INCH

    /**
     * Sets the document metadata from the injected clock.
     *
     * `CreationDate` is the scan time -- for a batch, the start of its first page
     * -- and comes from the clock so tests can pin it and compare PDFs byte for
     * byte. `ModificationDate` is set to the same instant: PDFBox would
     * otherwise leave it unset for some writers and fill it for others, and a
     * field that sometimes appears is exactly what breaks a byte comparison.
     *
     * The producer string is fixed rather than carrying the application version,
     * for the same reason: a version number in the metadata would make every
     * release change every golden file, with no gain.
     */
    private fun applyMetadata(document: PDDocument) {
        val created = GregorianCalendar.from(clock.instant().atZone(clock.zone))
        document.documentInformation.apply {
            producer = PRODUCER
            creationDate = created
            modificationDate = created
        }
    }

    private companion object {
        /** A PostScript point is 1/72 inch. */
        const val POINTS_PER_INCH = 72f

        /**
         * Deliberately without a version number, so a release does not change
         * every byte of every document.
         */
        const val PRODUCER = "UnboundAir"
    }
}
