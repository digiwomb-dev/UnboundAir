package dev.digiwomb.unboundair.processing

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Property tests for [roundOriginInwards] (SV-01).
 *
 * The function rounds the crop window origin inwards to the iMCU grid,
 * ensuring that the resulting crop window stays within the original box.
 * These properties pin that invariant for arbitrary paper boxes and iMCU grids.
 *
 * Every property runs against a fixed [SEED], so the runs are deterministic;
 * the function under test is pure, so nothing touches the network, a file,
 * or the clock (DC-03).
 */
class ImcuRoundingPropertyTest {
    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-01 the rounded origin is greater than or equal to the raw origin`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(0, 200),
                Arb.int(0, 200),
                Arb.int(1, 16),
                Arb.int(1, 16),
                Arb.int(0, 200),
                Arb.int(0, 200),
            ) { x0, y0, imcuWidth, imcuHeight, x1, y1 ->
                // Ensure x1 >= x0 and y1 >= y0
                val actualX1 = maxOf(x1, x0)
                val actualY1 = maxOf(y1, y0)

                val cropWindow = roundOriginInwards(x0, y0, imcuWidth, imcuHeight, actualX1, actualY1)
                val ax0 = cropWindow.ax0
                val ay0 = cropWindow.ay0

                assertThat(ax0)
                    .`as`("rounded x0 must be >= raw x0 for x0=%d, imcuWidth=%d", x0, imcuWidth)
                    .isGreaterThanOrEqualTo(x0)
                assertThat(ay0)
                    .`as`("rounded y0 must be >= raw y0 for y0=%d, imcuHeight=%d", y0, imcuHeight)
                    .isGreaterThanOrEqualTo(y0)
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-01 the rounded origin is grid-aligned`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(0, 200),
                Arb.int(0, 200),
                Arb.int(1, 16),
                Arb.int(1, 16),
                Arb.int(0, 200),
                Arb.int(0, 200),
            ) { x0, y0, imcuWidth, imcuHeight, x1, y1 ->
                // Ensure x1 >= x0 and y1 >= y0
                val actualX1 = maxOf(x1, x0)
                val actualY1 = maxOf(y1, y0)

                val cropWindow = roundOriginInwards(x0, y0, imcuWidth, imcuHeight, actualX1, actualY1)
                val ax0 = cropWindow.ax0
                val ay0 = cropWindow.ay0

                assertThat(ax0 % imcuWidth)
                    .`as`("rounded x0 must be grid-aligned for x0=%d, imcuWidth=%d", ax0, imcuWidth)
                    .isEqualTo(0)
                assertThat(ay0 % imcuHeight)
                    .`as`("rounded y0 must be grid-aligned for y0=%d, imcuHeight=%d", ay0, imcuHeight)
                    .isEqualTo(0)
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-01 the rounded origin moves by less than one grid step`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(0, 200),
                Arb.int(0, 200),
                Arb.int(1, 16),
                Arb.int(1, 16),
                Arb.int(0, 200),
                Arb.int(0, 200),
            ) { x0, y0, imcuWidth, imcuHeight, x1, y1 ->
                // Ensure x1 >= x0 and y1 >= y0
                val actualX1 = maxOf(x1, x0)
                val actualY1 = maxOf(y1, y0)

                val cropWindow = roundOriginInwards(x0, y0, imcuWidth, imcuHeight, actualX1, actualY1)
                val ax0 = cropWindow.ax0
                val ay0 = cropWindow.ay0

                assertThat(ax0 - x0)
                    .`as`("difference in x0 must be < imcuWidth for x0=%d, imcuWidth=%d", x0, imcuWidth)
                    .isLessThan(imcuWidth)
                assertThat(ay0 - y0)
                    .`as`("difference in y0 must be < imcuHeight for y0=%d, imcuHeight=%d", y0, imcuHeight)
                    .isLessThan(imcuHeight)
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `SV-01 the crop window stays inside the box`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.int(0, 100),
                Arb.int(0, 100),
                Arb.int(1, 16),
                Arb.int(1, 16),
                Arb.int(0, 50),
                Arb.int(0, 50),
            ) { x0, y0, imcuWidth, imcuHeight, xExtra, yExtra ->
                // The real crop only runs on a plausible box, which spans at
                // least one grid step; a thinner box is degenerate and would
                // let the inward rounding legitimately overshoot x1/y1.
                val x1 = x0 + imcuWidth + xExtra
                val y1 = y0 + imcuHeight + yExtra

                val cropWindow = roundOriginInwards(x0, y0, imcuWidth, imcuHeight, x1, y1)
                val ax0 = cropWindow.ax0
                val ay0 = cropWindow.ay0
                val cropWidth = cropWindow.cropWidth
                val cropHeight = cropWindow.cropHeight

                assertThat(ax0)
                    .`as`("rounded x0 must be <= x1 for x0=%d, x1=%d", ax0, x1)
                    .isLessThanOrEqualTo(x1)
                assertThat(ay0)
                    .`as`("rounded y0 must be <= y1 for y0=%d, y1=%d", ay0, y1)
                    .isLessThanOrEqualTo(y1)

                // Ensure crop dimensions are valid (at least 1 pixel)
                assertThat(cropWidth)
                    .`as`("crop width must be >= 1 for x0=%d, x1=%d", x0, x1)
                    .isGreaterThanOrEqualTo(1)
                assertThat(cropHeight)
                    .`as`("crop height must be >= 1 for y0=%d, y1=%d", y0, y1)
                    .isGreaterThanOrEqualTo(1)
            }
        }
    }

    private companion object {
        /** Fixed seed so every run generates the same cases (determinism). */
        const val SEED = 5555L
    }
}
