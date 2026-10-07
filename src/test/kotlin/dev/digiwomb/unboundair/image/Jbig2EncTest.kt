package dev.digiwomb.unboundair.image

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests for the external `jbig2` runs of [Jbig2Enc] (SV-08):
 * two similar pages share one dictionary, two different pages keep their
 * order, a stray page file fails loudly, PDF-mode streams carry no file
 * header, and both failure modes (non-zero exit, missing program) surface
 * as [Jbig2EncException].
 *
 * This test requires `jbig2` in the container (#143); its version is pinned
 * by the base image digest, and the size relations asserted below depend on
 * it. Integration, not unit, because the real external `jbig2` runs — the
 * same system dependency the runtime image carries
 * (`docs/internal/teststrategie.md` places `jpegtran` in this layer for exactly that
 * reason).
 *
 * All pages are hand-built PBM files (`P4` header plus packed rows, MSB
 * first, set bit = black); the shared-dictionary test uses the committed bw
 * envelope fixture twice, because the size relation needs realistic content.
 * No network is needed (DC-03).
 */
class Jbig2EncTest {
    @TempDir
    lateinit var dir: Path

    private companion object {
        /** The committed bw envelope fixture used as a realistic similar page. */
        const val ENVELOPE_BW_PBM = "/golden/envelope_dl_300dpi_bw.pbm"
    }

    @Test
    fun `SV-08 two similar pages share one dictionary and page streams stay smaller than the globals`() {
        val first = materialise(dir, "page_a.pbm")
        val second = materialise(dir, "page_b.pbm")

        val output = Jbig2Enc.encode(listOf(first, second), dir)

        assertThat(output.globals.size).`as`("globals hold the shared symbol dictionary").isGreaterThan(0)
        assertThat(output.pages).`as`("one page stream per input page").hasSize(2)
        val pagesTotal = output.pages.sumOf { it.size }
        // Spike #140 measured this shape on two similar real pages (globals
        // 10 169 B, page streams 2 259 + 2 259 B, pages together ~44 % of the
        // globals). The symbols live in the dictionary, not in the pages;
        // without `-s` the pages would each carry their symbols and dwarf the
        // globals. A synthetic 64x64 block pattern does not show it (few
        // symbols, dominant page context), so the two pages here are the
        // committed bw envelope fixture twice - the same shape the spike
        // measured.
        assertThat(pagesTotal).`as`("shared dictionary carries the symbols, not the page streams").isLessThan(
            output.globals.size,
        )
    }

    @Test
    fun `SV-08 two different pages come back in input order and stay distinguishable`() {
        val black = writePbm(dir, "black.pbm", 64, 64, solidRows(64, 64, true))
        val white = writePbm(dir, "white.pbm", 64, 64, solidRows(64, 64, false))

        val output = Jbig2Enc.encode(listOf(black, white), dir)

        assertThat(output.pages).`as`("one page stream per input page").hasSize(2)
        assertThat(output.pages[0]).`as`("the mostly-black page comes back first").isNotEqualTo(output.pages[1])
        assertThat(output.pages[0].size)
            .`as`("the mostly-black page stream differs from the mostly-white one")
            .isNotEqualTo(output.pages[1].size)
    }

    @Test
    fun `SV-08 a stray page file fails loudly naming both page counts`() {
        val first = writePbm(dir, "page_a.pbm", 64, 64, blockRows(64, 64, 0))
        val second = writePbm(dir, "page_b.pbm", 64, 64, blockRows(64, 64, 1))
        Files.write(dir.resolve("pages.9999"), byteArrayOf(0, 1, 2, 3))

        assertThatThrownBy { Jbig2Enc.encode(listOf(first, second), dir) }
            .isInstanceOf(Jbig2EncException::class.java)
            .hasMessageContaining("3")
            .hasMessageContaining("2")
    }

    @Test
    fun `SV-08 PDF-mode page streams carry no JBIG2 file header`() {
        val first = writePbm(dir, "page_a.pbm", 64, 64, blockRows(64, 64, 0))
        val second = writePbm(dir, "page_b.pbm", 64, 64, blockRows(64, 64, 1))

        val output = Jbig2Enc.encode(listOf(first, second), dir)

        // A file header in the stream would make it unusable in a PDF, where
        // the streams are embedded as /JBIG2Decode page segments only.
        val magic = byteArrayOf(0x97.toByte(), 0x4A, 0x42, 0x32)
        output.pages.forEachIndexed { index, page ->
            assertThat(page.sliceArray(0 until 4))
                .`as`("page stream %d carries no JBIG2 file header", index)
                .isNotEqualTo(magic)
        }
    }

    @Test
    fun `SV-08 a file that is not a PBM fails with a Jbig2EncException naming the program and command line`() {
        val bogus = dir.resolve("not_a_pbm.txt")
        Files.writeString(bogus, "this is not a PBM file\n")

        assertThatThrownBy { Jbig2Enc.encode(listOf(bogus), dir) }
            .isInstanceOf(Jbig2EncException::class.java)
            .hasMessageContaining("jbig2")
            .hasMessageContaining("-s -p -b")
    }

    @Test
    fun `SV-08 a missing program fails with a Jbig2EncException with its cause set`() {
        val page = writePbm(dir, "page.pbm", 64, 64, blockRows(64, 64, 0))
        val original = Jbig2Enc.program
        Jbig2Enc.program = "no-such-jbig2-binary"
        try {
            assertThatThrownBy { Jbig2Enc.encode(listOf(page), dir) }
                .isInstanceOf(Jbig2EncException::class.java)
                .hasCauseInstanceOf(IOException::class.java)
        } finally {
            Jbig2Enc.program = original
        }
    }

    @Test
    fun `SV-08 an empty source list is rejected before a process starts`() {
        assertThatThrownBy { Jbig2Enc.encode(emptyList(), dir) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(dir.toFile().listFiles())
            .`as`("no process ran, so the workDir stays empty")
            .isEmpty()
    }

    /**
     * Writes a binary PBM (`P4`) file: the header `P4\n<w> <h>\n` followed
     * by the packed row bytes, MSB first, set bit = black.
     */
    private fun writePbm(
        dir: Path,
        name: String,
        width: Int,
        height: Int,
        rows: List<ByteArray>,
    ): Path {
        require(rows.size == height) { "need one row per height unit" }
        val target = dir.resolve(name)
        val header = "P4\n$width $height\n".toByteArray(Charsets.US_ASCII)
        val body = rows.fold(byteArrayOf()) { acc, row -> acc + row }
        Files.write(target, header + body)
        return target
    }

    /**
     * Copies the committed bw envelope fixture (golden/envelope_dl_300dpi_bw.pbm)
     * into [dir] under [name] and returns its path.
     */
    private fun materialise(
        dir: Path,
        name: String,
    ): Path {
        val resource =
            javaClass.getResourceAsStream(ENVELOPE_BW_PBM)
                ?: throw IllegalStateException("fixture not found on the classpath: $ENVELOPE_BW_PBM")
        val target = dir.resolve(name)
        Files.copy(resource, target)
        return target
    }

    /**
     * Builds [height] rows of packed bytes with a repeated block pattern;
     * [shift] offsets the pattern so two pages look similar but not
     * identical (shared symbols with slight variation).
     */
    private fun blockRows(
        width: Int,
        height: Int,
        shift: Int,
    ): List<ByteArray> {
        val rowBytes = (width + 7) / 8
        return (0 until height).map { y ->
            ByteArray(rowBytes) { x ->
                (((x + y + shift) % 4) * 0x55).toByte()
            }
        }
    }

    /** Builds [height] rows that are all black or all white. */
    private fun solidRows(
        width: Int,
        height: Int,
        black: Boolean,
    ): List<ByteArray> {
        val rowBytes = (width + 7) / 8
        val fill = if (black) 0xFF.toByte() else 0x00.toByte()
        return (0 until height).map { ByteArray(rowBytes) { fill } }
    }
}
