package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.image.LumaImage
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Property tests for the PBM packing ([MonochromeStep.Companion.pack], SV-08).
 *
 * These generalise the fixed-example assertions of [MonochromeStepTest]
 * (boundary, polarity, padding) to arbitrary pages — the property layer's job
 * per `docs/internal/teststrategie.md`: pinning invariants over generated inputs that
 * hand-written examples miss (every width's padding, every threshold's
 * polarity).
 *
 * [MonochromeStep.Companion.pack] is internal for exactly this reason: it is
 * pure, so the tests build [LumaImage]s in memory with no file I/O, no
 * external program, and no network (DC-03). Every property runs against a
 * fixed [SEED], so the runs are deterministic.
 */
class MonochromeStepPropertyTest {
    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-08 the payload length is ceil(width div 8) times height for arbitrary pages`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(1, 64),
                Arb.int(1, 32),
                Arb.int(1, 255),
            ) { width, height, threshold ->
                // The length property does not depend on content, so a fixed
                // fill keeps the case simple.
                val packed = MonochromeStep.pack(LumaImage(width, height, ByteArray(width * height)), threshold)
                val header = "P4\n$width $height\n".toByteArray(Charsets.US_ASCII)
                assertThat(packed.sliceArray(0 until header.size))
                    .`as`("the PBM header names the magic and dimensions for a %dx%d page", width, height)
                    .isEqualTo(header)
                val payload = packed.sliceArray(header.size until packed.size)
                assertThat(payload.size)
                    .`as`("a %dx%d page packs to ceil(%d/8)*%d payload bytes", width, height, width, height)
                    .isEqualTo(((width + 7) / 8) * height)
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-08 rows not a multiple of eight wide end with zero padding`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(1, 64),
                Arb.int(1, 32),
                Arb.int(1, 255),
            ) { width, height, threshold ->
                if (width % 8 == 0) {
                    return@checkAll
                }
                // All black maximises the set bits, so any stray bit leaking
                // into the padding shows up.
                val packed = MonochromeStep.pack(LumaImage(width, height, ByteArray(width * height)), threshold)
                val header = "P4\n$width $height\n".toByteArray(Charsets.US_ASCII)
                val payload = packed.sliceArray(header.size until packed.size)
                val stride = (width + 7) / 8
                val unusedBits = stride * 8 - width
                val paddingMask = (1 shl unusedBits) - 1
                for (y in 0 until height) {
                    val lastByte = payload[y * stride + stride - 1].toInt() and 0xFF
                    assertThat(lastByte and paddingMask)
                        .`as`("row %d of a %d-wide page must end with %d zero padding bits", y, width, unusedBits)
                        .isEqualTo(0)
                }
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-08 a set bit means black - bit(x,y) equals luma(x,y) below threshold`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(1, 64),
                Arb.int(1, 32),
                Arb.int(1, 255),
                Arb.byteArray(Arb.int(0, MAX_PIXELS), Arb.byte()),
            ) { width, height, threshold, raw ->
                val samples = ByteArray(width * height) { i -> if (i < raw.size) raw[i] else 0 }
                val packed = MonochromeStep.pack(LumaImage(width, height, samples), threshold)
                val header = "P4\n$width $height\n".toByteArray(Charsets.US_ASCII)
                val payload = packed.sliceArray(header.size until packed.size)
                val stride = (width + 7) / 8
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val luma = samples[y * width + x].toInt() and 0xFF
                        val expected = luma < threshold
                        val rowByte = payload[y * stride + x / 8].toInt() and 0xFF
                        val actual = ((rowByte shr (7 - (x % 8))) and 1) == 1
                        assertThat(actual)
                            .`as`(
                                "bit (%d,%d) with luma %d at threshold %d must be set iff luma < threshold",
                                x,
                                y,
                                luma,
                                threshold,
                            ).isEqualTo(expected)
                    }
                }
            }
        }
    }

    private companion object {
        /** Fixed seed so every run generates the same cases (determinism). */
        const val SEED = 8080L

        /** Largest page the width/height generators can produce (64 by 32). */
        const val MAX_PIXELS = 64 * 32
    }
}
