package dev.digiwomb.unboundair.image

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The external `jbig2` program failed to encode the pages.
 *
 * (SV-08) The 1-bit encoding runs only through the external `jbig2`
 * program; the plan never reimplements JBIG2, so every failure of that
 * program must surface as this exception. The message always names the
 * command that was run and, where available, the output the program
 * produced; the cause is set when the failure is a JVM-level problem
 * (for example the program cannot be started or its output files cannot
 * be read).
 *
 * @param message a human-readable description of the failure.
 * @param cause the underlying cause, if any (e.g. the I/O exception that
 *   prevented the program from starting); `null` when `jbig2` simply
 *   exited with a non-zero code or produced an unexpected file set.
 */
class Jbig2EncException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Runs the external `jbig2` program (jbig2enc) for the 1-bit page
 * encoding of the processing chain (SV-08).
 *
 * This object is the only place in the codebase that invokes `jbig2`.
 * The program builds a shared symbol dictionary over all input pages
 * and emits one global segment plus one page stream per input page,
 * which the PDF writer embeds as `/JBIG2Decode` streams.
 *
 * The program is called `jbig2`, not `jbig2enc`: `jbig2enc` is the
 * source package name, while the installed binary on the PATH (package
 * `jbig2`, Ubuntu universe) is `jbig2`. Getting this wrong compiles
 * fine and fails only at runtime, so the name matters.
 *
 * Symbol mode (`-s`) is lossy by design: similar symbols are unified
 * into one dictionary entry, so two different letters can in principle
 * be drawn with one shape (spike #140 measured 0.0058 % changed
 * pixels). The lossless variant (`-r`, refinement) is dead in current
 * releases: the program prints "Refinement broke in recent releases
 * ..." and returns 1 before the flag is ever set, so it is not an
 * option we have.
 *
 * No thresholding happens here: for 1-bpp input the encoder clones the
 * page (`pixClone`) and skips `pixThresholdToBinary` entirely (spike
 * #140), which is precisely why `MonochromeStep` hands over a PBM. If
 * anything ever hands this a grayscale image, the encoder picks its own
 * threshold and our setting becomes decoration.
 *
 * The program name is an `internal var` (and not a `private const` like
 * in [JpegTran]) as a deliberate testability seam: the "cannot start"
 * path is tested here, so tests must be able to point the name at a
 * nonexistent binary. Production code never reassigns it.
 */
object Jbig2Enc {
    // Name of the external program. It is looked up on the PATH of the
    // process; the container image provides it. Internal (not private)
    // and a var (not a const) so tests can point it at a binary that
    // does not exist; production code never reassigns it.
    internal var program: String = "jbig2"

    /**
     * The result of one `jbig2` run: the shared dictionary plus one
     * stream per input page, in input order.
     *
     * @param globals the bytes of `<base>.sym`, the global segments
     *   (the symbol dictionary) shared by all pages.
     * @param pages the bytes of the `<base>.NNNN` page streams, one per
     *   input page, in input order.
     */
    data class Jbig2Output(
        val globals: ByteArray,
        val pages: List<ByteArray>,
    )

    /**
     * Encodes the 1-bpp PBM pages in [sources] with `jbig2` and returns
     * the shared dictionary plus one page stream per input (SV-08).
     *
     * Runs `jbig2 -s -p -b BASE page1.pbm page2.pbm ...` with
     * `BASE = workDir/pages`, so the program writes `pages.sym` (the
     * globals) plus `pages.0000`, `pages.0001`, ... (one per input
     * page, in input order) into [workDir]:
     * `-s` selects symbol mode, the shared dictionary without which
     * none of this would be worth doing; `-p` selects PDF mode, which
     * emits streams without the JBIG2 file and page headers that a PDF
     * embedded stream must not contain; `-b` sets the output basename.
     *
     * The process is started through [ProcessBuilder] with one argument
     * per list entry, so no shell is involved and no argument can be
     * misread as shell syntax. The program's standard output and
     * standard error are merged and read in full before the exit code
     * is checked, so a program that prints a lot of diagnostics cannot
     * block in a full pipe buffer.
     *
     * @param sources the input 1-bpp PBM pages, in document order; must
     *   not be empty.
     * @param workDir the directory the `pages.*` files are written to;
     *   it must already exist.
     * @return the globals and the page streams, in input order.
     * @throws IllegalArgumentException [sources] is empty.
     * @throws Jbig2EncException the program cannot be started (e.g. it
     *   is missing or not executable), it exits with a non-zero code,
     *   or its output files cannot be read back; also when the number
     *   of `pages.NNNN` files does not match the number of inputs,
     *   because a silently short list would drop a page from the
     *   finished document.
     */
    fun encode(
        sources: List<Path>,
        workDir: Path,
    ): Jbig2Output {
        require(sources.isNotEmpty()) { "sources must not be empty: jbig2 needs at least one input page" }
        val base = workDir.resolve("pages")
        val arguments =
            listOf(program, "-s", "-p", "-b", base.toString()) + sources.map { it.toString() }
        val output = StringBuilder()
        val process =
            try {
                ProcessBuilder(arguments).redirectErrorStream(true).start()
            } catch (e: IOException) {
                throw Jbig2EncException(
                    "Cannot start $program to encode ${sources.size} page(s): " +
                        "${e.message} (is it installed and on the PATH?)",
                    e,
                )
            }
        try {
            process.inputStream.use { stream -> output.append(stream.readBytes().decodeToString()) }
        } catch (e: IOException) {
            throw Jbig2EncException(
                "Failed to read the output of $program while encoding ${sources.size} page(s): ${e.message}",
                e,
            )
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw Jbig2EncException(
                "$program failed with exit code $exitCode while running " +
                    "${commandLine(arguments)}${diagnostics(output)}",
            )
        }
        val globals =
            try {
                Files.readAllBytes(workDir.resolve("pages.sym"))
            } catch (e: IOException) {
                throw Jbig2EncException(
                    "Failed to read the globals file pages.sym produced by " +
                        "${commandLine(arguments)}: ${e.message}",
                    e,
                )
            }
        val prefix = base.fileName.toString() + "."
        val pageFiles =
            try {
                Files.list(workDir).use { stream ->
                    stream
                        .filter { file ->
                            val name = file.fileName.toString()
                            val suffix = name.removePrefix(prefix)
                            name.startsWith(prefix) && suffix.isNotEmpty() && suffix.all { it.isDigit() }
                        }.sorted()
                        .toList()
                }
            } catch (e: IOException) {
                throw Jbig2EncException(
                    "Failed to list the page files $prefix* produced by " +
                        "${commandLine(arguments)}: ${e.message}",
                    e,
                )
            }
        if (pageFiles.size != sources.size) {
            throw Jbig2EncException(
                "$program produced ${pageFiles.size} page file(s) for ${sources.size} input page(s) while running " +
                    commandLine(arguments),
            )
        }
        val pages =
            try {
                pageFiles.map { Files.readAllBytes(it) }
            } catch (e: IOException) {
                throw Jbig2EncException(
                    "Failed to read the page files produced by ${commandLine(arguments)}: ${e.message}",
                    e,
                )
            }
        return Jbig2Output(globals, pages)
    }

    /**
     * Renders [arguments] as a single command line for log messages,
     * quoting any argument that contains a space.
     */
    private fun commandLine(arguments: List<String>): String =
        arguments.joinToString(separator = " ") { argument ->
            if (argument.contains(' ')) "'$argument'" else argument
        }

    /**
     * Formats the collected program [output] for a failure message; empty
     * output yields an explicit note instead of a blank ending.
     */
    private fun diagnostics(output: StringBuilder): String = if (output.isEmpty()) " (it printed no output)" else ": ${output.trim()}"
}
