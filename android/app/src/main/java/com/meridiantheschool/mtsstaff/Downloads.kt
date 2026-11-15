package com.meridiantheschool.mtsstaff

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Puts files into the phone's public Downloads folder. */
object Downloads {

    class Saved(val name: String, val uri: Uri)

    /** Android 9 and older need the storage permission to write to Downloads. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun save(context: Context, name: String, mime: String, bytes: ByteArray): Saved? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // Android may have renamed it to "name (1).xlsx"
            val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: name
            Saved(finalName, uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            var file = File(dir, name)
            var n = 1
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            while (file.exists()) file = File(dir, "$base (${n++})$ext")
            file.writeBytes(bytes)
            Saved(file.name, FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        }
    } catch (e: Exception) {
        null
    }

    /** Normal web downloads (a link to a file on the internet). */
    fun enqueue(context: Context, url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(context, context.getString(R.string.saved_to_downloads, name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
