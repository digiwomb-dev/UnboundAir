package dev.digiwomb.unboundair.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.time.Duration

/**
 * Slice tests for the configuration binding (KL-01), the third layer of
 * `docs/internal/teststrategie.md`.
 *
 * Two things are pinned here, and they are different claims:
 *
 * 1. **Every setting resolves to its documented default.** The defaults live in
 *    exactly one place ([UnboundAirProperties]) and are mirrored for humans in
 *    `docs/konfiguration.md` (DO-09). If someone changes a default in the code
 *    without touching the docs, this test is the tripwire.
 * 2. **An environment variable overrides a default.** This is the actual
 *    acceptance criterion of KL-01, and the mapping rule is easy to get wrong:
 *    every dot becomes an underscore and every hyphen is dropped with no
 *    replacement. The property test [PropertyNameMappingPropertyTest] pins the
 *    rule itself; here it is exercised end to end through a real binder.
 *
 * [ApplicationContextRunner] is used rather than `@SpringBootTest`, because a
 * slice must not boot the whole application: the runner builds a minimal context
 * with only the properties class registered, which keeps the test fast and
 * independent of the CLI dispatcher in the composition root.
 *
 * Nothing here touches the network, the clock, or an external program (DC-03).
 */
class ConfigBindingSliceTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)

    /**
     * Returns a runner whose environment carries [variables] as real environment
     * variables.
     *
     * Two details here are not decoration; both were found by a failing test and
     * both would silently produce a green test that proves the opposite of KL-01:
     *
     * 1. **It must be a [SystemEnvironmentPropertySource].** Only for that kind of
     *    source does Spring apply the relaxed binding that lets
     *    `UNBOUNDAIR_POLLINTERVAL` reach `unboundair.poll-interval`.
     *    `withPropertyValues` installs an ordinary map source, where the
     *    upper-case name is taken literally and binds nothing.
     * 2. **The source name must be `systemEnvironment` or end in
     *    `-systemEnvironment`.** Spring recognises the environment-variable
     *    convention by that name, not by the class alone. A source of the right
     *    class under a name like `test-environment` is ignored for relaxed
     *    binding -- the value is simply never found, with no error.
     *
     * The variables are injected as an additional property source rather than by
     * mutating the JVM environment, so the tests stay independent of each other
     * and of the machine they run on.
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

    /**
     * The documented defaults (KL-01). Kept as one test per group rather than one
     * giant assertion, so a failure names the group that drifted.
     */
    @Nested
    inner class Defaults {
        @Test
        fun `KL-01 the timing defaults are 3, 10 and 20 seconds with idle handling off`() {
            runner.run { context ->
                val properties = context.getBean(UnboundAirProperties::class.java)

                assertThat(properties.pollInterval)
                    .`as`("unboundair.poll-interval default (DL-01)")
                    .isEqualTo(Duration.ofSeconds(3))
                assertThat(properties.offlinePollInterval)
                    .`as`("unboundair.offline-poll-interval default (DL-02)")
                    .isEqualTo(Duration.ofSeconds(10))
                assertThat(properties.batchTimeout)
                    .`as`("unboundair.batch-timeout default (DL-04), provisional until measure")
                    .isEqualTo(Duration.ofSeconds(20))
                assertThat(properties.idleMinutes)
                    .`as`("unboundair.idle-minutes default (DL-06): off until measurements exist")
                    .isNull()
            }
        }

        @Test
        fun `KL-01 the page defaults are gray without keeping the raw scan`() {
            runner.run { context ->
                val properties = context.getBean(UnboundAirProperties::class.java)

                assertThat(properties.colorMode)
                    .`as`("unboundair.color-mode default (SV-03)")
                    .isEqualTo("gray")
                assertThat(properties.keepRaw)
                    .`as`("unboundair.keep-raw default (SV-06)")
                    .isFalse()
                assertThat(properties.bwThreshold)
                    .`as`("unboundair.bw-threshold default (SV-08)")
                    .isEqualTo(128)
                assertThat(properties.dpi)
                    .`as`("unboundair.dpi default (SC-07, SC-08)")
                    .isEqualTo(300)

                // Reflection on purpose, approved by the client: the architecture
                // guard applies to test classes too (config is a leaf and may not
                // access any layer), so importing PageSettings here would turn
                // ArchitectureRulesTest red. A string class name creates no
                // bytecode dependency, yet still ties the two defaults together.
                // Cheap because PageSettings is a plain data class, no Spring
                // involved. No range check here: validation lives in
                // PageSettings (#153) and is tested there; a second assertion
                // here would invite a second validator.
                val pageSettingsDefault =
                    Class
                        .forName("dev.digiwomb.unboundair.processing.PageSettings")
                        .getDeclaredConstructor()
                        .newInstance()
                        .let { instance ->
                            instance.javaClass
                                .getDeclaredField("bwThreshold")
                                .apply { isAccessible = true }
                                .get(instance)
                        }
                assertThat(properties.bwThreshold)
                    .`as`(
                        "the config default must equal PageSettings().bwThreshold" +
                            " - two defaults for one value drift silently",
                    ).isEqualTo(pageSettingsDefault)
            }
        }

        @Test
        fun `KL-01 the scanner, output and outbox defaults match the plan`() {
            runner.run { context ->
                val properties = context.getBean(UnboundAirProperties::class.java)

                assertThat(properties.scanner.host)
                    .`as`("unboundair.scanner.host default (SC-06)")
                    .isEqualTo("192.168.18.33")
                assertThat(properties.scanner.port)
                    .`as`("unboundair.scanner.port default (SC-06)")
                    .isEqualTo(23)
                assertThat(properties.output.modules)
                    .`as`("unboundair.output.modules default (AU-03): nothing active until configured")
                    .isEmpty()
                assertThat(properties.outbox.path)
                    .`as`("unboundair.outbox.path default (AU-04)")
                    .isEqualTo("/var/lib/unboundair/outbox")
            }
        }
    }

    /**
     * The override path (KL-01), driven through a real environment property
     * source so the `UNBOUNDAIR_…` spelling is genuinely exercised -- see
     * [runnerWithEnvironment] for why that distinction is not a formality.
     */
    @Nested
    inner class Overrides {
        @Test
        fun `KL-01 an environment variable overrides a default`() {
            runnerWithEnvironment(mapOf("UNBOUNDAIR_POLLINTERVAL" to "7"))
                .run { context ->
                    val properties = context.getBean(UnboundAirProperties::class.java)

                    assertThat(properties.pollInterval)
                        .`as`("UNBOUNDAIR_POLLINTERVAL must override the 3 s default")
                        .isEqualTo(Duration.ofSeconds(7))
                }
        }

        @Test
        fun `KL-01 a bare number means seconds and an explicit unit still works`() {
            runner
                .withPropertyValues("unboundair.batch-timeout=45")
                .run { context ->
                    assertThat(context.getBean(UnboundAirProperties::class.java).batchTimeout)
                        .`as`("a bare number must mean seconds, not milliseconds")
                        .isEqualTo(Duration.ofSeconds(45))
                }

            runner
                .withPropertyValues("unboundair.batch-timeout=500ms")
                .run { context ->
                    assertThat(context.getBean(UnboundAirProperties::class.java).batchTimeout)
                        .`as`("an explicit unit must still be honoured")
                        .isEqualTo(Duration.ofMillis(500))
                }
        }

        @Test
        fun `KL-01 nested settings are overridable through their environment variables`() {
            runnerWithEnvironment(
                mapOf(
                    "UNBOUNDAIR_SCANNER_HOST" to "127.0.0.1",
                    "UNBOUNDAIR_SCANNER_PORT" to "15000",
                    "UNBOUNDAIR_OUTBOX_PATH" to "/tmp/outbox",
                ),
            ).run { context ->
                val properties = context.getBean(UnboundAirProperties::class.java)

                assertThat(properties.scanner.host).isEqualTo("127.0.0.1")
                assertThat(properties.scanner.port)
                    .`as`("SC-06 demands a free port can be configured without a code change")
                    .isEqualTo(15000)
                assertThat(properties.outbox.path).isEqualTo("/tmp/outbox")
            }
        }

        @Test
        fun `AU-03 the module list is read as a comma-separated value`() {
            runnerWithEnvironment(mapOf("UNBOUNDAIR_OUTPUT_MODULES" to "paperless,archive"))
                .run { context ->
                    assertThat(context.getBean(UnboundAirProperties::class.java).output.modules)
                        .`as`("a comma list keeps the 'several modules at once' question open (AU-03)")
                        .containsExactly("paperless", "archive")
                }
        }

        @Test
        fun `KL-01 the bw threshold binds from its property name`() {
            runner
                .withPropertyValues("unboundair.bw-threshold=90")
                .run { context ->
                    assertThat(context.getBean(UnboundAirProperties::class.java).bwThreshold)
                        .`as`("unboundair.bw-threshold=90 must bind (SV-08)")
                        .isEqualTo(90)
                }
        }

        @Test
        fun `KL-01 the bw threshold binds from its documented environment variable`() {
            runnerWithEnvironment(mapOf("UNBOUNDAIR_BWTHRESHOLD" to "90"))
                .run { context ->
                    assertThat(context.getBean(UnboundAirProperties::class.java).bwThreshold)
                        .`as`("UNBOUNDAIR_BWTHRESHOLD must override the 128 default (SV-08)")
                        .isEqualTo(90)
                }
        }

        @Test
        fun `KL-01 the dpi binds from its property name (SC-07, SC-08)`() {
            runner
                .withPropertyValues("unboundair.dpi=600")
                .run { context ->
                    assertThat(context.getBean(UnboundAirProperties::class.java).dpi)
                        .`as`("unboundair.dpi=600 must bind (SC-07, SC-08)")
                        .isEqualTo(600)
                }
        }

        @Test
        fun `KL-01 the dpi binds from its documented environment variable (SC-07, SC-08)`() {
            runnerWithEnvironment(mapOf("UNBOUNDAIR_DPI" to "600"))
                .run { context ->
                    assertThat(context.getBean(UnboundAirProperties::class.java).dpi)
                        .`as`("UNBOUNDAIR_DPI must override the 300 default (SC-07, SC-08)")
                        .isEqualTo(600)
                }
        }
    }

    /**
     * Context smoke (layer 3 of `docs/internal/teststrategie.md`): boot a context and
     * confirm the configuration is resolvable at all.
     *
     * This asserts no behaviour on purpose. Its job is to catch wiring errors --
     * a properties class that cannot be constructed, a binding that throws -- which
     * the targeted tests above would never reach, because they would fail during
     * setup with a far less obvious message.
     */
    @Test
    fun `KL-01 the configuration is resolvable in an application context`() {
        runner.run { context ->
            assertThat(context)
                .`as`("the context must start and the properties bean must be present")
                .hasSingleBean(UnboundAirProperties::class.java)
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
