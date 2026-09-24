package dev.digiwomb.unboundair.image

import dev.digiwomb.unboundair.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [JpegInfo.read] (SV-01, OF-06): the dimensions, the component
 * count, and the iMCU size must come from each file itself, never from a
 * guess about the material.
 *
 * The two real scanner scans are 4:2:2 (iMCU 16x8) while the two synthetic
 * fixtures are 4:4:0 (iMCU 16x16), so a green suite proves the iMCU size is
 * read from the file's sampling factors instead of assumed. Every test
 * copies its fixture into its own [dir] first, mirroring how the production
 * steps receive plain file paths.
 */
class JpegInfoTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `SV-01 the real DL envelope reads as 1776x2769 with 3 components and a 16x8 imcu`() {
        expectStructure("envelope_dl_300dpi_raw.jpg", JpegInfo(1776, 2769, 3, 16, 8))
    }

    @Test
    fun `SV-01 the real A4 scan reads as 2464x3425 with 3 components and a 16x8 imcu`() {
        expectStructure("din_a4_300dpi_raw.jpg", JpegInfo(2464, 3425, 3, 16, 8))
    }

    @Test
    fun `SV-01 the synthetic bottom stripe reads as 1240x1754 with 3 components and a 16x16 imcu`() {
        expectStructure("a4_bottom_stripe.jpg", JpegInfo(1240, 1754, 3, 16, 16))
    }

    @Test
    fun `SV-01 the synthetic dark page reads as 600x800 with 3 components and a 16x16 imcu`() {
        expectStructure("dark_page.jpg", JpegInfo(600, 800, 3, 16, 16))
    }

    @Test
    fun `SV-01 a file that is not a JPEG raises IllegalArgumentException`() {
        val path = dir.resolve("not_a_jpeg.bin")
        Files.write(path, byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))

        assertThatThrownBy { JpegInfo.read(path) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * Copies the fixture [fileName] into [dir] and expects [JpegInfo.read]
     * to return exactly [expected].
     */
    private fun expectStructure(
        fileName: String,
        expected: JpegInfo,
    ) {
        val file = TestImages.copy(fileName, dir)
        val info = JpegInfo.read(file)

        assertThat(info.width).`as`("width of $fileName").isEqualTo(expected.width)
        assertThat(info.height).`as`("height of $fileName").isEqualTo(expected.height)
        assertThat(info.components).`as`("component count of $fileName").isEqualTo(expected.components)
        assertThat(info.imcuWidth).`as`("iMCU width of $fileName").isEqualTo(expected.imcuWidth)
        assertThat(info.imcuHeight).`as`("iMCU height of $fileName").isEqualTo(expected.imcuHeight)
    }
}
