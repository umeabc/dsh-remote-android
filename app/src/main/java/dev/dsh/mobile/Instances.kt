package dev.dsh.mobile

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/** One saved desktop connection. Each instance keeps its own connection code. */
data class RemoteInstance(
    val id: String,
    val name: String,
    val link: String,
) {
    val endpoint: String
        get() = Pairing.parse(link)?.let { "${it.host}:${it.port}" } ?: "—"
}

/**
 * Instances live in the same preference file as the active connection so the
 * service can keep reading `link` without knowing about the list.
 */
object Instances {

    private const val STORE = "dsh-mobile"
    private const val KEY_LIST = "instances"
    private const val KEY_LINK = "link"
    private const val KEY_LAST = "last_connected"

    private fun prefs(context: Context) =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun list(context: Context): List<RemoteInstance> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val link = item.optString("link")
                    if (id.isBlank() || link.isBlank()) continue
                    add(RemoteInstance(id, item.optString("name").ifBlank { "桌面工作站" }, link))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<RemoteInstance>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                org.json.JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("link", item.link),
            )
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** Promote a pre-0.6 single connection into the first instance. */
    fun migrate(context: Context) {
        if (prefs(context).getString(KEY_LIST, null) != null) return
        val legacy = prefs(context).getString(KEY_LINK, null) ?: return
        if (Pairing.parse(legacy) == null) return
        save(context, listOf(RemoteInstance(newId(), defaultName(context), legacy)))
    }

    fun activeLink(context: Context): String? = prefs(context).getString(KEY_LINK, null)

    fun active(context: Context): RemoteInstance? {
        val link = activeLink(context) ?: return null
        return list(context).firstOrNull { it.link == link }
    }

    fun find(context: Context, id: String): RemoteInstance? = list(context).firstOrNull { it.id == id }

    /** Add (or reuse) the instance for a connection code and make it active. */
    fun add(context: Context, link: String, name: String? = null): RemoteInstance {
        val existing = list(context).firstOrNull { sameTarget(it.link, link) }
        if (existing != null) return existing
        val item = RemoteInstance(newId(), name ?: defaultName(context), link)
        save(context, list(context) + item)
        return item
    }

    fun update(context: Context, item: RemoteInstance) {
        save(context, list(context).map { if (it.id == item.id) item else it })
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    fun activate(context: Context, item: RemoteInstance) {
        prefs(context).edit()
            .putString(KEY_LINK, item.link)
            .putLong(KEY_LAST, System.currentTimeMillis())
            .apply()
    }

    fun clearActive(context: Context) {
        prefs(context).edit().remove(KEY_LINK).apply()
    }

    fun sameTarget(first: String, second: String): Boolean {
        val a = Pairing.parse(first) ?: return false
        val b = Pairing.parse(second) ?: return false
        return a.host == b.host && a.port == b.port && a.room == b.room
    }

    private fun defaultName(context: Context): String {
        val count = list(context).size
        return if (count == 0) "桌面工作站" else "桌面工作站 ${count + 1}"
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
}
