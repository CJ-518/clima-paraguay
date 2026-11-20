package com.example.changewatcher

import android.content.Context
import androidx.core.text.HtmlCompat
import java.io.File
import java.text.Normalizer

/** Stores saved page text as files in the app's private storage and deletes them after a retention period. */
object SnapshotStore {
    private const val DIR = "snapshots"
    private const val MAX_CHARS = 3_000_000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** Saves plain text and returns the file name (its timestamp in milliseconds + ".txt"). */
    fun saveText(ctx: Context, text: String): String {
        val name = "${System.currentTimeMillis()}.txt"
        val content = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        File(dir(ctx), name).writeText(content)
        return name
    }

    /** Returns the saved file, or null if it doesn't exist (for example it was deleted after the retention period). */
    fun file(ctx: Context, name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = File(dir(ctx), name)
        return if (f.exists()) f else null
    }

    /** Time the snapshot was saved, in milliseconds since the epoch. */
    fun savedAt(name: String): Long? = name.substringBefore('.').toLongOrNull()

    /** Deletes saved files older than [retentionDays] days. */
    fun cleanup(ctx: Context, retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * DAY_MS
        dir(ctx).listFiles()?.forEach { f ->
            val ts = savedAt(f.name) ?: f.lastModified()
            if (ts < cutoff) f.delete()
        }
    }

    /** Converts HTML into readable plain text (scripts, styles and tags removed, one line per block). */
    fun htmlToText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<(script|style|noscript|head|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            // keep the tag and add a line break after block-level elements
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|section|article|ul|ol|table)>"), "\$0\n")
        val text = HtmlCompat.fromHtml(cleaned, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        return text
            .replace("￼", "") // placeholder character that images leave behind
            .replace(' ', ' ')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** Replaces accented letters by their plain version, keeping the same length so positions still match. */
    private fun fold(s: String): String = buildString {
        for (c in s) append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0])
    }

    /**
     * Keeps only the part of [text] that starts at [from] and ends at the end of the line containing [until].
     * Matching ignores upper/lower case and accents. If [from] is not found, the whole text is returned so nothing is lost.
     */
    fun extractSection(text: String, from: String, until: String): String {
        if (from.isBlank() && until.isBlank()) return text
        val folded = fold(text)

        var start = 0
        if (from.isNotBlank()) {
            val i = folded.indexOf(fold(from.trim()), 0, ignoreCase = true)
            if (i < 0) return text
            start = i
        }

        var end = text.length
        if (until.isNotBlank()) {
            val j = folded.indexOf(fold(until.trim()), start, ignoreCase = true)
            if (j >= 0) {
                val eol = text.indexOf('\n', j)
                end = if (eol < 0) text.length else eol
            }
        }
        return text.substring(start, end).trim()
    }
}
