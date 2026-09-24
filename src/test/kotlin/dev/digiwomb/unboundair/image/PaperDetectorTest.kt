package dev.digiwomb.unboundair.image

import dev.digiwomb.unboundair.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for [PaperDetector.detect] and [PaperDetector.isPlausible] (SV-01, SV-02).
 *
 * Three hand-built luma fields pin down the detection algorithm without any file
 * in between: a two pixel dark border must be cut away, a fully bright frame must
 * come back whole, and a fully dark page must be rejected. The real fixtures then
 * prove the same algorithm on decoded JPEGs: the A4 scan occupies the whole frame
 * (the evidence for the byte-identical A4 case of the crop step), the DL envelope
 * comes back as the box the test definitions pin down, and the synthetic dark
 * page is rejected.
 */
class PaperDetectorTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `SV-01 a two pixel dark border detects the bright rectangle inside it`() {
        val image = withBorder(20, 10, border = 2, borderLuma = 0, paperLuma = 200)

        assertThat(PaperDetector.detect(image)).isEqualTo(PaperBox(2, 2, 17, 7))
    }

    @Test
    fun `SV-01 a fully bright image detects the whole frame`() {
        val image = flat(20, 10, 200)

        assertThat(PaperDetector.detect(image)).isEqualTo(PaperBox(0, 0, 19, 9))
    }

    @Test
    fun `SV-01 a fully dark image detects no paper`() {
        val image = flat(20, 10, 5)

        assertThat(PaperDetector.detect(image)).isNull()
    }

    @Test
    fun `SV-02 a box covering only one percent of the image fails the minimum area`() {
        val box = PaperBox(0, 0, 9, 9)

        assertThat(PaperDetector.isPlausible(box, 100, 100, 0.10, 6.0)).isFalse()
    }

    @Test
    fun `SV-02 a box covering the whole image is plausible`() {
        val box = PaperBox(0, 0, 9, 9)

        assertThat(PaperDetector.isPlausible(box, 10, 10, 0.10, 6.0)).isTrue()
    }

    @Test
    fun `SV-02 a box with a 50 to 1 side ratio fails the aspect ratio limit`() {
        val box = PaperBox(0, 0, 99, 1)

        assertThat(PaperDetector.isPlausible(box, 100, 100, 0.10, 6.0)).isFalse()
    }

    @Test
    fun `SV-01 the real a4 scan detects the whole frame`() {
        val image = LumaImage.read(TestImages.copy("din_a4_300dpi_raw.jpg", dir))

        assertThat(PaperDetector.detect(image)).isEqualTo(PaperBox(0, 0, 2463, 3424))
    }

    @Test
    fun `SV-01 the real dl envelope detects the box from the test definitions`() {
        val image = LumaImage.read(TestImages.copy("envelope_dl_300dpi_raw.jpg", dir))

        assertThat(PaperDetector.detect(image)).isEqualTo(PaperBox(546, 50, 1775, 2549))
    }

    @Test
    fun `SV-01 the synthetic dark page detects no paper`() {
        val image = LumaImage.read(TestImages.copy("dark_page.jpg", dir))

        assertThat(PaperDetector.detect(image)).isNull()
    }

    /**
     * Builds a luma field that holds the single value [fill] in every pixel.
     */
    private fun flat(
        width: Int,
        height: Int,
        fill: Int,
    ): LumaImage {
        val luma = ByteArray(width * height)
        luma.fill(fill.toByte())
        return LumaImage(width, height, luma)
    }

    /**
     * Builds a luma field whose [border] outer pixel rows and columns hold
     * [borderLuma] and whose interior holds [paperLuma].
     */
    private fun withBorder(
        width: Int,
        height: Int,
        border: Int,
        borderLuma: Int,
        paperLuma: Int,
    ): LumaImage {
        val luma = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inBorder = x < border || x >= width - border || y < border || y >= height - border
                luma[y * width + x] = if (inBorder) borderLuma.toByte() else paperLuma.toByte()
            }
        }
        return LumaImage(width, height, luma)
    }
}
