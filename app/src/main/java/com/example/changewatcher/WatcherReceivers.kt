package com.example.changewatcher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/*
 * WatcherReceivers.kt
 *
 * Receptores de eventos del sistema:
 *  - AlarmReceiver: se ejecuta cuando suena la alarma programada por [AlarmScheduler].
 *  - BootReceiver: reprograma la alarma después de reiniciar el teléfono, actualizar la app
 *    o cambiar el permiso de alarmas exactas.
 */

/** Nombre único de la revisión lanzada por la alarma; evita que se acumulen revisiones repetidas. */
private const val ALARM_WORK_NAME = "alarm_check"

/**
 * Se ejecuta cuando suena la alarma. Hace dos cosas:
 *  1. Programa la siguiente alarma (primero, para que la cadena continúe aunque algo falle después).
 *  2. Lanza una revisión inmediata con [CheckWorker].
 *
 * La revisión se encola sin restricción de red a propósito: así WorkManager la ejecuta enseguida
 * dentro del proceso que la alarma acaba de despertar, sin esperar a la ventana de mantenimiento de Doze.
 * Si no hay conexión, la revisión falla y WorkManager la reintenta más tarde.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_CHECK) return

        val cfg = Prefs(context).load()
        // Si el usuario desactivó el monitoreo, la cadena de alarmas se detiene aquí
        if (!cfg.enabled) return

        AlarmScheduler.schedule(context, cfg.intervalMinutes)

        val request = OneTimeWorkRequestBuilder<CheckWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ALARM_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * Las alarmas se pierden al reiniciar el teléfono, así que aquí se vuelven a programar si el monitoreo
 * estaba activado. También se ejecuta tras actualizar la app y cuando cambia el permiso de alarmas exactas
 * (para pasar de una alarma normal a una exacta, o al revés).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                val cfg = Prefs(context).load()
                if (cfg.enabled) AlarmScheduler.schedule(context, cfg.intervalMinutes)
            }
        }
    }
}
