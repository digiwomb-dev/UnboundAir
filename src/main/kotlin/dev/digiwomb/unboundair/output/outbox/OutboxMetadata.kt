package dev.digiwomb.unboundair.output.outbox

import tools.jackson.core.JacksonException
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * What the outbox persists next to each document that the PDF itself does not carry,
 * stored as `metadata.json` beside `document.pdf` (AU-04).
 *
 * [pageCount], [startedAt] and [finishedAt] are the metadata of
 * [dev.digiwomb.unboundair.output.OutputDocument] minus the PDF path: the outbox owns
 * the file, it needs only the size and the timing. The document *name* is deliberately
 * not stored: it is derived from [startedAt] via the `documentName` function at delivery
 * time, so the `scan-YYYYMMDD-HHMMSS.pdf` format has exactly one implementation that
 * cannot drift.
 *
 * [attempts] and [nextAttemptAt] are the retry state of the delivery backoff: after each
 * failed attempt the outbox increments [attempts], reschedules [nextAttemptAt] and
 * persists this object again, so a restart resumes the backoff instead of hammering the
 * module with immediate retries.
 */
data class OutboxMetadata(
    val pageCount: Int,
    val startedAt: Instant,
    val finishedAt: Instant,
    val attempts: Int,
    val nextAttemptAt: Instant,
)

/**
 * The Jackson mapper for the outbox `metadata.json` files.
 *
 * Exposed here, next to [OutboxMetadata], so the outbox and the round-trip test share
 * one configured mapper instead of each building their own.
 *
 * [KotlinModule] is registered because [OutboxMetadata] is a Kotlin data class without
 * a default constructor; without the module, Jackson cannot call the primary
 * constructor when reading. [DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS] is disabled so
 * [Instant] values round-trip as ISO-8601 strings rather than fractional epoch seconds:
 * the java.time default has differed across Jackson versions (in 2.x it was epoch
 * numbers), so the format is pinned here explicitly instead of inherited from the
 * mapper, and stays on the real serialization path the round-trip test proves.
 */
val outboxMetadataMapper: JsonMapper =
    JsonMapper
        .builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

/**
 * Writes [metadata] to [path] atomically: to a temporary file in the same directory
 * first, then renamed over the target. The rename is atomic within the directory, so a
 * crash mid-write cannot leave a half-written `metadata.json` behind.
 */
fun writeMetadata(
    path: Path,
    metadata: OutboxMetadata,
) {
    val tmp = Files.createTempFile(path.parent, "metadata", ".tmp")
    try {
        outboxMetadataMapper.writeValue(tmp, metadata)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        runCatching { Files.deleteIfExists(tmp) }
        throw e
    }
}

/**
 * Reads the `metadata.json` at [path]; returns `null` if it is corrupt or half-written.
 *
 * One broken entry must not take down the whole service: the outbox skips that entry
 * (loudly, with a log) and retries from the state it can read. This function never
 * deletes the file — a safety net that silently discards a document is not a safety
 * net.
 *
 * `null` means "unparseable", not "absent": if the file is missing or unreadable, the
 * I/O exception propagates, because that is a different failure than a corrupt entry.
 */
fun readMetadata(path: Path): OutboxMetadata? =
    try {
        outboxMetadataMapper.readValue(path, OutboxMetadata::class.java)
    } catch (e: JacksonException) {
        null
    }
