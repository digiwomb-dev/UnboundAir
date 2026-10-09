package dev.digiwomb.unboundair.output.outbox

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Property tests for [backoffDelay] (AU-04).
 *
 * The documented rule is short — the first failure waits the initial delay, each further failure
 * multiplies it by the factor, and the result never exceeds the cap, for unbounded attempts — but
 * it has edges that handwritten examples miss: an off-by-one that multiplies once too often at
 * attempt 0, a cap enforced only at the end instead of before each step, a factor that shrinks
 * instead of grows, and a nanosecond count that overflows `Long` after a few dozen doublings.
 *
 * A property test covers the shape of the rule for arbitrary attempts, delays, and factors, which
 * is what makes each mistake above impossible to reintroduce unnoticed. Where a property needs a
 * restricted domain (a factor ≥ 1 for growth, a strictly growing factor for reaching the cap),
 * the domain and the reason for it are documented on the property.
 *
 * The function under test is pure; nothing touches the network, a file, or the clock (DC-03).
 * Every property runs against a fixed [SEED], so runs are deterministic.
 */
class BackoffPropertyTest {
    @Nested
    inner class BackoffDelays {
        /**
         * The delay stays strictly positive for every non-shrinking factor.
         *
         * The factor is restricted to values ≥ 1 on purpose: the decimal expansion of a double
         * ≥ 1.0 is ≥ 1, so each step multiplies an exact nanosecond count by at least 1 and
         * floors, which cannot shrink it, and the sequence starts at `min(initial, cap)` with
         * both generated ≥ 1 s. A factor < 1 is out of this property's domain — it legitimately
         * decays the delay toward zero (the cap bound below still holds for such factors).
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-04 a growing backoff is never negative and never zero`() {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED),
                    ATTEMPT,
                    POSITIVE_DELAY,
                    GROWING_FACTOR,
                    POSITIVE_DELAY,
                ) { attempt, initial, factor, cap ->
                    val delay = backoffDelay(attempt, initial, factor, cap)

                    assertThat(delay)
                        .`as`("min(%s, %s) with a factor ≥ 1 never shrinks, so the delay stays positive", initial, cap)
                        .isPositive()
                }
            }
        }

        /**
         * The cap is a hard bound for every valid factor, shrinking factors included.
         *
         * Each step is clamped with `min(…, cap)` before it is returned, and attempt 0 starts at
         * `min(initial, cap)` — so the result can never exceed the cap regardless of the factor
         * or the attempt. Hence the full valid factor domain 0.0..5.0 is in play here, unlike in
         * the growth properties.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-04 the delay never exceeds the cap`() {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED),
                    ATTEMPT,
                    POSITIVE_DELAY,
                    ANY_VALID_FACTOR,
                    POSITIVE_DELAY,
                ) { attempt, initial, factor, cap ->
                    val delay = backoffDelay(attempt, initial, factor, cap)

                    assertThat(delay)
                        .`as`("clamped to the cap on every step, including attempt 0 (initial %s, factor %s)", initial, factor)
                        .isLessThanOrEqualTo(cap)
                }
            }
        }

        /**
         * A later attempt never waits less than an earlier one.
         *
         * The factor is restricted to values ≥ 1: for every positive integer delay,
         * `floor(delay × factor) ≥ delay` when the factor is ≥ 1, and the cap clamp preserves
         * the bound. With a factor < 1 the sequence legitimately shrinks, so growth is not
         * claimed there.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-04 a later attempt never waits less than an earlier one`() {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED),
                    ATTEMPT,
                    POSITIVE_DELAY,
                    GROWING_FACTOR,
                    POSITIVE_DELAY,
                ) { attempt, initial, factor, cap ->
                    val current = backoffDelay(attempt, initial, factor, cap)
                    val next = backoffDelay(attempt + 1, initial, factor, cap)

                    assertThat(next)
                        .`as`("the delay after %d failures must not be shorter than after %d (factor %s)", attempt + 1, attempt, factor)
                        .isGreaterThanOrEqualTo(current)
                }
            }
        }

        /**
         * Attempt 0 yields exactly the initial delay, regardless of the factor.
         *
         * The cap is built as a whole multiple of the initial delay, so it never binds; the
         * factor is left fully arbitrary on purpose — even a factor of 5 or of 0 must not
         * change the answer, because attempt 0 is "the first failure" and waits the plain
         * initial delay. An off-by-one that multiplied once too often would fail here for any
         * factor ≠ 1.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-04 attempt 0 yields exactly the initial delay`() {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED),
                    POSITIVE_DELAY,
                    MULTIPLIER,
                    ANY_VALID_FACTOR,
                ) { initial, multiplier, factor ->
                    val cap = initial.multipliedBy(multiplier)

                    val delay = backoffDelay(0, initial, factor, cap)

                    assertThat(delay)
                        .`as`("attempt 0 waits the initial delay unchanged (cap is %d× the initial, factor %s)", multiplier, factor)
                        .isEqualTo(initial)
                }
            }
        }

        /**
         * Attempt 0 is clamped to the cap when the initial delay exceeds it.
         *
         * The initial delay is built as `cap × (1 + multiplier)`, strictly above the cap, and
         * the factor is again fully arbitrary, so this pins the attempt-0 clamp independently
         * of any multiplication: the answer is the cap, not the initial delay.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-04 attempt 0 is clamped to the cap when the initial delay exceeds it`() {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED),
                    POSITIVE_DELAY,
                    MULTIPLIER,
                    ANY_VALID_FACTOR,
                ) { cap, multiplier, factor ->
                    val initial = cap.multipliedBy(1 + multiplier)

                    val delay = backoffDelay(0, initial, factor, cap)

                    assertThat(delay)
                        .`as`("an initial %s above the cap %s is lowered to the cap before the first retry", initial, cap)
                        .isEqualTo(cap)
                }
            }
        }

        /**
         * A strictly growing backoff reaches the cap and then stays there.
         *
         * The factor is strictly > 1: 1.5 is the slowest of the three, and for every positive
         * nanosecond count `floor(1.5 × v) ≥ 1.25 × v` (the floor loses less than a quarter
         * once `v ≥ 4`), so from any generated initial delay of ≥ 1 s up to the cap of 1 h the
         * sequence reaches it in at most ≈37 steps — well inside the 128-attempt scan window.
         * Factor 1.0 is deliberately excluded: a constant sequence that starts below the cap
         * never reaches it, and staying at `min(initial, cap)` is the correct answer there.
         *
         * "Stays there" rests on the loop's early exit: once the running value equals the cap,
         * the loop stops, so every later attempt returns exactly the cap. The `Int.MAX_VALUE`
         * spot check makes that explicit for the unbounded-attempt case.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-04 a strictly growing backoff reaches the cap and stays there`() {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED),
                    POSITIVE_DELAY,
                    POSITIVE_DELAY,
                    GROWTH_FACTOR,
                ) { initial, cap, factor ->
                    val sequence = (0..SCAN_WINDOW).map { backoffDelay(it, initial, factor, cap) }

                    val reaches = sequence.indexOfFirst { it == cap }

                    assertThat(reaches)
                        .`as`(
                            "from %s with factor %s the delay must hit the cap %s within the first %d attempts",
                            initial,
                            factor,
                            cap,
                            SCAN_WINDOW,
                        ).isBetween(0, SCAN_WINDOW)
                    assertThat(sequence.drop(reaches))
                        .`as`("once the cap is reached, every later attempt returns exactly the cap")
                        .allMatch { it == cap }
                    assertThat(backoffDelay(Int.MAX_VALUE, initial, factor, cap))
                        .`as`("attempts are unbounded; the loop stops at the cap, so attempt %d returns it", Int.MAX_VALUE)
                        .isEqualTo(cap)
                }
            }
        }

        /**
         * Unbounded attempts cannot overflow (the named Long-overflow guard).
         *
         * With the outbox defaults (30 s, factor 2, cap 1 h) the delay doubles every attempt.
         * A naive `Long` nanosecond count would wrap to a negative value after roughly 59
         * doublings — attempt 10 000 or `Int.MAX_VALUE` would report a negative delay. Here the
         * running value is clamped to the 1 h cap before it can grow that far, so both named
         * attempts must return exactly the cap, and must do so quickly: the loop stops after
         * the seven doublings that reach the cap, never iterating the attempt itself.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-04 unbounded attempts return the cap instead of overflowing`() {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED),
                    OVERFLOW_ATTEMPTS,
                ) { attempt ->
                    val delay = backoffDelay(attempt, INITIAL_DEFAULT, 2.0, CAP_DEFAULT)

                    assertThat(delay)
                        .`as`("the defaults double from 30 s to the 1 h cap; attempt %d must return exactly the cap", attempt)
                        .isEqualTo(CAP_DEFAULT)
                }
            }
        }
    }

    @Nested
    inner class RejectedInputs {
        @Test
        fun `AU-04 a negative attempt is rejected`() {
            assertThatThrownBy { backoffDelay(-1, INITIAL_DEFAULT, 2.0, CAP_DEFAULT) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `AU-04 a negative initial delay is rejected`() {
            assertThatThrownBy { backoffDelay(0, Duration.ofSeconds(-30), 2.0, CAP_DEFAULT) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `AU-04 a negative cap is rejected`() {
            assertThatThrownBy { backoffDelay(0, INITIAL_DEFAULT, 2.0, Duration.ofHours(-1)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `AU-04 a negative, NaN, or infinite factor is rejected`() {
            assertThatThrownBy { backoffDelay(0, INITIAL_DEFAULT, -2.0, CAP_DEFAULT) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { backoffDelay(0, INITIAL_DEFAULT, Double.NaN, CAP_DEFAULT) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { backoffDelay(0, INITIAL_DEFAULT, Double.POSITIVE_INFINITY, CAP_DEFAULT) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private companion object {
        /** Fixed seed, so that every run generates the same cases (determinism). */
        const val SEED = 4711L

        /** The outbox defaults from `docs/internal/plan.md` (AU-04): start 30 s, factor 2, cap 1 h. */
        val INITIAL_DEFAULT: Duration = Duration.ofSeconds(30)

        val CAP_DEFAULT: Duration = Duration.ofHours(1)

        /** Non-shrinking factors; the literals are exactly representable, so `BigDecimal(factor)` is exact. */
        val GROWING_FACTORS = doubleArrayOf(1.0, 1.5, 2.0, 3.0, 5.0)

        /** Strictly growing factors; 1.5 is the slowest, which makes reaching the cap the slow path. */
        val GROWTH_FACTORS = doubleArrayOf(1.5, 2.0, 3.0)

        /** 0 is the first failure; 1000 runs well past where a growing sequence hits the cap. */
        val ATTEMPT: Arb<Int> = Arb.int(0..1_000)

        /** Positive delays in whole seconds, from 1 s to 1 h. */
        val POSITIVE_DELAY: Arb<Duration> = Arb.int(1..3_600).map { Duration.ofSeconds(it.toLong()) }

        /** A factor that never shrinks the delay. */
        val GROWING_FACTOR: Arb<Double> = Arb.int(0..GROWING_FACTORS.lastIndex).map { GROWING_FACTORS[it] }

        /** A strictly growing factor (1.0 excluded: it would never reach a cap above the initial delay). */
        val GROWTH_FACTOR: Arb<Double> = Arb.int(0..GROWTH_FACTORS.lastIndex).map { GROWTH_FACTORS[it] }

        /**
         * The full factor domain from the issue, 0.0 to 5.0, shrinking factors included.
         *
         * Built from an integer grid rather than `Arb.double(0.0..5.0)`: kotest's range-based double
         * arb injects NaN and ±Infinity as edge cases, which fall outside the SUT's finite domain
         * and are rejected by its `require` — the grid keeps every generated factor valid.
         */
        val ANY_VALID_FACTOR: Arb<Double> = Arb.int(0..5_000_000).map { it / 5_000_000.0 }

        /** How far apart initial and cap may sit, in whole multiples of each other. */
        val MULTIPLIER: Arb<Long> = Arb.int(1..5).map { it.toLong() }

        /** The two named overflow attempts: 10 000 doublings, and the largest `Int`. */
        val OVERFLOW_ATTEMPTS: Arb<Int> = Arb.int(0..1).map { if (it == 0) 10_000 else Int.MAX_VALUE }

        /** Scan window for "reaches the cap": the worst case needs ≈37 steps, covered with room to spare. */
        const val SCAN_WINDOW = 128
    }
}
