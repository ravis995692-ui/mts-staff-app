package com.meridiantheschool.mtsstaff

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Phone notifications for period reminders, new arrangements and notices (sent by the web app). */
object Notifications {
    private const val CHANNEL = "alerts"
    private const val CHANNEL_FILES = "files"
    private var counter = 1000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FILES, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, body: String, tag: String) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(ContextCompat.getColor(context, R.color.navy))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        // same tag replaces the earlier alert, like the browser does
        val id = if (tag.isNotEmpty()) tag.hashCode() else counter++
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }

    fun cancel(context: Context, tag: String) {
        if (tag.isNotEmpty()) NotificationManagerCompat.from(context).cancel(tag.hashCode())
    }

    /** "Saved: report.xlsx — tap to open" */
    @SuppressLint("MissingPermission")
    fun fileSaved(context: Context, name: String, uri: Uri, mime: String) {
        if (!allowed(context)) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, name.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_FILES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(ContextCompat.getColor(context, R.color.navy))
            .setContentTitle(name)
            .setContentText(context.getString(R.string.saved_to_downloads, name))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(context).notify(("file:$name").hashCode(), n) } catch (_: SecurityException) { }
    }

    fun openSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        try { context.startActivity(intent) } catch (_: Exception) { }
    }
}
