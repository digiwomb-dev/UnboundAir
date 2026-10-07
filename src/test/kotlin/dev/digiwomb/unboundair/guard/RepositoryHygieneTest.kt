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
 * Convention guard ("Konventionstest" of the Wächter layer in docs/internal/teststrategie.md)
 * over the repository itself rather than over the code: no tooling configuration is
 * tracked (DO-10), and every relative documentation link resolves (DO-08).
 *
 * Both kinds of damage these rules prevent are invisible at the next build. A tooling
 * directory can reappear through a stray `git add`, and a dead relative link stays
 * silent until a reader clicks it -- the documentation is one linked web, with the
 * README signposting every file in `docs/` and CONTRIBUTING.md having taken two
 * sections out of `docs/de/development.md`.
 *
 * **Why this test reads the filesystem.** Every other test here takes its fixtures
 * from the classpath, because its subject is a resource. The subject here is the
 * working tree: `README.md`, `CONTRIBUTING.md` and the Markdown files under `docs/`
 * are not test resources and are not on the classpath at all. [repositoryRoot] walks
 * up from the working directory looking for the repository marker instead of assuming
 * a particular working directory -- Gradle runs tests in the project directory, but
 * that is a default the build does not pin, so relying on it would make this guard
 * fragile for the wrong reason. Since DO-12 the product documentation lives under
 * `docs/de/` (and later `docs/en/`), the working documents under `docs/internal/` --
 * the file collection below walks `docs/` recursively for exactly that reason.
 *
 * **Why each rule is checked twice.** The live checks run against the real
 * repository, which is the point of the guard -- but the tooling check needs `git`,
 * and `git` is not always usable (in a worktree whose git directory lives outside the
 * dev container's mount, for instance, it is not). A check that silently skips proves
 * nothing, so each rule is additionally applied to a synthetic input that is known to
 * be broken. That half cannot skip and shows the rule actually bites.
 *
 * Both live checks collect their findings and fail once, naming every offending file,
 * so a broken state points at the whole fix instead of stopping at the first problem.
 * Everything here is local file and process work and stays offline (DC-03).
 */
@Tag("guard")
class RepositoryHygieneTest {
    @Nested
    inner class NoToolingConfiguration {
        /**
         * DO-10 -- the repository carries no configuration of a local tool.
         *
         * The check asks **git**, not the filesystem, and that distinction is the
         * point: a contributor may well keep their own `.opencode/` directory in the
         * working tree, and this guard must not punish them for it. What must stay
         * true is that the repository does not *carry* it.
         *
         * Skips when git cannot be asked -- an unavailable git makes the check
         * inapplicable, not failed, and a guard that goes red for the wrong reason
         * trains people to ignore it. The companion test below covers the rule
         * itself in that case.
         */
        @Test
        fun `DO-10 no tooling configuration is tracked`() {
            val tracked = trackedFiles()
            assumeTrue(tracked != null, "git cannot be asked here, so there is no tracked file list to check")

            assertThat(tracked)
                .`as`("git ls-files must list the repository content, otherwise this proves nothing")
                .contains("docs/internal/plan.md", "build.gradle.kts")

            val offenders = offendingToolingPaths(tracked!!)

            assertThat(offenders)
                .`as`("tooling configuration must not be tracked (DO-10); these paths are: %s", offenders)
                .isEmpty()
        }

        /**
         * DO-10 -- the rule itself, on a synthetic file list. Proves the check bites
         * without depending on git, and pins the two boundaries that a naive
         * "contains opencode" test would get wrong.
         */
        @Test
        fun `DO-10 the rule rejects tooling paths and spares look-alikes`() {
            val offenders =
                offendingToolingPaths(
                    listOf(
                        "opencode.json",
                        ".opencode/agent/reviewer.md",
                        ".opencode",
                        "docs/plan.md",
                        // Neither of these is the tooling configuration: a file whose
                        // name merely starts the same, and a same-named file somewhere
                        // else in the tree.
                        ".opencoderc",
                        "docs/opencode.json",
                    ),
                )

            assertThat(offenders)
                .containsExactlyInAnyOrder("opencode.json", ".opencode/agent/reviewer.md", ".opencode")
        }
    }

    @Nested
    inner class DocumentationLinks {
        /**
         * DO-08 -- every relative Markdown link in the documentation points at a file
         * that exists.
         */
        @Test
        fun `DO-08 every relative documentation link resolves`() {
            val files = documentationFiles()

            val relative = files.map { repositoryRoot.relativize(it).toString() }

            assertThat(relative.filter { it.startsWith("docs/de/") })
                .`as`("docs/de must contribute documentation files, otherwise the guard proves nothing")
                .isNotEmpty()
            assertThat(relative.filter { it.startsWith("docs/internal/") })
                .`as`("docs/internal must contribute documentation files, otherwise the guard proves nothing")
                .isNotEmpty()
            assertThat(relative.filter { it.startsWith("docs/en/") })
                .`as`("docs/en must contribute documentation files now that DO-16 fills it, otherwise the guard proves nothing")
                .isNotEmpty()
            // docs/en is deliberately not asserted: until DO-16 fills it, it holds
            // no product files, and asserting it would keep this guard red for the
            // wrong reason.

            val (dead, checked) = deadLinks(repositoryRoot, files)

            assertThat(checked)
                .`as`("the guard must find relative links to check, otherwise it proves nothing")
                .isPositive()

            assertThat(dead)
                .`as`("every relative documentation link must resolve (DO-08); these do not: %s", dead)
                .isEmpty()
        }

        /**
         * DO-08 -- the rule itself, on a synthetic file. Proves the check bites and
         * pins what is deliberately *not* checked: absolute URLs would need the
         * network, which DC-03 forbids, and a build that depends on the reachability
         * of foreign sites fails for reasons unrelated to this repository.
         */
        @Test
        fun `DO-08 the rule reports a dead link and skips what cannot be checked`(
            @TempDir root: Path,
        ) {
            Files.createDirectory(root.resolve("docs"))
            Files.writeString(root.resolve("docs/there.md"), "# there\n")
            val file = root.resolve("docs/here.md")
            Files.writeString(
                file,
                """
                A link that resolves: [there](there.md).
                A link with an anchor that resolves: [there](there.md#heading).
                A link that does not: [gone](missing.md).
                Not checked: [web](https://example.org/x), [anchor](#heading).
                """.trimIndent(),
            )

            val (dead, checked) = deadLinks(root, listOf(file))

            assertThat(dead).containsExactly("docs/here.md:3 -> missing.md")
            assertThat(checked)
                .`as`("the two resolving links and the dead one are checked, the web and anchor links are not")
                .isEqualTo(3)
        }

        /**
         * DO-16 -- links from English files into the German-only working
         * documents carry their marking. A reader following an unmarked link
         * walks into German text unannounced.
         */
        @Test
        fun `DO-16 links into working documents carry their German-only marking`() {
            val files = documentationFiles().filter { isEnglishFile(it) }

            assertThat(files.map { repositoryRoot.relativize(it).toString() })
                .`as`("the marking rule must find English files, otherwise it proves nothing")
                .isNotEmpty()

            val unmarked = unmarkedInternalLinks(repositoryRoot, files)

            assertThat(unmarked)
                .`as`("every link from an English file into docs/internal/ carries (German only); these do not: %s", unmarked)
                .isEmpty()
        }

        /**
         * DO-16 -- the rule itself, on a synthetic file. Proves the check bites
         * and pins that same-language links need no marking.
         */
        @Test
        fun `DO-16 the rule reports an unmarked link and spares the rest`(
            @TempDir root: Path,
        ) {
            Files.createDirectories(root.resolve("docs/internal"))
            Files.createDirectories(root.resolve("docs/en"))
            Files.writeString(root.resolve("docs/internal/plan.md"), "# plan\n")
            val file = root.resolve("docs/en/operations.md")
            Files.writeString(
                file,
                """
                Marked: [plan](../internal/plan.md) (German only).
                Not marked: [plan](../internal/plan.md).
                Same language: [other](configuration.md).
                """.trimIndent(),
            )

            assertThat(unmarkedInternalLinks(root, listOf(file)))
                .containsExactly("docs/en/operations.md:2 -> ../internal/plan.md")
        }
    }

    /**
     * The English documentation files: everything under `docs/en/` plus the
     * English root files (`README.md`, not `README.de.md`).
     */
    private fun isEnglishFile(file: Path): Boolean {
        val relative = repositoryRoot.relativize(file).toString()
        return relative.startsWith("docs/en/") || relative in ENGLISH_ROOT_DOCUMENTS
    }

    /**
     * Every link from [files] into `docs/internal/` whose line carries no
     * `(German only)` marking. Targets resolve against the file holding the
     * link, like in [deadLinks].
     */
    private fun unmarkedInternalLinks(
        root: Path,
        files: List<Path>,
    ): List<String> {
        val unmarked = mutableListOf<String>()
        files.forEach { file ->
            val directory = file.parent
            Files.readAllLines(file).forEachIndexed { index, line ->
                LINK.findAll(line).forEach { match ->
                    val target = match.groupValues[1].trim().substringBefore("#")
                    if (target.isEmpty()) return@forEach
                    val resolved = directory.resolve(target).normalize()
                    if (!resolved.startsWith(root.resolve("docs/internal"))) return@forEach
                    val after = line.substring(match.range.last + 1)
                    if (!after.contains("(German only)")) {
                        unmarked += "${root.relativize(file)}:${index + 1} -> $target"
                    }
                }
            }
        }
        return unmarked
    }

    /**
     * The tracked paths that must not be there. `.opencode` matches the directory
     * itself and everything below it; `opencode.json` only at the repository root,
     * because that is where the tooling configuration sits.
     */
    private fun offendingToolingPaths(tracked: List<String>): List<String> =
        tracked.filter { path ->
            TOOLING_PATHS.any { path == it || path.startsWith("$it/") }
        }

    /**
     * Every relative link target in [files] that does not resolve, together with the
     * number of targets actually checked. Targets are resolved against the file
     * holding the link, not against the repository root: `configuration.md` in
     * `docs/de/operations.md` means `docs/de/configuration.md`.
     *
     * Absolute URLs and pure `#anchor` links are skipped; an anchor suffix on a file
     * target is stripped before the file is resolved.
     */
    private fun deadLinks(
        root: Path,
        files: List<Path>,
    ): Pair<List<String>, Int> {
        val dead = mutableListOf<String>()
        var checked = 0

        files.forEach { file ->
            val directory = file.parent
            Files.readAllLines(file).forEachIndexed { index, line ->
                LINK.findAll(line).forEach { match ->
                    val target = match.groupValues[1].trim()
                    if (SKIPPED_SCHEMES.any { target.startsWith(it) }) return@forEach
                    val path = target.substringBefore('#')
                    if (path.isEmpty()) return@forEach

                    checked++
                    if (!Files.exists(directory.resolve(path))) {
                        dead += "${root.relativize(file)}:${index + 1} -> $target"
                    }
                }
            }
        }

        return dead to checked
    }

    /**
     * Lists the paths tracked by git, or `null` when git cannot be asked.
     *
     * Running an external process matches what this project already does for
     * `jpegtran` and `jbig2`, and stays offline (DC-03).
     */
    private fun trackedFiles(): List<String>? =
        runCatching {
            val process =
                ProcessBuilder("git", "ls-files")
                    .directory(repositoryRoot.toFile())
                    .start()
            val output = process.inputStream.use { it.readAllBytes().decodeToString() }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null
            }
            output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }.getOrNull()

    /**
     * The Markdown files whose links are checked: the root-level entry points that
     * exist, plus everything under `docs/`.
     */
    private fun documentationFiles(): List<Path> {
        val roots =
            ROOT_DOCUMENTS
                .map { repositoryRoot.resolve(it) }
                .filter { Files.isRegularFile(it) }
        val docs =
            Files.walk(repositoryRoot.resolve("docs")).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }.toList()
            }
        return (roots + docs).sorted()
    }

    private companion object {
        val ENGLISH_ROOT_DOCUMENTS = listOf("README.md", "CONTRIBUTING.md", "SECURITY.md")

        val TOOLING_PATHS = listOf(".opencode", "opencode.json")

        val ROOT_DOCUMENTS =
            listOf(
                "README.md",
                "README.de.md",
                "CONTRIBUTING.md",
                "CONTRIBUTING.de.md",
                "SECURITY.md",
                "SECURITY.de.md",
                "AGENTS.md",
            )

        val SKIPPED_SCHEMES = listOf("http://", "https://", "mailto:")

        /**
         * An inline Markdown link target: `](…)`. Nested parentheses in a target are
         * not supported and do not occur here; the reference form `[a]: url` is not
         * used in this documentation either.
         */
        val LINK = Regex("]\\(([^)]+)\\)")

        /**
         * The repository root, found by walking up from the working directory until a
         * directory contains the build script and `docs/`. Deliberately not derived
         * from an assumed working directory -- see the class KDoc.
         */
        val repositoryRoot: Path =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .firstOrNull { Files.isRegularFile(it.resolve("build.gradle.kts")) && Files.isDirectory(it.resolve("docs")) }
                ?: throw IllegalStateException(
                    "repository root not found above ${Path.of("").toAbsolutePath()}; " +
                        "expected a directory containing build.gradle.kts and docs/",
                )
    }
}
