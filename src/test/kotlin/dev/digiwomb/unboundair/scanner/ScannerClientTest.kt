package dev.digiwomb.unboundair.scanner

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
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

            assertThat(bytes)
                .`as`("the scanned page must arrive byte-identical")
                .isEqualTo(fake.payload)

            val expectedCommands = listOf("status", "version", "dpi300", "scan", "jpegsize", "jpegdata")
            assertThat(fake.receivedCommands)
                .`as`("the whole flow must arrive in this order")
                .isEqualTo(expectedCommands)
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
            assertThat(fake.connectionCount)
                .`as`("one status query must open exactly one connection")
                .isEqualTo(1)

            client.scan(300)
            assertThat(fake.connectionCount)
                .`as`("a second operation must open exactly one more connection")
                .isEqualTo(2)
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
            assertThatThrownBy { client.scan(300) }
                .isInstanceOf(ScannerTimeoutException::class.java)
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
                assertThat(client.queryStatus())
                    .`as`("the padded answer must be recognized as '$word'")
                    .isEqualTo(word)
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
            assertThat(bytes.size)
                .`as`("the size parsed from jpegsize must match the payload")
                .isEqualTo(fake.payload.size)
            assertThat(bytes)
                .`as`("the payload must arrive untouched")
                .isEqualTo(fake.payload)
        } finally {
            fake.stop()
        }
    }

    /**
     * Every scanner failure raises its own exception (SC-05).
     *
     * The `ScannerTimeoutException` case is not repeated here: it is asserted in the
     * SC-02 test, where a hanging scanner makes the client's read time out after
     * 10 seconds, and this class pays for that wait exactly once.
     */
    @Nested
    inner class ScannerFailures {
        @Test
        fun `SC-05 offline is mapped to ScannerOfflineException`() {
            val fake = FakeScanner()
            fake.start()
            try {
                fake.stop()
                val client = clientFor(fake)
                assertThatThrownBy { client.queryStatus() }
                    .isInstanceOf(ScannerOfflineException::class.java)
            } finally {
                fake.stop() // idempotent
            }
        }

        @Test
        fun `SC-05 devbusy is mapped to ScannerBusyException`() {
            val fake = FakeScanner()
            fake.statusWord = "devbusy"
            fake.start()
            try {
                val client = clientFor(fake)
                assertThatThrownBy { client.scan(300) }
                    .isInstanceOf(ScannerBusyException::class.java)
            } finally {
                fake.stop()
            }
        }

        @Test
        fun `SC-05 nopaper is mapped to ScannerNoPaperException`() {
            val fake = FakeScanner()
            fake.statusWord = "nopaper"
            fake.start()
            try {
                val client = clientFor(fake)
                assertThatThrownBy { client.scan(300) }
                    .isInstanceOf(ScannerNoPaperException::class.java)
            } finally {
                fake.stop()
            }
        }

        @Test
        fun `SC-05 battlow is mapped to ScannerBatteryLowException`() {
            val fake = FakeScanner()
            fake.statusWord = "battlow"
            fake.start()
            try {
                val client = clientFor(fake)
                assertThatThrownBy { client.scan(300) }
                    .isInstanceOf(ScannerBatteryLowException::class.java)
            } finally {
                fake.stop()
            }
        }

        @Test
        fun `SC-05 an unknown status word is mapped to ScannerProtocolException`() {
            val fake = FakeScanner()
            fake.statusWord = "garbage"
            fake.start()
            try {
                val client = clientFor(fake)
                assertThatThrownBy { client.queryStatus() }
                    .isInstanceOf(ScannerProtocolException::class.java)
            } finally {
                fake.stop()
            }
        }
    }

    @Test
    fun `SC-06 host and port are configurable with the shipped defaults`() {
        assertThat(ScannerClient.DEFAULT_HOST)
            .`as`("shipped device address")
            .isEqualTo("192.168.18.33")
        assertThat(ScannerClient.DEFAULT_PORT)
            .`as`("shipped device port")
            .isEqualTo(23)

        val fake = FakeScanner()
        fake.start()
        try {
            // host and port are plain constructor parameters, so pointing the client at
            // the fake needs no code change, only the free port the fake bound itself to.
            val client = ScannerClient("127.0.0.1", fake.port)
            assertThat(client.queryStatus()).isEqualTo("scanready")
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

            assertThat(bytes)
                .`as`("the scan must succeed")
                .isEqualTo(capableFake.payload)
            assertThat(capableFake.receivedCommands)
                .`as`("a capable firmware allows 600 dpi")
                .contains("dpi600")
            assertThat(capableFake.receivedCommands)
                .`as`("no 300 dpi fallback when 600 is granted")
                .doesNotContain("dpi300")
            assertThat(warnings)
                .`as`("no warning for a capable firmware")
                .isEmpty()
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

            assertThat(bytes)
                .`as`("the scan must succeed at 300 dpi")
                .isEqualTo(outdatedFake.payload)
            assertThat(outdatedFake.receivedCommands)
                .`as`("an old firmware falls back to 300 dpi")
                .contains("dpi300")
            assertThat(outdatedFake.receivedCommands)
                .`as`("600 dpi must not be sent for old firmware")
                .doesNotContain("dpi600")
            assertThat(warnings)
                .`as`("the fallback must warn")
                .isNotEmpty()
            assertThat(warnings)
                .`as`("the warning must mention the 600 dpi fallback")
                .anySatisfy { assertThat(it).contains("600 dpi") }
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
