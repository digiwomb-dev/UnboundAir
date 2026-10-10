package dev.digiwomb.unboundair.output

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.imageio.ImageIO

/**
 * Property tests for the page placement in [PdfBuilder.placeImage] (SV-09).
 *
 * `PdfPageSizeTest` pins the placement at named examples; what actually arrives here is any
 * scan size against any configured box — exactly the "arbitrary image geometry" case the
 * property layer exists for (`docs/internal/teststrategie.md`). Two properties hold without
 * exception:
 *
 * - the content is drawn at its physical size (`pixels / dpi * 72`, SV-05) and never scaled
 *   to the box — so it is never enlarged and never shrunk, whatever the combination;
 * - the box is the configured target exactly as written (never swapped, no orientation
 *   automation), and the image sits at the offset `(box - content) / 2` — which is exactly
 *   non-negative when the content fits that axis and exactly negative when it overflows it.
 *
 * A mistake such as a `scaleToFit` sneaking back in, a swapped width/height, or an offset
 * computed from the wrong axis would fail one of these properties for many generated cases,
 * not just for the one example that happened to catch it.
 *
 * Offline (DC-03): each case synthesises a JPEG locally (no fixture variation could cover
 * arbitrary pixel counts) and writes the PDF into the test's temp dir; OpenPDF writes,
 * PDFBox reads back (producer/verifier separation as everywhere in this package). Every run
 * uses a fixed [SEED] and a pinned clock, so the cases are deterministic.
 */
class PdfPageSizePropertyTest {
    @Nested
    inner class Placement {
        /**
         * The drawn size is always the physical size, for any pixels and any target box.
         *
         * The `cm` matrix draws with `scaleAbsolute(pixels / dpi * 72, …)`; the box plays no
         * part in that number. The shear terms `b`/`c` are zero as well — the builder never
         * rotates, and a `scaleToFit` (which is what "place into the box" would tempt) would
         * make `a`/`d` deviate from the physical size whenever box and content differ.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `SV-09 the drawn content keeps its physical size and stays upright for any geometry`(
            @TempDir dir: Path,
        ) {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED, iterations = ITERATIONS),
                    PIXELS,
                    PIXELS,
                    BOX_POINTS,
                    BOX_POINTS,
                ) { pixelsWide, pixelsHigh, boxWide, boxHigh ->
                    val fixed = TargetPageSize.Fixed(boxWide.toFloat(), boxHigh.toFloat())
                    buildPdf(dir, pixelsWide, pixelsHigh, fixed).use { document ->
                        val matrix = imageMatrix(document.getPage(0))
                        val contentWide = pixelsWide / DPI.toFloat() * 72f
                        val contentHigh = pixelsHigh / DPI.toFloat() * 72f
                        assertThat(matrix[0])
                            .`as`(
                                "drawn width must be the physical width for %d px into a %s x %s pt box, never fitted",
                                pixelsWide,
                                boxWide,
                                boxHigh,
                            ).isCloseTo(contentWide, within(TOLERANCE_POINTS))
                        assertThat(matrix[3])
                            .`as`(
                                "drawn height must be the physical height for %d px into a %s x %s pt box, never fitted",
                                pixelsHigh,
                                boxWide,
                                boxHigh,
                            ).isCloseTo(contentHigh, within(TOLERANCE_POINTS))
                        assertThat(matrix[1])
                            .`as`("no rotation may ever enter: the shear term b stays zero")
                            .isCloseTo(0f, within(TOLERANCE_POINTS))
                        assertThat(matrix[2])
                            .`as`("no rotation may ever enter: the shear term c stays zero")
                            .isCloseTo(0f, within(TOLERANCE_POINTS))
                    }
                }
            }
        }

        /**
         * The box is the target exactly as configured, and the offset is exactly half the
         * free space — non-negative exactly when the content fits that axis.
         *
         * The offset `(box - content) / 2` is negative exactly on overflow, and the box is
         * never swapped for the content's orientation: `boxWide`/`boxHigh` appear in the
         * `/MediaBox` in the order they were configured, whatever the pixel aspect.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `SV-09 the box is the target as written and the offset is half the free space, negative exactly on overflow`(
            @TempDir dir: Path,
        ) {
            runBlocking {
                checkAll(
                    PropTestConfig(seed = SEED, iterations = ITERATIONS),
                    PIXELS,
                    PIXELS,
                    BOX_POINTS,
                    BOX_POINTS,
                ) { pixelsWide, pixelsHigh, boxWide, boxHigh ->
                    val fixed = TargetPageSize.Fixed(boxWide.toFloat(), boxHigh.toFloat())
                    buildPdf(dir, pixelsWide, pixelsHigh, fixed).use { document ->
                        val page = document.getPage(0)
                        val box = page.mediaBox
                        val contentWide = pixelsWide / DPI.toFloat() * 72f
                        val contentHigh = pixelsHigh / DPI.toFloat() * 72f
                        assertThat(box.width)
                            .`as`("the MediaBox takes the configured width, never swapped for the content's aspect")
                            .isCloseTo(boxWide.toFloat(), within(TOLERANCE_POINTS))
                        assertThat(box.height)
                            .`as`("the MediaBox takes the configured height, never swapped for the content's aspect")
                            .isCloseTo(boxHigh.toFloat(), within(TOLERANCE_POINTS))
                        val matrix = imageMatrix(page)
                        assertThat(matrix[4])
                            .`as`("the x offset centres the content: (box - content) / 2")
                            .isCloseTo((boxWide - contentWide) / 2f, within(TOLERANCE_POINTS))
                        assertThat(matrix[5])
                            .`as`("the y offset centres the content: (box - content) / 2")
                            .isCloseTo((boxHigh - contentHigh) / 2f, within(TOLERANCE_POINTS))
                        assertThat(matrix[4] >= 0f)
                            .`as`("the x offset is negative exactly when the content overflows the box")
                            .isEqualTo(contentWide <= boxWide)
                        assertThat(matrix[5] >= 0f)
                            .`as`("the y offset is negative exactly when the content overflows the box")
                            .isEqualTo(contentHigh <= boxHigh)
                    }
                }
            }
        }

        /** Synthesises a JPEG of the given pixel size, places it into [fixed], writes the PDF. */
        private fun buildPdf(
            dir: Path,
            pixelsWide: Int,
            pixelsHigh: Int,
            fixed: TargetPageSize.Fixed,
        ): PDDocument {
            val image = BufferedImage(pixelsWide, pixelsHigh, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, pixelsWide, pixelsHigh)
            graphics.color = Color.BLACK
            graphics.fillRect(0, 0, pixelsWide / 2, pixelsHigh / 2)
            graphics.dispose()
            val jpeg = dir.resolve("case.jpg")
            check(ImageIO.write(image, "JPEG", jpeg.toFile())) { "no JPEG writer: cannot synthesise the case" }
            val pdf = dir.resolve("out.pdf")
            PdfBuilder(FIXED_CLOCK, fixed).build(listOf(PdfPage(jpeg, DPI)), pdf)
            return Loader.loadPDF(pdf.toFile())
        }

        /**
         * Reads the single `cm` matrix placing the image on [page]: `a b c d e f`,
         * where `a`/`d` are the drawn width/height in points, `b`/`c` the shear
         * (zero when upright) and `e`/`f` the offset of the image origin.
         */
        private fun imageMatrix(page: PDPage): FloatArray {
            val contents = page.contents ?: throw AssertionError("expected a content stream for the page")
            val text = contents.readBytes().toString(Charsets.US_ASCII)
            val matches =
                Regex("""([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+([-\d.+eE]+)\s+cm""")
                    .findAll(text)
                    .toList()
            assertThat(matches)
                .`as`("one image per page is drawn with exactly one cm placement, no extra transforms")
                .hasSize(1)
            return FloatArray(6) { index -> matches[0].groupValues[index + 1].toFloat() }
        }
    }

    private companion object {
        /** Fixed seed, so that every run generates the same cases (determinism). */
        const val SEED = 4711L

        /** Enough cases to cross content-overflow and content-fit on both axes, cheap enough to stay fast. */
        const val ITERATIONS = 150

        /** The scan resolution every case is built at; the physical size derives from it (SV-05). */
        const val DPI = 300

        /**
         * Pixel counts on a grid that keeps the JPEG synthesis cheap while covering both
         * outcomes: at 300 dpi the content spans 7.68..115.2 pt against boxes of 10..200 pt,
         * so both axes overflow and fit with equal likelihood.
         */
        val PIXELS: Arb<Int> = Arb.int(32..480)

        /** Target boxes in whole points, small enough that overflow cases actually occur. */
        val BOX_POINTS: Arb<Int> = Arb.int(10..200)

        /** Pinned like every PDF-writing test here: the document ID derives from the clock. */
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-27T10:15:30Z"), ZoneOffset.UTC)

        /** OpenPDF's number formatting writes at most two decimals, so 1/100 pt is the floor. */
        const val TOLERANCE_POINTS = 0.01f
    }
}
