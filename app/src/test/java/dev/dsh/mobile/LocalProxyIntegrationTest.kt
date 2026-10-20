package dev.dsh.mobile

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.Base64

/**
 * Optional live check: runs the real loopback proxy against the deployed relay
 * and fetches the DSH index through it. Skipped unless DSH_ROOM/DSH_KEY exist.
 */
class LocalProxyIntegrationTest {

    private fun request(): String {
        val crlf = "\r\n"
        val lines = listOf(
            "GET / HTTP/1.1",
            "Host: 127.0.0.1",
            "Accept: text/html,application/xhtml+xml",
            "Accept-Encoding: gzip, deflate, br",
            "Connection: close",
        )
        return lines.joinToString(crlf) + crlf + crlf
    }

    @Test(timeout = 90_000)
    fun servesTheIndexThroughTheRelay() {
        val room = System.getenv("DSH_ROOM")
        val key = System.getenv("DSH_KEY")
        assumeTrue("DSH_ROOM/DSH_KEY not set", room != null && key != null)

        val config = RelayConfig(
            System.getenv("DSH_RELAY_HOST") ?: "127.0.0.1",
            (System.getenv("DSH_RELAY_PORT") ?: "9443").toInt(),
            room ?: return,
            Base64.getUrlDecoder().decode(key ?: return),
        )
        val tunnel = Tunnel(config, object : Tunnel.Listener {
            override fun onState(state: Tunnel.State, detail: String?) = Unit
        })
        val proxy = LocalProxy(tunnel, preferredPort = 0)
        try {
            proxy.start()
            tunnel.start()
            val deadline = System.currentTimeMillis() + 15_000
            while (!tunnel.isConnected && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("tunnel did not connect", tunnel.isConnected)

            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.soTimeout = 20_000
                socket.getOutputStream().write(request().toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
                val bytes = socket.getInputStream().readBytes()
                val text = String(bytes, Charsets.ISO_8859_1)
                println("proxy bytes=${bytes.size} head=${text.take(80)}")
                assertTrue("empty response", bytes.isNotEmpty())
                assertTrue("status line: ${text.take(40)}", text.startsWith("HTTP/1.1 200"))
                assertTrue("shell missing", text.contains("__DSH_BOOT__"))
            }
        } finally {
            proxy.stop()
            tunnel.stop()
        }
    }
}
