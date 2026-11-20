package com.mandeep.jarvis

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Things Singh AI can do WITHOUT internet and WITHOUT asking the AI (so they're instant):
 * open apps, call people, torch, volume, pause/next song, and play songs —
 * on the internet from YouTube / YouTube Music, offline from the songs saved on the phone.
 */
object Offline {

    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) { true }

    fun audioPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun canReadSongs(c: Context) = c.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** Finds a song saved on the phone (title / artist / album). Empty query = any song. */
    fun findLocalSong(c: Context, query: String): Pair<Uri, String>? {
        if (!canReadSongs(c)) return null
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }.take(4)
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM)
        val sel = StringBuilder(MediaStore.Audio.Media.IS_MUSIC + " != 0")
        val args = ArrayList<String>()
        for (w in words) {
            sel.append(" AND (" + MediaStore.Audio.Media.TITLE + " LIKE ? OR " + MediaStore.Audio.Media.ARTIST + " LIKE ? OR " +
                MediaStore.Audio.Media.ALBUM + " LIKE ?)")
            repeat(3) { args.add("%$w%") }
        }
        return try {
            c.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel.toString(), args.toTypedArray(),
                if (words.isEmpty()) "RANDOM()" else MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )?.use { cur ->
                if (!cur.moveToFirst()) return@use null
                val id = cur.getLong(0)
                val title = cur.getString(1) ?: "song"
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id) to title
            }
        } catch (e: Exception) {
            // some phones don't allow RANDOM() — try plain order
            if (words.isEmpty()) try {
                c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel.toString(), null, null)?.use { cur ->
                    if (!cur.moveToFirst()) return@use null
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)) to (cur.getString(1) ?: "song")
                }
            } catch (e2: Exception) { null } else null
        }
    }

    fun playLocal(c: Context, uri: Uri): Boolean = try {
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) { false }

    // ------------------------------------------------------------------ understanding

    fun clean(text: String) = " " + text.lowercase().replace(Regex("[.,!?।|\"']"), " ").replace(Regex("\\s+"), " ").trim() + " "

    fun has(t: String, words: List<String>) = words.any { t.contains(" $it ") || (it.length > 4 && t.contains(it)) }

    val OFF = listOf("off", "band", "bandh", "band kar", "bujha", "ਬੰਦ", "ਬੁਝਾ", "बंद", "बुझा")
    val TORCH = listOf("torch", "flashlight", "flash light", "ਟਾਰਚ", "ਟੌਰਚ", "टॉर्च", "टार्च")
    val VOLUME = listOf("volume", "awaaz", "awaz", "aawaz", "ਆਵਾਜ਼", "ਅਵਾਜ਼", "ਆਵਾਜ", "ਵਾਲੀਅਮ", "आवाज़", "आवाज", "वॉल्यूम")
    val UP = listOf("up", "vadha", "badha", "uchi", "ucchi", "tez", "increase", "louder", "ਵਧਾ", "ਉੱਚੀ", "ਉੱਚਾ", "ਤੇਜ਼", "बढ़ा", "तेज")
    val DOWN = listOf("down", "ghata", "ghat", "holi", "hauli", "kam", "decrease", "lower", "ਘਟਾ", "ਘੱਟ", "ਹੌਲੀ", "घटा", "कम", "धीमी")
    val MUTE = listOf("mute", "ਮਿਊਟ", "म्यूट")
    val PAUSE = listOf("pause", "ruk", "rok", "rok de", "stop music", "stop song", "ਰੋਕ", "ਰੁਕ", "ਪੌਜ਼", "रोक", "रुक")
    val NEXT = listOf("next", "agla", "agla gaana", "skip", "ਅਗਲਾ", "अगला")
    val PREV = listOf("previous", "pichla", "pehla wala", "ਪਿਛਲਾ", "पिछला")
    val CALL = listOf("call", "phone kar", "phone la", "fon la", "ਕਾਲ", "ਫ਼ੋਨ ਲਾ", "ਫੋਨ ਲਾ", "ਫ਼ੋਨ ਕਰ", "ਫੋਨ ਕਰ", "कॉल", "फोन लगा", "फोन कर")
    val PLAY_VERBS = listOf(
        "play", "chala", "chalao", "chala de", "la", "laa", "la de", "laga", "lagao", "laga de", "suna", "sunao", "suna de", "baja", "bajao",
        "ਚਲਾ", "ਚਲਾਓ", "ਲਾ", "ਲਾਦੇ", "ਲਾ ਦੇ", "ਲਗਾ", "ਲਗਾਓ", "ਸੁਣਾ", "ਸੁਣਾਓ", "ਵਜਾ",
        "चला", "चलाओ", "लगा", "लगाओ", "सुना", "सुनाओ", "बजा", "बजाओ"
    )
    val SONG = listOf(
        "song", "songs", "gaana", "gana", "gaane", "gane", "geet", "music", "track",
        "ਗਾਣਾ", "ਗਾਣੇ", "ਗੀਤ", "ਗਾਨਾ", "ਮਿਊਜ਼ਿਕ", "गाना", "गाने", "गीत", "म्यूजिक"
    )
    val PLAYLIST = listOf("playlist", "play list", "ਪਲੇਲਿਸਟ", "ਪਲੇ ਲਿਸਟ", "प्लेलिस्ट", "mere gaane", "mere gane", "ਮੇਰੇ ਗਾਣੇ")
    private val FILLER = setOf(
        "play", "karo", "kar", "kardo", "de", "do", "dio", "diyo", "la", "laa", "lagao", "laga", "chala", "chalao", "suna", "sunao",
        "baja", "bajao", "on", "te", "pe", "par", "ch", "vich", "youtube", "music", "wala", "vala", "wali", "da", "di", "de", "ka", "ki",
        "mainu", "menu", "mujhe", "koi", "ek", "ik", "song", "songs", "gaana", "gana", "gaane", "gane", "geet", "please", "ji", "zara",
        "jara", "thoda", "a", "the", "me", "for", "some", "yt", "ytmusic", "spotify", "singh", "ai", "hun", "hune", "now",
        "ਚਲਾ", "ਚਲਾਓ", "ਲਾ", "ਲਾਦੇ", "ਦੇ", "ਲਗਾ", "ਲਗਾਓ", "ਸੁਣਾ", "ਸੁਣਾਓ", "ਵਜਾ", "ਤੇ", "ਉੱਤੇ", "ਵਿੱਚ", "ਯੂਟਿਊਬ", "ਮਿਊਜ਼ਿਕ",
        "ਵਾਲਾ", "ਵਾਲੀ", "ਦਾ", "ਦੀ", "ਮੈਨੂੰ", "ਕੋਈ", "ਇੱਕ", "ਗਾਣਾ", "ਗਾਣੇ", "ਗੀਤ", "ਗਾਨਾ", "ਜੀ", "ਜ਼ਰਾ", "ਹੁਣ",
        "चला", "चलाओ", "लगा", "लगाओ", "सुना", "सुनाओ", "बजा", "बजाओ", "दो", "दे", "पर", "में", "यूट्यूब", "वाला", "वाली",
        "का", "की", "के", "मुझे", "कोई", "एक", "गाना", "गाने", "गीत", "जी", "ज़रा"
    )

    /** Singers people usually ask for (any other name works through the AI too). */
    val SINGERS = listOf(
        "sidhu moose wala", "sidhu moose", "sidhu moosewala", "sidhu", "diljit dosanjh", "diljit", "karan aujla", "ap dhillon", "shubh",
        "babbu maan", "gurdas maan", "amrinder gill", "ammy virk", "jass manak", "guru randhawa", "arjan dhillon",
        "satinder sartaaj", "sartaaj", "kulwinder billa", "gippy grewal", "r nait", "bohemia", "imran khan", "garry sandhu",
        "hardy sandhu", "nimrat khaira", "jasmine sandlas", "prem dhillon", "gurnam bhullar", "jordan sandhu", "sukh e",
        "navaan sandhu", "cheema y", "chani nattan", "sultaan", "tegi pannu", "talwiinder", "arijit singh", "badshah",
        "yo yo honey singh", "honey singh", "raftaar", "b praak", "jassi gill", "mankirt aulakh", "gur sidhu", "nirvair pannu",
        "harrdy sandhu", "parmish verma", "jazzy b", "surjit bindrakhia", "kuldeep manak", "amar singh chamkila", "chamkila",
        "the weeknd", "drake", "taylor swift", "ed sheeran", "justin bieber", "eminem", "atif aslam", "neha kakkar",
        "ਸਿੱਧੂ ਮੂਸੇ ਵਾਲਾ", "ਸਿੱਧੂ", "ਦਿਲਜੀਤ", "ਕਰਨ ਔਜਲਾ", "ਬੱਬੂ ਮਾਨ", "ਗੁਰਦਾਸ ਮਾਨ", "ਅਮਰਿੰਦਰ ਗਿੱਲ", "ਐਮੀ ਵਿਰਕ", "ਸ਼ੁੱਭ", "ਸਰਤਾਜ"
    )

    /** "Diljit de gaane" → "diljit" (just a singer, no song name) — then play their latest songs. */
    fun singerOnly(q: String): String? {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return null
        val s = SINGERS.firstOrNull { t == it || t.removeSuffix(" new").removeSuffix(" latest").trim() == it } ?: return null
        return s
    }

    /** "Sidhu Moose Wala da gaana YouTube te la de" → "sidhu moose wala". */
    fun songQuery(t: String): String =
        t.trim().split(" ").filter { it.isNotBlank() && it !in FILLER }.joinToString(" ").trim()

    fun isSongRequest(t: String): Boolean {
        val verb = has(t, PLAY_VERBS)
        val song = has(t, SONG) || t.contains(" youtube ") || t.contains("ਯੂਟਿਊਬ") || t.contains("यूट्यूब")
        return (verb && song) || t.trimStart().startsWith("play ")
    }

    /** "Papa nu call kar" → "papa". */
    fun callName(t: String): String? {
        if (!has(t, CALL)) return null
        var s = t
        for (w in CALL + listOf("kar", "karo", "kar de", "kardo", "nu", "ਨੂੰ", "को", "de", "ਦੇ", "do", "dena", "please", "ji", "ਜੀ", "जी", "to", "ਕਰ", "ਕਰੋ", "ਕਰਦੇ", "करो", "कर"))
            s = s.replace(" $w ", " ")
        val name = s.trim().replace(Regex("\\s+"), " ")
        return name.takeIf { it.length in 2..30 && it.split(" ").size <= 3 }
    }
}
