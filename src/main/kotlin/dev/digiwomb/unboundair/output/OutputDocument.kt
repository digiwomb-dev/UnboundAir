package dev.digiwomb.unboundair.output

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * A finished document as the output side sees it (AU-02).
 *
 * The document type of the module interface: the PDF plus its metadata -- how many
 * pages it holds, and when it started and finished. These are the same four values
 * `service.ScannedDocument` carries; the batch's sink maps that type onto this one,
 * because per the layer table `output` may not see `service` (`docs/internal/plan.md`,
 * "Dokument-Typ der Modul-Schnittstelle"). A few lines of mapping is the cheaper price.
 *
 * The metadata travels beside the file rather than being parsed back out of the PDF:
 * the outbox has to persist it next to the document so it survives a restart
 * (AU-04), and the file name of a re-sent document is rebuilt from [startedAt]
 * (AU-05) -- the outbox stores the file as `document.pdf`, so the name cannot be
 * read off it.
 *
 * @property pdf the assembled multi-page PDF.
 * @property pageCount how many pages it holds. Redundant with the PDF itself and
 *   kept deliberately: a module should not have to open the document to log what
 *   it is sending.
 * @property startedAt when the **first** page of the batch began scanning. This is
 *   the document's timestamp throughout: both the PDF `CreationDate` and the
 *   `scan-YYYYMMDD-HHMMSS.pdf` file name of AU-05 derive from it.
 * @property finishedAt when the batch was closed. Kept for diagnostics: the span
 *   to [startedAt] is how long the whole document took.
 */
data class OutputDocument(
    val pdf: Path,
    val pageCount: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
)

/**
 * The file name of a document (AU-05): `scan-YYYYMMDD-HHMMSS.pdf`, built from the
 * start of the first page in the given time zone.
 *
 * Local time, not UTC, is deliberate: the name is for people, and `TZ` controls
 * the zone in the container (`docs/internal/plan.md`, "Dateiname und paperless-Felder").
 *
 * The function lives in `output` rather than in the batch because from this
 * milestone on two places build the name: the batch, for the working PDF, and the
 * paperless module, for the upload. The outbox stores the file as `document.pdf`,
 * so the upload name must be rebuilt from [startedAt] and cannot be read off the
 * file -- and two copies of one format string would drift apart invisibly. This
 * package has no clock, so the zone is a parameter: the batch passes the zone of
 * its clock.
 *
 * @param startedAt when the first page of the batch began scanning.
 * @param zone the time zone to render the name in.
 * @return the document file name.
 */
fun documentName(
    startedAt: Instant,
    zone: ZoneId,
): String {
    val local = startedAt.atZone(zone)
    return "scan-%04d%02d%02d-%02d%02d%02d.pdf".format(
        local.year,
        local.monthValue,
        local.dayOfMonth,
        local.hour,
        local.minute,
        local.second,
    )
}
