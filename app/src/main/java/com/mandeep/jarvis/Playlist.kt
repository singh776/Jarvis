package com.mandeep.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Meri playlist": every song you ask Singh AI to play is saved here by itself (in the background).
 * Songs you play more often go higher. Say "meri playlist chala de" and it plays them all on YouTube.
 */
object Playlist {
    private const val KEY = "playlist"
    private const val MAX = 150

    data class Song(val query: String, val id: String, val plays: Int, val last: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun key(q: String) = q.lowercase().filter { it.isLetterOrDigit() }

    fun all(c: Context): List<Song> = synchronized(lock) {
        try {
            val arr = JSONArray(prefs(c).getString(KEY, "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Song(o.optString("q"), o.optString("id"), o.optInt("n", 1), o.optLong("t"))
            }.filter { it.query.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(c: Context, list: List<Song>) {
        val arr = JSONArray()
        list.sortedByDescending { it.last }.take(MAX).forEach {
            arr.put(JSONObject().put("q", it.query).put("id", it.id).put("n", it.plays).put("t", it.last))
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** Most played first, then newest. */
    fun ordered(c: Context): List<Song> = all(c).sortedWith(compareByDescending<Song> { it.plays }.thenByDescending { it.last })

    /** Saves a played song (finds its YouTube video in the background). [knownId] if we already have it. */
    fun add(c: Context, query: String, knownId: String? = null) {
        val q = query.trim()
        if (q.isEmpty()) return
        val app = c.applicationContext
        Thread {
            val k = key(q)
            val existing = all(app).firstOrNull { key(it.query) == k }
            val id = knownId ?: existing?.id?.takeIf { it.isNotEmpty() }
                ?: try { YouTubeFinder.firstVideoId(q) } catch (e: Exception) { null } ?: ""
            synchronized(lock) {
                val list = all(app).filterNot { key(it.query) == k }.toMutableList()
                list.add(Song(existing?.query ?: q, id, (existing?.plays ?: 0) + 1, System.currentTimeMillis()))
                save(app, list)
            }
        }.start()
    }

    fun remove(c: Context, query: String): Boolean = synchronized(lock) {
        val k = key(query)
        if (k.isEmpty()) return false
        val list = all(c)
        val left = list.filterNot { key(it.query) == k || key(it.query).contains(k) }
        save(c, left)
        left.size != list.size
    }

    fun clear(c: Context) = prefs(c).edit().remove(KEY).apply()

    /** Short list for the AI, so it knows what you like. */
    fun summary(c: Context): String =
        ordered(c).take(20).joinToString("; ") { it.query }.ifEmpty { "(empty)" }

    /** YouTube video ids to play, best first (or shuffled). */
    fun ids(c: Context, shuffle: Boolean): List<String> {
        val ids = ordered(c).map { it.id }.filter { it.length == 11 }
        return (if (shuffle) ids.shuffled() else ids).take(50)
    }
}
