package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Durable, credential-free state. Caller supplies a stable host + device + conversation scope. */
class DraftStore(private val root: File) {
    init { root.mkdirs() }
    companion object {
        private val stores = mutableMapOf<String, DraftStore>()
        @Synchronized fun shared(root: File): DraftStore = stores.getOrPut(root.absolutePath) { DraftStore(root) }
    }

    @Synchronized fun read(scope: String): JSONObject = readFile(file(scope))
        ?: JSONObject().put("text", "").put("attachments", JSONArray())

    @Synchronized fun write(scope: String, value: JSONObject) {
        val canonical = canonicalScope(scope)
        atomic(file(canonical), value.put("scope", canonical).put("updatedAt", System.currentTimeMillis()))
    }

    /** Keeps active workers on their original record; the new routing key is
     * only an alias. Never merge independently edited drafts or cross devices. */
    @Synchronized fun bindWorkspaceAlias(previous: String, replacement: String) {
        val oldParts = previous.split('\u0000')
        val newParts = replacement.split('\u0000')
        require(oldParts.size == 3 && newParts.size == 3 && oldParts.take(2) == newParts.take(2) &&
            oldParts.take(2).all(String::isNotBlank) && oldParts[2].startsWith("draft:") &&
            newParts[2].matches(Regex("draft:remote-workspace://[a-f0-9]{64}"))) { "Invalid workspace draft migration" }
        val source = canonicalScope(previous)
        val target = canonicalScope(replacement)
        if (source == target) return
        val aliases = readAliases()
        val sourceFile = file(source)
        val targetFile = file(target)
        fun saved(location: File): JSONObject? = if (location.exists()) {
            readFile(location) ?: error("Workspace draft could not be read; migration paused")
        } else null
        val sourceDraft = saved(sourceFile)
        val targetDraft = saved(targetFile)
        if (targetDraft != null && !hasDraftContent(sourceDraft)) {
            // Merely opening a workspace can persist an empty draft. Keep the
            // existing destination (including uploads/write keys) authoritative;
            // redirect the empty source without overwriting or deleting either file.
            aliases.put(source, target)
        } else {
            check(!hasDraftContent(targetDraft)) { "Both workspace drafts exist; migration requires conflict handling" }
            aliases.put(target, source)
        }
        atomic(File(root, "workspace.aliases"), aliases)
    }

    private fun hasDraftContent(draft: JSONObject?): Boolean {
        if (draft == null) return false
        if (draft.optString("text").isNotEmpty() || (draft.optJSONArray("attachments")?.length() ?: 0) > 0) return true
        // Treat write/retry keys and unknown future state conservatively. Only
        // an empty editor's known bookkeeping can be folded into another draft.
        val bookkeeping = setOf("text", "attachments", "server", "device", "chunked", "scope", "updatedAt")
        return draft.keys().asSequence().any { it !in bookkeeping }
    }

    private fun readAliases(): JSONObject {
        val location = File(root, "workspace.aliases")
        if (!location.exists()) return JSONObject()
        return readFile(location) ?: error("Workspace draft aliases could not be read")
    }

    private fun canonicalScope(scope: String): String {
        val aliases = readAliases()
        var current = scope
        val seen = mutableSetOf<String>()
        while (aliases.has(current)) {
            check(seen.add(current) && seen.size <= 64) { "Invalid workspace draft aliases" }
            val next = aliases.getString(current)
            check(next.split('\u0000').take(2) == current.split('\u0000').take(2)) { "Invalid workspace draft alias scope" }
            current = next
        }
        return current
    }

    @Synchronized fun updateAttachment(scope: String, id: String, change: (JSONObject) -> Unit): Boolean {
        val draft = read(scope)
        val items = draft.optJSONArray("attachments") ?: return false
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            if (item.optString("localId") == id) {
                change(item)
                write(scope, draft)
                return true
            }
        }
        return false
    }

    /** A rejected first turn has no resumable remote history. Keep it a local draft. */
    @Synchronized fun restoreRejectedNewChat(from: String, to: String): Boolean {
        require(from.split('\u0000').take(2) == to.split('\u0000').take(2))
        val destination = read(to)
        if (destination.optString("text").isNotBlank() ||
            (destination.optJSONArray("attachments")?.length() ?: 0) > 0) return false
        val saved = read(from)
        listOf("threadKey", "threadFingerprint", "turnKey", "turnFingerprint").forEach(saved::remove)
        write(to, saved)
        write(from, JSONObject().put("text", "").put("attachments", JSONArray()))
        return true
    }

    @Synchronized fun scopes(): List<String> = root.listFiles().orEmpty().filter { it.extension == "json" }
        .mapNotNull { readFile(it)?.optString("scope")?.takeIf(String::isNotBlank) }

    @Synchronized fun attachment(scope: String, id: String): JSONObject? {
        val items = read(scope).optJSONArray("attachments") ?: return null
        return (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.optString("localId") == id }
    }

    @Synchronized fun removeAttachment(scope: String, id: String) {
        val draft = read(scope)
        val items = draft.optJSONArray("attachments") ?: return
        val remaining = JSONArray()
        val deletions = draft.optJSONArray("deletions") ?: JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("localId") != id) remaining.put(item)
            else if (item.optString("remoteId").isNotBlank()) deletions.put(item.getString("remoteId"))
        }
        write(scope, draft.put("attachments", remaining).put("deletions", deletions))
    }

    fun importFile(input: InputStream, maxBytes: Long = 20L * 1024 * 1024, cancelled: () -> Boolean = { false }): Pair<File, String> {
        val destination = File(root, "${UUID.randomUUID()}.payload")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (cancelled()) throw java.io.IOException("Attachment import interrupted")
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Attachment exceeds 20 MB" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                require(total > 0) { "Attachment is empty" }
                output.fd.sync()
            }
            return destination to digest.digest().joinToString("") { "%02x".format(it) }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /** Only deletes app-owned payloads; metadata and remote deletion are managed separately. */
    @Synchronized fun cleanupOrphans(now: Long = System.currentTimeMillis()) {
        scopes().forEach { scope ->
            val draft = read(scope)
            if (now - draft.optLong("updatedAt", now) > 30L * 24 * 60 * 60_000) {
                val items = draft.optJSONArray("attachments") ?: JSONArray()
                val deletions = draft.optJSONArray("deletions") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.optString("type") != "remoteAttachment") continue
                    if (item.optString("remoteId").isNotBlank()) deletions.put(item.getString("remoteId"))
                    listOf("localFile", "previewPath", "sourceUri", "remoteId").forEach(item::remove)
                    item.put("path", "").put("state", "expired")
                        .put("description", "Attachment expired after 30 days; remove and select again")
                }
                write(scope, draft.put("deletions", deletions))
            }
        }
        val referenced = root.listFiles().orEmpty().filter { it.extension == "json" }
            .mapNotNull(::readFile).flatMap { draft ->
                val items = draft.optJSONArray("attachments") ?: JSONArray()
                (0 until items.length()).map { items.getJSONObject(it).optString("localFile") }
            }.toSet()
        root.listFiles().orEmpty().filter {
            it.extension == "payload" && it.absolutePath !in referenced && now - it.lastModified() > 24 * 60 * 60_000L
        }.forEach { it.delete() }
    }

    private fun file(scope: String): File = File(root, MessageDigest.getInstance("SHA-256")
        .digest(canonicalScope(scope).toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    private fun readFile(file: File): JSONObject? = runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun atomic(file: File, value: JSONObject) {
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(value.toString().toByteArray())
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not save draft" }
    }
}
