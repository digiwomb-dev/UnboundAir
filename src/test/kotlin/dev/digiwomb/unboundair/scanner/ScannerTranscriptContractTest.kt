package dev.digiwomb.unboundair.scanner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Contract tests (layer "contract" in docs/teststrategie.md) that pin the FakeScanner
 * to the real device protocol (SC-01..SC-04): a real [ScannerClient] session against
 * the fake must produce a transcript that is byte-equal (hex) to the committed golden
 * transcript files under [golden/transcripts].
 *
 * The contract covers:
 * - SC-01: byte-exact protocol fidelity (commands, answers, fill bytes)
 * - SC-02: one operation = one TCP connection
 * - SC-03: answer format with fill bytes (11-byte padded answers ending with 'H')
 * - SC-04: jpegsize answer structure (8-byte word + 4-byte little-endian size)
 *
 * Golden transcript format: one entry per line
 * `connection|direction|hex|decoded`
 *
 * Where:
 * - `connection`: 1-based TCP connection number
 * - `direction`: `FROM_CLIENT` or `TO_CLIENT`
 * - `hex`: lowercase hex of the exact bytes
 * - `decoded`: command/answer word (status, version, dpi300, scan, jpegsize, jpegdata, etc.)
 *
 * The golden transcripts were recorded with the defaults:
 * - `fillBytes = true`
 * - Default payload (3000-byte pattern `(index * 31 + 7).toByte()`)
 * - `splitJpegsize = false`
 * - `statusWord` "scanready" for scan sessions, "nopaper" for status sessions
 * - `version` default "NB0a.032"
 */
class ScannerTranscriptContractTest {
    /**
     * Renders a transcript to the golden format.
     * Format: `connection|direction|hex|decoded` per line, ending with newline.
     */
    private fun render(entries: List<TranscriptEntry>): String =
        entries.joinToString(separator = "\n", postfix = "\n") {
            "${it.connection}|${it.direction}|${it.hex}|${it.decoded}"
        }

    /**
     * Reads a golden transcript file from the classpath.
     * @throws IllegalStateException if the resource is missing
     */
    private fun readGoldenTranscript(path: String): String {
        val stream =
            javaClass.getResourceAsStream(path)
                ?: throw IllegalStateException("Golden transcript not found: $path")
        val output = ByteArrayOutputStream()
        stream.copyTo(output)
        return output.toString(StandardCharsets.UTF_8)
    }

    /**
     * Test that a status session with "nopaper" matches the golden transcript.
     * This verifies SC-01: byte-exact protocol fidelity for the status query.
     */
    @Test
    fun `SC-01 the status session matches the golden transcript`() {
        val fake = FakeScanner().apply { statusWord = "nopaper" }
        try {
            fake.start()
            val client = ScannerClient("127.0.0.1", fake.port)
            client.queryStatus()
        } finally {
            fake.stop()
        }

        val rendered = render(fake.transcript)
        val expected = readGoldenTranscript("/golden/transcripts/status_session.txt")

        assertThat(rendered)
            .`as`("status session transcript must match golden source byte-exact")
            .isEqualTo(expected)
    }

    /**
     * Test that a scan session with 300 dpi matches the golden transcript.
     * This verifies SC-01..SC-04: byte-exact protocol fidelity for the full scan flow,
     * one connection per operation (SC-02), the padded answer format (SC-03), and the
     * jpegsize answer structure (SC-04).
     */
    @Test
    fun `SC-01 to SC-04 the scan session matches the golden transcript`() {
        val fake = FakeScanner() // Uses defaults: fillBytes=true, scanready status
        try {
            fake.start()
            val client = ScannerClient("127.0.0.1", fake.port)
            client.scan(300)
        } finally {
            fake.stop()
        }

        val rendered = render(fake.transcript)
        val expected = readGoldenTranscript("/golden/transcripts/scan_session_300dpi.txt")

        assertThat(rendered)
            .`as`("scan session transcript must match golden source byte-exact")
            .isEqualTo(expected)
    }

    /**
     * Negative control: the contract breaks when fillBytes is disabled.
     * This proves the contract test is live and would catch protocol changes.
     */
    @Test
    fun `SC-03 the contract breaks when the fake drops the fill bytes`() {
        val fake =
            FakeScanner().apply {
                statusWord = "nopaper"
                fillBytes = false // Disable fill bytes
            }
        try {
            fake.start()
            val client = ScannerClient("127.0.0.1", fake.port)
            client.queryStatus()
        } finally {
            fake.stop()
        }

        val rendered = render(fake.transcript)
        val golden = readGoldenTranscript("/golden/transcripts/status_session.txt")

        assertThat(rendered)
            .`as`("transcript without fill bytes must differ from golden source")
            .isNotEqualTo(golden)
    }
}
