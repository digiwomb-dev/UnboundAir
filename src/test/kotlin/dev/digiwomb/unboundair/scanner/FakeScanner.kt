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
     * Whether status/ack answers carry the real device's fill bytes.
     *
     * When `true` (default), every status/ack answer is exactly 11 bytes:
     * the ASCII word + `\x00` padding + a trailing `H`. When `false`, only
     * the raw word is sent, which tests use to provoke a protocol error
     * (SC-05).
     */
    var fillBytes: Boolean = true

    /**
     * The version string returned for the `version` command.
     *
     * Default `NB0a.032`. The answer is the string plus a single `\x00`
     * byte, e.g. `NB0a.032\x00` — deliberately NOT padded to 11 bytes
     * (OF-07). Tests set this to a version below 26 to exercise the
     * firmware check (SC-07).
     */
    var version: String = "NB0a.032"

    /**
     * Whether the 12-byte `jpegsize` answer is split into two TCP segments.
     *
     * When `true`, the first 10 bytes and the remaining 2 bytes are sent in
     * two separate writes with a short pause in between, as observed on the
     * real device (SC-04). Default `false`.
     */
    var splitJpegsize: Boolean = false

    /**
     * Whether the fake accepts connections but never answers.
     *
     * When `true`, commands are read and recorded but no response is ever
     * written, so a client read eventually times out. Used for the timeout
     * test (SC-02). Default `false`.
     */
    var hang: Boolean = false

    /**
     * The JPEG payload returned for the `jpegdata` command.
     *
     * Default is a small synthetic, deterministic byte pattern (not a real
     * JPEG and not loaded from a file). Tests override it and assert that
     * the exact bytes come back unchanged (SC-01).
     */
    var payload: ByteArray =
        ByteArray(3000) { index -> (index * 31 + 7).toByte() }

    /**
     * The status word returned for the `status` command.
     *
     * Default `scanready`. Tests set it to `nopaper`, `devbusy`, or
     * `battlow` to exercise the error paths (SC-05).
     */
    var statusWord: String = "scanready"

    /**
     * An optional delay before answering `jpegsize`, in milliseconds.
     *
     * Default 0. The real device pulls the paper through in this time, so
     * the client allows up to 60 s here; tests may set a short delay to
     * observe the behaviour.
     */
    var scanDelayMillis: Long = 0

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
        acceptThread =
            thread(isDaemon = true, name = "fake-scanner-accept") {
                while (isRunning.get()) {
                    try {
                        val clientSocket = socket.accept()
                        connectionCounter.incrementAndGet()
                        activeSockets.add(clientSocket)
                        thread(isDaemon = true, name = "fake-scanner-client") {
                            try {
                                handleClient(clientSocket)
                            } finally {
                                activeSockets.remove(clientSocket)
                            }
                        }
                    } catch (e: IOException) {
                        if (isRunning.get()) continue else break
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
     * The connection ends when the client closes it, when an unknown command
     * arrives (the real device hangs up), or when the server is stopped.
     */
    private fun handleClient(socket: Socket) {
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

                val commandName = commandName(command)
                if (commandName != null) {
                    receivedCommandQueue.add(commandName)
                }

                // A hanging device reads but never answers; the client's
                // read must time out on its own.
                if (hang) continue

                when (commandName) {
                    "status" -> {
                        write(output, paddedAnswer(statusWord))
                    }

                    "version" -> {
                        write(output, versionAnswer())
                    }

                    "dpi300" -> {
                        write(output, paddedAnswer("dpistd"))
                    }

                    "dpi600" -> {
                        write(output, paddedAnswer("dpifine"))
                    }

                    "scan" -> {
                        write(output, paddedAnswer("scango"))
                    }

                    "jpegsize" -> {
                        if (scanDelayMillis > 0) Thread.sleep(scanDelayMillis)
                        val answer = jpegSizeAnswer()
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
                        var offset = 0
                        while (offset < payload.size) {
                            val end = minOf(offset + JPEG_CHUNK_SIZE, payload.size)
                            output.write(payload, offset, end - offset)
                            offset = end
                        }
                        output.flush()
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
     * Builds the 12-byte `jpegsize` answer: the ASCII word followed by the
     * payload size as a little-endian uint32 (SC-04).
     */
    private fun jpegSizeAnswer(): ByteArray {
        val size = payload.size
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

    private companion object {
        /** Total length of a padded status/ack answer (word + padding + H). */
        const val FILLED_ANSWER_LENGTH = 11

        /** Chunk size for streaming the JPEG payload, as on the real device. */
        const val JPEG_CHUNK_SIZE = 1460

        /** Pause between the two segments of a split jpegsize answer. */
        const val SPLIT_PAUSE_MILLIS = 50L
    }
}
