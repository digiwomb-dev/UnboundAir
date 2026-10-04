package dev.digiwomb.unboundair.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * Slice tests for the paperless-ngx block of the configuration binding (KL-01, AU-05).
 *
 * This is the sibling of [ConfigBindingSliceTest], which covers the milestone-3
 * settings; this file covers only the nested `unboundair.output.paperless.*` block
 * added afterwards. The structure mirrors the sibling on purpose, so a reader can
 * compare both claim by claim.
 *
 * Three claims are pinned here:
 *
 * 1. **Every paperless setting binds from both spellings.** The dotted spelling is
 *    what an operator writes into `application.properties`; the
 *    environment-variable spelling is what they actually use in a container
 *    (KL-01). The hyphen rule is easy to get wrong -- `base-url` becomes
 *    `BASEURL`, with the hyphen dropped and no underscore added -- so the
 *    canonical spelling is asserted explicitly.
 * 2. **An environment variable wins over a dotted property** (AU-05). This is the
 *    acceptance criterion of issue #122.
 * 3. **The nested block displaced nothing.** `unboundair.output.modules` and
 *    `unboundair.outbox.path` still bind with their documented defaults.
 *
 * Nothing here touches the network, the clock, or an external program (DC-03).
 */
class PaperlessBindingSliceTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)

    /**
     * Returns a runner whose environment carries [variables] as real environment
     * variables.
     *
     * The source must be a [SystemEnvironmentPropertySource] named
     * `test-systemEnvironment`: only under that name does Spring apply the
     * relaxed binding that lets `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL` reach
     * `unboundair.output.paperless.base-url`. Any other name -- or the ordinary
     * map source installed by `withPropertyValues` -- silently binds nothing.
     * See [ConfigBindingSliceTest] for the full story.
     */
    private fun runnerWithEnvironment(variables: Map<String, Any>) =
        runner.withInitializer { context ->
            context.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    "test-systemEnvironment",
                    variables,
                ),
            )
        }

    /** The documented defaults (KL-01, AU-05): usable only when configured. */
    @Nested
    inner class Defaults {
        @Test
        fun `KL-01 the paperless block is empty and disabled by default`() {
            runner.run { context ->
                val paperless = context.getBean(UnboundAirProperties::class.java).output.paperless

                assertThat(paperless.baseUrl).`as`("unboundair.output.paperless.base-url default: no guess").isEqualTo("")
                assertThat(paperless.token).`as`("unboundair.output.paperless.token default: set token or token-file").isEqualTo("")
                assertThat(paperless.tokenFile).`as`("unboundair.output.paperless.token-file default: no secret mounted").isEqualTo("")
                assertThat(paperless.tags).`as`("unboundair.output.paperless.tags default: no tags attached").isEmpty()
                assertThat(paperless.correspondent).`as`("unboundair.output.paperless.correspondent default: none").isNull()
                assertThat(paperless.documentType).`as`("unboundair.output.paperless.document-type default: none").isNull()
            }
        }
    }

    /** All six settings bind from the dotted spelling (KL-01). */
    @Nested
    inner class DottedBinding {
        @Test
        fun `KL-01 all six paperless settings bind from the dotted spelling`() {
            runner
                .withPropertyValues(
                    "unboundair.output.paperless.base-url=https://paperless.example.org",
                    "unboundair.output.paperless.token=abc123",
                    "unboundair.output.paperless.token-file=/run/secrets/paperless-token",
                    "unboundair.output.paperless.tags=4,7",
                    "unboundair.output.paperless.correspondent=3",
                    "unboundair.output.paperless.document-type=9",
                ).run { context ->
                    val paperless = context.getBean(UnboundAirProperties::class.java).output.paperless

                    assertThat(paperless.baseUrl).isEqualTo("https://paperless.example.org")
                    assertThat(paperless.token).isEqualTo("abc123")
                    assertThat(paperless.tokenFile).isEqualTo("/run/secrets/paperless-token")
                    assertThat(paperless.tags).containsExactly(4L, 7L)
                    assertThat(paperless.correspondent).isEqualTo(3L)
                    assertThat(paperless.documentType).isEqualTo(9L)
                }
        }
    }

    /** All six settings bind from the environment-variable spelling (KL-01). */
    @Nested
    inner class EnvironmentBinding {
        @Test
        fun `KL-01 all six paperless settings bind from environment variables`() {
            runnerWithEnvironment(
                mapOf(
                    "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL" to "https://paperless.example.org",
                    "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN" to "abc123",
                    "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE" to "/run/secrets/paperless-token",
                    "UNBOUNDAIR_OUTPUT_PAPERLESS_TAGS" to "4,7",
                    "UNBOUNDAIR_OUTPUT_PAPERLESS_CORRESPONDENT" to "3",
                    "UNBOUNDAIR_OUTPUT_PAPERLESS_DOCUMENTTYPE" to "9",
                ),
            ).run { context ->
                val paperless = context.getBean(UnboundAirProperties::class.java).output.paperless

                assertThat(paperless.baseUrl).isEqualTo("https://paperless.example.org")
                assertThat(paperless.token).isEqualTo("abc123")
                assertThat(paperless.tokenFile).isEqualTo("/run/secrets/paperless-token")
                assertThat(paperless.tags).containsExactly(4L, 7L)
                assertThat(paperless.correspondent).isEqualTo(3L)
                assertThat(paperless.documentType).isEqualTo(9L)
            }
        }

        @Test
        fun `KL-01 the hyphen disappears without replacement in environment names`() {
            // base-url -> BASEURL: the hyphen is dropped with no replacement. Spring's
            // relaxed binding also tolerates BASE_URL with an underscore, so this pins
            // the canonical documented spelling instead of claiming the other form
            // binds nothing -- the mistake it guards against is documenting BASE_URL
            // as if only that form were correct.
            runnerWithEnvironment(
                mapOf("UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL" to "https://paperless.example.org"),
            ).run { context ->
                assertThat(
                    context
                        .getBean(UnboundAirProperties::class.java)
                        .output.paperless.baseUrl,
                ).`as`("BASEURL without an underscore must reach base-url: the hyphen simply disappears")
                    .isEqualTo("https://paperless.example.org")
            }
        }
    }

    /** Precedence between the two spellings (AU-05, issue #122). */
    @Nested
    inner class Precedence {
        @Test
        fun `AU-05 an environment variable wins over a dotted property`() {
            runnerWithEnvironment(
                mapOf("UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL" to "https://env.example.org"),
            ).withPropertyValues(
                "unboundair.output.paperless.base-url=https://file.example.org",
            ).run { context ->
                assertThat(
                    context
                        .getBean(UnboundAirProperties::class.java)
                        .output.paperless.baseUrl,
                ).`as`("the container environment must override the shipped property file")
                    .isEqualTo("https://env.example.org")
            }
        }
    }

    /** The nested block must not displace the existing settings (KL-01, AU-03, AU-04). */
    @Nested
    inner class Neighbours {
        @Test
        fun `KL-01 modules and outbox path still bind with their documented defaults`() {
            runner
                .withPropertyValues(
                    "unboundair.output.modules=paperless",
                    "unboundair.outbox.path=/tmp/outbox",
                ).run { context ->
                    val properties = context.getBean(UnboundAirProperties::class.java)

                    assertThat(properties.output.modules)
                        .`as`("unboundair.output.modules (AU-03)")
                        .containsExactly("paperless")
                    assertThat(properties.outbox.path)
                        .`as`("unboundair.outbox.path (AU-04)")
                        .isEqualTo("/tmp/outbox")
                }

            runner.run { context ->
                val properties = context.getBean(UnboundAirProperties::class.java)

                assertThat(properties.output.modules)
                    .`as`("unboundair.output.modules default (AU-03): nothing active until configured")
                    .isEmpty()
                assertThat(properties.outbox.path)
                    .`as`("unboundair.outbox.path default (AU-04)")
                    .isEqualTo("/var/lib/unboundair/outbox")
            }
        }
    }

    /** The tag list parses as numbers, not as one string (AU-05). */
    @Nested
    inner class TagParsing {
        @Test
        fun `AU-05 tags parse a comma list into several numbers`() {
            runnerWithEnvironment(
                mapOf("UNBOUNDAIR_OUTPUT_PAPERLESS_TAGS" to "4,7"),
            ).run { context ->
                assertThat(
                    context
                        .getBean(UnboundAirProperties::class.java)
                        .output.paperless.tags,
                ).`as`("`4,7` must become two tag IDs, not one string")
                    .containsExactly(4L, 7L)
            }
        }
    }

    /**
     * Registers the properties class for the slice. The production code uses
     * `@ConfigurationPropertiesScan` on the application class; naming the class
     * explicitly here keeps the slice free of a component scan over the whole
     * application.
     */
    @EnableConfigurationProperties(UnboundAirProperties::class)
    private class PropertiesConfiguration
}
