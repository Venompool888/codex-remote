package app.codexremote.android

import org.json.JSONObject

internal object WorkspaceMigration {
    fun mappings(paths: List<String>, result: JSONObject, optionalPaths: Set<String> = emptySet()): Map<String, String> {
        val entries = result.optJSONArray("workspaces") ?: error("Missing workspace migration results")
        check(entries.length() == paths.size) { "Incomplete workspace migration results" }
        val mapping = linkedMapOf<String, String>()
        val indices = mutableSetOf<Int>()
        for (position in 0 until entries.length()) {
            val row = entries.getJSONObject(position)
            val rawIndex = row.opt("index")
            check(rawIndex is Int) { "Invalid workspace migration index" }
            val index = rawIndex
            check(index in paths.indices && indices.add(index)) { "Invalid workspace migration result" }
            val available = row.opt("available")
            check(available is Boolean) { "Invalid workspace availability" }
            if (!available && paths[index] in optionalPaths) continue
            check(available) { "A saved workspace is unavailable; its draft is retained" }
            val reference = row.optString("cwd")
            check(reference.matches(Regex("remote-workspace://[a-f0-9]{64}"))) { "Invalid workspace reference" }
            mapping[paths[index]] = reference
        }
        check(mapping.values.toSet().size == mapping.size) { "Ambiguous workspace migration results" }
        return mapping
    }
}
