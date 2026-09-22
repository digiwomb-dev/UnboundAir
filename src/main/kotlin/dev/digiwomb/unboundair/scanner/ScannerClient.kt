package dev.digiwomb.unboundair.scanner

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * TCP client for the Mustek iScan Air (S400W) scan protocol.
 *
 * One operation, one connection (SC-02): [queryStatus] opens a single
 * connection for one `status` command, and [scan] opens one for the whole
 * flow `status` → `version` → `dpi300`/`dpi600` → `scan` → `jpegsize` →
 * `jpegdata`. Both close the connection again on the way out, even on
 * error, and the client keeps no state, so one instance serves both
 * operations.
 *
 * Every answer is compared by prefix only (SC-03), because the device
 * pads its answers with fill bytes and does not share a uniform answer
 * length. The 12-byte `jpegsize` answer is read until 12 bytes arrived,
 * because the device may split it across TCP segments (SC-04). All
 * pauses and timeouts are constants in the companion object, mirroring
 * the reference client in one central place (SC-02), and every failure
 * is mapped to one of the dedicated scanner exceptions (SC-05).
 *
 * [host] and [port] default to the shipped device but are configurable,
 * so tests can point the client at a fake scanner on the loopback
 * interface (SC-06).
 *
 * @property host address of the scanner, defaults to [DEFAULT_HOST].
 * @property port TCP port of the scanner, defaults to [DEFAULT_PORT].
 * @property warn sink for warnings, e.g. the 300 dpi fallback of
 *   [resolveDpi] (SC-07); defaults to a no-op.
 */
class ScannerClient(
    private val host: String = ScannerClient.DEFAULT_HOST,
    private val port: Int = ScannerClient.DEFAULT_PORT,
    private val warn: (String) -> Unit = {},
) {
    /**
     * Queries the scanner's status in one connection.
     *
     * Sends `status`, reads the answer, and returns one of the four known
     * words: `scanready`, `nopaper`, `devbusy`, or `battlow`.
     *
     * @throws ScannerOfflineException the scanner cannot be reached or the connection drops.
     * @throws ScannerTimeoutException the status answer did not arrive within the timeout.
     * @throws ScannerProtocolException the answer is none of the four known words.
     */
    fun queryStatus(): String {
        val socket = connect()
        try {
            send(socket, ScannerCommand.STATUS, "status", STATUS_QUERY_PAUSE_AFTER_MILLIS)
            val answer = readAnswer(socket, "status", NORMAL_TIMEOUT_MILLIS)
            val status = statusWord(answer)
            if (status == null) {
                throw ScannerProtocolException("Unrecognized status answer from $host:$port: ${describe(answer)}")
            }
            return status
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Scans one page in a single connection (SC-02) and returns the raw
     * JPEG bytes exactly as the device sent them, unmodified (SC-01).
     *
     * Flow: `status` (any word other than `scanready` aborts with the
     * matching exception) → `version` → `dpi300`/`dpi600` (chosen by
     * [resolveDpi], SC-07) → `scan` → `jpegsize` → `jpegdata`.
     *
     * @param dpi requested resolution: 300 or 600.
     * @return the raw JPEG bytes of the scanned page.
     * @throws ScannerOfflineException the scanner cannot be reached or the connection drops.
     * @throws ScannerTimeoutException a read outlived its timeout.
     * @throws ScannerNoPaperException the scanner reports `nopaper`.
     * @throws ScannerBusyException the scanner reports `devbusy`.
     * @throws ScannerBatteryLowException the scanner reports `battlow`.
     * @throws ScannerProtocolException the device answered with an unexpected word.
     * @throws IllegalArgumentException [dpi] is neither 300 nor 600.
     */
    fun scan(dpi: Int): ByteArray {
        require(dpi == 300 || dpi == 600) { "dpi must be 300 or 600, was $dpi" }
        val socket = connect()
        try {
            send(socket, ScannerCommand.STATUS, "status")
            val statusAnswer = readAnswer(socket, "status", NORMAL_TIMEOUT_MILLIS)
            val status = statusWord(statusAnswer)
            if (status == null) {
                throw ScannerProtocolException("Unrecognized status answer from $host:$port: ${describe(statusAnswer)}")
            }
            if (status == "nopaper") {
                throw ScannerNoPaperException("Scanner at $host:$port reports no paper: insert a sheet and retry")
            }
            if (status == "devbusy") {
                throw ScannerBusyException("Scanner at $host:$port is busy with another job: wait a moment and retry")
            }
            if (status == "battlow") {
                throw ScannerBatteryLowException("Scanner at $host:$port reports a low battery: charge it before scanning")
            }
            send(socket, ScannerCommand.VERSION, "version")
            val firmware = readFirmwareVersion(socket)
            val effectiveDpi = resolveDpi(dpi, firmware)
            if (effectiveDpi != dpi) {
                warn("firmware $firmware is too old for 600 dpi, staying at 300 dpi")
            }
            val dpiCommand = if (effectiveDpi == 600) ScannerCommand.DPI600 else ScannerCommand.DPI300
            val dpiExpected = if (effectiveDpi == 600) ScannerResponse.DPIFINE else ScannerResponse.DPISTD
            send(socket, dpiCommand, if (effectiveDpi == 600) "dpi600" else "dpi300")
            expectPrefix(readAnswer(socket, "dpi", NORMAL_TIMEOUT_MILLIS), dpiExpected, "dpi")
            send(socket, ScannerCommand.SCAN, "scan")
            expectPrefix(readAnswer(socket, "scan", NORMAL_TIMEOUT_MILLIS), ScannerResponse.SCANGO, "scan")
            send(socket, ScannerCommand.JPEGSIZE, "jpegsize")
            val sizeAnswer = readJpegSizeAnswer(socket, JPEGSIZE_TIMEOUT_MILLIS)
            val size = sizeOf(sizeAnswer)
            send(socket, ScannerCommand.JPEGDATA, "jpegdata", BULK_READ_PAUSE_MILLIS)
            return readBulkData(socket, size, BULK_READ_TIMEOUT_MILLIS)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Chooses the effective dpi for a scan (SC-07).
     *
     * 600 dpi is only sent if the firmware is new enough to switch to
     * it: the firmware number is the digits after the last `.` of the
     * `version` string (32 for `NB0a.032`) and must be at least
     * [MIN_FIRMWARE_NUMBER_FOR_600_DPI]. A version that cannot be parsed
     * at all is treated as too old — the client conservatively stays at
     * 300 dpi and leaves it to the caller to warn.
     *
     * Pure: no I/O, so it is testable in isolation.
     *
     * @param requestedDpi 300 or 600.
     * @param firmwareVersion the `version` answer, e.g. `NB0a.032`.
     * @return 600 if requested and supported by the firmware, 300 otherwise.
     * @throws IllegalArgumentException [requestedDpi] is neither 300 nor 600.
     */
    fun resolveDpi(
        requestedDpi: Int,
        firmwareVersion: String,
    ): Int {
        require(requestedDpi == 300 || requestedDpi == 600) { "requestedDpi must be 300 or 600, was $requestedDpi" }
        if (requestedDpi != 600) {
            return 300
        }
        val number = firmwareVersion.substringAfterLast('.').toIntOrNull() ?: return 300
        return if (number >= MIN_FIRMWARE_NUMBER_FOR_600_DPI) 600 else 300
    }

    /**
     * Opens the TCP connection to the scanner, mapping every failure
     * (refused, unreachable, timeout) to [ScannerOfflineException] (SC-05).
     */
    private fun connect(): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port), NORMAL_TIMEOUT_MILLIS.toInt())
            return socket
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw ScannerOfflineException("Cannot connect to the scanner at $host:$port: ${e.message}", e)
        }
    }

    /**
     * Sends one 4-byte command with the reference client's pacing (SC-02):
     * 200 ms before the write and [pauseAfterMillis] after it.
     */
    private fun send(
        socket: Socket,
        command: ByteArray,
        step: String,
        pauseAfterMillis: Long = SEND_PAUSE_AFTER_MILLIS,
    ) {
        pause(SEND_PAUSE_MILLIS)
        try {
            val output = socket.getOutputStream()
            output.write(command)
            output.flush()
        } catch (e: IOException) {
            throw ScannerOfflineException("Failed to send '$step' to $host:$port: ${e.message}", e)
        }
        pause(pauseAfterMillis)
    }

    /**
     * Reads one short answer with [timeoutMillis].
     *
     * Short answers arrive as a single small TCP segment on the device,
     * so a single read is enough; only [readJpegSizeAnswer] and
     * [readBulkData] have to loop.
     */
    private fun readAnswer(
        socket: Socket,
        step: String,
        timeoutMillis: Long,
    ): ByteArray {
        val buffer = ByteArray(SHORT_ANSWER_BUFFER_SIZE)
        val read = readChunk(socket, buffer, 0, buffer.size, step, timeoutMillis)
        if (read == -1) {
            throw ScannerOfflineException("Connection to $host:$port closed before the '$step' answer arrived")
        }
        return buffer.copyOf(read)
    }

    /**
     * Reads the `version` answer and returns it as a clean string.
     *
     * The version answer does not follow the 11-byte padding scheme of
     * the other answers (OF-07): it is a short ASCII string with a
     * trailing NUL, e.g. `NB0a.032`. A result that is empty or contains
     * non-printable characters is garbage and becomes a protocol error.
     */
    private fun readFirmwareVersion(socket: Socket): String {
        val answer = readAnswer(socket, "version", NORMAL_TIMEOUT_MILLIS)
        val version = answer.toString(Charsets.US_ASCII).trim { it <= ' ' }
        if (version.isEmpty() || version.any { it < ' ' || it > '~' }) {
            throw ScannerProtocolException("Malformed 'version' answer from $host:$port: ${describe(answer)}")
        }
        return version
    }

    /**
     * Reads the `jpegsize` answer until its full 12 bytes arrived (SC-04).
     *
     * The device splits the answer (8-byte word + 4-byte little-endian
     * size) across TCP segments, so a single read is not enough. Each
     * read is allowed [timeoutMillis] — 60 s, because the device feeds
     * the page through the scanner before it reports the size.
     */
    private fun readJpegSizeAnswer(
        socket: Socket,
        timeoutMillis: Long,
    ): ByteArray {
        val answer = ByteArray(JPEGSIZE_ANSWER_LENGTH)
        var read = 0
        while (read < JPEGSIZE_ANSWER_LENGTH) {
            val chunk = readChunk(socket, answer, read, JPEGSIZE_ANSWER_LENGTH - read, "jpegsize", timeoutMillis)
            if (chunk == -1) {
                throw ScannerOfflineException("Connection to $host:$port closed after $read/$JPEGSIZE_ANSWER_LENGTH bytes of 'jpegsize'")
            }
            read += chunk
        }
        return answer
    }

    /**
     * Reads exactly [size] bytes of the JPEG data, chunk by chunk.
     *
     * The device streams the JPEG in small chunks (1460 bytes); the
     * client reads in [BULK_READ_CHUNK_SIZE]-byte windows until the size
     * announced by `jpegsize` is reached, and returns the bytes
     * untouched (SC-01). Each read is allowed [timeoutMillis].
     */
    private fun readBulkData(
        socket: Socket,
        size: Int,
        timeoutMillis: Long,
    ): ByteArray {
        val data = ByteArray(size)
        val buffer = ByteArray(BULK_READ_CHUNK_SIZE)
        var offset = 0
        while (offset < size) {
            val read = readChunk(socket, buffer, 0, minOf(buffer.size, size - offset), "jpegdata", timeoutMillis)
            if (read == -1) {
                throw ScannerOfflineException("Connection to $host:$port closed after $offset of $size bytes of the JPEG data")
            }
            System.arraycopy(buffer, 0, data, offset, read)
            offset += read
        }
        return data
    }

    /**
     * Reads up to [length] bytes into [buffer] at [offset] with the
     * central SC-05 error mapping: a read timeout becomes
     * [ScannerTimeoutException], an EOF or any other I/O failure becomes
     * [ScannerOfflineException].
     *
     * @return the number of bytes read, or -1 if the connection was closed.
     */
    private fun readChunk(
        socket: Socket,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        step: String,
        timeoutMillis: Long,
    ): Int {
        socket.soTimeout = timeoutMillis.toInt()
        return try {
            socket.getInputStream().read(buffer, offset, length)
        } catch (e: SocketTimeoutException) {
            throw ScannerTimeoutException("Timed out after $timeoutMillis ms reading the '$step' answer", e)
        } catch (e: IOException) {
            throw ScannerOfflineException("Lost the connection to $host:$port while reading the '$step' answer", e)
        }
    }

    /**
     * Matches a status answer against the four known status words.
     *
     * @return the matching word, or null if the answer starts with none
     * of them (a protocol violation).
     */
    private fun statusWord(answer: ByteArray): String? =
        listOf(
            ScannerResponse.SCANREADY,
            ScannerResponse.NOPAPER,
            ScannerResponse.DEVBUSY,
            ScannerResponse.BATTLOW,
        ).firstNotNullOfOrNull { if (startsWithPrefix(answer, it)) it.toString(Charsets.US_ASCII) else null }

    /**
     * Checks one answer against the expected word, throwing
     * [ScannerProtocolException] if the prefixes do not match (SC-03).
     */
    private fun expectPrefix(
        answer: ByteArray,
        expected: ByteArray,
        step: String,
    ) {
        if (!startsWithPrefix(answer, expected)) {
            val actual = describe(answer)
            val expectedWord = describe(expected)
            throw ScannerProtocolException("'$step' answered '$actual' from $host:$port, expected '$expectedWord'")
        }
    }

    /**
     * Parses the 12-byte `jpegsize` answer into the payload size, mapping
     * a malformed answer (short buffer, NUL word) to
     * [ScannerProtocolException] (SC-05).
     */
    private fun sizeOf(sizeAnswer: ByteArray): Int =
        try {
            parseJpegSize(sizeAnswer)
        } catch (e: IllegalArgumentException) {
            throw ScannerProtocolException("Malformed 'jpegsize' answer from $host:$port: ${describe(sizeAnswer)}", e)
        }

    /**
     * Sleeps for [millis]; an interruption restores the interrupt flag
     * and aborts the operation instead of swallowing it.
     */
    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while talking to the scanner at $host:$port", e)
        }
    }

    /**
     * Renders raw bytes for an error message, replacing non-printable
     * bytes with `?` so the device's fill bytes stay readable.
     */
    private fun describe(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            val code = byte.toInt() and 0xFF
            if (code in 0x20..0x7E) code.toChar().toString() else "?"
        }

    /**
     * All tuning values of the protocol client in one place (SC-02):
     * device defaults, the reference client's pacing, timeouts, and
     * protocol sizes.
     */
    companion object {
        // Shipped device; both configurable (SC-06).
        const val DEFAULT_HOST = "192.168.18.33"
        const val DEFAULT_PORT = 23

        // Pause before every send (SC-02).
        const val SEND_PAUSE_MILLIS = 200L

        // Pause after every send, except where noted (SC-02).
        const val SEND_PAUSE_AFTER_MILLIS = 200L

        // [queryStatus] is a status query, so the reference client waits
        // 500 ms instead of 200 ms after its single `status` send.
        const val STATUS_QUERY_PAUSE_AFTER_MILLIS = 500L

        // The bulk JPEG read starts 500 ms after the `jpegdata` send,
        // because the device needs a moment to start streaming.
        const val BULK_READ_PAUSE_MILLIS = 500L

        // Timeout for connects and short answers such as `status` or `scan` (SC-02).
        const val NORMAL_TIMEOUT_MILLIS = 10_000L

        // The device feeds the page through the scanner before `jpegsize` (SC-02).
        const val JPEGSIZE_TIMEOUT_MILLIS = 60_000L

        // Timeout for each read of the streamed JPEG data (SC-02).
        const val BULK_READ_TIMEOUT_MILLIS = 30_000L

        // 600 dpi is only sent from firmware number 26 on (SC-07).
        const val MIN_FIRMWARE_NUMBER_FOR_600_DPI = 26

        // Room for the 11-byte padded answers and the 12-byte `jpegsize` one.
        const val SHORT_ANSWER_BUFFER_SIZE = 16

        // Read window for the streamed JPEG; the device's 1460-byte chunks fit in it.
        const val BULK_READ_CHUNK_SIZE = 65536

        // `jpegsize` answer: 8-byte word + 4-byte little-endian size (SC-04).
        const val JPEGSIZE_ANSWER_LENGTH = 12
    }
}
