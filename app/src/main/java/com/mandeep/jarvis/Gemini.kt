package com.mandeep.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An HTTP error from Gemini, with its status code. */
class GeminiException(val code: Int, message: String, val reason: String = "") : RuntimeException(message)

/** Small Google Gemini REST client (no extra libraries). */
object Gemini {

    // ------------------------------------------------------------------ CONFIG
    // Change the models here. Both can also be changed in Settings → AI.

    /** Main model. */
    const val DEFAULT_MODEL = "gemini-flash-latest"

    /** Lighter backup model, used when the main one stays busy (503/429). */
    const val DEFAULT_FALLBACK_MODEL = "gemini-flash-lite-latest"

    /** Models offered in Settings (id to label). */
    val MODEL_CHOICES = listOf(
        "gemini-flash-latest" to "Gemini Flash (latest)",
        "gemini-flash-lite-latest" to "Gemini Flash-Lite (latest)",
        "gemini-pro-latest" to "Gemini Pro (latest)",
        "gemini-3.6-flash" to "Gemini 3.6 Flash"
    )

    /** Waits between retries when the server is busy: 1s, 2s, 4s. */
    private val RETRY_DELAYS_MS = longArrayOf(1000, 2000, 4000)

    /** HTTP codes that mean "busy, try again soon". */
    private val BUSY_CODES = setOf(429, 500, 502, 503, 504)

    // -------------------------------------------------------------------------

    /**
     * Called (on a background thread) when Jarvis is about to retry because the server is busy.
     * attempt = 1..3 for the main model, usingFallback = true when switching to the backup model.
     */
    @Volatile
    var onRetry: ((attempt: Int, usingFallback: Boolean) -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String = cleanKey(prefs(ctx).getString("api_key", "") ?: "")

    /** Removes spaces, new lines, quotes and invisible characters that sneak in when pasting. */
    fun cleanKey(raw: String): String =
        raw.filter { it.code in 33..126 && it != '"' && it != '\'' }

    fun model(ctx: Context): String =
        prefs(ctx).getString("model", "")?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_MODEL

    fun fallbackModel(ctx: Context): String =
        prefs(ctx).getString("fallback_model", "")?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_FALLBACK_MODEL

    fun fallbackEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("fallback_on", true)

    fun label(id: String): String = MODEL_CHOICES.firstOrNull { it.first == id }?.second ?: id

    fun part(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun isBusy(e: Throwable): Boolean = e is GeminiException && e.code in BUSY_CODES

    /**
     * Blocking call — run on a background thread. Returns the model's text.
     * Retries busy errors (1s, 2s, 4s), then tries the backup model (and retries it once).
     */
    fun generate(ctx: Context, system: String, contents: JSONArray, json: Boolean): String {
        val key = apiKey(ctx)
        if (key.isEmpty()) throw IllegalStateException("No Gemini API key")

        val main = model(ctx)
        var last: GeminiException? = null

        // 1) main model: first try + up to 3 retries
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            try {
                return call(key, main, system, contents, json)
            } catch (e: GeminiException) {
                if (e.code == 404) { last = e; break } // model gone → go straight to the backup model
                if (e.code !in BUSY_CODES) throw e
                last = e
                if (attempt < RETRY_DELAYS_MS.size) {
                    onRetry?.invoke(attempt + 1, false)
                    Thread.sleep(RETRY_DELAYS_MS[attempt])
                }
            }
        }

        // 2) backup model: one try + one retry
        val backup = fallbackModel(ctx)
        if (fallbackEnabled(ctx) && backup.isNotEmpty() && backup != main) {
            for (attempt in 0..1) {
                try {
                    onRetry?.invoke(attempt + 1, true)
                    return call(key, backup, system, contents, json)
                } catch (e: GeminiException) {
                    if (e.code !in BUSY_CODES) throw e
                    last = e
                    if (attempt == 0) Thread.sleep(2000)
                }
            }
        }
        throw last ?: GeminiException(503, "busy")
    }

    /** Models that refused "no thinking" — we stop asking them for it. */
    private val thinkingRequired = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun call(key: String, model: String, system: String, contents: JSONArray, json: Boolean): String {
        // Skip the slow "thinking" step on Flash models so answers come back faster.
        val fast = model.contains("flash") && model !in thinkingRequired
        return try {
            callKey(key, model, system, contents, json, fast)
        } catch (e: GeminiException) {
            if (fast && e.code == 400 && e.message.orEmpty().contains("thinking", true)) {
                thinkingRequired.add(model)
                callKey(key, model, system, contents, json, false)
            } else throw e
        }
    }

    private fun callKey(key: String, model: String, system: String, contents: JSONArray, json: Boolean, fast: Boolean): String =
        try {
            call(key, model, system, contents, json, keyInUrl = false, fast = fast)
        } catch (e: GeminiException) {
            // Some keys are only accepted as ?key=… — try that once before giving up.
            if (e.code == 401 || e.code == 403) call(key, model, system, contents, json, keyInUrl = true, fast = fast) else throw e
        }

    private fun call(
        key: String, model: String, system: String, contents: JSONArray, json: Boolean, keyInUrl: Boolean, fast: Boolean
    ): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        val gen = JSONObject()
        if (json) gen.put("responseMimeType", "application/json")
        if (fast) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        if (gen.length() > 0) body.put("generationConfig", gen)

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent" +
                (if (keyInUrl) "?key=" + URLEncoder.encode(key, "UTF-8") else "")
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!keyInUrl) setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val (code, raw) = read(conn)
            if (code !in 200..299) throw toError(code, raw)
            val parts = JSONObject(raw)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** Reads status + body. Some Android versions throw on a 401 instead of returning it. */
    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            if (e.message.orEmpty().contains("authentication challenge", true)) 401 else throw e
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = try { stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (e: IOException) { "" }
        return code to raw
    }

    /** Turns Google's error JSON into a [GeminiException] with the exact reason. */
    private fun toError(code: Int, raw: String): GeminiException {
        var msg = raw
        var reason = ""
        try {
            val err = JSONObject(raw).getJSONObject("error")
            msg = err.optString("message", raw)
            reason = err.optString("status", "")
            val details = err.optJSONArray("details")
            if (details != null) for (i in 0 until details.length()) {
                val r = details.optJSONObject(i)?.optString("reason", "").orEmpty()
                if (r.isNotEmpty()) { reason = r; break }
            }
        } catch (e: Exception) {}
        return GeminiException(code, "Gemini error $code: ${msg.take(200)}", reason)
    }

    /**
     * Checks a key the same way the app uses it (blocking — run off the main thread).
     * Returns (ok, text to show) and, when it works, the Gemini models this key can use.
     */
    fun testKey(rawKey: String): Triple<Boolean, String, List<String>> {
        val key = cleanKey(rawKey)
        if (key.isEmpty()) return Triple(false, "Key khaali hai.", emptyList())
        val info = "Key: ${key.take(6)}…${key.takeLast(4)}  (${key.length} akhar)"
        var lastErr = ""
        for (inUrl in listOf(false, true)) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" +
                (if (inUrl) "&key=" + URLEncoder.encode(key, "UTF-8") else "")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                if (!inUrl) setRequestProperty("x-goog-api-key", key)
            }
            try {
                val (code, raw) = read(conn)
                if (code in 200..299) {
                    val arr = JSONObject(raw).optJSONArray("models") ?: JSONArray()
                    val names = ArrayList<String>()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                        val id = m.optString("name").removePrefix("models/")
                        if (id.startsWith("gemini") && methods.contains("generateContent")) names.add(id)
                    }
                    return Triple(true, "✅ Key theek hai!\n$info\nTareeka: " +
                        (if (inUrl) "?key=" else "x-goog-api-key header") + "\nModels: ${names.size}", names)
                }
                val e = toError(code, raw)
                lastErr = "❌ Google ne ${e.code} dittaa" + (if (e.reason.isNotEmpty()) " (${e.reason})" else "") +
                    "\n\n${e.message?.removePrefix("Gemini error ${e.code}: ")}"
            } catch (e: IOException) {
                return Triple(false, "❌ Internet nahi: ${e.javaClass.simpleName}\n$info", emptyList())
            } finally {
                conn.disconnect()
            }
        }
        return Triple(false, "$lastErr\n\n$info", emptyList())
    }

    /** A short, correct message for the user for any error from [generate]. */
    fun friendlyError(e: Throwable, punjabi: Boolean): String {
        val keyOrNet = if (punjabi) "ਮਾਫ਼ ਕਰਨਾ ਸਰ, ਜੁੜ ਨਹੀਂ ਸਕਿਆ। ਇੰਟਰਨੈੱਟ ਤੇ API key ਚੈੱਕ ਕਰੋ।"
        else "Sorry Sir, I couldn't connect. Check the internet and API key."
        return when {
            isBusy(e) ->
                if (punjabi) "ਸਰਵਰ ਬਿਜ਼ੀ ਹੈ ਸਰ, ਥੋੜ੍ਹੀ ਦੇਰ ਬਾਅਦ ਦੁਬਾਰਾ ਪੁੱਛੋ।"
                else "Google's server is busy right now, Sir. Please ask again in a moment."
            e is GeminiException && (e.code == 401 || e.code == 403) ->
                (if (punjabi) "Google ਨੇ API key ਨਹੀਂ ਮੰਨੀ (${e.code}${if (e.reason.isNotEmpty()) " " + e.reason else ""}). " +
                    "ਸੈਟਿੰਗ → API Key → \"Test key\" ਦਬਾਓ, ਜਾਂ ਨਵੀਂ key ਬਣਾ ਕੇ ਪਾਓ।"
                else "Google rejected the API key (${e.code}${if (e.reason.isNotEmpty()) " " + e.reason else ""}). " +
                    "Open Settings → API Key → \"Test key\", or paste a new key.")
            e is GeminiException && e.code == 400 && e.message.orEmpty().contains("API key", true) -> "$keyOrNet (400)"
            e is GeminiException && e.code == 404 ->
                if (punjabi) "ਇਹ ਮਾਡਲ ਨਹੀਂ ਮਿਲਿਆ। ਸੈਟਿੰਗ → AI ਵਿੱਚ ਮਾਡਲ ਬਦਲੋ।"
                else "That model wasn't found. Change it in Settings → AI."
            e is IOException -> keyOrNet
            e is IllegalStateException ->
                if (punjabi) "ਪਹਿਲਾਂ ਸੈਟਿੰਗ ਵਿੱਚ Gemini API key ਪਾਓ।" else "Please add your Gemini API key in Settings first."
            e is GeminiException ->
                (if (punjabi) "ਮਾਫ਼ ਕਰਨਾ ਸਰ, ਕੁਝ ਗੜਬੜ ਹੋ ਗਈ।" else "Sorry Sir, something went wrong.") + " (${e.code})"
            else ->
                (if (punjabi) "ਮਾਫ਼ ਕਰਨਾ ਸਰ, ਕੁਝ ਗੜਬੜ ਹੋ ਗਈ।" else "Sorry Sir, something went wrong.") +
                    " (${e.javaClass.simpleName})"
        }
    }

    /** Parses a JSON object out of the model text (tolerates ```json fences). */
    fun parseJson(out: String): JSONObject? {
        val s = out.trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(s.substring(start, end + 1)) } catch (e: Exception) { null }
    }
}
