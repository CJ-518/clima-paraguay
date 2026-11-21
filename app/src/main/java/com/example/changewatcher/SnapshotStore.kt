package com.example.changewatcher

import android.content.Context
import androidx.core.text.HtmlCompat
import java.io.File
import java.text.Normalizer

/*
 * SnapshotStore.kt
 *
 * Utilidades para trabajar con el texto de los avisos:
 *  - convertir el HTML de la página en texto plano,
 *  - normalizar texto (minúsculas y sin acentos) para comparar,
 *  - recortar solo la parte del aviso que interesa,
 *  - guardar y leer el último aviso en el almacenamiento privado de la app.
 */

/**
 * Guarda el texto del aviso como archivo en el almacenamiento privado de la app
 * y ofrece las funciones de procesamiento de texto. Solo se conserva el último aviso.
 */
object SnapshotStore {
    /** Subcarpeta de filesDir donde se guardan los avisos. */
    private const val DIR = "snapshots"

    /** Límite de caracteres por archivo, para no llenar el almacenamiento con una página enorme. */
    private const val MAX_CHARS = 3_000_000

    /** Devuelve la carpeta de avisos, creándola si todavía no existe. */
    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /**
     * Guarda [text] como texto plano y devuelve el nombre del archivo.
     * El nombre es la hora actual en milisegundos + ".txt", por lo que también sirve como fecha de guardado
     * (ver [savedAt]). Después de escribir, borra todos los avisos anteriores: solo queda el más nuevo.
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

    /**
     * Devuelve el archivo guardado con ese nombre, o null si no existe
     * (por ejemplo, porque lo reemplazó un aviso más reciente).
     * Rechaza nombres vacíos o con "/", "\" o ".." para que nadie pueda salir de la carpeta de avisos.
     */
    fun file(ctx: Context, name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = File(dir(ctx), name)
        return if (f.exists()) f else null
    }

    /** Momento en que se guardó el aviso (milisegundos desde 1970), leído del nombre del archivo. Null si el nombre no es válido. */
    fun savedAt(name: String): Long? = name.substringBefore('.').toLongOrNull()

    /**
     * Convierte HTML en texto plano legible, en cuatro pasos:
     * 1. Elimina bloques que no son contenido visible (script, style, noscript, head, svg) y los comentarios HTML.
     * 2. Agrega un salto de línea después de las etiquetas de bloque (p, div, li, tr, títulos, etc.)
     *    para que cada bloque quede en su propia línea.
     * 3. Deja que HtmlCompat quite las etiquetas y decodifique entidades como &amp; o &aacute;.
     * 4. Limpia el resultado: quita caracteres de relleno, recorta cada línea y descarta las vacías.
     */
    fun htmlToText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<(script|style|noscript|head|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            // se conserva la etiqueta y se añade un salto de línea después de los elementos de bloque
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|section|article|ul|ol|table)>"), "\$0\n")
        val text = HtmlCompat.fromHtml(cleaned, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        return text
            .replace("\uFFFC", "") // carácter de relleno que dejan las imágenes (U+FFFC)
            .replace('\u00A0', ' ') // espacio de no separación (U+00A0) convertido en espacio normal
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * Pasa el texto a minúsculas y le quita los acentos (Ñ queda como n, é como e, etc.).
     * Sirve para comparar sin que importen mayúsculas ni tildes.
     *
     * Cada carácter se descompone (NFD) y se toma solo el primero, que es la letra base;
     * por eso el resultado tiene exactamente el mismo largo que el original, y las posiciones
     * encontradas en él valen también para el texto original (se usa para resaltar).
     */
    fun fold(s: String): String = buildString {
        for (c in s) append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar())
    }

    /** True si [marker] aparece en [text], sin distinguir mayúsculas ni acentos. */
    fun hasMarker(text: String, marker: String): Boolean = fold(text).contains(fold(marker.trim()))

    /**
     * Recorta de [text] solo la parte que empieza en [from] y termina al final de la línea que contiene [until].
     * Si esa línea solo trae la etiqueta (por ejemplo "Departamentos afectados:"), se incluye también
     * la línea siguiente, que es donde está la lista de departamentos.
     * La búsqueda ignora mayúsculas y acentos. Si [from] no se encuentra, devuelve el texto completo.
     * Si [until] no se encuentra, la parte recortada llega hasta el final del texto.
     */
    fun extractSection(text: String, from: String, until: String): String {
        if (from.isBlank() && until.isBlank()) return text
        val folded = fold(text)

        // Posición de inicio: donde aparece [from]
        var start = 0
        if (from.isNotBlank()) {
            val i = folded.indexOf(fold(from.trim()))
            if (i < 0) return text
            start = i
        }

        // Posición de fin: el final de la línea donde aparece [until] (buscando después de start)
        var end = text.length
        if (until.isNotBlank()) {
            val marker = fold(until.trim())
            val j = folded.indexOf(marker, start)
            if (j >= 0) {
                var eol = text.indexOf('\n', j)
                if (eol < 0) {
                    eol = text.length
                } else {
                    // Lo que queda en la línea después de la etiqueta, sin espacios ni ":"
                    val rest = text.substring(j + marker.length, eol).trim().trim(':').trim()
                    if (rest.isEmpty()) {
                        // La lista está en la línea de abajo: se amplía el final hasta ahí
                        val nextEol = text.indexOf('\n', eol + 1)
                        eol = if (nextEol < 0) text.length else nextEol
                    }
                }
                end = eol
            }
        }
        return text.substring(start, end).trim()
    }

    /**
     * Devuelve la parte "Departamentos afectados: ..." de un aviso guardado, desde la etiqueta hasta el final,
     * o null si esa etiqueta no está. Solo en esta parte se buscan los departamentos, para no confundirlos
     * con otras menciones en el resto del texto.
     */
    fun departmentsPart(section: String): String? {
        val i = fold(section).indexOf(fold(SAVE_UNTIL.trim()))
        return if (i < 0) null else section.substring(i)
    }
}
