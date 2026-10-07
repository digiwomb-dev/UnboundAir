package dev.digiwomb.unboundair.guard

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Convention guard ("Konventionstest" of the Wächter layer in docs/teststrategie.md)
 * over translations: every English file under `docs/en/` is paired with its German
 * source under `docs/de/` by filename, and the guard goes red when the pair drifts
 * apart structurally (DO-15).
 *
 * **No language model, no network.** Every check below is arithmetic over the two
 * texts: code blocks, requirement IDs, headings, table rows, link targets and the
 * provenance marker. Whether the translation reads well stays a human's job — the
 * guard only proves it is structurally intact and current.
 *
 * **Pairs without a marker are skipped, not failed.** The marker
 * (`<!-- translated from docs/de/<file> @ <commit> -->` in line 1) arrives with
 * the translator (#231); before that there is nothing whose staleness could be
 * computed. Each rule is therefore checked twice: against the live repository
 * and against a synthetic pair that is known to be broken. The synthetic half
 * cannot skip and proves the rule bites.
 *
 * **Link targets are compared after two normalizations.** Fragments are stripped:
 * heading slugs derive from translated heading text, so `#spenden` and
 * `#donations` can never match and must not fail the build. A `.de.md` suffix
 * counts as `.md`: the German reader is sent to `CONTRIBUTING.de.md`, the
 * English one to `CONTRIBUTING.md` — same document, other language.
 *
 * **The staleness check needs history.** It asks `git rev-list --count` whether
 * the German file changed after the marker commit, which is a local process
 * call like `git ls-files` in [RepositoryHygieneTest] and stays offline (DC-03).
 * But it only works where history exists: whatever CI job runs `./gradlew test`
 * (CI-01) must check out with `fetch-depth: 0`, because the `actions/checkout`
 * default is a shallow clone without history. Gotten wrong, the guard silently
 * passes everything — the KDoc states this so the workflow cannot forget it.
 */
@Tag("guard")
class TranslationGuardTest {
    @Nested
    inner class Pairing {
        /**
         * DO-15 -- every English file has a German source under the same name.
         */
        @Test
        fun `DO-15 every English file pairs with a German source`() {
            val orphans = livePairs().filter { !Files.isRegularFile(it.german) }

            assertThat(orphans.map { repositoryRoot.relativize(it.english).toString() })
                .`as`("every English file must have a German source under the same name")
                .isEmpty()
        }

        @Test
        fun `DO-15 an English file without a German source is reported`(
            @TempDir root: Path,
        ) {
            val english = writePair(root, germanName = null, englishName = "orphan.md")

            assertThat(orphanViolation(root, english))
                .isEqualTo("docs/en/orphan.md has no German source docs/de/orphan.md")
        }
    }

    @Nested
    inner class CodeBlocks {
        /**
         * DO-15 -- code blocks match in count and content. A translated CLI
         * command is a broken CLI command.
         */
        @Test
        fun `DO-15 code blocks match in every live pair`() {
            assertThat(liveViolations(::codeBlockViolation))
                .`as`("code blocks must match in count and content (DO-15)")
                .isEmpty()
        }

        @Test
        fun `DO-15 an altered code block is reported`(
            @TempDir root: Path,
        ) {
            val pair = writePair(root, de = "Run:\n\n```bash\n./gradlew test\n```\n", en = "Run:\n\n```bash\n./gradlew check\n```\n")

            assertThat(codeBlockViolation(root, pair)).startsWith("docs/en/doc.md: code blocks differ")
        }

        @Test
        fun `DO-15 a matching pair passes`(
            @TempDir root: Path,
        ) {
            val pair = writePair(root, de = "Run:\n\n```bash\n./gradlew test\n```\n", en = "Run:\n\n```bash\n./gradlew test\n```\n")

            assertThat(codeBlockViolation(root, pair)).isNull()
        }
    }

    @Nested
    inner class RequirementIds {
        /**
         * DO-15 -- every requirement ID present in German survives. `SC-01`
         * and `AU-04` are anchors, not prose.
         */
        @Test
        fun `DO-15 requirement IDs survive in every live pair`() {
            assertThat(liveViolations(::requirementIdViolation))
                .`as`("requirement IDs present in German must survive translation (DO-15)")
                .isEmpty()
        }

        @Test
        fun `DO-15 a dropped requirement ID is reported`(
            @TempDir root: Path,
        ) {
            val pair = writePair(root, de = "Covers SC-01 and AU-04.\n", en = "Covers SC-01.\n")

            assertThat(requirementIdViolation(root, pair)).isEqualTo("docs/en/doc.md: requirement IDs missing: AU-04")
        }
    }

    @Nested
    inner class Headings {
        /**
         * DO-15 -- heading counts match. A dropped section is a dropped section.
         */
        @Test
        fun `DO-15 heading counts match in every live pair`() {
            assertThat(liveViolations(::headingViolation))
                .`as`("heading counts must match (DO-15)")
                .isEmpty()
        }

        @Test
        fun `DO-15 a dropped heading is reported`(
            @TempDir root: Path,
        ) {
            val pair = writePair(root, de = "# One\n\n# Two\n", en = "# One\n")

            assertThat(headingViolation(root, pair)).isEqualTo("docs/en/doc.md: headings differ (2 vs 1)")
        }
    }

    @Nested
    inner class TableRows {
        /**
         * DO-15 -- table row counts match. Silently losing a config row loses
         * a default.
         */
        @Test
        fun `DO-15 table row counts match in every live pair`() {
            assertThat(liveViolations(::tableRowViolation))
                .`as`("table row counts must match (DO-15)")
                .isEmpty()
        }

        @Test
        fun `DO-15 a dropped table row is reported`(
            @TempDir root: Path,
        ) {
            val pair =
                writePair(
                    root,
                    de = "| a | b |\n|---|---|\n| 1 | 2 |\n",
                    en = "| a | b |\n|---|---|\n",
                )

            assertThat(tableRowViolation(root, pair)).isEqualTo("docs/en/doc.md: table rows differ (3 vs 2)")
        }
    }

    @Nested
    inner class LinkTargets {
        /**
         * DO-15 -- link targets match after normalization. A translated path
         * points nowhere.
         */
        @Test
        fun `DO-15 link targets match in every live pair`() {
            assertThat(liveViolations(::linkTargetViolation))
                .`as`("link targets must match after normalization (DO-15)")
                .isEmpty()
        }

        @Test
        fun `DO-15 a rewritten link target is reported`(
            @TempDir root: Path,
        ) {
            val pair = writePair(root, de = "See [guide](configuration.md).\n", en = "See [guide](setup.md).\n")

            assertThat(linkTargetViolation(root, pair)).startsWith("docs/en/doc.md: link targets differ")
        }

        @Test
        fun `DO-15 locale counterparts and fragments do not fail`(
            @TempDir root: Path,
        ) {
            val pair =
                writePair(
                    root,
                    de = "See [rules](../../CONTRIBUTING.de.md) and [part](other.md#abschnitt).\n",
                    en = "See [rules](../../CONTRIBUTING.md) and [part](other.md#section).\n",
                )

            assertThat(linkTargetViolation(root, pair)).isNull()
        }
    }

    @Nested
    inner class Provenance {
        /**
         * DO-15 -- no translation is older than its German source. Pairs
         * without a marker are skipped: the marker arrives with the translator
         * (#231), and untranslatable staleness must not fail the build.
         */
        @Test
        fun `DO-15 no translation is older than its German source`() {
            val pairs = livePairs().filter { Files.isRegularFile(it.german) }
            assumeTrue(gitAvailable(), "git cannot be asked here, so staleness cannot be computed")

            val violations = pairs.mapNotNull { provenanceViolation(repositoryRoot, it) }

            assertThat(violations)
                .`as`("no translation must be older than its German source (DO-15)")
                .isEmpty()
        }

        @Test
        fun `DO-15 a changed German file with an untouched translation goes red`(
            @TempDir root: Path,
        ) {
            assumeTrue(gitAvailable(), "the staleness proof needs git")
            val pair = initGitPair(root, german = "# Title\n")
            val first = gitRev(root, "HEAD")
            appendGitPair(root, germanExtra = "\nMore.\n")
            // Marker still names the first commit: stale.
            setMarker(root, pair, "docs/de/doc.md", first)

            assertThat(provenanceViolation(root, pair)).startsWith("docs/en/doc.md: translation older than docs/de/doc.md")
        }

        @Test
        fun `DO-15 a current marker passes and a missing marker skips`(
            @TempDir root: Path,
        ) {
            assumeTrue(gitAvailable(), "the staleness proof needs git")
            val pair = initGitPair(root, german = "# Title\n")
            setMarker(root, pair, "docs/de/doc.md", gitRev(root, "HEAD"))

            assertThat(provenanceViolation(root, pair)).isNull()

            val unmarked = writePair(root, de = "# Title\n", en = "# Title\n")
            assertThat(provenanceViolation(root, unmarked)).isNull()
        }
    }

    private fun livePairs(): List<DocPair> {
        val englishDir = repositoryRoot.resolve("docs/en")
        val germanDir = repositoryRoot.resolve("docs/de")
        if (!Files.isDirectory(englishDir)) return emptyList()
        return Files.list(englishDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .map { english -> DocPair(germanDir.resolve(english.fileName.toString()), english) }
                .toList()
        }
    }

    private fun liveViolations(check: (Path, DocPair) -> String?): List<String> =
        livePairs().filter { Files.isRegularFile(it.german) }.mapNotNull { check(repositoryRoot, it) }

    private fun orphanViolation(
        root: Path,
        english: Path,
    ): String? {
        val german = root.resolve("docs/de").resolve(english.fileName.toString())
        if (Files.isRegularFile(german)) return null
        return "${root.relativize(english)} has no German source ${root.relativize(german)}"
    }

    private fun codeBlockViolation(
        root: Path,
        pair: DocPair,
    ): String? {
        val german = codeBlocks(bodyOf(pair, germanSide = true))
        val english = codeBlocks(bodyOf(pair, germanSide = false))
        if (german == english) return null
        return "${root.relativize(pair.english)}: code blocks differ (${german.size} vs ${english.size})"
    }

    private fun requirementIdViolation(
        root: Path,
        pair: DocPair,
    ): String? {
        val missing = (requirementIds(bodyOf(pair, germanSide = true)) - requirementIds(bodyOf(pair, germanSide = false))).sorted()
        if (missing.isEmpty()) return null
        return "${root.relativize(pair.english)}: requirement IDs missing: ${missing.joinToString(", ")}"
    }

    private fun headingViolation(
        root: Path,
        pair: DocPair,
    ): String? {
        val german = bodyOf(pair, germanSide = true).lines().count { HEADING.containsMatchIn(it) }
        val english = bodyOf(pair, germanSide = false).lines().count { HEADING.containsMatchIn(it) }
        if (german == english) return null
        return "${root.relativize(pair.english)}: headings differ ($german vs $english)"
    }

    private fun tableRowViolation(
        root: Path,
        pair: DocPair,
    ): String? {
        val german = bodyOf(pair, germanSide = true).lines().count { it.trimStart().startsWith("|") }
        val english = bodyOf(pair, germanSide = false).lines().count { it.trimStart().startsWith("|") }
        if (german == english) return null
        return "${root.relativize(pair.english)}: table rows differ ($german vs $english)"
    }

    private fun linkTargetViolation(
        root: Path,
        pair: DocPair,
    ): String? {
        val german = linkTargets(bodyOf(pair, germanSide = true))
        val english = linkTargets(bodyOf(pair, germanSide = false))
        if (german == english) return null
        return "${root.relativize(pair.english)}: link targets differ ($german vs $english)"
    }

    private fun provenanceViolation(
        root: Path,
        pair: DocPair,
    ): String? {
        val marker = markerOf(pair) ?: return null
        val (path, commit) = marker
        val expected = root.relativize(pair.german).toString()
        if (path != expected) return "${root.relativize(pair.english)}: marker points at $path, expected $expected"
        val changes = gitRevListCount(root, commit, expected) ?: return "${root.relativize(pair.english)}: marker commit $commit is unknown"
        if (changes > 0) {
            return "${root.relativize(pair.english)}: translation older than $expected " +
                "($changes change(s) since $commit)"
        }
        return null
    }

    private fun bodyOf(
        pair: DocPair,
        germanSide: Boolean,
    ): String {
        val text = Files.readString(if (germanSide) pair.german else pair.english)
        // The provenance marker lives in line 1 of the English file by
        // convention; it is metadata about the pair, never content.
        if (!germanSide) {
            val lines = text.lines()
            if (lines.isNotEmpty() && MARKER.containsMatchIn(lines.first())) {
                return lines.drop(1).joinToString("\n")
            }
        }
        return text
    }

    private fun markerOf(pair: DocPair): Pair<String, String>? {
        val first = Files.readString(pair.english).lines().firstOrNull() ?: return null
        val match = MARKER.find(first) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun gitAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("git", "--version").start()
            process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)

    private fun gitRevListCount(
        root: Path,
        commit: String,
        path: String,
    ): Int? =
        runCatching {
            val process =
                ProcessBuilder("git", "rev-list", "--count", "$commit..HEAD", "--", path)
                    .directory(root.toFile())
                    .start()
            val output = process.inputStream.use { it.readAllBytes().decodeToString() }.trim()
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            output.toIntOrNull()
        }.getOrNull()

    private fun writePair(
        root: Path,
        de: String,
        en: String,
    ): DocPair {
        val germanDir = root.resolve("docs/de")
        val englishDir = root.resolve("docs/en")
        Files.createDirectories(germanDir)
        Files.createDirectories(englishDir)
        val german = germanDir.resolve("doc.md")
        val english = englishDir.resolve("doc.md")
        Files.writeString(german, de)
        Files.writeString(english, en)
        return DocPair(german, english)
    }

    private fun writePair(
        root: Path,
        germanName: String?,
        englishName: String,
    ): Path {
        val englishDir = root.resolve("docs/en")
        Files.createDirectories(englishDir)
        if (germanName != null) {
            val germanDir = root.resolve("docs/de")
            Files.createDirectories(germanDir)
            Files.writeString(germanDir.resolve(germanName), "# Title\n")
        }
        val english = englishDir.resolve(englishName)
        Files.writeString(english, "# Title\n")
        return english
    }

    private fun setMarker(
        root: Path,
        pair: DocPair,
        path: String,
        commit: String,
    ) {
        val english = Files.readString(pair.english)
        Files.writeString(pair.english, "<!-- translated from $path @ $commit -->\n$english")
    }

    private fun gitRev(
        root: Path,
        revision: String,
    ): String {
        val process =
            ProcessBuilder("git", "rev-parse", revision)
                .directory(root.toFile())
                .start()
        val output = process.inputStream.use { it.readAllBytes().decodeToString() }.trim()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git rev-parse failed" }
        return output
    }

    private fun initGitPair(
        root: Path,
        german: String,
    ): DocPair {
        val pair = writePair(root, de = german, en = "# Title\n")
        git(root, "init")
        git(root, "config", "user.email", "test@example.org")
        git(root, "config", "user.name", "Test")
        git(root, "add", ".")
        git(root, "commit", "-m", "initial")
        return pair
    }

    private fun appendGitPair(
        root: Path,
        germanExtra: String,
    ) {
        val german = root.resolve("docs/de/doc.md")
        Files.writeString(german, Files.readString(german) + germanExtra)
        git(root, "add", ".")
        git(root, "commit", "-m", "change")
    }

    private fun git(
        root: Path,
        vararg args: String,
    ) {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(root.toFile())
                .start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} failed" }
    }

    private data class DocPair(
        val german: Path,
        val english: Path,
    )

    private companion object {
        val MARKER = Regex("""<!--\s*translated from (\S+) @ ([0-9a-f]+)\s*-->""")

        val HEADING = Regex("""#{1,6}\s""")

        val REQUIREMENT_ID = Regex("""\b[A-Z]{2}-\d{2}\b""")

        val LINK = Regex("]\\(([^)]+)\\)")

        /**
         * The repository root, found the same way as in [RepositoryHygieneTest]:
         * walk up until a directory contains the build script and `docs/`.
         */
        val repositoryRoot: Path =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .firstOrNull { Files.isRegularFile(it.resolve("build.gradle.kts")) && Files.isDirectory(it.resolve("docs")) }
                ?: throw IllegalStateException(
                    "repository root not found above ${Path.of("").toAbsolutePath()}; " +
                        "expected a directory containing build.gradle.kts and docs/",
                )

        private fun codeBlocks(text: String): List<String> {
            val blocks = mutableListOf<String>()
            var current: MutableList<String>? = null
            for (raw in text.lines()) {
                val line = raw.trimEnd()
                if (line.trimStart().startsWith("```")) {
                    if (current == null) {
                        current = mutableListOf(line.trim())
                    } else {
                        current.add(line.trim())
                        blocks.add(current.joinToString("\n"))
                        current = null
                    }
                } else {
                    current?.add(line)
                }
            }
            if (current != null) blocks.add(current.joinToString("\n"))
            return blocks
        }

        private fun requirementIds(text: String): Set<String> = REQUIREMENT_ID.findAll(text).map { it.value }.toSet()

        private fun linkTargets(text: String): List<String> =
            // Absolute URLs are compared for equality as well: that needs no
            // network, and a rewritten URL is exactly what DO-15 forbids.
            LINK
                .findAll(text)
                .map { it.groupValues[1].trim().substringBefore("#") }
                .filter { it.isNotEmpty() && !it.startsWith("mailto:") }
                .map { if (it.endsWith(".de.md")) it.dropLast(".de.md".length) + ".md" else it }
                .sorted()
                .toList()
    }
}
