package com.mandeep.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What Jarvis wants to say, plus an optional phone action to perform. */
data class Reply(val say: String, val action: JSONObject?)

/**
 * Jarvis's "brain": sends what you said to Google Gemini and gets back a short
 * spoken answer plus an action for the phone.
 */
class Brain(private val context: Context) {

    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val history = ArrayList<Pair<String, String>>() // role ("user"/"model") to text

    init { loadHistory() }

    val apiKey: String get() = Gemini.apiKey(context)

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    // ------------------------------------------------------------- memory
    // Facts the user tells Singh AI (name, family, likes…). Kept forever until cleared.

    private fun saveHistory() {
        val arr = JSONArray()
        for ((role, msg) in history) arr.put(JSONObject().put("r", role).put("t", msg))
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory() {
        try {
            val arr = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(o.getString("r") to o.getString("t"))
            }
        } catch (e: Exception) { history.clear() }
    }

    /** Runs on a background thread. Throws on network / API errors. */
    fun ask(text: String, punjabiMode: Boolean, punjabiVoice: Boolean, phoneInfo: String): Reply {
        if (apiKey.isEmpty()) {
            return offline(text, punjabiMode)
                ?: Reply(
                    if (punjabiMode) "ਪਹਿਲਾਂ ਸੈਟਿੰਗ ਵਿੱਚ Gemini API key ਪਾਓ, ਫਿਰ ਮੈਂ ਹਰ ਕੰਮ ਕਰ ਸਕਾਂਗਾ।"
                    else "Please add your Gemini API key in settings first.",
                    null
                )
        }

        history.add("user" to text)
        while (history.size > 24) history.removeAt(0)
        val contents = JSONArray()
        for ((role, msg) in history) contents.put(Gemini.part(role, msg))

        val out = try {
            Gemini.generate(context, systemPrompt(punjabiMode, punjabiVoice, phoneInfo), contents, json = true)
        } catch (e: Exception) {
            history.removeAt(history.size - 1)
            throw e
        }
        history.add("model" to out)
        saveHistory()
        val o = Gemini.parseJson(out) ?: return Reply(out.trim(), null)
        // new things to remember / forget
        o.optJSONArray("remember")?.let { arr -> for (i in 0 until arr.length()) Memory.add(context, arr.optString(i)) }
        o.optJSONArray("forget")?.let { arr -> for (i in 0 until arr.length()) Memory.forget(context, arr.optString(i)) }
        return Reply(o.optString("say", ""), o.optJSONObject("action"))
    }

    /** Answers a question about a photo, screenshot or file the user showed (read, explain, translate…). */
    fun askAbout(question: String, file: Attachment, punjabiMode: Boolean, punjabiVoice: Boolean): String {
        if (apiKey.isEmpty()) throw IllegalStateException("No Gemini API key")
        val q = question.ifBlank {
            if (punjabiMode) "ਇਸ ਵਿੱਚ ਕੀ ਹੈ? ਜੇ ਕੁਝ ਲਿਖਿਆ ਹੈ ਤਾਂ ਪੜ੍ਹ ਕੇ ਦੱਸੋ।" else "What is this? If there is text, read it to me."
        }
        val system = "You are Singh AI, the user's phone assistant. The user is showing you a ${file.label}. " +
            "Look at it carefully and do what they ask: describe it, read the text in it, explain it, answer a question about it, " +
            "or translate it (English ↔ Punjabi, or whatever language they ask). When translating or reading, give the full text. " +
            "Your reply is spoken aloud: plain sentences, no markdown, no lists, no emojis. Keep it short unless reading or translating. " +
            langRule(punjabiMode, punjabiVoice) +
            Memory.all(context).let { m -> if (m.isEmpty()) "" else " Things you know about the user: " + m.joinToString("; ") + "." }

        val contents = JSONArray()
        for ((role, msg) in history.takeLast(8).dropWhile { it.first == "model" }) contents.put(Gemini.part(role, msg))
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", file.mime)
                        .put("data", android.util.Base64.encodeToString(file.bytes, android.util.Base64.NO_WRAP))
                )
            )
            .put(JSONObject().put("text", q))
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        val out = Gemini.generate(context, system, contents, json = false).trim()
        history.add("user" to "[showed a ${file.label}] $q")
        history.add("model" to JSONObject().put("say", out).put("action", JSONObject().put("type", "none")).toString())
        while (history.size > 24) history.removeAt(0)
        saveHistory()
        return out
    }

    /** Second step for actions that fetch data (e.g. notifications): turn data into a spoken answer. */
    fun summarize(question: String, data: String, punjabiMode: Boolean, punjabiVoice: Boolean): String {
        val system = "You are Singh AI, the user's phone assistant. Answer the user's request using the DATA below. " +
            "Reply in plain spoken sentences (no markdown, no lists), max 5 sentences. " +
            langRule(punjabiMode, punjabiVoice)
        val contents = JSONArray().put(Gemini.part("user", "REQUEST: $question\n\nDATA:\n$data"))
        val out = Gemini.generate(context, system, contents, json = false).trim()
        history.add("model" to JSONObject().put("say", out).put("action", JSONObject().put("type", "none")).toString())
        saveHistory()
        return out
    }

    private fun langRule(punjabiMode: Boolean, punjabiVoice: Boolean): String {
        val script = if (punjabiVoice) "write it in Gurmukhi script (ਪੰਜਾਬੀ)"
        else "write it in romanized Punjabi using English letters (e.g. \"Haan ji, kar dita\"), because this phone has no Gurmukhi voice"
        return "The user speaks Punjabi and English, often mixed. Reply in the language the user used. " +
            "If they speak Punjabi, $script. If they speak English, reply in English. " +
            "The microphone is currently set to ${if (punjabiMode) "Punjabi" else "English"}."
    }

    private fun systemPrompt(punjabiMode: Boolean, punjabiVoice: Boolean, phoneInfo: String): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        return """
You are "Singh AI" (say your name as Singh AI), a JARVIS-style witty, calm, loyal personal AI assistant (in the spirit of Iron Man's assistant) living inside the user's Samsung Galaxy S26 Android phone. You can operate the whole phone. Call the user "Sir" now and then.

LANGUAGE: ${langRule(punjabiMode, punjabiVoice)}

STYLE: "say" is spoken aloud by text-to-speech: keep it short (1-3 sentences), natural, no markdown, no emojis, no lists. Longer only when the user asks for detail.

PHONE STATUS: Current time: $now. $phoneInfo

MEMORY (things the user told you before — always use them, e.g. call the user by their name, answer "what is my name?" from here):
${Memory.all(context).let { m -> if (m.isEmpty()) "(nothing yet)" else m.joinToString("\n") { "- $it" } }}

OUTPUT: ONLY a JSON object: {"say": "...", "action": {...}, "remember": ["..."], "forget": ["..."]}.
"remember": short facts about the user to keep forever (in English), e.g. "User's name is Mandeep", "User's mother is called Momi" — add one whenever the user tells you something personal or says "yaad rakh"/"remember". Don't repeat facts already in MEMORY. Leave it out when there is nothing new.
"forget": exact MEMORY lines to delete when the user asks you to forget them or a fact changed. Pick the most direct action:
{"type":"none"}  -> just talking / answering a question
{"type":"open_app","name":"<app name in English>"}
{"type":"youtube","query":"..."}
{"type":"web_search","query":"..."}
{"type":"maps","query":"<place>"}
{"type":"call","contact":"<contact name in English letters as likely saved, e.g. Mom, Papa, Harpreet>"} or {"type":"call","number":"<digits>"}
{"type":"sms","contact":"...","text":"...","send":true|false} (or "number" instead of "contact")
{"type":"whatsapp_to","contact":"...","text":"...","send":true|false} (or "number") -> opens that person's WhatsApp chat directly with the text typed. "send": true when the user says send/bhej/ਭੇਜ; false when they only say write/type/likh/draft. Use "text":"" with send false to just open someone's chat.
{"type":"play_music","query":"<song / artist>","app":"spotify|youtube music|youtube|"}
{"type":"navigate","query":"<place>"} -> starts Google Maps directions
{"type":"alarm","hour":0-23,"minute":0-59,"label":"optional"}
{"type":"timer","seconds":N}
{"type":"torch","on":true|false}
{"type":"media","cmd":"play|pause|next|previous"}  -> controls whatever music/video is playing
{"type":"volume","level":0-100} or {"type":"volume","change":"up|down|mute"}
{"type":"brightness","level":0-100}
{"type":"ringer","mode":"normal|vibrate"}
{"type":"global","cmd":"back|home|recents|notifications|quick_settings|lock|screenshot|power_menu"}
{"type":"read_notifications"}  -> when the user asks what notifications/messages they got
{"type":"settings","page":"wifi|bluetooth|display|sound|battery|main"}
{"type":"screen_task","app":"<app to open first, e.g. Instagram>","goal":"<short, exact goal in English — only what the user asked>"} -> for ANYTHING else that needs using apps on screen: turning wifi/bluetooth/hotspot/airplane mode on or off, posting, liking, searching inside an app, ordering a ride, reading what is on the screen, replying in a chat app, changing a setting, playing a specific song in Spotify, taking a photo, etc. Jarvis will tap, type and scroll by itself.

RULES: Do EXACTLY what the user asked — nothing more, nothing less, no extra steps, no questions back when the request is clear. Prefer a direct action over screen_task whenever one fits (it is much faster). If the user said to send, send; if not, don't. Message text: use the user's words as the message (in the language/script they used), without adding greetings or names. Use an action only when the user wants something done; when you act, "say" briefly confirms it (e.g. "Opening YouTube, Sir."). Never use screen_task to pay money, buy things, transfer funds, or delete accounts/data — tell the user to do that part themselves.
""".trimIndent()
    }

    /** Tiny offline fallback so a few things work even without an API key. */
    private fun offline(text: String, punjabiMode: Boolean): Reply? {
        val t = text.lowercase(Locale.ROOT)
        fun has(vararg words: String) = words.any { t.contains(it) }
        val torchWords = arrayOf("torch", "flash", "ਟਾਰਚ", "ਫਲੈਸ਼")
        return when {
            has(*torchWords) && has("off", "band", "ਬੰਦ", "bujha", "ਬੁਝਾ") ->
                Reply(if (punjabiMode) "ਟਾਰਚ ਬੰਦ।" else "Torch off.", JSONObject().put("type", "torch").put("on", false))
            has(*torchWords) ->
                Reply(if (punjabiMode) "ਟਾਰਚ ਚਾਲੂ।" else "Torch on.", JSONObject().put("type", "torch").put("on", true))
            has("time", "ਟਾਈਮ", "ਸਮਾਂ", "ਵਕਤ", "kinne vaje", "ਕਿੰਨੇ ਵਜੇ") -> {
                val now = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
                Reply(if (punjabiMode) "ਹੁਣ $now ਹੋਏ ਹਨ।" else "It's $now, Sir.", null)
            }
            has("youtube", "ਯੂਟਿਊਬ") ->
                Reply(if (punjabiMode) "ਯੂਟਿਊਬ ਖੋਲ੍ਹ ਰਿਹਾ ਹਾਂ।" else "Opening YouTube.", JSONObject().put("type", "open_app").put("name", "YouTube"))
            else -> null
        }
    }
}

/** Long-term memory, saved on the phone. */
object Memory {
    private const val KEY = "memory"
    private const val MAX = 80

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun all(c: Context): List<String> = try {
        val arr = JSONArray(prefs(c).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, list: List<String>) {
        val arr = JSONArray()
        list.takeLast(MAX).forEach { arr.put(it) }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(c: Context, fact: String) {
        val f = fact.trim()
        if (f.isEmpty()) return
        val list = all(c).toMutableList()
        if (list.any { it.equals(f, ignoreCase = true) }) return
        list.add(f)
        save(c, list)
    }

    fun forget(c: Context, fact: String) {
        val f = fact.trim()
        if (f.isEmpty()) return
        save(c, all(c).filterNot { it.equals(f, ignoreCase = true) || it.contains(f, ignoreCase = true) })
    }

    fun clear(c: Context) = prefs(c).edit().remove(KEY).apply()
}
