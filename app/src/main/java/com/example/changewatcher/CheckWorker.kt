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
        // Las revisiones manuales ("Revisar ahora") se hacen aunque el monitoreo esté desactivado
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val time = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es-PY")).format(Date())

        if (!manual && !cfg.enabled) return Result.success()
        if (cfg.departments.isEmpty()) {
            if (manual) prefs.lastStatus = "Revisión del $time: selecciona al menos un departamento"
            return Result.success()
        }

        return try {
            val body = withContext(Dispatchers.IO) { fetch(PAGE_URL) }
            val fullText = withContext(Dispatchers.Default) { SnapshotStore.htmlToText(body) }

            val hasAlert = SnapshotStore.hasMarker(fullText, SAVE_FROM)
            // Solo se conserva la parte del aviso: desde el título hasta la lista de departamentos
            val section = if (hasAlert) SnapshotStore.extractSection(fullText, SAVE_FROM, SAVE_UNTIL) else ""
            val departmentsText = if (hasAlert) SnapshotStore.departmentsPart(section) else null
            val affected = if (departmentsText != null) {
                Departments.findAffected(departmentsText, cfg.departments)
            } else {
                emptyList()
            }
            val met = affected.isNotEmpty()

            // Las revisiones programadas avisan solo si el aviso es nuevo o cambió.
            // Las manuales avisan siempre que haya coincidencia (útil para probar).
            val alertHash = section.hashCode().toString()
            val shouldNotify = met && (manual || alertHash != prefs.lastAlertHash)

            var snapshotName = ""
            var notified = true
            if (shouldNotify) {
                snapshotName = SnapshotStore.saveText(applicationContext, section)
                prefs.lastSnapshot = snapshotName
                prefs.lastSnapshotUrl = PAGE_URL
                notified = notifyUser(affected, snapshotName)
            }
            prefs.lastAlertHash = if (met) alertHash else ""

            var status = when {
                !hasAlert -> "Revisión del $time: no hay ningún aviso en la página"
                !met -> "Revisión del $time: hay un aviso, pero no incluye los departamentos elegidos"
                else -> "Revisión del $time: aviso para " + affected.joinToString(", ") { it.label }
            }
            if (met && !shouldNotify) status += " (ya se notificó)"
            if (snapshotName.isNotEmpty()) status += " - aviso guardado"
            if (shouldNotify && !notified) {
                status += " - notificación bloqueada: permite las notificaciones de esta app en los ajustes de Android"
            }
            prefs.lastStatus = status
            Result.success()
        } catch (e: Exception) {
            prefs.lastStatus = "Revisión del $time falló: ${e.message ?: e.javaClass.simpleName}"
            if (manual) Result.failure() else Result.retry()
        }
    }

    private fun fetch(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "AvisosMeteorologicos/1.0")
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Devuelve true si se mostró la notificación, false si no hay permiso para mostrarla. */
    private fun notifyUser(affected: List<Department>, snapshotName: String): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Avisos meteorológicos", NotificationManager.IMPORTANCE_HIGH
            )
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // Al tocar la notificación se abre el texto guardado del aviso
        val openSnapshot = Intent(ctx, SnapshotActivity::class.java).apply {
            putExtra(SnapshotActivity.EXTRA_FILE, snapshotName)
            putExtra(SnapshotActivity.EXTRA_URL, PAGE_URL)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            System.currentTimeMillis().toInt(), // código único para que cada notificación conserve su propio aviso
            openSnapshot,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val names = affected.joinToString(", ") { it.label }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aviso meteorológico")
            .setContentText("Departamentos: $names")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("El aviso incluye: $names. Toca para ver el texto guardado.")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification)
        return true
    }

    companion object {
        const val CHANNEL_ID = "avisos_meteorologicos"
        const val NOTIF_ID = 1001
        const val KEY_MANUAL = "manual"
    }
}
