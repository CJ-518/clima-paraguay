package com.example.changewatcher

import android.content.Context

/** The only page the app checks. */
const val PAGE_URL = "https://www.meteorologia.gov.py/avisos/"

/** The saved text starts here... */
const val SAVE_FROM = "Detalle de Aviso Meteorologico"

/** ...and ends at the end of the line (or paragraph) of this label. The affected departments are listed there. */
const val SAVE_UNTIL = "Departamentos afectados"

data class WatchConfig(
    /** Ids of the departments the user wants to be notified about (see [Departments]). */
    val departments: Set<String> = emptySet(),
    val intervalMinutes: Long = 15,
    val enabled: Boolean = false,
)

/** Simple SharedPreferences-backed storage for the single watcher. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)

    fun load() = WatchConfig(
        departments = (sp.getStringSet("departments", emptySet()) ?: emptySet()).toSet(),
        intervalMinutes = sp.getLong("interval", 15),
        enabled = sp.getBoolean("enabled", false),
    )

    fun save(c: WatchConfig) {
        sp.edit()
            .putStringSet("departments", HashSet(c.departments))
            .putLong("interval", c.intervalMinutes)
            .putBoolean("enabled", c.enabled)
            .apply()
    }

    /** Fingerprint of the last alert that matched the chosen departments (used to notify only about new/changed alerts). */
    var lastAlertHash: String
        get() = sp.getString("lastAlertHash", "") ?: ""
        set(v) = sp.edit().putString("lastAlertHash", v).apply()

    var lastStatus: String
        get() = sp.getString("lastStatus", "Aún no se hizo ninguna revisión") ?: "Aún no se hizo ninguna revisión"
        set(v) = sp.edit().putString("lastStatus", v).apply()

    /** File name of the most recent saved alert (empty if none). */
    var lastSnapshot: String
        get() = sp.getString("lastSnapshot", "") ?: ""
        set(v) = sp.edit().putString("lastSnapshot", v).apply()

    /** URL the most recent saved alert was taken from. */
    var lastSnapshotUrl: String
        get() = sp.getString("lastSnapshotUrl", "") ?: ""
        set(v) = sp.edit().putString("lastSnapshotUrl", v).apply()
}
