package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.image.BitmapInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Tests for [MonochromeStep.apply] (SV-08): the 1-bit threshold, the bit
 * polarity, the row padding, the custom threshold, the pass-through of the
 * other color modes, and the degenerate all-white and all-black pages.
 *
 * Every image is built by hand as a lossless PNG with grey pixels (r = g =
 * b = value, so the BT.601 luma decodes to exactly the written value) and
 * written into [tempDir]; the step receives an empty working directory of
 * its group, mirroring how the processing chain receives pages in
 * production. Pixel expectations are asserted on the raw PBM payload bytes
 * after the `P4\n<w> <h>\n` header, where a set bit means black. No external
 * program is involved, so the tests stay offline per DC-03.
 */
class MonochromeStepTest {
    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class Threshold {
        @TempDir
        lateinit var workDir: Path

        private val step = MonochromeStep(PageSettings(colorMode = ColorMode.BW))

        /**
         * SV-08 -- the boundary is where the KDoc says it is: luma below the
         * default threshold of 128 becomes black, luma at or above becomes
         * white. A 3x1 image with luma 127, 128 and 129 yields exactly the
         * bits `100`, so an off-by-one shows up in the raw payload byte.
         */
        @Test
        fun `SV-08 luma below the threshold becomes black, at or above becomes white`() {
            val src = writeGrayImage(tempDir, "boundary.png", 3, 1, intArrayOf(127, 128, 129))
            val warnings = mutableListOf<String>()
            val result = step.apply(PageImage(src, BitmapInfo(3, 1)), workDir) { warnings += it }

            assertThat(result.file)
                .`as`("the converted page is written to mono.pbm in the working directory")
                .isEqualTo(workDir.resolve("mono.pbm"))
            assertThat(payloadOf(result.file, 3, 1))
                .`as`("only the 127 pixel is below 128, so only the first bit is set")
                .containsExactly(0x80.toByte())
            assertThat(result.info.width).`as`("the converted page keeps its width").isEqualTo(3)
            assertThat(result.info.height).`as`("the converted page keeps its height").isEqualTo(1)
            assertThat(warnings).`as`("the conversion needs no warning").isEmpty()
        }

        /**
         * SV-08 -- a custom threshold shifts the result: the same image at
         * threshold 64 and at 200 yields different packed bits, proving the
         * setting is actually read and not hardcoded.
         */
        @Test
        fun `SV-08 a custom threshold shifts the packed bits`() {
            val low = MonochromeStep(PageSettings(colorMode = ColorMode.BW, bwThreshold = 64))
            val high = MonochromeStep(PageSettings(colorMode = ColorMode.BW, bwThreshold = 200))
            val warnings = mutableListOf<String>()

            val lowSrc = writeGrayImage(tempDir, "low.png", 2, 1, intArrayOf(100, 150))
            val lowDir = Files.createDirectory(workDir.resolve("low"))
            val lowResult = low.apply(PageImage(lowSrc, BitmapInfo(2, 1)), lowDir) { warnings += it }

            val highSrc = writeGrayImage(tempDir, "high.png", 2, 1, intArrayOf(100, 150))
            val highDir = Files.createDirectory(workDir.resolve("high"))
            val highResult = high.apply(PageImage(highSrc, BitmapInfo(2, 1)), highDir) { warnings += it }

            assertThat(payloadOf(lowResult.file, 2, 1))
                .`as`("at threshold 64 both pixels (100, 150) are at or above it, so no bit is set")
                .containsExactly(0x00.toByte())
            assertThat(payloadOf(highResult.file, 2, 1))
                .`as`("at threshold 200 both pixels are below it, so both bits are set")
                .containsExactly(0xC0.toByte())
            assertThat(warnings).`as`("the conversion needs no warning").isEmpty()
        }
    }

    @Nested
    inner class Encoding {
        @TempDir
        lateinit var workDir: Path

        private val step = MonochromeStep(PageSettings(colorMode = ColorMode.BW))

        /**
         * SV-08 -- a set bit means black: eight known pixels (black, white,
         * alternating) yield the raw payload byte 0xAA, not the dimensions
         * but the actual bits. An inverted polarity would read 0x55 here.
         */
        @Test
        fun `SV-08 a set bit means black in the packed payload`() {
            val src = writeGrayImage(tempDir, "polarity.png", 8, 1, intArrayOf(0, 255, 0, 255, 0, 255, 0, 255))
            val warnings = mutableListOf<String>()
            val result = step.apply(PageImage(src, BitmapInfo(8, 1)), workDir) { warnings += it }

            assertThat(payloadOf(result.file, 8, 1))
                .`as`("black pixels set their bit MSB first, so BWBWBWBW packs to 0xAA")
                .containsExactly(0xAA.toByte())
            assertThat(warnings).`as`("the conversion needs no warning").isEmpty()
        }

        /**
         * SV-08 -- row padding: a width of 9 pixels (not a multiple of eight)
         * produces 2 bytes per row, and the last seven bits are zero padding.
         */
        @Test
        fun `SV-08 a row whose width is not a multiple of eight ends with zero padding`() {
            val src = writeGrayImage(tempDir, "padding.png", 9, 1, IntArray(9) { 0 })
            val warnings = mutableListOf<String>()
            val result = step.apply(PageImage(src, BitmapInfo(9, 1)), workDir) { warnings += it }

            assertThat(payloadOf(result.file, 9, 1))
                .`as`("nine black pixels pack to 0xFF plus the ninth pixel in the MSB of the second byte")
                .containsExactly(0xFF.toByte(), 0x80.toByte())
            assertThat(warnings).`as`("the conversion needs no warning").isEmpty()
        }
    }

    @Nested
    inner class PassThrough {
        @TempDir
        lateinit var workDir: Path

        /**
         * SV-08 -- gray mode passes the very same [PageImage] instance through
         * and performs no file I/O, so the working directory stays empty.
         */
        @Test
        fun `SV-08 gray mode passes the same page instance through and writes no file`() {
            val step = MonochromeStep(PageSettings(colorMode = ColorMode.GRAY))
            val src = writeGrayImage(tempDir, "gray.png", 4, 2, IntArray(8) { 200 })
            val image = PageImage(src, BitmapInfo(4, 2))
            val warnings = mutableListOf<String>()
            val result = step.apply(image, workDir) { warnings += it }

            assertThat(result).isSameAs(image)
            assertThat(Files.list(workDir).use { it.count() })
                .`as`("pass-through performs no file I/O, so the working directory stays empty")
                .isEqualTo(0L)
            assertThat(warnings).`as`("pass-through needs no warning").isEmpty()
        }

        /**
         * SV-08 -- color mode passes the very same [PageImage] instance through
         * and performs no file I/O, so the working directory stays empty.
         */
        @Test
        fun `SV-08 color mode passes the same page instance through and writes no file`() {
            val step = MonochromeStep(PageSettings(colorMode = ColorMode.COLOR))
            val src = writeGrayImage(tempDir, "color.png", 4, 2, IntArray(8) { 200 })
            val image = PageImage(src, BitmapInfo(4, 2))
            val warnings = mutableListOf<String>()
            val result = step.apply(image, workDir) { warnings += it }

            assertThat(result).isSameAs(image)
            assertThat(Files.list(workDir).use { it.count() })
                .`as`("pass-through performs no file I/O, so the working directory stays empty")
                .isEqualTo(0L)
            assertThat(warnings).`as`("pass-through needs no warning").isEmpty()
        }
    }

    @Nested
    inner class Degenerate {
        @TempDir
        lateinit var workDir: Path

        private val step = MonochromeStep(PageSettings(colorMode = ColorMode.BW))

        /**
         * SV-08 -- a blank (all-white) page is legitimate, not an anomaly: it
         * converts without warning or failure to the correct dimensions with
         * no bit set.
         */
        @Test
        fun `SV-08 a blank page converts to correct dimensions with no bit set`() {
            val src = writeGrayImage(tempDir, "blank.png", 4, 2, IntArray(8) { 255 })
            val warnings = mutableListOf<String>()
            val result = step.apply(PageImage(src, BitmapInfo(4, 2)), workDir) { warnings += it }

            assertThat(result.info.width).`as`("the blank page keeps its width").isEqualTo(4)
            assertThat(result.info.height).`as`("the blank page keeps its height").isEqualTo(2)
            assertThat(payloadOf(result.file, 4, 2))
                .`as`("no pixel is below the threshold, so no bit is set")
                .containsExactly(0x00.toByte(), 0x00.toByte())
            assertThat(warnings).`as`("a blank page is legitimate and needs no warning").isEmpty()
        }

        /**
         * SV-08 -- an all-black page converts without warning or failure to
         * the correct dimensions with every pixel bit set.
         */
        @Test
        fun `SV-08 an all-black page converts to correct dimensions with every pixel bit set`() {
            val src = writeGrayImage(tempDir, "black.png", 4, 2, IntArray(8) { 0 })
            val warnings = mutableListOf<String>()
            val result = step.apply(PageImage(src, BitmapInfo(4, 2)), workDir) { warnings += it }

            assertThat(result.info.width).`as`("the black page keeps its width").isEqualTo(4)
            assertThat(result.info.height).`as`("the black page keeps its height").isEqualTo(2)
            assertThat(payloadOf(result.file, 4, 2))
                .`as`("every pixel is below the threshold, so each row packs to 0xF0")
                .containsExactly(0xF0.toByte(), 0xF0.toByte())
            assertThat(warnings).`as`("an all-black page needs no warning").isEmpty()
        }
    }

    /**
     * Writes a lossless PNG with one grey pixel (r = g = b = value) per entry
     * of [values] in row-major order.
     *
     * @param dir the directory to write into.
     * @param name the file name.
     * @param width the image width in pixels.
     * @param height the image height in pixels.
     * @param values the grey values in row-major order, exactly width * height.
     * @return the written image file.
     */
    private fun writeGrayImage(
        dir: Path,
        name: String,
        width: Int,
        height: Int,
        values: IntArray,
    ): Path {
        require(values.size == width * height) {
            "expected ${width * height} grey values for a ${width}x$height image, got ${values.size}"
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = values[y * width + x]
                image.setRGB(x, y, (value shl 16) or (value shl 8) or value)
            }
        }
        val target = dir.resolve(name)
        ImageIO.write(image, "png", target.toFile())
        return target
    }

    /**
     * Returns the packed bitmap of the PBM file at [path], asserting its
     * header names exactly the expected dimensions.
     *
     * @param path the PBM file to read.
     * @param width the expected image width in pixels.
     * @param height the expected image height in pixels.
     * @return the payload bytes after the `P4\n<w> <h>\n` header.
     */
    private fun payloadOf(
        path: Path,
        width: Int,
        height: Int,
    ): ByteArray {
        val bytes = Files.readAllBytes(path)
        val header = "P4\n$width $height\n".toByteArray(Charsets.US_ASCII)
        assertThat(bytes.sliceArray(0 until header.size))
            .`as`("the PBM header names the expected magic and dimensions")
            .isEqualTo(header)
        return bytes.sliceArray(header.size until bytes.size)
    }
}
