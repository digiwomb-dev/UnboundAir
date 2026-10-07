package dev.digiwomb.unboundair.guard

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Convention guard ("Konventionstest" of the Wächter layer in docs/internal/teststrategie.md)
 * over the deployment examples in `docs/de/operations.md` (DP-01).
 *
 * The failure mode worth a test: Spring's relaxed binding drops hyphens
 * without complaint, so a typo in an environment variable name produces a
 * running container with the wrong configuration and no error anywhere. A
 * reader copies the example, the container starts, and the scanner is never
 * reached. This guard checks not "does the file parse" but "does every key
 * in it correspond to a property that actually exists" — against the
 * property reference in `docs/de/configuration.md`, applying the same
 * relaxed-binding rules Spring does rather than a simplified version that
 * would miss exactly the typos being looked for.
 *
 * **What the test expects the page to look like.** Both examples live as
 * fenced code blocks in the operations page, and the block that belongs to
 * a file carries `# Datei: <name>` as its first content line
 * (`compose.yaml`, `unboundair.env`, `unboundair.container`). The test
 * finds the examples by those markers — keep them when editing the page,
 * or the guard fails in a way that looks unrelated to the edit. Shell
 * snippets without such a line are ignored.
 *
 * **Only the German source is checked.** The English blocks are
 * byte-identical by the translation guard's code-block rule, so checking
 * them would re-verify identical bytes at double the maintenance.
 *
 * What this guard does not do, so its reach is not overestimated:
 *
 * - **It does not start a container.** A valid property with an unusable
 *   value — a wrong port, an unreachable host — passes.
 * - **It does not check the property's type or range**, only that the key
 *   exists.
 * - **It does not verify the Quadlet's systemd syntax.** A structurally
 *   invalid unit file with correct environment keys would pass. That would
 *   need `systemd-analyze`, which is not available in the test environment.
 * - **It cannot see values injected at runtime** from an environment file,
 *   which is exactly where the secrets live.
 *
 * Everything here is local file work and stays offline (DC-03).
 */
@Tag("guard")
class DeploymentExampleTest {
    /**
     * DP-01 -- the live examples: every key exists, both configure the same
     * thing, the image name is the published one.
     */
    @Test
    fun `DP-01 deployment examples match the property reference`() {
        val operations = repositoryRoot.resolve("docs/de/operations.md")
        val configuration = repositoryRoot.resolve("docs/de/configuration.md")

        assertThat(checkExamples(operations, configuration))
            .`as`("every key in both deployment examples must exist (DP-01)")
            .isEmpty()
    }

    @Nested
    inner class PlantedViolations {
        @Test
        fun `DP-01 an environment key mapping to no property is reported`(
            @TempDir root: Path,
        ) {
            val required =
                listOf("UNBOUNDAIR_OUTPUT_MODULES", "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL", "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE")
            val files =
                writeExamplePair(
                    root,
                    composeKeys = required + "UNBOUNDAIR_SCANNER_PORTS",
                    quadletKeys =
                        required + "UNBOUNDAIR_SCANNER_PORTS",
                )

            assertThat(checkExamples(files.first, files.second))
                .containsExactly(
                    "compose.yaml: UNBOUNDAIR_SCANNER_PORTS maps to no documented property",
                    "unboundair.container: UNBOUNDAIR_SCANNER_PORTS maps to no documented property",
                )
        }

        @Test
        fun `DP-01 a non-canonical spelling is reported with the expected form`(
            @TempDir root: Path,
        ) {
            val files =
                writeExamplePair(
                    root,
                    composeKeys =
                        listOf(
                            "UNBOUNDAIR_OUTPUT_MODULES",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN_FILE",
                        ),
                    quadletKeys =
                        listOf(
                            "UNBOUNDAIR_OUTPUT_MODULES",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN_FILE",
                        ),
                )

            assertThat(checkExamples(files.first, files.second))
                .containsExactly(
                    "compose.yaml: UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN_FILE is not the documented spelling, expected UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE",
                    "unboundair.container: UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN_FILE is not the documented spelling, expected UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE",
                )
        }

        @Test
        fun `DP-01 a required property missing from an example is reported`(
            @TempDir root: Path,
        ) {
            val files =
                writeExamplePair(
                    root,
                    composeKeys =
                        listOf(
                            "UNBOUNDAIR_OUTPUT_MODULES",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE",
                        ),
                    quadletKeys = listOf("UNBOUNDAIR_OUTPUT_MODULES", "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE"),
                )

            assertThat(checkExamples(files.first, files.second))
                .containsExactly(
                    "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL is present in compose.yaml but absent from unboundair.container",
                    "unboundair.container: required UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL is missing",
                )
        }

        @Test
        fun `DP-01 a key in only one example is reported`(
            @TempDir root: Path,
        ) {
            val files =
                writeExamplePair(
                    root,
                    composeKeys =
                        listOf(
                            "UNBOUNDAIR_OUTPUT_MODULES",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE",
                            "UNBOUNDAIR_SCANNER_HOST",
                        ),
                    quadletKeys =
                        listOf(
                            "UNBOUNDAIR_OUTPUT_MODULES",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE",
                        ),
                )

            assertThat(checkExamples(files.first, files.second))
                .containsExactly("UNBOUNDAIR_SCANNER_HOST is present in compose.yaml but absent from unboundair.container")
        }

        @Test
        fun `DP-01 a wrong image name is reported`(
            @TempDir root: Path,
        ) {
            val operations = root.resolve("docs/de/operations.md")
            Files.createDirectories(operations.parent)
            Files.writeString(
                operations,
                """
                ```yaml
                # Datei: compose.yaml
                services:
                  unboundair:
                    image: docker.io/library/unboundair:nightly
                ```
                ```ini
                # Datei: unboundair.env
                UNBOUNDAIR_OUTPUT_MODULES=paperless
                UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org
                UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token
                ```
                ```ini
                # Datei: unboundair.container
                Image=docker.io/library/unboundair:nightly
                Environment=UNBOUNDAIR_OUTPUT_MODULES=paperless
                Environment=UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL=https://paperless.example.org
                Environment=UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE=/run/secrets/paperless-token
                ```
                """.trimIndent(),
            )
            val configuration = minimalConfiguration(root)

            assertThat(checkExamples(operations, configuration))
                .containsExactly(
                    "compose.yaml: image name docker.io/library/unboundair is not ghcr.io/digiwomb-dev/unboundair",
                    "unboundair.container: image name docker.io/library/unboundair is not ghcr.io/digiwomb-dev/unboundair",
                )
        }

        @Test
        fun `DP-01 clean examples pass`(
            @TempDir root: Path,
        ) {
            val files =
                writeExamplePair(
                    root,
                    composeKeys =
                        listOf(
                            "UNBOUNDAIR_OUTPUT_MODULES",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE",
                        ),
                    quadletKeys =
                        listOf(
                            "UNBOUNDAIR_OUTPUT_MODULES",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
                            "UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE",
                        ),
                )

            assertThat(checkExamples(files.first, files.second)).isEmpty()
        }
    }

    private fun checkExamples(
        operations: Path,
        configuration: Path,
    ): List<String> {
        val violations = mutableListOf<String>()
        val blocks = codeBlocksByFile(operations)
        val documented = documentedProperties(configuration)

        // The compose file carries no keys itself: they live in the env file
        // it references. Labels below still name the examples, not the blocks.
        val composeKeys = environmentKeys(blocks["unboundair.env"], "UNBOUNDAIR_")
        val quadletKeys = environmentKeys(blocks["unboundair.container"], "UNBOUNDAIR_")
        violations += unknownKeys("compose.yaml", composeKeys, documented)
        violations += unknownKeys("unboundair.container", quadletKeys, documented)
        violations += missingRequired("compose.yaml", composeKeys)
        violations += missingRequired("unboundair.container", quadletKeys)

        for (key in composeKeys - quadletKeys) {
            violations += "$key is present in compose.yaml but absent from unboundair.container"
        }
        for (key in quadletKeys - composeKeys) {
            violations += "$key is present in unboundair.container but absent from compose.yaml"
        }

        violations += imageViolation("compose.yaml", blocks["compose.yaml"], "image:")
        violations += imageViolation("unboundair.container", blocks["unboundair.container"], "Image=")
        return violations.sorted()
    }

    private fun codeBlocksByFile(operations: Path): Map<String, String> {
        val blocks = mutableMapOf<String, String>()
        var current: MutableList<String>? = null
        for (raw in Files.readAllLines(operations)) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (current == null) {
                    current = mutableListOf()
                } else {
                    val name =
                        current
                            .firstOrNull()
                            ?.removePrefix("# Datei:")
                            ?.trim()
                            ?.split(" ")
                            ?.firstOrNull()
                    if (name != null && current.size > 1) blocks[name] = current.drop(1).joinToString("\n")
                    current = null
                }
            } else {
                current?.add(line)
            }
        }
        return blocks
    }

    private fun environmentKeys(
        block: String?,
        prefix: String,
    ): Set<String> {
        if (block == null) return emptySet()
        return block
            .lines()
            .map { it.trim() }
            .filter { it.startsWith(prefix) || it.startsWith("Environment=$prefix") }
            .map { it.removePrefix("Environment=").substringBefore("=").trim() }
            .filter { it.matches(Regex("[A-Z][A-Z0-9_]*")) }
            .toSet()
    }

    private fun documentedProperties(configuration: Path): DocumentedProperties {
        val exactEnvironments = mutableSetOf<String>()
        val canonicalProperties = mutableMapOf<String, String>()
        for (raw in Files.readAllLines(configuration)) {
            val line = raw.trim()
            if (!line.startsWith("|")) continue
            val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.size < 2) continue
            val property = cells[0].removeSurrounding("`")
            val environment = cells[1].removeSurrounding("`")
            if (!environment.matches(Regex("UNBOUNDAIR_[A-Z_]+"))) continue
            exactEnvironments += environment
            canonicalProperties[canonical(environment)] = environment
        }
        return DocumentedProperties(exactEnvironments, canonicalProperties)
    }

    private fun unknownKeys(
        file: String,
        keys: Set<String>,
        documented: DocumentedProperties,
    ): List<String> =
        keys.sorted().mapNotNull { key ->
            when {
                key in documented.exactEnvironments -> {
                    null
                }

                canonical(key) in documented.canonicalProperties -> {
                    "$file: $key is not the documented spelling, expected ${documented.canonicalProperties[canonical(key)]}"
                }

                else -> {
                    "$file: $key maps to no documented property"
                }
            }
        }

    private fun missingRequired(
        file: String,
        keys: Set<String>,
    ): List<String> {
        // Canonical comparison on purpose: a non-canonical spelling still
        // binds to the same property at runtime, so the value is provided —
        // the spelling itself is unknownKeys' complaint, not this one's.
        val canonicalKeys = keys.map { canonical(it) }.toSet()
        val violations = mutableListOf<String>()
        for (required in REQUIRED_ENVIRONMENTS) {
            if (canonical(required) !in canonicalKeys) violations += "$file: required $required is missing"
        }
        if (canonical("UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN") !in canonicalKeys &&
            canonical("UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE") !in canonicalKeys
        ) {
            violations +=
                "$file: required paperless token is missing (UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN or UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE)"
        }
        return violations
    }

    private fun imageViolation(
        file: String,
        block: String?,
        key: String,
    ): List<String> {
        val line = block?.lines()?.firstOrNull { it.trim().startsWith(key) } ?: return listOf("$file: no image found")
        // Strip the key itself first (`image:` vs `Image=`): substringAfter(":")
        // would otherwise eat up to the port colon in the Quadlet form.
        val reference =
            line
                .trim()
                .removePrefix(key)
                .trim()
                .removePrefix("=")
                .removePrefix(":")
                .trim()
                .substringBefore(" ")
        val name = reference.substringBeforeLast(":")
        if (name != EXPECTED_IMAGE) return listOf("$file: image name $name is not $EXPECTED_IMAGE")
        if (!reference.contains(":")) return listOf("$file: image $reference carries no tag")
        return emptyList()
    }

    private fun writeExamplePair(
        root: Path,
        composeKeys: List<String>,
        quadletKeys: List<String>,
    ): Pair<Path, Path> {
        val operations = root.resolve("docs/de/operations.md")
        Files.createDirectories(operations.parent)
        // No shared indentation here on purpose: trimIndent strips only the
        // common indent, and interpolated key lines start at column 0, so a
        // heredoc would leave every static line indented and break the
        // `# Datei:` first-line convention the extraction relies on.
        val lines =
            mutableListOf(
                "```yaml",
                "# Datei: compose.yaml",
                "services:",
                "  unboundair:",
                "    image: ghcr.io/digiwomb-dev/unboundair:nightly",
                "```",
                "```ini",
                "# Datei: unboundair.env",
            )
        lines += composeKeys
        lines +=
            listOf(
                "```",
                "```ini",
                "# Datei: unboundair.container",
                "Image=ghcr.io/digiwomb-dev/unboundair:nightly",
            )
        lines += quadletKeys.map { "Environment=$it=value" }
        lines += "```"
        val operationsText = lines.joinToString("\n")
        Files.writeString(operations, operationsText)
        return operations to minimalConfiguration(root)
    }

    private fun minimalConfiguration(root: Path): Path {
        val configuration = root.resolve("docs/de/configuration.md")
        Files.createDirectories(configuration.parent)
        Files.writeString(
            configuration,
            """
            | Property | Environment variable |
            |---|---|
            | `unboundair.output.modules` | `UNBOUNDAIR_OUTPUT_MODULES` |
            | `unboundair.output.paperless.base-url` | `UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL` |
            | `unboundair.output.paperless.token` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKEN` |
            | `unboundair.output.paperless.token-file` | `UNBOUNDAIR_OUTPUT_PAPERLESS_TOKENFILE` |
            | `unboundair.scanner.host` | `UNBOUNDAIR_SCANNER_HOST` |
            """.trimIndent(),
        )
        return configuration
    }

    private data class DocumentedProperties(
        val exactEnvironments: Set<String>,
        val canonicalProperties: Map<String, String>,
    )

    private companion object {
        /**
         * The repository root, found the same way as in [RepositoryHygieneTest].
         */
        val repositoryRoot: Path =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .firstOrNull { Files.isRegularFile(it.resolve("build.gradle.kts")) && Files.isDirectory(it.resolve("docs")) }
                ?: throw IllegalStateException(
                    "repository root not found above ${Path.of("").toAbsolutePath()}; " +
                        "expected a directory containing build.gradle.kts and docs/",
                )

        const val EXPECTED_IMAGE = "ghcr.io/digiwomb-dev/unboundair"

        /**
         * Properties without which the example cannot work: no modules means
         * nothing is ever delivered, no base URL leaves the paperless module
         * unusable. The token pair is checked separately — either form
         * suffices, since the service accepts exactly one of the two. All of
         * these have working defaults nowhere: omitting them breaks the
         * example, while scanner host, port and outbox path fall back to
         * documented defaults.
         */
        val REQUIRED_ENVIRONMENTS =
            listOf(
                "UNBOUNDAIR_OUTPUT_MODULES",
                "UNBOUNDAIR_OUTPUT_PAPERLESS_BASEURL",
            )

        private fun canonical(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
    }
}
