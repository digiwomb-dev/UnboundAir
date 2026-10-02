package dev.digiwomb.unboundair.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * Central place for all configuration defaults (KL-01).
 *
 * This class is the single source of truth for default values; the documented
 * reference for humans lives in `docs/konfiguration.md` (DO-09). Both must stay
 * in step: a setting that exists here and nowhere else is undocumented, and a
 * setting documented there but missing here does not exist.
 *
 * The class is registered by `@ConfigurationPropertiesScan` on the application
 * class. Without that annotation `@ConfigurationProperties` below would have no
 * effect at all.
 *
 * Property names use dots and hyphens, e.g. `unboundair.poll-interval`. Environment
 * variables use upper case with underscores and hyphens dropped. Every dot becomes an
 * underscore and every hyphen is removed with no replacement. Examples:
 * - `unboundair.poll-interval` -> `UNBOUNDAIR_POLLINTERVAL`
 * - `unboundair.offline-poll-interval` -> `UNBOUNDAIR_OFFLINEPOLLINTERVAL`
 * - `unboundair.output.modules` -> `UNBOUNDAIR_OUTPUT_MODULES`
 * - `unboundair.outbox.path` -> `UNBOUNDAIR_OUTBOX_PATH`
 *
 * @property pollInterval Poll interval for status checks (DL-01). Default 3 seconds.
 * @property offlinePollInterval Poll interval when scanner is offline (DL-02). Default 10 seconds.
 * @property batchTimeout Seconds after last page to close batch (DL-04). Default 20 seconds.
 * @property idleMinutes Idle handling in minutes, null means off (DL-06). Default null.
 * @property colorMode Color mode for processing, plain string to keep `config` leaf (SV-03). Default "gray".
 * @property keepRaw Keep raw JPEGs for debug (SV-06). Default false.
 * @property scanner where the scanner is reached (SC-06); see [ScannerProperties].
 * @property output which output modules are active (AU-03); see [OutputProperties].
 * @property outbox where documents are persisted before delivery (AU-04); see [OutboxProperties].
 */
@ConfigurationProperties(prefix = "unboundair")
data class UnboundAirProperties(
    @DurationUnit(ChronoUnit.SECONDS)
    val pollInterval: Duration = Duration.ofSeconds(3),
    @DurationUnit(ChronoUnit.SECONDS)
    val offlinePollInterval: Duration = Duration.ofSeconds(10),
    @DurationUnit(ChronoUnit.SECONDS)
    val batchTimeout: Duration = Duration.ofSeconds(20),
    val idleMinutes: Int? = null,
    /**
     * The `config` package is an architecture leaf and must not depend on `processing`,
     * so the value is carried as a plain string here and mapped to the `ColorMode` enum
     * by the composition root. Valid values are `gray` and `color`.
     */
    val colorMode: String = "gray",
    val keepRaw: Boolean = false,
    val scanner: ScannerProperties = ScannerProperties(),
    val output: OutputProperties = OutputProperties(),
    val outbox: OutboxProperties = OutboxProperties(),
) {
    /**
     * Where the scanner is reached (SC-06).
     *
     * In real operation these are constants; they are configurable so tests can
     * point the client at a fake scanner on a free port without a code change.
     *
     * @property host the scanner address. Default `192.168.18.33`.
     * @property port the scanner TCP port. Default `23`.
     */
    data class ScannerProperties(
        val host: String = "192.168.18.33",
        val port: Int = 23,
    )

    /**
     * Which output modules receive finished documents (AU-03).
     *
     * The list is evaluated at runtime; modules are never selected through
     * `@ConditionalOnProperty` or similar, because Spring does not support that
     * in GraalVM native images.
     *
     * @property modules the active module names, e.g. `paperless`. Default empty.
     */
    data class OutputProperties(
        val modules: List<String> = emptyList(),
    )

    /**
     * Where documents are persisted before delivery (AU-04).
     *
     * @property path the outbox directory. Default `/var/lib/unboundair/outbox`.
     */
    data class OutboxProperties(
        val path: String = "/var/lib/unboundair/outbox",
    )
}
