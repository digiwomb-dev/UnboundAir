package dev.digiwomb.unboundair.output

/**
 * The target page box for the finished PDF (SV-09).
 *
 * `Off` keeps the historic behaviour: the page box is the scan size,
 * pixels divided by dpi (SV-05). `Fixed` sets the page box to the given
 * size in PostScript points; the page content is centred into it unscaled
 * (never upscaled, never recompressed).
 *
 * The applied size is used exactly as written -- there is no orientation
 * automation: the same input string always yields the same box, and a
 * landscape scan on `a4` stays upright on the portrait A4 box with white
 * margins. Landscape targets exist explicitly (`a6-landscape`, or a free
 * measure such as `210x148mm`). This is deliberate, not a gap: the S400W
 * is a sheet-feed scanner whose image width ends at about 208.6 mm
 * (`docs/de/hardware.md`, scan properties), so the width cannot overflow
 * an A4 box; rotating would turn a fitting A6-landscape scan into a
 * needless landscape A4; and the reading direction is still open, so
 * rotation is out of v1.
 *
 * This type lives in `output` on purpose and depends on nothing -- no
 * config, no Spring, no processing -- so the PDF assembly and the
 * configuration binding can both use it without pulling in each other.
 */
sealed interface TargetPageSize {
    /**
     * Keep SV-05: the page box is the scan size (pixels divided by dpi).
     */
    data object Off : TargetPageSize

    /**
     * A fixed target box in PostScript points (1/72 inch).
     *
     * @property widthPt box width in points.
     * @property heightPt box height in points.
     */
    data class Fixed(
        val widthPt: Float,
        val heightPt: Float,
    ) : TargetPageSize

    companion object {
        /**
         * Parses [raw] into a [TargetPageSize], case-insensitively.
         *
         * Accepted forms are `off`, the names `a4`, `a5`, `a6`,
         * `a6-landscape`, `letter`, `legal`, and free measures such as
         * `210x297mm` in whole millimetres.
         *
         * @throws IllegalArgumentException for anything else; the message
         *   names [raw] and lists the accepted forms.
         */
        fun parse(raw: String): TargetPageSize {
            val normalised = raw.trim().lowercase()
            when (normalised) {
                "off" -> {
                    return Off
                }

                "a4" -> {
                    return Fixed(mmToPt(210), mmToPt(297))
                }

                "a5" -> {
                    return Fixed(mmToPt(148), mmToPt(210))
                }

                "a6" -> {
                    return Fixed(mmToPt(105), mmToPt(148))
                }

                "a6-landscape" -> {
                    val portrait = parse("a6") as Fixed
                    return Fixed(portrait.heightPt, portrait.widthPt)
                }

                // Inch-native on purpose: 8.5 x 11 in and 8.5 x 14 in, so the
                // box is exactly 612 x 792 and 612 x 1008 pt rather than going
                // through rounded millimetres.
                "letter" -> {
                    return Fixed(612f, 792f)
                }

                "legal" -> {
                    return Fixed(612f, 1008f)
                }
            }
            val freeForm = FREE_FORM.matchEntire(normalised)
            if (freeForm != null) {
                val widthMm = freeForm.groupValues[1].toInt()
                val heightMm = freeForm.groupValues[2].toInt()
                require(widthMm in 1..MAX_MM && heightMm in 1..MAX_MM) {
                    "Invalid page size '$raw': dimensions must be between 1 and $MAX_MM mm"
                }
                return Fixed(mmToPt(widthMm), mmToPt(heightMm))
            }
            throw IllegalArgumentException(
                "Invalid page size '$raw': " +
                    "expected one of off, a4, a5, a6, a6-landscape, letter, legal, " +
                    "or <width>x<height>mm in whole mm (e.g. 210x297mm)",
            )
        }

        /** One millimetre is 72 / 25.4 PostScript points. */
        private fun mmToPt(mm: Int): Float = mm.toFloat() * POINTS_PER_MM

        private const val POINTS_PER_MM = 72f / 25.4f

        /** Free-form guardrail: whole millimetres, nothing absurd. */
        private const val MAX_MM = 2000

        private val FREE_FORM = Regex("""(-?\d+)\s*x\s*(-?\d+)\s*mm""")
    }
}
