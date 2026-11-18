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

    private val history = ArrayList<Pair<String, String>>() // role ("user"/"model") to text

    val apiKey: String get() = Gemini.apiKey(context)

    fun clearHistory() = history.clear()

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
        while (history.size > 16) history.removeAt(0)
        val contents = JSONArray()
        for ((role, msg) in history) contents.put(Gemini.part(role, msg))

        val out = try {
            Gemini.generate(context, systemPrompt(punjabiMode, punjabiVoice, phoneInfo), contents, json = true)
        } catch (e: Exception) {
            history.removeAt(history.size - 1)
            throw e
        }
        history.add("model" to out)
        val o = Gemini.parseJson(out) ?: return Reply(out.trim(), null)
        return Reply(o.optString("say", ""), o.optJSONObject("action"))
    }

    /** Second step for actions that fetch data (e.g. notifications): turn data into a spoken answer. */
    fun summarize(question: String, data: String, punjabiMode: Boolean, punjabiVoice: Boolean): String {
        val system = "You are JARVIS, the user's phone assistant. Answer the user's request using the DATA below. " +
            "Reply in plain spoken sentences (no markdown, no lists), max 5 sentences. " +
            langRule(punjabiMode, punjabiVoice)
        val contents = JSONArray().put(Gemini.part("user", "REQUEST: $question\n\nDATA:\n$data"))
        val out = Gemini.generate(context, system, contents, json = false).trim()
        history.add("model" to JSONObject().put("say", out).put("action", JSONObject().put("type", "none")).toString())
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
You are JARVIS, a witty, calm, loyal personal AI assistant (in the spirit of Iron Man's assistant) living inside the user's Samsung Galaxy S26 Android phone. You can operate the whole phone. Call the user "Sir" now and then.

LANGUAGE: ${langRule(punjabiMode, punjabiVoice)}

STYLE: "say" is spoken aloud by text-to-speech: keep it short (1-3 sentences), natural, no markdown, no emojis, no lists. Longer only when the user asks for detail.

PHONE STATUS: Current time: $now. $phoneInfo

OUTPUT: ONLY a JSON object: {"say": "...", "action": {...}}. Pick the most direct action:
{"type":"none"}  -> just talking / answering a question
{"type":"open_app","name":"<app name in English>"}
{"type":"youtube","query":"..."}
{"type":"web_search","query":"..."}
{"type":"maps","query":"<place>"}
{"type":"call","contact":"<contact name in English letters as likely saved, e.g. Mom, Papa, Harpreet>"} or {"type":"call","number":"<digits>"}
{"type":"sms","contact":"...","text":"..."} (or "number" instead of "contact")
{"type":"whatsapp_to","contact":"...","text":"..."} (or "number") -> sends a WhatsApp message to that person
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
{"type":"screen_task","goal":"<clear step-by-step goal in English>"} -> for ANYTHING else that needs using apps on screen: turning wifi/bluetooth/hotspot/airplane mode on or off, posting, liking, searching inside an app, ordering a ride, reading what is on the screen, replying in a chat app, changing a setting, playing a specific song in Spotify, taking a photo, etc. Jarvis will tap, type and scroll by itself.

RULES: Use an action only when the user wants something done; when you act, "say" briefly confirms it (e.g. "Opening YouTube, Sir."). Never use screen_task to pay money, buy things, transfer funds, or delete accounts/data — tell the user to do that part themselves.
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
