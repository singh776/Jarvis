package com.mandeep.jarvis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Something the user showed Singh AI: a photo, a screenshot or a file. */
class Attachment(
    val bytes: ByteArray,
    val mime: String,
    val thumb: Bitmap?,
    val label: String
)

/** Loads photos / screenshots / files and shrinks them so they upload fast. */
object Attach {

    private const val MAX_SIDE = 1600
    private const val MAX_FILE = 8 * 1024 * 1024

    /** Reads an image or file the user picked. Returns null if it can't be read. */
    fun fromUri(ctx: Context, uri: Uri, label: String): Attachment? {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: guessMime(uri.toString())
        if (mime.startsWith("image/")) {
            val bmp = decodeScaled(ctx, uri) ?: return null
            return fromBitmap(bmp, label)
        }
        val bytes = cr.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        val supported = mime == "application/pdf" || mime.startsWith("text/")
        return Attachment(bytes, if (supported) mime else "text/plain", null, label)
    }

    fun fromFile(ctx: Context, path: String, label: String): Attachment? {
        val f = File(path)
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(path) ?: return null
        f.delete()
        return fromBitmap(scale(bmp), label)
    }

    fun fromBitmap(bmp: Bitmap, label: String): Attachment {
        val small = scale(bmp)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Attachment(out.toByteArray(), "image/jpeg", thumbOf(small), label)
    }

    private fun scale(bmp: Bitmap): Bitmap {
        val side = maxOf(bmp.width, bmp.height)
        if (side <= MAX_SIDE) return bmp
        val k = MAX_SIDE.toFloat() / side
        return Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
    }

    private fun thumbOf(bmp: Bitmap): Bitmap {
        val side = maxOf(bmp.width, bmp.height)
        val k = 200f / side
        return if (k >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
    }

    private fun decodeScaled(ctx: Context, uri: Uri): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return scale(bmp)
    }

    private fun guessMime(s: String): String {
        val l = s.lowercase()
        return when {
            l.endsWith(".pdf") -> "application/pdf"
            l.endsWith(".png") -> "image/png"
            l.endsWith(".jpg") || l.endsWith(".jpeg") -> "image/jpeg"
            l.endsWith(".webp") -> "image/webp"
            else -> "text/plain"
        }
    }
}
