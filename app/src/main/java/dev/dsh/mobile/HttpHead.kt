package dev.dsh.mobile

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Minimal HTTP/1.1 request-head reader and parser for the loopback proxy.
 *
 * Kept separate from [LocalProxy] so the framing rules (one head, then raw
 * bytes) are unit-testable on the JVM without an Android AssetManager.
 */
internal object HttpHead {

    const val MAX_BYTES = 16 * 1024

    class Request(
        val method: String,
        val path: String,
        val isUpgrade: Boolean,
        val hasRange: Boolean,
    ) {
        val isHead: Boolean get() = method.equals("HEAD", ignoreCase = true)
        val isRead: Boolean get() = method.equals("GET", ignoreCase = true) || isHead

        /** True when the bundled shell may answer this request itself. */
        val servable: Boolean get() = isRead && !isUpgrade && !hasRange
    }

    /** Reads exactly one request head, stopping after CRLFCRLF. */
    fun read(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(2048)
        var state = 0
        while (buffer.size() < MAX_BYTES) {
            val value = input.read()
            if (value < 0) return null
            buffer.write(value)
            state = when {
                value == 13 -> if (state == 2) 3 else 1
                value == 10 -> when (state) {
                    1 -> 2
                    3 -> 4
                    else -> 0
                }
                else -> 0
            }
            if (state == 4) return buffer.toByteArray()
        }
        return null
    }

    fun parse(head: ByteArray): Request? {
        val text = String(head, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 3) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf(':')
            if (at <= 0) continue
            headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
        }
        val upgrade = headers["upgrade"]?.contains("websocket", ignoreCase = true) == true
        return Request(
            method = parts[0],
            path = parts[1].substringBefore('?'),
            isUpgrade = upgrade,
            hasRange = headers.containsKey("range"),
        )
    }
}
