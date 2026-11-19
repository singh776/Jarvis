package com.mandeep.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved conversations for the chat list (≡ on the left, like Gemini).
 * Every time the app opens a new chat starts; old chats stay here and can be opened and continued.
 */
object ChatStore {
    private const val KEY = "chats"
    private const val MAX = 60

    /** One saved conversation. msgs = what's on screen, hist = what the AI remembers of it. */
    class Chat(val id: String, val title: String, val time: Long, val msgs: JSONArray, val hist: JSONArray)

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis_chats", Context.MODE_PRIVATE)

    fun all(c: Context): List<Chat> = try {
        val arr = JSONArray(prefs(c).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Chat(
                o.optString("id"), o.optString("title"), o.optLong("time"),
                o.optJSONArray("msgs") ?: JSONArray(), o.optJSONArray("hist") ?: JSONArray()
            )
        }.sortedByDescending { it.time }
    } catch (e: Exception) { emptyList() }

    fun get(c: Context, id: String): Chat? = all(c).firstOrNull { it.id == id }

    private fun write(c: Context, list: List<Chat>) {
        val arr = JSONArray()
        list.sortedByDescending { it.time }.take(MAX).forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("time", it.time)
                .put("msgs", it.msgs).put("hist", it.hist))
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** Saves (or updates) a conversation. [msgs] = list of (fromUser, text). */
    fun save(c: Context, id: String, msgs: List<Pair<Boolean, String>>, hist: JSONArray) {
        if (msgs.isEmpty()) return
        val m = JSONArray()
        msgs.takeLast(120).forEach { (u, t) -> m.put(JSONObject().put("u", u).put("t", t)) }
        val first = msgs.firstOrNull { it.first }?.second ?: msgs.first().second
        val title = first.replace("\n", " ").trim().let { if (it.length > 48) it.take(46) + "…" else it }
        val list = all(c).filterNot { it.id == id }.toMutableList()
        list.add(Chat(id, title, System.currentTimeMillis(), m, hist))
        write(c, list)
    }

    fun delete(c: Context, id: String) = write(c, all(c).filterNot { it.id == id })

    fun messages(chat: Chat): List<Pair<Boolean, String>> =
        (0 until chat.msgs.length()).mapNotNull { i ->
            val o = chat.msgs.optJSONObject(i) ?: return@mapNotNull null
            o.optBoolean("u") to o.optString("t")
        }

    fun newId() = "c" + System.currentTimeMillis()

    /** "5 min ago", "Yesterday"… for the list. */
    fun ago(time: Long): String {
        val d = (System.currentTimeMillis() - time) / 60_000
        return when {
            d < 1 -> "Just now"
            d < 60 -> "$d min ago"
            d < 24 * 60 -> "${d / 60} h ago"
            d < 48 * 60 -> "Yesterday"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.ENGLISH).format(java.util.Date(time))
        }
    }
}
