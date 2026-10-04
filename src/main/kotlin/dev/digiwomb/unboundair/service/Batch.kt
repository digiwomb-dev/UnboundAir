package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.output.PdfBuilder
import dev.digiwomb.unboundair.output.PdfPage
import dev.digiwomb.unboundair.output.documentName
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Collects scanned pages into one document and closes it when the batch is done
 * (DL-03, DL-04).
 *
 * A batch is the service's answer to "which pages belong together". It opens
 * with the first page, takes every further page that arrives in time, and closes
 * on one of two triggers:
 *
 * - **the batch timeout** ([Duration] since the end of the last page), which is
 *   the normal case: the user has stopped feeding sheets;
 * - **the scanner going offline**, which DL-04 calls out as a regular trigger
 *   rather than a failure. The device switches itself off after five minutes,
 *   and that simply means the job is over.
 *
 * On closing, the pages become a PDF and go to [sink].
 *
 * ## Why the handover is a lambda
 *
 * [sink] is a `(ScannedDocument) -> Unit`, the same pattern the project already
 * uses for `warn`. In milestone 3 it writes the PDF to a directory; in milestone
 * 4 the outbox (AU-04) is plugged in, and this class does not change. Building
 * the outbox now, before there is any module to deliver to, would only move the
 * work; inventing an interface for one implementation would be ceremony.
 *
 * ## Time
 *
 * Every timestamp comes from the injected [clock] -- never `Instant.now()`. That
 * is what lets a test pin the clock and step it deliberately instead of sleeping,
 * and it is also what makes the PDF reproducible (see `docs/entscheidungen.md`).
 *
 * ## Thread safety
 *
 * All mutating methods are `synchronized`. The service loop appends pages from
 * its polling thread while a shutdown or a timeout check may close the batch
 * from another, and a page must never be lost or appear twice in that race.
 * [closeIfDue] and [close] are idempotent: whoever gets there first closes the
 * batch, everyone after that is a no-op.
 *
 * @property clock source of all timestamps.
 * @property batchTimeout how long after the last page a batch stays open.
 * @property workDir where page files and the assembled PDF are kept.
 * @property sink receives the finished document.
 * @property pdfBuilder assembles the PDF; shares the [clock].
 */
class Batch(
    private val clock: Clock,
    private val batchTimeout: Duration,
    private val workDir: Path,
    private val sink: (ScannedDocument) -> Unit,
    private val pdfBuilder: PdfBuilder = PdfBuilder(clock),
) {
    private val pages = mutableListOf<PdfPage>()
    private var startedAt: Instant? = null
    private var lastPageAt: Instant? = null
    private var closed = false
    private var sequence = 0

    /** Whether any page is waiting to be written out. */
    val isOpen: Boolean
        @Synchronized get() = pages.isNotEmpty() && !closed

    /** How many pages the open batch currently holds. */
    val pageCount: Int
        @Synchronized get() = pages.size

    /**
     * When the first page of the open batch started, or `null` if none has.
     *
     * This is the document's timestamp (AU-05), so it is exposed rather than
     * derived later from a file date.
     */
    val startedInstant: Instant?
        @Synchronized get() = startedAt

    /**
     * Adds one scanned page to the batch, opening it if necessary (DL-03).
     *
     * The bytes are copied into [workDir] under a sequential name, so the caller
     * may reuse its buffer and the page order is visible on disk when a run is
     * inspected by hand.
     *
     * @param bytes the processed JPEG of the page.
     * @param dpi the resolution the page was actually scanned at (SC-08); it
     *   determines this page's size in the PDF (SV-05).
     */
    @Synchronized
    fun addPage(
        bytes: ByteArray,
        dpi: Int,
    ) {
        check(!closed) { "the batch is closed" }
        val now = clock.instant()
        if (pages.isEmpty()) {
            startedAt = now
        }
        Files.createDirectories(workDir)
        val file = workDir.resolve("page-%03d.jpg".format(++sequence))
        Files.write(file, bytes)
        pages.add(PdfPage(file, dpi))
        lastPageAt = now
    }

    /**
     * Closes the batch if the timeout has elapsed since the last page (DL-04).
     *
     * Called by the service loop on every tick. Does nothing while the batch is
     * empty or the window is still open, so the loop can call it unconditionally
     * without asking first.
     *
     * @return the document, if one was produced.
     */
    @Synchronized
    fun closeIfDue(): ScannedDocument? {
        val last = lastPageAt ?: return null
        if (pages.isEmpty()) return null
        val elapsed = Duration.between(last, clock.instant())
        // Not `>`: a timeout of exactly the configured length has elapsed. With
        // `>` a test that steps the clock by exactly batchTimeout would hang,
        // and the boundary would depend on clock granularity.
        if (elapsed < batchTimeout) return null
        return close()
    }

    /**
     * Closes the batch now, whatever the clock says.
     *
     * Used for the other DL-04 trigger -- the scanner has gone offline -- and on
     * shutdown, where the open batch must still be delivered (DL-07, milestone
     * 5).
     *
     * @return the document, or `null` if there was nothing to close. Returning
     *   `null` rather than throwing keeps the caller free of an "is there
     *   anything?" check before every call.
     */
    @Synchronized
    fun close(): ScannedDocument? {
        if (closed || pages.isEmpty()) return null
        val started = startedAt ?: return null
        val finished = clock.instant()

        val pdf = workDir.resolve(documentName(started, clock.zone))
        pdfBuilder.build(pages.toList(), pdf)
        val document =
            ScannedDocument(
                pdf = pdf,
                pageCount = pages.size,
                startedAt = started,
                finishedAt = finished,
            )

        // Reset before handing over: the sink may be slow (the outbox will do
        // I/O, later a network call), and the next sheet must be able to open a
        // fresh batch rather than land in one that is on its way out.
        pages.clear()
        startedAt = null
        lastPageAt = null
        sequence = 0

        sink(document)
        return document
    }
}
