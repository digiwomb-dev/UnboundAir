package dev.digiwomb.unboundair.output.outbox

import dev.digiwomb.unboundair.output.OutputDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [Outbox] (AU-04).
 *
 * The outbox is the persistence buffer between a finished document and the
 * output modules, and its one promise is "persist before handing over":
 * [Outbox.accept] completes the entry directory, the PDF, and the metadata
 * before it returns, and the entry is deleted only after a module has
 * confirmed delivery via [Outbox.recordSuccess]. A failed delivery via
 * [Outbox.recordFailure] keeps the document and backs off.
 *
 * **No sleeping anywhere.** Time comes from a fixed [Clock] the test
 * constructs per step. A mutable clock is deliberately avoided: each scenario
 * names the instant it needs, and "the clock advanced past the backoff" is
 * expressed by building the next outbox with a fixed clock on that later
 * instant, which is exactly what a restart would do.
 *
 * **The disk is the source of truth.** AU-04's crash safety lives in the files
 * beside each document, so the retry state is asserted through [readMetadata]
 * on the `metadata.json` on disk, not only on the in-memory entry that the
 * caller happens to hold.
 *
 * Offline (DC-03): a committed golden PDF as the source document, fixed
 * clocks, a temporary directory. No device, no network.
 */
class OutboxTest {
    @Nested
    inner class Accept {
        @Test
        fun `AU-04 accept persists the PDF and the metadata before it returns`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val source = pdfSource(dir, "source.pdf")
            val outbox = Outbox(root, clockAt(START))

            val entry = outbox.accept(OutputDocument(source, PAGE_COUNT, START, FINISH))

            val expectedDir = root.resolve(ENTRY_NAME)
            assertThat(entry.directory)
                .`as`("the entry directory is the root plus the document's start time in epoch milliseconds")
                .isEqualTo(expectedDir)
            assertThat(Files.exists(entry.pdf))
                .`as`("the PDF is copied into the entry directory before accept returns")
                .isTrue()
            assertThat(Files.readAllBytes(entry.pdf))
                .`as`("the outbox copies the document byte for byte; it never re-encodes")
                .isEqualTo(Files.readAllBytes(source))
            assertThat(Files.exists(entry.directory.resolve(METADATA_FILE_NAME)))
                .`as`("the metadata is written before accept returns, so the handover rests on that persistence")
                .isTrue()
            assertThat(entry.metadata)
                .`as`("a fresh entry has no failed attempts and is due at the instant the clock shows")
                .isEqualTo(OutboxMetadata(PAGE_COUNT, START, FINISH, 0, START))
            assertThat(entry.document)
                .`as`("the entry rebuilds the document from its persisted metadata, pointing at document.pdf")
                .isEqualTo(OutputDocument(expectedDir.resolve(PDF_FILE_NAME), PAGE_COUNT, START, FINISH))
            assertThat(outbox.due())
                .`as`("a just-accepted entry is due, so the runner can deliver it")
                .containsExactly(entry)
        }

        @Test
        fun `AU-04 two documents with the same start time get two directories`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))

            val first = outbox.accept(OutputDocument(pdfSource(dir, "first.pdf"), PAGE_COUNT, START, FINISH))
            val second = outbox.accept(OutputDocument(pdfSource(dir, "second.pdf"), PAGE_COUNT, START, FINISH))

            assertThat(first.directory.fileName.toString())
                .`as`("the first document takes the bare start-time name")
                .isEqualTo(ENTRY_NAME)
            assertThat(second.directory.fileName.toString())
                .`as`("a collision on the same millisecond lands in a suffixed directory, so both keep their copy")
                .isEqualTo("$ENTRY_NAME-1")
            assertThat(Files.exists(first.pdf)).isTrue()
            assertThat(Files.exists(second.pdf)).isTrue()
            assertThat(outbox.due())
                .`as`("both entries are due and sort chronologically, the earlier name first")
                .containsExactly(first, second)
        }
    }

    @Nested
    inner class RecordFailure {
        @Test
        fun `AU-04 a failed delivery keeps the document and the entry stays pending`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            outbox.recordFailure(entry)

            assertThat(Files.exists(entry.directory))
                .`as`("a failure must not delete the document; until a success the outbox is the only copy")
                .isTrue()
            assertThat(Files.exists(entry.pdf)).isTrue()
            assertThat(Files.exists(entry.directory.resolve(METADATA_FILE_NAME))).isTrue()

            val pending = Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1))).due()
            assertThat(pending)
                .`as`("the entry is still known after the failure: once the backoff has elapsed it is due again")
                .hasSize(1)
            assertThat(pending.single().id).isEqualTo(entry.id)
        }

        @Test
        fun `AU-04 a failed entry is not due again until the backoff has elapsed`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            outbox.recordFailure(entry)

            assertThat(outbox.due())
                .`as`("at the same instant the failure was recorded, the backoff has not elapsed")
                .isEmpty()
            assertThat(Outbox(root, clockAt(START.plus(BACKOFF).minusSeconds(1))).due())
                .`as`("one second before the backoff elapses the entry is still not due")
                .isEmpty()

            val pending = Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1))).due()
            assertThat(pending)
                .`as`("one second past the initial backoff the entry is due again")
                .hasSize(1)
            assertThat(pending.single().metadata.nextAttemptAt)
                .`as`("the first failure waits the initial 30 s backoff")
                .isEqualTo(START.plus(BACKOFF))
        }

        @Test
        fun `AU-04 recordFailure persists the retry state to disk`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            outbox.recordFailure(entry)

            val persisted = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))

            assertThat(persisted)
                .`as`("the retry state must be on disk, not only in memory; a crash in between must resume it")
                .isNotNull()
            assertThat(persisted!!.attempts)
                .`as`("one recorded failure means one failed attempt on the file, not only in the map")
                .isEqualTo(1)
            assertThat(persisted.nextAttemptAt)
                .`as`("the file carries the pushed-out next attempt, so a restart resumes the backoff")
                .isEqualTo(START.plus(BACKOFF))
        }
    }

    @Nested
    inner class RecordSuccess {
        @Test
        fun `AU-04 recordSuccess deletes the directory and drops the entry from the queue`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            outbox.recordSuccess(entry)

            assertThat(Files.exists(entry.directory))
                .`as`("the directory is deleted only after a module has confirmed delivery")
                .isFalse()
            assertThat(outbox.due())
                .`as`("a delivered entry is not due again")
                .isEmpty()
        }
    }

    @Nested
    inner class Recovery {
        @Test
        fun `AU-04 a restart recovers the entry with its attempts intact`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            outbox.recordFailure(entry)
            // The runner's next cycle would fetch the updated entry from due();
            // with a fixed clock the advanced clock plays the role of "later".
            val failedOnce = Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1))).due().single()
            outbox.recordFailure(failedOnce)

            val recovered = Outbox(root, clockAt(START.plus(SECOND_BACKOFF).plusSeconds(1))).due()

            assertThat(recovered)
                .`as`("a restart finds the failing entry again")
                .hasSize(1)
            assertThat(recovered.single().id)
                .`as`("the recovered entry is the same document")
                .isEqualTo(entry.id)
            assertThat(recovered.single().metadata.attempts)
                .`as`("the attempt counter survives the restart; the backoff resumes, it does not reset")
                .isEqualTo(2)
            assertThat(recovered.single().metadata.nextAttemptAt)
                .`as`("the second failure doubled the delay to 60 s, and the restart kept that schedule")
                .isEqualTo(START.plus(SECOND_BACKOFF))
        }

        @Test
        fun `AU-04 a corrupt metadata file is skipped loudly and never deleted`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            Files.writeString(entry.directory.resolve(METADATA_FILE_NAME), "{ not valid json")
            val warnings = mutableListOf<String>()
            val restarted = Outbox(root, clockAt(START), warn = { warnings.add(it) })

            assertThat(Files.exists(entry.directory))
                .`as`("a corrupt entry must survive the restart; deleting it would lose the document")
                .isTrue()
            assertThat(Files.exists(entry.pdf)).isTrue()
            assertThat(restarted.due())
                .`as`("an entry the outbox cannot read cannot be delivered, so it is not due")
                .isEmpty()
            assertThat(warnings)
                .`as`("the skip is announced loudly, so an operator can repair the entry")
                .hasSize(1)
            assertThat(warnings.single()).contains(entry.id)
        }

        @Test
        fun `AU-04 a directory without a metadata file is skipped loudly and never deleted`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            Outbox(root, clockAt(START))

            // A crash mid-accept: the PDF made it into the directory, the metadata write never did.
            val orphan = Files.createDirectory(root.resolve(ENTRY_NAME))
            Files.copy(pdfSource(dir, "source.pdf"), orphan.resolve(PDF_FILE_NAME))

            val warnings = mutableListOf<String>()
            val restarted = Outbox(root, clockAt(START), warn = { warnings.add(it) })

            assertThat(Files.exists(orphan))
                .`as`("a half-built entry must survive the restart; deleting it would lose the document")
                .isTrue()
            assertThat(Files.exists(orphan.resolve(PDF_FILE_NAME))).isTrue()
            assertThat(restarted.due())
                .`as`("an entry without metadata cannot be delivered, so it is not due")
                .isEmpty()
            assertThat(warnings)
                .`as`("the skip is announced loudly, so an operator can repair the entry")
                .hasSize(1)
            assertThat(warnings.single()).contains(orphan.fileName.toString())
        }
    }

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    /** Writes the golden PDF's bytes into [dir] under [name] and returns the path, as the document's source file. */
    private fun pdfSource(
        dir: Path,
        name: String,
    ): Path {
        val file = dir.resolve(name)
        Files.write(file, goldenPdfBytes())
        return file
    }

    private fun goldenPdfBytes(): ByteArray =
        javaClass.getResourceAsStream("/golden/$GOLDEN_PDF")?.use { it.readAllBytes() }
            ?: throw IllegalStateException("golden PDF not found on the classpath: /golden/$GOLDEN_PDF")

    private companion object {
        val START: Instant = Instant.parse("2026-10-03T09:00:00Z")
        val FINISH: Instant = START.plusSeconds(45)

        /** The outbox's default backoff after the first failed attempt (AU-04). */
        val BACKOFF: Duration = Duration.ofSeconds(30)

        /** The delay after the second failure with the default factor of 2. */
        val SECOND_BACKOFF: Duration = BACKOFF.multipliedBy(2)

        /** The entry directory name for [START]: epoch milliseconds, zero-padded to 13 digits. */
        val ENTRY_NAME: String = "%013d".format(START.toEpochMilli())

        const val PAGE_COUNT = 3

        const val GOLDEN_PDF = "three_pages_300dpi.pdf"
    }
}
