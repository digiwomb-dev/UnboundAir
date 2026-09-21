package dev.digiwomb.unboundair.scanner

/*
 * Low-level protocol constants and a prefix-comparison helper for the Mustek
 * iScan Air (S400W) scanner.
 *
 * The device speaks a tiny TCP protocol: every command is exactly four bytes
 * (see ScannerCommand) and most answers are short ASCII words. Two quirks of
 * the real hardware drive the design here:
 *
 * 1. **Fill bytes.** Every status/ack answer is 11 bytes: the ASCII word,
 *    `\u0000` padding, and a trailing `H` (e.g. `nopaper\u0000\u0000\u0000H`,
 *    `scanready\u0000H`, `dpistd\u0000\u0000\u0000\u0000H`, `scango\u0000\u0000\u0000\u0000H`).
 *
 * 2. **No uniform length.** The `version` answer does **not** follow that
 *    scheme (e.g. `NB0a.032`, possibly followed by a single `\u0000`), and the
 *    `jpegsize` answer is 12 bytes (the word plus a little-endian `uint32`).
 *
 * Because of (2) a response must never be matched by assuming a fixed length or
 * a particular padding. We therefore always compare by **prefix**: check that
 * the leading bytes equal the expected word. This is startsWithPrefix (SC-03).
 */

/**
 * Outgoing command payloads. Each command is exactly four bytes, listed in
 * send order (the device app interprets them as a little-endian `Int32`).
 *
 * Byte literals greater than `0x7F` are written with `Int.toByte()` so the
 * high-bit values fit into a signed [Byte]; values up to `0x7F` are left as
 * plain literals, which the compiler narrows to [Byte] because they are in
 * range. Every literal was checked so this compiles.
 */
object ScannerCommand {
    val STATUS: ByteArray = byteArrayOf(0x00, 0x60, 0x00, 0x50)
    val VERSION: ByteArray = byteArrayOf(0x30, 0x30, 0x20, 0x20)
    val DPI300: ByteArray = byteArrayOf(0x40, 0x30, 0x20, 0x10)
    val DPI600: ByteArray = byteArrayOf(0x80.toByte(), 0x70, 0x60, 0x50)
    val SCAN: ByteArray = byteArrayOf(0x00, 0x20, 0x00, 0x10)
    val JPEGSIZE: ByteArray = byteArrayOf(0x00, 0xD0.toByte(), 0x00, 0xC0.toByte())
    val JPEGDATA: ByteArray = byteArrayOf(0x00, 0xF0.toByte(), 0x00, 0xE0.toByte())
}

/**
 * Expected response prefixes as ASCII bytes.
 *
 * These are the leading bytes of each answer. [startsWithPrefix] compares only
 * this prefix, so any trailing fill bytes, padding, or (for `version`) a
 * different length are ignored.
 */
object ScannerResponse {
    val SCANREADY: ByteArray = "scanready".toByteArray(Charsets.US_ASCII)
    val NOPAPER: ByteArray = "nopaper".toByteArray(Charsets.US_ASCII)
    val DEVBUSY: ByteArray = "devbusy".toByteArray(Charsets.US_ASCII)
    val BATTLOW: ByteArray = "battlow".toByteArray(Charsets.US_ASCII)
    val DPISTD: ByteArray = "dpistd".toByteArray(Charsets.US_ASCII)
    val DPIFINE: ByteArray = "dpifine".toByteArray(Charsets.US_ASCII)
    val SCANGO: ByteArray = "scango".toByteArray(Charsets.US_ASCII)
    val JPEGSIZE: ByteArray = "jpegsize".toByteArray(Charsets.US_ASCII)
}

/**
 * Returns `true` iff [response] starts with [prefix], comparing raw bytes.
 *
 * **SC-03 - no length or padding assumption.** A response matches when it is at
 * least as long as the prefix and its leading `prefix.size` bytes are equal to
 * the prefix. The remaining bytes (fill bytes, `\u0000` padding, a trailing
 * `H`, or simply a shorter `version` answer) are deliberately ignored. Raw
 * bytes are compared; the response is never decoded to a [String], because a
 * fill byte in the padding would otherwise interfere with a string comparison.
 */
fun startsWithPrefix(
    response: ByteArray,
    prefix: ByteArray,
): Boolean {
    if (response.size < prefix.size) return false
    for (i in prefix.indices) {
        if (response[i] != prefix[i]) return false
    }
    return true
}

/**
 * Overload accepting the expected word as an ASCII [String] for convenience.
 *
 * The string is converted to ASCII bytes and compared by prefix exactly like
 * [startsWithPrefix]. SC-03: no length or padding is assumed.
 */
fun startsWithPrefix(
    response: ByteArray,
    asciiPrefix: String,
): Boolean = startsWithPrefix(response, asciiPrefix.toByteArray(Charsets.US_ASCII))

/**
 * Parses a `jpegsize` answer into the JPEG payload size in bytes.
 *
 * The `jpegsize` answer is 12 bytes: the ASCII word `jpegsize` (8 bytes)
 * followed by the size as a little-endian `uint32` in bytes `[8..11]`.
 *
 * @throws IllegalArgumentException if the answer is shorter than 12 bytes or
 *   does not start with the `jpegsize` prefix (defensive checks; a well-formed
 *   real-device answer always passes both).
 */
fun parseJpegSize(response: ByteArray): Int {
    if (response.size < 12) {
        throw IllegalArgumentException(
            "jpegsize answer is too short: ${response.size} byte(s), need at least 12",
        )
    }
    if (!startsWithPrefix(response, ScannerResponse.JPEGSIZE)) {
        throw IllegalArgumentException("not a jpegsize answer: missing the 'jpegsize' prefix")
    }
    // Little-endian uint32 in bytes [8..11].
    return (response[8].toInt() and 0xFF) or
        ((response[9].toInt() and 0xFF) shl 8) or
        ((response[10].toInt() and 0xFF) shl 16) or
        ((response[11].toInt() and 0xFF) shl 24)
}
