package dev.digiwomb.unboundair

import java.nio.file.Files
import java.nio.file.Path

/**
 * Test image access for the whole test suite.
 *
 * The committed test images (TE-02) live as classpath resources in
 * `src/test/resources/fixtures/`. Steps and commands under test operate on
 * file paths, so [copy] materializes a fixture inside the caller's
 * `@TempDir` directory, while [bytes] serves the raw resource bytes.
 *
 * The available fixtures are `envelope_dl_300dpi_raw.jpg`,
 * `din_a4_300dpi_raw.jpg`, `a4_bottom_stripe.jpg`, and `dark_page.jpg`.
 */
object TestImages {
    /**
     * Reads a test image resource from the classpath.
     *
     * @param name the file name inside `src/test/resources/fixtures/`, e.g.
     *   `envelope_dl_300dpi_raw.jpg` (no leading slash, no directory part).
     * @return the full byte content of the resource.
     * @throws IllegalStateException if the resource does not exist, naming
     *   the file in the message so a typo or a missing fixture fails clearly.
     */
    fun bytes(name: String): ByteArray {
        val resourcePath = "/fixtures/$name"
        val resource =
            TestImages::class.java.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException(
                    "Test image fixture not found on classpath: $resourcePath",
                )
        return resource.use { it.readBytes() }
    }

    /**
     * Copies a test image resource into [targetDir] under the given [name].
     *
     * The file is created or overwritten, so each test run works on its own
     * copy and never modifies the committed fixture. [targetDir] must exist;
     * tests pass a `@TempDir` directory.
     *
     * @return the path of the written file, `targetDir/<name>`.
     * @throws IllegalStateException if the resource does not exist, naming
     *   the file in the message.
     */
    fun copy(
        name: String,
        targetDir: Path,
    ): Path {
        val path = targetDir.resolve(name)
        Files.write(path, bytes(name))
        return path
    }
}
