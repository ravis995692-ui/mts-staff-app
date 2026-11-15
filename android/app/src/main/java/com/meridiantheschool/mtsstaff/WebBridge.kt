package com.meridiantheschool.mtsstaff

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Methods the web page can call as window.MTSNative.* (wired up by assets/bridge.js).
 * Calls arrive on a WebView background thread; anything touching the UI is posted to the main thread.
 */
class WebBridge(private val activity: MainActivity, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("mts", Context.MODE_PRIVATE)
    private val speech = Speech(activity, web)

    private fun resolve(id: String, jsonValue: String) = main.post {
        web.evaluateJavascript("window.__mtsResolve && window.__mtsResolve(${JSONObject.quote(id)}, $jsonValue)", null)
    }

    private fun toastNow(text: String) = main.post { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }

    fun shutdown() = speech.shutdown()

    @JavascriptInterface
    fun scope(): String = BuildConfig.APP_SCOPE

    @JavascriptInterface
    fun toast(key: String) {
        val res = when (key) {
            "save_failed" -> R.string.save_failed
            "copied" -> R.string.copied
            else -> return
        }
        toastNow(activity.getString(res))
    }

    /* ---------------- notifications ---------------- */

    @JavascriptInterface
    fun notifPermission(): String = when {
        Notifications.allowed(activity) -> "granted"
        Build.VERSION.SDK_INT < 33 -> "denied" // turned off by the user in Settings
        prefs.getBoolean("notifAsked", false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS) -> "denied"
        else -> "default"
    }

    @JavascriptInterface
    fun requestNotifPermission(id: String) = main.post {
        val now = notifPermission()
        when {
            now == "granted" -> resolve(id, "\"granted\"")
            Build.VERSION.SDK_INT >= 33 && now == "default" ->
                activity.askPermission(Manifest.permission.POST_NOTIFICATIONS) { granted ->
                    prefs.edit().putBoolean("notifAsked", true).apply()
                    resolve(id, JSONObject.quote(if (granted) "granted" else notifPermission()))
                }
            else -> {
                // blocked earlier: send the user to the app's notification settings
                Toast.makeText(activity, R.string.notif_blocked, Toast.LENGTH_LONG).show()
                Notifications.openSettings(activity)
                resolve(id, JSONObject.quote(now))
            }
        }
    }

    @JavascriptInterface
    fun showNotification(title: String, body: String, tag: String) = Notifications.show(activity, title, body, tag)

    @JavascriptInterface
    fun cancelNotify(tag: String) = Notifications.cancel(activity, tag)

    /* ---------------- files ---------------- */

    /** Saves a file the web app generated (Excel report etc.) into the phone's Downloads folder. */
    @JavascriptInterface
    fun saveFile(id: String, name: String, mime: String, base64: String, alsoShare: Boolean) {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: Exception) {
            toast("save_failed"); resolve(id, "false"); return
        }
        val doSave = {
            Thread {
                val saved = Downloads.save(activity, safeName(name), mime, bytes)
                if (saved != null) {
                    toastNow(activity.getString(R.string.saved_to_downloads, saved.name))
                    Notifications.fileSaved(activity, saved.name, saved.uri, mime)
                } else toast("save_failed")
                if (alsoShare) shareBytes(listOf(Triple(safeName(name), mime, bytes)), "", "")
                resolve(id, if (saved != null) "true" else "false")
            }.start()
        }
        if (Downloads.needsPermission(activity)) {
            main.post {
                activity.askPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) { granted ->
                    if (granted) doSave() else {
                        // no storage permission: still let the user send the file somewhere
                        Thread { shareBytes(listOf(Triple(safeName(name), mime, bytes)), "", ""); resolve(id, "false") }.start()
                    }
                }
            }
        } else doSave()
    }

    /** navigator.share({ files }) — saves a copy in Downloads and opens Android's share sheet (WhatsApp, Drive, Gmail …). */
    @JavascriptInterface
    fun shareFiles(id: String, filesJson: String, title: String, text: String) {
        val files = try {
            val arr = JSONArray(filesJson)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Triple(safeName(o.optString("name", "file")), o.optString("type", "application/octet-stream"),
                    Base64.decode(o.getString("data"), Base64.DEFAULT))
            }
        } catch (e: Exception) {
            toast("save_failed"); resolve(id, "false"); return
        }
        Thread {
            if (!Downloads.needsPermission(activity)) {
                files.forEach { (n, m, b) -> Downloads.save(activity, n, m, b) }
            }
            shareBytes(files, title, text)
            resolve(id, "true")
        }.start()
    }

    private fun shareBytes(files: List<Triple<String, String, ByteArray>>, title: String, text: String) {
        try {
            val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
            val uris = files.map { (name, _, bytes) ->
                val f = File(dir, name).apply { writeBytes(bytes) }
                FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
            }
            val mime = files.map { it.second }.distinct().singleOrNull() ?: "*/*"
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.type = mime
            if (title.isNotEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, title)
            if (text.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text)
            intent.clipData = ClipData.newUri(activity.contentResolver, files[0].first, uris[0]).also { cd ->
                uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            main.post { activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_with))) }
        } catch (e: Exception) {
            toast("save_failed")
        }
    }

    @JavascriptInterface
    fun shareText(title: String, text: String) = main.post {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, title)
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_with)))
    }

    /* ---------------- copy, print, links, theme ---------------- */

    @JavascriptInterface
    fun copy(text: String) = main.post {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MTS", text))
    }

    @JavascriptInterface
    fun print() = main.post {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val job = activity.getString(R.string.print_job)
        pm.print(job, web.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
    }

    @JavascriptInterface
    fun openExternal(url: String) = main.post { activity.openExternal(url.toUri()) }

    @JavascriptInterface
    fun setThemeColor(color: String) {
        val c = try { Color.parseColor(color.trim()) } catch (e: Exception) { return }
        main.post { activity.applyBarColor(c) }
    }

    /* ---------------- voice ---------------- */

    @JavascriptInterface
    fun ttsSpeak(text: String, lang: String, rate: Double) = speech.speak(text, lang, rate.toFloat())

    @JavascriptInterface
    fun ttsStop() = speech.stopSpeaking()

    @JavascriptInterface
    fun sttAvailable(): Boolean = speech.recognitionAvailable()

    @JavascriptInterface
    fun sttStart(id: String, lang: String, interim: Boolean) = main.post {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            speech.listen(id, lang, interim)
        } else {
            activity.askPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                if (granted) speech.listen(id, lang, interim)
                else {
                    Toast.makeText(activity, R.string.mic_needed, Toast.LENGTH_LONG).show()
                    speech.fail(id, "not-allowed")
                }
            }
        }
    }

    @JavascriptInterface
    fun sttStop() = main.post { speech.stopListening() }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "file" }.take(120)
}
