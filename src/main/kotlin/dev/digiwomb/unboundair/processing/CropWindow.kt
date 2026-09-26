package dev.digiwomb.unboundair.processing

/**
 * Result of iMCU origin rounding.
 *
 * @property ax0 the rounded x-coordinate of the crop window's top-left corner.
 * @property ay0 the rounded y-coordinate of the crop window's top-left corner.
 * @property cropWidth the width of the crop window (from ax0 to box.x1 inclusive).
 * @property cropHeight the height of the crop window (from ay0 to box.y1 inclusive).
 */
data class CropWindow(
    val ax0: Int,
    val ay0: Int,
    val cropWidth: Int,
    val cropHeight: Int,
)

/**
 * Rounds the origin inwards (up) to the iMCU grid.
 *
 * This function computes the rounded crop window coordinates from raw input
 * parameters, without any side effects or dependencies on external state.
 *
 * @param x0 the raw x-coordinate of the crop window's top-left corner.
 * @param y0 the raw y-coordinate of the crop window's top-left corner.
 * @param imcuWidth the iMCU width in pixels.
 * @param imcuHeight the iMCU height in pixels.
 * @param x1 the x-coordinate of the crop window's bottom-right corner (inclusive).
 * @param y1 the y-coordinate of the crop window's bottom-right corner (inclusive).
 * @return the rounded crop window coordinates and dimensions.
 */
fun roundOriginInwards(
    x0: Int,
    y0: Int,
    imcuWidth: Int,
    imcuHeight: Int,
    x1: Int,
    y1: Int,
): CropWindow {
    // Round the origin inwards (up) to the iMCU grid; a misaligned
    // origin would be shifted by `jpegtran` and bring background back.
    val ax0 = ((x0 + imcuWidth - 1) / imcuWidth) * imcuWidth
    val ay0 = ((y0 + imcuHeight - 1) / imcuHeight) * imcuHeight
    val cropWidth = x1 - ax0 + 1
    val cropHeight = y1 - ay0 + 1
    return CropWindow(ax0, ay0, cropWidth, cropHeight)
}
