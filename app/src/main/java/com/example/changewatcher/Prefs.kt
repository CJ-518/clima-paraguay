package com.example.changewatcher

import android.content.Context

/*
 * Prefs.kt
 *
 * Configuración y estado persistente de la app.
 *
 * Todo se guarda en SharedPreferences (archivo "watcher"), así que sobrevive
 * al cierre de la app y al reinicio del teléfono. Lo usan tanto la pantalla
 * principal (MainActivity) como el trabajo en segundo plano (CheckWorker).
 */

/** Única página que la app revisa: la lista de avisos meteorológicos. */
const val PAGE_URL = "https://www.meteorologia.gov.py/avisos/"

/**
 * Texto con el que empieza la parte del aviso que se guarda.
 * Si esta frase no aparece en la página, se considera que no hay ningún aviso activo.
 */
const val SAVE_FROM = "Detalle de Aviso Meteorologico"

/**
 * Texto con el que termina la parte guardada. Termina al final de la línea
 * (o párrafo) que contiene esta etiqueta, porque ahí se listan los departamentos afectados.
 */
const val SAVE_UNTIL = "Departamentos afectados"

/**
 * Ajustes que elige el usuario.
 *
 * @param departments ids de los departamentos a vigilar (ver [Departments]).
 * @param intervalMinutes cada cuántos minutos se revisa la página (WorkManager exige mínimo 15).
 * @param enabled true si el monitoreo periódico está activado.
 */
data class WatchConfig(
    val departments: Set<String> = emptySet(),
    val intervalMinutes: Long = 15,
    val enabled: Boolean = false,
)

/**
 * Lectura y escritura de ajustes y estado usando SharedPreferences.
 * Además de la configuración, guarda datos de seguimiento: el último aviso
 * notificado, el estado de la última revisión y el último texto guardado.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)

    /** Lee la configuración guardada; si no hay nada, devuelve los valores por defecto. */
    fun load() = WatchConfig(
        departments = (sp.getStringSet("departments", emptySet()) ?: emptySet()).toSet(),
        intervalMinutes = sp.getLong("interval", 15),
        enabled = sp.getBoolean("enabled", false),
    )

    /**
     * Guarda la configuración. Se copia el conjunto de departamentos en un HashSet nuevo
     * porque SharedPreferences no debe recibir el mismo objeto que se modificó antes.
     */
    fun save(c: WatchConfig) {
        sp.edit()
            .putStringSet("departments", HashSet(c.departments))
            .putLong("interval", c.intervalMinutes)
            .putBoolean("enabled", c.enabled)
            .apply()
    }

    /**
     * Huella (hash) del último aviso que coincidió con los departamentos elegidos.
     * Permite notificar solo cuando el aviso es nuevo o cambió, y no en cada revisión.
     * Queda vacía cuando no hay coincidencia, para que un aviso futuro vuelva a notificar.
     */
    var lastAlertHash: String
        get() = sp.getString("lastAlertHash", "") ?: ""
        set(v) = sp.edit().putString("lastAlertHash", v).apply()

    /** Mensaje con el resultado de la última revisión; se muestra en la pantalla principal. */
    var lastStatus: String
        get() = sp.getString("lastStatus", "Aún no se hizo ninguna revisión") ?: "Aún no se hizo ninguna revisión"
        set(v) = sp.edit().putString("lastStatus", v).apply()

    /** Nombre del archivo del último aviso guardado (vacío si todavía no hay ninguno). */
    var lastSnapshot: String
        get() = sp.getString("lastSnapshot", "") ?: ""
        set(v) = sp.edit().putString("lastSnapshot", v).apply()

    /** Dirección de la página de donde se tomó el último aviso guardado. */
    var lastSnapshotUrl: String
        get() = sp.getString("lastSnapshotUrl", "") ?: ""
        set(v) = sp.edit().putString("lastSnapshotUrl", v).apply()
}
