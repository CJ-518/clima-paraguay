package com.example.changewatcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/*
 * AlarmScheduler.kt
 *
 * Programa las revisiones con AlarmManager, que despierta la app a la hora indicada
 * aunque el teléfono esté en reposo (Doze). Es más puntual que WorkManager.
 *
 * Funcionamiento: se programa UNA sola alarma. Cuando suena, [AlarmReceiver] lanza la revisión
 * y programa la siguiente, formando una cadena mientras el monitoreo esté activado.
 */
object AlarmScheduler {

    /** Código que identifica nuestra alarma; el mismo código permite reemplazarla o cancelarla. */
    private const val REQUEST_CODE = 4001

    /** Acción del Intent que recibe [AlarmReceiver]. */
    const val ACTION_CHECK = "com.example.changewatcher.ACTION_CHECK"

    /** PendingIntent que dispara [AlarmReceiver]. Es idéntico en cada llamada, por eso reemplaza a la alarma anterior. */
    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * True si la app puede programar alarmas exactas.
     * Antes de Android 12 siempre se puede; desde Android 12 el usuario debe conceder el permiso.
     */
    fun canScheduleExact(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * Programa la próxima revisión dentro de [intervalMinutes] minutos, reemplazando cualquier alarma anterior.
     * Usa una alarma exacta que funciona incluso en modo Doze. Si no se tiene el permiso de alarmas exactas,
     * usa una alarma normal que también funciona en Doze pero con menos precisión.
     */
    fun schedule(ctx: Context, intervalMinutes: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + intervalMinutes * 60_000L
        val pi = pendingIntent(ctx)
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            // El permiso se revocó justo ahora: se usa la alarma normal
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Cancela la alarma pendiente (al desactivar el monitoreo). */
    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(ctx))
    }

    /** Abre la pantalla de ajustes de Android donde el usuario concede "Alarmas y recordatorios" a la app (Android 12+). */
    fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
