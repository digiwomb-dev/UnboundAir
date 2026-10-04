package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.image.JpegInfo
import dev.digiwomb.unboundair.image.PageInfo
import java.nio.file.Path

/**
 * One scanned page in its current state of the processing chain (SV-07).
 *
 * A [PageImage] pairs the page file ([file]) with the structure of that very
 * file ([info]). A step that changes the page writes a new file and returns a
 * new [PageImage] whose [info] is freshly read from the new file, so the
 * structure always describes the file it travels with — without promising
 * which format that file is in.
 *
 * @property file the file of the page in its current state.
 * @property info the [PageInfo] of [file] (at least its dimensions).
 */
data class PageImage(
    val file: Path,
    val info: PageInfo,
)

/**
 * One step of the page processing chain (SV-07).
 *
 * A step transforms a single scanned page: it receives the page in its
 * current state as a [PageImage] (file plus the [PageInfo] of that file)
 * and a working directory [apply.workDir] in which it may write a
 * replacement file if it changes anything.
 *
 * The contract is deliberately simple and keeps the chain safe to extend:
 *
 * - A step that changes nothing returns the *same* [PageImage] instance it
 *   received, so passing through costs no file I/O.
 * - A step that changes the page writes the result into the working
 *   directory and returns a new [PageImage] with a freshly read [PageInfo].
 *
 * A step that hits a degenerate case (for example: no paper found, page
 * carried through uncropped) reports it through [apply.warn] instead of
 * failing; a page always reaches the end of the chain.
 *
 * The chain is cut this way so that later steps (rotation, deskew, and
 * eventually the PDF building) are added by appending them to the step list,
 * without touching the existing steps (SV-07).
 *
 * @property name the name of this step, used in logs and warnings.
 */
interface ProcessingStep {
    val name: String

    /**
     * Applies this step to [image].
     *
     * @param image the page in its current state (file plus its
     *   [PageInfo]).
     * @param workDir the working directory of the chain; a step that changes
     *   the page writes its output file here.
     * @param warn the channel for warnings about this page (for example: no
     *   paper found, page carried through uncropped).
     * @return the [PageImage] of the page after this step; the same instance
     *   as [image] when the step changed nothing, a new instance otherwise.
     */
    fun apply(
        image: PageImage,
        workDir: Path,
        warn: (String) -> Unit,
    ): PageImage
}

/**
 * Creates a [PageImage] from a file without requiring the [dev.digiwomb.unboundair.image] package
 * in callers. This factory keeps the CLI layer decoupled from the image package (architecture guard).
 *
 * @param file the JPEG file to read.
 * @return a [PageImage] with freshly read [JpegInfo].
 */
fun pageImage(file: Path): PageImage = PageImage(file, JpegInfo.read(file))
