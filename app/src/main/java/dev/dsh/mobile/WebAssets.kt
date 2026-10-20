package dev.dsh.mobile

import android.content.res.AssetManager
import java.io.IOException

/**
 * Static DSH frontend shipped inside the APK.
 *
 * The desktop's static server sends no cache headers, so a phone would pull the
 * ~5 MB shell through the encrypted tunnel on every launch. These files are
 * content-hashed by the build, so serving them from the APK is both faster and
 * safe to cache forever. A missing file simply falls through to the tunnel,
 * which keeps the app working across DSH upgrades.
 */
object WebAssets {

    private val TYPES = mapOf(
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "woff2" to "font/woff2",
        "woff" to "font/woff",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "html" to "text/html; charset=utf-8",
        "webmanifest" to "application/manifest+json",
        "wasm" to "application/wasm",
        "map" to "application/json",
    )

    fun contentType(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        return TYPES[extension] ?: "application/octet-stream"
    }

    /** Reads one bundled file, or null when the bundle does not carry it. */
    fun read(assets: AssetManager, path: String): ByteArray? {
        if (path.isEmpty() || path == "/" || path.contains("..")) return null
        val normalized = path.removePrefix("/")
        return try {
            assets.open("dsh-web/$normalized").use { stream -> stream.readBytes().takeIf { it.isNotEmpty() } }
        } catch (_: IOException) {
            null
        }
    }
}
