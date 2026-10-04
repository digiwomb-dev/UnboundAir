package dev.digiwomb.unboundair.image

import dev.digiwomb.unboundair.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [BitmapInfo.read] and the [PageInfo] substitutability (SV-08): a
 * 1-bit page reports its size from its `P4` header without pretending to be a
 * JPEG.
 *
 * All PBM bytes are built by hand in the test; the only committed fixture
 * used is the real JPEG for the cross-rejection and the substitutability
 * checks. Every test writes into its own [dir], mirroring how the production
 * steps receive plain file paths.
 */
class PageInfoTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `SV-08 a P4 header parses and reports its dimensions`() {
        val path = writePbm("page.pbm", "P4\n8 4\n", ByteArray(4))

        val info = BitmapInfo.read(path)

        assertThat(info.width).`as`("width of hand-written PBM").isEqualTo(8)
        assertThat(info.height).`as`("height of hand-written PBM").isEqualTo(4)
    }

    @Test
    fun `SV-08 a comment line in the P4 header is skipped`() {
        val path = writePbm("commented.pbm", "P4\n# scanned at 300dpi\n8 4\n", ByteArray(4))

        val info = BitmapInfo.read(path)

        assertThat(info.width).`as`("width of commented PBM").isEqualTo(8)
        assertThat(info.height).`as`("height of commented PBM").isEqualTo(4)
    }

    @Test
    fun `SV-08 a JPEG is rejected by BitmapInfo read`() {
        val path = TestImages.copy("envelope_dl_300dpi_raw.jpg", dir)

        assertThatThrownBy { BitmapInfo.read(path) }
            .`as`("BitmapInfo reading a JPEG")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(path.toString())
    }

    @Test
    fun `SV-08 a PBM is rejected by JpegInfo read`() {
        val path = writePbm("page.pbm", "P4\n8 4\n", ByteArray(4))

        assertThatThrownBy { JpegInfo.read(path) }
            .`as`("JpegInfo reading a PBM")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(path.toString())
    }

    @Test
    fun `SV-08 a JpegInfo satisfies the PageInfo contract`() {
        val path = TestImages.copy("envelope_dl_300dpi_raw.jpg", dir)

        val info: PageInfo = JpegInfo.read(path)

        assertThat(info.width).`as`("PageInfo width of the DL envelope").isEqualTo(1776)
        assertThat(info.height).`as`("PageInfo height of the DL envelope").isEqualTo(2769)
    }

    @Test
    fun `SV-08 a P4 header without dimensions fails`() {
        val path = writePbm("truncated.pbm", "P4\n", byteArrayOf())

        assertThatThrownBy { BitmapInfo.read(path) }
            .`as`("BitmapInfo reading a PBM without dimensions")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(path.toString())
    }

    @Test
    fun `SV-08 a P4 header with zero width fails`() {
        val path = writePbm("zero_width.pbm", "P4\n0 100\n", byteArrayOf())

        assertThatThrownBy { BitmapInfo.read(path) }
            .`as`("BitmapInfo reading a PBM with zero width")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(path.toString())
    }

    @Test
    fun `SV-08 a missing PBM file fails`() {
        val path = dir.resolve("missing.pbm")

        assertThatThrownBy { BitmapInfo.read(path) }
            .`as`("BitmapInfo reading a missing file")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(path.toString())
    }

    @Test
    fun `SV-08 a P4 header with a non-numeric width fails`() {
        val path = writePbm("not_numeric.pbm", "P4\nwide 100\n", byteArrayOf())

        assertThatThrownBy { BitmapInfo.read(path) }
            .`as`("BitmapInfo reading a PBM with a non-numeric width")
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(path.toString())
    }

    /**
     * Writes a PBM file of [header] plus [payload] bytes into [dir] under
     * [fileName] and returns its path.
     */
    private fun writePbm(
        fileName: String,
        header: String,
        payload: ByteArray,
    ): Path {
        val path = dir.resolve(fileName)
        Files.write(path, header.toByteArray(Charsets.US_ASCII) + payload)
        return path
    }
}
