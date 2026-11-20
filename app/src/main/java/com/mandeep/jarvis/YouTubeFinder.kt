package com.mandeep.jarvis

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Finds the first YouTube video for a search, without any API key. Blocking: run off the main thread. */
object YouTubeFinder {
    private val VIDEO_ID = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    /** Up to [n] different video ids for [query] (in YouTube's order). Blocking. */
    fun videoIds(query: String, n: Int): List<String> {
        if (query.isBlank()) return emptyList()
        val url = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+1")
        }
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val ids = LinkedHashSet<String>()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val buf = CharArray(32 * 1024)
                val sb = StringBuilder()
                while (ids.size < n && sb.length < 2_000_000) {
                    val k = r.read(buf)
                    if (k < 0) break
                    sb.append(buf, 0, k)
                    ids.clear()
                    VIDEO_ID.findAll(sb).forEach { if (ids.size < n) ids.add(it.groupValues[1]) }
                }
            }
            ids.toList()
        } catch (e: Exception) { emptyList() } finally { conn.disconnect() }
    }

    fun firstVideoId(query: String): String? {
        if (query.isBlank()) return null
        val url = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 7000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+1")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            // read only until the first video shows up (much faster than loading the whole page)
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val buf = CharArray(16 * 1024)
                val sb = StringBuilder()
                var searched = 0
                while (sb.length < 1_500_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    val from = (searched - 40).coerceAtLeast(0)
                    VIDEO_ID.find(sb, from)?.let { return it.groupValues[1] }
                    searched = sb.length
                }
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
