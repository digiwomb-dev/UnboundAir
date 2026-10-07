package dev.digiwomb.unboundair.output.outbox

import dev.digiwomb.unboundair.output.OutputDocument
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** The file name of the document inside an outbox entry directory (AU-04). */
const val PDF_FILE_NAME = "document.pdf"

/** The file name of the entry's persisted metadata, beside [PDF_FILE_NAME] (AU-04). */
const val METADATA_FILE_NAME = "metadata.json"

/**
 * One document as the outbox holds it (AU-04): the entry [directory] plus the delivery state persisted in its [METADATA_FILE_NAME].
 *
 * It exposes everything a caller needs to deliver the document: the [pdf] as the outbox stored it, and the [document] rebuilt from the
 * persisted [metadata] — including the [OutputDocument.startedAt] the AU-05 file name derives from, which the stored `document.pdf`
 * itself does not carry.
 */
data class OutboxEntry(
    val id: String,
    val directory: Path,
    val metadata: OutboxMetadata,
) {
    /** The document's PDF, exactly as persisted in [directory]. */
    val pdf: Path
        get() = directory.resolve(PDF_FILE_NAME)

    /** The document rebuilt from the persisted [metadata], ready for delivery. */
    val document: OutputDocument
        get() = OutputDocument(pdf, metadata.pageCount, metadata.startedAt, metadata.finishedAt)
}

/**
 * The outbox: the persistence buffer between a finished document and the output modules (AU-04).
 *
 * **The promise: persist before handing over.** [accept] completes the full persistence — entry directory, PDF, metadata — *before* it
 * returns, so the caller may hand the document to a module only on the strength of that persistence. The entry is deleted only after a
 * module has confirmed delivery ([recordSuccess]); until then the outbox is the only copy, and a crash loses nothing because [recover]
 * rebuilds the same state on construction.
 *
 * **Passive by design.** No thread, no timer, no reference to the output modules: the outbox persists, names what is due ([due]), and
 * records an outcome it is told about. The clock is turned by an `OutboxRunner` in the service layer (issue #116), which is what calls
 * the modules — the plan's "Wer die Wiederholung antreibt" decision; a self-driving outbox would force every test into concurrency,
 * which made milestone 3 flaky.
 *
 * **Crash safety and recovery.** Both files are placed by temp-file-then-rename, so each name only ever appears fully written, and a
 * crash mid-[accept] leaves a directory *without* the metadata file. On construction, [recover] reads every directory so a restart
 * finds its pending work; an entry whose metadata is missing, unreadable, or corrupt is skipped **loudly** via [warn] and never deleted.
 *
 * The state sits under one coarse lock: [accept] comes from the batch's sink, [due]/[recordSuccess]/[recordFailure] from the runner;
 * each is a handful of map updates plus file I/O, so one lock is the whole concurrency story.
 *
 * @param root the outbox directory, created if absent; one sub-directory per document.
 * @param clock the time source for [due] and the backoff; injected so tests control it.
 * @param backoffInitial the delay after the **first** failed attempt. A constructor parameter rather than a setting: it tunes a
 *   per-instance algorithm, so the `PageSettings` pattern does not apply and `docs/konfiguration.md` must not grow a knob nobody turns.
 * @param backoffFactor how much each failed attempt multiplies the next delay by.
 * @param backoffCap the maximum delay; attempts are unbounded (AU-04), the delay only grows towards this cap.
 * @param warn where recoverable trouble goes, as a string; tests assert on it.
 */
class Outbox(
    root: Path,
    private val clock: Clock,
    private val backoffInitial: Duration = Duration.ofSeconds(30),
    private val backoffFactor: Double = 2.0,
    private val backoffCap: Duration = Duration.ofHours(1),
    private val warn: (String) -> Unit = {},
) {
    /** The outbox directory, created if absent. */
    val root: Path = Files.createDirectories(root)

    /** The pending entries, keyed by directory name; also the lock guarding them. */
    private val entries = LinkedHashMap<String, OutboxEntry>()

    init {
        recover()
    }

    /**
     * Accepts a finished document (AU-04): creates the entry directory, copies the PDF in, writes the metadata — all *before* it
     * returns, because the caller may hand the document over only on the strength of that persistence. On failure the half-built
     * directory is undone (best effort) and the exception propagates, so the handover cannot happen on an incomplete persistence.
     *
     * @return the accepted entry.
     */
    fun accept(document: OutputDocument): OutboxEntry =
        synchronized(entries) {
            val dir = createEntryDirectory(document.startedAt)
            val entry = OutboxEntry(dir.fileName.toString(), dir, persistInto(dir, document))
            entries[entry.id] = entry
            entry
        }

    /**
     * The entries whose [OutboxMetadata.nextAttemptAt] has arrived according to the injected [clock], oldest first. This only *names*
     * them: delivering is the runner's job (issue #116), whose outcome must come back through [recordSuccess] or [recordFailure] —
     * nothing here touches a module.
     */
    fun due(): List<OutboxEntry> =
        synchronized(entries) {
            val now = clock.instant()
            entries.values
                .filter { it.metadata.nextAttemptAt <= now }
                .sortedWith(compareBy({ it.metadata.nextAttemptAt }, { it.id }))
        }

    /**
     * Records that delivery of [entry] succeeded: removes it from the pending state and deletes its directory — and only then; AU-04
     * keeps an entry *until* a module confirms, and deleting anything earlier is how a document is lost for good.
     *
     * If the directory cannot be fully deleted, the entry is still removed — the document has already been delivered — and [warn] says
     * a directory was left behind. After a restart it will be recovered and delivered again: a duplicate beats a silent loss, and the
     * warning tells the operator what to clean up.
     */
    fun recordSuccess(entry: OutboxEntry) {
        synchronized(entries) {
            entries.remove(entry.id)
        }

        if (!deleteRecursively(entry.directory)) {
            warn(
                "entry ${entry.id} was delivered, but its directory could not be fully deleted; " +
                    "it will be recovered and delivered again after a restart — remove it manually",
            )
        }
    }

    /**
     * Records that delivery of [entry] failed: increments [OutboxMetadata.attempts], pushes [OutboxMetadata.nextAttemptAt] out by
     * [backoffDelay] for that many failed attempts, and persists the result.
     *
     * The metadata is written **before** the in-memory state changes, so a crash in between resumes the same backoff after a restart
     * (AU-04) instead of retrying immediately. Attempts are unbounded; the delay only grows towards the cap.
     */
    fun recordFailure(entry: OutboxEntry) {
        synchronized(entries) {
            val delay = backoffDelay(entry.metadata.attempts, backoffInitial, backoffFactor, backoffCap)
            val metadata =
                entry.metadata.copy(
                    attempts = entry.metadata.attempts + 1,
                    nextAttemptAt = clock.instant().plus(delay),
                )
            writeMetadata(entry.directory.resolve(METADATA_FILE_NAME), metadata)
            entries[entry.id] = entry.copy(metadata = metadata)
        }
    }

    /**
     * Copies the PDF into [dir] and writes the metadata (AU-04).
     *
     * The order is what makes a crash safe: (1) the PDF is copied to a temporary name *in the same directory*, then renamed over
     * [PDF_FILE_NAME] — a rename is atomic, so the name only ever appears with a complete file; (2) the [METADATA_FILE_NAME] is written
     * the same way ([writeMetadata]). [recover] trusts an entry on the presence of the metadata file, so a directory that *looks*
     * complete is complete; a crash mid-accept leaves one without a metadata file, which recover skips.
     *
     * @throws java.io.IOException if any step fails; the half-built [dir] is undone (best effort) and the exception rethrown.
     */
    private fun persistInto(
        dir: Path,
        document: OutputDocument,
    ): OutboxMetadata {
        try {
            val tmp = Files.createTempFile(dir, "document", ".pdf.tmp")
            Files.copy(document.pdf, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, dir.resolve(PDF_FILE_NAME), StandardCopyOption.REPLACE_EXISTING)
            val metadata =
                OutboxMetadata(
                    pageCount = document.pageCount,
                    startedAt = document.startedAt,
                    finishedAt = document.finishedAt,
                    attempts = 0,
                    nextAttemptAt = clock.instant(),
                )
            writeMetadata(dir.resolve(METADATA_FILE_NAME), metadata)
            return metadata
        } catch (e: Exception) {
            if (!deleteRecursively(dir)) {
                warn("outbox entry ${dir.fileName} could not be fully undone after a failed accept — inspect it")
            }
            throw e
        }
    }

    /**
     * Rebuilds the pending state from the directories that already sit in [root], so a restart finds its work (AU-04).
     *
     * A directory whose [METADATA_FILE_NAME] is missing (an [accept] that crashed before the metadata write), unreadable, or corrupt
     * is skipped **loudly** via [warn] and never deleted; a missing [PDF_FILE_NAME] warns, too, but the entry stays — delivery will
     * fail and back off, and a human can repair it.
     */
    private fun recover() {
        if (!Files.isDirectory(root)) return
        val dirs = Files.list(root).use { stream -> stream.filter { Files.isDirectory(it) }.toList() }
        dirs.sortedBy { it.fileName.toString() }.forEach { dir ->
            val metadata =
                try {
                    readMetadata(dir.resolve(METADATA_FILE_NAME))
                } catch (e: Exception) {
                    warn("outbox entry ${dir.fileName} skipped: $METADATA_FILE_NAME unreadable (${e.message})")
                    return@forEach
                }
            if (metadata == null) {
                warn("outbox entry ${dir.fileName} skipped: $METADATA_FILE_NAME is corrupt")
                return@forEach
            }
            if (!Files.exists(dir.resolve(PDF_FILE_NAME))) {
                warn("outbox entry ${dir.fileName} recovered, but $PDF_FILE_NAME is missing — delivery will fail")
            }
            val id = dir.fileName.toString()
            entries[id] = OutboxEntry(id, dir, metadata)
        }
    }

    /**
     * Creates the entry directory for a document with the given [startedAt].
     *
     * The name is the epoch **milliseconds** of the instant, zero-padded to 13 digits: the fixed width keeps the lexicographic order
     * the numeric order, so the directories sort chronologically as plain strings; it derives from the same instant the AU-05 file
     * name uses; and the millisecond resolution separates two documents that finish in the same *second* (where `documentName` would
     * collide). A collision on the very same millisecond — the batch closes sequentially, so effectively impossible — still lands in
     * a different directory: a numeric suffix `-2`, `-3`, ... is appended until a free name is found.
     */
    private fun createEntryDirectory(startedAt: Instant): Path {
        val base = "%013d".format(startedAt.toEpochMilli())
        val name =
            if (Files.exists(root.resolve(base))) {
                var n = 1
                while (Files.exists(root.resolve("$base-$n"))) {
                    n++
                }
                "$base-$n"
            } else {
                base
            }
        return Files.createDirectory(root.resolve(name))
    }

    /** Deletes [dir] and everything in it; returns whether *everything* went away, so a clean delete is told apart from a partial one. */
    @OptIn(ExperimentalPathApi::class)
    private fun deleteRecursively(dir: Path): Boolean {
        if (!Files.exists(dir)) return true
        return runCatching { dir.deleteRecursively() }.isSuccess && !Files.exists(dir)
    }
}

/**
 * The delay before the next delivery attempt, given [attempt] failures so far (AU-04).
 *
 * [initial] is the delay after the first failure; each further failure multiplies the delay by [factor], clamped at [cap]. With the
 * outbox defaults (30 s, factor 2, cap 1 h) the sequence is 30 s, 1 min, 2 min, ... capped at 1 h; the attempts themselves are
 * unbounded.
 *
 * **Why this cannot overflow.** The delay is multiplied as a [BigDecimal] count of nanoseconds, which has no upper bound, and every
 * step is clamped to [cap] *before* the next multiplication — so the running value never exceeds [cap] and the result is converted
 * back to a [Duration] only once it is known to fit. A naive `toNanos()` times a factor would wrap `Long` after roughly 63 doublings
 * and return a negative delay; here the value has no range to run out of. The loop also stops as soon as the delay stops changing:
 * a growing backoff reaches the cap after a handful of iterations (seven for the defaults), while a shrinking one (factor below 1)
 * floors to zero and stays there — so either direction costs a handful of iterations no matter how large [attempt] is.
 *
 * @param attempt how many attempts have already failed *before* the one being scheduled; `0` yields [initial], so the first failure
 *   waits [initial]. This is the `attempts` counter as it stands when the failure is recorded, before it is incremented.
 * @param initial the delay after the first failure.
 * @param factor the multiplier applied per additional failure; a [Double], so non-integral factors such as `1.5` are allowed.
 * @param cap the maximum delay.
 */
fun backoffDelay(
    attempt: Int,
    initial: Duration,
    factor: Double,
    cap: Duration,
): Duration {
    require(attempt >= 0) { "attempt must be non-negative" }
    require(!initial.isNegative) { "initial must not be negative" }
    require(!cap.isNegative) { "cap must not be negative" }
    require(factor >= 0.0 && factor.isFinite()) { "factor must be finite and non-negative" }

    val capNanos = cap.toExactNanos()
    var delayNanos = initial.toExactNanos().min(capNanos)
    var remaining = attempt
    while (remaining > 0 && delayNanos < capNanos && delayNanos.signum() != 0) {
        val next =
            delayNanos
                .multiply(BigDecimal(factor))
                .setScale(0, RoundingMode.FLOOR)
                .min(capNanos)
        if (next == delayNanos) break
        delayNanos = next
        remaining--
    }
    return delayNanos.toDurationOfNanos()
}

/** This duration as an exact nanosecond count. [BigDecimal] rather than `toNanos()`, which throws once a duration exceeds ~292 years. */
private fun Duration.toExactNanos(): BigDecimal = BigDecimal(seconds).multiply(NANOS_PER_SECOND).add(BigDecimal(nano.toLong()))

/** The inverse of [toExactNanos]. Only called with a value already clamped to a cap that is itself a [Duration], so the seconds fit. */
private fun BigDecimal.toDurationOfNanos(): Duration {
    val (seconds, nanos) = divideAndRemainder(NANOS_PER_SECOND)
    return Duration.ofSeconds(seconds.toLong(), nanos.toLong())
}

private val NANOS_PER_SECOND = BigDecimal(1_000_000_000L)
