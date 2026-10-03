package dev.digiwomb.unboundair.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.config.UnboundAirProperties
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.service.Batch
import dev.digiwomb.unboundair.service.OutputPipeline
import dev.digiwomb.unboundair.service.ScanLoop
import dev.digiwomb.unboundair.service.ScannedDocument
import dev.digiwomb.unboundair.service.outputPipeline
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The service end to end (AU-02..AU-05, issue #74): three sheets go in, ONE three-page PDF is uploaded.
 *
 * The whole chain with nothing stubbed between the socket and the paperless upload:
 *
 * ```
 * FakeScanner -> ScannerClient -> ScanLoop -> CropStep -> GrayscaleStep
 *             -> Batch -> outbox -> paperless module -> WireMock
 * ```
 *
 * This is the [WalkingSkeletonIntegrationTest][dev.digiwomb.unboundair.service.WalkingSkeletonIntegrationTest]
 * flow with the output chain bolted on behind the batch instead of a collecting sink: the batch window
 * expires, the finished document lands in the outbox, the runner hands it to the paperless module, and the
 * module uploads it. The assertions read the PDF back out of the multipart body WireMock actually received,
 * not off the local file: checking the local file would leave the delivery itself untested, and a document
 * that never left the outbox could still pass.
 *
 * Two deliberate choices, both inherited from the templates:
 *
 * - The chain is **not** assembled by hand. [outputPipeline] builds `Outbox`, `OutputModules`,
 *   `PaperlessModule` and `OutboxRunner` and returns them; using that assembly function is the point of this
 *   test, so a drift between the tested wiring and the shipped wiring has nowhere to hide.
 * - The `RestClient` is pinned to HTTP/1.1, copied from
 *   [PaperlessContractTest][dev.digiwomb.unboundair.output.paperless.PaperlessContractTest]: WireMock resets
 *   the connection when the JDK client probes for an HTTP/2 upgrade, so without the pin every multipart
 *   upload dies in transport. This is WireMock's limitation, not the module's -- do not remove the pin
 *   thinking it is decoration.
 *
 * Offline (DC-03): loopback TCP to the fake scanner, WireMock on a dynamic localhost port, committed
 * fixtures, the dev container's `jpegtran`. Nothing reaches the internet.
 */
class ServiceEndToEndTest {
    private lateinit var server: WireMockServer

    @BeforeEach
    fun startServer() {
        server = WireMockServer(options().dynamicPort())
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

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
    fun `AU-02 three scanned sheets are uploaded as one three-page PDF`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            // Two different images, so a document that uploaded the same page
            // three times could not pass unnoticed.
            fake.loadSheets(
                listOf(
                    TestImages.bytes(ENVELOPE),
                    TestImages.bytes(A4),
                    TestImages.bytes(ENVELOPE),
                ),
            )
            fake.start()

            val taskId = "8d1c0b2e-1111-2222-3333-444455556666"
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(200).withBody("\"$taskId\"")))

            val clock = MutableClock(START)
            val pipeline = pipelineFor(clock, tempDir)
            val loop = loopFor(clock, fake, tempDir, pipeline)

            val loopThread = thread(start = true) { loop.run() }
            val runnerThread = thread(start = true) { pipeline.runner.run() }
            try {
                // All three sheets are picked up by the loop on its own: the
                // tray empties itself and the status settles on nopaper.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= EXPECTED_PAGES }

                // The user stops feeding sheets: the window expires and the
                // batch closes on its own (DL-04), landing in the outbox for
                // the runner to deliver.
                clock.advance(BATCH_TIMEOUT)
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { server.allServeEvents.isNotEmpty() }
            } finally {
                loop.stop()
                pipeline.runner.stop()
                loopThread.join(THREAD_JOIN_MILLIS)
                runnerThread.join(THREAD_JOIN_MILLIS)
            }

            // AU-02/AU-03: three sheets are ONE document, so paperless sees
            // exactly ONE upload, not three.
            server.verify(1, postRequestedFor(urlEqualTo(POST_PATH)))

            val request = server.allServeEvents.single().request
            val part = request.getPart("document")
            assertThat(part)
                .`as`("the upload carries the PDF as the multipart field 'document' (AU-05)")
                .isNotNull()
            checkNotNull(part)

            assertThat(part.fileName)
                .`as`("AU-05 fixes the file name shape scan-JJJJMMTT-HHMMSS.pdf")
                .matches("scan-\\d{8}-\\d{6}\\.pdf")
                .isEqualTo("scan-20260927-100000.pdf")

            val pdfBytes = part.body.asBytes()
            assertThat(pdfBytes)
                .`as`("the uploaded document part is a real PDF, read off the wire as bytes")
                .startsWith(*PDF_MAGIC)

            Loader.loadPDF(pdfBytes).use { pdf ->
                assertThat(pdf.numberOfPages)
                    .`as`("AU-02: three pages in, three pages uploaded")
                    .isEqualTo(EXPECTED_PAGES)

                // SV-05 on the delivered bytes: the second sheet is the A4
                // fixture, 2464 px wide at 300 dpi.
                assertThat(pdf.getPage(1).mediaBox.width)
                    .`as`("SV-05: page size is pixels / dpi * 72 pt, taken from the real scan")
                    .isCloseTo(2464f / 300f * 72f, within(TOLERANCE_POINTS))
            }
        }
    }

    /**
     * The assembled output chain, not a hand-built one: [outputPipeline] owns the wiring, this test only
     * feeds it configuration. `pollInterval` is milliseconds so the runner turns without real waiting;
     * production keeps seconds.
     */
    private fun pipelineFor(
        clock: MutableClock,
        dir: Path,
    ): OutputPipeline {
        val properties =
            UnboundAirProperties(
                pollInterval = POLL_INTERVAL,
                output =
                    UnboundAirProperties.OutputProperties(
                        modules = listOf("paperless"),
                        paperless =
                            UnboundAirProperties.PaperlessProperties(
                                baseUrl = "http://localhost:${server.port()}",
                                token = TOKEN,
                            ),
                    ),
                outbox = UnboundAirProperties.OutboxProperties(path = dir.resolve("outbox").toString()),
            )
        // Pinned to HTTP/1.1: see the class KDoc and PaperlessContractTest.fixture -- WireMock resets the
        // connection on the JDK client's HTTP/2 upgrade probe, so an unpinned client never delivers.
        val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val factory = JdkClientHttpRequestFactory(httpClient)
        val restClient = RestClient.builder().requestFactory(factory).build()
        return outputPipeline(properties, clock, ZoneOffset.UTC, warn = {}, restClient)
    }

    private fun loopFor(
        clock: MutableClock,
        fake: FakeScanner,
        dir: Path,
        pipeline: OutputPipeline,
    ): ScanLoop {
        val work = Files.createDirectories(dir.resolve("work"))
        return ScanLoop(
            client = ScannerClient("127.0.0.1", fake.port),
            batch = Batch(clock, BATCH_TIMEOUT, dir.resolve("batch"), pipeline.sink),
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
        const val POST_PATH = "/api/documents/post_document/"
        const val TOKEN = "secret-token"

        val START: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)
        val BATCH_TIMEOUT: Duration = Duration.ofSeconds(20)

        const val EXPECTED_PAGES = 3
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"
        const val TOLERANCE_POINTS = 1.0f

        val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)

        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
        const val SLEEP_MILLIS = 5L
    }
}
