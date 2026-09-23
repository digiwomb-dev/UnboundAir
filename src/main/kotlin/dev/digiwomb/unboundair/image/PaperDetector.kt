package dev.digiwomb.unboundair.image

/**
 * The bounding box of a detected paper region, in inclusive pixel
 * coordinates.
 *
 * [x0] and [y0] are the left and top edge, [x1] and [y1] the right and bottom
 * edge. All four values are inclusive, so the box spans `x1 - x0 + 1` by
 * `y1 - y0 + 1` pixels.
 */
data class PaperBox(
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
)

/**
 * Finds the paper in a scanned page and checks candidate boxes for
 * plausibility.
 *
 * The scanner background is almost pure black while the paper is bright, so a
 * fixed luma threshold separates the two reliably. Detection runs in two
 * phases:
 *
 * 1. **Coarse bounds.** A column (resp. row) belongs to the paper when at
 *    least [MIN_BRIGHT_FRACTION] of its pixels are bright, i.e. their luma is
 *    above [BRIGHT_THRESHOLD]. The first and last qualifying column/row give
 *    the initial box. If no column or no row qualifies, no paper is present
 *    and [detect] returns `null`.
 * 2. **Edge shrink.** Each edge is pulled inwards, one pixel per round, while
 *    more than [MAX_DARK_FRACTION] of that edge line is still dark. At most
 *    [MAX_SHRINK_STEPS] rounds run, and shrinking stops as soon as a full
 *    round moves no edge.
 *
 * Both functions are pure: they read only the [LumaImage] (or plain numbers)
 * and touch no file and no network.
 */
object PaperDetector {
    /** Luma value (0..255) above which a pixel counts as paper, not background. */
    const val BRIGHT_THRESHOLD = 60

    /** Minimum fraction of bright pixels for a row or column to belong to the paper. */
    const val MIN_BRIGHT_FRACTION = 0.05

    /** Maximum fraction of dark pixels an edge line may still contain after shrinking. */
    const val MAX_DARK_FRACTION = 0.02

    /** Safety limit for the inward edge pull, in pixels (60 px = 5 mm at 300 dpi). */
    const val MAX_SHRINK_STEPS = 60

    /**
     * Detects the paper in [image] and returns its inclusive bounding box.
     *
     * @param image the luma samples of one scanned page.
     * @return the inclusive [PaperBox] of the paper, or `null` when no paper
     * was found (no column or no row has enough bright pixels).
     */
    fun detect(image: LumaImage): PaperBox? {
        val width = image.width
        val height = image.height

        // Bright mask plus per-row / per-column bright counts, built in one pass.
        val bright = BooleanArray(width * height)
        val colBright = IntArray(width)
        val rowBright = IntArray(height)
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                val isBright = image.get(x, y) > BRIGHT_THRESHOLD
                bright[rowBase + x] = isBright
                if (isBright) {
                    colBright[x]++
                    rowBright[y]++
                }
            }
        }

        // Coarse bounds: the first and last column and row that belong to the paper.
        var x0 = -1
        var x1 = -1
        for (x in 0 until width) {
            if (colBright[x].toDouble() / height >= MIN_BRIGHT_FRACTION) {
                if (x0 < 0) {
                    x0 = x
                }
                x1 = x
            }
        }
        var y0 = -1
        var y1 = -1
        for (y in 0 until height) {
            if (rowBright[y].toDouble() / width >= MIN_BRIGHT_FRACTION) {
                if (y0 < 0) {
                    y0 = y
                }
                y1 = y
            }
        }
        if (x0 < 0 || y0 < 0) {
            return null
        }

        // Edge shrink: pull each edge in, one pixel per round, while its line is
        // mostly dark. Checked in fixed order (top, bottom, left, right) so the
        // column checks see the row edges already moved in this round.
        var boxX0 = x0
        var boxY0 = y0
        var boxX1 = x1
        var boxY1 = y1
        var steps = 0
        while (steps < MAX_SHRINK_STEPS) {
            var moved = false
            if (darkRowFraction(bright, width, height, boxY0, boxX0, boxX1) > MAX_DARK_FRACTION) {
                boxY0++
                moved = true
            }
            if (darkRowFraction(bright, width, height, boxY1, boxX0, boxX1) > MAX_DARK_FRACTION) {
                boxY1--
                moved = true
            }
            if (darkColumnFraction(bright, width, height, boxX0, boxY0, boxY1) > MAX_DARK_FRACTION) {
                boxX0++
                moved = true
            }
            if (darkColumnFraction(bright, width, height, boxX1, boxY0, boxY1) > MAX_DARK_FRACTION) {
                boxX1--
                moved = true
            }
            if (!moved) {
                break
            }
            steps++
        }
        return PaperBox(boxX0, boxY0, boxX1, boxY1)
    }

    /**
     * Checks whether [box] is a plausible paper box inside an image of
     * [imageWidth] by [imageHeight] pixels.
     *
     * A box is plausible when its area covers at least [minAreaFraction] of the
     * image and its longer-to-shorter side ratio is at most [maxAspectRatio].
     *
     * @return `true` when both the area and the aspect-ratio constraints hold.
     */
    fun isPlausible(
        box: PaperBox,
        imageWidth: Int,
        imageHeight: Int,
        minAreaFraction: Double,
        maxAspectRatio: Double,
    ): Boolean {
        val imageArea = imageWidth.toLong() * imageHeight.toLong()
        if (imageArea <= 0L) {
            return false
        }
        val boxWidth = box.x1 - box.x0 + 1
        val boxHeight = box.y1 - box.y0 + 1
        val boxArea = boxWidth.toLong() * boxHeight.toLong()
        val areaFraction = boxArea.toDouble() / imageArea.toDouble()
        val aspectRatio = maxOf(boxWidth, boxHeight).toDouble() / minOf(boxWidth, boxHeight).toDouble()
        return areaFraction >= minAreaFraction && aspectRatio <= maxAspectRatio
    }

    /**
     * Returns the fraction of dark pixels of the row at [y] across columns
     * [xFrom]..[xTo] (both inclusive).
     *
     * An out-of-bounds row or an empty column range yields `0.0`, so a box that
     * shrank past itself stops shrinking instead of failing.
     */
    private fun darkRowFraction(
        bright: BooleanArray,
        width: Int,
        height: Int,
        y: Int,
        xFrom: Int,
        xTo: Int,
    ): Double {
        if (y !in 0 until height) {
            return 0.0
        }
        val from = xFrom.coerceAtLeast(0)
        val to = xTo.coerceAtMost(width - 1)
        if (from > to) {
            return 0.0
        }
        val rowBase = y * width
        var dark = 0
        for (x in from..to) {
            if (!bright[rowBase + x]) {
                dark++
            }
        }
        return dark.toDouble() / (to - from + 1)
    }

    /**
     * Returns the fraction of dark pixels of the column at [x] across rows
     * [yFrom]..[yTo] (both inclusive).
     *
     * An out-of-bounds column or an empty row range yields `0.0`, so a box that
     * shrank past itself stops shrinking instead of failing.
     */
    private fun darkColumnFraction(
        bright: BooleanArray,
        width: Int,
        height: Int,
        x: Int,
        yFrom: Int,
        yTo: Int,
    ): Double {
        if (x !in 0 until width) {
            return 0.0
        }
        val from = yFrom.coerceAtLeast(0)
        val to = yTo.coerceAtMost(height - 1)
        if (from > to) {
            return 0.0
        }
        var dark = 0
        for (y in from..to) {
            if (!bright[y * width + x]) {
                dark++
            }
        }
        return dark.toDouble() / (to - from + 1)
    }
}
