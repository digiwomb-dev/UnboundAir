package dev.digiwomb.unboundair.scanner

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests for the sequencing abilities of the [FakeScanner] (TE-01 extension).
 *
 * The fake is test infrastructure, so testing it needs a word of justification.
 * Everything milestone 3 still has to prove -- the polling loop (DL-01 to
 * DL-06), the batch (DL-03, DL-04) and the measuring run (BE-04) -- rests on
 * this device changing over time in exactly the way the real one does. If the
 * fake is subtly wrong, those tests do not fail: they pass while proving
 * something else. The sheet tray, the scripted statuses and the reversible
 * offline are therefore pinned here in their own right, before anything is
 * built on them.
 *
 * Offline (DC-03): a loopback TCP server, nothing else.
 */
class FakeScannerSequenceTest {
    /**
     * The acceptance criterion of the issue: three pages, a `devbusy` answer
     * and an offline, played in sequence without restarting the fake.
     */
    @Test
    fun `TE-01 three pages, a devbusy and an offline play in one session`() {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(page(1, 400), page(2, 500), page(3, 600)))
            fake.scriptStatuses("devbusy")
            fake.start()
            val client = ScannerClient("127.0.0.1", fake.port)

            // The scripted answer comes first and is consumed by one poll.
            assertThat(client.queryStatus())
                .`as`("the scripted devbusy must be served before the tray is consulted (OF-02)")
                .isEqualTo("devbusy")

            // Then the tray takes over: three sheets, each scanned exactly once.
            val scanned = mutableListOf<ByteArray>()
            repeat(3) {
                assertThat(client.queryStatus())
                    .`as`("a waiting sheet must be announced as scanready")
                    .isEqualTo("scanready")
                scanned.add(client.scan(300).bytes)
            }

            assertThat(scanned.map { it.size })
                .`as`("each sheet must be delivered once and in order, not the same one three times")
                .containsExactly(400, 500, 600)
            assertThat(client.queryStatus())
                .`as`("an empty tray must answer nopaper, otherwise a loop would scan forever")
                .isEqualTo("nopaper")
            assertThat(fake.completedScans).isEqualTo(3)

            // Finally the device switches itself off (OF-01 auto-off).
            fake.goOffline()
            assertThatThrownBy { client.queryStatus() }
                .`as`("an offline device must be unreachable for the client (DL-02)")
                .isInstanceOf(ScannerOfflineException::class.java)
        }
    }

    @Test
    fun `TE-01 the device comes back on the same port after going offline`() {
        FakeScanner().use { fake ->
            fake.statusWord = "nopaper"
            fake.start()
            val port = fake.port
            val client = ScannerClient("127.0.0.1", port)
            assertThat(client.queryStatus()).isEqualTo("nopaper")

            fake.goOffline()
            assertThat(fake.isOffline).isTrue()
            assertThatThrownBy { client.queryStatus() }
                .isInstanceOf(ScannerOfflineException::class.java)

            fake.comeOnline()

            assertThat(fake.port)
                .`as`("the port must not change, or the client would be pointing at the wrong address")
                .isEqualTo(port)
            assertThat(client.queryStatus())
                .`as`("the same client instance must reach the device again, without being reconfigured")
                .isEqualTo("nopaper")
        }
    }

    @Test
    fun `TE-01 a sheet inserted while running joins the queue`() {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(page(1, 400)))
            fake.start()
            val client = ScannerClient("127.0.0.1", fake.port)

            client.scan(300)
            assertThat(client.queryStatus())
                .`as`("the tray is empty after the only sheet was scanned")
                .isEqualTo("nopaper")

            fake.insertSheet(page(2, 700))

            assertThat(client.queryStatus())
                .`as`("laying a new sheet on a running device must make it scan-ready again (DL-03)")
                .isEqualTo("scanready")
            assertThat(client.scan(300).bytes.size).isEqualTo(700)
        }
    }

    @Test
    fun `TE-01 the scripted statuses are consumed one per poll and then the tray decides`() {
        FakeScanner().use { fake ->
            fake.loadSheets(listOf(page(1, 400)))
            fake.scriptStatuses("devbusy", "devbusy")
            fake.start()
            val client = ScannerClient("127.0.0.1", fake.port)

            assertThat(listOf(client.queryStatus(), client.queryStatus()))
                .`as`("each scripted entry must be consumed by exactly one status command")
                .containsExactly("devbusy", "devbusy")
            assertThat(client.queryStatus())
                .`as`("once the script is exhausted the tray decides again")
                .isEqualTo("scanready")
        }
    }

    /**
     * The tray is opt-in. Every single-shot test written before this extension
     * sets [FakeScanner.statusWord] and [FakeScanner.payload] directly, and must
     * keep working untouched.
     */
    @Test
    fun `TE-01 without sheets the status word and payload fields still rule`() {
        FakeScanner().use { fake ->
            fake.statusWord = "battlow"
            fake.payload = page(9, 123)
            fake.start()
            val client = ScannerClient("127.0.0.1", fake.port)

            assertThat(client.queryStatus())
                .`as`("an unused tray must not override the plain statusWord field")
                .isEqualTo("battlow")
        }
    }

    /**
     * Each page carries a distinct size and a distinct fill byte, so a test can
     * tell which sheet it received. Identical payloads would let a loop that
     * rescans one sheet pass unnoticed.
     */
    private fun page(
        marker: Int,
        size: Int,
    ): ByteArray = ByteArray(size) { marker.toByte() }
}
