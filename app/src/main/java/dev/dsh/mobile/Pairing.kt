package dev.dsh.mobile

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Parses the `dshm://relay?...` connection code shown by the desktop. */
object Pairing {

    fun parse(text: String): RelayConfig? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("dshm", ignoreCase = true)) return null
        val query = parseQuery(uri.rawQuery ?: return null)
        val address = query["url"]?.trim().orEmpty()
        val room = query["room"]?.trim().orEmpty()
        val key = query["key"]?.trim().orEmpty()
        if (address.isEmpty() || room.isEmpty() || key.isEmpty()) return null

        val colon = address.lastIndexOf(':')
        val host: String
        val port: Int
        if (colon <= 0) {
            host = address
            port = 9443
        } else {
            host = address.substring(0, colon)
            port = address.substring(colon + 1).toIntOrNull() ?: return null
        }
        if (port !in 1..65535) return null
        if (!room.matches(Regex("[0-9a-fA-F]{16,64}"))) return null

        val secret = runCatching { Base64.getUrlDecoder().decode(key) }.getOrNull() ?: return null
        if (secret.size != 16) return null
        return RelayConfig(host, port, room.lowercase(), secret)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val at = part.indexOf('=')
            val name = if (at < 0) part else part.substring(0, at)
            val value = if (at < 0) "" else part.substring(at + 1)
            result[decode(name)] = decode(value)
        }
        return result
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
