package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for [Batch] (DL-03, DL-04).
 *
 * DL-04 names two triggers for closing a batch, and both get a test: the batch
 * timeout since the last page, and the scanner going offline. The second is a
 * regular trigger, not an error -- the device switches itself off after five
 * minutes and that just means the job is done.
 *
 * **No sleeping anywhere.** Time comes from a [MutableClock] the test advances
 * by hand. A test that slept for the real timeout would be slow, and one that
 * slept for a shortened timeout would be a race dressed up as a test.
 *
 * Offline (DC-03): committed fixtures, a fake clock, a temporary directory.
 */
class BatchTest {
    /**
     * A clock the test moves deliberately.
     *
     * [Clock.fixed] cannot express "20 seconds later", and [Clock.offset] would
     * need a new instance per step. Both would obscure what the test is
     * actually doing, which is stepping time across a threshold.
     */
    private class MutableClock(
        private var now: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    @Test
    fun `DL-03 a page opens the batch and further pages join it`(
        @TempDir dir: Path,
    ) {
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir)

        batch.addPage(jpeg(), DPI)
        assertThat(batch.isOpen).`as`("the first page opens the batch").isTrue()
        assertThat(batch.startedInstant)
            .`as`("the document timestamp is the start of the first page (AU-05)")
            .isEqualTo(START)

        clock.advance(Duration.ofSeconds(5))
        batch.addPage(jpeg(), DPI)
        clock.advance(Duration.ofSeconds(5))
        batch.addPage(jpeg(), DPI)

        assertThat(batch.pageCount)
            .`as`("pages arriving inside the window belong to the same document")
            .isEqualTo(3)
        assertThat(batch.startedInstant)
            .`as`("later pages must not move the document timestamp")
            .isEqualTo(START)
    }

    @Test
    fun `DL-04 the batch closes when the timeout elapses after the last page`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        batch.addPage(jpeg(), DPI)

        clock.advance(TIMEOUT.minusSeconds(1))
        assertThat(batch.closeIfDue())
            .`as`("one second before the window expires the batch must stay open")
            .isNull()
        assertThat(delivered).isEmpty()

        clock.advance(Duration.ofSeconds(1))
        val document = batch.closeIfDue()

        assertThat(document).`as`("the elapsed timeout must close the batch").isNotNull()
        assertThat(delivered)
            .`as`("the closed document goes to the sink exactly once")
            .hasSize(1)
        assertThat(delivered.single().pageCount).isEqualTo(2)
        assertThat(Files.exists(delivered.single().pdf))
            .`as`("the PDF must exist where the document says it is")
            .isTrue()
        assertThat(batch.isOpen)
            .`as`("after closing, the batch is empty and ready for the next document")
            .isFalse()
    }

    /**
     * The timeout counts from the **last** page, not from the first. A batch
     * fed steadily for ten minutes must not close in the middle just because it
     * started long ago.
     */
    @Test
    fun `DL-04 each new page restarts the timeout window`(
        @TempDir dir: Path,
    ) {
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir)

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT.minusSeconds(1))
        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT.minusSeconds(1))

        assertThat(batch.closeIfDue())
            .`as`("well past the first page, but only just past the second: the batch stays open")
            .isNull()
        assertThat(batch.pageCount).isEqualTo(2)
    }

    @Test
    fun `DL-04 the scanner going offline closes the batch immediately`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(Duration.ofSeconds(1))

        // The offline trigger: no timeout has elapsed, and the batch closes
        // anyway. DL-04 treats the auto-off as a regular end, not a failure.
        val document = batch.close()

        assertThat(document)
            .`as`("offline must close the batch without waiting for the timeout")
            .isNotNull()
        assertThat(delivered).hasSize(1)
        assertThat(delivered.single().pageCount).isEqualTo(1)
    }

    @Test
    fun `DL-04 closing an empty batch produces nothing`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val batch = batchOf(MutableClock(START), dir) { delivered.add(it) }

        assertThat(batch.close())
            .`as`("a scanner that goes offline without a scan must not produce an empty document")
            .isNull()
        assertThat(batch.closeIfDue())
            .`as`("nor may an empty batch ever be due")
            .isNull()
        assertThat(delivered).isEmpty()
    }

    @Test
    fun `DL-04 closing twice delivers the document only once`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)

        batch.closeIfDue()
        batch.close()
        batch.closeIfDue()

        assertThat(delivered)
            .`as`("the offline trigger and the timeout may fire together; the document must go out once")
            .hasSize(1)
    }

    /**
     * After a document is delivered the batch must be usable again: the next
     * sheet starts a new document rather than reopening the old one.
     */
    @Test
    fun `DL-03 the next page after a close starts a fresh document`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        clock.advance(Duration.ofSeconds(30))
        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        assertThat(delivered).hasSize(2)
        assertThat(delivered[1].startedAt)
            .`as`("the second document must carry its own start time, not the first one's")
            .isNotEqualTo(delivered[0].startedAt)
        assertThat(delivered.map { it.pageCount })
            .`as`("one page each: the second document must not inherit the first one's pages")
            .containsExactly(1, 1)
    }

    @Test
    fun `AU-05 the document is named after the start of its first page`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(Instant.parse("2026-09-27T14:05:09Z"))
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        assertThat(
            delivered
                .single()
                .pdf.fileName
                .toString(),
        ).`as`("AU-05 fixes the shape scan-YYYYMMDD-HHMMSS.pdf, taken from the first page")
            .isEqualTo("scan-20260927-140509.pdf")
    }

    @Test
    fun `DL-04 the document reports the span from the first page to the close`(
        @TempDir dir: Path,
    ) {
        val delivered = mutableListOf<ScannedDocument>()
        val clock = MutableClock(START)
        val batch = batchOf(clock, dir) { delivered.add(it) }

        batch.addPage(jpeg(), DPI)
        clock.advance(Duration.ofSeconds(7))
        batch.addPage(jpeg(), DPI)
        clock.advance(TIMEOUT)
        batch.closeIfDue()

        val document = delivered.single()
        assertThat(document.startedAt).isEqualTo(START)
        assertThat(Duration.between(document.startedAt, document.finishedAt))
            .`as`("7 s to the second page plus the 20 s window")
            .isEqualTo(Duration.ofSeconds(7).plus(TIMEOUT))
    }

    private fun batchOf(
        clock: Clock,
        dir: Path,
        sink: (ScannedDocument) -> Unit = {},
    ) = Batch(clock, TIMEOUT, dir, sink)

    /** A real JPEG, so the PDF assembly in `close` has something valid to embed. */
    private fun jpeg(): ByteArray = TestImages.bytes("envelope_dl_300dpi_raw.jpg")

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        const val DPI = 300
    }
}
