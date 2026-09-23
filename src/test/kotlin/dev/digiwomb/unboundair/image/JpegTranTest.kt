package dev.digiwomb.unboundair.image

import dev.digiwomb.unboundair.TestImages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the external `jpegtran` invocations of [JpegTran] (SV-01, SV-03):
 * the lossless crop keeps a window of the source with its components intact,
 * the grayscale conversion reduces the stored components to a single luma
 * component, and a file that is not a JPEG fails with a [JpegTranException]
 * whose message names the program.
 *
 * Every test works on a copy inside its own [dir] (via [TestImages.copy]) so
 * the committed fixtures are never modified, and the error case feeds a plain
 * byte file that is no JPEG.
 */
class JpegTranTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `crop keeps a 16x8 window with its 3 components intact`() {
        val source = TestImages.copy("envelope_dl_300dpi_raw.jpg", dir)
        val target = dir.resolve("cropped.jpg")

        JpegTran.crop(source, target, 16, 8, 0, 0)

        val info = JpegInfo.read(target)
        assertEquals(16, info.width, "width of the crop window")
        assertEquals(8, info.height, "height of the crop window")
        assertEquals(3, info.components, "the crop keeps all 3 stored components")
    }

    @Test
    fun `grayscale reduces the stored components to a single luma component`() {
        val source = TestImages.copy("envelope_dl_300dpi_raw.jpg", dir)
        val target = dir.resolve("gray.jpg")

        JpegTran.grayscale(source, target)

        val info = JpegInfo.read(target)
        assertEquals(1, info.components, "grayscale leaves exactly one component")
    }

    @Test
    fun `cropping a file that is not a JPEG fails with a JpegTranException naming the program`() {
        val nonJpeg = dir.resolve("not_a_jpeg.bin")
        Files.write(nonJpeg, byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        val target = dir.resolve("cropped.jpg")

        val failure =
            assertThrows(JpegTranException::class.java) {
                JpegTran.crop(nonJpeg, target, 16, 8, 0, 0)
            }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("jpegtran"), "the message names the failed program: $message")
    }
}
