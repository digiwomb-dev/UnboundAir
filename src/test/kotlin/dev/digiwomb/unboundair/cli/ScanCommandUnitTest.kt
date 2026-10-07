package dev.digiwomb.unboundair.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Unit tests for the pure companion functions of [ScanCommand] (the "unit"
 * layer of docs/internal/teststrategie.md): no Spring context, no scanner, no
 * `jpegtran`, no file I/O — offline per DC-03.
 *
 * [CommandTest] covers the same companion from the integration layer, where
 * the fake scanner and the real processing chain are in play. This file
 * isolates the pure string logic, so the layer labeling stays honest:
 *
 * - [rawFileName] (SV-06): the keep-raw file name derived from the target
 *   page name;
 * - [defaultFileName] (BE-02): the reference `iscan_<timestamp>_<dpi>dpi.jpg`
 *   name, stamped with an injected [LocalDateTime] so the test is
 *   deterministic.
 */
class ScanCommandUnitTest {
    /**
     * SV-06 -- [ScanCommand.rawFileName] derives the keep-raw file name from
     * the target page name: the same name with `_raw` inserted before the
     * last extension, or appended when the name has no usable extension.
     */
    @Nested
    inner class RawFileName {
        /**
         * SV-06 -- the common case: a single extension. `_raw` is inserted
         * before the last dot, so the extension is preserved.
         */
        @Test
        fun `SV-06 a name with one extension gets _raw inserted before it`() {
            assertThat(ScanCommand.rawFileName("page.jpg"))
                .`as`("the extension must be preserved behind the inserted _raw")
                .isEqualTo("page_raw.jpg")
        }

        /**
         * SV-06 -- multiple dots: only the *last* dot counts as the extension
         * separator, so the dot inside the stem stays untouched.
         */
        @Test
        fun `SV-06 a name with multiple dots gets _raw inserted before the last dot only`() {
            assertThat(ScanCommand.rawFileName("a.b.jpg"))
                .`as`("only the last extension is the insertion point, the stem dot stays")
                .isEqualTo("a.b_raw.jpg")
        }

        /**
         * SV-06 -- the documented example of the KDoc: the default scan file
         * name maps to its keep-raw sibling.
         */
        @Test
        fun `SV-06 the default scan file name maps to its raw sibling`() {
            assertThat(ScanCommand.rawFileName("iscan_20260922-143500_300dpi.jpg"))
                .`as`("the KDoc example must hold")
                .isEqualTo("iscan_20260922-143500_300dpi_raw.jpg")
        }

        /**
         * SV-06 -- a name without any dot takes the `else` branch: `_raw` is
         * appended, the result never gains a dot.
         */
        @Test
        fun `SV-06 a name without an extension gets _raw appended`() {
            assertThat(ScanCommand.rawFileName("page"))
                .`as`("without a dot there is no extension, so _raw is appended")
                .isEqualTo("page_raw")
        }

        /**
         * SV-06 -- a leading dot (hidden file) is the lower boundary of the
         * guard: a dot at index 0 is not an extension, so `_raw` is
         * appended after the name, keeping the leading dot in place.
         */
        @Test
        fun `SV-06 a name with a leading dot only gets _raw appended`() {
            assertThat(ScanCommand.rawFileName(".hidden"))
                .`as`("a dot at index 0 is not an extension, so _raw is appended")
                .isEqualTo(".hidden_raw")
        }

        /**
         * SV-06 -- a trailing dot is the upper boundary of the guard: a dot
         * at the last index is not a real extension separator, so `_raw` is
         * appended after the name and the result never ends in a bare dot,
         * matching the KDoc contract.
         */
        @Test
        fun `SV-06 a name with a trailing dot only gets _raw appended after the dot`() {
            assertThat(ScanCommand.rawFileName("page."))
                .`as`(
                    "a dot at the last index is not an extension, so _raw is appended " +
                        "after it and the result never ends in a bare dot",
                ).isEqualTo("page._raw")
        }
    }

    /**
     * BE-02 -- [ScanCommand.defaultFileName] builds the reference file name
     * `iscan_<timestamp>_<dpi>dpi.jpg`; the injectable clock makes the test
     * deterministic.
     */
    @Test
    fun `BE-02 the default file name stamps the time and dpi in the reference format`() {
        val now = LocalDateTime.of(2026, 9, 22, 14, 35, 0)

        assertThat(ScanCommand.defaultFileName(300, now))
            .`as`("the reference format is iscan_<timestamp>_<dpi>dpi.jpg")
            .isEqualTo("iscan_20260922-143500_300dpi.jpg")
        assertThat(ScanCommand.defaultFileName(600, now))
            .`as`("a 600 dpi scan stamps 600dpi, the same timestamp")
            .isEqualTo("iscan_20260922-143500_600dpi.jpg")
    }
}
