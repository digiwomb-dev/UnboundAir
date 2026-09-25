package dev.digiwomb.unboundair.processing

import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the page processing chain (SV-07).
 *
 * A [PageProcessor] executes its steps in the given order, feeding each step
 * the [PageImage] the previous step returned: the page enters the chain as
 * the scanned [PageImage] the caller passes to [process] and leaves it as the
 * [PageImage] the last step returned.
 *
 * The processor does not know anything about concrete steps, which is what
 * keeps the chain safe to extend (SV-07): rotation, deskew, or a no-op dummy
 * step are added by appending them to the step list (or plugging one in at any
 * position), without touching any existing step.
 *
 * Steps write their replacement files into the working directory of the run
 * ([process.workDir]) - for example, the crop step writes `cropped.jpg` and
 * the grayscale step writes `gray.jpg`. The processor keeps this directory
 * clean:
 *
 * - before the run it records every file that already exists in the working
 *   directory;
 * - after the run it deletes every file that was not there before, with one
 *   exception: the final result file (the file of the last [PageImage]) must
 *   survive, because the caller consumes it.
 *
 * The input file is owned by the caller and is never touched: it is either
 * outside the working directory, or it was already there before the run and
 * is therefore part of the recorded set.
 *
 * The cleanup is best-effort: a file that cannot be deleted does not fail the
 * run; the processor reports it through [process.warn] instead.
 *
 * @property steps the steps of the chain, executed in this order.
 */
class PageProcessor(
    private val steps: List<ProcessingStep>,
) {
    /**
     * Runs [image] through all [steps] in order and cleans up the working
     * directory afterwards.
     *
     * @param image the page as the caller has it (raw JPEG file plus its
     *   structure).
     * @param workDir the working directory of the run; the steps write their
     *   replacement files here, and the processor removes the files written
     *   during the run afterwards, keeping the final result file.
     * @param warn the channel for warnings; the steps of the chain and the
     *   cleanup report through it.
     * @return the [PageImage] after the last step; the same instance as
     *   [image] when no step changed the page.
     */
    fun process(
        image: PageImage,
        workDir: Path,
        warn: (String) -> Unit = {},
    ): PageImage {
        val existing = listFiles(workDir)
        val result = steps.fold(image) { page, step -> step.apply(page, workDir, warn) }
        cleanUp(workDir, existing, result.file, warn)
        return result
    }

    /**
     * Deletes every file in [workDir] that was not there before the run
     * ([existing]) and is not the final result file [finalFile].
     *
     * Each deletion is best-effort: a failure is reported through [warn] and
     * does not interrupt the cleanup of the remaining files.
     */
    private fun cleanUp(
        workDir: Path,
        existing: Set<Path>,
        finalFile: Path,
        warn: (String) -> Unit,
    ) {
        listFiles(workDir)
            .filter { it !in existing && it != finalFile }
            .forEach { file ->
                runCatching { Files.delete(file) }
                    .onFailure { warn("could not clean up $file: ${it.message}") }
            }
    }

    /**
     * Lists all regular files in [dir], recursively.
     *
     * Returns the empty set when [dir] does not exist, so a working directory
     * the caller created fresh is simply recorded as "nothing was there
     * before".
     */
    private fun listFiles(dir: Path): Set<Path> {
        if (!Files.isDirectory(dir)) {
            return emptySet()
        }
        val files = mutableSetOf<Path>()
        Files.walk(dir).use { walk ->
            walk.filter { Files.isRegularFile(it) }.forEach { files.add(it) }
        }
        return files
    }
}
