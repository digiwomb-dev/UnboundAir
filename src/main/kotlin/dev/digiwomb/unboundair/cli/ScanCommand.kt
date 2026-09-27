package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.processing.CropStep
import dev.digiwomb.unboundair.processing.GrayscaleStep
import dev.digiwomb.unboundair.processing.PageProcessor
import dev.digiwomb.unboundair.processing.PageSettings
import dev.digiwomb.unboundair.processing.pageImage
import dev.digiwomb.unboundair.scanner.ScannerClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The `scan` command (BE-02): scans one page and writes the processed page
 * (crop plus grayscale according to [PageSettings.colorMode], SV-03) to a
 * file.
 *
 * The page is scanned through the [ScannerClient] and the raw JPEG is then
 * run through the processing chain: a [CropStep] and a [GrayscaleStep]
 * driven by [PageSettings]. Like [CropCommand], the chain runs in a
 * temporary working directory and its final result is moved to the target
 * path; the working directory is deleted afterwards.
 *
 * Processing warnings (for example: no paper found, page carried through
 * uncropped) are reported through [warn] instead of being printed by the
 * command itself, so the caller decides where they go (SV-02). Warnings of
 * the scan flow itself (for example: the 300 dpi fallback) travel through
 * the [client] and its own warn sink.
 *
 * With [PageSettings.keepRaw] set, the raw JPEG is additionally written to
 * a file derived from the target name: the same name with `_raw` inserted
 * before the extension (SV-06, [rawFileName]).
 *
 * @property client the scanner client to scan the page through.
 * @property settings the page settings of the processing chain; defaults to
 *   a [PageSettings] with all defaults (grayscale, no raw kept).
 * @property warn sink for processing warnings, e.g. a page carried through
 *   uncropped (SV-02); defaults to a no-op.
 */
class ScanCommand(
    private val client: ScannerClient,
    private val settings: PageSettings = PageSettings(),
    private val warn: (String) -> Unit = {},
) {
    /**
     * The outcome of a [run]: where the processed page was written, how
     * large it is, and where the raw JPEG was written.
     *
     * [path] is the target the processed page was written to (the requested
     * `--out` path or the default file name, [defaultFileName]). [size] is
     * the size of that processed page in bytes. [rawPath] is the file the
     * raw JPEG was written to, and `null` when [PageSettings.keepRaw] is
     * false; it is the target's file name with `_raw` inserted before the
     * extension ([rawFileName]) in the target's directory.
     *
     * [dpi] is the resolution the page was **actually** scanned at (SC-08),
     * which is not necessarily the one that was requested: an old firmware
     * forces a downgrade from 600 to 300 (SC-07). It is reported because the
     * caller needs it — SV-05 derives the PDF page size from exactly this
     * number.
     */
    data class Result(
        val path: Path,
        val size: Int,
        val rawPath: Path?,
        val dpi: Int,
    )

    /**
     * Scans one page at [dpi], processes it (crop, then color mode), and
     * writes the processed page to [out] or to the default file name,
     * [defaultFileName]. With [PageSettings.keepRaw] set, the raw JPEG is
     * additionally written to [rawFileName] of the target name.
     *
     * The raw bytes are written to a temporary working directory, the
     * processing chain ([CropStep], [GrayscaleStep]) runs there, and the
     * final result is moved to the target path. When the chain changed
     * nothing (color mode, and a crop that covers the whole frame), the
     * result *is* the raw file and gets copied instead, so the raw file
     * stays available for the keep-raw write.
     *
     * @param dpi the resolution to scan at (300 or 600); validated by the
     *   [ScannerClient].
     * @param out the target path for the processed page, or `null` for the
     *   default file name in the current directory.
     * @return where the processed page was written, its size, and the raw
     *   file (or `null`).
     * @throws dev.digiwomb.unboundair.scanner.ScannerException the scanner
     *   is unreachable, its firmware is not supported, or no scan-ready
     *   status was received.
     * @throws IllegalArgumentException the raw JPEG cannot be read or the
     *   external `jpegtran` fails.
     */
    fun run(
        dpi: Int,
        out: Path?,
    ): Result {
        val scan = client.scan(dpi)
        val bytes = scan.bytes
        // The effective resolution, not the requested one (SC-08): if the
        // firmware forced a downgrade to 300 dpi, a file named "600dpi" would
        // be a lie, and the number is what SV-05 later derives the page size
        // from.
        val target = out ?: Path.of(defaultFileName(scan.dpi))
        target.parent?.let { Files.createDirectories(it) }

        val workDir = Files.createTempDirectory("unboundair-scan")
        try {
            val rawFile = workDir.resolve("raw.jpg")
            Files.write(rawFile, bytes)

            val processor = PageProcessor(listOf(CropStep(settings), GrayscaleStep(settings)))
            val image = pageImage(rawFile)
            val result = processor.process(image, workDir, warn)

            if (result.file == rawFile) {
                // The chain changed nothing: the result is the raw file
                // itself. Copy it, never move, so the raw file stays in
                // place until the working directory is deleted.
                Files.copy(rawFile, target, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(result.file, target, StandardCopyOption.REPLACE_EXISTING)
            }

            // The raw file is the exact scanner payload, byte-equal to it
            // (SV-06), so it is written from the in-memory bytes.
            val rawPath =
                if (settings.keepRaw) {
                    val rawTarget = target.resolveSibling(rawFileName(target.fileName.toString()))
                    Files.write(rawTarget, bytes)
                    rawTarget
                } else {
                    null
                }

            return Result(target, Files.size(target).toInt(), rawPath, scan.dpi)
        } finally {
            runCatching { deleteRecursively(workDir) }
        }
    }

    private fun deleteRecursively(dir: Path) {
        Files.list(dir).use {
            it.forEach { entry ->
                if (Files.isDirectory(entry)) {
                    deleteRecursively(entry)
                } else {
                    Files.deleteIfExists(entry)
                }
            }
        }
        Files.deleteIfExists(dir)
    }

    companion object {
        /** Timestamp format of the default file name, e.g. `20260922-143500`. */
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * The default file name for a scanned page: `iscan_<timestamp>_<dpi>dpi.jpg`
         * with a timestamp like `20260922-143500`, as in the reference
         * implementation (BE-02).
         *
         * @param dpi the resolution the page was scanned at (300 or 600).
         * @param now the point in time to stamp; injectable for tests,
         *   defaults to the current time.
         */
        fun defaultFileName(
            dpi: Int,
            now: LocalDateTime = LocalDateTime.now(),
        ): String = "iscan_${TIMESTAMP.format(now)}_${dpi}dpi.jpg"

        /**
         * The file name of the raw JPEG that belongs to a processed page
         * (SV-06): the same name with `_raw` inserted before the last
         * extension, e.g. `iscan_20260922-143500_300dpi.jpg` maps to
         * `iscan_20260922-143500_300dpi_raw.jpg`.
         *
         * A name without an extension (or with a trailing or leading dot
         * only) gets `_raw` appended, so the result never ends in a bare
         * dot: `page` maps to `page_raw`.
         *
         * @param fileName the file name of the processed page.
         * @return the file name to write the raw JPEG to.
         */
        fun rawFileName(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            // A dot is only an extension separator when at least one character
            // follows it: a trailing dot (dot == length - 1) is not a real
            // extension, so `_raw` is appended and the result never ends in a
            // bare dot.
            return if (dot in 1 until fileName.length - 1) {
                fileName.substring(0, dot) + "_raw" + fileName.substring(dot)
            } else {
                fileName + "_raw"
            }
        }
    }
}
