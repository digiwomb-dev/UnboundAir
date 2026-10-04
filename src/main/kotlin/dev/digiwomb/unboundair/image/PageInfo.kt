package dev.digiwomb.unboundair.image

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The structure of one scanned page, whatever its file format (SV-08).
 *
 * The processing chain carries JPEGs and 1-bit bitmaps from SV-08 onwards,
 * and the two readers of page structure need different amounts of it: steps
 * that only place the page (layout, logging) read [width] and [height],
 * while the lossless crop needs the JPEG iMCU grid and asks for a [JpegInfo]
 * explicitly.
 *
 * @property width the image width in pixels.
 * @property height the image height in pixels.
 */
interface PageInfo {
    val width: Int
    val height: Int
}

/**
 * The structure of a 1-bit page stored as a binary PBM (`P4`) file (SV-08).
 *
 * Dimensions only: the packed bitmap carries no JPEG-style structure a later
 * step could need, so there is nothing else to read.
 *
 * @property width the image width in pixels.
 * @property height the image height in pixels.
 */
data class BitmapInfo(
    override val width: Int,
    override val height: Int,
) : PageInfo {
    init {
        require(width > 0 && height > 0) { "width and height must be positive, got ${width}x$height" }
    }

    companion object {
        // The magic of a binary (raw-bits) PBM file.
        private const val MAGIC = "P4"

        /**
         * Reads the dimensions of the PBM file at [path] (SV-08).
         *
         * Only the `P4` header is parsed: the magic `P4`, the
         * whitespace-separated width and height, and the single whitespace
         * byte between the height token and the packed bitmap. A `#` comment
         * runs to the end of its line and is skipped anywhere in the header.
         * The packed bitmap itself is neither required nor read, so a header
         * ending right after the single whitespace byte already parses.
         *
         * @param path the PBM file to read.
         * @return the [BitmapInfo] of the image stored in [path].
         * @throws IllegalArgumentException the file is missing, has the wrong
         *   magic, or carries missing, non-numeric or non-positive dimensions;
         *   the message names [path].
         */
        fun read(path: Path): BitmapInfo {
            val parser = HeaderParser(readBytes(path), path)
            parser.expectMagic()
            val width = parser.readDimension("width")
            val height = parser.readDimension("height")
            parser.expectBitmapStart()
            return BitmapInfo(width, height)
        }

        /**
         * Reads all bytes of [path].
         *
         * @throws IllegalArgumentException the file is missing or unreadable;
         *   the message names [path].
         */
        private fun readBytes(path: Path): ByteArray =
            try {
                Files.readAllBytes(path)
            } catch (e: IOException) {
                throw IllegalArgumentException("Cannot read $path as a PBM: ${e.message}", e)
            }

        /**
         * Parses the `P4` header of one PBM file, byte by byte.
         *
         * @property bytes the whole file content.
         * @property path the file the content was read from, named in errors.
         * @property pos the current read position in [bytes].
         */
        private class HeaderParser(
            private val bytes: ByteArray,
            private val path: Path,
            private var pos: Int = 0,
        ) {
            /**
             * Consumes the `P4` magic and checks its trailing separator.
             *
             * @throws IllegalArgumentException the first two bytes are not
             *   `P4`, or no whitespace or comment separates the magic from
             *   the dimensions; the message names [path].
             */
            fun expectMagic() {
                val magic = MAGIC.toByteArray(Charsets.US_ASCII)
                val found = bytes.take(magic.size).toByteArray().toString(Charsets.US_ASCII)
                if (!bytes.copyOf(magic.size).contentEquals(magic)) {
                    throw IllegalArgumentException("Cannot read $path as a PBM: wrong magic (found '$found')")
                }
                pos = magic.size
                if (pos >= bytes.size || (!isWhitespace(bytes[pos]) && bytes[pos] != '#'.code.toByte())) {
                    throw IllegalArgumentException(
                        "Cannot read $path as a PBM: no dimensions after the magic (found '$found')",
                    )
                }
            }

            /**
             * Reads the next header dimension called [name].
             *
             * @throws IllegalArgumentException the header ends here, or the
             *   token is not a positive integer; the message names [path].
             */
            fun readDimension(name: String): Int {
                val token =
                    nextToken()
                        ?: throw IllegalArgumentException("Cannot read $path as a PBM: the header has no $name")
                val value =
                    token.toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "Cannot read $path as a PBM: the $name is not a number (found '$token')",
                        )
                if (value <= 0) {
                    throw IllegalArgumentException(
                        "Cannot read $path as a PBM: the $name must be positive (found '$token')",
                    )
                }
                return value
            }

            /**
             * Consumes the single whitespace byte between the height token
             * and the packed bitmap.
             *
             * The bitmap itself is neither required nor read: whatever
             * follows the single whitespace byte is payload and is left
             * alone.
             *
             * @throws IllegalArgumentException the header ends after the
             *   dimensions, or the next byte is not whitespace; the message
             *   names [path].
             */
            fun expectBitmapStart() {
                if (pos >= bytes.size) {
                    throw IllegalArgumentException(
                        "Cannot read $path as a PBM: the header ends after the dimensions, " +
                            "expected one whitespace byte before the packed bitmap",
                    )
                }
                if (!isWhitespace(bytes[pos])) {
                    throw IllegalArgumentException(
                        "Cannot read $path as a PBM: expected one whitespace byte after the dimensions " +
                            "(found '${bytes[pos].toInt().toChar()}')",
                    )
                }
                pos++
            }

            /**
             * Reads the next whitespace-separated header token, skipping
             * comments.
             *
             * @return the token, or null when the header ends first.
             */
            private fun nextToken(): String? {
                skipWhitespaceAndComments()
                if (pos >= bytes.size) {
                    return null
                }
                val start = pos
                while (pos < bytes.size && !isWhitespace(bytes[pos]) && bytes[pos] != '#'.code.toByte()) {
                    pos++
                }
                return bytes.copyOfRange(start, pos).toString(Charsets.US_ASCII)
            }

            /**
             * Skips whitespace and `#` comments, each running to the end of
             * its line.
             */
            private fun skipWhitespaceAndComments() {
                while (pos < bytes.size) {
                    val byte = bytes[pos]
                    if (isWhitespace(byte)) {
                        pos++
                    } else if (byte == '#'.code.toByte()) {
                        while (pos < bytes.size && bytes[pos] != '\n'.code.toByte()) {
                            pos++
                        }
                    } else {
                        return
                    }
                }
            }

            /**
             * Returns whether [byte] is PBM header whitespace (space, tab,
             * carriage return, line feed, vertical tab or form feed).
             */
            private fun isWhitespace(byte: Byte): Boolean =
                byte == ' '.code.toByte() ||
                    byte == '\t'.code.toByte() ||
                    byte == '\n'.code.toByte() ||
                    byte == '\r'.code.toByte() ||
                    byte == 0x0B.toByte() ||
                    byte == 0x0C.toByte()
        }
    }
}
