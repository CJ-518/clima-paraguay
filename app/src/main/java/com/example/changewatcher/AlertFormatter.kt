package com.example.changewatcher

import android.graphics.Color
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
private val COLOR_TITLE = Color.parseColor("#0D47A1")      // azul oscuro: títulos y etiquetas
private val COLOR_ALERT = Color.parseColor("#B71C1C")      // rojo oscuro: fenómeno esperado
private val COLOR_SELECTED = Color.parseColor("#2E7D32")   // verde: departamentos elegidos por el usuario

object AlertFormatter {

    /**
     * Devuelve el aviso listo para mostrar: texto reorganizado, con negritas, tamaños y colores,
     * y con la letra de los departamentos de [selected] en verde y negrita.
     */
    fun format(raw: String, selected: Set<String>): SpannableString {
        // 1) Reorganiza el texto con espacios entre secciones y apunta qué es cada línea
        val (text, lines) = layout(raw)
        val spannable = SpannableString(text)

        // 2) Negritas, tamaños y colores según el tipo de línea y su sección
        styleLines(spannable, lines)

        // 3) Cambia el color de la letra de los departamentos elegidos donde aparezcan.
        //    Las posiciones se calculan sobre el texto ya reorganizado, por eso coinciden.
        //    Se aplica al final para que prevalezca sobre los colores de la sección.
        for (r in Departments.matchRanges(text, selected)) {
            spannable.setSpan(
                ForegroundColorSpan(COLOR_SELECTED),
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
}
