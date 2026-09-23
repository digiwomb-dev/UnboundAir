package dev.digiwomb.unboundair.image

import java.io.IOException
import java.nio.file.Path

/**
 * The external `jpegtran` program failed to process a page.
 *
 * (SV-01, SV-03) The lossless crop and the grayscale conversion run only
 * through `jpegtran` (libjpeg-turbo); the plan never re-compresses an image,
 * so every failure of that program must surface as this exception. The
 * message always names the command that was run and, where available, the
 * output the program produced; the cause is set when the failure is a JVM-
 * level problem (for example the program cannot be started).
 *
 * @param message a human-readable description of the failure.
 * @param cause the underlying cause, if any (e.g. the I/O exception that
 *   prevented the program from starting); `null` when `jpegtran` simply
 *   exited with a non-zero code.
 */
class JpegTranException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Runs the external `jpegtran` program (libjpeg-turbo) for the two
 * lossless page operations of the processing chain (SV-01, SV-03).
 *
 * This object is the only place in the codebase that invokes `jpegtran`.
 * Both operations copy the existing DCT coefficients without re-compressing
 * the image: the crop keeps a window of the source, the grayscale
 * conversion re-interprets the stored color components as a single luma
 * component. The program is a system dependency provided by the container
 * image; when it is missing or fails, a [JpegTranException] is raised.
 */
object JpegTran {
    // Name of the external program. It is looked up on the PATH of the
    // process; the container image provides libjpeg-turbo.
    private const val PROGRAM = "jpegtran"

    /**
     * Losslessly crops the JPEG at [source] to a [width]x[height] window at
     * the offset (+[x], +[y]) and writes the result to [target] (SV-01).
     *
     * Runs `jpegtran -copy all -crop WxH+X+Y -outfile TARGET SOURCE`. The
     * coefficients inside the window are copied without re-compression, so
     * the pixels of the output are exact copies of the source pixels.
     *
     * @param source the input JPEG.
     * @param target the output JPEG to write.
     * @param width the width of the crop window in pixels.
     * @param height the height of the crop window in pixels.
     * @param x the offset of the window's left edge in pixels.
     * @param y the offset of the window's top edge in pixels.
     * @throws JpegTranException [PROGRAM] cannot be started, its output
     *   cannot be read, or it exits with a non-zero code.
     */
    fun crop(
        source: Path,
        target: Path,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ) {
        run(source, target, listOf("-copy", "all", "-crop", "${width}x$height+$x+$y"))
    }

    /**
     * Converts the JPEG at [source] to grayscale and writes the result to
     * [target] (SV-03).
     *
     * Runs `jpegtran -copy all -grayscale -outfile TARGET SOURCE`.
     * `jpegtran` converts the stored color components to a single luma
     * component without re-compressing the image; the existing DCT
     * coefficients are preserved.
     *
     * @param source the input JPEG.
     * @param target the output JPEG to write.
     * @throws JpegTranException [PROGRAM] cannot be started, its output
     *   cannot be read, or it exits with a non-zero code.
     */
    fun grayscale(
        source: Path,
        target: Path,
    ) {
        run(source, target, listOf("-copy", "all", "-grayscale"))
    }

    /**
     * Runs [PROGRAM] with [options] plus the fixed `-outfile TARGET SOURCE`
     * suffix, collects the program's output, waits for it to end, and fails
     * the operation if it did not succeed.
     *
     * The process is started through [ProcessBuilder] with one argument per
     * list entry, so no shell is involved and no argument can be misread as
     * shell syntax. The program's standard output and standard error are
     * merged and read in full before the exit code is checked, so a program
     * that prints a lot of diagnostics cannot block in a full pipe buffer.
     *
     * @param source the input JPEG; the last argument of the command.
     * @param target the output JPEG; the value of `-outfile`.
     * @param options the `jpegtran` options, in order.
     * @throws JpegTranException the program cannot be started (e.g. it is
     *   missing or not executable), its output cannot be read, or it exits
     *   with a non-zero code; the message names the full command and, where
     *   available, the output it produced, and the cause is the underlying
     *   I/O exception for start and read failures.
     */
    private fun run(
        source: Path,
        target: Path,
        options: List<String>,
    ) {
        val arguments = listOf(PROGRAM) + options + listOf("-outfile", target.toString(), source.toString())
        val output = StringBuilder()
        val process =
            try {
                ProcessBuilder(arguments).redirectErrorStream(true).start()
            } catch (e: IOException) {
                throw JpegTranException(
                    "Cannot start $PROGRAM to process $source: ${e.message} (is it installed and on the PATH?)",
                    e,
                )
            }
        try {
            process.inputStream.use { stream -> output.append(stream.readBytes().decodeToString()) }
        } catch (e: IOException) {
            throw JpegTranException(
                "Failed to read the output of $PROGRAM while processing $source: ${e.message}",
                e,
            )
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw JpegTranException(
                "$PROGRAM failed with exit code $exitCode while running " +
                    "${commandLine(arguments)}${diagnostics(output)}",
            )
        }
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
