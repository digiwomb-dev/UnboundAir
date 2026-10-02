package dev.digiwomb.unboundair.scanner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for [ScanResult] and the resolution it reports (SC-08).
 *
 * The integration side of SC-08 -- a real scan against the [FakeScanner] with an
 * old firmware reporting 300 dpi back -- lives in [ScannerClientTest], where a
 * socket is involved. What is pinned here is the part that needs no scanner:
 * the decision itself ([ScannerClient.resolveDpi], which is pure) and the value
 * type that carries it.
 *
 * Why this is worth its own file: the whole point of SC-08 is that the effective
 * resolution must not get lost between the device and the PDF page size. Two
 * things could silently break that. The resolution could be resolved wrongly, or
 * the value type could compare two different scans as equal and let a stale
 * result pass unnoticed. Both are covered below.
 */
class ScanResultTest {
    private val client = ScannerClient()

    @Test
    fun `SC-08 a firmware older than 26 downgrades a requested 600 dpi to 300`() {
        assertThat(client.resolveDpi(600, "NB0a.020"))
            .`as`("firmware number 20 is below the threshold of 26, so 600 dpi is not available")
            .isEqualTo(300)
    }

    @Test
    fun `SC-08 the test device firmware NB0a_032 grants 600 dpi`() {
        assertThat(client.resolveDpi(600, "NB0a.032"))
            .`as`("firmware number 32 is at or above the threshold, so the request stands")
            .isEqualTo(600)
    }

    @Test
    fun `SC-08 the firmware number 26 is exactly on the boundary and grants 600 dpi`() {
        assertThat(client.resolveDpi(600, "NB0a.026"))
            .`as`("the plan says 'at least 26', so 26 itself must be enough")
            .isEqualTo(600)
        assertThat(client.resolveDpi(600, "NB0a.025"))
            .`as`("one below the boundary must not")
            .isEqualTo(300)
    }

    @Test
    fun `SC-08 an unparseable firmware is treated as too old`() {
        assertThat(client.resolveDpi(600, "garbage"))
            .`as`("a version that carries no number must not be read as capable: stay conservative at 300 dpi")
            .isEqualTo(300)
    }

    @Test
    fun `SC-08 a requested 300 dpi stays 300 whatever the firmware says`() {
        assertThat(client.resolveDpi(300, "NB0a.032"))
            .`as`("a capable firmware must not silently upgrade a 300 dpi request")
            .isEqualTo(300)
    }

    @Test
    fun `SC-08 the result reports the resolution alongside the bytes`() {
        val payload = byteArrayOf(1, 2, 3)

        val result = ScanResult(payload, 300)

        assertThat(result.bytes)
            .`as`("the payload must be carried through untouched")
            .isEqualTo(payload)
        assertThat(result.dpi)
            .`as`("SV-05 derives the page size from this value, so it must be readable from the result")
            .isEqualTo(300)
    }

    /**
     * A data class holding a [ByteArray] compares it by identity unless `equals`
     * is written by hand. Without the override, two scans of the same page would
     * count as different, and -- worse for a golden-master test -- a comparison
     * that was meant to check content would quietly check references instead.
     */
    @Test
    fun `SC-08 two results with the same image and resolution are equal`() {
        val first = ScanResult(byteArrayOf(1, 2, 3), 300)
        val second = ScanResult(byteArrayOf(1, 2, 3), 300)

        assertThat(first)
            .`as`("equality must compare the image content, not the array reference")
            .isEqualTo(second)
        assertThat(first.hashCode())
            .`as`("equal values must hash equally, or a set or map would misbehave")
            .isEqualTo(second.hashCode())
    }

    @Test
    fun `SC-08 results differing only in resolution are not equal`() {
        val at300 = ScanResult(byteArrayOf(1, 2, 3), 300)
        val at600 = ScanResult(byteArrayOf(1, 2, 3), 600)

        assertThat(at300)
            .`as`("the same pixels at a different resolution are a different page size, so not the same result")
            .isNotEqualTo(at600)
    }

    /**
     * A scan is roughly a megabyte. The generated `toString` of a data class
     * would render every byte of it into the output of a failing assertion.
     */
    @Test
    fun `SC-08 the description names the size instead of dumping the image`() {
        val result = ScanResult(ByteArray(900_000), 300, Duration.ofMillis(1200), Duration.ofMillis(800))

        assertThat(result.toString())
            .`as`("a failing test must stay readable")
            .isEqualTo("ScanResult(bytes=900000, dpi=300, scan=1200ms, transfer=800ms)")
    }

    /**
     * KL-02 needs the scan and the transfer timed separately, and the split can
     * only be made inside the client. The durations are therefore part of the
     * result -- but they are measurements, not identity.
     */
    @Test
    fun `KL-02 the result carries the scan and transfer durations`() {
        val result = ScanResult(byteArrayOf(1), 300, Duration.ofMillis(1500), Duration.ofMillis(400))

        assertThat(result.scanDuration)
            .`as`("the time the device spent pulling the sheet through")
            .isEqualTo(Duration.ofMillis(1500))
        assertThat(result.transferDuration)
            .`as`("the time the image spent on the wire")
            .isEqualTo(Duration.ofMillis(400))
    }

    @Test
    fun `SC-08 two results differing only in their durations are equal`() {
        val fast = ScanResult(byteArrayOf(1, 2, 3), 300, Duration.ofMillis(100), Duration.ofMillis(50))
        val slow = ScanResult(byteArrayOf(1, 2, 3), 300, Duration.ofSeconds(9), Duration.ofSeconds(4))

        assertThat(fast)
            .`as`(
                "durations are measurements, not identity: the same page scanned twice is the same result, " +
                    "and including them would make every comparison fail for a reason that does not matter",
            ).isEqualTo(slow)
        assertThat(fast.hashCode()).isEqualTo(slow.hashCode())
    }
}
