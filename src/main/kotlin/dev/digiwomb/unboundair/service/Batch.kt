package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.image.Jbig2Enc
import dev.digiwomb.unboundair.output.PdfBuilder
import dev.digiwomb.unboundair.output.PdfPage
import dev.digiwomb.unboundair.output.documentName
import java.io.IOException
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
 * On closing, the pages become a PDF and go to [sink]. JPEG pages are built
 * directly; bitmap (PBM) pages are first encoded together with `jbig2` (SV-08)
 * and then built through the JBIG2-aware builder entry point.
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
     * inspected by hand. The file name follows the content: `page-NNN.jpg` for a
     * JPEG (`FF D8`), `page-NNN.pbm` for a binary bitmap (`P4`, SV-08). Anything
     * else is not a page at all and fails here, at the earliest honest point,
     * with a message naming the bytes found.
     *
     * @param bytes the processed page: a JPEG or a binary PBM (SV-08).
     * @param dpi the resolution the page was actually scanned at (SC-08); it
     *   determines this page's size in the PDF (SV-05).
     * @throws IllegalArgumentException [bytes] is neither a JPEG nor a PBM.
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
        val file = workDir.resolve("page-%03d.%s".format(++sequence, extensionFor(bytes)))
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
     * An all-bitmap batch is encoded with `jbig2` exactly once over all its
     * pages (SV-08) and built through the JBIG2-aware builder entry point; an
     * all-JPEG batch is built exactly as before. A batch whose page files
     * disagree on their format is rejected with an [IllegalStateException]
     * naming the offending file: per-format grouping could silently reorder or
     * split the document, and a wrong document is far worse than a refused one.
     *
     * This class knows only "bitmaps are encoded together". It deliberately
     * knows nothing about `ColorMode` and never decides *why* a page is
     * monochrome; that decision lives in the processing chain.
     *
     * A throw -- a mixed batch or the encoder failing -- happens before the
     * reset below, so the pages survive the failure (DL-05's spirit: nothing is
     * lost silently) and the batch stays open for a retry or an inspection. The
     * exception propagates to the caller.
     *
     * @return the document, or `null` if there was nothing to close. Returning
     *   `null` rather than throwing keeps the caller free of an "is there
     *   anything?" check before every call.
     * @throws IllegalStateException the page files disagree on their format.
     */
    @Synchronized
    fun close(): ScannedDocument? {
        if (closed || pages.isEmpty()) return null
        val started = startedAt ?: return null
        val finished = clock.instant()

        val pdf = workDir.resolve(documentName(started, clock.zone))
        val snapshot = pages.toList()
        if (isBitmapBatch(snapshot)) {
            val files = snapshot.map { it.file }
            val jbig2 = Jbig2Enc.encode(files, workDir)
            pdfBuilder.build(snapshot, jbig2, pdf)
        } else {
            pdfBuilder.build(snapshot, pdf)
        }
        val document =
            ScannedDocument(
                pdf = pdf,
                pageCount = pages.size,
                startedAt = started,
                finishedAt = finished,
            )

        // Reset before handing over: the sink may be slow (the outbox will do
        // I/O, later a network call), and the next sheet must be able to open a
        // fresh batch rather than land in one that is on its way out. The reset
        // stays after the build on purpose, so a failure above leaves the pages
        // intact instead of destroying the work it just refused to deliver.
        pages.clear()
        startedAt = null
        lastPageAt = null
        sequence = 0

        sink(document)
        return document
    }

    /**
     * Whether [snapshot] is an all-bitmap batch (SV-08).
     *
     * Every page file is inspected by content; the first file sets the
     * expectation and any file that disagrees is the offender. An all-JPEG
     * batch returns `false` and builds exactly as before.
     *
     * @throws IllegalStateException a page file has an unknown format or
     *   disagrees with the first page's format; the message names the file.
     */
    private fun isBitmapBatch(snapshot: List<PdfPage>): Boolean {
        val first = fileFormat(snapshot.first().file)
        snapshot.drop(1).forEach { page ->
            val format = fileFormat(page.file)
            check(format == first) {
                "Cannot build a mixed-format document: ${page.file} is ${format.name} " +
                    "but ${snapshot.first().file} is ${first.name} (mixed documents are rejected)"
            }
        }
        return first == PageFormat.PBM
    }

    /**
     * The file extension for [bytes], decided from the content magic.
     *
     * The same two-byte check the [PdfBuilder] uses, duplicated here on purpose:
     * the file must be named before the builder ever sees it, so sharing the
     * check would couple the naming to the reading for no gain.
     *
     * @throws IllegalArgumentException [bytes] starts with neither `FF D8`
     *   (JPEG) nor `P4` (binary PBM); the message names the bytes found.
     */
    private fun extensionFor(bytes: ByteArray): String {
        if (bytes.size >= MAGIC_BYTES && bytes[0] == JPEG_SOI_FIRST && bytes[1] == JPEG_SOI_SECOND) {
            return "jpg"
        }
        if (bytes.size >= MAGIC_BYTES && bytes[0] == PBM_MAGIC_FIRST && bytes[1] == PBM_MAGIC_SECOND) {
            return "pbm"
        }
        throw IllegalArgumentException(
            "Cannot add the page: expected FF D8 (JPEG) or P4 (PBM), found ${describeBytes(bytes)}",
        )
    }

    /**
     * Detects a page file's format from its content (SV-08).
     *
     * Detection is by magic bytes, not by extension: the extension is the naming
     * convention of [addPage], and a second place that trusts the name instead
     * of looking would only repeat what the name claims.
     *
     * @throws IllegalStateException the file cannot be read or starts with
     *   anything else; the message names the file and the bytes found.
     */
    private fun fileFormat(file: Path): PageFormat {
        val firstTwo =
            try {
                Files.newInputStream(file).use { input -> input.readNBytes(MAGIC_BYTES) }
            } catch (e: IOException) {
                throw IllegalStateException("Cannot detect the format of $file: ${e.message}", e)
            }
        if (firstTwo.size >= MAGIC_BYTES && firstTwo[0] == JPEG_SOI_FIRST && firstTwo[1] == JPEG_SOI_SECOND) {
            return PageFormat.JPEG
        }
        if (firstTwo.size >= MAGIC_BYTES && firstTwo[0] == PBM_MAGIC_FIRST && firstTwo[1] == PBM_MAGIC_SECOND) {
            return PageFormat.PBM
        }
        throw IllegalStateException(
            "Cannot detect the format of $file: expected FF D8 (JPEG) or P4 (PBM), found ${describeBytes(firstTwo)}",
        )
    }

    /**
     * Describes the first bytes of [bytes] for the unknown-format error: up to
     * two bytes as hex, or an explicit note when there is nothing to show.
     */
    private fun describeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return "an empty file"
        }
        return bytes.take(MAGIC_BYTES).joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
    }

    /**
     * The page formats the batch accepts, detected by content (SV-08).
     */
    private enum class PageFormat {
        JPEG,
        PBM,
    }

    private companion object {
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
    }
}
