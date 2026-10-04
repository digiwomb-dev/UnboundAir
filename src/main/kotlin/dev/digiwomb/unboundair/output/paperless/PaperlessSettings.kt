package dev.digiwomb.unboundair.output.paperless

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The settings of the paperless-ngx module as a plain value object (AU-05).
 *
 * Follows the `PageSettings` pattern: no Spring annotation, no injected
 * `UnboundAirProperties`. The composition root maps the
 * `unboundair.output.paperless.*` configuration onto these constructor values,
 * which keeps the core free of the framework and this object constructible in a
 * unit test without a context.
 *
 * [token] is the **resolved** token, never the raw configuration. AU-05 lets
 * the token arrive either as an environment variable or as a file whose path
 * arrives as an environment variable; the upload may send exactly one value.
 * [fromConfigured] performs that resolution and fails at construction on every
 * misconfiguration, because a module with an empty token would discover it as
 * an opaque 401 at the first document, long after startup.
 *
 * The remaining fields are the optional paperless fields (AU-05): a `null` or
 * empty value means "not set", so paperless derives the value itself.
 *
 * @property baseUrl the base URL of the paperless instance.
 * @property token the resolved API token, sent as `Authorization: Token ...`.
 * @property tags the numeric tag IDs, sent as repeated `tags` fields. Default: none.
 * @property correspondent the correspondent ID. Default: `null`, not sent.
 * @property documentType the document type ID. Default: `null`, not sent.
 */
data class PaperlessSettings(
    val baseUrl: String,
    val token: String,
    val tags: List<Long> = emptyList(),
    val correspondent: Long? = null,
    val documentType: Long? = null,
) {
    /**
     * A data class prints **all** of its fields in `toString`, [token] included.
     * A token that lands in a log line or an exception message ends up in the
     * journal, which is the one place an operator copies into a bug report --
     * so the token stays masked here. This override is load-bearing, not noise:
     * removing it would leak the secret through every place the object is printed.
     */
    override fun toString(): String =
        "PaperlessSettings(baseUrl=$baseUrl, token=***, tags=$tags, correspondent=$correspondent, documentType=$documentType)"

    companion object {
        /**
         * Builds the settings from the raw configured values, resolving the two
         * token sources of AU-05 into the one [token] the upload sends. Called
         * from the composition root; `config` is an architecture leaf and must
         * not read files, which is why the resolution lives here.
         *
         * The token cases:
         *
         * - **Token file given, with or without a token:** the file wins and must
         *   read. A mounted file is the stronger signal of intent -- rotating the
         *   token means replacing its contents -- and a stale inline token would
         *   defeat a rotation invisibly. The inline token is simply ignored; it is
         *   a secret, so its presence cannot even be mentioned in a message.
         * - **Only the token given (no file):** the token wins, as-is.
         * - **Neither given:** [IllegalArgumentException], mirroring `OutputModules`
         *   failing for an unknown module name; `UnboundAirApplication.run` maps
         *   it to a logged error and exit code 1, so the service refuses to start
         *   instead of discovering the gap at the first document.
         * - **File given but unreadable or empty:** [IllegalArgumentException] that
         *   names the path, so the operator knows what to fix -- never the contents.
         *
         * A token read from a file is trimmed of surrounding whitespace. The
         * practical case is the trailing newline that `echo "token" > file` leaves
         * behind -- the single most likely way a mounted token breaks in practice.
         *
         * @param baseUrl the base URL of the paperless instance.
         * @param token the token as configured (environment variable), or `null`.
         * @param tokenFilePath the path to a file containing the token, or `null`.
         * @param tags the tag IDs to apply to the document; default: none.
         * @param correspondent the correspondent ID; default: `null`, not sent.
         * @param documentType the document type ID; default: `null`, not sent.
         */
        fun fromConfigured(
            baseUrl: String,
            token: String?,
            tokenFilePath: String?,
            tags: List<Long> = emptyList(),
            correspondent: Long? = null,
            documentType: Long? = null,
        ): PaperlessSettings {
            val inline = token?.trim()?.takeIf { it.isNotEmpty() }
            val fromFile = tokenFilePath?.trim()?.takeIf { it.isNotEmpty() }?.let { tokenFromFile(Path.of(it)) }
            val resolved = fromFile ?: inline
            require(resolved != null) {
                "paperless: no token configured -- set the token or the path to a token file (AU-05)"
            }
            return PaperlessSettings(baseUrl, resolved, tags, correspondent, documentType)
        }

        /**
         * Reads the token from [path]. The underlying [IOException] is wrapped in
         * [IllegalArgumentException] without echoing its message, so no part of
         * the file can ever reach a log line or a journal entry; only the path
         * of the file is named, which is what the operator needs to fix it.
         */
        private fun tokenFromFile(path: Path): String {
            val content =
                try {
                    path.readText()
                } catch (e: IOException) {
                    throw IllegalArgumentException(
                        "paperless: token file $path is not readable; check that it exists and is accessible (AU-05)",
                    )
                }
            val token = content.trim()
            require(token.isNotEmpty()) {
                "paperless: token file $path is empty; the file must contain the token (AU-05)"
            }
            return token
        }
    }
}
