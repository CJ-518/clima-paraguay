package com.example.changewatcher

import android.content.Context
import androidx.core.text.HtmlCompat
import java.io.File
import java.text.Normalizer

/** Stores the saved alert text as a file in the app's private storage. Only the latest alert is kept. */
object SnapshotStore {
    private const val DIR = "snapshots"
    private const val MAX_CHARS = 3_000_000

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /**
     * Saves plain text and returns the file name (its timestamp in milliseconds + ".txt").
     * Every previously saved alert is deleted, so only the newest one remains.
     */
    fun saveText(ctx: Context, text: String): String {
        val name = "${System.currentTimeMillis()}.txt"
        val content = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        File(dir(ctx), name).writeText(content)
        dir(ctx).listFiles()?.forEach { f ->
            if (f.name != name) f.delete()
        }
        return name
    }

    /** Returns the saved file, or null if it doesn't exist (for example it was replaced by a newer one). */
    fun file(ctx: Context, name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = File(dir(ctx), name)
        return if (f.exists()) f else null
    }

    /** Time the text was saved, in milliseconds since the epoch. */
    fun savedAt(name: String): Long? = name.substringBefore('.').toLongOrNull()

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

    /**
     * Lower-cases the text and removes accents (Ñ becomes n, é becomes e, ...).
     * The result has exactly the same length as the input, so positions found in it also apply to the original text.
     */
    fun fold(s: String): String = buildString {
        for (c in s) append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar())
    }

    /** True if [marker] appears in [text] (ignoring case and accents). */
    fun hasMarker(text: String, marker: String): Boolean = fold(text).contains(fold(marker.trim()))

    /**
     * Keeps only the part of [text] that starts at [from] and ends at the end of the line containing [until]
     * (if that line holds only the label, for example "Departamentos afectados:", the next line is included too).
     * Matching ignores upper/lower case and accents. If [from] is not found, the whole text is returned.
     */
    fun extractSection(text: String, from: String, until: String): String {
        if (from.isBlank() && until.isBlank()) return text
        val folded = fold(text)

        var start = 0
        if (from.isNotBlank()) {
            val i = folded.indexOf(fold(from.trim()))
            if (i < 0) return text
            start = i
        }

        var end = text.length
        if (until.isNotBlank()) {
            val marker = fold(until.trim())
            val j = folded.indexOf(marker, start)
            if (j >= 0) {
                var eol = text.indexOf('\n', j)
                if (eol < 0) {
                    eol = text.length
                } else {
                    val rest = text.substring(j + marker.length, eol).trim().trim(':').trim()
                    if (rest.isEmpty()) {
                        val nextEol = text.indexOf('\n', eol + 1)
                        eol = if (nextEol < 0) text.length else nextEol
                    }
                }
                end = eol
            }
        }
        return text.substring(start, end).trim()
    }

    /** The "Departamentos afectados: ..." part of a saved alert, or null if that label is not there. */
    fun departmentsPart(section: String): String? {
        val i = fold(section).indexOf(fold(SAVE_UNTIL.trim()))
        return if (i < 0) null else section.substring(i)
    }
}
