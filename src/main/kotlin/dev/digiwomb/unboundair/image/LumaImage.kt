package dev.digiwomb.unboundair.image

import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Per-pixel luma samples of a single image, in row-major order.
 *
 * Each sample is one [Byte] holding the integer luma value of one pixel in
 * 0..255, computed with the BT.601 formula `(299*R + 587*G + 114*B) / 1000`.
 * Samples are laid out row by row, left to right, top to bottom; the sample
 * at pixel `(x, y)` is at index `y * width + x`. [width] and [height] are the
 * pixel dimensions of the image the samples belong to.
 *
 * [read] decodes an image file through `javax.imageio` and derives the luma
 * samples from the decoded pixels. The constructor is public and does not
 * touch the filesystem, so tests can build small images by hand.
 */
class LumaImage(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "width and height must be positive, got ${width}x$height" }
        require(luma.size == width * height) {
            "luma array size ${luma.size} does not match ${width}x$height = ${width * height}"
        }
    }

    /**
     * Returns the luma value of the pixel at `(x, y)` as an unsigned `Int` in
     * 0..255.
     *
     * @throws IndexOutOfBoundsException if `(x, y)` is outside the image.
     */
    fun get(
        x: Int,
        y: Int,
    ): Int {
        if (x !in 0 until width || y !in 0 until height) {
            throw IndexOutOfBoundsException("pixel ($x, $y) is outside the ${width}x$height image")
        }
        return luma[y * width + x].toInt() and 0xFF
    }

    companion object {
        /**
         * Decodes the image file at [path] via `ImageIO` and converts it to
         * luma samples.
         *
         * The decoded image is rendered into a `BufferedImage` of type
         * `TYPE_INT_RGB` when it is not already in that layout, so the luma
         * calculation sees one red, green and blue component per pixel.
         *
         * @param path a readable image file, e.g. one of the scanner's JPEGs.
         * @return an unsigned [LumaImage] with the pixel dimensions of [path].
         * @throws IllegalArgumentException if the file does not exist, is not
         * readable, or contains no image `ImageIO` can decode.
         */
        fun read(path: Path): LumaImage {
            val source =
                try {
                    ImageIO.read(path.toFile())
                } catch (e: IOException) {
                    throw IllegalArgumentException("Could not read image from $path: ${e.message}", e)
                }
            if (source == null) {
                throw IllegalArgumentException("Could not read image from $path: no ImageIO reader can decode it")
            }
            val rgb = toIntRgb(source)
            val luma = ByteArray(rgb.width * rgb.height)
            var i = 0
            for (y in 0 until rgb.height) {
                for (x in 0 until rgb.width) {
                    val pixel = rgb.getRGB(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    luma[i++] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
                }
            }
            return LumaImage(rgb.width, rgb.height, luma)
        }

        /**
         * Returns [source] when it is already a `TYPE_INT_RGB` image, or a
         * `TYPE_INT_RGB` copy of it otherwise.
         */
        private fun toIntRgb(source: BufferedImage): BufferedImage {
            if (source.type == BufferedImage.TYPE_INT_RGB) {
                return source
            }
            val canvas = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
            val graphics: Graphics2D = canvas.createGraphics()
            try {
                graphics.drawImage(source, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            return canvas
        }
    }
}
