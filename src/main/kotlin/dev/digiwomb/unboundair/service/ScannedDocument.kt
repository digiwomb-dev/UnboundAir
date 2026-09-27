package dev.digiwomb.unboundair.service

import java.nio.file.Path
import java.time.Instant

/**
 * A finished document on its way to the output modules (AU-02).
 *
 * This is what a closed batch hands to its sink. In milestone 3 the sink writes
 * the PDF to a directory; in milestone 4 the outbox (AU-04) takes its place,
 * persists the document, and forwards it to the configured modules. The batch
 * does not change for that: it already produces everything a module needs.
 *
 * The metadata is carried alongside the file rather than being read back out of
 * the PDF, for two reasons. The outbox has to persist it next to the document so
 * it survives a restart (AU-04), and the paperless module builds its file name
 * from [startedAt] (AU-05) -- both would otherwise have to parse a PDF to learn
 * things the service already knew.
 *
 * @property pdf the assembled multi-page PDF.
 * @property pageCount how many pages it holds. Redundant with the PDF itself and
 *   kept deliberately: a module should not have to open the document to log what
 *   it is sending.
 * @property startedAt when the **first** page of the batch began scanning. This
 *   is the document's timestamp throughout -- the PDF `CreationDate` and the
 *   `scan-YYYYMMDD-HHMMSS.pdf` file name of AU-05 both derive from it, so a
 *   document is named after when it was started, not when it happened to finish.
 * @property finishedAt when the batch was closed. Kept for diagnostics: the span
 *   to [startedAt] is how long the whole document took, which is one of the
 *   numbers `measure` reports (BE-04).
 */
data class ScannedDocument(
    val pdf: Path,
    val pageCount: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
)
