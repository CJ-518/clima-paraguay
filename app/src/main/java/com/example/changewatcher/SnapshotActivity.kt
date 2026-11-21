package com.example.changewatcher

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * SnapshotActivity.kt
 *
 * Pantalla que muestra el texto del último aviso guardado. A diferencia de MainActivity,
 * se construye con vistas clásicas de Android (LinearLayout, TextView, etc.) en lugar de Compose.
 *
 * Estructura de la pantalla (de arriba a abajo):
 *  - encabezado con la fecha de guardado,
 *  - botón para abrir la página en vivo en el navegador,
 *  - cuerpo desplazable con el texto del aviso, con espacios, negritas, tamaños y colores.
 *
 * El archivo guardado NO se modifica: es texto plano, una línea por bloque. El formato se aplica
 * solo al mostrarlo, en dos pasos: [layout] reorganiza el texto con espacios entre secciones y
 * [styleLines] aplica negritas, tamaños y colores.
 */

/** Tipo de línea dentro del aviso. */
private enum class Kind {
    /** La primera línea: el título del aviso. */
    TITLE,

    /** Una línea con etiqueta corta antes de los dos puntos, por ejemplo "Fecha: ...". */
    LABEL,

    /** Cualquier otra línea; continúa la sección de la última etiqueta. */
    BODY,
}

/** Sección del aviso, que decide el tamaño y el color de sus líneas. */
private enum class Section {
    /** Datos secundarios (fecha, hora de emisión, etc.): tamaño normal. */
    NORMAL,

    /** "Fenómeno esperado": es lo más importante, va grande, en negrita y en rojo. */
    PHENOMENON,

    /** "Zona de cobertura" y "Departamentos afectados": van un poco más grandes. */
    KEY,
}

/**
 * Una línea ya colocada en el texto que se muestra.
 *
 * @param start posición donde empieza la línea en el texto mostrado.
 * @param end posición donde termina (exclusiva).
 * @param kind tipo de línea.
 * @param labelEnd cantidad de caracteres de la etiqueta contando los dos puntos (solo en LABEL; -1 en los demás).
 * @param section sección a la que pertenece la línea.
 */
private data class Line(
    val start: Int,
    val end: Int,
    val kind: Kind,
    val labelEnd: Int,
    val section: Section,
)

/** Colores del texto del aviso. */
private val COLOR_TITLE = Color.parseColor("#0D47A1")   // azul oscuro: títulos y etiquetas
private val COLOR_ALERT = Color.parseColor("#B71C1C")   // rojo oscuro: fenómeno esperado
private val COLOR_HEADER = Color.parseColor("#546E7A")  // gris azulado: encabezado de la pantalla

/**
 * Muestra el texto del último aviso guardado.
 * Se abre desde la notificación o desde el botón "Ver último aviso guardado".
 */
class SnapshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = Prefs(this)
        // Datos recibidos en el Intent: nombre del archivo del aviso y página de origen
        val requested = intent.getStringExtra(EXTRA_FILE) ?: ""
        val url = (intent.getStringExtra(EXTRA_URL) ?: "").ifBlank { PAGE_URL }

        // Solo se conserva el último aviso: si el de esta notificación fue reemplazado, se muestra el más reciente
        val exact = SnapshotStore.file(this, requested)
        val latestName = prefs.lastSnapshot
        val file = exact ?: SnapshotStore.file(this, latestName)
        val name = if (exact != null) requested else latestName
        val replaced = exact == null && file != null   // true si se está mostrando uno distinto al pedido
        val savedAt = SnapshotStore.savedAt(name)
        val cfg = prefs.load()

        // Función auxiliar para convertir dp a píxeles según la densidad de la pantalla
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Contenedor vertical principal
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Encabezado: aquí se escribe la fecha de guardado o el mensaje de que no hay aviso
        val header = TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(8))
            textSize = 13f
            setTextColor(COLOR_HEADER)
        }
        root.addView(header)

        // Botón que abre la página de avisos en el navegador
        val openLive = Button(this).apply {
            text = "Abrir página en vivo"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        root.addView(openLive)

        // Cuerpo con el texto del aviso (seleccionable) dentro de un ScrollView que ocupa el espacio restante.
        // El interlineado ampliado (1.3) y el margen interior dan aire al texto.
        val body = TextView(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(24))
            textSize = 16f
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(body)
        }
        root.addView(scroll)

        if (file == null) {
            // No existe ningún aviso guardado
            header.text = "Todavía no hay ningún aviso guardado."
        } else {
            // Fecha de guardado tomada del nombre del archivo, y aviso si el pedido fue reemplazado
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("es-PY"))
            val savedText = savedAt?.let { fmt.format(Date(it)) } ?: "fecha desconocida"
            header.text = "Guardado el $savedText" +
                if (replaced) "\nEl aviso de esta notificación fue reemplazado por uno más reciente." else ""
            body.text = "Cargando…"

            // Leer y dar formato al texto puede tardar un momento, así que se hace fuera del hilo principal
            Thread {
                // Si la lectura falla, se muestra el error tal cual, sin darle formato de aviso
                var failed = false
                val raw = try {
                    val content = file.readText()
                    // Lo guardado en versiones antiguas era HTML; lo nuevo ya es texto plano
                    if (name.endsWith(".html")) SnapshotStore.htmlToText(content) else content
                } catch (e: Exception) {
                    failed = true
                    "No se pudo leer el aviso guardado: ${e.message ?: e.javaClass.simpleName}"
                }

                // 1) Reorganiza el texto con espacios entre secciones y apunta qué es cada línea
                val (text, lines) = if (failed) raw to emptyList() else layout(raw)

                // SpannableString permite aplicar formato (color, negrita, tamaño, fondo) a partes del texto
                val spannable = SpannableString(text)

                // 2) Negritas, tamaños y colores según el tipo de línea y su sección
                styleLines(spannable, lines)

                // 3) Resalta los departamentos elegidos donde aparezcan: fondo amarillo y negrita.
                //    Las posiciones se calculan sobre el texto ya reorganizado, por eso coinciden.
                for (r in Departments.matchRanges(text, cfg.departments)) {
                    spannable.setSpan(
                        BackgroundColorSpan(Color.YELLOW),
                        r.first,
                        r.last + 1, // el rango termina en r.last (inclusive); setSpan espera un final exclusivo
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        r.first,
                        r.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // Las vistas solo se pueden modificar desde el hilo principal
                runOnUiThread {
                    body.text = if (text.isBlank()) {
                        "(No se guardó nada de texto.)"
                    } else {
                        spannable
                    }
                }
            }.start()
        }

        setContentView(root)
    }

    /**
     * Reorganiza el texto del aviso para que se lea con aire y devuelve el texto nuevo junto con
     * la lista de líneas (posición, tipo y sección), que luego usa [styleLines].
     *
     * Reglas:
     *  - la primera línea con contenido es el título;
     *  - una línea con una etiqueta corta antes de los dos puntos (hasta 40 caracteres y 4 palabras,
     *    por ejemplo "Fecha:" o "Hora de emisión:") es una etiqueta y abre una sección nueva;
     *  - las demás líneas son contenido de la sección actual;
     *  - se deja una línea en blanco después del título y antes de cada etiqueta, para separar las secciones.
     */
    private fun layout(raw: String): Pair<String, List<Line>> {
        val out = StringBuilder()
        val lines = mutableListOf<Line>()
        var prev: Kind? = null
        var section = Section.NORMAL

        for (line in raw.split("\n")) {
            if (line.isBlank()) continue

            val colon = line.indexOf(':')
            val isLabel = prev != null && colon in 1..40 &&
                line.substring(0, colon).trim().split(" ").size <= 4
            val kind = when {
                prev == null -> Kind.TITLE
                isLabel -> Kind.LABEL
                else -> Kind.BODY
            }

            // Separador: línea en blanco tras el título y antes de cada etiqueta; salto simple en el resto
            if (prev != null) {
                out.append(if (prev == Kind.TITLE || kind == Kind.LABEL) "\n\n" else "\n")
            }

            // Cada etiqueta decide la sección que sigue (se compara sin acentos ni mayúsculas)
            if (kind == Kind.LABEL) {
                val label = SnapshotStore.fold(line.substring(0, colon))
                section = when {
                    label.startsWith("fenomeno esperado") -> Section.PHENOMENON
                    label.contains("departamentos afectados") ||
                        label.contains("zona de cobertura") -> Section.KEY
                    else -> Section.NORMAL
                }
            }

            val start = out.length
            out.append(line)
            lines.add(Line(start, out.length, kind, if (kind == Kind.LABEL) colon + 1 else -1, section))
            prev = kind
        }
        return out.toString() to lines
    }

    /**
     * Aplica negritas, tamaños y colores según el tipo de línea y su sección:
     *  - Título: el más grande (1.5x), negrita, azul oscuro.
     *  - Fenómeno esperado: etiqueta y contenido en negrita, 1.25x, rojo oscuro.
     *  - Zona de cobertura y Departamentos afectados: etiqueta en negrita azul y contenido en 1.1x.
     *  - Otras etiquetas (Fecha, Hora de emisión, ...): solo la etiqueta en negrita azul; el contenido queda normal.
     */
    private fun styleLines(spannable: SpannableString, lines: List<Line>) {

        /** Aplica negrita, color y tamaño relativo entre [start] y [end]. Un tamaño de 1f o un color null no cambian nada. */
        fun style(start: Int, end: Int, size: Float, color: Int?, bold: Boolean) {
            if (end <= start) return
            if (bold) {
                spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (color != null) {
                spannable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (size != 1f) {
                spannable.setSpan(RelativeSizeSpan(size), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        for (l in lines) {
            when (l.kind) {
                Kind.TITLE -> style(l.start, l.end, 1.5f, COLOR_TITLE, true)

                Kind.LABEL -> {
                    val labelStop = l.start + l.labelEnd // fin de la etiqueta, incluidos los dos puntos
                    when (l.section) {
                        Section.PHENOMENON -> style(l.start, l.end, 1.25f, COLOR_ALERT, true)
                        Section.KEY -> {
                            style(l.start, labelStop, 1.1f, COLOR_TITLE, true)
                            style(labelStop, l.end, 1.1f, null, false)
                        }
                        Section.NORMAL -> style(l.start, labelStop, 1f, COLOR_TITLE, true)
                    }
                }

                Kind.BODY -> when (l.section) {
                    Section.PHENOMENON -> style(l.start, l.end, 1.25f, COLOR_ALERT, true)
                    Section.KEY -> style(l.start, l.end, 1.1f, null, false)
                    Section.NORMAL -> Unit // contenido normal: sin formato especial
                }
            }
        }
    }

    companion object {
        /** Clave del Intent con el nombre del archivo del aviso a mostrar. */
        const val EXTRA_FILE = "snapshot_file"

        /** Clave del Intent con la dirección de la página de origen (para el botón "Abrir página en vivo"). */
        const val EXTRA_URL = "snapshot_url"
    }
}
