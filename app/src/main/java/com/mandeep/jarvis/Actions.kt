package com.mandeep.jarvis

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Locale

/**
 * Result of running an action.
 * message  = extra thing to say (usually a problem), or null
 * followUp = data the brain should turn into an answer (e.g. notifications)
 * task     = a goal for the on-screen agent (Jarvis taps/types by itself)
 * confirm  = this action needs a yes from the user first; text describes it
 * pressSend = a chat was opened with the message typed; screen control should tap Send
 */
data class ActionResult(
    val message: String? = null,
    val followUp: String? = null,
    val task: String? = null,
    val confirm: String? = null,
    val pressSend: Boolean = false,
)

/** Carries out the phone action the brain asked for. */
class Actions(private val activity: Context) {

    private fun p(pa: Boolean, punjabi: String, english: String) = if (pa) punjabi else english

    fun run(action: JSONObject?, pa: Boolean, confirmed: Boolean = false): ActionResult {
        if (action == null) return ActionResult()
        return try {
            when (action.optString("type", "none")) {
                "open_app" -> msg(openApp(action.optString("name"), pa))
                "youtube" -> msg(youtube(action.optString("query")))
                "web_search" -> msg(webSearch(action.optString("query")))
                "maps" -> msg(view("geo:0,0?q=" + Uri.encode(action.optString("query"))))
                "dial", "call" -> msg(call(action, pa))
                "sms" -> sms(action, pa, confirmed)
                "whatsapp_to" -> whatsappTo(action, pa, confirmed)
                "whatsapp_message" -> msg(whatsappShare(action.optString("text")))
                "alarm" -> msg(alarm(action.optInt("hour", 7), action.optInt("minute", 0), action.optString("label", "")))
                "timer" -> msg(timer(action.optInt("seconds", 60)))
                "torch" -> msg(torch(action.optBoolean("on", true), pa))
                "media" -> msg(media(action.optString("cmd", "play")))
                "volume" -> msg(volume(action))
                "brightness" -> msg(brightness(action.optInt("level", 50), pa))
                "ringer" -> msg(ringer(action.optString("mode", "normal"), pa))
                "global" -> msg(global(action.optString("cmd"), pa))
                "read_notifications" -> notifications(pa)
                "settings" -> msg(settings(action.optString("page", "main")))
                "screen_task" -> screenTask(action)
                "set_language" -> {
                    val toPa = action.optString("lang").lowercase(Locale.ROOT).startsWith("pa")
                    activity.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("punjabi", toPa).apply()
                    ActionResult()
                }
                "play_music" -> msg(playMusic(action.optString("query"), action.optString("app")))
                "navigate" -> msg(navigate(action.optString("query")))
                else -> ActionResult()
            }
        } catch (e: ActivityNotFoundException) {
            ActionResult(p(pa, "ਇਹ ਕੰਮ ਕਰਨ ਵਾਲੀ ਐਪ ਫ਼ੋਨ ਵਿੱਚ ਨਹੀਂ ਲੱਭੀ।", "I couldn't find an app for that."))
        } catch (e: SecurityException) {
            ActionResult(p(pa, "ਇਸ ਕੰਮ ਲਈ ਪਰਮਿਸ਼ਨ ਚਾਹੀਦੀ ਹੈ — ⚙ ਵਿੱਚ ਜਾ ਕੇ ਦਿਓ।", "I need a permission for that — open ⚙ to grant it."))
        } catch (e: Exception) {
            ActionResult(p(pa, "ਮਾਫ਼ ਕਰਨਾ, ਇਹ ਕੰਮ ਨਹੀਂ ਹੋ ਸਕਿਆ।", "Sorry, that didn't work."))
        }
    }

    private fun msg(s: String?) = ActionResult(message = s)

    private fun start(intent: Intent) {
        // From the floating mini panel (not an Activity) a new task is needed.
        if (activity !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    private fun view(uri: String): String? {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        return null
    }

    private fun granted(perm: String) =
        activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ apps

    private fun openApp(name: String, pa: Boolean): String? {
        val wanted = norm(name)
        if (wanted.isEmpty()) return null
        val pm = activity.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to norm(it.loadLabel(pm).toString()) }

        val best = apps.firstOrNull { it.second == wanted }
            ?: apps.firstOrNull { it.second.startsWith(wanted) }
            ?: apps.firstOrNull { it.second.contains(wanted) }
            ?: apps.firstOrNull { it.second.isNotEmpty() && wanted.contains(it.second) }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).contains(wanted) }

        val intent = best?.let { pm.getLaunchIntentForPackage(it.first) }
            ?: return p(pa, "\"$name\" ਐਪ ਫ਼ੋਨ ਵਿੱਚ ਨਹੀਂ ਲੱਭੀ।", "I couldn't find \"$name\" on your phone.")
        start(intent)
        return null
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * Instant path for "open X" / "X ਖੋਲ੍ਹ" — opens the app right away without asking the AI.
     * Returns what to say, or null when the sentence isn't a plain "open app" request.
     */
    fun quickOpen(text: String, pa: Boolean): String? {
        var t = " " + text.lowercase(Locale.ROOT).replace(Regex("[.,!?।]"), " ") + " "
        val verbs = listOf(
            "open", "launch", "start", "kholo", "khol de", "kholde", "khol", "chalao", "chala",
            "ਖੋਲ੍ਹ ਦੇ", "ਖੋਲ੍ਹੋ", "ਖੋਲ੍ਹ", "ਖੋਲੋ", "ਖੋਲ", "ਚਲਾਓ", "ਚਲਾ", "खोल दो", "खोल दे", "खोलो", "खोल", "चलाओ"
        )
        val verb = verbs.firstOrNull { t.contains(" $it ") } ?: return null
        t = t.replace(" $verb ", " ")
        for (f in listOf("app", "ਐਪ", "ऐप", "एप", "please", "ji", "ਜੀ", "जी", "the", "nu", "ਨੂੰ", "को", "de", "ਦੇ", "do", "my", "mera", "ਮੇਰਾ", "singh", "ai", "ਸਿੰਘ"))
            t = t.replace(" $f ", " ")
        var name = t.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty() || name.split(" ").size > 3) return null
        name = ALIASES[name] ?: ALIASES[name.replace(" ", "")] ?: name
        val wanted = norm(name)
        if (wanted.length < 2) return null
        val pm = activity.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to norm(it.loadLabel(pm).toString()) }
        val best = apps.firstOrNull { it.second == wanted }
            ?: apps.firstOrNull { it.second.startsWith(wanted) }
            ?: apps.firstOrNull { wanted.length >= 4 && it.second.contains(wanted) }
            ?: return null
        val i = pm.getLaunchIntentForPackage(best.first) ?: return null
        start(i)
        return p(pa, "ਖੋਲ੍ਹ ਰਿਹਾ ਹਾਂ।", "Opening it.")
    }

    companion object {
        /** Spoken app names (Punjabi / Hindi script) → the app's English name. */
        private val ALIASES = mapOf(
            "ਯੂਟਿਊਬ" to "youtube", "ਯੂ ਟਿਊਬ" to "youtube", "यूट्यूब" to "youtube",
            "ਵਟਸਐਪ" to "whatsapp", "ਵਹਟਸਐਪ" to "whatsapp", "ਵਟਸ ਐਪ" to "whatsapp", "व्हाट्सएप" to "whatsapp",
            "ਇੰਸਟਾਗ੍ਰਾਮ" to "instagram", "ਇੰਸਟਾ" to "instagram", "इंस्टाग्राम" to "instagram",
            "ਫੇਸਬੁੱਕ" to "facebook", "ਫੇਸਬੁਕ" to "facebook", "फेसबुक" to "facebook",
            "ਕੈਮਰਾ" to "camera", "कैमरा" to "camera", "ਗੈਲਰੀ" to "gallery", "गैलरी" to "gallery",
            "ਸੈਟਿੰਗ" to "settings", "ਸੈਟਿੰਗਜ਼" to "settings", "ਸੈਟਿੰਗਸ" to "settings", "सेटिंग" to "settings",
            "ਕਰੋਮ" to "chrome", "क्रोम" to "chrome", "ਮੈਪ" to "maps", "ਮੈਪਸ" to "maps", "मैप" to "maps",
            "ਸਪੋਟੀਫਾਈ" to "spotify", "ਟਿਕਟੌਕ" to "tiktok", "ਟਿਕ ਟੌਕ" to "tiktok", "ਸਨੈਪਚੈਟ" to "snapchat",
            "ਸਨੈਪ" to "snapchat", "ਜੀਮੇਲ" to "gmail", "ਨੈੱਟਫਲਿਕਸ" to "netflix", "ਨੈਟਫਲਿਕਸ" to "netflix",
            "ਕੈਲਕੁਲੇਟਰ" to "calculator", "ਘੜੀ" to "clock", "ਫ਼ੋਨ" to "phone", "ਫੋਨ" to "phone",
            "ਮੈਸੇਜ" to "messages", "ਮੈਸੇਜਸ" to "messages", "ਟੈਲੀਗ੍ਰਾਮ" to "telegram", "ਟੈਲੀਗਰਾਮ" to "telegram",
            "ਐਮਾਜ਼ਾਨ" to "amazon", "ਪਲੇ ਸਟੋਰ" to "play store", "ਪਲੇਸਟੋਰ" to "play store"
        )
    }

    private fun youtube(query: String): String? {
        try {
            start(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query))
        } catch (e: ActivityNotFoundException) {
            view("https://www.youtube.com/results?search_query=" + Uri.encode(query))
        }
        return null
    }

    private fun webSearch(query: String): String? {
        try {
            start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
        } catch (e: ActivityNotFoundException) {
            view("https://www.google.com/search?q=" + Uri.encode(query))
        }
        return null
    }

    // -------------------------------------------------------------- contacts

    /** Finds the best matching contact: (name, number), or null. */
    private fun findContact(name: String): Pair<String, String>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val wanted = norm(name)
        if (wanted.isEmpty()) return null
        val words = name.lowercase(Locale.ROOT).split(" ").map { norm(it) }.filter { it.length > 1 }
        var best: Pair<String, String>? = null
        var bestScore = 0
        activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val display = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                val n = norm(display)
                val score = when {
                    n == wanted -> 100
                    n.startsWith(wanted) -> 60
                    n.contains(wanted) -> 40
                    words.isNotEmpty() && words.all { n.contains(it) } -> 30
                    words.any { n.startsWith(it) } -> 10
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = display to number
                }
            }
        }
        return best
    }

    /** (display name, number) from an action with "number" or "contact". */
    private fun target(action: JSONObject): Pair<String, String>? {
        val number = action.optString("number").filter { it.isDigit() || it == '+' }
        if (number.length >= 3) return number to number
        return findContact(action.optString("contact"))
    }

    private fun noContact(action: JSONObject, pa: Boolean): String {
        val who = action.optString("contact")
        return if (!granted(Manifest.permission.READ_CONTACTS))
            p(pa, "ਸੰਪਰਕ ਦੇਖਣ ਦੀ ਪਰਮਿਸ਼ਨ ਨਹੀਂ — ⚙ ਵਿੱਚ ਜਾ ਕੇ ਦਿਓ।", "I can't see your contacts yet — grant it in ⚙.")
        else p(pa, "\"$who\" ਨਾਮ ਦਾ ਸੰਪਰਕ ਨਹੀਂ ਲੱਭਿਆ।", "I couldn't find a contact called \"$who\".")
    }

    private fun call(action: JSONObject, pa: Boolean): String? {
        val (_, number) = target(action) ?: return noContact(action, pa)
        val uri = Uri.parse("tel:" + Uri.encode(number))
        start(Intent(if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        return null
    }

    private fun sms(action: JSONObject, pa: Boolean, confirmed: Boolean): ActionResult {
        val (who, number) = target(action) ?: return ActionResult(noContact(action, pa))
        val text = action.optString("text")
        val send = action.optBoolean("send", true)
        if (!send || !granted(Manifest.permission.SEND_SMS)) {
            // No permission: open the messages app with the text ready.
            start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", text))
            return ActionResult()
        }
        @Suppress("DEPRECATION")
        val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31)
            activity.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
        return ActionResult(message = p(pa, "$who ਨੂੰ SMS ਭੇਜ ਦਿੱਤਾ।", "SMS sent to $who."))
    }

    /**
     * Opens the WhatsApp chat straight away with the message already typed (no searching),
     * then screen control taps Send — only when the user asked to send.
     */
    private fun whatsappTo(action: JSONObject, pa: Boolean, @Suppress("UNUSED_PARAMETER") confirmed: Boolean): ActionResult {
        val text = action.optString("text")
        val send = action.optBoolean("send", true)
        val contactName = action.optString("contact")
        val found = target(action)
        val intl = found?.let { intlNumber(it.second) }.orEmpty()

        if (intl.length < 8) {
            // Contact/number not found → let screen control search inside WhatsApp.
            val who = found?.first ?: contactName
            if (JarvisAccess.instance == null) return ActionResult(noContact(action, pa))
            openApp("WhatsApp", pa)
            return ActionResult(
                task = "WhatsApp is open. Tap the search icon, search \"$who\", open that chat, " +
                    "type exactly: \"$text\"" + (if (send) " and tap Send." else ". Do NOT send.") + " Then finish."
            )
        }

        val link = "https://api.whatsapp.com/send?phone=$intl" +
            (if (text.isNotEmpty()) "&text=" + Uri.encode(text) else "")
        var opened = false
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b", null)) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                if (pkg != null) i.setPackage(pkg)
                start(i)
                opened = true
                break
            } catch (e: ActivityNotFoundException) {}
        }
        if (!opened) return ActionResult(p(pa, "WhatsApp ਨਹੀਂ ਲੱਭਿਆ।", "WhatsApp isn't installed."))
        if (!send || text.isEmpty()) return ActionResult()
        if (JarvisAccess.instance == null) return ActionResult(
            p(pa, "ਸੁਨੇਹਾ ਲਿਖ ਦਿੱਤਾ — Send ਦਬਾ ਦਿਓ। Screen Control ਚਾਲੂ ਕਰੋ ਤਾਂ ਮੈਂ ਆਪ ਭੇਜਾਂਗਾ।",
                "Message typed — tap Send. Turn on Screen Control and I'll send it myself.")
        )
        return ActionResult(pressSend = true)
    }

    /** Turns a saved number like "(559) 555-0123" or "098765 43210" into "15595550123" / "919876543210". */
    private fun intlNumber(raw: String): String {
        val plus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (plus) return d
        if (d.startsWith("00")) return d.drop(2)
        val cc = countryCode()
        if (d.startsWith("0")) d = d.trimStart('0')
        return when {
            cc == "1" && d.length == 11 && d.startsWith("1") -> d
            d.length <= 10 -> cc + d
            else -> d
        }
    }

    private fun countryCode(): String {
        val fromSim = try {
            val tm = activity.getSystemService(TelephonyManager::class.java)
            (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso ?: "").lowercase(Locale.ROOT)
        } catch (e: Exception) { "" }
        val iso = fromSim.ifBlank { Locale.getDefault().country.lowercase(Locale.ROOT) }
        return when (iso) {
            "in" -> "91"; "gb" -> "44"; "au" -> "61"; "nz" -> "64"; "ae" -> "971"; "pk" -> "92"
            "it" -> "39"; "de" -> "49"; "fr" -> "33"; "es" -> "34"
            else -> "1" // US / Canada
        }
    }

    /** Opens the right app first, so screen control starts in the right place. */
    private fun screenTask(action: JSONObject): ActionResult {
        val app = action.optString("app")
        if (app.isNotBlank()) openApp(app, false)
        return ActionResult(task = action.optString("goal"))
    }

    private fun playMusic(query: String, app: String): String? {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        val pkg = when (norm(app)) {
            "spotify" -> "com.spotify.music"
            "youtubemusic", "ytmusic" -> "com.google.android.apps.youtube.music"
            "youtube" -> return youtube(query)
            else -> null
        }
        if (pkg != null) i.setPackage(pkg)
        try { start(i) } catch (e: ActivityNotFoundException) { return youtube(query) }
        return null
    }

    private fun navigate(place: String): String? {
        try {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))))
        } catch (e: ActivityNotFoundException) {
            view("geo:0,0?q=" + Uri.encode(place))
        }
        return null
    }

    private fun whatsappShare(text: String): String? {
        start(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.whatsapp")
        )
        return null
    }

    // ------------------------------------------------------------- clock etc

    private fun alarm(hour: Int, minute: Int, label: String): String? {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        start(i)
        return null
    }

    private fun timer(seconds: Int): String? {
        start(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return null
    }

    private fun torch(on: Boolean, pa: Boolean): String? {
        val cm = activity.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return p(pa, "ਇਸ ਫ਼ੋਨ ਵਿੱਚ ਟਾਰਚ ਨਹੀਂ ਲੱਭੀ।", "No flashlight found.")
        cm.setTorchMode(id, on)
        return null
    }

    // ------------------------------------------------------- sound / screen

    private val audio get() = activity.getSystemService(AudioManager::class.java)

    private fun media(cmd: String): String? {
        val code = when (cmd) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return null
    }

    private fun volume(action: JSONObject): String? {
        val am = audio
        val stream = AudioManager.STREAM_MUSIC
        when (action.optString("change", "")) {
            "up" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> {
                val level = action.optInt("level", 50).coerceIn(0, 100)
                val max = am.getStreamMaxVolume(stream)
                am.setStreamVolume(stream, Math.round(max * level / 100f), AudioManager.FLAG_SHOW_UI)
            }
        }
        return null
    }

    private fun brightness(level: Int, pa: Boolean): String? {
        if (!Settings.System.canWrite(activity)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + activity.packageName)))
            return p(pa, "ਪਹਿਲਾਂ Singh AI ਨੂੰ ਸੈਟਿੰਗ ਬਦਲਣ ਦੀ ਇਜਾਜ਼ਤ ਦਿਓ, ਫਿਰ ਦੁਬਾਰਾ ਕਹੋ।", "Allow Singh AI to modify settings, then ask again.")
        }
        val cr = activity.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (level.coerceIn(0, 100) * 255 / 100).coerceAtLeast(5))
        return null
    }

    private fun ringer(mode: String, pa: Boolean): String? {
        return try {
            audio.ringerMode = if (mode == "vibrate") AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            null
        } catch (e: SecurityException) {
            p(pa, "Do Not Disturb ਦੀ ਪਰਮਿਸ਼ਨ ਨਹੀਂ।", "Do Not Disturb access is needed for that.")
        }
    }

    private fun global(cmd: String, pa: Boolean): String? {
        val svc = JarvisAccess.instance
            ?: return p(pa, "ਪਹਿਲਾਂ ⚙ ਵਿੱਚ 'ਸਕਰੀਨ ਕੰਟਰੋਲ' ਚਾਲੂ ਕਰੋ।", "Turn on Screen control in ⚙ first.")
        if (cmd == "home" || cmd == "lock" || cmd == "screenshot" || cmd == "notifications" ||
            cmd == "quick_settings" || cmd == "recents"
        ) (activity as? Activity)?.moveTaskToBack(true)
        svc.globalLater(cmd, 500)
        return null
    }

    private fun notifications(pa: Boolean): ActionResult {
        if (!JarvisNotifications.isEnabled(activity)) {
            start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return ActionResult(p(pa, "ਪਹਿਲਾਂ Singh AI ਨੂੰ ਨੋਟੀਫਿਕੇਸ਼ਨ ਪੜ੍ਹਨ ਦੀ ਇਜਾਜ਼ਤ ਦਿਓ।", "Allow Singh AI to read notifications first."))
        }
        val data = JarvisNotifications.recentText()
        return if (data.isBlank()) ActionResult(p(pa, "ਕੋਈ ਨਵਾਂ ਨੋਟੀਫਿਕੇਸ਼ਨ ਨਹੀਂ।", "No new notifications, Sir."))
        else ActionResult(followUp = data)
    }

    private fun settings(page: String): String? {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            else -> Settings.ACTION_SETTINGS
        }
        start(Intent(action))
        return null
    }
}
