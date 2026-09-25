package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.image.JpegInfo
import dev.digiwomb.unboundair.image.JpegTran
import dev.digiwomb.unboundair.image.LumaImage
import dev.digiwomb.unboundair.image.PaperDetector
import java.nio.file.Path

/**
 * The crop step of the page processing chain (SV-01, SV-02).
 *
 * Detects the paper of a page and cuts the surrounding background away
 * without re-compressing: the cut is a window of the source image copied
 * by `jpegtran -copy all -crop`, which preserves the stored DCT
 * coefficients, so the pixels of the crop are exact copies of the source
 * pixels (plan, "Feste Entscheidungen": never re-compress).
 *
 * Before the window is used, the detected bounding box is checked against
 * the plausibility thresholds of the step's [PageSettings] with
 * [PaperDetector.isPlausible]: the paper must cover at least
 * [PageSettings.minPaperAreaFraction] of the image area, and its aspect
 * ratio must not exceed [PageSettings.maxAspectRatio].
 *
 * The origin of the crop window is then rounded inwards to the iMCU grid
 * of the image ([JpegInfo.imcuWidth] by [JpegInfo.imcuHeight]):
 * `jpegtran` would round a misaligned origin to the previous grid line,
 * which would bring background pixels back into the page.
 *
 * The step follows the contract of [ProcessingStep] and returns the same
 * [PageImage] instance whenever it changes nothing:
 *
 * - no paper found, or the detected box is not plausible: the page is
 *   carried through uncropped and a warning is reported through
 *   [apply.warn];
 * - the crop window covers the whole image (the A4 case, in which the
 *   paper fills the scan, so nothing would be cut): the page is passed
 *   through unchanged without running `jpegtran`, so the output stays
 *   byte-identical to the input;
 * - otherwise: the window is written to `cropped.jpg` in the working
 *   directory by `jpegtran`, and the step returns a new [PageImage] whose
 *   [JpegInfo] is freshly read from that file.
 *
 * @property settings the settings of the step; the plausibility thresholds
 *   are read from it. Defaults to a [PageSettings] with all defaults.
 */
class CropStep(
    private val settings: PageSettings = PageSettings(),
) : ProcessingStep {
    override val name: String get() = "crop"

    override fun apply(
        image: PageImage,
        workDir: Path,
        warn: (String) -> Unit,
    ): PageImage {
        val info = image.info
        val luma = LumaImage.read(image.file)
        val box = PaperDetector.detect(luma)
        if (box == null) {
            warn("no paper found in ${image.file}, keeping the page uncropped")
            return image
        }
        val boxWidth = box.x1 - box.x0 + 1
        val boxHeight = box.y1 - box.y0 + 1
        val plausible =
            PaperDetector.isPlausible(
                box,
                info.width,
                info.height,
                settings.minPaperAreaFraction,
                settings.maxAspectRatio,
            )
        if (!plausible) {
            warn("implausible paper box ${boxWidth}x$boxHeight px in ${image.file}, keeping the page uncropped")
            return image
        }

        // Round the origin inwards (up) to the iMCU grid; a misaligned
        // origin would be shifted by `jpegtran` and bring background back.
        val cropWindow =
            roundOriginInwards(
                x0 = box.x0,
                y0 = box.y0,
                imcuWidth = info.imcuWidth,
                imcuHeight = info.imcuHeight,
                x1 = box.x1,
                y1 = box.y1,
            )
        val ax0 = cropWindow.ax0
        val ay0 = cropWindow.ay0
        val cropWidth = cropWindow.cropWidth
        val cropHeight = cropWindow.cropHeight
        if (ax0 == 0 && ay0 == 0 && cropWidth >= info.width && cropHeight >= info.height) {
            // The window covers the whole image (the A4 case): nothing to
            // cut, so the page passes through unchanged. No `jpegtran`
            // run, so the output stays byte-identical to the input.
            return image
        }

        val target = workDir.resolve("cropped.jpg")
        JpegTran.crop(image.file, target, cropWidth, cropHeight, ax0, ay0)
        return PageImage(target, JpegInfo.read(target))
    }
}
