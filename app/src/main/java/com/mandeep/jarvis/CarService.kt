package com.mandeep.jarvis

import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.util.Locale

/**
 * Singh AI inside Android Auto (shows up as a "media" app on the car screen).
 *
 * - Tap 🔍 / the mic inside Singh AI on the car screen and say anything:
 *   "Sidhu Moose Wala de gaane", "call Papa", "navigate to Walmart"…
 * - Or say "Hey Google, play Diljit on Singh AI".
 * The words go to Singh AI's brain (Gemini), which does the job on the phone.
 * Songs are played in YouTube Music (it shows on the car screen), or Spotify / YouTube.
 */
class CarService : MediaBrowserService(), TextToSpeech.OnInitListener {

    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val punjabi get() = prefs.getBoolean("punjabi", true)

    /** Quick buttons shown on the car screen (title to what to play). */
    private val shortcuts = listOf(
        "Sidhu Moose Wala" to "Sidhu Moose Wala songs",
        "Diljit Dosanjh" to "Diljit Dosanjh songs",
        "Karan Aujla" to "Karan Aujla songs",
        "AP Dhillon" to "AP Dhillon songs",
        "Punjabi Hits" to "latest Punjabi hit songs",
        "Gurbani" to "Gurbani kirtan live",
    )

    companion object {
        /** The running car-screen service (null when Android Auto isn't showing Singh AI). */
        @Volatile var instance: CarService? = null
    }

    fun post(r: () -> Unit) { main.post(r) }

    /** Shows Singh AI's words on the car screen (used by the side-key assistant too). */
    fun showText(title: String, text: String) = show(title, text)

    override fun onCreate() {
        super.onCreate()
        instance = this
        tts = TextToSpeech(this, this)
        session = MediaSession(this, "SinghAI").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlayFromSearch(query: String?, extras: Bundle?) = handle(query.orEmpty())
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    val id = mediaId.orEmpty()
                    when {
                        id.startsWith("q:") -> handle(id.removePrefix("q:"))
                        id.startsWith("s:") -> playSong(id.removePrefix("s:"), id.removePrefix("s:"))
                    }
                }
                override fun onPlay() = show("Singh AI", say("ਗਾਣੇ ਦਾ ਨਾਂ ਬੋਲੋ ਜਾਂ ਹੇਠੋਂ ਚੁਣੋ", "Say a song, or pick one below"))
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        show("Singh AI", say("ਮਾਈਕ ਦਬਾ ਕੇ ਬੋਲੋ", "Tap the mic and speak"))
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        session.release()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- car screen lists

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val extras = Bundle().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }
        return BrowserRoot("root", extras)
    }

    override fun onLoadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != "root") { result.sendResult(mutableListOf()); return }
        result.sendResult(shortcuts.map { (title, q) -> item("s:$q", "▶ $title", "YouTube Music") }.toMutableList())
    }

    private fun item(id: String, title: String, subtitle: String) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaBrowser.MediaItem.FLAG_PLAYABLE
    )

    // ---------------------------------------------------------------- the brain

    private fun say(pa: String, en: String) = if (punjabi) pa else en

    /** Sends what the user said to Singh AI's brain and does what it asks. */
    private fun handle(text: String) {
        if (text.isBlank()) return
        show(text, say("ਸੋਚ ਰਿਹਾ ਹਾਂ…", "Thinking…"))
        Thread {
            val reply = try {
                Brain(this).ask(text, punjabi, false, "The user is DRIVING, using Android Auto on the car screen. " +
                    "For songs prefer play_music (it plays in YouTube Music on the car screen). Keep answers very short.")
            } catch (e: Exception) {
                main.post { speakAndShow(text, Gemini.friendlyError(e, punjabi)) }
                return@Thread
            }
            main.post {
                val a = reply.action
                val type = a?.optString("type").orEmpty()
                if (type == "play_music" || type == "youtube") {
                    playSong(a!!.optString("query", text), reply.say.ifBlank { text })
                    return@post
                }
                val res = try { Actions(this).run(a, punjabi, confirmed = true) } catch (e: Exception) { ActionResult() }
                speakAndShow(text, listOfNotNull(reply.say.ifBlank { null }, res.message).joinToString(" "))
            }
        }.start()
    }

    /** Plays a song in YouTube Music (shows in Android Auto), else Spotify, else YouTube. */
    private fun playSong(query: String, title: String) {
        show(title, say("ਗਾਣਾ ਲਾ ਰਿਹਾ ਹਾਂ…", "Playing…"))
        for (pkg in listOf("com.google.android.apps.youtube.music", "com.spotify.music")) {
            try {
                startActivity(
                    Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .setPackage(pkg)
                        .putExtra(android.app.SearchManager.QUERY, query)
                        .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (e: Exception) { /* try the next app */ }
        }
        try {
            Actions(this).run(JSONObject().put("type", "youtube").put("query", query), punjabi, confirmed = true)
        } catch (e: Exception) {
            show(title, say("ਗਾਣਾ ਨਹੀਂ ਲੱਗ ਸਕਿਆ", "Couldn't play that"))
        }
    }

    private fun speakAndShow(title: String, text: String) {
        show(title, text)
        if (ttsReady && text.isNotBlank()) {
            tts?.language = if (punjabi) Locale("pa", "IN") else Locale.ENGLISH
            tts?.setAudioAttributes(CarAudio.voiceAttributes(this))
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "car")
        }
    }

    /** Shows text on the car's "now playing" screen. */
    private fun show(title: String, subtitle: String) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_FROM_SEARCH or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                .build()
        )
    }
}
