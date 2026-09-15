package eu.kanade.domain.ui.model

/**
 * Bottom navigation tab identity. Order persistence stores these names;
 * identity is the enum constant (not visual position), so reordering can
 * never lose or duplicate a tab. Malformed/legacy data falls back to the
 * default order.
 */
enum class NavTab {
    LIBRARY,
    RECENT,
    FEED,
    BROWSE,
    MORE, ;

    companion object {
        val DEFAULT_ORDER: List<NavTab> = entries.toList()

        /**
         * Parse a persisted CSV of tab names. Unknown names are dropped,
         * missing tabs are appended in default order, duplicates removed —
         * the result is always exactly one instance of every tab.
         */
        fun parseOrder(raw: String?): List<NavTab> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = raw.split(',').mapNotNull { name ->
                entries.firstOrNull { it.name == name.trim() }
            }.distinct()
            if (parsed.size != entries.size) {
                val missing = DEFAULT_ORDER.filterNot { it in parsed }
                return parsed + missing
            }
            return parsed
        }

        fun serializeOrder(order: List<NavTab>): String = order.joinToString(",") { it.name }
    }
}
