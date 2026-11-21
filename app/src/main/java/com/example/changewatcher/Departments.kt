package com.example.changewatcher

/*
 * Departments.kt
 *
 * Lista de departamentos de Paraguay y la lógica para detectar cuáles de ellos
 * se mencionan dentro del texto de un aviso.
 */

/**
 * Un departamento que el usuario puede vigilar.
 *
 * @param id identificador estable; es lo que se guarda en los ajustes.
 * @param label nombre que se muestra al usuario (con acentos).
 * @param aliases formas de escribir el nombre que se buscan en el aviso. Deben estar en
 *   minúsculas y sin acentos, porque el texto de la página se normaliza igual antes de buscar
 *   (ver [SnapshotStore.fold]).
 */
data class Department(
    val id: String,
    val label: String,
    val aliases: List<String>,
) {
    /**
     * Expresión regular que une todos los alias con "|".
     * Solo coincide con palabras completas: las condiciones (?<!\p{L}) y (?!\p{L}) exigen que
     * antes y después no haya otra letra. Así "Alto Paraguay" nunca se confunde con "Alto Paraná",
     * ni "Central" con una palabra más larga que lo contenga.
     */
    val regex: Regex = Regex(
        "(?<!\\p{L})(?:" + aliases.joinToString("|") { Regex.escape(it) } + ")(?!\\p{L})"
    )
}

/** Catálogo de departamentos y funciones de búsqueda sobre el texto de un aviso. */
object Departments {

    /** Los 18 departamentos más la capital, en orden alfabético. */
    val all: List<Department> = listOf(
        Department("alto_paraguay", "Alto Paraguay", listOf("alto paraguay")),
        Department("alto_parana", "Alto Paraná", listOf("alto parana")),
        Department("amambay", "Amambay", listOf("amambay")),
        Department("asuncion", "Asunción (capital)", listOf("asuncion")),
        Department("boqueron", "Boquerón", listOf("boqueron")),
        Department("caaguazu", "Caaguazú", listOf("caaguazu")),
        Department("caazapa", "Caazapá", listOf("caazapa")),
        Department("canindeyu", "Canindeyú", listOf("canindeyu")),
        Department("central", "Central", listOf("central")),
        Department("concepcion", "Concepción", listOf("concepcion")),
        Department("cordillera", "Cordillera", listOf("cordillera")),
        Department("guaira", "Guairá", listOf("guaira")),
        Department("itapua", "Itapúa", listOf("itapua")),
        Department("misiones", "Misiones", listOf("misiones")),
        Department("neembucu", "Ñeembucú", listOf("neembucu")),
        Department("paraguari", "Paraguarí", listOf("paraguari")),
        // Presidente Hayes tiene varias abreviaturas habituales en los avisos
        Department(
            "presidente_hayes", "Presidente Hayes",
            listOf("presidente hayes", "pte. hayes", "pte hayes", "pdte. hayes", "pdte hayes")
        ),
        Department("san_pedro", "San Pedro", listOf("san pedro")),
    )

    /** Convierte un conjunto de ids (los guardados en los ajustes) en los departamentos correspondientes. */
    fun byIds(ids: Set<String>): List<Department> = all.filter { it.id in ids }

    /**
     * Devuelve cuáles de los departamentos seleccionados se mencionan en [text].
     * El texto se normaliza (minúsculas, sin acentos) y luego se busca cada departamento con su regex.
     * Es lo que decide si se envía la notificación.
     */
    fun findAffected(text: String, selected: Set<String>): List<Department> {
        val folded = SnapshotStore.fold(text)
        return byIds(selected).filter { it.regex.containsMatchIn(folded) }
    }

    /**
     * Devuelve las posiciones (inicio..fin) donde aparece un departamento seleccionado dentro de [text].
     * Se usa para resaltarlos en amarillo al mostrar el aviso guardado.
     *
     * Como [SnapshotStore.fold] conserva el largo del texto, las posiciones encontradas en el texto
     * normalizado sirven también para el texto original. Se limita a 500 coincidencias para evitar
     * trabajo excesivo con textos muy grandes.
     */
    fun matchRanges(text: String, selected: Set<String>): List<IntRange> {
        val folded = SnapshotStore.fold(text)
        val ranges = mutableListOf<IntRange>()
        for (d in byIds(selected)) {
            for (m in d.regex.findAll(folded)) {
                ranges.add(m.range)
                if (ranges.size >= 500) return ranges
            }
        }
        return ranges
    }
}
