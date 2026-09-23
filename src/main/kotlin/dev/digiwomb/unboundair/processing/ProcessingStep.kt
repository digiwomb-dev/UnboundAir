package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.image.JpegInfo
import java.nio.file.Path

/**
 * One scanned page in its current state of the processing chain (SV-07).
 *
 * A [PageImage] pairs the JPEG file of the page ([file]) with the structure
 * of that very file ([info]). A step that changes the page writes a new file
 * and returns a new [PageImage] whose [info] is freshly read from the new
 * file, so the structure always describes the file it travels with.
 *
 * @property file the JPEG file of the page in its current state.
 * @property info the [JpegInfo] of [file] (dimensions, components, iMCU size).
 */
data class PageImage(
    val file: Path,
    val info: JpegInfo,
)

/**
 * One step of the page processing chain (SV-07).
 *
 * A step transforms a single scanned page: it receives the page in its
 * current state as a [PageImage] (JPEG file plus the [JpegInfo] of that file)
 * and a working directory [apply.workDir] in which it may write a
 * replacement file if it changes anything.
 *
 * The contract is deliberately simple and keeps the chain safe to extend:
 *
 * - A step that changes nothing returns the *same* [PageImage] instance it
 *   received, so passing through costs no file I/O.
 * - A step that changes the page writes the result into the working
 *   directory and returns a new [PageImage] with a freshly read [JpegInfo].
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
     * @param image the page in its current state (JPEG file plus its
     *   [JpegInfo]).
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
