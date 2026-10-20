package dev.dsh.mobile

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

/**
 * Optional live check: joins the running relay with the desktop's room key and
 * fetches the DSH shell through the tunnel. Skipped unless DSH_ROOM/DSH_KEY are
 * present in the environment.
 */
class RelayIntegrationTest {

    @Test(timeout = 45_000)
    fun fetchesTheDesktopShellThroughTheRelay() {
        val room = System.getenv("DSH_ROOM")
        val key = System.getenv("DSH_KEY")
        assumeTrue("DSH_ROOM/DSH_KEY not set", room != null && key != null)
        val roomId = room ?: return
        val roomKey = key ?: return

        val config = RelayConfig(
            System.getenv("DSH_RELAY_HOST") ?: "127.0.0.1",
            (System.getenv("DSH_RELAY_PORT") ?: "9443").toInt(),
            roomId,
            Base64.getUrlDecoder().decode(roomKey),
        )
        val tunnel = Tunnel(config, object : Tunnel.Listener {
            override fun onState(state: Tunnel.State, detail: String?) = Unit
        })
        tunnel.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (!tunnel.isConnected && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("tunnel did not connect", tunnel.isConnected)

        val stream = tunnel.openStream()
        assertTrue("no stream", stream != null)
        val request = "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray()
        stream!!.write(request, 0, request.size)
        stream.closeWrite()

        val response = StringBuilder()
        val buffer = ByteArray(16 * 1024)
        val readDeadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < readDeadline) {
            val read = stream.read(buffer)
            if (read < 0) break
            response.append(String(buffer, 0, read))
            if (response.length > 64 * 1024) break
        }
        tunnel.stop()
        assertTrue("no 200 response: ${response.take(80)}", response.startsWith("HTTP/1.1 200"))
        assertTrue("shell missing", response.contains("__DSH_BOOT__"))
    }
}
