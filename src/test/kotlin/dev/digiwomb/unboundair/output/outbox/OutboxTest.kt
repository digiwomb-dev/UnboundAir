package dev.digiwomb.unboundair.output.outbox

import dev.digiwomb.unboundair.output.OutputDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

    @Nested
    inner class BackoffGrowth {
        @Test
        fun `AU-04 repeated failures double the delay with the default factor`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val first = Outbox(root, clockAt(START))
            val entry = first.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            first.recordFailure(entry)
            assertThat(readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!.nextAttemptAt)
                .`as`("the first failure waits the initial 30 s backoff")
                .isEqualTo(START.plus(BACKOFF))

            val secondTime = START.plus(BACKOFF).plusSeconds(1)
            val second = Outbox(root, clockAt(secondTime))
            second.recordFailure(second.due().single())
            val afterSecond = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!
            assertThat(afterSecond.attempts)
                .`as`("the second recorded failure is the second failed attempt")
                .isEqualTo(2)
            assertThat(afterSecond.nextAttemptAt)
                .`as`("the second failure doubles the delay to 60 s from the moment it was recorded")
                .isEqualTo(secondTime.plus(SECOND_BACKOFF))

            val thirdTime = secondTime.plus(SECOND_BACKOFF).plusSeconds(1)
            val third = Outbox(root, clockAt(thirdTime))
            third.recordFailure(third.due().single())
            val afterThird = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!
            assertThat(afterThird.attempts)
                .`as`("the third recorded failure is the third failed attempt")
                .isEqualTo(3)
            assertThat(afterThird.nextAttemptAt)
                .`as`("the third failure doubles the delay again, to 120 s from the moment it was recorded")
                .isEqualTo(thirdTime.plus(SECOND_BACKOFF.multipliedBy(2)))
        }

        @Test
        fun `AU-04 the delay never exceeds the cap`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val box =
                Outbox(
                    root,
                    clockAt(START),
                    backoffInitial = Duration.ofSeconds(10),
                    backoffFactor = 2.0,
                    backoffCap = Duration.ofSeconds(25),
                )
            var entry = box.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            listOf(10L, 20L, 25L, 25L).forEachIndexed { index, seconds ->
                box.recordFailure(entry)
                entry = entry.copy(metadata = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!)
                assertThat(entry.metadata.attempts)
                    .`as`("failure ${index + 1} is recorded as attempt ${index + 1}")
                    .isEqualTo(index + 1)
                assertThat(entry.metadata.nextAttemptAt)
                    .`as`("after failure ${index + 1} the next attempt is 10 s, 20 s, then the 25 s cap (never 40 s)")
                    .isEqualTo(START.plusSeconds(seconds))
            }
        }

        @Test
        fun `AU-04 a custom factor scales each further delay`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val box =
                Outbox(
                    root,
                    clockAt(START),
                    backoffInitial = Duration.ofSeconds(10),
                    backoffFactor = 3.0,
                    backoffCap = Duration.ofHours(1),
                )
            val entry = box.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            box.recordFailure(entry)
            val afterFirst = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!
            assertThat(afterFirst.nextAttemptAt)
                .`as`("the first failure waits the initial 10 s regardless of the factor")
                .isEqualTo(START.plusSeconds(10))

            box.recordFailure(entry.copy(metadata = afterFirst))
            val afterSecond = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!
            assertThat(afterSecond.attempts)
                .`as`("the second recorded failure is the second failed attempt")
                .isEqualTo(2)
            assertThat(afterSecond.nextAttemptAt)
                .`as`("the second failure triples the delay to 30 s")
                .isEqualTo(START.plusSeconds(30))
        }

        @Test
        fun `AU-04 unbounded attempts return the cap instead of overflowing`() {
            assertThat(backoffDelay(10_000, BACKOFF, 2.0, CAP))
                .`as`("10 000 doublings from 30 s would overflow a Long nanosecond count; the cap stops it at 1 h")
                .isEqualTo(CAP)
        }
    }

    @Nested
    inner class DueBoundary {
        @Test
        fun `AU-04 an entry is due exactly at its next attempt`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            outbox.recordFailure(entry)
            val nextAttempt = START.plus(BACKOFF)

            assertThat(Outbox(root, clockAt(nextAttempt.minusNanos(1))).due())
                .`as`("one nanosecond before the next attempt the entry is still not due")
                .isEmpty()
            assertThat(Outbox(root, clockAt(nextAttempt)).due().map { it.id })
                .`as`("at exactly the next attempt the entry is due: the boundary is inclusive")
                .containsExactly(entry.id)
        }

        @Test
        fun `AU-04 due entries sort by next attempt, oldest first`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val first = outbox.accept(OutputDocument(pdfSource(dir, "first.pdf"), PAGE_COUNT, START, FINISH))
            val laterStart = START.plusSeconds(3_600)
            val second =
                outbox.accept(
                    OutputDocument(pdfSource(dir, "second.pdf"), PAGE_COUNT, laterStart, laterStart.plusSeconds(45)),
                )

            outbox.recordFailure(first)

            val later = Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1)))
            assertThat(later.due().map { it.id })
                .`as`("the untouched entry (due since accept) sorts before the failed one (backed off by 30 s)")
                .containsExactly(second.id, first.id)
        }
    }

    @Nested
    inner class RetryKeepsEntry {
        @Test
        fun `AU-04 repeated failures never drop the entry`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val box =
                Outbox(
                    root,
                    clockAt(START),
                    backoffInitial = Duration.ofSeconds(1),
                    backoffFactor = 2.0,
                    backoffCap = Duration.ofHours(1),
                )
            var entry = box.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))
            repeat(5) {
                box.recordFailure(entry)
                entry = entry.copy(metadata = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!)
            }

            assertThat(entry.metadata.attempts)
                .`as`("five recorded failures are five failed attempts: attempts are unbounded, nothing is dropped")
                .isEqualTo(5)
            assertThat(Files.exists(entry.pdf))
                .`as`("after repeated failures the document is still there: the outbox keeps what it cannot deliver")
                .isTrue()
            val recovered = Outbox(root, clockAt(START.plusSeconds(3_600))).due()
            assertThat(recovered.map { it.id })
                .`as`("a restart still finds the repeatedly failing entry")
                .containsExactly(entry.id)
            assertThat(recovered.single().metadata.attempts)
                .`as`("the attempt counter survives the restart after repeated failures")
                .isEqualTo(5)
        }

        @Test
        fun `AU-04 a failure with an invalid backoff fails loudly and keeps the entry`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val box = Outbox(root, clockAt(START), backoffFactor = -1.0)
            val entry = box.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            assertThatThrownBy { box.recordFailure(entry) }
                .`as`("a negative factor is rejected instead of scheduling a nonsense retry")
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(box.due().map { it.id })
                .`as`("the rejected failure changed nothing: the entry is still due immediately")
                .containsExactly(entry.id)
            assertThat(Files.exists(entry.pdf))
                .`as`("the rejected failure deleted nothing")
                .isTrue()
        }
    }

    @Nested
    inner class SuccessAfterFailure {
        @Test
        fun `AU-04 recordSuccess after failures deletes the metadata and the document`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val entry = outbox.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))
            outbox.recordFailure(entry)
            val failed = Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1))).due().single()

            outbox.recordSuccess(failed)

            assertThat(Files.exists(entry.directory))
                .`as`("after a confirmed delivery the entry directory is gone")
                .isFalse()
            assertThat(Files.exists(entry.directory.resolve(METADATA_FILE_NAME)))
                .`as`("the metadata goes with the directory: a delivered entry leaves no retry state behind")
                .isFalse()
            assertThat(Files.exists(entry.pdf))
                .`as`("the document copy goes with the directory: the outbox holds nothing after delivery")
                .isFalse()
            assertThat(Outbox(root, clockAt(START.plus(BACKOFF).plusSeconds(1))).due())
                .`as`("a delivered entry never comes back, not even after a restart")
                .isEmpty()
        }

        @Test
        fun `AU-04 recordSuccess drops only the delivered entry`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val outbox = Outbox(root, clockAt(START))
            val first = outbox.accept(OutputDocument(pdfSource(dir, "first.pdf"), PAGE_COUNT, START, FINISH))
            val laterStart = START.plusSeconds(3_600)
            val second =
                outbox.accept(
                    OutputDocument(pdfSource(dir, "second.pdf"), PAGE_COUNT, laterStart, laterStart.plusSeconds(45)),
                )

            outbox.recordFailure(first)
            outbox.recordSuccess(first)

            assertThat(Files.exists(first.directory))
                .`as`("the delivered entry's directory is gone")
                .isFalse()
            assertThat(Files.exists(second.directory))
                .`as`("delivering one entry leaves the other entry's directory alone")
                .isTrue()
            assertThat(Files.exists(second.pdf))
                .`as`("the undelivered entry keeps its document")
                .isTrue()
            assertThat(outbox.due().map { it.id })
                .`as`("only the undelivered entry is still due")
                .containsExactly(second.id)
        }
    }

    @Nested
    inner class ShrinkingBackoff {
        @Test
        fun `AU-04 a factor of one keeps the initial delay for unbounded attempts`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val box =
                Outbox(
                    root,
                    clockAt(START),
                    backoffInitial = BACKOFF,
                    backoffFactor = 1.0,
                    backoffCap = CAP,
                )
            val entry = box.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            box.recordFailure(entry)
            assertThat(readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!.nextAttemptAt)
                .`as`("with a factor of one the first failure still waits the initial delay")
                .isEqualTo(START.plus(BACKOFF))

            // The #136 shape: without the early exit this iterates the whole attempt count.
            assertThat(backoffDelay(Int.MAX_VALUE, BACKOFF, 1.0, CAP))
                .`as`("a constant backoff never reaches the cap, so unbounded attempts return the initial delay")
                .isEqualTo(BACKOFF)
        }

        @Test
        fun `AU-04 a zero factor retries immediately instead of hanging`(
            @TempDir dir: Path,
        ) {
            val root = dir.resolve("outbox")
            val box =
                Outbox(
                    root,
                    clockAt(START),
                    backoffInitial = BACKOFF,
                    backoffFactor = 0.0,
                    backoffCap = CAP,
                )
            val entry = box.accept(OutputDocument(pdfSource(dir, "source.pdf"), PAGE_COUNT, START, FINISH))

            box.recordFailure(entry)
            val afterFirst = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!
            assertThat(afterFirst.attempts)
                .`as`("the recorded failure is the first failed attempt")
                .isEqualTo(1)
            assertThat(afterFirst.nextAttemptAt)
                .`as`("attempt 0 waits the initial delay; the factor only scales further failures")
                .isEqualTo(START.plus(BACKOFF))

            val secondTime = START.plus(BACKOFF).plusSeconds(1)
            val later = Outbox(root, clockAt(secondTime), backoffInitial = BACKOFF, backoffFactor = 0.0, backoffCap = CAP)
            later.recordFailure(later.due().single())
            val failed = readMetadata(entry.directory.resolve(METADATA_FILE_NAME))!!
            assertThat(failed.attempts)
                .`as`("the second recorded failure is the second failed attempt")
                .isEqualTo(2)
            assertThat(failed.nextAttemptAt)
                .`as`("a zero factor floors the further delay to zero, so the next attempt is now")
                .isEqualTo(secondTime)
            assertThat(later.due().map { it.id })
                .`as`("with no further delay the twice-failed entry is due again at once")
                .containsExactly(entry.id)
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

        /** The outbox's default cap: the delay grows towards it but never past it (AU-04). */
        val CAP: Duration = Duration.ofHours(1)

        /** The entry directory name for [START]: epoch milliseconds, zero-padded to 13 digits. */
        val ENTRY_NAME: String = "%013d".format(START.toEpochMilli())

        const val PAGE_COUNT = 3

        const val GOLDEN_PDF = "three_pages_300dpi.pdf"
    }
}
