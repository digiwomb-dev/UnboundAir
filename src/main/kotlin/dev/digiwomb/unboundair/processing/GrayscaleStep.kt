package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.image.JpegInfo
import dev.digiwomb.unboundair.image.JpegTran
import java.nio.file.Path

/**
 * The grayscale step of the page processing chain (SV-03).
 *
 * Converts a page to grayscale without re-compressing: the conversion is a
 * transform of the stored color components performed by
 * `jpegtran -copy all -grayscale`, which preserves the existing DCT
 * coefficients (plan, "Feste Entscheidungen": never re-compress).
 *
 * Whether the step changes the page at all is decided by the step's
 * [PageSettings] color mode ([PageSettings.colorMode]):
 *
 * - [ColorMode.COLOR]: the page stays in the color in which the scanner
 *   produced it; the same [PageImage] instance is returned and no file I/O
 *   happens;
 * - [ColorMode.GRAY] (the default): the page is converted to grayscale and
 *   written to `gray.jpg` in the working directory, and the step returns a
 *   new [PageImage] whose [JpegInfo] is freshly read from that file.
 *
 * There is no degenerate case that needs a warning: in [ColorMode.COLOR]
 * the step intentionally changes nothing, and in [ColorMode.GRAY] the
 * conversion is applied to every page.
 *
 * @property settings the settings of the step; the color mode is read from
 *   it. Defaults to a [PageSettings] with all defaults.
 */
class GrayscaleStep(
    private val settings: PageSettings = PageSettings(),
) : ProcessingStep {
    override val name: String get() = "grayscale"

    override fun apply(
        image: PageImage,
        workDir: Path,
        warn: (String) -> Unit,
    ): PageImage {
        if (settings.colorMode == ColorMode.COLOR) {
            // The page stays in the color in which the scanner produced it:
            // no conversion, no file I/O, the same instance passes through.
            return image
        }
        val target = workDir.resolve("gray.jpg")
        JpegTran.grayscale(image.file, target)
        return PageImage(target, JpegInfo.read(target))
    }
}
