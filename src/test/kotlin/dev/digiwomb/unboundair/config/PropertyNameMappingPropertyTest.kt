package dev.digiwomb.unboundair.config

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Property tests for the property-name to environment-variable mapping (KL-01).
 *
 * The rule from `docs/plan.md` is short but easy to get wrong in exactly one
 * place: **every dot becomes an underscore, and every hyphen is dropped with no
 * replacement.** The tempting mistake is to turn a hyphen into an underscore
 * too, which yields `UNBOUNDAIR_POLL_INTERVAL` -- a variable Spring will not
 * bind, and a bug that only shows up in a container where nothing is logged.
 *
 * Handwritten examples cover the four names the plan lists. A property test
 * covers the shape of the rule for arbitrary names, which is what makes the
 * two mistakes above impossible to reintroduce unnoticed.
 *
 * The function under test is pure; nothing touches the network, a file, or the
 * clock (DC-03). Every property runs against a fixed [SEED], so runs are
 * deterministic.
 */
class PropertyNameMappingPropertyTest {
    /**
     * The mapping rule under test, written out as the plan states it.
     *
     * This mirrors what Spring Boot does when it derives the environment-variable
     * spelling of a configuration property. It is defined in the test rather than
     * in production code on purpose: production never performs this conversion --
     * Spring does -- so a production helper would be dead code that only existed
     * to be tested. What must be pinned is the *rule*, because the documentation
     * in `docs/konfiguration.md` (DO-09) states it and operators rely on it.
     */
    private fun toEnvironmentVariable(property: String): String = property.replace(".", "_").replace("-", "").uppercase()

    @Test
    fun `KL-01 the four documented names map as the plan states`() {
        assertThat(toEnvironmentVariable("unboundair.poll-interval")).isEqualTo("UNBOUNDAIR_POLLINTERVAL")
        assertThat(toEnvironmentVariable("unboundair.output.modules")).isEqualTo("UNBOUNDAIR_OUTPUT_MODULES")
        assertThat(toEnvironmentVariable("unboundair.outbox.path")).isEqualTo("UNBOUNDAIR_OUTBOX_PATH")
        assertThat(toEnvironmentVariable("unboundair.output.paperless.token-file"))
            .`as`("the example from the plan that carries both a dot and a hyphen")
            .isEqualTo("UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE")
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `KL-01 every dot becomes an underscore and no hyphen survives`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.list(Arb.stringPattern("[a-z][a-z-]{0,10}"), 1..5),
            ) { segments ->
                val property = segments.joinToString(".")

                val actual = toEnvironmentVariable(property)

                assertThat(actual)
                    .`as`("no hyphen may survive the conversion of '%s'", property)
                    .doesNotContain("-")
                assertThat(actual.count { it == '_' })
                    .`as`("each of the %d dots in '%s' must become exactly one underscore", segments.size - 1, property)
                    .isEqualTo(segments.size - 1)
                assertThat(actual)
                    .`as`("the result must be upper case for '%s'", property)
                    .isEqualTo(actual.uppercase())
            }
        }
    }

    @OptIn(ExperimentalKotest::class)
    @Test
    fun `KL-01 a hyphen is dropped rather than turned into an underscore`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = SEED),
                Arb.stringPattern("[a-z]{1,8}"),
                Arb.stringPattern("[a-z]{1,8}"),
            ) { left, right ->
                val hyphenated = toEnvironmentVariable("unboundair.$left-$right")
                val joined = toEnvironmentVariable("unboundair.$left$right")

                assertThat(hyphenated)
                    .`as`("'%s-%s' and '%s%s' must map to the same variable: the hyphen leaves no trace", left, right, left, right)
                    .isEqualTo(joined)
                assertThat(hyphenated)
                    .`as`("the hyphen must not become an underscore, which would produce an unbindable name")
                    .isEqualTo("UNBOUNDAIR_${left.uppercase()}${right.uppercase()}")
            }
        }
    }

    private companion object {
        /** Fixed seed so every run generates the same cases (determinism). */
        const val SEED = 4711L
    }
}
