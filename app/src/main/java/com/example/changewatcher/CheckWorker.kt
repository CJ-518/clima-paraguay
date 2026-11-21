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

/*
 * CheckWorker.kt
 *
 * Es el "motor" de la app. WorkManager lo ejecuta en segundo plano, de forma periódica
 * (cuando el monitoreo está activado) o una sola vez (botón "Revisar ahora").
 *
 * Flujo de cada revisión:
 *  1. Lee los ajustes y decide si debe correr.
 *  2. Descarga la página de avisos y la convierte en texto.
 *  3. Si hay un aviso, recorta la parte relevante y busca los departamentos elegidos.
 *  4. Si hay coincidencia y el aviso es nuevo (o la revisión es manual), guarda el texto y notifica.
 *  5. Guarda un mensaje de estado que la pantalla principal muestra al usuario.
 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /**
     * Ejecuta una revisión completa. Devuelve:
     *  - success: la revisión terminó (haya o no avisos);
     *  - retry: falló una revisión programada, WorkManager la reintenta más tarde;
     *  - failure: falló una revisión manual, no se reintenta.
     */
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val cfg = prefs.load()
        // Las revisiones manuales ("Revisar ahora") se hacen aunque el monitoreo esté desactivado
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val time = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es-PY")).format(Date())

        // Condiciones para no hacer nada: monitoreo apagado (si no es manual) o sin departamentos elegidos
        if (!manual && !cfg.enabled) return Result.success()
        if (cfg.departments.isEmpty()) {
            if (manual) prefs.lastStatus = "Revisión del $time: selecciona al menos un departamento"
            return Result.success()
        }

        return try {
            // La descarga va en el hilo de red (IO) y la conversión de HTML en el hilo de cálculo (Default)
            val body = withContext(Dispatchers.IO) { fetch(PAGE_URL) }
            val fullText = withContext(Dispatchers.Default) { SnapshotStore.htmlToText(body) }

            // ¿La página tiene un aviso activo? Se sabe si aparece el título "Detalle de Aviso Meteorologico"
            val hasAlert = SnapshotStore.hasMarker(fullText, SAVE_FROM)
            // Solo se conserva la parte del aviso: desde el título hasta la lista de departamentos
            val section = if (hasAlert) SnapshotStore.extractSection(fullText, SAVE_FROM, SAVE_UNTIL) else ""
            val departmentsText = if (hasAlert) SnapshotStore.departmentsPart(section) else null
            // Departamentos elegidos por el usuario que aparecen en la lista de afectados
            val affected = if (departmentsText != null) {
                Departments.findAffected(departmentsText, cfg.departments)
            } else {
                emptyList()
            }
            val met = affected.isNotEmpty()

            // Las revisiones programadas avisan solo si el aviso es nuevo o cambió (se compara su hash).
            // Las manuales avisan siempre que haya coincidencia (útil para probar).
            val alertHash = section.hashCode().toString()
            val shouldNotify = met && (manual || alertHash != prefs.lastAlertHash)

            var snapshotName = ""
            var notified = true
            if (shouldNotify) {
                // Se guarda el texto del aviso para poder abrirlo desde la notificación
                snapshotName = SnapshotStore.saveText(applicationContext, section)
                prefs.lastSnapshot = snapshotName
                prefs.lastSnapshotUrl = PAGE_URL
                notified = notifyUser(affected, snapshotName)
            }
            // Si ya no hay coincidencia el hash se borra, así un aviso futuro idéntico volverá a notificar
            prefs.lastAlertHash = if (met) alertHash else ""

            // Mensaje de estado que verá el usuario en la pantalla principal
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
            // Error de red u otro problema: se registra en el estado y se decide si reintentar
            prefs.lastStatus = "Revisión del $time falló: ${e.message ?: e.javaClass.simpleName}"
            if (manual) Result.failure() else Result.retry()
        }
    }

    /**
     * Descarga la página con una petición HTTP GET y devuelve el HTML como texto.
     * Usa 15 segundos de espera máxima y lanza una excepción si el servidor no responde con código 2xx.
     * La conexión siempre se cierra al terminar.
     */
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

    /**
     * Muestra la notificación del aviso. Devuelve true si se mostró, o false si no hay permiso
     * para notificar (en Android 13 o superior el usuario debe conceder POST_NOTIFICATIONS).
     */
    private fun notifyUser(affected: List<Department>, snapshotName: String): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        // Desde Android 8 las notificaciones necesitan un canal; crearlo varias veces no tiene efecto
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

        // Título corto, y texto expandible con la lista de departamentos afectados
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

        // Se usa siempre el mismo id: una notificación nueva reemplaza a la anterior
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notification)
        return true
    }

    companion object {
        /** Identificador del canal de notificaciones. */
        const val CHANNEL_ID = "avisos_meteorologicos"

        /** Identificador de la notificación; al ser fijo, cada aviso nuevo sustituye al anterior. */
        const val NOTIF_ID = 1001

        /** Clave del dato de entrada que indica que la revisión la pidió el usuario con "Revisar ahora". */
        const val KEY_MANUAL = "manual"
    }
}
