package dev.digiwomb.unboundair.scanner

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Property tests for [startsWithPrefix] (SC-03).
 *
 * The scanner pads its answers with fill bytes and does not use a uniform
 * answer length, so the prefix comparison must match only the leading word
 * and ignore everything after it. These properties pin that invariant for
 * arbitrary words, arbitrary suffixes, and the device's padded answer format.
 *
 * Every property runs against a fixed [SEED], so the runs are deterministic;
 * the functions under test are pure, so nothing touches the network, a file,
 * or the clock (DC-03).
 */
class ScannerProtocolPropertyTest {
    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SC-03 the prefix match equals the reference comparison for any word and suffix`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.string(0, 8),
                Arb.byteArray(Arb.int(0, 20), Arb.byte()),
            ) { word, response ->
                val prefix = word.toByteArray(Charsets.US_ASCII)
                val expected = response.size >= prefix.size && response.copyOf(prefix.size).contentEquals(prefix)

                assertThat(startsWithPrefix(response, prefix))
                    .`as`("byte overload must equal the reference comparison for response=%s, word='%s'", response.contentToString(), word)
                    .isEqualTo(expected)
                assertThat(startsWithPrefix(response, word))
                    .`as`("string overload must equal the byte overload for response=%s, word='%s'", response.contentToString(), word)
                    .isEqualTo(expected)
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SC-03 the device's padded answers are recognized regardless of the padding length`() {
        val statusWords = listOf("scanready", "nopaper", "devbusy", "battlow")
        runBlocking {
            checkAll(PropTestConfig(seed = SEED), Arb.element(statusWords), Arb.int(0, 20)) { word, padding ->
                // The real device answers with: word + \x00 padding + a trailing 'H'.
                val answer = word.toByteArray(Charsets.US_ASCII) + ByteArray(padding) + byteArrayOf('H'.code.toByte())

                assertThat(startsWithPrefix(answer, word))
                    .`as`("the padded answer for '%s' with %d padding bytes must be recognized", word, padding)
                    .isTrue()
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SC-03 a response shorter than the word is never matched`() {
        runBlocking {
            checkAll(PropTestConfig(seed = SEED), Arb.string(2, 12)) { word ->
                val tooShort = word.dropLast(1).toByteArray(Charsets.US_ASCII)

                assertThat(startsWithPrefix(tooShort, word))
                    .`as`("a response of %d bytes cannot match the %d-byte word '%s'", tooShort.size, word.length, word)
                    .isFalse()
            }
        }
    }

    private companion object {
        /** Fixed seed so every run generates the same cases (determinism). */
        const val SEED = 1234L
    }
}
