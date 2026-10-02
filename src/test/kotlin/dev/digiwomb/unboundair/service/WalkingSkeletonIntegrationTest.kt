package dev.digiwomb.unboundair.service

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The walking skeleton: three sheets go in, one three-page PDF comes out
 * (DL-03, DL-04, AU-01, SV-05).
 *
 * This is point 4 of "Ergebnis" in `docs/plan.md` minus paperless, and it is
 * the test that says milestone 3 actually holds together. Every other test in
 * this milestone checks one part in isolation; this one runs the whole chain
 * with nothing stubbed between the socket and the finished document:
 *
 * ```
 * FakeScanner -> ScannerClient -> ScanLoop -> CropStep -> GrayscaleStep
 *             -> Batch -> PdfBuilder -> sink
 * ```
 *
 * Real TCP, real `jpegtran`, real PDFBox. The only things that are not
 * production are the device itself and the sink, which in milestone 4 becomes
 * the outbox (AU-04) and then the paperless module. The full end-to-end test
 * with a mocked paperless is #74 and belongs to that milestone.
 *
 * The two assertions that carry the weight:
 *
 * - **three sheets become one document of three pages**, not three documents
 *   of one page. That is the whole point of a batch, and the failure mode is
 *   silent: every page would still be delivered, just torn apart.
 * - **every embedded JPEG is byte-identical to what the processing chain
 *   produced.** The "never recompress" guardrail has to survive the *whole*
 *   pipeline, not only the PdfBuilder unit test.
 *
 * Offline (DC-03): loopback TCP, committed fixtures, the `jpegtran` of the dev
 * container.
 */
class WalkingSkeletonIntegrationTest {
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
    fun `AU-01 three scanned sheets become one three-page PDF`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            // Two different images, so a document that embedded the same page
            // three times could not pass unnoticed.
            fake.loadSheets(
                listOf(
                    TestImages.bytes(ENVELOPE),
                    TestImages.bytes(A4),
                    TestImages.bytes(ENVELOPE),
                ),
            )
            fake.start()

            val clock = MutableClock(START)
            val delivered = ConcurrentLinkedQueue<ScannedDocument>()
            val loop = loopFor(clock, fake, tempDir) { delivered.add(it) }

            val t = thread(start = true) { loop.run() }
            try {
                // All three sheets are picked up by the loop on its own: the
                // tray empties itself and the status settles on nopaper.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= EXPECTED_PAGES }

                assertThat(delivered)
                    .`as`("the batch window has not expired yet, so the document must still be open")
                    .isEmpty()

                // The user stops feeding sheets: the window expires and the
                // batch closes on its own (DL-04).
                clock.advance(BATCH_TIMEOUT)
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { delivered.isNotEmpty() }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }

            assertThat(delivered)
                .`as`("three sheets scanned inside one window must produce ONE document, not three")
                .hasSize(1)

            val document = delivered.single()
            assertThat(document.pageCount).isEqualTo(EXPECTED_PAGES)
            assertThat(document.startedAt)
                .`as`("the document is timestamped from the start of its first page (AU-05)")
                .isEqualTo(START)
            assertThat(document.pdf.fileName.toString())
                .`as`("AU-05 fixes the file name shape")
                .isEqualTo("scan-20260927-100000.pdf")

            Loader.loadPDF(document.pdf.toFile()).use { pdf ->
                assertThat(pdf.numberOfPages)
                    .`as`("AU-01: three pages in, three pages out")
                    .isEqualTo(EXPECTED_PAGES)

                // SV-05 on the real output: the second sheet is the A4 fixture,
                // 2464 px wide at 300 dpi.
                assertThat(pdf.getPage(1).mediaBox.width)
                    .`as`("SV-05: page size is pixels / dpi * 72 pt, taken from the real scan")
                    .isCloseTo(2464f / 300f * 72f, within(TOLERANCE_POINTS))

                // The guardrail, end to end: what PDFBox stored must be exactly
                // the bytes the processing chain produced.
                //
                // The comparison is against the pages the batch kept on disk,
                // not against a marker check. An earlier version of this test
                // only asserted the SOI/EOI markers, and those survive
                // re-encoding untouched: swapping createFromByteArray for
                // createFromImage left the test green while every pixel had
                // been through a second lossy pass. Only comparing the actual
                // bytes catches that.
                val batchPages =
                    Files
                        .list(tempDir.resolve("batch"))
                        .use { stream -> stream.filter { it.fileName.toString().endsWith(".jpg") }.sorted().toList() }

                assertThat(batchPages)
                    .`as`("precondition: the batch kept one processed file per page")
                    .hasSize(EXPECTED_PAGES)

                batchPages.forEachIndexed { index, page ->
                    val embedded = embeddedJpeg(pdf, index)
                    assertThat(embedded)
                        .`as`("page %d must be embedded byte for byte as the chain produced it, never recompressed", index + 1)
                        .isEqualTo(Files.readAllBytes(page))
                    assertThat(embedded)
                        .`as`("and it must still be a complete JPEG")
                        .startsWith(*JPEG_SOI)
                        .endsWith(*JPEG_EOI)
                }

                // Pages 1 and 3 came from the same fixture, page 2 from the
                // other: a document that embedded one page three times, or lost
                // the order, fails here.
                assertThat(embeddedJpeg(pdf, 0))
                    .`as`("pages 1 and 3 are the same sheet and must be byte-identical")
                    .isEqualTo(embeddedJpeg(pdf, 2))
                assertThat(embeddedJpeg(pdf, 1))
                    .`as`("page 2 is a different sheet and must differ")
                    .isNotEqualTo(embeddedJpeg(pdf, 0))
            }
        }
    }

    /**
     * The other DL-04 trigger, through the whole chain: the scanner switching
     * itself off ends the document just as the timeout would.
     */
    @Test
    fun `DL-04 a scanner that goes offline delivers the pages scanned so far`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE), TestImages.bytes(A4)))
            fake.start()

            val clock = MutableClock(START)
            val delivered = ConcurrentLinkedQueue<ScannedDocument>()
            val loop = loopFor(clock, fake, tempDir) { delivered.add(it) }

            val t = thread(start = true) { loop.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= 2 }

                // The five-minute auto-off of the real device.
                fake.goOffline()
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { delivered.isNotEmpty() }
            } finally {
                loop.stop()
                t.join(THREAD_JOIN_MILLIS)
            }

            val document = delivered.single()
            assertThat(document.pageCount)
                .`as`("DL-04: switching off is a regular end - both pages must be in the document")
                .isEqualTo(2)

            Loader.loadPDF(document.pdf.toFile()).use { pdf ->
                assertThat(pdf.numberOfPages).isEqualTo(2)
            }
        }
    }

    /**
     * Extracts the raw, still-compressed JPEG of a page.
     *
     * `createRawInputStream` deliberately: it yields the bytes as stored,
     * without running DCTDecode. Decoding would compare pixels instead of
     * bytes, and a recompressing pipeline could still pass.
     */
    private fun embeddedJpeg(
        document: org.apache.pdfbox.pdmodel.PDDocument,
        pageIndex: Int,
    ): ByteArray {
        val resources = document.getPage(pageIndex).resources
        val name = resources.xObjectNames.first { resources.getXObject(it) is PDImageXObject }
        val image = resources.getXObject(name) as PDImageXObject
        return image.cosObject.createRawInputStream().use { it.readBytes() }
    }

    private fun loopFor(
        clock: MutableClock,
        fake: FakeScanner,
        dir: Path,
        sink: (ScannedDocument) -> Unit,
    ): ScanLoop {
        val work = Files.createDirectories(dir.resolve("work"))
        return ScanLoop(
            client = ScannerClient("127.0.0.1", fake.port),
            batch = Batch(clock, BATCH_TIMEOUT, dir.resolve("batch"), sink),
            // The production chain, not an empty one: crop then grayscale, the
            // same steps the scan command uses.
            processor = PageProcessor(listOf(CropStep(), GrayscaleStep())),
            workDir = work,
            clock = clock,
            pollInterval = POLL_INTERVAL,
            offlinePollInterval = POLL_INTERVAL,
            sleeper = { Thread.sleep(SLEEP_MILLIS) },
        )
    }

    private companion object {
        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)
        val BATCH_TIMEOUT: Duration = Duration.ofSeconds(20)

        const val EXPECTED_PAGES = 3
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"
        const val TOLERANCE_POINTS = 1.0f

        val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
        const val SLEEP_MILLIS = 5L
    }
}
