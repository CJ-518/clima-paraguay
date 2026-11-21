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
 * (cuando el monitoreo está activado) o una sola vez (botón "Revisar ahora" o al abrir la app).
 *
 * Flujo de cada revisión:
 *  1. Lee los ajustes y decide si debe correr.
 *  2. Descarga la página de avisos y la convierte en texto.
 *  3. Si hay un aviso, recorta la parte relevante y busca los departamentos elegidos.
 *  4. Si hay un aviso nuevo (o la revisión es manual), guarda el texto, mencione o no los departamentos elegidos.
 *     Solo notifica si menciona alguno de los elegidos y la app NO está en primer plano
 *     (si está abierta, el usuario ya ve el aviso).
 *  5. Guarda un mensaje de estado que la pantalla principal muestra al usuario.
 */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /**
     * Ejecuta una revisión completa. Devuelve:
     *  - success: la revisión terminó (haya o no avisos);
     *  - retry: falló una revisión programada, WorkManager la reintenta más tarde;
     *  - failure: falló una revisión manual o al abrir la app, no se reintenta.
     */
    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val cfg = prefs.load()
        // Las revisiones manuales ("Revisar ahora") se hacen aunque el monitoreo esté desactivado
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        // La revisión al abrir la app también corre con el monitoreo desactivado, pero no repite avisos ya guardados
        val refresh = inputData.getBoolean(KEY_REFRESH, false)
        val time = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es-PY")).format(Date())

        // Condiciones para no hacer nada: monitoreo apagado (si no es manual ni al abrir) o sin departamentos elegidos
        if (!manual && !refresh && !cfg.enabled) return Result.success()
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

            // El aviso se guarda siempre que haya uno nuevo o cambiado (se compara su hash),
            // mencione o no los departamentos elegidos. Las revisiones manuales lo guardan siempre.
            val alertHash = section.hashCode().toString()
            val shouldSave = hasAlert && section.isNotBlank() && (manual || alertHash != prefs.lastAlertHash)
            // Se notifica solo si el aviso menciona departamentos elegidos y todavía no se notificó este aviso
            val shouldNotify = met && (shouldSave || alertHash != prefs.lastNotifiedHash)

            var snapshotName = ""
            var notified = false
            // Con la app en primer plano no se notifica: el usuario ya está viendo el aviso en pantalla
            var suppressed = false
            if (shouldSave) {
                // Se guarda el texto del aviso para poder abrirlo desde la app o desde la notificación
                snapshotName = SnapshotStore.saveText(applicationContext, section)
                prefs.lastSnapshot = snapshotName
                prefs.lastSnapshotUrl = PAGE_URL
            }
            if (shouldNotify) {
                // Se consulta el primer plano justo ahora, porque la descarga pudo tardar
                if (Foreground.isActive) {
                    suppressed = true
                } else {
                    notified = notifyUser(affected)
                }
                prefs.lastNotifiedHash = alertHash
            }
            // Cuando no hay aviso se borran los hashes, así un aviso futuro idéntico volverá a guardarse y notificarse
            prefs.lastAlertHash = if (hasAlert) alertHash else ""
            if (!met) prefs.lastNotifiedHash = ""

            // Mensaje de estado que verá el usuario en la pantalla principal
            var status = when {
                !hasAlert -> "Revisión del $time: no hay ningún aviso en la página"
                !met -> "Revisión del $time: hay un aviso, pero no incluye los departamentos elegidos"
                else -> "Revisión del $time: aviso para " + affected.joinToString(", ") { it.label }
            }
            if (hasAlert && !shouldSave) status += " (sin cambios)"
            if (snapshotName.isNotEmpty()) status += " - aviso guardado"
            if (shouldNotify && !suppressed && !notified) {
                status += " - notificación bloqueada: permite las notificaciones de esta app en los ajustes de Android"
            }
            prefs.lastStatus = status
            Result.success()
        } catch (e: Exception) {
            // Error de red u otro problema: se registra en el estado y se decide si reintentar
            prefs.lastStatus = "Revisión del $time falló: ${e.message ?: e.javaClass.simpleName}"
            if (manual || refresh) Result.failure() else Result.retry()
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
    private fun notifyUser(affected: List<Department>): Boolean {
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

        // Al tocar la notificación se abre la pantalla principal, que muestra el último aviso guardado
        val openApp = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            0,
            openApp,
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
                    .bigText("El aviso incluye: $names. Toca para ver el aviso.")
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

        /** Clave del dato de entrada que indica que la revisión se lanzó al abrir la app. */
        const val KEY_REFRESH = "refresh"
    }
}
