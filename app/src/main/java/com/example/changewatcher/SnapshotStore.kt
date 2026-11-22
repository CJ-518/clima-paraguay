package com.example.changewatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.text.HtmlCompat
import java.io.File
import java.net.URL
import java.text.Normalizer

/*
 * SnapshotStore.kt
 *
 * Utilidades para trabajar con el texto de los avisos:
 *  - convertir el HTML de la página en texto plano,
 *  - normalizar texto (minúsculas y sin acentos) para comparar,
 *  - recortar solo la parte del aviso que interesa,
 *  - guardar y leer el último aviso en el almacenamiento privado de la app,
 *  - encontrar en el HTML la imagen del aviso, guardarla y leerla.
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

    /** Subcarpeta de filesDir donde se guarda la imagen del aviso. */
    private const val IMAGE_DIR = "images"

    /** Marca temporal que se pone en lugar de cada <img> para saber en qué parte del texto estaba. */
    private const val IMG_TOKEN = "@@IMG"

    private val IMG_TOKEN_REGEX = Regex("@@IMG(\\d+)@@")
    private val IMG_TAG_REGEX = Regex("(?is)<img\\b[^>]*>")
    private val ATTR_REGEX =
        Regex("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

    /** Palabras que indican que la imagen es un adorno de la página (logos, iconos, redes sociales…) y no el aviso. */
    private val JUNK_WORDS = listOf(
        "logo", "icon", "favicon", "sprite", "avatar", "spinner", "loading", "blank", "pixel",
        "tracking", "facebook", "twitter", "instagram", "youtube", "whatsapp", "telegram", "tiktok",
    )

    /** Palabras que sugieren que la imagen es la del aviso (mapa de zonas afectadas, aviso, alerta…). */
    private val LIKELY_REGEX = Regex("aviso|alerta|warning|mapa|map|pronostic|zona|region")

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

    /** Quita del HTML los bloques que no son contenido visible (script, style, noscript, head, svg) y los comentarios. */
    private fun stripNonContent(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript|head|svg)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?is)<!--.*?-->"), " ")

    /**
     * Convierte HTML en texto plano legible, en cuatro pasos:
     * 1. Elimina bloques que no son contenido visible (script, style, noscript, head, svg) y los comentarios HTML.
     * 2. Agrega un salto de línea después de las etiquetas de bloque (p, div, li, tr, títulos, etc.)
     *    para que cada bloque quede en su propia línea.
     * 3. Deja que HtmlCompat quite las etiquetas y decodifique entidades como &amp; o &aacute;.
     * 4. Limpia el resultado: quita caracteres de relleno, recorta cada línea y descarta las vacías.
     */
    fun htmlToText(html: String): String {
        val cleaned = stripNonContent(html)
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

    // ------------------------------------------------------------------------------------------
    // Imagen del aviso
    // ------------------------------------------------------------------------------------------

    /**
     * Busca en el HTML de la página la imagen que acompaña al aviso y devuelve su dirección completa (http/https),
     * o null si no hay aviso o no se encontró ninguna imagen.
     *
     * Cada <img> se reemplaza por una marca de texto, se convierte la página a texto y se mira qué marcas
     * quedaron después del título del aviso ([SAVE_FROM]). Así se sabe qué imágenes pertenecen al aviso
     * y no al encabezado o al pie de la página.
     *
     * Orden de preferencia:
     *  1. la primera imagen después del título cuya dirección parezca de un aviso o mapa;
     *  2. la primera imagen válida después del título;
     *  3. una imagen de aviso o mapa que esté antes del título.
     * Los logos, iconos e imágenes diminutas se descartan.
     */
    fun findImageUrl(html: String, pageUrl: String): String? {
        val urls = ArrayList<String?>()
        val marked = stripNonContent(html).replace(IMG_TAG_REGEX) { m ->
            urls.add(imageUrlFromTag(m.value, pageUrl))
            "\n$IMG_TOKEN${urls.size - 1}@@\n"
        }
        val text = htmlToText(marked)
        val markerAt = fold(text).indexOf(fold(SAVE_FROM.trim()))
        if (markerAt < 0) return null

        val found = IMG_TOKEN_REGEX.findAll(text).mapNotNull { m ->
            val url = urls.getOrNull(m.groupValues[1].toInt()) ?: return@mapNotNull null
            m.range.first to url
        }.toList()
        val after = found.filter { it.first > markerAt }.map { it.second }
        val before = found.filter { it.first < markerAt }.map { it.second }

        return after.firstOrNull { LIKELY_REGEX.containsMatchIn(it.lowercase()) }
            ?: after.firstOrNull()
            ?: before.firstOrNull { LIKELY_REGEX.containsMatchIn(it.lowercase()) }
    }

    /**
     * Saca de una etiqueta <img> la dirección completa de la imagen, o null si no sirve
     * (sin dirección, imagen en base64, adorno de la página o tamaño diminuto).
     * Entiende imágenes con carga diferida (data-src, data-lazy-src, data-original) y srcset.
     */
    private fun imageUrlFromTag(tag: String, pageUrl: String): String? {
        val attrs = HashMap<String, String>()
        for (m in ATTR_REGEX.findAll(tag)) {
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            attrs[m.groupValues[1].lowercase()] = value
        }
        // Imágenes diminutas (iconos, píxeles de seguimiento)
        val w = attrs["width"]?.filter { it.isDigit() }?.toIntOrNull()
        val h = attrs["height"]?.filter { it.isDigit() }?.toIntOrNull()
        if ((w != null && w in 1..64) || (h != null && h in 1..64)) return null

        var raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .mapNotNull { attrs[it]?.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
        if (raw == null) {
            // srcset: lista "dirección tamaño, dirección tamaño"; se toma la última, que suele ser la más grande
            val set = (attrs["srcset"] ?: attrs["data-srcset"])?.trim()
            raw = set?.split(',')?.lastOrNull()?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
        }
        if (raw == null) return null

        val absolute = runCatching {
            URL(URL(pageUrl), raw.replace("&amp;", "&").replace(" ", "%20")).toString()
        }.getOrNull() ?: return null
        if (!absolute.startsWith("http://", true) && !absolute.startsWith("https://", true)) return null

        val lower = absolute.lowercase()
        if (lower.substringBefore('?').endsWith(".svg")) return null
        if (JUNK_WORDS.any { lower.contains(it) }) return null
        return absolute
    }

    /** Devuelve la carpeta de imágenes, creándola si todavía no existe. */
    private fun imageDir(ctx: Context): File = File(ctx.filesDir, IMAGE_DIR).apply { mkdirs() }

    /**
     * Guarda los bytes de una imagen y devuelve el nombre del archivo, o null si no son una imagen válida.
     * Solo se conserva la imagen más reciente: las anteriores se borran.
     */
    fun saveImage(ctx: Context, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val name = "${System.currentTimeMillis()}.img"
        File(imageDir(ctx), name).writeBytes(bytes)
        imageDir(ctx).listFiles()?.forEach { f -> if (f.name != name) f.delete() }
        return name
    }

    /** Devuelve el archivo de la imagen guardada con ese nombre, o null si no existe o el nombre no es seguro. */
    fun imageFile(ctx: Context, name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = File(imageDir(ctx), name)
        return if (f.exists()) f else null
    }

    /** Borra la imagen guardada (cuando el aviso nuevo no trae imagen, para no mostrar la de otro aviso). */
    fun clearImages(ctx: Context) {
        imageDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Lee la imagen guardada reduciéndola si es muy grande (el lado mayor queda en [maxSide] píxeles o poco más),
     * para no gastar memoria de más. Devuelve null si el archivo no se puede leer.
     */
    fun decodeImage(file: File, maxSide: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
