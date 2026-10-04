package dev.digiwomb.unboundair.scanner

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Standalone entry point for the [FakeScanner] (TE-01, CT-01 prerequisite).
 *
 * Started via `./gradlew fakeScanner` (see `build.gradle.kts`). It starts the
 * existing [FakeScanner] -- no behaviour is reimplemented here, so the quirks
 * the contract tests rely on (fill bytes, split `jpegsize`, `devbusy`,
 * offline, `battlow`) are served unchanged -- and forwards a fixed public
 * port to it. The fake itself only binds an OS-assigned free port on the
 * loopback interface, which no container check could address; the forwarder
 * below listens on all interfaces so `status` (BE-01) gets a real answer.
 *
 * Arguments, all optional:
 * - `--port <n>`: the public port to serve on. Default [DEFAULT_PORT].
 * - `--page <path>`: a file whose bytes are loaded into the tray as one
 *   sheet, so scans deliver a real page instead of the synthetic payload.
 *   Without it the fake answers `scanready` with its defaults, which is
 *   what the `status` check (BE-01) needs.
 *
 * Everything served is logged to stdout, so a failing container check in CI
 * can be read without guessing.
 */
object FakeScannerMain {
    /** The public port served when `--port` is not given. */
    const val DEFAULT_PORT = 2323

    @JvmStatic
    fun main(args: Array<String>) {
        val port = flagValue(args, "--port")?.toIntOrNull() ?: DEFAULT_PORT
        val pagePath = flagValue(args, "--page")

        val fake = FakeScanner()
        if (pagePath != null) {
            val pageFile = Path(pagePath)
            require(pageFile.exists()) { "page file does not exist: $pagePath" }
            val page = pageFile.readBytes()
            fake.loadSheets(listOf(page))
            println("fake-scanner: loaded page $pagePath (${page.size} bytes) into the tray")
        }
        fake.start()
        println("fake-scanner: backend FakeScanner on 127.0.0.1:${fake.port} (statusWord=${fake.statusWord}, version=${fake.version})")
        println("fake-scanner: serving on 0.0.0.0:$port (status against this port answers ${fake.statusWord} and ${fake.version})")

        val server = ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"))
        Runtime.getRuntime().addShutdownHook(
            thread(start = false, isDaemon = false, name = "fake-scanner-shutdown") {
                println("fake-scanner: stopping")
                runCatching { server.close() }
                runCatching { fake.stop() }
            },
        )
        println("fake-scanner: ready, waiting for connections")
        while (!server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (e: java.io.IOException) {
                    break
                }
            thread(isDaemon = true, name = "fake-scanner-forward") {
                forward(client, fake.port)
            }
        }
    }

    /**
     * Forwards one accepted [client] connection to the backend [FakeScanner]
     * on [backendPort], byte-exact in both directions, and logs the outcome.
     */
    private fun forward(
        client: Socket,
        backendPort: Int,
    ) {
        val remote = client.remoteSocketAddress
        println("fake-scanner: connection from $remote")
        val backend =
            try {
                Socket("127.0.0.1", backendPort)
            } catch (e: java.io.IOException) {
                println("fake-scanner: backend unreachable for $remote: ${e.message}")
                runCatching { client.close() }
                return
            }
        var clientToBackend = 0L
        var backendToClient = 0L
        val first = thread(isDaemon = true) { clientToBackend = copy(client, backend) }
        backendToClient = copy(backend, client)
        first.join(5000L)
        runCatching { client.close() }
        runCatching { backend.close() }
        println("fake-scanner: closed $remote (in $clientToBackend bytes, out $backendToClient bytes)")
    }

    /**
     * Copies bytes from [from] to [to] until end of stream, then shuts down
     * the matching direction so the peer sees the end too. Returns the bytes
     * moved; I/O errors just end the copy, the connection is over anyway.
     */
    private fun copy(
        from: Socket,
        to: Socket,
    ): Long {
        var moved = 0L
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
                moved += read
            }
            runCatching { to.shutdownOutput() }
        } catch (e: java.io.IOException) {
            // Connection dropped mid-copy; the forward ends here.
        }
        return moved
    }

    /** Returns the value following [flag] in [args], or null when absent. */
    private fun flagValue(
        args: Array<String>,
        flag: String,
    ): String? {
        val index = args.indexOf(flag)
        if (index == -1 || index + 1 >= args.size) return null
        return args[index + 1]
    }
}
