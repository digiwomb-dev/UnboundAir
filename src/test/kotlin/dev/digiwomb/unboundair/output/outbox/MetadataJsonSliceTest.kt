package dev.digiwomb.unboundair.output.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.json.JsonTest
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Slice test for the outbox `metadata.json` round trip (AU-04, layer `slice` of
 * `docs/teststrategie.md`).
 *
 * The metadata is what the document does not carry inside itself. If it is lost in the
 * round trip, a restart delivers a document with the wrong file name (AU-05 derives it
 * from `startedAt`) and nobody notices. So this pins what survives a `writeMetadata` to
 * `readMetadata` cycle: the page count, both instants with their sub-second precision, and
 * the attempt count come back equal; an instant stays an ISO-8601 string and never
 * silently becomes a number of seconds; a corrupt file reads as `null` instead of
 * throwing.
 *
 * `@JsonTest` is the slice the issue names: it starts the Jackson auto-configuration
 * without the whole application. That slice injects Spring's own mapper, which the outbox
 * never uses, so every behaviour assertion goes through the shipped [outboxMetadataMapper]
 * and the injected mapper is only checked in the boot smoke test. The round trip also
 * walks the trap the issue warns about: Jackson 3 lives under `tools.jackson` and the
 * Kotlin data class needs `jackson-module-kotlin` of the matching group, a combination
 * that fails at runtime, not at compile time.
 *
 * Offline (DC-03): fixed instants, a temporary directory, no device and no network.
 */
@JsonTest
class MetadataJsonSliceTest(
    @Autowired private val springMapper: ObjectMapper,
) {
    @Test
    fun `AU-04 the JsonTest slice boots with the Jackson 3 mapper`() {
        assertThat(springMapper)
            .`as`("the Jackson auto-configuration must resolve, or the round trip below could not run")
            .isNotNull()
    }

    @Nested
    inner class Serialization {
        @Test
        fun `AU-04 an instant is written as an ISO-8601 string, not an epoch number`(
            @TempDir dir: Path,
        ) {
            val path = dir.resolve(METADATA_FILE_NAME)

            writeMetadata(path, metadata())

            val text = Files.readString(path)
            assertThat(text)
                .`as`("WRITE_DATES_AS_TIMESTAMPS is disabled on the shipped mapper, so the file stays human-readable")
                .contains("\"startedAt\":\"${STARTED_AT}\"")
                .contains("\"finishedAt\":\"${FINISHED_AT}\"")
            assertThat(text)
                .`as`("an epoch number would silently change the file format under a Jackson upgrade")
                .doesNotContain(STARTED_AT.epochSecond.toString())
        }
    }

    @Nested
    inner class RoundTrip {
        @Test
        fun `AU-04 a full write-and-read round trip loses nothing`(
            @TempDir dir: Path,
        ) {
            val path = dir.resolve(METADATA_FILE_NAME)

            writeMetadata(path, metadata())

            assertThat(readMetadata(path))
                .`as`("page count, both instants and the attempt count come back exactly as written")
                .isEqualTo(metadata())
        }

        @Test
        fun `AU-04 sub-second instants keep their nanosecond precision`(
            @TempDir dir: Path,
        ) {
            val path = dir.resolve(METADATA_FILE_NAME)

            writeMetadata(path, metadata())

            assertThat(Files.readString(path))
                .`as`("the full fraction must reach the file, not a coarser rendering")
                .contains("$STARTED_AT")
            assertThat(readMetadata(path)!!.startedAt)
                .`as`("AU-05 derives the file name from startedAt, so the nanoseconds must come back intact")
                .isEqualTo(STARTED_AT)
        }
    }

    /**
     * `readMetadata` against files whose content differs from a `writeMetadata`: corrupt,
     * a field missing, a field added. These pin what the outbox does with a file it did
     * not write itself.
     */
    @Nested
    inner class Reading {
        @Test
        fun `AU-04 a corrupt file reads as null and does not throw`(
            @TempDir dir: Path,
        ) {
            val path = dir.resolve(METADATA_FILE_NAME)
            Files.writeString(path, "{ not valid json")

            assertThatCode { readMetadata(path) }
                .`as`("one broken entry must not take the service down; the outbox skips it loudly")
                .doesNotThrowAnyException()
            assertThat(readMetadata(path)).isNull()
        }

        @Test
        fun `AU-04 a file missing a required field reads as null`(
            @TempDir dir: Path,
        ) {
            val m = metadata()
            val path = dir.resolve(METADATA_FILE_NAME)
            Files.writeString(
                path,
                metadataJson(
                    "\"pageCount\":${m.pageCount}",
                    "\"startedAt\":\"${m.startedAt}\"",
                    "\"finishedAt\":\"${m.finishedAt}\"",
                    "\"nextAttemptAt\":\"${m.nextAttemptAt}\"",
                ),
            )

            assertThat(readMetadata(path))
                .`as`("without the attempts counter the backoff state is ambiguous, so the entry is skipped, not half-trusted")
                .isNull()
        }

        @Test
        fun `AU-04 an unknown extra field is ignored on read`(
            @TempDir dir: Path,
        ) {
            val m = metadata()
            val path = dir.resolve(METADATA_FILE_NAME)
            Files.writeString(
                path,
                metadataJson(
                    "\"pageCount\":${m.pageCount}",
                    "\"startedAt\":\"${m.startedAt}\"",
                    "\"finishedAt\":\"${m.finishedAt}\"",
                    "\"attempts\":${m.attempts}",
                    "\"nextAttemptAt\":\"${m.nextAttemptAt}\"",
                    "\"addedLater\":1",
                ),
            )

            assertThat(readMetadata(path))
                .`as`("a field added by a future version must not break the read")
                .isEqualTo(m)
        }
    }

    /** Assembles `metadata.json` field by field so the edge-case tests can leave a field out or add one the outbox never wrote. */
    private fun metadataJson(vararg fields: String): String = fields.joinToString(prefix = "{", postfix = "}")

    private companion object {
        /** A start time whose sub-second part uses the full nanosecond precision. */
        val STARTED_AT: Instant = Instant.parse("2026-09-27T14:05:09.123456789Z")

        val FINISHED_AT: Instant = STARTED_AT.plusSeconds(42)

        val NEXT_ATTEMPT_AT: Instant = FINISHED_AT.plusSeconds(30)

        const val PAGE_COUNT = 3

        const val ATTEMPTS = 2

        fun metadata() = OutboxMetadata(PAGE_COUNT, STARTED_AT, FINISHED_AT, ATTEMPTS, NEXT_ATTEMPT_AT)
    }
}
