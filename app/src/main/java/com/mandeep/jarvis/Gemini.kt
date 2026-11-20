package com.mandeep.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An HTTP error from Gemini, with its status code. */
class GeminiException(val code: Int, message: String, val reason: String = "", val retrySec: Int = 0) : RuntimeException(message) {
    /** 429 because this key has NO free use of this model at all ("limit: 0"). */
    val noQuota: Boolean get() = code == 429 && (message.orEmpty().contains("limit: 0") || message.orEmpty().contains("limit:0"))
}

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
    private val RETRY_DELAYS_MS = longArrayOf(800, 1600)

    /** Models that hit their limit (429) — skipped until this time, so we don't waste seconds on them. */
    private val coolDown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun cooling(model: String) = (coolDown[model] ?: 0L) > System.currentTimeMillis()

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
    /** The quick model used for Live chat / the side-button popup (Settings → backup model). */
    fun fastModel(ctx: Context): String = fallbackModel(ctx).ifEmpty { DEFAULT_FALLBACK_MODEL }

    /** Set while a streamed answer is being read: gets the text so far (on the background thread). */
    private val streamCb = ThreadLocal<((String) -> Unit)?>()

    /**
     * Like [generate] but the answer comes in piece by piece (like Gemini typing it out):
     * [onText] is called again and again with all the text received so far.
     */
    fun generateStream(
        ctx: Context, system: String, contents: JSONArray, json: Boolean,
        modelOverride: String? = null, onText: (String) -> Unit
    ): String {
        streamCb.set(onText)
        try {
            return generate(ctx, system, contents, json, modelOverride)
        } finally {
            streamCb.set(null)
        }
    }

    fun generate(ctx: Context, system: String, contents: JSONArray, json: Boolean, modelOverride: String? = null): String {
        val key = apiKey(ctx)
        if (key.isEmpty()) throw IllegalStateException("No Gemini API key")

        val contents = sanitize(contents)
        if (contents.length() == 0) throw GeminiException(400, "Gemini error 400: empty request")
        loadLevels(ctx)
        val levelsBefore = thinkLevel.toMap()
        val main = modelOverride ?: model(ctx)
        // Try the chosen model first, then the backup, then Flash-Lite (works on almost every free key).
        val all = LinkedHashSet<String>().apply {
            add(main)
            if (fallbackEnabled(ctx)) {
                if (modelOverride != null) add(model(ctx))
                fallbackModel(ctx).takeIf { it.isNotEmpty() }?.let { add(it) }
                add(DEFAULT_FALLBACK_MODEL)
            }
        }.toList()
        val ready = all.filterNot { cooling(it) }
        val order = if (ready.isEmpty()) all else ready
        var last: GeminiException? = null

        for ((n, m) in order.withIndex()) {
            if (n > 0) onRetry?.invoke(1, true)
            for (attempt in 0..RETRY_DELAYS_MS.size) {
                try {
                    val out = call(key, m, system, contents, json)
                    coolDown.remove(m)
                    if (thinkLevel != levelsBefore) saveLevels(ctx)
                    return out
                } catch (e: GeminiException) {
                    last = e
                    when {
                        e.code == 429 -> {
                            // limit reached: don't hammer it, move to the next model right away
                            val wait = when {
                                e.noQuota -> 30 * 60_000L
                                e.retrySec > 0 -> e.retrySec * 1000L
                                else -> 60_000L
                            }
                            coolDown[m] = System.currentTimeMillis() + wait
                            break
                        }
                        e.code == 404 -> break // model not available on this key
                        e.code in BUSY_CODES -> {
                            if (attempt < RETRY_DELAYS_MS.size) {
                                onRetry?.invoke(attempt + 1, false)
                                Thread.sleep(RETRY_DELAYS_MS[attempt])
                            }
                        }
                        else -> throw e
                    }
                }
            }
        }
        throw last ?: GeminiException(503, "busy")
    }

    /**
     * Cleans the conversation so Google never rejects it with 400:
     * drops empty messages, joins two messages in a row from the same side,
     * and makes sure it starts and ends with the user.
     */
    fun sanitize(contents: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until contents.length()) {
            val c = contents.optJSONObject(i) ?: continue
            val role = if (c.optString("role") == "model") "model" else "user"
            val parts = c.optJSONArray("parts") ?: continue
            val good = JSONArray()
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                if (p.has("inline_data") || p.has("inlineData")) good.put(p)
                else if (p.optString("text").isNotBlank()) good.put(JSONObject().put("text", p.optString("text")))
            }
            if (good.length() == 0) continue
            if (out.length() == 0 && role == "model") continue
            val prev = if (out.length() > 0) out.getJSONObject(out.length() - 1) else null
            if (prev != null && prev.getString("role") == role) {
                val pp = prev.getJSONArray("parts")
                for (j in 0 until good.length()) pp.put(good.get(j))
            } else out.put(JSONObject().put("role", role).put("parts", good))
        }
        while (out.length() > 0 && out.getJSONObject(out.length() - 1).getString("role") == "model") out.remove(out.length() - 1)
        return out
    }

    /**
     * Fast answers: ask Flash models to think as little as possible.
     * Newer models only accept some levels, so we try "minimal" -> "low" -> no setting,
     * and remember per model what worked (so only the very first question is a bit slower).
     */
    private val THINK_LEVELS = listOf("minimal", "low", "")
    private val thinkLevel = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var levelsLoaded = false
    @Volatile private var lastWarm = 0L

    /** Remembers which thinking setting each model accepts, so even the first question after opening is fast. */
    private fun loadLevels(ctx: Context) {
        if (levelsLoaded) return
        levelsLoaded = true
        try {
            val o = JSONObject(prefs(ctx).getString("think_levels", "{}") ?: "{}")
            for (k in o.keys()) thinkLevel[k] = o.optInt(k)
        } catch (e: Exception) {}
    }

    private fun saveLevels(ctx: Context) {
        try {
            val o = JSONObject()
            for ((k, v) in thinkLevel) o.put(k, v)
            prefs(ctx).edit().putString("think_levels", o.toString()).apply()
        } catch (e: Exception) {}
    }

    /** Opens the connection to Google early (in the background) so the first answer comes quicker. */
    fun warmUp(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastWarm < 60_000 || !Offline.online(ctx)) return
        lastWarm = now
        loadLevels(ctx)
        Thread {
            try {
                val c = (URL("https://generativelanguage.googleapis.com/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000
                }
                c.responseCode; (c.errorStream ?: c.inputStream)?.use { it.readBytes() }
            } catch (e: Exception) {}
        }.start()
    }

    private fun call(key: String, model: String, system: String, contents: JSONArray, json: Boolean): String {
        var i = if (model.contains("flash")) thinkLevel[model] ?: 0 else THINK_LEVELS.size - 1
        while (true) {
            try {
                val out = callKey(key, model, system, contents, json, THINK_LEVELS[i])
                thinkLevel[model] = i
                return out
            } catch (e: GeminiException) {
                val canTryNext = e.code == 400 && !e.message.orEmpty().contains("API key", true) && i < THINK_LEVELS.size - 1
                if (!canTryNext) throw e
                i++
            }
        }
    }

    private fun callKey(key: String, model: String, system: String, contents: JSONArray, json: Boolean, think: String): String =
        try {
            call(key, model, system, contents, json, keyInUrl = false, think = think)
        } catch (e: GeminiException) {
            // Some keys are only accepted as ?key=… — try that once before giving up.
            if (e.code == 401 || e.code == 403) call(key, model, system, contents, json, keyInUrl = true, think = think) else throw e
        }

    private fun call(
        key: String, model: String, system: String, contents: JSONArray, json: Boolean, keyInUrl: Boolean, think: String
    ): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        val gen = JSONObject()
        if (json) gen.put("responseMimeType", "application/json")
        if (think.isNotEmpty()) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", think))
        if (gen.length() > 0) body.put("generationConfig", gen)

        val cb = streamCb.get()
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") +
                (if (cb != null) ":streamGenerateContent?alt=sse" else ":generateContent") +
                (if (keyInUrl) (if (cb != null) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8") else "")
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!keyInUrl) setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (cb != null) return readStream(conn, cb)
            val (code, raw) = read(conn)
            if (code !in 200..299) throw toError(code, raw)
            val parts = JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw GeminiException(503, "Gemini error 503: no answer (blocked or empty)")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
            }
            if (sb.isBlank()) throw GeminiException(503, "Gemini error 503: empty answer")
            return sb.toString()
        } finally {
            // no disconnect(): the connection is kept open and reused → next answer starts faster
        }
    }

    /** Reads a streamed answer ("data: {…}" lines), passing the text so far to [cb]. */
    private fun readStream(conn: HttpURLConnection, cb: (String) -> Unit): String {
        val code = try { conn.responseCode } catch (e: IOException) {
            if (e.message.orEmpty().contains("authentication challenge", true)) 401 else throw e
        }
        if (code !in 200..299) {
            val raw = try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (e: IOException) { "" }
            // streamed errors can come as a JSON list
            throw toError(code, raw.trim().removePrefix("[").removeSuffix("]"))
        }
        val sb = StringBuilder()
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = line.removePrefix("data:").trim()
                if (js.isEmpty()) continue
                val o = try { JSONObject(js) } catch (e: Exception) { continue }
                o.optJSONObject("error")?.let { throw toError(it.optInt("code", 500), o.toString()) }
                val parts = o.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                var added = false
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    val t = p.optString("text", "")
                    if (t.isNotEmpty()) { sb.append(t); added = true }
                }
                if (added) try { cb(sb.toString()) } catch (e: Exception) {}
            }
        }
        if (sb.isBlank()) throw GeminiException(503, "Gemini error 503: empty answer")
        return sb.toString()
    }

    /**
     * Pulls the "say" text out of a JSON answer that is still arriving.
     * Returns (text so far, finished) or null when "say" hasn't started yet.
     */
    fun partialSay(raw: String): Pair<String, Boolean>? {
        val m = Regex("\"say\"\\s*:\\s*\"").find(raw) ?: return null
        val sb = StringBuilder()
        var i = m.range.last + 1
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\') {
                if (i + 1 >= raw.length) break
                when (val n = raw[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append(' '); '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 >= raw.length) break
                        raw.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            if (ch == '"') return sb.toString() to true
            sb.append(ch)
            i++
        }
        return sb.toString() to false
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
        var retry = 0
        try {
            val err = JSONObject(raw).getJSONObject("error")
            msg = err.optString("message", raw)
            reason = err.optString("status", "")
            val details = err.optJSONArray("details")
            if (details != null) for (i in 0 until details.length()) {
                val d = details.optJSONObject(i) ?: continue
                val r = d.optString("reason", "")
                if (r.isNotEmpty() && reason.isEmpty()) reason = r
                val delay = d.optString("retryDelay", "")
                if (delay.isNotEmpty()) retry = delay.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.toInt() ?: 0
            }
        } catch (e: Exception) {}
        return GeminiException(code, "Gemini error $code: ${msg.take(300)}", reason, retry)
    }

    /**
     * Checks a key the same way the app uses it (blocking — run off the main thread).
     * Returns (ok, text to show) and, when it works, the Gemini models this key can use.
     */
    fun testKey(rawKey: String): Triple<Boolean, String, List<String>> {
        val key = cleanKey(rawKey)
        if (key.isEmpty()) return Triple(false, "The key is empty.", emptyList())
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
                    return Triple(true, "✅ The key works!\n$info\nMethod: " +
                        (if (inUrl) "?key=" else "x-goog-api-key header") + "\nModels: ${names.size}", names)
                }
                val e = toError(code, raw)
                lastErr = "❌ Google answered ${e.code}" + (if (e.reason.isNotEmpty()) " (${e.reason})" else "") +
                    "\n\n${e.message?.removePrefix("Gemini error ${e.code}: ")}"
            } catch (e: IOException) {
                return Triple(false, "❌ No internet: ${e.javaClass.simpleName}\n$info", emptyList())
            } finally {
                conn.disconnect()
            }
        }
        return Triple(false, "$lastErr\n\n$info", emptyList())
    }

    /** Tries one tiny question on [model] and says if it works (blocking — run off the main thread). */
    fun probe(ctx: Context, model: String): Pair<Boolean, String> {
        val key = apiKey(ctx)
        if (key.isEmpty()) return false to "No API key"
        val t0 = System.currentTimeMillis()
        return try {
            call(key, model, "Reply with one word.", JSONArray().put(part("user", "Hi")), false)
            coolDown.remove(model)
            true to "✅ Works · ${"%.1f".format((System.currentTimeMillis() - t0) / 1000f)}s"
        } catch (e: GeminiException) {
            false to when {
                e.noQuota -> "❌ No free use on your key"
                e.code == 429 -> "⏳ Limit reached — try in a minute"
                e.code == 503 || e.code == 500 -> "⏳ Google busy right now"
                e.code == 404 -> "❌ Not available"
                e.code == 401 || e.code == 403 -> "❌ Key not allowed"
                else -> "❌ ${e.code}: " + e.message.orEmpty().removePrefix("Gemini error ${e.code}: ").take(60)
            }
        } catch (e: Exception) {
            false to "❌ ${e.javaClass.simpleName}"
        }
    }

    /** Text models this key can use (blocking). */
    fun listModels(ctx: Context): List<String> {
        val (ok, _, names) = testKey(apiKey(ctx))
        if (!ok) return emptyList()
        val skip = listOf("tts", "image", "embedding", "audio", "live", "robotics", "aqa", "native")
        return names.filter { n -> skip.none { n.contains(it) } }.distinct().sorted()
    }

    /** A short, correct message for the user for any error from [generate]. */
    fun friendlyError(e: Throwable, punjabi: Boolean): String {
        val keyOrNet = if (punjabi) "ਮਾਫ਼ ਕਰਨਾ ਸਰ, ਜੁੜ ਨਹੀਂ ਸਕਿਆ। ਇੰਟਰਨੈੱਟ ਤੇ API key ਚੈੱਕ ਕਰੋ।"
        else "Sorry Sir, I couldn't connect. Check the internet and API key."
        return when {
            e is GeminiException && e.noQuota ->
                if (punjabi) "ਤੁਹਾਡੀ API key ਤੇ ਇਸ ਮਾਡਲ ਦੀ ਮੁਫ਼ਤ ਵਰਤੋਂ ਨਹੀਂ ਹੈ। ਸੈਟਿੰਗ → AI ਵਿੱਚ Flash-Lite ਚੁਣੋ, ਜਾਂ AI Studio ਵਿੱਚ billing ਚਾਲੂ ਕਰੋ।"
                else "Your API key has no free use of this model. Pick Flash-Lite in Settings → AI, or turn on billing in AI Studio."
            e is GeminiException && e.code == 429 ->
                if (punjabi) "ਮੁਫ਼ਤ ਵਾਲੀ ਹੱਦ ਪੂਰੀ ਹੋ ਗਈ (ਇੱਕ ਮਿੰਟ ਵਿੱਚ ਬਹੁਤੇ ਸਵਾਲ)। ਮਿੰਟ ਕੁ ਬਾਅਦ ਫਿਰ ਪੁੱਛੋ।"
                else "The free limit is used up for now (too many requests). Ask again in a minute."
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
                (if (punjabi) "ਮਾਫ਼ ਕਰਨਾ ਸਰ, ਕੁਝ ਗੜਬੜ ਹੋ ਗਈ।" else "Sorry Sir, something went wrong.") + " (${e.code})" +
                    (if (e.code == 400) "\n" + e.message.orEmpty().removePrefix("Gemini error 400: ").take(160) else "")
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
