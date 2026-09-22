package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.scanner.ScannerClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The `scan` command: scans one page and writes the raw JPEG to a file.
 */
class ScanCommand(
    private val client: ScannerClient,
) {
    /**
     * Outcome of a scan: where the raw JPEG was written and how big it is.
     */
    data class Result(
        val path: Path,
        val size: Int,
    )

    /**
     * Scans one page and writes the raw JPEG to a file.
     *
     * Milestone 1 only writes the raw file; cropping and PDF assembly come
     * in milestone 2. If [out] is null, a default file name derived from
     * the dpi is used. Failures are not handled here; they propagate to
     * the caller.
     *
     * @param dpi requested resolution: 300 or 600, validated by [ScannerClient.scan].
     * @param out target file, or null to use the default file name.
     * @return the target path and the number of raw bytes written.
     */
    fun run(
        dpi: Int,
        out: Path?,
    ): Result {
        val bytes = client.scan(dpi)
        val target = out ?: Path.of(defaultFileName(dpi))
        Files.write(target, bytes)
        return Result(target, bytes.size)
    }

    companion object {
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * Default output file name for a scan, e.g. `iscan_20260922-143500_300dpi.jpg`.
         *
         * @param dpi the resolution the page was scanned at, 300 or 600.
         * @param now the timestamp to use; defaults to the current time, injectable for tests.
         * @return the file name, e.g. `iscan_20260922-143500_300dpi.jpg`.
         */
        fun defaultFileName(
            dpi: Int,
            now: LocalDateTime = LocalDateTime.now(),
        ): String = "iscan_${TIMESTAMP.format(now)}_${dpi}dpi.jpg"
    }
}
