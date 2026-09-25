package dev.digiwomb.unboundair.golden

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Tests for the sha256 manifest of the golden directory (SV-01, SV-03;
 * "golden-master" layer of docs/teststrategie.md).
 *
 * The golden files under `src/test/resources/golden/` are pinned by
 * `manifest.sha256`, one line per file: `<sha256 lowercase hex>  <path
 * relative to golden/>`. These tests make the manifest itself checkable:
 *
 * 1. every manifest entry names a file that exists and hash-matches
 *    (SV-01);
 * 2. every golden file is listed in the manifest, so nothing can rot
 *    unobserved (SV-03);
 * 3. every manifest line has the exact `<hash>  <path>` shape and no hash
 *    appears twice (SV-01).
 *
 * All three checks collect their findings and fail with one message that
 * names every offending file and its expected and actual hash, so a broken
 * golden state points at the fix instead of stopping at the first mismatch.
 * The tests are pure file/hash work on the classpath and stay offline
 * (DC-03).
 */
class GoldenManifestTest {
    /**
     * SV-01 -- every entry of the manifest must exist as a golden file and
     * its content must hash to the recorded sha256. Missing files and hash
     * mismatches are collected and reported together, with the expected and
     * the actual hash per file.
     */
    @Test
    fun `SV-01 every manifest entry exists and hash-matches`() {
        val errors = mutableListOf<String>()
        manifestLines().forEachIndexed { index, line ->
            val parts = parseEntry(line)
            if (parts == null) {
                errors.add("manifest line ${index + 1} is malformed: '$line'")
                return@forEachIndexed
            }
            val (expected, path) = parts
            val bytes = resourceBytes("/golden/$path")
            if (bytes == null) {
                errors.add("'$path' is listed in the manifest but the file does not exist (expected $expected)")
                return@forEachIndexed
            }
            val actual = sha256(bytes)
            if (actual != expected) {
                errors.add("hash mismatch for '$path': expected $expected, got $actual")
            }
        }
        assertThat(errors)
            .`as`("every manifest entry must name an existing file whose content matches its hash:\n${errors.joinToString("\n")}")
            .isEmpty()
    }

    /**
     * SV-03 -- every golden file (except the manifest itself) must be listed
     * in the manifest. A file that appears in `golden/` without an entry
     * would otherwise never be checked.
     */
    @Test
    fun `SV-03 every golden file is listed in the manifest`() {
        val listed =
            manifestLines()
                .mapNotNull { parseEntry(it)?.second }
                .toSet()
        val missing =
            goldenFiles()
                .filterNot { listed.contains(it) }
        assertThat(missing)
            .`as`("every file under golden/ must have a manifest entry:\n${missing.joinToString("\n")}")
            .isEmpty()
    }

    /**
     * SV-01 -- every non-blank manifest line must match the exact
     * `<64-hex-chars><two spaces><path>` shape, and no hash may appear twice
     * (a duplicate entry would let a file change hands unnoticed).
     */
    @Test
    fun `SV-01 the manifest has no duplicate or malformed lines`() {
        val errors = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        manifestLines().forEachIndexed { index, line ->
            val parts = parseEntry(line)
            if (parts == null) {
                errors.add("manifest line ${index + 1} is malformed: '$line'")
                return@forEachIndexed
            }
            if (!seen.add(parts.first)) {
                errors.add("duplicate hash ${parts.first} on manifest line ${index + 1}")
            }
        }
        assertThat(errors)
            .`as`("every manifest line must have the shape '<sha256>  <path>' and a unique hash:\n${errors.joinToString("\n")}")
            .isEmpty()
    }

    /**
     * Splits one manifest line into its hash and path.
     *
     * @return the `(hash, path)` pair, or `null` when the line does not
     *   match the exact manifest shape (64 lowercase hex chars, two spaces,
     *   a non-empty path).
     */
    private fun parseEntry(line: String): Pair<String, String>? {
        if (!ENTRY.matches(line)) {
            return null
        }
        val hash = line.substring(0, 64)
        val path = line.substring(66)
        return hash to path
    }

    /**
     * Returns the non-blank lines of `golden/manifest.sha256`.
     */
    private fun manifestLines(): List<String> {
        val text =
            javaClass.getResourceAsStream(MANIFEST)
                ?.use { it.readAllBytes().decodeToString() }
                ?: throw IllegalStateException("golden manifest not found on the classpath: $MANIFEST")
        return text.lineSequence().filter { it.isNotBlank() }.toList()
    }

    /**
     * Returns the bytes of the classpath resource at [path], or `null` when
     * it does not exist.
     */
    private fun resourceBytes(path: String): ByteArray? =
        javaClass.getResourceAsStream(path)?.use { it.readAllBytes() }

    /**
     * Enumerates every regular file under `golden/`, relative to it, except
     * the manifest itself.
     */
    private fun goldenFiles(): List<String> {
        val root =
            javaClass.getResource(GOLDEN_DIR)
                ?: throw IllegalStateException("golden directory not found on the classpath: $GOLDEN_DIR")
        val golden = java.nio.file.Path.of(root.toURI())
        return Files.walk(golden).use { walk ->
            walk
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() != MANIFEST_FILE }
                .map { golden.relativize(it).toString() }
                .sorted()
                .toList()
        }
    }

    /**
     * SHA-256 of [bytes] as lowercase hex, the manifest's format.
     */
    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private companion object {
        const val MANIFEST = "/golden/manifest.sha256"
        const val GOLDEN_DIR = "/golden/"
        const val MANIFEST_FILE = "manifest.sha256"

        /** `<64 lowercase hex chars>  <path>` — exactly two spaces. */
        val ENTRY = Regex("^[0-9a-f]{64}  .+$")
    }
}
