package dev.digiwomb.unboundair.output

import dev.digiwomb.unboundair.TestImages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Golden-master test for the assembled PDF (AU-01, SV-05).
 *
 * This file carries what were originally two issues, #60 and #66, because one
 * file covers both and splitting them would mean building the same document
 * twice:
 *
 * - **Determinism (#60):** the same input, assembled twice with the clock
 *   pinned, produces byte-identical output. Without this, no byte comparison
 *   against a stored file could ever be trusted -- a failure would be
 *   indistinguishable from ordinary run-to-run variation.
 * - **Content (#66):** the bytes match the committed reference in
 *   `golden/three_pages_300dpi.pdf`, whose sha256 is listed in
 *   `manifest.sha256` and therefore checked by `GoldenManifestTest` as well.
 *
 * The order matters. Determinism is asserted first: if it fails, the golden
 * comparison below is meaningless and its failure would send the reader looking
 * in the wrong place.
 *
 * **What this catches that the unit tests do not.** `PdfBuilderTest` checks the
 * properties we thought to name -- page count, embedded bytes, page size. The
 * golden file pins everything else too: the object structure, the metadata, the
 * content stream. A PDFBox upgrade that silently changes how images are
 * referenced, or a stray field added to the document information, turns this
 * red and nothing else would.
 *
 * **When this test fails after an intentional change,** regenerate the golden
 * file and its manifest entry in the same commit, and say in the message why
 * the bytes moved. A golden file updated without that explanation is worthless.
 *
 * Offline (DC-03): committed fixtures, a pinned clock, no device, no network.
 */
class PdfGoldenTest {
    @Test
    fun `AU-01 the same pages with a pinned clock produce byte-identical PDFs`(
        @TempDir dir: Path,
    ) {
        val first = dir.resolve("first.pdf")
        val second = dir.resolve("second.pdf")

        PdfBuilder(FIXED_CLOCK).build(threePages(dir), first)
        PdfBuilder(FIXED_CLOCK).build(threePages(dir), second)

        assertThat(Files.readAllBytes(first))
            .`as`(
                "two runs over the same input must agree byte for byte; without this the golden " +
                    "comparison below could not tell a regression from run-to-run noise",
            ).isEqualTo(Files.readAllBytes(second))
    }

    @Test
    fun `AU-01 three pages are byte-identical to the golden PDF`(
        @TempDir dir: Path,
    ) {
        val target = dir.resolve("actual.pdf")

        PdfBuilder(FIXED_CLOCK).build(threePages(dir), target)

        assertThat(Files.readAllBytes(target))
            .`as`(
                "the assembled PDF must match golden/$GOLDEN_NAME byte for byte; if this change was " +
                    "intended, regenerate the golden file and its manifest entry in the same commit",
            ).isEqualTo(goldenBytes())
    }

    /**
     * The document under test: envelope, A4, envelope at 300 dpi.
     *
     * Two different fixtures on purpose. Three copies of one image would let a
     * builder that embeds the first page three times pass unnoticed, and the
     * differing dimensions also pin that each page keeps its own size.
     */
    private fun threePages(dir: Path): List<PdfPage> =
        listOf(
            materialise(ENVELOPE, dir, "page1"),
            materialise(A4, dir, "page2"),
            materialise(ENVELOPE, dir, "page3"),
        )

    private fun materialise(
        fixture: String,
        dir: Path,
        name: String,
    ): PdfPage {
        val file = dir.resolve("$name.jpg")
        Files.write(file, TestImages.bytes(fixture))
        return PdfPage(file, DPI)
    }

    private fun goldenBytes(): ByteArray =
        javaClass.getResourceAsStream("/golden/$GOLDEN_NAME")?.use { it.readAllBytes() }
            ?: throw IllegalStateException("golden file not found on the classpath: /golden/$GOLDEN_NAME")

    private companion object {
        const val ENVELOPE = "envelope_dl_300dpi_raw.jpg"
        const val A4 = "din_a4_300dpi_raw.jpg"
        const val GOLDEN_NAME = "three_pages_300dpi.pdf"
        const val DPI = 300

        /**
         * The pinned instant. Any fixed value would do; this one is the date the
         * golden file was generated, so the metadata inside it is not misleading
         * to anyone who opens it.
         */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-27T10:15:30Z"), ZoneOffset.UTC)
    }
}
