package com.mandeep.jarvis

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Finds the first YouTube video for a search, without any API key. Blocking: run off the main thread. */
object YouTubeFinder {
    private val VIDEO_ID = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    fun firstVideoId(query: String): String? {
        if (query.isBlank()) return null
        val url = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cookie", "CONSENT=YES+1")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            VIDEO_ID.find(html)?.groupValues?.get(1)
        } finally {
            conn.disconnect()
        }
    }
}
