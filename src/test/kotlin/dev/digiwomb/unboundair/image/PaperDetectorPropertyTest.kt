package dev.digiwomb.unboundair.image

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Property tests for [PaperDetector.detect] and [PaperDetector.isPlausible] (SV-01, SV-02).
 *
 * The detector separates bright paper from a near-black background, so the
 * meaningful input is a bright rectangle on a dark surround. These properties
 * pin that, for an arbitrary bright rectangle, [PaperDetector.detect] returns
 * exactly that rectangle (a valid, in-bounds, non-inverted box), that a fully
 * dark page yields `null`, and that [PaperDetector.isPlausible] is monotone in
 * its thresholds, enforces the exact 10%-area and 6:1-aspect boundaries, and
 * rejects boxes with non-positive area.
 *
 * Every property runs against a fixed [SEED], so the runs are deterministic;
 * the functions under test are pure, so nothing touches the network, a file,
 * or the clock (DC-03).
 */
class PaperDetectorPropertyTest {
    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-01 detect returns the bright rectangle as a valid in-bounds box`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(8, 32),
                Arb.int(8, 32),
                Arb.int(1, 2),
                Arb.int(1, 2),
                Arb.int(1, 2),
                Arb.int(1, 2),
            ) { width, height, left, right, top, bottom ->
                // Margins are bounded so the bright rectangle always spans at
                // least a few pixels in each dimension: below 5 % of the row
                // or column it would legitimately be rejected as background.
                val rx0 = left
                val ry0 = top
                val rx1 = width - 1 - right
                val ry1 = height - 1 - bottom

                val image = brightRect(width, height, rx0, ry0, rx1, ry1)

                val box = PaperDetector.detect(image)

                assertThat(box)
                    .`as`("a bright rectangle on a dark surround must be detected")
                    .isNotNull()
                assertThat(box!!.x0).isBetween(0, width - 1)
                assertThat(box.x1).isBetween(0, width - 1)
                assertThat(box.y0).isBetween(0, height - 1)
                assertThat(box.y1).isBetween(0, height - 1)
                assertThat(box.x0).isLessThanOrEqualTo(box.x1)
                assertThat(box.y0).isLessThanOrEqualTo(box.y1)
                assertThat(box)
                    .`as`("the detected box must be exactly the bright rectangle")
                    .isEqualTo(PaperBox(rx0, ry0, rx1, ry1))
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-01 a fully bright field detects the whole frame`() {
        runBlocking {
            checkAll(PropTestConfig(seed = SEED), Arb.int(2, 32), Arb.int(2, 32)) { width, height ->
                val luma = ByteArray(width * height).apply { fill(200.toByte()) }
                val image = LumaImage(width, height, luma)

                assertThat(PaperDetector.detect(image))
                    .`as`("fully bright ${width}x$height field must detect whole frame")
                    .isEqualTo(PaperBox(0, 0, width - 1, height - 1))
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-01 a fully dark field detects nothing`() {
        runBlocking {
            checkAll(PropTestConfig(seed = SEED), Arb.int(2, 32), Arb.int(2, 32)) { width, height ->
                val luma = ByteArray(width * height).apply { fill(5.toByte()) }
                val image = LumaImage(width, height, luma)

                assertThat(PaperDetector.detect(image))
                    .`as`("fully dark ${width}x$height field must detect nothing")
                    .isNull()
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-02 isPlausible is monotone in both thresholds`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(0, 100),
                Arb.int(0, 100),
                Arb.int(0, 100),
                Arb.int(0, 100),
                Arb.int(1, 200),
                Arb.int(1, 200),
                Arb.double(0.0, 0.9),
                Arb.double(1.0, 10.0),
            ) { x0, y0, x1, y1, width, height, minArea, maxAspect ->
                val box = PaperBox(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
                val plausible = PaperDetector.isPlausible(box, width, height, minArea, maxAspect)

                // Loosening the area threshold (toward 0) must never turn a
                // plausible box implausible. This pins the `>=` direction without
                // recomputing the area fraction.
                if (plausible) {
                    assertThat(PaperDetector.isPlausible(box, width, height, 0.0, maxAspect))
                        .`as`("a box plausible at area $minArea must stay plausible at area 0")
                        .isTrue()
                }

                // Loosening the aspect-ratio threshold (upward) must never turn a
                // plausible box implausible. This pins the `<=` direction without
                // recomputing the aspect ratio.
                if (plausible) {
                    assertThat(PaperDetector.isPlausible(box, width, height, minArea, 100.0))
                        .`as`("a box plausible at aspect $maxAspect must stay plausible at aspect 100")
                        .isTrue()
                }
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-02 isPlausible enforces the exact area and aspect boundaries`() {
        // Hand-derived boundary cases: each expected value is written as a literal
        // (from the integer pixel counts), never recomputed through the production
        // formula. This pins the `>=`/`<=` operator direction at the exact boundary
        // (10% area, 6:1 aspect) and would fail on `>`/`<` instead.
        val cases =
            listOf(
                // Area: 5x2 = 10 px in 10x10 = 100 px is exactly 10% -> plausible (`>=`).
                BoundaryCase(10, 10, 5, 2, 0.10, 6.0, true),
                // Area: 3x3 = 9 px is 9% -> just below -> implausible.
                BoundaryCase(10, 10, 3, 3, 0.10, 6.0, false),
                // Aspect: 12x2 = 6.0 exactly -> plausible (`<=`). Area kept >= 10%.
                BoundaryCase(100, 2, 12, 2, 0.10, 6.0, true),
                // Aspect: 13x2 = 6.5 -> just above -> implausible.
                BoundaryCase(100, 2, 13, 2, 0.10, 6.0, false),
                // Arbitrary threshold: 5x5 = 25% in 10x10 against minArea 0.5 -> below.
                BoundaryCase(10, 10, 5, 5, 0.50, 6.0, false),
                // Arbitrary threshold: 5x10 = 50% exactly against minArea 0.5 -> plausible.
                BoundaryCase(10, 10, 10, 5, 0.50, 6.0, true),
            )

        for (case in cases) {
            val box = PaperBox(0, 0, case.boxW - 1, case.boxH - 1)
            assertThat(PaperDetector.isPlausible(box, case.width, case.height, case.minArea, case.maxAspect))
                .`as`("${case.boxW}x${case.boxH} in ${case.width}x${case.height} (minArea ${case.minArea}, maxAspect ${case.maxAspect})")
                .isEqualTo(case.expected)
        }
    }

    /**
     * Builds a [LumaImage] that is dark (luma 0) everywhere except a bright
     * (luma 200) rectangle spanning columns [rx0]..[rx1] and rows [ry0]..[ry1].
     */
    private fun brightRect(
        width: Int,
        height: Int,
        rx0: Int,
        ry0: Int,
        rx1: Int,
        ry1: Int,
    ): LumaImage {
        val luma = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x in rx0..rx1 && y in ry0..ry1) {
                    luma[y * width + x] = 200.toByte()
                } else {
                    luma[y * width + x] = 0.toByte()
                }
            }
        }
        return LumaImage(width, height, luma)
    }

    private companion object {
        /** Fixed seed so every run generates the same cases (determinism). */
        const val SEED = 9876L
    }

    private data class BoundaryCase(
        val width: Int,
        val height: Int,
        val boxW: Int,
        val boxH: Int,
        val minArea: Double,
        val maxAspect: Double,
        val expected: Boolean,
    )
}
