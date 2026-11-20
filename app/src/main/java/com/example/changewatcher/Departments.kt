package com.example.changewatcher

data class Department(
    val id: String,
    val label: String,
    /** Spellings to look for, already lower case and without accents (the page text is normalized the same way). */
    val aliases: List<String>,
) {
    /** Matches whole words only, so "Alto Paraguay" is never confused with "Alto Paraná". */
    val regex: Regex = Regex(
        "(?<!\\p{L})(?:" + aliases.joinToString("|") { Regex.escape(it) } + ")(?!\\p{L})"
    )
}

object Departments {

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
        Department(
            "presidente_hayes", "Presidente Hayes",
            listOf("presidente hayes", "pte. hayes", "pte hayes", "pdte. hayes", "pdte hayes")
        ),
        Department("san_pedro", "San Pedro", listOf("san pedro")),
    )

    fun byIds(ids: Set<String>): List<Department> = all.filter { it.id in ids }

    /** Which of the selected departments are mentioned in [text]. */
    fun findAffected(text: String, selected: Set<String>): List<Department> {
        val folded = SnapshotStore.fold(text)
        return byIds(selected).filter { it.regex.containsMatchIn(folded) }
    }

    /** Positions in [text] where a selected department is mentioned (used for highlighting). */
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
