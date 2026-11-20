package com.mandeep.jarvis

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything Singh AI learns by itself:
 * - Knowledge: answers it found on Google are saved, so the same question works again WITHOUT internet.
 */
object Knowledge {
    private const val KEY = "knowledge"
    private const val MAX = 400

    class Note(val q: String, val a: String, val src: String, val t: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("jarvis_knowledge", Context.MODE_PRIVATE)

    fun all(c: Context): List<Note> = try {
        val arr = JSONArray(prefs(c).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Note(o.optString("q"), o.optString("a"), o.optString("s"), o.optLong("t"))
        }
    } catch (e: Exception) { emptyList() }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

    @Synchronized
    fun save(c: Context, question: String, answer: String, sources: List<Pair<String, String>>) {
        if (question.isBlank() || answer.isBlank()) return
        val k = words(question)
        val list = all(c).filterNot { words(it.q) == k }.toMutableList()
        list.add(Note(question.trim(), answer.trim(), sources.take(3).joinToString(" · ") { it.first }, System.currentTimeMillis()))
        val arr = JSONArray()
        list.takeLast(MAX).forEach { arr.put(JSONObject().put("q", it.q).put("a", it.a).put("s", it.src).put("t", it.t)) }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** A saved answer to (almost) the same question, or null. */
    fun find(c: Context, question: String): Note? {
        val k = words(question)
        if (k.isEmpty()) return null
        return all(c).map { n ->
            val w = words(n.q)
            val common = k.intersect(w).size.toFloat()
            n to common / maxOf(k.size, w.size).coerceAtLeast(1)
        }.filter { it.second >= 0.6f }.maxByOrNull { it.second + it.first.t / 1e15f }?.first
    }

    fun count(c: Context) = all(c).size

    /** No internet: answer from what was found on Google before, if anything matches. */
    fun offlineAnswer(c: Context, question: String, pa: Boolean): String? {
        val n = find(c, question) ?: return null
        return (if (pa) "ਨੈੱਟ ਨਹੀਂ ਹੈ, ਪਰ ਪਹਿਲਾਂ Google ਤੋਂ ਸਿੱਖਿਆ ਸੀ: " else "No internet, but I learned this from Google before: ") + n.a
    }
    fun clear(c: Context) = prefs(c).edit().remove(KEY).apply()
}

/** Backup (memory, Google knowledge, playlist, settings — no chats, never the API key) as one zip. */
object Backup {
    private val PREF_FILES = listOf("jarvis", "jarvis_knowledge")
    private val SECRET = setOf("api_key")

    private fun prefsJson(c: Context, name: String): JSONObject {
        val o = JSONObject()
        for ((k, v) in c.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            if (k in SECRET || v == null) continue
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }
            o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.toList()).toString() else v))
        }
        return o
    }

    /** Question → answer pairs learned from Google, in the usual training format (one JSON per line). */
    private fun trainingJsonl(c: Context): String {
        val sb = StringBuilder()
        for (n in Knowledge.all(c)) {
            sb.append(JSONObject().put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", n.q))
                .put(JSONObject().put("role", "assistant").put("content", n.a))).toString()).append('\n')
        }
        return sb.toString()
    }

    private fun writeZip(c: Context, out: java.io.OutputStream) {
        ZipOutputStream(out).use { z ->
            for (name in PREF_FILES) {
                z.putNextEntry(ZipEntry("$name.json"))
                z.write(prefsJson(c, name).toString(1).toByteArray())
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("training.jsonl"))
            z.write(trainingJsonl(c).toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("README.txt"))
            z.write(("Singh AI backup\n- *.json: settings, memory, playlist, Google knowledge (no chats, no API key)\n" +
                "- training.jsonl: question/answer pairs learned from Google, ready to train your own AI (e.g. Gemma)\n" +
                "Restore: Settings → Backup → Restore from backup.\n").toByteArray())
            z.closeEntry()
        }
    }

    /** Saves the zip in Downloads/SinghAI. Returns where (or an error). */
    fun export(c: Context): String {
        val name = "SinghAI-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SinghAI")
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return "❌ Couldn't save"
                c.contentResolver.openOutputStream(uri)?.use { writeZip(c, it) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SinghAI").apply { mkdirs() }
                FileOutputStream(File(dir, name)).use { writeZip(c, it) }
            }
            c.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putLong("last_backup", System.currentTimeMillis()).apply()
            "Downloads/SinghAI/$name"
        } catch (e: Exception) {
            "❌ ${e.javaClass.simpleName}"
        }
    }

    /** Once a day, quietly in the background. */
    fun autoBackup(c: Context) {
        val p = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        if (!p.getBoolean("auto_backup", true)) return
        if (System.currentTimeMillis() - p.getLong("last_backup", 0) < 24 * 3600_000L) return
        if (Knowledge.count(c) == 0 && Memory.all(c).isEmpty()) return
        val app = c.applicationContext
        Thread { export(app) }.start()
    }

    /** Puts a backup zip back. Keeps the current API key. */
    fun restore(c: Context, uri: Uri): Boolean = try {
        c.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name.removeSuffix(".json")
                    if (e.name.endsWith(".json") && n in PREF_FILES) {
                        val o = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        val ed = c.getSharedPreferences(n, Context.MODE_PRIVATE).edit()
                        for (k in o.keys()) {
                            if (k in SECRET) continue
                            val item = o.getJSONObject(k)
                            when (item.optString("t")) {
                                "b" -> ed.putBoolean(k, item.optBoolean("v"))
                                "i" -> ed.putInt(k, item.optInt("v"))
                                "l" -> ed.putLong(k, item.optLong("v"))
                                "f" -> ed.putFloat(k, item.optDouble("v").toFloat())
                                else -> ed.putString(k, item.optString("v"))
                            }
                        }
                        ed.apply()
                    }
                    e = z.nextEntry
                }
            }
        }
        true
    } catch (e: Exception) { false }
}
