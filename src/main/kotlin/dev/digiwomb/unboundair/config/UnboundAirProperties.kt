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
 * underscore and every hyphen is removed with no replacement.
 *
 * Spring's relaxed binding is more forgiving than that: measured, it also accepts a
 * hyphen spelled as an underscore (`UNBOUNDAIR_OUTPUT_PAPERLESS_BASE_URL` binds just
 * like `..._BASEURL`, and with both set the hyphen-dropped form wins). The names below
 * are nonetheless the documented ones, and the only ones the documentation promises:
 * one spelling per setting is what makes a configuration reviewable, and the tolerance
 * is an implementation detail of Spring rather than a guarantee this project gives.
 * Examples:
 * - `unboundair.poll-interval` -> `UNBOUNDAIR_POLLINTERVAL`
 * - `unboundair.offline-poll-interval` -> `UNBOUNDAIR_OFFLINEPOLLINTERVAL`
 * - `unboundair.output.modules` -> `UNBOUNDAIR_OUTPUT_MODULES`
 * - `unboundair.outbox.path` -> `UNBOUNDAIR_OUTBOX_PATH`
 * - `unboundair.output.paperless.base-url` -> `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL`
 * - `unboundair.output.paperless.token-file` -> `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE`
 * - `unboundair.bw-threshold` -> `UNBOUNDAIR_BWTHRESHOLD`
 * - `unboundair.dpi` -> `UNBOUNDAIR_DPI`
 *
 * @property pollInterval Poll interval for status checks (DL-01). Default 3 seconds.
 * @property offlinePollInterval Poll interval when scanner is offline (DL-02). Default 10 seconds.
 * @property batchTimeout Seconds after last page to close batch (DL-04). Default 20 seconds.
 * @property idleMinutes Idle handling in minutes, null means off (DL-06). Default null.
 * @property colorMode Color mode for processing, plain string to keep `config` leaf (SV-03). Default "gray".
 * @property bwThreshold Luma threshold for color-mode bw, 1..255 (SV-08). Default 128.
 * @property keepRaw Keep raw JPEGs for debug (SV-06). Default false.
 * @property dpi Scan resolution in DPI, 300 or 600 (SC-07, SC-08). Default 300.
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
     * by the composition root. Valid values are `gray`, `color` and `bw`.
     */
    val colorMode: String = "gray",
    /**
     * Luma threshold for color-mode `bw` (SV-08). Carried here, validated where
     * `PageSettings` is built (#153): no `@Min`/`@Max` here on purpose, so one bad
     * value produces one error message where it is easiest to read.
     */
    val bwThreshold: Int = 128,
    val keepRaw: Boolean = false,
    val dpi: Int = 300,
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
     * @property paperless the settings of the paperless-ngx module (AU-05); see
     *   [PaperlessProperties]. Nested here so the names come out as
     *   `unboundair.output.paperless.*`, which is what AU-03 asks of every module.
     */
    data class OutputProperties(
        val modules: List<String> = emptyList(),
        val paperless: PaperlessProperties = PaperlessProperties(),
    )

    /**
     * The paperless-ngx module (AU-05).
     *
     * These are the **raw** configured values. Turning [token] and [tokenFile] into the
     * one token the upload sends is `PaperlessSettings`' job, not this one: `config` is
     * an architecture leaf and must not read a file. The same leaf property is why
     * nothing here is validated beyond its type -- an unreachable base URL or a missing
     * token surfaces when the settings are built, with a message that names the cause.
     *
     * `title` and `created` deliberately have no settings. docs/plan.md fixes that we do
     * not send them, so paperless derives both from the file name itself; a knob here
     * would invite someone to switch that decision on without reading why it was made.
     *
     * Environment variable names follow the rule from the class KDoc -- each dot becomes
     * an underscore and each hyphen simply disappears:
     *
     * - `unboundair.output.paperless.base-url` -> `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL`
     * - `unboundair.output.paperless.token-file` -> `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE`
     * - `unboundair.output.paperless.document-type` -> `UNBOUNDAIR_OUTPUT_PAPERLESS_DOCUMENTTYPE`
     *
     * @property baseUrl the paperless instance, e.g. `https://paperless.example.org`.
     *   Empty by default: there is no sensible guess, and the module is unusable without it.
     * @property token the API token. Default empty; set this **or** [tokenFile] (AU-05).
     * @property tokenFile path to a file holding the token, e.g. a mounted secret.
     *   Default empty. The file is read by `PaperlessSettings`, not here.
     * @property tags numeric tag IDs to attach to every document. Default empty.
     * @property correspondent numeric correspondent ID, null means none. Default null.
     * @property documentType numeric document type ID, null means none. Default null.
     */
    data class PaperlessProperties(
        val baseUrl: String = "",
        val token: String = "",
        val tokenFile: String = "",
        val tags: List<Long> = emptyList(),
        val correspondent: Long? = null,
        val documentType: Long? = null,
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
