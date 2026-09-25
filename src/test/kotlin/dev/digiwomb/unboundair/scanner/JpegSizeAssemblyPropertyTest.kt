package dev.digiwomb.unboundair.scanner

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Property tests for [JpegSizeAssembler] and [parseJpegSize] (SC-04).
 *
 * The `jpegsize` answer is a fixed 12-byte response: the ASCII word `jpegsize`
 * (8 bytes) followed by a little-endian `uint32` size in bytes [8..11].
 * These properties pin that invariant for arbitrary sizes, arbitrary
 * segmentations, and the device's answer format.
 *
 * Every property runs against a fixed [SEED], so the runs are deterministic;
 * the functions under test are pure, so nothing touches the network, a file,
 * or the clock (DC-03).
 */
class JpegSizeAssemblyPropertyTest {
    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SC-04 any segmentation of the 12-byte answer assembles the same size`() {
        runBlocking {
            checkAll(PropTestConfig(seed = SEED), Arb.long(0, Int.MAX_VALUE.toLong())) { size ->
                // build a 12-byte answer for size.toInt(), split it, feed both halves, assert parse() == size.toInt()
                val answer = ByteArray(12)
                ScannerResponse.JPEGSIZE.copyInto(answer, 0)
                // Little-endian uint32 size
                answer[8] = (size and 0xFF).toByte()
                answer[9] = ((size shr 8) and 0xFF).toByte()
                answer[10] = ((size shr 16) and 0xFF).toByte()
                answer[11] = ((size shr 24) and 0xFF).toByte()

                // Test two halves segmentation
                val assembler = JpegSizeAssembler()
                assembler.feed(answer, 0, 6)
                assembler.feed(answer, 6, 6)
                assertThat(assembler.parse()).isEqualTo(size.toInt())

                // Test one-byte-at-a-time feeding
                val assembler2 = JpegSizeAssembler()
                for (i in 0 until 12) {
                    assembler2.feed(answer, i, 1)
                }
                assertThat(assembler2.parse()).isEqualTo(size.toInt())
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SC-04 short answer is rejected`() {
        runBlocking {
            checkAll(PropTestConfig(seed = SEED), Arb.int(0, 11)) { length ->
                if (length < 12) {
                    val answer = ByteArray(length)
                    // Test parseJpegSize
                    assertThatThrownBy { parseJpegSize(answer) }
                        .isInstanceOf(IllegalArgumentException::class.java)
                        .hasMessageContaining("jpegsize answer is too short")

                    // Test JpegSizeAssembler
                    val assembler = JpegSizeAssembler()
                    assembler.feed(answer)
                    assertThatThrownBy { assembler.parse() }
                        .isInstanceOf(IllegalArgumentException::class.java)
                        .hasMessageContaining("jpegsize answer is too short")
                }
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SC-04 answer without prefix is rejected`() {
        runBlocking {
            checkAll(PropTestConfig(seed = SEED), Arb.long(0, Int.MAX_VALUE.toLong())) { size ->
                // Create a 12-byte answer that doesn't start with "jpegsize"
                val answer = ByteArray(12)
                // Fill with random bytes that don't match "jpegsize"
                answer[0] = 'x'.code.toByte()
                answer[1] = 'y'.code.toByte()
                answer[2] = 'z'.code.toByte()
                answer[3] = 'a'.code.toByte()
                answer[4] = 'b'.code.toByte()
                answer[5] = 'c'.code.toByte()
                answer[6] = 'd'.code.toByte()
                answer[7] = 'e'.code.toByte()
                // Set the size part
                answer[8] = (size and 0xFF).toByte()
                answer[9] = ((size shr 8) and 0xFF).toByte()
                answer[10] = ((size shr 16) and 0xFF).toByte()
                answer[11] = ((size shr 24) and 0xFF).toByte()

                // Test parseJpegSize
                assertThatThrownBy { parseJpegSize(answer) }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("not a jpegsize answer")

                // Test JpegSizeAssembler
                val assembler = JpegSizeAssembler()
                assembler.feed(answer)
                assertThatThrownBy { assembler.parse() }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("not a jpegsize answer")
            }
        }
    }

    private companion object {
        /** Fixed seed so every run generates the same cases (determinism). */
        const val SEED = 5678L
    }
}
