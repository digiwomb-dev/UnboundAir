package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.image.BitmapInfo
import dev.digiwomb.unboundair.image.LumaImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * The monochrome step of the page processing chain (SV-08).
 *
 * Converts a page to real 1-bit black and white by a luma threshold: the
 * page is read with [LumaImage.read] (BT.601 luma, any ImageIO-decodable
 * input), each pixel is thresholded against [PageSettings.bwThreshold]
 * (luma below the threshold becomes black, at or above becomes white),
 * and the packed bitmap is written by hand as a binary PBM (`P4`) to
 * `mono.pbm` in the working directory. The step returns a new [PageImage]
 * whose [BitmapInfo] is freshly read from that file.
 *
 * Whether the step changes the page at all is decided by the step's
 * [PageSettings] color mode ([PageSettings.colorMode]):
 *
 * - [ColorMode.GRAY] (the default) and [ColorMode.COLOR]: the page passes
 *   through untouched; the same [PageImage] instance is returned and no
 *   file I/O happens;
 * - [ColorMode.BW]: the page is converted to 1-bit as described above.
 *
 * There is no degenerate case that needs a warning: in [ColorMode.GRAY]
 * and [ColorMode.COLOR] the step intentionally changes nothing, and in
 * [ColorMode.BW] the conversion is applied to every page — a page with no
 * dark pixels is a legitimately blank page, not an anomaly.
 *
 * This step does not call `jbig2`. JBIG2 encoding happens once per document
 * at batch close, over all pages together, because the symbol dictionary is
 * shared; a per-page encoder call would defeat the entire point.
 *
 * @property settings the settings of the step; the color mode and the luma
 *   threshold are read from it. Defaults to a [PageSettings] with all
 *   defaults.
 */
class MonochromeStep(
    private val settings: PageSettings = PageSettings(),
) : ProcessingStep {
    override val name: String get() = "monochrome"

    override fun apply(
        image: PageImage,
        workDir: Path,
        warn: (String) -> Unit,
    ): PageImage {
        if (settings.colorMode != ColorMode.BW) {
            // The page keeps its tones: no conversion, no file I/O,
            // the same instance passes through.
            return image
        }
        val luma = LumaImage.read(image.file)
        val target = workDir.resolve("mono.pbm")
        Files.write(target, pack(luma, settings.bwThreshold))
        return PageImage(target, BitmapInfo.read(target))
    }

    companion object {
        /**
         * Packs the thresholded luma samples of [luma] into a binary PBM
         * (`P4`) file image: the ASCII header `P4\n<width> <height>\n`
         * followed immediately by the packed bitmap.
         *
         * Each row is `ceil(width / 8)` bytes, most significant bit first,
         * and each row starts on a byte boundary, so a row whose width is
         * not a multiple of eight ends with padding bits.
         *
         * In PBM a set bit means black (luma below [threshold]), an unset
         * bit means white — the opposite of the intuitive reading.
         *
         * @param luma the luma samples to threshold and pack.
         * @param threshold the luma threshold: luma below it becomes black,
         *   at or above it becomes white.
         * @return the complete `P4` file content (header plus bitmap).
         */
        // Internal (not private) so the property test can drive the pure
        // packing function directly with in-memory images — the deliberate
        // testability seam; production callers use it exactly as before.
        internal fun pack(
            luma: LumaImage,
            threshold: Int,
        ): ByteArray {
            val header = "P4\n${luma.width} ${luma.height}\n".toByteArray(Charsets.US_ASCII)
            val stride = (luma.width + 7) / 8
            val bitmap = ByteArray(stride * luma.height)
            for (y in 0 until luma.height) {
                for (x in 0 until luma.width) {
                    if (luma.get(x, y) < threshold) {
                        val index = y * stride + x / 8
                        bitmap[index] = (bitmap[index].toInt() or (0x80 shr (x % 8))).toByte()
                    }
                }
            }
            return header + bitmap
        }
    }
}
