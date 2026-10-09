package dev.digiwomb.unboundair.output

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TargetPageSize.parse] (SV-09).
 *
 * Offline (DC-03): plain JVM, no Spring, no device, no network.
 */
class TargetPageSizeTest {
    @Nested
    inner class AcceptedForms {
        @Test
        fun `SV-09 a4 pins to 595-28x841-89 points within tolerance`() {
            val parsed = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            assertThat(parsed.widthPt).`as`("a4 width must be 210 mm in points").isCloseTo(595.28f, within(0.01f))
            assertThat(parsed.heightPt).`as`("a4 height must be 297 mm in points").isCloseTo(841.89f, within(0.01f))
        }

        @Test
        fun `SV-09 letter is exactly 612x792 points`() {
            val parsed = TargetPageSize.parse("letter") as TargetPageSize.Fixed

            assertThat(parsed.widthPt).`as`("letter width is inch-native 8.5in").isEqualTo(612f)
            assertThat(parsed.heightPt).`as`("letter height is inch-native 11in").isEqualTo(792f)
        }

        @Test
        fun `SV-09 a5 pins to 148x210mm in points`() {
            val parsed = TargetPageSize.parse("a5") as TargetPageSize.Fixed

            assertThat(parsed.widthPt).`as`("a5 width must be 148 mm in points").isCloseTo(419.53f, within(0.01f))
            assertThat(parsed.heightPt).`as`("a5 height must be 210 mm in points").isCloseTo(595.28f, within(0.01f))
        }

        @Test
        fun `SV-09 a6 pins to 105x148mm in points`() {
            val parsed = TargetPageSize.parse("a6") as TargetPageSize.Fixed

            assertThat(parsed.widthPt).`as`("a6 width must be 105 mm in points").isCloseTo(297.64f, within(0.01f))
            assertThat(parsed.heightPt).`as`("a6 height must be 148 mm in points").isCloseTo(419.53f, within(0.01f))
        }

        @Test
        fun `SV-09 legal is exactly 612x1008 points`() {
            val parsed = TargetPageSize.parse("legal") as TargetPageSize.Fixed

            assertThat(parsed.widthPt).`as`("legal width is inch-native 8.5in").isEqualTo(612f)
            assertThat(parsed.heightPt).`as`("legal height is inch-native 14in").isEqualTo(1008f)
        }

        @Test
        fun `SV-09 free-form 210x297mm equals a4`() {
            val free = TargetPageSize.parse("210x297mm") as TargetPageSize.Fixed
            val a4 = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            assertThat(free).`as`("210x297mm must land on the same box as a4").isEqualTo(a4)
            assertThat(free.widthPt).`as`("free-form width").isCloseTo(595.28f, within(0.01f))
            assertThat(free.heightPt).`as`("free-form height").isCloseTo(841.89f, within(0.01f))
        }

        @Test
        fun `SV-09 off keeps the historic scan-size behaviour`() {
            assertThat(TargetPageSize.parse("off"))
                .`as`("off must parse to Off")
                .isEqualTo(TargetPageSize.Off)
        }

        @Test
        fun `SV-09 mixed case parses`() {
            assertThat(TargetPageSize.parse("A4"))
                .`as`("upper-case A4 must parse like a4")
                .isEqualTo(TargetPageSize.parse("a4"))
            assertThat(TargetPageSize.parse("Letter"))
                .`as`("capitalised Letter must parse like letter")
                .isEqualTo(TargetPageSize.parse("letter"))
            assertThat(TargetPageSize.parse("OFF"))
                .`as`("upper-case OFF must parse to Off")
                .isEqualTo(TargetPageSize.Off)
            assertThat(TargetPageSize.parse("A6-Landscape"))
                .`as`("mixed-case A6-Landscape must parse like a6-landscape")
                .isEqualTo(TargetPageSize.parse("a6-landscape"))
        }

        @Test
        fun `SV-09 a6-landscape is exactly a6 swapped to 419-53x297-64`() {
            val portrait = TargetPageSize.parse("a6") as TargetPageSize.Fixed
            val landscape = TargetPageSize.parse("a6-landscape") as TargetPageSize.Fixed

            assertThat(landscape.widthPt).`as`("landscape width must be the portrait height").isEqualTo(portrait.heightPt)
            assertThat(landscape.heightPt).`as`("landscape height must be the portrait width").isEqualTo(portrait.widthPt)
            assertThat(landscape.widthPt).`as`("a6-landscape width").isCloseTo(419.53f, within(0.01f))
            assertThat(landscape.heightPt).`as`("a6-landscape height").isCloseTo(297.64f, within(0.01f))
        }
    }

    @Nested
    inner class Orientation {
        @Test
        fun `SV-09 the same input twice yields the same box`() {
            assertThat(TargetPageSize.parse("a4"))
                .`as`("parsing is deterministic: same input, same box")
                .isEqualTo(TargetPageSize.parse("a4"))
        }

        @Test
        fun `SV-09 a4 is always portrait 595-28x841-89 never rotated`() {
            val first = TargetPageSize.parse("a4") as TargetPageSize.Fixed
            val second = TargetPageSize.parse("a4") as TargetPageSize.Fixed

            assertThat(first).`as`("a4 must be stable across parses").isEqualTo(second)
            assertThat(first.widthPt).`as`("a4 width stays 210 mm in points").isCloseTo(595.28f, within(0.01f))
            assertThat(first.heightPt).`as`("a4 height stays 297 mm in points").isCloseTo(841.89f, within(0.01f))
            assertThat(first.widthPt < first.heightPt)
                .`as`("a4 must stay portrait, never rotated to landscape")
                .isTrue()
        }

        @Test
        fun `SV-09 parse takes a single String and has no page aspect overload`() {
            val parses = TargetPageSize.Companion::class.java.methods.filter { it.name == "parse" }

            assertThat(parses)
                .`as`("there must be exactly one parse overload, so no caller can pass a page aspect or dimensions")
                .hasSize(1)
            assertThat(parses.single().parameterTypes.toList())
                .`as`("the single parse signature takes only the raw String")
                .containsExactly(String::class.java)
        }
    }

    @Nested
    inner class Rejected {
        @Test
        fun `SV-09 empty input throws naming the value and the accepted forms`() {
            assertThatThrownBy { TargetPageSize.parse("") }
                .`as`("empty input must be rejected")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("''")
                .hasMessageContaining("a4")
                .hasMessageContaining("210x297mm")
        }

        @Test
        fun `SV-09 unknown name throws naming the value and the accepted forms`() {
            assertThatThrownBy { TargetPageSize.parse("din-a4") }
                .`as`("din-a4 is not an accepted name")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("din-a4")
                .hasMessageContaining("a4")
                .hasMessageContaining("210x297mm")
        }

        @Test
        fun `SV-09 zero dimensions throw naming the value`() {
            assertThatThrownBy { TargetPageSize.parse("0x297mm") }
                .`as`("zero width must be rejected")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("0x297mm")
                .hasMessageContaining("mm")
        }

        @Test
        fun `SV-09 negative dimensions throw naming the value`() {
            assertThatThrownBy { TargetPageSize.parse("-210x297mm") }
                .`as`("negative width must be rejected")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("-210x297mm")
                .hasMessageContaining("mm")
        }

        @Test
        fun `SV-09 missing unit throws naming the value and the accepted forms`() {
            assertThatThrownBy { TargetPageSize.parse("210x297") }
                .`as`("a measure without mm must be rejected")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("210x297")
                .hasMessageContaining("a4")
                .hasMessageContaining("210x297mm")
        }

        @Test
        fun `SV-09 non-integer millimetres throw naming the value and the accepted forms`() {
            assertThatThrownBy { TargetPageSize.parse("210.5x297mm") }
                .`as`("fractional millimetres must be rejected: whole mm only")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("210.5x297mm")
                .hasMessageContaining("a4")
                .hasMessageContaining("210x297mm")
        }
    }
}
