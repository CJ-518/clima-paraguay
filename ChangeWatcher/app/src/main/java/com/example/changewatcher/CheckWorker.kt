package com.example.changewatcher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val cfg = prefs.load()
        // Manual checks ("Check now") run even when monitoring is switched off
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val time = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())

        // Delete saved pages that are past the retention period
        SnapshotStore.cleanup(applicationContext, cfg.retentionDays)

        if (!manual && !cfg.enabled) return Result.success()
        if (cfg.url.isBlank() || cfg.text.isBlank()) {
            if (manual) prefs.lastStatus = "Last check $time: enter a URL and text first"
            return Result.success()
        }

        return try {
            val body = withContext(Dispatchers.IO) { fetch(cfg.url) }
            val found = body.contains(cfg.text, ignoreCase = cfg.ignoreCase)
            val met = when (cfg.mode) {
                Mode.APPEARS -> found
                Mode.DISAPPEARS -> !found
            }

            // Scheduled checks notify only on a "not met" -> "met" transition.
            // Manual checks notify whenever the condition is met (handy for testing).
            val shouldNotify = met && (manual || !prefs.lastConditionMet)

            var snapshotName = ""
            var notified = true
            if (shouldNotify) {
                // Save only the part of the page the user cares about, as plain text
                val fullText = withContext(Dispatchers.Default) { SnapshotStore.htmlToText(body) }
                val section = SnapshotStore.extractSection(fullText, cfg.saveFrom, cfg.saveUntil)
                snapshotName = SnapshotStore.saveText(applicationContext, section)
                prefs.lastSnapshot = snapshotName
                prefs.lastSnapshotUrl = cfg.url
                notified = notifyUser(cfg, snapshotName)
            }

            var status = "Last check $time: text " +
                (if (found) "present" else "not present") +
                " (condition " + (if (met) "MET" else "not met") + ")"
            if (snapshotName.isNotEmpty()) status += " - page text saved"
            if (shouldNotify && !notified) {
                status += " - notification blocked, allow notifications for this app in Android settings"
            }
            prefs.lastStatus = status
            prefs.lastConditionMet = met
            Result.success()
        } catch (e: Exception) {
            prefs.lastStatus = "Last check $time failed: ${e.message ?: e.javaClass.simpleName}"
            if (manual) Result.failure() else Result.retry()
        }
    }

    private fun fetch(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "ChangeWatcher/1.0")
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Returns true if the notification was posted, false if it was blocked by missing permission. */
    private fun notifyUser(cfg: WatchConfig, snapshotName: String): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Watcher alerts", NotificationManager.IMPORTANCE_HIGH
            )
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // Tapping the notification opens the saved text
        val openSnapshot = Intent(ctx, SnapshotActivity::class.java).apply {
            putExtra(SnapshotActivity.EXTRA_FILE, snapshotName)
            putExtra(SnapshotActivity.EXTRA_URL, cfg.url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            System.currentTimeMillis().toInt(), // unique request code so each notification keeps its own saved text
            openSnapshot,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val verb = if (cfg.mode == Mode.APPEARS) "appeared on" else "disappeared from"
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Watcher: condition met")
            .setContentText("\"${cfg.text}\" $verb the page - tap to view the saved text")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("\"${cfg.text}\" $verb ${cfg.url}. Tap to view the saved text.")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification)
        return true
    }

    companion object {
        const val CHANNEL_ID = "watcher_alerts"
        const val NOTIF_ID = 1001
        const val KEY_MANUAL = "manual"
    }
}
