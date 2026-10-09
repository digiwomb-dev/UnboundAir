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
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
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
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/**
 * The fixed page size end to end through the real entry point (SV-09, issue #296):
 * `run` with `unboundair.page-size=a4` against a FakeScanner and a WireMock paperless
 * delivers one A4 page whose content is the scan, centred and unscaled.
 *
 * The chain is the same as in [ServiceEndToEndTest]:
 *
 * ```
 * run -> FakeScanner -> ScanLoop -> Batch -> outbox -> paperless module -> WireMock
 * ```
 *
 * What this test proves over the unit-level [dev.digiwomb.unboundair.output.PdfPageSizeTest]:
 * the page size travels from the Spring property (`UNBOUNDAIR_PAGESIZE`, bound to
 * `unboundair.page-size`, parsed by `TargetPageSize` in the `run` branch of
 * `UnboundAirApplication`) into the delivered document. A mistake in the property
 * mapping would leave the unit test green and the shipped service on scan-sized
 * pages; here the thing the user starts is the thing the test starts. No `--page-size`
 * flag is passed anywhere (`parseCliArgs` rejects every `--`-prefixed token, so only
 * the command itself travels through `argv`).
 *
 * Three assertions on the PDF read back out of the multipart body WireMock actually
 * received, not off a local file:
 *
 * - the page box is A4 (210 x 297 mm in points);
 * - the content keeps its physical size (`pixels / dpi * 72`, SV-05) and is centred
 *   into the box via the `cm` matrix (positive offsets, no scaling);
 * - the embedded raw stream is byte-identical to the scanned input (never
 *   recompressed, SV-09): the A4 fixture fills the scan, so `CropStep` passes it
 *   through without running `jpegtran`, and `color-mode=color` carries it through
 *   `GrayscaleStep` and `MonochromeStep` untouched.
 *
 * The same deliberate choices as the sister test apply: the batch closes through its
 * normal offline trigger (DL-04) — the tray empties, the loop turns once more, the test
 * switches the fake off via `goOffline` — and stopping `run` interrupts the worker
 * threads by name until the boot thread returns. `run` owns a real clock, so no clock
 * is injected or manipulated; every wait in this test is an Awaitility poll, never a
 * `Thread.sleep`, and the termination pins that no service thread survives (a leaked
 * `scan-loop` or `outbox-runner` would make a later unrelated test flaky).
 *
 * Offline (DC-03): loopback TCP to the fake scanner, WireMock on a dynamic localhost
 * port, committed fixtures, the dev container's `jpegtran`. Nothing reaches the internet.
 */
class PageSizeEndToEndTest {
    private lateinit var server: WireMockServer

    @BeforeEach
    fun startServer() {
        // h2c disabled on purpose, same diagnosis as in ServiceEndToEndTest: `run`
        // uploads with the production default `RestClient`, whose JDK client sends the
        // HTTP/2 upgrade probe, and WireMock 3.13.2 with h2c enabled swallows the body
        // into the upgrade — the journal then records an empty body and the delivered
        // PDF would be unobservable.
        server = WireMockServer(options().dynamicPort().http2PlainDisabled(true))
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop()
    }

    @Test
    fun `SV-09 run with page-size a4 delivers the scan centred and unscaled on an A4 page with identical image bytes`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            val scanned = TestImages.bytes(A4_FIXTURE)
            fake.loadSheets(listOf(scanned))
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
                                    // SV-09 through Spring properties alone: no flag exists.
                                    "unboundair.page-size=a4",
                                    // Keeps every processing step a pass-through, so the
                                    // embedded stream must equal the scanned bytes.
                                    "unboundair.color-mode=color",
                                ).run("run"),
                        )
                    } catch (e: Throwable) {
                        bootFailure.set(e)
                    }
                }
            try {
                // The sheet is picked up by the loop on its own: the tray empties
                // itself and the fake counts the completed scan.
                await().atMost(SCAN_AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.completedScans >= EXPECTED_PAGES }
                // The scan is counted when its payload leaves the fake, while the
                // loop still has to process it and add it to the batch. The next
                // status poll happens strictly after that, so one more connection
                // proves the page is in the batch and the scanner can go offline
                // without losing it.
                val connectionsAfterScans = fake.connectionCount
                await().atMost(SETTLE_AWAIT_SECONDS, TimeUnit.SECONDS).until { fake.connectionCount > connectionsAfterScans }

                // The device switches itself off: the DL-04 offline trigger closes
                // the batch on its own, landing it in the outbox for the runner to
                // deliver (AU-03) and paperless to answer.
                fake.goOffline()
                await().atMost(UPLOAD_AWAIT_SECONDS, TimeUnit.SECONDS).until { server.allServeEvents.isNotEmpty() }
                if (bootFailure.get() != null) {
                    throw AssertionError("the run command failed before delivering", bootFailure.get())
                }

                // One sheet is ONE document: paperless sees exactly ONE upload.
                server.verify(
                    1,
                    postRequestedFor(urlEqualTo(POST_PATH))
                        .withHeader("Authorization", equalTo("Token $TOKEN")),
                )

                val request = server.allServeEvents.single().request
                val part = request.getPart("document")
                assertThat(part)
                    .`as`("the upload carries the PDF as the multipart field 'document'")
                    .isNotNull()
                checkNotNull(part)

                val pdfBytes = part.body.asBytes()
                assertThat(pdfBytes)
                    .`as`("the uploaded document part is a real PDF, read off the wire as bytes")
                    .startsWith(*PDF_MAGIC)

                Loader.loadPDF(pdfBytes).use { pdf ->
                    assertThat(pdf.numberOfPages)
                        .`as`("SV-09: one sheet in, one page uploaded")
                        .isEqualTo(EXPECTED_PAGES)

                    // The A4 target in points, exactly as TargetPageSize computes it.
                    val expectedWidth = 210f * 72f / 25.4f
                    val expectedHeight = 297f * 72f / 25.4f
                    val box = pdf.getPage(0).mediaBox
                    assertThat(box.width)
                        .`as`("SV-09: the page box is the configured target, 210 mm in points")
                        .isCloseTo(expectedWidth, within(TOLERANCE_POINTS))
                    assertThat(box.height)
                        .`as`("SV-09: the page box is the configured target, 297 mm in points")
                        .isCloseTo(expectedHeight, within(TOLERANCE_POINTS))

                    // The physical content size comes from the scanned pixels, not
                    // from a constant: pixels / dpi * 72 (SV-05).
                    val (pixelsWide, pixelsHigh) = pixelSize(scanned)
                    val contentWidth = pixelsWide.toFloat() / DPI * 72f
                    val contentHeight = pixelsHigh.toFloat() / DPI * 72f
                    val matrix = imageMatrix(pdf, 0)
                    assertThat(matrix[0])
                        .`as`("SV-09: the content keeps its physical width, not enlarged to fill A4")
                        .isCloseTo(contentWidth, within(TOLERANCE_POINTS))
                    assertThat(matrix[3])
                        .`as`("SV-09: the content keeps its physical height, not enlarged to fill A4")
                        .isCloseTo(contentHeight, within(TOLERANCE_POINTS))
                    assertThat(matrix[4].toDouble())
                        .`as`("SV-09: the smaller content is centred with a white margin on x")
                        .isGreaterThan(0.0)
                    assertThat(matrix[5].toDouble())
                        .`as`("SV-09: the smaller content is centred with a white margin on y")
                        .isGreaterThan(0.0)
                    assertThat(matrix[4])
                        .`as`("SV-09: the x offset centres the content: (box - content) / 2")
                        .isCloseTo((expectedWidth - contentWidth) / 2f, within(TOLERANCE_POINTS))
                    assertThat(matrix[5])
                        .`as`("SV-09: the y offset centres the content: (box - content) / 2")
                        .isCloseTo((expectedHeight - contentHeight) / 2f, within(TOLERANCE_POINTS))

                    assertThat(embeddedRawBytes(pdf, 0))
                        .`as`("SV-09: the fixed box must not touch the image bytes: no recompression, not one byte")
                        .isEqualTo(scanned)
                }

                terminate(boot, unexpectedDeath)
                boot.join(BOOT_JOIN_MILLIS)
                assertThat(boot.isAlive)
                    .`as`("run must terminate once stopped, so a hanging thread fails here")
                    .isFalse()
                assertThat(serviceThreads())
                    .`as`("no scan-loop or outbox-runner thread may survive the stop")
                    .isEmpty()
                assertThat(unexpectedDeath.get())
                    .`as`("no worker may die from anything but the stop signal")
                    .isNull()

                val context =
                    checkNotNull(contextRef.get()) {
                        "the run command returned without handing back its Spring context"
                    }
                assertThat(SpringApplication.exit(context))
                    .`as`("an unattended run that delivers its document must exit 0")
                    .isEqualTo(0)
                context.close()
            } finally {
                terminateQuietly(boot, unexpectedDeath)
                runCatching { contextRef.get()?.close() }
            }
        }
    }

    /**
     * Extracts the raw, still-encoded image stream of the image on [pageIndex].
     *
     * `createRawInputStream` yields the bytes exactly as they sit in the PDF,
     * *without* running the DCTDecode filter — `createInputStream` would decode
     * the stream, so the comparison would be against pixel data and a
     * recompressing pipeline could still pass.
     */
    private fun embeddedRawBytes(
        document: PDDocument,
        pageIndex: Int,
    ): ByteArray {
        val resources = document.getPage(pageIndex).resources
        val name = resources.xObjectNames.first { resources.getXObject(it) is PDImageXObject }
        val image = resources.getXObject(name) as PDImageXObject
        return image.cosObject.createRawInputStream().use { it.readBytes() }
    }

    /**
     * Reads the single `cm` matrix placing the image on the page: `a b c d e f`,
     * where `a`/`d` are the drawn width/height in points and `e`/`f` the offset
     * of the image origin. With one image per page there is exactly one `cm`.
     */
    private fun imageMatrix(
        document: PDDocument,
        pageIndex: Int,
    ): FloatArray {
        val page = document.getPage(pageIndex)
        val contents = page.contents ?: throw AssertionError("expected a content stream for the page")
        val text = contents.readBytes().toString(Charsets.US_ASCII)
        val matches =
            Regex("""([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+cm""")
                .findAll(text)
                .toList()
        assertThat(matches)
            .`as`("one image per page is drawn with exactly one cm placement, no extra transforms")
            .hasSize(1)
        return FloatArray(6) { index -> matches[0].groupValues[index + 1].toFloat() }
    }

    /** Pixel dimensions of the scanned JPEG, decoded locally (offline, DC-03). */
    private fun pixelSize(jpeg: ByteArray): Pair<Int, Int> {
        val image =
            ByteArrayInputStream(jpeg).use { ImageIO.read(it) }
                ?: throw AssertionError("the scanned fixture is not a readable image")
        return image.width to image.height
    }

    /**
     * Ends the service threads and returns once the boot thread left `run`.
     *
     * The workers are interrupted by name on every poll until the boot thread is gone; a single
     * interrupt could land in socket I/O and merely arm the flag, but every loop turn ends in a
     * sleeper or a client pause that throws on it, so repeated interrupts terminate both threads
     * within a bounded time. Pacing comes from Awaitility's poll interval — never from
     * `Thread.sleep`.
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

        const val EXPECTED_PAGES = 1
        const val A4_FIXTURE = "din_a4_300dpi_raw.jpg"
        const val DPI = 300
        const val TOLERANCE_POINTS = 0.05f

        val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)

        /** The boot thread running the Spring context with the `run` command. */
        const val BOOT_THREAD_NAME = "run-command-boot"

        /** Mirrors the worker names `RunCommand` assigns (see [serviceThreads]). */
        const val SCAN_LOOP_THREAD_NAME = "scan-loop"
        const val OUTBOX_RUNNER_THREAD_NAME = "outbox-runner"

        /**
         * Generous on purpose: one scan takes seconds, and each status poll costs
         * ~0.7 s in mandated pauses regardless of the interval; the bounds below
         * are never exhausted when everything works.
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
