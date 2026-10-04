package dev.digiwomb.unboundair.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.config.UnboundAirProperties
import dev.digiwomb.unboundair.output.outbox.METADATA_FILE_NAME
import dev.digiwomb.unboundair.output.outbox.PDF_FILE_NAME
import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import dev.digiwomb.unboundair.service.Batch
import dev.digiwomb.unboundair.service.OutputPipeline
import dev.digiwomb.unboundair.service.ScanLoop
import dev.digiwomb.unboundair.service.outputPipeline
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
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
 * The outbox recovery end to end (DL-04, AU-04, issue #133): a finished document survives the
 * scanner going away and a failed upload, and a restart over the same directory delivers it.
 *
 * The chain is the same as in [ServiceEndToEndTest]:
 *
 * ```
 * FakeScanner -> ScannerClient -> ScanLoop -> CropStep -> GrayscaleStep
 *             -> Batch -> outbox -> paperless module -> WireMock
 * ```
 *
 * Two tests. The first covers DL-04: the scanner goes offline mid-batch, the batch closes on the
 * offline signal without the clock moving, and the two scanned sheets still arrive as one two-page
 * PDF. The second covers AU-04: the first upload fails with HTTP 500, the entry stays in the
 * outbox, and a second pipeline over the SAME directory -- the restart, with a clock past the
 * 30-second backoff -- delivers it and deletes the entry only after the confirmed delivery.
 *
 * The same deliberate choices as the sister test apply: the chain is assembled by
 * [outputPipeline], never by hand, and the `RestClient` is pinned to HTTP/1.1 because WireMock
 * resets the connection on the JDK client's HTTP/2 upgrade probe.
 *
 * The clocks deserve a note. The outbox schedules the next attempt 30 seconds out
 * (`backoffInitial`), and `outputPipeline` always builds the outbox with that default. With a
 * standing clock the failed entry is therefore never due again, so the second pipeline -- the
 * restart -- MUST run on a clock more than 30 seconds ahead of the first one. Five minutes is
 * deliberately generous so the test never balances on the backoff boundary.
 *
 * Offline (DC-03): loopback TCP to the fake scanner, WireMock on a dynamic localhost port,
 * committed fixtures, the dev container's `jpegtran`. Nothing reaches the internet.
 */
class OutboxRecoveryEndToEndTest {
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
    fun `DL-04 the batch closes when the scanner goes offline and the document is still delivered`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(
                listOf(
                    TestImages.bytes(ENVELOPE),
                    TestImages.bytes(A4),
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
                // Both sheets are picked up by the loop on its own.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= EXPECTED_PAGES }

                // The device switches itself off: the batch closes on the
                // offline signal (DL-04) with the clock standing still.
                fake.goOffline()

                // The closed batch lands in the outbox and the runner
                // delivers it without any further clock movement.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { server.allServeEvents.isNotEmpty() }
            } finally {
                loop.stop()
                pipeline.runner.stop()
                loopThread.join(THREAD_JOIN_MILLIS)
                runnerThread.join(THREAD_JOIN_MILLIS)
            }

            // DL-04/AU-02: two sheets are ONE document, so paperless sees
            // exactly ONE upload, not two.
            server.verify(1, postRequestedFor(urlEqualTo(POST_PATH)))

            val request = server.allServeEvents.single().request
            val part = request.getPart("document")
            assertThat(part)
                .`as`("the upload carries the PDF as the multipart field 'document' (AU-05)")
                .isNotNull()
            checkNotNull(part)

            val pdfBytes = part.body.asBytes()
            Loader.loadPDF(pdfBytes).use { pdf ->
                assertThat(pdf.numberOfPages)
                    .`as`("DL-04: two pages in, two pages uploaded after the offline close")
                    .isEqualTo(EXPECTED_PAGES)
            }
        }
    }

    @Test
    fun `AU-04 a failed upload is delivered after a restart over the same outbox directory`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(
                listOf(
                    TestImages.bytes(ENVELOPE),
                    TestImages.bytes(A4),
                ),
            )
            fake.start()

            // Phase one: paperless answers 500, so the delivery fails.
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(500)))

            val clock = MutableClock(START)
            val pipeline = pipelineFor(clock, tempDir)
            val loop = loopFor(clock, fake, tempDir, pipeline)

            val loopThread = thread(start = true) { loop.run() }
            val runnerThread = thread(start = true) { pipeline.runner.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { loop.pageCount >= EXPECTED_PAGES }

                // The user stops feeding sheets: the window expires and the
                // batch closes, landing in the outbox for the runner.
                clock.advance(BATCH_TIMEOUT)

                // The failed attempt happened AND the outbox recorded the
                // backoff: the serve event alone would race the recordFailure
                // write, but an entry with its next attempt 30 seconds out is
                // no longer due on the standing clock.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until {
                    server.allServeEvents.isNotEmpty() && pipeline.outbox.due().isEmpty()
                }
            } finally {
                loop.stop()
                pipeline.runner.stop()
                loopThread.join(THREAD_JOIN_MILLIS)
                runnerThread.join(THREAD_JOIN_MILLIS)
            }

            // AU-04: the failed document is still there -- the entry is only
            // deleted after a confirmed delivery.
            val outboxRoot = tempDir.resolve("outbox")
            val entries =
                Files.list(outboxRoot).use { stream ->
                    stream.filter { Files.isDirectory(it) }.toList()
                }
            assertThat(entries)
                .`as`("AU-04: the failed document stays in the outbox")
                .isNotEmpty()
            assertThat(entries.any { Files.exists(it.resolve(PDF_FILE_NAME)) })
                .`as`("AU-04: the pending entry keeps its $PDF_FILE_NAME")
                .isTrue()
            assertThat(entries.any { Files.exists(it.resolve(METADATA_FILE_NAME)) })
                .`as`("AU-04: the pending entry keeps its $METADATA_FILE_NAME")
                .isTrue()

            // Phase two -- the restart: paperless is healthy again, and a
            // SECOND pipeline over the SAME directory picks the entry up. The
            // new clock stands five minutes ahead so the 30-second backoff
            // written by the first pipeline is already due.
            val taskId = "8d1c0b2e-1111-2222-3333-444455556666"
            server.resetAll()
            server.stubFor(post(urlEqualTo(POST_PATH)).willReturn(aResponse().withStatus(200).withBody("\"$taskId\"")))

            val restartClock = MutableClock(START.plus(RESTART_AHEAD))
            val restartPipeline = pipelineFor(restartClock, tempDir)

            val restartRunnerThread = thread(start = true) { restartPipeline.runner.run() }
            try {
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until { server.allServeEvents.isNotEmpty() }

                server.verify(1, postRequestedFor(urlEqualTo(POST_PATH)))

                val request = server.allServeEvents.single().request
                val part = request.getPart("document")
                assertThat(part)
                    .`as`("the redelivered upload carries the PDF as the multipart field 'document' (AU-05)")
                    .isNotNull()
                checkNotNull(part)

                val pdfBytes = part.body.asBytes()
                Loader.loadPDF(pdfBytes).use { pdf ->
                    assertThat(pdf.numberOfPages)
                        .`as`("AU-04: two pages in, two pages uploaded after the restart")
                        .isEqualTo(EXPECTED_PAGES)
                }

                // The serve event races the delete: the runner removes the
                // entry only after the confirmed delivery, so wait for the
                // directory to empty instead of asserting it straight away.
                await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS).until {
                    Files.list(outboxRoot).use { stream -> stream.toList().isEmpty() }
                }
            } finally {
                restartPipeline.runner.stop()
                restartRunnerThread.join(THREAD_JOIN_MILLIS)
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

        /** How far ahead the restart clock stands: safely past the 30-second backoff. */
        val RESTART_AHEAD: Duration = Duration.ofMinutes(5)

        const val EXPECTED_PAGES = 2
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"

        const val AWAIT_SECONDS = 60L
        const val THREAD_JOIN_MILLIS = 5_000L
        const val SLEEP_MILLIS = 5L
    }
}
