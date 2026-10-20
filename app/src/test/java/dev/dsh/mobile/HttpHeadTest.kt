package dev.dsh.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpHeadTest {

    private val crlf = "\r\n"

    @Test
    fun parsesAGetRequest() {
        val head = "GET /assets/index-abc.js HTTP/1.1$crlf Host: 127.0.0.1:8321$crlf$crlf"
        val request = HttpHead.parse(head.toByteArray())
        requireNotNull(request)
        assertEquals("GET", request.method)
        assertEquals("/assets/index-abc.js", request.path)
        assertTrue(request.servable)
        assertFalse(request.isUpgrade)
    }

    @Test
    fun stripsTheQueryString() {
        val request = HttpHead.parse("GET /assets/a.css?v=1 HTTP/1.1$crlf$crlf".toByteArray())
        assertEquals("/assets/a.css", request?.path)
    }

    @Test
    fun refusesToServeWebSocketUpgrades() {
        val head = "GET /api/remote.mux HTTP/1.1$crlf Upgrade: websocket$crlf Connection: Upgrade$crlf$crlf"
        val request = HttpHead.parse(head.toByteArray())
        requireNotNull(request)
        assertTrue(request.isUpgrade)
        assertFalse(request.servable)
    }

    @Test
    fun refusesToServeRangeRequests() {
        val head = "GET /assets/font.woff2 HTTP/1.1$crlf Range: bytes=0-100$crlf$crlf"
        val request = HttpHead.parse(head.toByteArray())
        requireNotNull(request)
        assertTrue(request.hasRange)
        assertFalse(request.servable)
    }

    @Test
    fun readsOnlyTheHeadAndLeavesTheBody() {
        val raw = "POST /api HTTP/1.1$crlf Content-Length: 5$crlf${crlf}hello"
        val stream = ByteArrayInputStream(raw.toByteArray())
        val head = HttpHead.read(stream)
        requireNotNull(head)
        assertEquals("POST /api HTTP/1.1$crlf Content-Length: 5$crlf$crlf", String(head))
        assertEquals("hello", String(stream.readBytes()))
    }

    @Test
    fun rejectsMalformedHeads() {
        assertEquals(null, HttpHead.parse("nonsense\r\n\r\n".toByteArray()))
    }
}
