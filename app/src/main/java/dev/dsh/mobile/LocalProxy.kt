package dev.dsh.mobile

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Loopback HTTP proxy for the WebView.
 *
 * Static shell files (JS/CSS/fonts) are served from the APK, so the first paint
 * does not wait for megabytes to cross the relay. Everything else — the boot
 * HTML, `/api`, uploads and WebSocket upgrades — is piped verbatim into one
 * tunnel stream, which keeps HTTP semantics untouched.
 */
class LocalProxy(
    tunnel: Tunnel,
    private val asset: (String) -> ByteArray? = { null },
    private val preferredPort: Int = 8321,
) {

    @Volatile
    var tunnel: Tunnel = tunnel

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    var port: Int = 0
        private set

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress("127.0.0.1", preferredPort))
        } catch (_: IOException) {
            socket.bind(InetSocketAddress("127.0.0.1", 0))
        }
        server = socket
        port = socket.localPort
        acceptThread = Thread({ acceptLoop(socket) }, "dsh-proxy").also {
            it.isDaemon = true
            it.start()
        }
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        synchronized(clients) {
            for (client in clients) runCatching { client.close() }
            clients.clear()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            clients.add(client)
            Thread({ handle(client) }, "dsh-proxy-conn").also { it.isDaemon = true; it.start() }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            runCatching { Log.d(TAG, "accepted ${client.remoteSocketAddress}") }
            val first = input.read()
            if (first < 0) {
                runCatching { Log.w(TAG, "client closed before sending") }
                return
            }
            runCatching { Log.d(TAG, "firstByte=0x${Integer.toHexString(first)}") }
            val head = HttpHead.read(
                java.io.SequenceInputStream(java.io.ByteArrayInputStream(byteArrayOf(first.toByte())), input),
            )
            if (head == null) {
                runCatching { Log.w(TAG, "no complete request head") }
                return
            }
            val request = HttpHead.parse(head)
            runCatching { Log.d(TAG, "req ${request?.method} ${request?.path}") }
            // Only the hashed shell files are served locally. The index (and
            // every app route) must reach the desktop, or a directory lookup
            // could answer "/" with an empty body.
            if (request != null && request.servable && isStatic(request.path)) {
                val body = asset(request.path)
                if (body != null) {
                    serveAsset(client, request, body)
                    return
                }
            }
            relay(client, input, head)
        } catch (error: Exception) {
            runCatching { Log.w(TAG, "request failed", error) }
            writeStatus(client, 502, "proxy error")
            runCatching { client.close() }
        } finally {
            clients.remove(client)
        }
    }

    private fun requestPath(head: ByteArray): String =
        String(head, Charsets.ISO_8859_1).lines().firstOrNull()?.take(120) ?: ""
    private fun isStatic(path: String): Boolean =
        path.startsWith("/assets/") || path.startsWith("/favicon") || path == "/manifest.webmanifest"

    private fun writeStatus(client: Socket, code: Int, message: String) {
        runCatching {
            val body = message.toByteArray(Charsets.UTF_8)
            val reason = if (code == 503) "Service Unavailable" else "Bad Gateway"
            val header = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(reason).append(CRLF)
                append("Content-Type: text/plain; charset=utf-8").append(CRLF)
                append("Content-Length: ").append(body.size).append(CRLF)
                append("Connection: close").append(CRLF).append(CRLF)
            }
            val out = client.getOutputStream()
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    /** Serve a bundled file; the hashed name makes it immutable. */
    private fun serveAsset(client: Socket, request: HttpHead.Request, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(WebAssets.contentType(request.path)).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: public, max-age=31536000, immutable\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output = client.getOutputStream()
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!request.isHead) output.write(body)
        output.flush()
        runCatching { client.close() }
    }

    /** Forward the request head and then pipe both directions through a stream. */
    private fun relay(client: Socket, input: InputStream, head: ByteArray) {
        val stream = tunnel.openStream()
        if (stream == null) {
            // Say why instead of closing with an empty response, which the
            // WebView reports as ERR_EMPTY_RESPONSE with no detail.
            runCatching { Log.w(TAG, "tunnel not connected for ${requestPath(head)}") }
            writeStatus(client, 503, "tunnel not connected")
            return
        }
        runCatching { Log.d(TAG, "relay ${head.size}B connected=${tunnel.isConnected}") }
        stream.write(head, 0, head.size)
        runCatching { Log.d(TAG, "relay head written") }

        val upload = Thread({
            try {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    stream.write(buffer, 0, read)
                }
                stream.closeWrite()
            } catch (_: Exception) {
                stream.reset()
            }
        }, "dsh-proxy-up").also { it.isDaemon = true; it.start() }

        var total = 0
        try {
            val buffer = ByteArray(16 * 1024)
            val out = client.getOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                out.write(buffer, 0, read)
                out.flush()
            }
            runCatching { Log.d(TAG, "relay eof total=$total") }
        } catch (error: Exception) {
            runCatching { Log.w(TAG, "relay error total=$total", error) }
        } finally {
            tunnel.closeStream(stream)
            runCatching { client.close() }
            upload.interrupt()
        }
    }

    private companion object {
        const val TAG = "DshProxy"
        const val CRLF = "\r\n"

        const val MAX_HEAD_BYTES = 16 * 1024
    }
}
