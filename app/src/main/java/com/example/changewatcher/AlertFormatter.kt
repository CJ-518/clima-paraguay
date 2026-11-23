package com.example.changewatcher

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/*
 * AlertFormatter.kt
 *
 * Da formato al texto del aviso guardado para mostrarlo en la pantalla principal.
 *
 * El archivo guardado NO se modifica: es texto plano, una línea por bloque. El formato se aplica
 * solo al mostrarlo, en tres pasos: [layout] reorganiza el texto con espacios entre secciones,
 * [styleLines] aplica negritas, tamaños y colores, y por último se cambia el color de la letra
 * de los departamentos elegidos.
 */

/** Tipo de línea dentro del aviso. */
private enum class Kind {
    /** Una línea con etiqueta corta antes de los dos puntos, por ejemplo "Fecha: ...". */
    LABEL,

    /** Cualquier otra línea; continúa la sección de la última etiqueta. */
    BODY,
}

/** Sección del aviso, que decide el tamaño y el color de sus líneas. */
private enum class Section {
    /** Datos secundarios: tamaño normal. */
    NORMAL,

    /** Fecha y hora de emisión: los números de la línea de la etiqueta van en rojo, negrita y más grandes. */
    EMISSION,

    /** "Fenómeno esperado": la etiqueta va con el color de título y el contenido grande y en negrita, con el color normal del texto. */
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

/**
 * Colores del texto del aviso (valores ARGB). Se toman del tema del teléfono para que se lean bien
 * tanto en modo claro como oscuro.
 *
 * @param title títulos y etiquetas (incluida la de "Fenómeno esperado").
 * @param alert números de la fecha y la hora de emisión (rojo).
 * @param selected departamentos elegidos por el usuario.
 * @param emission ya no lo usa [AlertFormatter] (los números de la emisión van con [alert]); se conserva
 *   para no tocar el código que construye esta clase.
 */
data class AlertColors(
    val title: Int,
    val alert: Int,
    val selected: Int,
    val emission: Int,
)

object AlertFormatter {

    /**
     * Devuelve el aviso listo para mostrar: texto reorganizado, con negritas, tamaños y colores,
     * y con la letra de los departamentos de [selected] en otro color y negrita.
     */
    fun format(raw: String, selected: Set<String>, colors: AlertColors): SpannableString {
        // 1) Reorganiza el texto con espacios entre secciones y apunta qué es cada línea
        val (text, lines) = layout(raw)
        val spannable = SpannableString(text)

        // 2) Negritas, tamaños y colores según el tipo de línea y su sección
        styleLines(spannable, lines, colors)

        // 3) Cambia el color de la letra de los departamentos elegidos donde aparezcan.
        //    Las posiciones se calculan sobre el texto ya reorganizado, por eso coinciden.
        //    Se aplica al final para que prevalezca sobre los colores de la sección.
        for (r in Departments.matchRanges(text, selected)) {
            spannable.setSpan(
                ForegroundColorSpan(colors.selected),
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
        return spannable
    }

    /**
     * Reorganiza el texto del aviso para que se lea con aire y devuelve el texto nuevo junto con
     * la lista de líneas (posición, tipo y sección), que luego usa [styleLines].
     *
     * Reglas:
     *  - el título del aviso ("Detalle de Aviso Meteorologico", la primera línea) no se muestra: ocupa mucho espacio;
     *  - una línea con una etiqueta corta antes de los dos puntos (hasta 40 caracteres y 4 palabras,
     *    por ejemplo "Fecha:" o "Hora de emisión:") es una etiqueta y abre una sección nueva;
     *  - las demás líneas son contenido de la sección actual;
     *  - se deja una línea en blanco antes de cada etiqueta (menos la primera), para separar las secciones.
     */
    private fun layout(raw: String): Pair<String, List<Line>> {
        val out = StringBuilder()
        val lines = mutableListOf<Line>()
        var prev: Kind? = null
        var section = Section.NORMAL

        for (line in raw.split("\n")) {
            if (line.isBlank()) continue

            // Se omite el título del aviso: es la primera línea y solo repite el nombre de la página
            if (prev == null && SnapshotStore.hasMarker(line, SAVE_FROM)) continue

            val colon = line.indexOf(':')
            val isLabel = colon in 1..40 &&
                    line.substring(0, colon).trim().split(" ").size <= 4
            val kind = if (isLabel) Kind.LABEL else Kind.BODY

            // Separador: línea en blanco antes de cada etiqueta (salvo la primera línea); salto simple en el resto
            if (prev != null) {
                out.append(if (kind == Kind.LABEL) "\n\n" else "\n")
            }

            // Cada etiqueta decide la sección que sigue (se compara sin acentos ni mayúsculas)
            if (kind == Kind.LABEL) {
                val label = SnapshotStore.fold(line.substring(0, colon))
                section = when {
                    label.startsWith("fenomeno esperado") -> Section.PHENOMENON
                    label.contains("departamentos afectados") ||
                            label.contains("zona de cobertura") -> Section.KEY

                    label.startsWith("fecha") ||
                            label.startsWith("hora") ||
                            label.contains("emision") -> Section.EMISSION

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
     *  - Fenómeno esperado: la etiqueta en negrita, 1.25x y color de título (como las demás etiquetas);
     *    el contenido, en negrita y 1.25x, pero con el color normal del texto (no rojo).
     *  - Zona de cobertura y Departamentos afectados: etiqueta en negrita con color de título y contenido en 1.1x.
     *  - Fecha y hora de emisión: la etiqueta como las demás (negrita, color de título) y, en esa misma línea,
     *    solo los números (22/11/2026, 14:30, ...) en rojo, negrita y 1.2x.
     *  - Otras etiquetas: solo la etiqueta en negrita con color de título; el contenido queda normal.
     */
    private fun styleLines(spannable: SpannableString, lines: List<Line>, colors: AlertColors) {

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

        // Números de una fecha u hora: "22", "2026", "22/11/2026", "14:30", "14:30:05"...
        val numberRegex = Regex("\\d+(?:[:/.\\-]\\d+)*")
        val fullText = spannable.toString()

        /** Pinta en rojo, negrita y más grandes solo los números que haya entre [start] y [end]; el resto no cambia. */
        fun colorNumbers(start: Int, end: Int) {
            if (end <= start) return
            for (m in numberRegex.findAll(fullText.substring(start, end))) {
                style(start + m.range.first, start + m.range.last + 1, 1.2f, colors.alert, true)
            }
        }

        for (l in lines) {
            when (l.kind) {
                Kind.LABEL -> {
                    val labelStop = l.start + l.labelEnd // fin de la etiqueta, incluidos los dos puntos
                    when (l.section) {
                        Section.PHENOMENON -> {
                            // La etiqueta con el color de título; el contenido, grande y en negrita pero sin color propio
                            style(l.start, labelStop, 1.25f, colors.title, true)
                            style(labelStop, l.end, 1.25f, null, true)
                        }

                        Section.EMISSION -> {
                            style(l.start, labelStop, 1f, colors.title, true)
                            colorNumbers(labelStop, l.end)
                        }

                        Section.KEY -> {
                            style(l.start, labelStop, 1.1f, colors.title, true)
                            style(labelStop, l.end, 1.1f, null, false)
                        }

                        Section.NORMAL -> style(l.start, labelStop, 1f, colors.title, true)
                    }
                }

                Kind.BODY -> when (l.section) {
                    Section.PHENOMENON -> style(l.start, l.end, 1.25f, null, true)
                    Section.KEY -> style(l.start, l.end, 1.1f, null, false)
                    Section.NORMAL, Section.EMISSION -> Unit // contenido normal: sin formato especial
                }
            }
        }
    }
}
