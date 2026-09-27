package dev.digiwomb.unboundair.scanner

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The direction of one [TranscriptEntry] within the scanner protocol.
 */
enum class TranscriptDirection {
    /** A 4-byte command read from the client. */
    FROM_CLIENT,

    /** An answer or payload written to the client. */
    TO_CLIENT,
}

/**
 * One recorded protocol exchange of the [FakeScanner] [transcript][FakeScanner.transcript]
 * (Session 5, requirement SC-01).
 *
 * @property connection the 1-based number of the TCP connection the bytes belong to.
 * @property direction whether the bytes were read from the client or written to it.
 * @property hex lowercase hex of the exact bytes, e.g. `7363616e` for the `scan` command.
 * @property decoded human-readable decoding: the command name (`status`, `version`,
 *   `dpi300`, `dpi600`, `scan`, `jpegsize`, `jpegdata`) or `unknown` when the 4 bytes
 *   map to no known command; for answers, the bare word (`scanready`, `dpistd`,
 *   `dpifine`, `scango`, `jpegdata`) or the version string without its trailing NUL
 *   (`NB0a.032`).
 */
data class TranscriptEntry(
    val connection: Int,
    val direction: TranscriptDirection,
    val hex: String,
    val decoded: String,
)

/**
 * A TCP server that mimics the Mustek iScan Air (S400W) scanner protocol.
 *
 * The fake reproduces the protocol quirks observed on the real device:
 * 4-byte commands, 11-byte padded answers (`word + \x00 padding + H`), the
 * special `version` format (`NB0a.032\x00`), the 12-byte `jpegsize` answer
 * (optionally split into two TCP segments), and the `jpegdata` payload
 * streamed in 1460-byte chunks.
 *
 * The server binds to an OS-assigned free port on the loopback interface and
 * handles each connection in its own daemon thread, so tests run against it
 * exactly like against the real device.
 *
 * Every protocol exchange is additionally recorded in [transcript]: one
 * [TranscriptDirection.FROM_CLIENT] entry per 4-byte command read and one
 * [TranscriptDirection.TO_CLIENT] entry per answer written, in the order
 * they happen within each connection. That transcript is the golden source
 * for the SC-01 protocol contract (Session 5): a contract test compares a
 * client session against the recorded bytes byte-exact. One [FakeScanner]
 * instance records one append-only transcript; a fresh instance per test is
 * the norm, matching how the class is used today.
 *
 * ## Driving a running service (TE-01 extension, milestone 3)
 *
 * A single fixed answer is enough to test one operation, but not a polling
 * loop (DL-01 to DL-06) or the measuring run (BE-04): those need the device to
 * *change* over time. Three additions cover that, all opt-in so the existing
 * single-shot tests keep behaving exactly as before:
 *
 * - **A sheet tray** ([loadSheets], [insertSheet]). This models the device
 *   rather than the test: `status` answers `scanready` while a sheet is
 *   waiting and `nopaper` once the tray is empty, and a completed scan
 *   consumes the sheet it delivered. A loop therefore scans each sheet exactly
 *   once and then settles, without the test having to time anything.
 * - **A scripted status queue** ([scriptStatuses]) for answers that do not
 *   follow from the tray, above all `devbusy` (OF-02) and `battlow`. Each
 *   scripted entry is consumed by one `status` command; when the script runs
 *   out, the tray decides again.
 * - **Reversible offline** ([goOffline], [comeOnline]) on the **same port**.
 *   [stop] is terminal and a later [start] would bind a different port, which
 *   cannot express "the device switched itself off and was switched on again"
 *   (DL-02, DL-04, and the auto-off of OF-01).
 *
 * ## Thread safety
 *
 * The configuration fields are `@Volatile` and the collections are concurrent.
 * This is not decoration: the fields are written by the test thread while the
 * per-connection threads read them, and without the annotation a change made
 * mid-run may never become visible to the serving thread. The resulting test
 * would fail rarely and unreproducibly.
 *
 * @see ScannerCommand
 * @see ScannerResponse
 */
class FakeScanner : AutoCloseable {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var boundPort: Int = 0
    private val isRunning = AtomicBoolean(false)
    private val connectionCounter = AtomicInteger(0)
    private val activeSockets = ConcurrentLinkedQueue<Socket>()
    private val receivedCommandQueue = ConcurrentLinkedQueue<String>()
    private val transcriptQueue = ConcurrentLinkedQueue<TranscriptEntry>()

    /**
     * The actual port the server socket is bound to.
     *
     * Available after [start] is called; with port 0 this is the OS-assigned
     * free port. The value is kept after [stop], so tests can still read it.
     */
    val port: Int
        get() = boundPort

    /**
     * The number of TCP connections accepted so far.
     *
     * Thread-safe; tests use it to assert that one operation opens exactly
     * one connection (SC-02).
     */
    val connectionCount: Int
        get() = connectionCounter.get()

    /**
     * An ordered record of every command name received across all
     * connections.
     *
     * Values are `"status"`, `"version"`, `"dpi300"`, `"dpi600"`, `"scan"`,
     * `"jpegsize"`, `"jpegdata"`. Tests assert against it, e.g. to check
     * which DPI command arrived (SC-07).
     */
    val receivedCommands: List<String>
        get() = receivedCommandQueue.toList()

    /**
     * An ordered record of every command and answer exchanged on every
     * connection, as [TranscriptEntry] snapshots.
     *
     * One entry per logical protocol exchange, in the order it happens:
     * [TranscriptDirection.FROM_CLIENT] for every 4-byte command read
     * (`decoded` is the command name, or `unknown`), and
     * [TranscriptDirection.TO_CLIENT] for every answer written. Answers are
     * recorded at the point their bytes are decided, so a split `jpegsize`
     * answer is one 12-byte entry (the split into two TCP segments is a
     * transport artifact, not part of the protocol exchange) and the
     * `jpegdata` payload is one entry with the full payload bytes (not one
     * per 1460-byte chunk). In the [hang] case commands are still recorded
     * as FROM_CLIENT, but nothing is answered, so no TO_CLIENT entries
     * appear.
     *
     * [hex] is the lowercase hex of the exact bytes exchanged.
     *
     * The queue is a [ConcurrentLinkedQueue] like [receivedCommandQueue], so
     * the per-client threads may append concurrently; within one connection
     * the entries are strictly ordered, because a single thread serves it.
     * Entries of different connections may interleave. One [FakeScanner]
     * instance records one append-only transcript — a fresh instance per
     * test is the norm.
     */
    val transcript: List<TranscriptEntry>
        get() = transcriptQueue.toList()

    /**
     * Whether status/ack answers carry the real device's fill bytes.
     *
     * When `true` (default), every status/ack answer is exactly 11 bytes:
     * the ASCII word + `\x00` padding + a trailing `H`. When `false`, only
     * the raw word is sent, which tests use to provoke a protocol error
     * (SC-05).
     */
    @Volatile
    var fillBytes: Boolean = true

    /**
     * The version string returned for the `version` command.
     *
     * Default `NB0a.032`. The answer is the string plus a single `\x00`
     * byte, e.g. `NB0a.032\x00` — deliberately NOT padded to 11 bytes
     * (OF-07). Tests set this to a version below 26 to exercise the
     * firmware check (SC-07).
     */
    @Volatile
    var version: String = "NB0a.032"

    /**
     * Whether the 12-byte `jpegsize` answer is split into two TCP segments.
     *
     * When `true`, the first 10 bytes and the remaining 2 bytes are sent in
     * two separate writes with a short pause in between, as observed on the
     * real device (SC-04). Default `false`.
     */
    @Volatile
    var splitJpegsize: Boolean = false

    /**
     * Whether the fake accepts connections but never answers.
     *
     * When `true`, commands are read and recorded but no response is ever
     * written, so a client read eventually times out. Used for the timeout
     * test (SC-02). Default `false`.
     */
    @Volatile
    var hang: Boolean = false

    /**
     * The JPEG payload returned for the `jpegdata` command.
     *
     * Default is a small synthetic, deterministic byte pattern (not a real
     * JPEG and not loaded from a file). Tests override it and assert that
     * the exact bytes come back unchanged (SC-01).
     */
    @Volatile
    var payload: ByteArray =
        ByteArray(3000) { index -> (index * 31 + 7).toByte() }

    /**
     * The status word returned for the `status` command.
     *
     * Default `scanready`. Tests set it to `nopaper`, `devbusy`, or
     * `battlow` to exercise the error paths (SC-05).
     */
    @Volatile
    var statusWord: String = "scanready"

    /**
     * An optional delay before answering `jpegsize`, in milliseconds.
     *
     * Default 0. The real device pulls the paper through in this time, so
     * the client allows up to 60 s here; tests may set a short delay to
     * observe the behaviour.
     */
    @Volatile
    var scanDelayMillis: Long = 0

    /**
     * The sheets waiting in the tray, each one the payload of a future scan.
     *
     * Empty by default, which keeps [statusWord] and [payload] in charge and
     * leaves every existing single-shot test untouched. Once sheets are loaded
     * ([loadSheets], [insertSheet]) the tray takes over: `status` answers
     * `scanready` while a sheet is waiting and `nopaper` when the tray is
     * empty, and each completed `jpegdata` consumes its sheet.
     */
    private val sheets = ConcurrentLinkedQueue<ByteArray>()

    /**
     * Status answers to hand out before the tray is consulted ([scriptStatuses]).
     */
    private val scriptedStatuses = ConcurrentLinkedQueue<String>()

    /**
     * Whether the tray has been used at all, i.e. whether sheets were ever
     * loaded.
     *
     * A separate flag rather than `sheets.isNotEmpty()`, because the two differ
     * in exactly the case that matters: once the last sheet has been scanned
     * the tray is empty, and the device must then answer `nopaper`. Deciding by
     * emptiness would instead fall back to the [statusWord] field -- `scanready`
     * by default -- and the loop would scan forever.
     */
    private val trayInUse = AtomicBoolean(false)

    /** Whether the device currently refuses connections ([goOffline]). */
    private val offline = AtomicBoolean(false)

    /** Counts completed scans, i.e. delivered `jpegdata` payloads. */
    private val scanCounter = AtomicInteger(0)

    /**
     * The number of pages fully delivered so far.
     *
     * Lets a test wait for "three pages have been scanned" instead of sleeping
     * for a guessed duration.
     */
    val completedScans: Int
        get() = scanCounter.get()

    /** How many sheets are still waiting in the tray. */
    val remainingSheets: Int
        get() = sheets.size

    /** Whether the device is currently refusing connections. */
    val isOffline: Boolean
        get() = offline.get()

    /**
     * Loads [pages] into the tray, replacing whatever was there.
     *
     * From this point the tray drives the status: `scanready` while a sheet is
     * waiting, `nopaper` when it is empty. Each page is delivered by exactly
     * one scan, in order, so a loop picks them up one after another and then
     * settles on `nopaper` by itself.
     *
     * Distinct page contents are recommended: identical payloads would let a
     * loop that scans the same sheet repeatedly pass unnoticed.
     */
    fun loadSheets(pages: List<ByteArray>) {
        sheets.clear()
        sheets.addAll(pages)
        trayInUse.set(true)
    }

    /**
     * Adds one sheet to the tray, as a person laying a page on a running
     * device would.
     *
     * Used to test that a page arriving within the batch window joins the open
     * batch (DL-03, DL-04).
     */
    fun insertSheet(page: ByteArray) {
        sheets.add(page)
        trayInUse.set(true)
    }

    /**
     * Queues status answers that are handed out before the tray is consulted,
     * one per `status` command, in order.
     *
     * This is for answers the tray cannot express: `devbusy` (OF-02) and
     * `battlow` above all. Once the script is exhausted the tray decides again,
     * so `scriptStatuses("devbusy", "devbusy")` means "the device is busy for
     * the next two polls and normal afterwards".
     */
    fun scriptStatuses(vararg statuses: String) {
        scriptedStatuses.addAll(statuses.toList())
    }

    /**
     * Makes the device unreachable without giving up its port.
     *
     * Existing connections are dropped and new ones refused, which is what a
     * client sees when the scanner switches itself off (the 5-minute auto-off
     * of OF-01, and the offline trigger of DL-02 and DL-04).
     *
     * Unlike [stop] this is reversible: [comeOnline] brings the device back on
     * the **same port**, so a test can cover a full off-and-on cycle. That is
     * impossible with [stop], because a later [start] binds a new port and the
     * client would be pointing at the wrong address.
     */
    fun goOffline() {
        if (offline.getAndSet(true)) return
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        activeSockets.forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
    }

    /**
     * Brings the device back on its original port after [goOffline].
     *
     * Binding the same port again can lose a race with the kernel releasing the
     * previous socket, so the attempt is retried briefly. `SO_REUSEADDR` is set
     * for the same reason: without it a socket lingering in `TIME_WAIT` would
     * make this fail intermittently.
     */
    fun comeOnline() {
        if (!offline.getAndSet(false)) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        var attempt = 0
        while (true) {
            try {
                socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), boundPort), 50)
                break
            } catch (e: IOException) {
                attempt++
                if (attempt >= REBIND_ATTEMPTS) throw e
                Thread.sleep(REBIND_PAUSE_MILLIS)
            }
        }
        serverSocket = socket
        startAcceptLoop(socket)
    }

    /**
     * Starts the fake scanner.
     *
     * Binds a [ServerSocket] to the loopback interface on port 0 (an
     * OS-assigned free port) and begins accepting connections in a
     * background thread. Idempotent: calling it again while running does
     * nothing.
     */
    fun start() {
        if (isRunning.get()) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        boundPort = socket.localPort
        isRunning.set(true)
        startAcceptLoop(socket)
    }

    /**
     * Accepts connections on [socket] until it is closed.
     *
     * Extracted from [start] so [comeOnline] can resume serving on a freshly
     * bound socket without duplicating the loop. The loop exits when its own
     * socket is closed, which is how both [stop] and [goOffline] end it.
     */
    private fun startAcceptLoop(socket: ServerSocket) {
        acceptThread =
            thread(isDaemon = true, name = "fake-scanner-accept") {
                while (isRunning.get() && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()
                        val connection = connectionCounter.incrementAndGet()
                        activeSockets.add(clientSocket)
                        thread(isDaemon = true, name = "fake-scanner-client") {
                            try {
                                handleClient(clientSocket, connection)
                            } finally {
                                activeSockets.remove(clientSocket)
                            }
                        }
                    } catch (e: IOException) {
                        // The socket was closed by stop() or goOffline(); in
                        // both cases this loop is done. A new one is started by
                        // comeOnline() on a new socket.
                        break
                    }
                }
            }
    }

    /**
     * Stops the fake scanner.
     *
     * Closes the server socket and every active client connection, which
     * releases the background threads (also any blocked reads).
     */
    fun stop() {
        if (!isRunning.get()) return
        isRunning.set(false)
        serverSocket?.close()
        serverSocket = null
        activeSockets.forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    /**
     * Closes the fake scanner. Alias for [stop], implements [AutoCloseable].
     */
    override fun close() {
        stop()
    }

    /**
     * Serves one client connection: reads 4-byte commands and answers
     * according to the scanner protocol.
     *
     * [connection] is the 1-based number of this TCP connection (the value
     * [connectionCounter] reached when the connection was accepted); every
     * [TranscriptEntry] recorded for it carries this number, so a transcript
     * of multiple connections can be told apart.
     *
     * The connection ends when the client closes it, when an unknown command
     * arrives (the real device hangs up), or when the server is stopped.
     */
    private fun handleClient(
        socket: Socket,
        connection: Int,
    ) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (isRunning.get()) {
                val command = ByteArray(4)
                var offset = 0
                while (offset < 4) {
                    val bytesRead = input.read(command, offset, 4 - offset)
                    if (bytesRead == -1) return
                    offset += bytesRead
                }

                // One FROM_CLIENT entry per command read, also for commands
                // the device does not know (recorded as `unknown`).
                recordFromClient(connection, command)

                val commandName = commandName(command)
                if (commandName != null) {
                    receivedCommandQueue.add(commandName)
                }

                // A hanging device reads but never answers; the client's
                // read must time out on its own.
                if (hang) continue

                when (commandName) {
                    "status" -> {
                        val word = currentStatusWord()
                        val answer = paddedAnswer(word)
                        recordToClient(connection, answer, word)
                        write(output, answer)
                    }

                    "version" -> {
                        val answer = versionAnswer()
                        // The answer carries a trailing NUL; the decoded form
                        // is the version string without it, e.g. `NB0a.032`.
                        val decoded = answer.toString(Charsets.US_ASCII).trimEnd('\u0000')
                        recordToClient(connection, answer, decoded)
                        write(output, answer)
                    }

                    "dpi300" -> {
                        val answer = paddedAnswer("dpistd")
                        recordToClient(connection, answer, "dpistd")
                        write(output, answer)
                    }

                    "dpi600" -> {
                        val answer = paddedAnswer("dpifine")
                        recordToClient(connection, answer, "dpifine")
                        write(output, answer)
                    }

                    "scan" -> {
                        val answer = paddedAnswer("scango")
                        recordToClient(connection, answer, "scango")
                        write(output, answer)
                    }

                    "jpegsize" -> {
                        if (scanDelayMillis > 0) Thread.sleep(scanDelayMillis)
                        val answer = jpegSizeAnswer(currentPayload())
                        // One entry for the full 12 bytes: the optional split
                        // into two TCP segments is a transport artifact and not
                        // part of the protocol exchange.
                        recordToClient(connection, answer, "jpegsize")
                        if (splitJpegsize) {
                            output.write(answer, 0, 10)
                            output.flush()
                            Thread.sleep(SPLIT_PAUSE_MILLIS)
                            output.write(answer, 10, 2)
                            output.flush()
                        } else {
                            write(output, answer)
                        }
                    }

                    "jpegdata" -> {
                        val page = currentPayload()
                        // One entry for the full payload: the 1460-byte
                        // chunking is a transport artifact, not a protocol
                        // exchange.
                        recordToClient(connection, page, "jpegdata")
                        var sent = 0
                        while (sent < page.size) {
                            val end = minOf(sent + JPEG_CHUNK_SIZE, page.size)
                            output.write(page, sent, end - sent)
                            sent = end
                        }
                        output.flush()
                        // The sheet has left the device: drop it from the tray
                        // so the next status answers nopaper unless another
                        // sheet is waiting. Without this the loop would rescan
                        // the same page forever.
                        sheets.poll()
                        scanCounter.incrementAndGet()
                    }

                    else -> {
                        return
                    }
                }
            }
        } catch (e: IOException) {
            // Connection dropped or closed during stop(); just end.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Maps a 4-byte command to its name, or `null` for unknown commands.
     */
    private fun commandName(command: ByteArray): String? =
        when {
            command.contentEquals(ScannerCommand.STATUS) -> "status"
            command.contentEquals(ScannerCommand.VERSION) -> "version"
            command.contentEquals(ScannerCommand.DPI300) -> "dpi300"
            command.contentEquals(ScannerCommand.DPI600) -> "dpi600"
            command.contentEquals(ScannerCommand.SCAN) -> "scan"
            command.contentEquals(ScannerCommand.JPEGSIZE) -> "jpegsize"
            command.contentEquals(ScannerCommand.JPEGDATA) -> "jpegdata"
            else -> null
        }

    /**
     * Builds the real device's 11-byte answer: word + `\x00` padding +
     * trailing `H` (SC-03). With [fillBytes] off, the raw word is sent
     * instead.
     */
    private fun paddedAnswer(word: String): ByteArray {
        if (!fillBytes) return word.toByteArray(Charsets.US_ASCII)
        val answer = ByteArray(FILLED_ANSWER_LENGTH)
        word.toByteArray(Charsets.US_ASCII).copyInto(answer, 0)
        answer[FILLED_ANSWER_LENGTH - 1] = 'H'.code.toByte()
        return answer
    }

    /**
     * Builds the special `version` answer: version string + single `\x00`,
     * no `H`, no 11-byte padding (OF-07).
     */
    private fun versionAnswer(): ByteArray = "$version\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * The status word for the next `status` command.
     *
     * Priority, and the order matters:
     *
     * 1. a scripted answer, if one is queued ([scriptStatuses]) -- these are the
     *    states the tray cannot express, such as `devbusy`;
     * 2. otherwise the tray, once it has been used at all: `scanready` while a
     *    sheet waits, `nopaper` when empty;
     * 3. otherwise the plain [statusWord] field, which is what every
     *    single-shot test uses and what keeps their behaviour unchanged.
     */
    private fun currentStatusWord(): String {
        scriptedStatuses.poll()?.let { return it }
        if (trayInUse.get()) {
            return if (sheets.isEmpty()) "nopaper" else "scanready"
        }
        return statusWord
    }

    /**
     * The payload for the current scan: the sheet at the front of the tray, or
     * the [payload] field when the tray is not in use.
     */
    private fun currentPayload(): ByteArray = sheets.peek() ?: payload

    /**
     * Builds the 12-byte `jpegsize` answer: the ASCII word followed by the
     * payload size as a little-endian uint32 (SC-04).
     */
    private fun jpegSizeAnswer(page: ByteArray): ByteArray {
        val size = page.size
        val answer = ByteArray(12)
        ScannerResponse.JPEGSIZE.copyInto(answer, 0)
        answer[8] = (size and 0xFF).toByte()
        answer[9] = ((size shr 8) and 0xFF).toByte()
        answer[10] = ((size shr 16) and 0xFF).toByte()
        answer[11] = ((size shr 24) and 0xFF).toByte()
        return answer
    }

    private fun write(
        output: OutputStream,
        bytes: ByteArray,
    ) {
        output.write(bytes)
        output.flush()
    }

    /**
     * Records the 4-byte command [command] read from connection
     * [connection] as a [TranscriptDirection.FROM_CLIENT] [TranscriptEntry].
     *
     * A command the device does not know is still recorded, with
     * [TranscriptEntry.decoded] = `unknown`; the connection then ends, as on
     * the real device.
     */
    private fun recordFromClient(
        connection: Int,
        command: ByteArray,
    ) {
        transcriptQueue.add(
            TranscriptEntry(
                connection = connection,
                direction = TranscriptDirection.FROM_CLIENT,
                hex = toHex(command),
                decoded = commandName(command) ?: "unknown",
            ),
        )
    }

    /**
     * Records [bytes] written to connection [connection] as a
     * [TranscriptDirection.TO_CLIENT] [TranscriptEntry].
     *
     * Callers record at the point the answer bytes are decided, so a split
     * `jpegsize` answer and the chunked `jpegdata` payload each become one
     * entry of the full logical bytes (see [transcript]).
     */
    private fun recordToClient(
        connection: Int,
        bytes: ByteArray,
        decoded: String,
    ) {
        transcriptQueue.add(
            TranscriptEntry(
                connection = connection,
                direction = TranscriptDirection.TO_CLIENT,
                hex = toHex(bytes),
                decoded = decoded,
            ),
        )
    }

    /**
     * Renders [bytes] as lowercase hex, byte-exact for arbitrary bytes: each
     * byte is masked with `0xFF` before formatting, so values above `0x7F`
     * print as two hex digits (`80` to `ff`) rather than signed values.
     */
    private fun toHex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private companion object {
        /** Total length of a padded status/ack answer (word + padding + H). */
        const val FILLED_ANSWER_LENGTH = 11

        /** Chunk size for streaming the JPEG payload, as on the real device. */
        const val JPEG_CHUNK_SIZE = 1460

        /** Pause between the two segments of a split jpegsize answer. */
        const val SPLIT_PAUSE_MILLIS = 50L

        /** How often [comeOnline] retries binding the original port. */
        const val REBIND_ATTEMPTS = 50

        /** Pause between two rebind attempts. */
        const val REBIND_PAUSE_MILLIS = 20L
    }
}
