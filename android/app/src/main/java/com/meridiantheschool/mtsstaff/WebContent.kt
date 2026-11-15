package com.meridiantheschool.mtsstaff

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves the web app's own files (page, app code, Supabase library, icons) from inside the phone
 * instead of downloading them every time, so the app opens at once, even without internet.
 * Only school data comes from the internet (Supabase).
 *
 * The files are packed in the APK (assets/web/...). When the app opens, it quietly checks the live
 * website; if a newer version has been published, it downloads it in the background and uses it from
 * the next time the app is opened. So website updates still reach the app without a new APK.
 */
object WebContent {

    /** Paths below APP_SCOPE that are served from the phone. Everything else goes to the internet as usual. */
    private val FILES = setOf(
        "beta/index.html",
        "beta/app.js.gz",
        "beta/supabase.js.gz",
        "beta/manifest.webmanifest",
        "icon-192.png",
        "icon-512.png",
    )
    private const val INDEX = "beta/index.html"
    private const val CHECK_EVERY_MS = 15 * 60 * 1000L

    private lateinit var appContext: Context
    @Volatile private var liveDir: File? = null
    private val checking = AtomicBoolean(false)

    private val root get() = File(appContext.filesDir, "web")
    private val prefs get() = appContext.getSharedPreferences("mts", Context.MODE_PRIVATE)

    fun init(context: Context) {
        appContext = context.applicationContext
        val current = File(root, "current")
        // a newly installed APK carries its own (newer) copy: drop copies downloaded by an older APK
        if (prefs.getInt("webForApk", -1) != BuildConfig.VERSION_CODE) {
            current.deleteRecursively()
            prefs.edit().putInt("webForApk", BuildConfig.VERSION_CODE).remove("webChecked").apply()
        }
        liveDir = if (File(current, INDEX).isFile) current else null
    }

    private fun relPath(uri: Uri): String? {
        val s = uri.toString().substringBefore('#').substringBefore('?')
        if (!s.startsWith(BuildConfig.APP_SCOPE)) return null
        var rel = s.removePrefix(BuildConfig.APP_SCOPE)
        if (rel.isEmpty() || rel.endsWith("/")) rel += "index.html"
        return rel.takeIf { it in FILES }
    }

    private fun open(rel: String): InputStream? {
        liveDir?.let { dir ->
            val f = File(dir, rel)
            if (f.isFile) return f.inputStream()
        }
        return try { appContext.assets.open("web/$rel") } catch (e: IOException) { null }
    }

    /** Called by the WebView for every request; answers our own files from the phone. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val rel = relPath(request.url) ?: return null
        val stream = open(rel) ?: return null
        val (mime, encoding) = when {
            rel.endsWith(".html") -> "text/html" to "utf-8"
            rel.endsWith(".gz") -> "application/gzip" to null
            rel.endsWith(".webmanifest") -> "application/manifest+json" to "utf-8"
            rel.endsWith(".png") -> "image/png" to null
            else -> "application/octet-stream" to null
        }
        return WebResourceResponse(mime, encoding, 200, "OK", mapOf("Cache-Control" to "no-store"), stream)
    }

    /** Looks for a newer version of the website in the background (at most every 15 minutes). */
    fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("webChecked", 0) < CHECK_EVERY_MS) return
        if (!checking.compareAndSet(false, true)) return
        Thread {
            try { update(now) } catch (_: Exception) { } finally { checking.set(false) }
        }.start()
    }

    private fun update(ts: Long) {
        val index = download("${BuildConfig.APP_SCOPE}$INDEX?u=$ts") ?: return
        prefs.edit().putLong("webChecked", ts).apply()
        val currentIndex = open(INDEX)?.use { it.readBytes() }
        if (currentIndex != null && index.contentEquals(currentIndex)) return // already up to date

        val html = String(index, Charsets.UTF_8)
        if (!html.contains("<html", ignoreCase = true) || !html.contains("app.js.gz")) return
        val v = Regex("var V = '([^']+)'").find(html)?.groupValues?.get(1) ?: ts.toString()

        val staging = File(root, "staging").apply { deleteRecursively() }
        File(staging, INDEX).apply { parentFile?.mkdirs(); writeBytes(index) }
        for (rel in FILES) {
            if (rel == INDEX) continue
            val bytes = download("${BuildConfig.APP_SCOPE}$rel?v=$v&u=$ts") ?: return // keep the old copy
            if (rel.endsWith(".gz") && !(bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())) return
            File(staging, rel).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        }
        // swap in the complete new copy in one step
        val current = File(root, "current")
        val old = File(root, "old").apply { deleteRecursively() }
        if (current.exists()) current.renameTo(old)
        if (staging.renameTo(current)) liveDir = current else if (old.exists()) old.renameTo(current)
        old.deleteRecursively()
    }

    private fun download(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: IOException) {
            null
        } finally {
            c.disconnect()
        }
    }
}
