package dev.digiwomb.unboundair.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.UnboundAirApplication
import dev.digiwomb.unboundair.scanner.FakeScanner
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The service end to end through the real entry point (BE-05, AU-01..AU-05, DL-03, DL-04, SV-05):
 * `run` against a FakeScanner and a WireMock paperless: three sheets go in, ONE three-page PDF is
 * uploaded.
 *
 * The whole chain from the socket to the paperless upload, with nothing stubbed in between:
 *
 * ```
 * run -> FakeScanner -> ScanLoop -> Batch -> outbox -> paperless module -> WireMock
 * ```
 *
 * What this test proves over the hand-assembled chain it replaced: the scanner address and the
 * paperless base URL arrive **through Spring properties alone** (SC-06, the container route — the
 * same binding `RunCommandIntegrationTest` exercises), no `--host` flag is passed anywhere
 * (`parseCliArgs` rejects every `--`-prefixed token, so only the command itself travels through
 * `argv`), and the thread ownership and runner order of `RunCommand` are the ones that deliver. A
 * mistake in the property mapping or in the startup order would leave a hand-assembled test green
 * and the shipped service broken; here the thing the user starts is the thing the test starts.
 *
 * The assertions read the PDF back out of the multipart body WireMock actually received, not off
 * a local file: checking a local file would leave the delivery itself untested, and a document
 * that never left the outbox could still pass.
 *
 * Two deliberate choices:
 *
 * - The batch closes through its normal offline trigger (DL-04): once the tray is empty and the
 *   loop has turned once more — so the third page is guaranteed to be processed and in the batch
 *   — the test switches the fake scanner off via `goOffline`, and the next poll closes the
 *   finished document into the outbox for the runner to deliver. No `close()` call from the test,
 *   no clock manipulation (`run` owns a real clock) and no wait for a wall-clock timeout: the
 *   offline trigger is deterministic where a timeout would depend on the scan duration keeping a
 *   margin to the configured window. The `batch-timeout` (30 s) stays far above the inter-page
 *   gaps so the timeout trigger can never split the document first; the `poll-interval` is short
 *   (1 s) so the test runs in seconds.
 * - Stopping `run` follows the deterministic terminate approach `RunCommandIntegrationTest`
 *   documents: the worker threads are interrupted by name until the boot thread returns. `stop`
 *   itself is reachable in production only through the shutdown hook (DL-07), so the test ends
 *   the threads the only other way that is deterministic. The batch under test is long delivered
 *   by then — the upload is awaited *before* the termination — so the stop path cannot smuggle
 *   the document into the outbox past the offline trigger.
 *
 * Offline (DC-03): loopback TCP to the fake scanner, WireMock on a dynamic localhost port,
 * committed fixtures, the dev container's `jpegtran`. Nothing reaches the internet.
 */
class ServiceEndToEndTest {
    private lateinit var server: WireMockServer

    @BeforeEach
    fun startServer() {
        // h2c disabled on purpose: `run` uploads with the production default `RestClient`,
        // whose JDK client sends the HTTP/2 upgrade probe (`Connection: Upgrade,
        // HTTP2-Settings`). WireMock 3.13.2 serves h2c by default and swallows the body into
        // the upgrade — the journal then records the headers with an empty body, and the
        // delivered PDF would be unobservable. Without h2c WireMock is a plain HTTP/1.1 stub
        // like a real paperless behind nginx/gunicorn, which likewise ignore the probe and
        // read the body. Same diagnosis as the HTTP/1.1 pin in `PaperlessContractTest`: a
        // WireMock limitation, not the module's — the bytes on the wire are identical either
        // way, and pinning anything client-side here is impossible anyway: `run` owns its
        // client and takes no HTTP settings through properties.
        server = WireMockServer(options().dynamicPort().http2PlainDisabled(true))
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    @Test
    fun `BE-05 run uploads three scanned sheets as one three-page PDF (AU-01, AU-02, AU-03, AU-05, DL-03, DL-04, SV-05)`(
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

            val outboxRoot = tempDir.resolve("outbox")
            Files.createDirectories(outboxRoot)

            val contextRef = AtomicReference<ConfigurableApplicationContext>()
            val bootFailure = AtomicReference<Throwable>()
            val unexpectedDeath = AtomicReference<Throwable>()
            val boot =
                thread(start = true, isDaemon = true, name = BOOT_THREAD_NAME) {
                    try {
                        contextRef.set(
                            SpringApplicationBuilder(UnboundAirApplication::class.java)
                                .web(WebApplicationType.NONE)
                                .properties(
                                    "unboundair.scanner.host=127.0.0.1",
                                    "unboundair.scanner.port=${fake.port}",
                                    "unboundair.poll-interval=1s",
                                    "unboundair.offline-poll-interval=1s",
                                    "unboundair.batch-timeout=30s",
                                    "unboundair.outbox.path=$outboxRoot",
                                    "unboundair.output.modules=paperless",
                                    "unboundair.output.paperless.base-url=http://localhost:${server.port()}",
                                    "unboundair.output.paperless.token=$TOKEN",
                                ).run("run"),
                        )
                    } catch (e: Throwable) {
                        bootFailure.set(e)
                    }
                }
            try {
                // All three sheets are picked up by the loop on its own: the
                // tray empties itself and the fake counts the completed scans.
                await().atMost(SCAN_AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.completedScans >= EXPECTED_PAGES }
                // The third scan is counted when its payload leaves the fake,
                // while the loop still has to process it and add it to the
                // batch. The next status poll happens strictly after that, so
                // one more connection proves the page is in the batch and the
                // scanner can go offline without losing it.
                val connectionsAfterScans = fake.connectionCount
                await().atMost(SETTLE_AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.connectionCount > connectionsAfterScans }

                // The device switches itself off: the DL-04 offline trigger
                // closes the batch on its own, landing it in the outbox for
                // the runner to deliver (AU-03) and paperless to answer.
                fake.goOffline()
                await().atMost(UPLOAD_AWAIT_SECONDS, TimeUnit.SECONDS).until { server.allServeEvents.isNotEmpty() }
                if (bootFailure.get() != null) {
                    throw AssertionError("the run command failed before delivering", bootFailure.get())
                }

                // AU-02/AU-03: three sheets are ONE document, so paperless sees
                // exactly ONE upload, not three — with the AU-05 path and auth
                // header, pinned through WireMock's own request journal.
                server.verify(
                    1,
                    postRequestedFor(urlEqualTo(POST_PATH))
                        .withHeader("Authorization", equalTo("Token $TOKEN")),
                )

                val request = server.allServeEvents.single().request
                val part = request.getPart("document")
                assertThat(part)
                    .`as`("the upload carries the PDF as the multipart field 'document' (AU-05)")
                    .isNotNull()
                checkNotNull(part)

                assertThat(part.fileName)
                    .`as`("AU-05 fixes the file name shape scan-JJJJMMTT-HHMMSS.pdf")
                    .matches("scan-\\d{8}-\\d{6}\\.pdf")

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

                terminate(boot, unexpectedDeath)
                boot.join(BOOT_JOIN_MILLIS)
                assertThat(boot.isAlive)
                    .`as`("BE-05: run must terminate once stopped, so a hanging thread fails here")
                    .isFalse()
                assertThat(unexpectedDeath.get())
                    .`as`("no worker may die from anything but the stop signal")
                    .isNull()

                val context =
                    checkNotNull(contextRef.get()) {
                        "the run command returned without handing back its Spring context"
                    }
                assertThat(SpringApplication.exit(context))
                    .`as`("BE-05: an unattended run that delivers its document must exit 0")
                    .isEqualTo(0)
                context.close()
            } finally {
                terminateQuietly(boot, unexpectedDeath)
                runCatching { contextRef.get()?.close() }
            }
        }
    }

    /**
     * Ends the service threads and returns once the boot thread left `run`.
     *
     * The workers are interrupted by name on every poll until the boot thread is gone; a single
     * interrupt could land in socket I/O and merely arm the flag, but every loop turn ends in a
     * sleeper or a client pause that throws on it, so repeated interrupts terminate both threads
     * within a bounded time. Pacing comes from Awaitility's poll interval — never from
     * `Thread.sleep` (docs/entscheidungen.md).
     *
     * The interrupt kills `scan-loop` with an *uncaught* exception (its loop has no shutdown path
     * but `stop`, which only the shutdown hook may call), and Gradle blames an uncaught exception
     * from any thread on the running test. Each worker therefore gets a handler first that
     * swallows exactly the kill signal; anything else is recorded in [unexpectedDeath] and
     * asserted after the termination, so a real defect cannot hide behind the stop.
     */
    private fun terminate(
        boot: Thread,
        unexpectedDeath: AtomicReference<Throwable>,
    ) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        await()
            .atMost(TERMINATE_AWAIT_SECONDS, TimeUnit.SECONDS)
            .pollInterval(TERMINATE_POLL_MILLIS, TimeUnit.MILLISECONDS)
            .until {
                serviceThreads().forEach {
                    it.uncaughtExceptionHandler =
                        Thread.UncaughtExceptionHandler { thread, error ->
                            if (!isKillSignal(error)) {
                                unexpectedDeath.set(error)
                                defaultHandler?.uncaughtException(thread, error)
                            }
                        }
                    it.interrupt()
                }
                !boot.isAlive
            }
    }

    /** Best-effort [terminate] for the `finally` block: never masks the real failure. */
    private fun terminateQuietly(
        boot: Thread,
        unexpectedDeath: AtomicReference<Throwable>,
    ) {
        runCatching { terminate(boot, unexpectedDeath) }
        runCatching { boot.join(BOOT_JOIN_MILLIS) }
    }

    /**
     * Whether [error] is the stop signal rather than a defect: an interrupt surfacing from a
     * sleeper, or the client's pause rethrowing it as an `IllegalStateException` instead of
     * swallowing it.
     */
    private fun isKillSignal(error: Throwable): Boolean =
        error is InterruptedException ||
            (error is IllegalStateException && error.cause is InterruptedException)

    /**
     * The live workers of the `run` command, found by the names `RunCommand` gives them. The
     * names are private there, so this mirrors them — if they are renamed, the termination wait
     * times out and names the coupling instead of hanging silently.
     */
    private fun serviceThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter {
            (it.name == SCAN_LOOP_THREAD_NAME || it.name == OUTBOX_RUNNER_THREAD_NAME) && it.isAlive
        }

    private companion object {
        const val POST_PATH = "/api/documents/post_document/"
        const val TOKEN = "secret-token"

        const val EXPECTED_PAGES = 3
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"
        const val TOLERANCE_POINTS = 1.0f

        val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)

        /** The boot thread running the Spring context with the `run` command. */
        const val BOOT_THREAD_NAME = "run-command-boot"

        /** Mirrors the worker names `RunCommand` assigns (see [serviceThreads]). */
        const val SCAN_LOOP_THREAD_NAME = "scan-loop"
        const val OUTBOX_RUNNER_THREAD_NAME = "outbox-runner"

        /**
         * Generous on purpose (docs/teststrategie.md): three scans take seconds; the
         * bounds below are never exhausted when everything works.
         */
        const val SCAN_AWAIT_SECONDS = 90L
        const val SETTLE_AWAIT_SECONDS = 30L
        const val UPLOAD_AWAIT_SECONDS = 60L

        /** Interrupts always land within one loop turn; 30 s is far beyond that. */
        const val TERMINATE_AWAIT_SECONDS = 30L
        const val TERMINATE_POLL_MILLIS = 50L
        const val BOOT_JOIN_MILLIS = 5_000L
    }
}
