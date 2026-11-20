package com.example.changewatcher

import android.content.Context

enum class Mode { APPEARS, DISAPPEARS }

const val DEFAULT_SAVE_FROM = "Detalle de Aviso Meteorologico"
const val DEFAULT_SAVE_UNTIL = "Departamentos afectados"

data class WatchConfig(
    val url: String = "",
    val text: String = "",
    val mode: Mode = Mode.APPEARS,
    val intervalMinutes: Long = 15,
    val retentionDays: Int = 7,
    /** Save the page text starting at this text (blank = from the beginning of the page). */
    val saveFrom: String = DEFAULT_SAVE_FROM,
    /** Stop saving at the end of the line containing this text (blank = until the end of the page). */
    val saveUntil: String = DEFAULT_SAVE_UNTIL,
    val ignoreCase: Boolean = true,
    val enabled: Boolean = false,
)

/** Simple SharedPreferences-backed storage for the single watcher. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)

    fun load() = WatchConfig(
        url = sp.getString("url", "") ?: "",
        text = sp.getString("text", "") ?: "",
        mode = Mode.valueOf(sp.getString("mode", Mode.APPEARS.name) ?: Mode.APPEARS.name),
        intervalMinutes = sp.getLong("interval", 15),
        retentionDays = sp.getInt("retention", 7),
        saveFrom = sp.getString("saveFrom", DEFAULT_SAVE_FROM) ?: DEFAULT_SAVE_FROM,
        saveUntil = sp.getString("saveUntil", DEFAULT_SAVE_UNTIL) ?: DEFAULT_SAVE_UNTIL,
        ignoreCase = sp.getBoolean("ignoreCase", true),
        enabled = sp.getBoolean("enabled", false),
    )

    fun save(c: WatchConfig) {
        sp.edit()
            .putString("url", c.url)
            .putString("text", c.text)
            .putString("mode", c.mode.name)
            .putLong("interval", c.intervalMinutes)
            .putInt("retention", c.retentionDays)
            .putString("saveFrom", c.saveFrom)
            .putString("saveUntil", c.saveUntil)
            .putBoolean("ignoreCase", c.ignoreCase)
            .putBoolean("enabled", c.enabled)
            .apply()
    }

    /** Whether the condition was met on the previous check (used to notify only on transitions). */
    var lastConditionMet: Boolean
        get() = sp.getBoolean("lastMet", false)
        set(v) = sp.edit().putBoolean("lastMet", v).apply()

    var lastStatus: String
        get() = sp.getString("lastStatus", "No checks yet") ?: "No checks yet"
        set(v) = sp.edit().putString("lastStatus", v).apply()

    /** File name of the most recent saved page snapshot (empty if none). */
    var lastSnapshot: String
        get() = sp.getString("lastSnapshot", "") ?: ""
        set(v) = sp.edit().putString("lastSnapshot", v).apply()

    /** URL the most recent snapshot was taken from. */
    var lastSnapshotUrl: String
        get() = sp.getString("lastSnapshotUrl", "") ?: ""
        set(v) = sp.edit().putString("lastSnapshotUrl", v).apply()
}
