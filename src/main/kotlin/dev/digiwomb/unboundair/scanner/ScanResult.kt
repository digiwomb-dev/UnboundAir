package dev.digiwomb.unboundair.scanner

import java.time.Duration

/**
 * The outcome of one scan: the image and the resolution it was taken at (SC-08).
 *
 * The resolution travels with the bytes on purpose. The firmware check of SC-07
 * may quietly downgrade a requested 600 dpi to 300 when the device is too old,
 * and until SC-08 the caller was told only through a warning in the log. That
 * was harmless while the number ended up in a file name — but SV-05 derives the
 * PDF page size from it (`pixels / dpi`), so a scan requested at 600 and taken
 * at 300 would produce a page of half the correct edge length. Nothing about the
 * document would look wrong; it would simply be the wrong size, and no one would
 * think to check the log.
 *
 * Carrying the effective value here makes that mistake impossible to make by
 * accident: there is no unqualified "the dpi" to reach for, only the one the
 * device actually used.
 *
 * The two durations are measured here rather than by the caller, because only
 * this class sees where one phase ends and the next begins (KL-02). From
 * outside, a scan is a single blocking call and the split would be guesswork.
 *
 * @property bytes the raw JPEG exactly as the scanner delivered it. Never
 *   re-encoded — the guardrail "never recompress" starts here.
 * @property dpi the resolution the scan was actually taken at, 300 or 600. This
 *   is the value SV-05 must use for the page size, not the requested one.
 * @property scanDuration how long the device took to pull the sheet through:
 *   from the `scan` command until `jpegsize` reports the result. This is the
 *   mechanical part, and the one that takes seconds.
 * @property transferDuration how long the image took to come over the wire:
 *   from the `jpegdata` command until the last byte arrived.
 */
data class ScanResult(
    val bytes: ByteArray,
    val dpi: Int,
    val scanDuration: Duration = Duration.ZERO,
    val transferDuration: Duration = Duration.ZERO,
) {
    /**
     * Value equality over the image content and the resolution.
     *
     * Two things are deliberate here:
     *
     * - [ByteArray] uses identity for `equals`, so the generated implementation
     *   of a data class holding one compares references and reports two scans of
     *   the same page as different. The content is compared instead.
     * - The **durations are excluded**. They are measurements, not identity: the
     *   same page scanned twice is the same result even though it will never
     *   take exactly the same number of milliseconds. Including them would make
     *   every comparison in a test fail for a reason that does not matter.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanResult) return false
        return dpi == other.dpi && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + dpi

    /**
     * Describes the result without dumping the image: a scan is roughly a
     * megabyte, and the generated `toString` of a data class would render every
     * byte into a failing test's output.
     */
    override fun toString(): String =
        "ScanResult(bytes=${bytes.size}, dpi=$dpi, scan=${scanDuration.toMillis()}ms, transfer=${transferDuration.toMillis()}ms)"
}
