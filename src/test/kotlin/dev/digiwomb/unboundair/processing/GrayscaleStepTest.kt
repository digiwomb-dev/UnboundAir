package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.JpegInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [GrayscaleStep.apply] against the DL envelope fixture and the two
 * color modes of the step's [PageSettings] (SV-03):
 *
 * 1. [ColorMode.GRAY] (the default): the page is converted to grayscale with
 *    `jpegtran -grayscale` (a transform of the stored color components, never
 *    a re-compression) and written to `gray.jpg` in the working directory;
 *    the step returns a new [PageImage] whose [JpegInfo] is freshly read
 *    from that file and stores a single luma component;
 * 2. [ColorMode.COLOR]: the page stays in the color in which the scanner
 *    produced it; the very same [PageImage] instance passes through and no
 *    file is written into the working directory.
 *
 * Every test copies the color (3-component) fixture into the outer [tempDir]
 * and passes the step an empty working directory of its color-mode group,
 * mirroring how the processing chain receives pages in production. The step
 * never modifies the input file; in [ColorMode.GRAY] the only external
 * program involved is `jpegtran` (a system dependency of the container, so
 * the test stays offline per DC-03), and [ColorMode.COLOR] performs no file
 * I/O at all.
 */
class GrayscaleStepTest {
    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class Grayscale {
        @TempDir
        lateinit var workDir: Path

        private val grayscale = GrayscaleStep(PageSettings(colorMode = ColorMode.GRAY))

        /**
         * SV-03 -- the default color mode. The step converts the DL envelope
         * to grayscale and writes `gray.jpg` into the working directory: the
         * result is a new [PageImage] whose [JpegInfo] carries exactly one
         * luma component, the dimensions of the source are preserved, and
         * the conversion needs no warning.
         */
        @Test
        fun `SV-03 gray mode converts the page and returns a single-luma gray-jpeg in the work dir`() {
            val src = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
            val image = PageImage(src, JpegInfo.read(src))
            val warnings = mutableListOf<String>()
            val result = grayscale.apply(image, workDir) { warnings += it }

            assertThat(result)
                .`as`("the conversion must yield a new page instance, not the input")
                .isNotSameAs(image)
            assertThat(result.file).isEqualTo(workDir.resolve("gray.jpg"))
            assertThat(Files.exists(result.file)).isTrue()
            assertThat(JpegInfo.read(result.file).components)
                .`as`("a grayscale JPEG stores exactly one luma component")
                .isEqualTo(1)
            assertThat(JpegInfo.read(result.file).width).isEqualTo(image.info.width)
            assertThat(JpegInfo.read(result.file).height).isEqualTo(image.info.height)
            assertThat(warnings).isEmpty()
        }
    }

    @Nested
    inner class Color {
        @TempDir
        lateinit var workDir: Path

        private val grayscale = GrayscaleStep(PageSettings(colorMode = ColorMode.COLOR))

        /**
         * SV-03 -- the page stays in the color in which the scanner produced
         * it: the very same [PageImage] instance passes through, no file is
         * written into the working directory, and no warning is emitted.
         */
        @Test
        fun `SV-03 color mode passes the same page instance through and writes no file`() {
            val src = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
            val image = PageImage(src, JpegInfo.read(src))
            val warnings = mutableListOf<String>()
            val result = grayscale.apply(image, workDir) { warnings += it }

            assertThat(result).isSameAs(image)
            assertThat(Files.list(workDir).use { it.count() }).isEqualTo(0L)
            assertThat(warnings).isEmpty()
        }
    }
}
