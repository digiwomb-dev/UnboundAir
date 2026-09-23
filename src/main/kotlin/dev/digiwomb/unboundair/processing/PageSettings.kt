package dev.digiwomb.unboundair.processing

/**
 * The color mode of a scanned page (SV-03).
 *
 * [GRAY] is the default: the page is converted to grayscale with
 * `jpegtran -grayscale`, so the JPEG data is never recompressed.
 * [COLOR] keeps the page in the color in which the scanner produced it.
 */
enum class ColorMode { GRAY, COLOR }

/**
 * The central settings for page processing (SV-02, SV-03, SV-06).
 *
 * All defaults of the page processing chain live in this one class;
 * the steps and the commands of the chain read their values from a
 * [PageSettings] instance. It is also the docking point for the Spring
 * properties of a later milestone (KL-01): the properties are the
 * kebab-case twins of the properties below (`color-mode`, `keep-raw`).
 *
 * The individual settings:
 *
 * - [colorMode] selects the color mode (SV-03); [ColorMode.GRAY] is
 *   the default and maps to `jpegtran -grayscale`.
 * - [keepRaw] is a pure storage concern, not part of the processing
 *   chain (SV-06): when set, the raw JPEG of a page is additionally
 *   stored alongside the processed results.
 * - [minPaperAreaFraction] and [maxAspectRatio] are the plausibility
 *   thresholds of the crop step (SV-02): a crop is only plausible if
 *   the detected paper covers at least [minPaperAreaFraction] of the
 *   image area and its aspect ratio does not exceed [maxAspectRatio];
 *   otherwise the page is carried through uncropped and a warning is
 *   emitted.
 *
 * @property colorMode the color mode of the page; [ColorMode.GRAY] by
 *   default (`jpegtran -grayscale`).
 * @property keepRaw whether to additionally store the raw JPEG of a
 *   page (default: false).
 * @property minPaperAreaFraction the minimum fraction of the image
 *   area the detected paper must cover for the crop to be plausible
 *   (default: 0.10).
 * @property maxAspectRatio the maximum aspect ratio of the detected
 *   paper for the crop to be plausible (default: 6.0).
 */
data class PageSettings(
    val colorMode: ColorMode = ColorMode.GRAY,
    val keepRaw: Boolean = false,
    val minPaperAreaFraction: Double = 0.10,
    val maxAspectRatio: Double = 6.0,
)
