package dev.digiwomb.unboundair.scanner

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ScannerClient] against [FakeScanner], one per requirement SC-01 to SC-07.
 *
 * Every test runs its own [FakeScanner] on the loopback interface. The fake binds port 0, so
 * it always receives a fresh, OS-assigned free port, and the tests never compete with each
 * other or with anything running on a fixed port; the client under test is always built
 * against `127.0.0.1` and the port the fake actually bound (SC-06). Every fake is started
 * in the test body and stopped in a `finally` block, so no test can leak a server thread.
 *
 * The whole class contains exactly one deliberately slow assertion: the hang case in SC-02,
 * where the client's read times out after the 10 second normal timeout. The
 * `ScannerTimeoutException` case of SC-05 refers to it instead of paying the 10 seconds a
 * second time.
 */
class ScannerClientTest {
    @Test
    fun `SC-01 the payload arrives byte-identical through the whole flow`() {
        val fake = FakeScanner()
        // A deterministic 5000-byte pattern that is not a real JPEG: whatever the client
        // returns must be exactly these bytes.
        fake.payload = ByteArray(5000) { index -> (index * 29 + 3).toByte() }
        fake.start()
        try {
            val bytes = clientFor(fake).scan(300)

            assertArrayEquals(fake.payload, bytes, "the scanned page must arrive byte-identical")

            val expectedCommands = listOf("status", "version", "dpi300", "scan", "jpegsize", "jpegdata")
            assertEquals(expectedCommands, fake.receivedCommands, "the whole flow must arrive in this order")
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `SC-02 one connection per operation and a read timeout is mapped`() {
        val fake = FakeScanner()
        fake.start()
        try {
            val client = clientFor(fake)

            client.queryStatus()
            assertEquals(1, fake.connectionCount, "one status query must open exactly one connection")

            client.scan(300)
            assertEquals(2, fake.connectionCount, "a second operation must open exactly one more connection")
        } finally {
            fake.stop()
        }

        // A hanging scanner accepts the connection but never answers, so the client's read
        // times out after the 10 second normal timeout. This assertion takes about 10
        // seconds; it is the only timeout wait in this class, and the
        // ScannerTimeoutException case of SC-05 points here instead of repeating it.
        val hangingFake = FakeScanner()
        hangingFake.hang = true
        hangingFake.start()
        try {
            val client = clientFor(hangingFake)
            assertThrows(ScannerTimeoutException::class.java) {
                client.scan(300)
            }
        } finally {
            hangingFake.stop()
        }
    }

    @Test
    fun `SC-03 padded status answers are recognized by prefix`() {
        val fake = FakeScanner()
        fake.start()
        try {
            // fillBytes stays at its default (true): the fake pads every answer to 11 bytes
            // (word + NUL padding + trailing H), exactly like the device, and the client
            // must still read back the word.
            val client = clientFor(fake)
            for (word in listOf("scanready", "nopaper", "devbusy", "battlow")) {
                fake.statusWord = word
                assertEquals(word, client.queryStatus(), "the padded answer must be recognized as '$word'")
            }
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `SC-04 a split jpegsize answer is assembled until 12 bytes arrived`() {
        val fake = FakeScanner()
        // Like the device, the fake splits the 12-byte jpegsize answer into two TCP
        // segments (10 bytes, short pause, 2 bytes).
        fake.splitJpegsize = true
        fake.payload = ByteArray(1234) { index -> (index * 31 + 7).toByte() }
        fake.start()
        try {
            val bytes = clientFor(fake).scan(300)

            // A byte-identical 1234-byte payload is the proof that the client assembled
            // the split size answer and parsed the size from it.
            assertEquals(fake.payload.size, bytes.size, "the size parsed from jpegsize must match the payload")
            assertArrayEquals(fake.payload, bytes, "the payload must arrive untouched")
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `SC-05 every scanner failure raises its own exception`() {
        // ScannerOfflineException: a stopped scanner refuses the connection.
        val offlineFake = FakeScanner()
        offlineFake.start()
        try {
            offlineFake.stop()
            val client = clientFor(offlineFake)
            assertThrows(ScannerOfflineException::class.java) {
                client.queryStatus()
            }
        } finally {
            offlineFake.stop() // idempotent
        }

        // ScannerBusyException: the scanner answers devbusy.
        val busyFake = FakeScanner()
        busyFake.statusWord = "devbusy"
        busyFake.start()
        try {
            val client = clientFor(busyFake)
            assertThrows(ScannerBusyException::class.java) {
                client.scan(300)
            }
        } finally {
            busyFake.stop()
        }

        // ScannerNoPaperException: the scanner answers nopaper.
        val noPaperFake = FakeScanner()
        noPaperFake.statusWord = "nopaper"
        noPaperFake.start()
        try {
            val client = clientFor(noPaperFake)
            assertThrows(ScannerNoPaperException::class.java) {
                client.scan(300)
            }
        } finally {
            noPaperFake.stop()
        }

        // ScannerBatteryLowException: the scanner answers battlow.
        val batteryFake = FakeScanner()
        batteryFake.statusWord = "battlow"
        batteryFake.start()
        try {
            val client = clientFor(batteryFake)
            assertThrows(ScannerBatteryLowException::class.java) {
                client.scan(300)
            }
        } finally {
            batteryFake.stop()
        }

        // ScannerProtocolException: an unknown status word is a protocol violation.
        val protocolFake = FakeScanner()
        protocolFake.statusWord = "garbage"
        protocolFake.start()
        try {
            val client = clientFor(protocolFake)
            assertThrows(ScannerProtocolException::class.java) {
                client.queryStatus()
            }
        } finally {
            protocolFake.stop()
        }

        // ScannerTimeoutException: asserted in the SC-02 test, where a hanging scanner
        // makes the client's read time out after 10 seconds. Deliberately not repeated
        // here: this class pays for that wait exactly once.
    }

    @Test
    fun `SC-06 host and port are configurable with the shipped defaults`() {
        assertEquals("192.168.18.33", ScannerClient.DEFAULT_HOST, "shipped device address")
        assertEquals(23, ScannerClient.DEFAULT_PORT, "shipped device port")

        val fake = FakeScanner()
        fake.start()
        try {
            // host and port are plain constructor parameters, so pointing the client at
            // the fake needs no code change, only the free port the fake bound itself to.
            val client = ScannerClient("127.0.0.1", fake.port)
            assertEquals("scanready", client.queryStatus())
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `SC-07 the firmware number decides whether 600 dpi is actually sent`() {
        val warnings = mutableListOf<String>()

        // NB0a.032: firmware number 32 (26 or newer) -> the client switches to 600 dpi.
        val capableFake = FakeScanner()
        capableFake.version = "NB0a.032"
        capableFake.start()
        try {
            val client = ScannerClient("127.0.0.1", capableFake.port) { warnings += it }
            val bytes = client.scan(600)

            assertArrayEquals(capableFake.payload, bytes, "the scan must succeed")
            assertTrue(capableFake.receivedCommands.contains("dpi600"), "a capable firmware allows 600 dpi")
            assertFalse(capableFake.receivedCommands.contains("dpi300"), "no 300 dpi fallback when 600 is granted")
            assertTrue(warnings.isEmpty(), "no warning for a capable firmware")
        } finally {
            capableFake.stop()
        }

        // NB0a.020: firmware number 20 (older than 26) -> the client stays at 300 dpi and warns.
        val outdatedFake = FakeScanner()
        outdatedFake.version = "NB0a.020"
        outdatedFake.start()
        try {
            val client = ScannerClient("127.0.0.1", outdatedFake.port) { warnings += it }
            val bytes = client.scan(600)

            assertArrayEquals(outdatedFake.payload, bytes, "the scan must succeed at 300 dpi")
            assertTrue(outdatedFake.receivedCommands.contains("dpi300"), "an old firmware falls back to 300 dpi")
            assertFalse(outdatedFake.receivedCommands.contains("dpi600"), "600 dpi must not be sent for old firmware")
            assertTrue(warnings.isNotEmpty(), "the fallback must warn")
            assertTrue(warnings.any { it.contains("600 dpi") }, "the warning must mention the 600 dpi fallback")
        } finally {
            outdatedFake.stop()
        }
    }

    /**
     * The test client for a given [fake]: the loopback host and the port the fake bound,
     * with the default no-op warning sink.
     *
     * Only the fixed address is centralised here; the fake's lifecycle (start in the test
     * body, stop in a finally block) stays visible in every test, because one fresh fake
     * per test is part of what the tests show.
     */
    private fun clientFor(fake: FakeScanner): ScannerClient = ScannerClient(host = "127.0.0.1", port = fake.port)
}
