package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.scanner.FakeScanner
import dev.digiwomb.unboundair.scanner.ScannerClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Integration test for the measuring run (BE-04).
 *
 * The acceptance criterion of BE-04 is precise: against a fake scanner with
 * several pages, `devbusy` and an offline, the summary must contain the page
 * count, the gaps, the `devbusy` count and the offline time -- **and no
 * document may reach an output module**.
 *
 * Each of those is one assertion below, and the last one is the easiest to get
 * wrong. `measure` drives the same [dev.digiwomb.unboundair.service.ScanLoop]
 * the service uses, so it necessarily produces documents; the point is that it
 * throws them away. A measuring run that quietly filed its test scans into
 * someone's archive would be a genuinely bad failure, and nothing but a test
 * prevents it.
 *
 * The numbers this command reports exist to answer OF-01 to OF-04 in
 * `docs/internal/offene-fragen.md`. The test therefore checks that the report *names*
 * those questions, so the output stays traceable to what it was built for.
 *
 * Offline (DC-03): a loopback TCP server and committed fixtures.
 */
class MeasureCommandIntegrationTest {
    @Test
    fun `BE-04 the summary reports pages, gaps, devbusy and the offline time`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            // Two devbusy answers before the sheets: OF-02 asks how often a
            // short poll interval provokes them.
            fake.scriptStatuses("devbusy", "devbusy")
            fake.loadSheets(
                listOf(
                    TestImages.bytes(ENVELOPE),
                    TestImages.bytes(A4),
                    TestImages.bytes(ENVELOPE),
                ),
            )
            fake.start()

            val command =
                MeasureCommand(
                    client = ScannerClient("127.0.0.1", fake.port),
                    clock = Clock.systemUTC(),
                    pollInterval = POLL_INTERVAL,
                    offlinePollInterval = POLL_INTERVAL,
                )

            // The device switches itself off once the sheets are through, the
            // way the real one does after five minutes (OF-01).
            val switchOff =
                thread(start = true) {
                    while (fake.completedScans < EXPECTED_PAGES) {
                        Thread.sleep(POLL_MILLIS)
                    }
                    fake.goOffline()
                }

            val report =
                command.run(
                    workDir = tempDir.resolve("work"),
                    duration = RUN_LIMIT,
                    stopAfterOffline = STOP_AFTER_OFFLINE,
                )
            switchOff.join(JOIN_MILLIS)

            assertThat(report.pages)
                .`as`("BE-04: the summary must report how many pages were scanned")
                .isEqualTo(EXPECTED_PAGES)
            assertThat(report.gaps)
                .`as`("BE-04: the gaps between pages are what the batch-timeout default is derived from (OF-03)")
                .hasSize(EXPECTED_PAGES - 1)
            assertThat(report.averageGap)
                .`as`("with gaps present, the average must be computed")
                .isNotNull()
            assertThat(report.devbusyCount)
                .`as`("BE-04: the devbusy answers must be counted (OF-02)")
                .isEqualTo(2)
            assertThat(report.offlineAt)
                .`as`("BE-04: the moment the device disappeared must be recorded (OF-01)")
                .isNotNull()
            assertThat(report.quietBeforeOffline)
                .`as`("OF-01 turns on how long nothing happened before the device switched itself off")
                .isNotNull()
        }
    }

    /**
     * The explicit prohibition of BE-04.
     */
    @Test
    fun `BE-04 no document reaches an output module`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE), TestImages.bytes(A4)))
            fake.start()

            val command =
                MeasureCommand(
                    client = ScannerClient("127.0.0.1", fake.port),
                    clock = Clock.systemUTC(),
                    pollInterval = POLL_INTERVAL,
                    offlinePollInterval = POLL_INTERVAL,
                )

            val switchOff =
                thread(start = true) {
                    while (fake.completedScans < 2) {
                        Thread.sleep(POLL_MILLIS)
                    }
                    fake.goOffline()
                }

            val work = tempDir.resolve("work")
            val report = command.run(work, RUN_LIMIT, STOP_AFTER_OFFLINE)
            switchOff.join(JOIN_MILLIS)

            assertThat(report.pages)
                .`as`("precondition: pages really were scanned, so the absence below means something")
                .isEqualTo(2)

            // The claim is asserted against the file system, not against a
            // collection the test controls. `measure` drives the same loop the
            // service does, so a document *is* assembled internally; what must
            // not happen is that it leaves the command. An assertion on a
            // locally created empty list would have proved nothing, because
            // nothing could ever have added to it.
            //
            // Everything the run produced has to live under the working
            // directory the caller named, and nowhere else. The dispatcher
            // deletes that directory afterwards (BE-04: the scans are a
            // by-product of the measurement, not something anyone keeps).
            assertThat(tempDir)
                .`as`("no output may be written outside the working directory the caller passed in")
                .isDirectoryContaining { it.fileName.toString() == "work" }
            assertThat(Files.list(tempDir).use { it.toList() })
                .`as`("exactly one entry: the working directory, nothing beside it")
                .hasSize(1)
        }
    }

    /**
     * The report is the command's answer, printed for a human to copy into
     * `docs/de/hardware.md`. It names the open questions so the numbers stay
     * traceable to what they were collected for.
     */
    @Test
    fun `BE-04 the formatted summary names the open questions it answers`(
        @TempDir tempDir: Path,
    ) {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(TestImages.bytes(ENVELOPE)))
            fake.start()

            val command =
                MeasureCommand(
                    client = ScannerClient("127.0.0.1", fake.port),
                    clock = Clock.systemUTC(),
                    pollInterval = POLL_INTERVAL,
                    offlinePollInterval = POLL_INTERVAL,
                )

            val switchOff =
                thread(start = true) {
                    while (fake.completedScans < 1) {
                        Thread.sleep(POLL_MILLIS)
                    }
                    fake.goOffline()
                }

            val text = command.run(tempDir.resolve("work"), RUN_LIMIT, STOP_AFTER_OFFLINE).format()
            switchOff.join(JOIN_MILLIS)

            assertThat(text)
                .`as`("the summary must be readable and traceable to the questions of docs/internal/offene-fragen.md")
                .contains("pages scanned:")
                .contains("devbusy answers:")
                .contains("OF-01")
                .contains("OF-02")
                .contains("OF-03")
                .contains("OF-04")
        }
    }

    private companion object {
        val POLL_INTERVAL: Duration = Duration.ofMillis(10)

        /** An upper bound only; the run ends when the device stays offline. */
        val RUN_LIMIT: Duration = Duration.ofMinutes(2)

        /** Short, so the test does not wait out the production grace period. */
        val STOP_AFTER_OFFLINE: Duration = Duration.ofMillis(200)

        const val EXPECTED_PAGES = 3
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"
        const val POLL_MILLIS = 20L
        const val JOIN_MILLIS = 30_000L
    }
}
