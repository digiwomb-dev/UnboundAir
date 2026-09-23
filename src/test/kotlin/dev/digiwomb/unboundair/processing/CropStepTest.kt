package dev.digiwomb.unboundair.processing

import dev.digiwomb.unboundair.TestImages
import dev.digiwomb.unboundair.image.JpegInfo
import dev.digiwomb.unboundair.image.LumaImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * Tests for [CropStep.apply] against the four milestone fixtures (the test
 * definitions of the milestone, test points 1 and 2 of the crop step):
 *
 * 1. a white page (DL envelope) in a gray background is cropped exactly to
 *    the detected paper box, losslessly;
 * 2. a page whose paper fills the whole frame is passed through unchanged;
 * 3. a page without any detectable paper is passed through unchanged and
 *    announced with a warning;
 * 4. a page with a dark stripe at the bottom is cropped at the bottom edge
 *    only, the left, right and top edges are kept.
 *
 * Every test copies its fixture into [tempDir] and passes the step an empty
 * [workDir], mirroring how the processing chain receives pages in
 * production. The step never modifies the input file; cropped results are
 * written into the working directory.
 */
class CropStepTest {
    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var workDir: Path

    private val crop = CropStep()

    /**
     * SV-01 -- envelope. The detector finds the white envelope in the gray
     * background and the crop removes exactly the background (the lossless
     * cut of the 1216x2494 window at +560+56).
     *
     * Luma is compared per pixel against the source window. The result is
     * bit-identical except for the first block column (x < 8): because the
     * fixture carries restart markers (DRI), [JpegTran] re-anchors the DC
     * prediction of the first block of the affected rows when the crop
     * window moves, which shifts the decoded luma of a single pixel column
     * by at most one level. Measured: 340 of 3,032,704 pixels (0.011 %),
     * all in x=0. See the milestone doc, "Testdefinitionen" and "Festgehaltene
     * Entscheidungen" item 6.
     *
     * The test asserts the exact dimensions, allows a deviation of at most
     * one level only in x < 8, requires bit-exact luma everywhere else, and
     * bounds the total number of deviating pixels to less than 1 % of the
     * area so that systematic corruption is caught.
     */
    @Test
    fun `the DL envelope is cropped to 1216x2494 at +560+56 and keeps its luma`() {
        val src = TestImages.copy("envelope_dl_300dpi_raw.jpg", tempDir)
        val image = PageImage(src, JpegInfo.read(src))
        val warnings = mutableListOf<String>()
        val result = crop.apply(image, workDir) { warnings += it }

        assertEquals(1216, JpegInfo.read(result.file).width)
        assertEquals(2494, JpegInfo.read(result.file).height)

        val sourceLuma = LumaImage.read(src)
        val croppedLuma = LumaImage.read(result.file)
        assertEquals(1216, croppedLuma.width)
        assertEquals(2494, croppedLuma.height)
        var deviations = 0
        for (y in 0 until croppedLuma.height) {
            for (x in 0 until croppedLuma.width) {
                val expected = sourceLuma.get(x + 560, y + 56)
                val actual = croppedLuma.get(x, y)
                val deviation = abs(expected - actual)
                if (deviation != 0) {
                    deviations++
                }
                if (x < 8) {
                    // First block column of the new JPEG file: see the
                    // documented DC re-anchoring deviation above.
                    assertTrue(
                        deviation <= 1,
                        "luma of pixel ($x, $y) in the first block column of the cropped envelope " +
                            "deviates by $deviation level(s) (expected $expected, got $actual)",
                    )
                } else {
                    assertEquals(
                        expected,
                        actual,
                        "luma of pixel ($x, $y) of the cropped envelope differs from the source window",
                    )
                }
            }
        }
        assertTrue(
            deviations < croppedLuma.width * croppedLuma.height / 100,
            "$deviations pixels deviate from the source window; expected fewer than 1 %",
        )
    }

    /**
     * SV-01 -- A4. The paper touches all four frame edges (see
     * PaperDetectorTest), so the detected box is the whole frame and the
     * step must pass the page through unchanged: the very same [PageImage]
     * instance, no output file written into the working directory.
     */
    @Test
    fun `a page whose paper fills the whole frame is passed through unchanged`() {
        val src = TestImages.copy("din_a4_300dpi_raw.jpg", tempDir)
        val image = PageImage(src, JpegInfo.read(src))
        val warnings = mutableListOf<String>()
        val result = crop.apply(image, workDir) { warnings += it }

        assertSame(image, result)
        assertEquals(0L, Files.list(workDir).use { it.count() })
    }

    /**
     * SV-02 -- no paper at all. The step must not crop: it carries the very
     * same [PageImage] through, and the caller receives a warning that
     * names the missing paper.
     */
    @Test
    fun `a page without paper is passed through unchanged with a warning`() {
        val src = TestImages.copy("dark_page.jpg", tempDir)
        val image = PageImage(src, JpegInfo.read(src))
        val warnings = mutableListOf<String>()
        val result = crop.apply(image, workDir) { warnings += it }

        assertSame(image, result)
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.any { it.contains("no paper") })
    }

    /**
     * Test point 2 -- bottom stripe only. The detector finds the white page
     * (its bottom edge ends where the dark stripe begins) and the crop cuts
     * exactly that stripe: the left, right and top edges are kept, so the
     * result is the full width 1240 of the source but 162 pixels shorter
     * (1716 - 162 = 1554).
     */
    @Test
    fun `a dark bottom stripe is cut while the sides and top edge are kept`() {
        val src = TestImages.copy("a4_bottom_stripe.jpg", tempDir)
        val image = PageImage(src, JpegInfo.read(src))
        val warnings = mutableListOf<String>()
        val result = crop.apply(image, workDir) { warnings += it }

        assertEquals(1240, JpegInfo.read(result.file).width)
        assertEquals(1554, JpegInfo.read(result.file).height)
    }
}
