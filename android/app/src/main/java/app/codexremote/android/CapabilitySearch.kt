package app.codexremote.android

import java.util.Locale

/** Separate fields so a name ending in "x" and description starting "R" cannot match "xr". */
internal fun matchesCapabilityQuery(name: String, description: String, query: String): Boolean {
    fun normalize(value: String) = value.lowercase(Locale.ROOT)
        .replace(Regex("[-_\\p{Pd}]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
    val terms = normalize(query).split(' ').filter(String::isNotEmpty)
    val searchable = normalize(name) + " " + normalize(description)
    return terms.all(searchable::contains)
}
