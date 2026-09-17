package app.codexremote.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RemoteProject(
    val id: String,
    val name: String,
    val connectionName: String,
    val serverUrl: String,
    val workspace: String,
)

data class RemoteConnection(
    val name: String,
    val serverUrl: String,
    val projects: List<RemoteProject>,
)

class RemoteProjectStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun list(): List<RemoteProject> = decode(preferences.getString(KEY_PROJECTS, null))

    // Keep project identities for connection ownership and discovery deduplication.
    // Recycling only changes sidebar visibility; it never deletes host data.
    fun visibleProjects(): List<RemoteProject> = distinctWorkspaces(list().filterNot { it.id in trashedIds() }, activeProjectId())

    fun trashedProjects(): List<RemoteProject> = distinctWorkspaces(list().filter { it.id in trashedIds() }, activeProjectId())

    fun trashProject(id: String) {
        val aliases = workspaceAliasIds(list(), id)
        if (aliases.isEmpty()) return
        preferences.edit().putStringSet(KEY_TRASHED_IDS, trashedIds() + aliases).apply()
    }

    fun restoreProject(id: String) {
        preferences.edit().putStringSet(KEY_TRASHED_IDS, trashedIds() - workspaceAliasIds(list(), id)).apply()
    }

    private fun trashedIds(): Set<String> = preferences.getStringSet(KEY_TRASHED_IDS, emptySet()).orEmpty().toSet()

    fun connections(): List<RemoteConnection> = mergeConnections(groupConnections(list()), savedConnections())

    private fun savedConnections(): List<RemoteConnection> = decodeConnections(preferences.getString(KEY_CONNECTIONS, null))

    fun saveConnection(serverUrl: String, name: String = "") {
        val server = serverUrl.trim().trimEnd('/')
        val connections = savedConnections().filterNot { it.serverUrl == server } +
            RemoteConnection(name.ifBlank { hostLabel(server) }, server, emptyList())
        preferences.edit().putString(KEY_CONNECTIONS, encodeConnections(connections)).apply()
    }

    fun save(project: RemoteProject) {
        val projects = list().filterNot { it.id == project.id } + project
        preferences.edit().putString(KEY_PROJECTS, encode(projects)).apply()
    }

    fun mergeDiscovered(serverUrl: String, connectionName: String, workspaces: List<String>, workspaceNames: Map<String, String> = emptyMap()): List<RemoteProject> {
        val projects = mergeDiscoveredProjects(list(), serverUrl, connectionName, workspaces, workspaceNames)
        preferences.edit().putString(KEY_PROJECTS, encode(projects)).apply()
        return projects
    }

    fun delete(id: String) {
        preferences.edit().putString(KEY_PROJECTS, encode(list().filterNot { it.id == id }))
            .putStringSet(KEY_TRASHED_IDS, trashedIds() - id).apply()
        if (activeProjectId() == id) preferences.edit().remove(KEY_ACTIVE_PROJECT).apply()
    }

    /** Remove all local projects for these hosts in one preferences update. */
    fun deleteConnections(serverUrls: Set<String>): List<RemoteProject> {
        val remaining = withoutConnections(list(), serverUrls)
        val targets = serverUrls.map { it.trim().trimEnd('/') }.toSet()
        preferences.edit().apply {
            putString(KEY_PROJECTS, encode(remaining))
            putString(KEY_CONNECTIONS, encodeConnections(savedConnections().filterNot { it.serverUrl in targets }))
            putStringSet(KEY_TRASHED_IDS, trashedIds().intersect(remaining.map { it.id }.toSet()))
            if (remaining.none { it.id == activeProjectId() }) remove(KEY_ACTIVE_PROJECT)
        }.apply()
        return remaining
    }

    fun updateConnection(oldServerUrl: String, name: String, serverUrl: String) {
        val projects = updateConnectionProjects(list(), oldServerUrl, name, serverUrl)
        val saved = savedConnections().filterNot { it.serverUrl == oldServerUrl.trim().trimEnd('/') || it.serverUrl == serverUrl.trim().trimEnd('/') } +
            RemoteConnection(name.ifBlank { hostLabel(serverUrl) }, serverUrl.trim().trimEnd('/'), emptyList())
        preferences.edit().putString(KEY_PROJECTS, encode(projects)).putString(KEY_CONNECTIONS, encodeConnections(saved)).apply()
    }

    fun activeProjectId(): String? = preferences.getString(KEY_ACTIVE_PROJECT, null)

    fun setActiveProject(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY_ACTIVE_PROJECT) else putString(KEY_ACTIVE_PROJECT, id)
        }.apply()
    }

    fun migrateLegacy(serverUrl: String, workspace: String, workspaceName: String = ""): RemoteProject? {
        if (list().isNotEmpty() || serverUrl.isBlank() || workspace.isBlank()) return null
        val project = RemoteProject(
            id = UUID.randomUUID().toString(),
            name = workspaceDisplayName(workspace, workspaceName),
            connectionName = hostLabel(serverUrl),
            serverUrl = serverUrl.trimEnd('/'),
            workspace = workspace,
        )
        save(project)
        setActiveProject(project.id)
        return project
    }

    companion object {
        internal fun requiresOpaqueRouting(projects: List<RemoteProject>, serverUrl: String): Boolean =
            projects.any { it.serverUrl.trim().trimEnd('/') == serverUrl.trim().trimEnd('/') &&
                it.workspace.startsWith("remote-workspace://") }

        private fun workspaceIdentity(project: RemoteProject) =
            project.serverUrl.trim().trimEnd('/') to normalizeWorkspace(project.workspace)

        // Migration can unify a discovered path and an already saved opaque ID.
        // Collapse only proven identities, never names; retain all stored records
        // and their IDs so drafts, active selection and recycling remain intact.
        internal fun distinctWorkspaces(projects: List<RemoteProject>, activeId: String?): List<RemoteProject> =
            projects.groupBy(::workspaceIdentity).values.map { aliases ->
                aliases.firstOrNull { it.id == activeId } ?: aliases.first()
            }

        internal fun workspaceAliasIds(projects: List<RemoteProject>, id: String): Set<String> {
            val project = projects.firstOrNull { it.id == id } ?: return emptySet()
            return projects.filter { workspaceIdentity(it) == workspaceIdentity(project) }.map { it.id }.toSet()
        }

        internal fun withoutConnections(projects: List<RemoteProject>, serverUrls: Set<String>): List<RemoteProject> {
            val targets = serverUrls.map { it.trim().trimEnd('/') }.filter(String::isNotBlank).toSet()
            return projects.filterNot { it.serverUrl.trim().trimEnd('/') in targets }
        }

        private const val PREFERENCES = "remote_projects"
        private const val KEY_PROJECTS = "projects"
        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_TRASHED_IDS = "trashed_project_ids"
        private const val KEY_ACTIVE_PROJECT = "active_project"

        fun create(
            name: String,
            connectionName: String,
            serverUrl: String,
            workspace: String,
        ) = RemoteProject(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            connectionName = connectionName.trim(),
            serverUrl = serverUrl.trim().trimEnd('/'),
            workspace = workspace.trim(),
        )

        internal fun encode(projects: List<RemoteProject>): String = JSONArray().apply {
            projects.forEach { project ->
                put(JSONObject()
                    .put("id", project.id)
                    .put("name", project.name)
                    .put("connectionName", project.connectionName)
                    .put("serverUrl", project.serverUrl)
                    .put("workspace", project.workspace))
            }
        }.toString()

        internal fun decode(value: String?): List<RemoteProject> = runCatching {
            val array = JSONArray(value ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    val connectionName = item.optString("connectionName")
                    val serverUrl = item.optString("serverUrl").trimEnd('/')
                    val workspace = item.optString("workspace")
                    if (id.isNotBlank() && name.isNotBlank() && serverUrl.isNotBlank() && workspace.isNotBlank()) {
                        add(RemoteProject(id, name, connectionName.ifBlank { hostLabel(serverUrl) }, serverUrl, workspace))
                    }
                }
            }
        }.getOrDefault(emptyList())

        internal fun mergeDiscoveredProjects(
            existing: List<RemoteProject>,
            serverUrl: String,
            connectionName: String,
            workspaces: List<String>,
            workspaceNames: Map<String, String> = emptyMap(),
            idFactory: () -> String = { UUID.randomUUID().toString() },
        ): List<RemoteProject> {
            val normalizedServer = serverUrl.trim().trimEnd('/')
            val known = existing
                .filter { it.serverUrl.trimEnd('/') == normalizedServer }
                .mapTo(mutableSetOf()) { normalizeWorkspace(it.workspace) }
            val discovered = workspaces
                .map(::normalizeWorkspace)
                .filter(String::isNotBlank)
                .distinct()
                .filter(known::add)
                .map { workspace ->
                    RemoteProject(
                        id = idFactory(),
                        name = workspaceDisplayName(workspace, workspaceNames[workspace].orEmpty()),
                        connectionName = connectionName.ifBlank { hostLabel(normalizedServer) },
                        serverUrl = normalizedServer,
                        workspace = workspace,
                    )
                }
            return existing + discovered
        }

        internal fun mergeConnections(grouped: List<RemoteConnection>, saved: List<RemoteConnection>): List<RemoteConnection> =
            (saved + grouped).groupBy { it.serverUrl }.map { (_, entries) ->
                entries.first().copy(projects = entries.flatMap { it.projects }.distinctBy { it.id })
            }

        internal fun encodeConnections(connections: List<RemoteConnection>): String = JSONArray().apply {
            connections.forEach { put(JSONObject().put("serverUrl", it.serverUrl).put("name", it.name)) }
        }.toString()

        internal fun decodeConnections(value: String?): List<RemoteConnection> = runCatching {
            val array = JSONArray(value ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val server = item.optString("serverUrl").trim().trimEnd('/')
                if (projectInputError(server, "/") != null) null else
                    RemoteConnection(item.optString("name").ifBlank { hostLabel(server) }, server, emptyList())
            }
        }.getOrDefault(emptyList())

        internal fun groupConnections(projects: List<RemoteProject>): List<RemoteConnection> = projects
            .groupBy { it.serverUrl.trim().trimEnd('/') }
            .map { (serverUrl, relatedProjects) ->
                RemoteConnection(
                    name = relatedProjects.firstOrNull()?.connectionName.orEmpty().ifBlank { hostLabel(serverUrl) },
                    serverUrl = serverUrl,
                    projects = relatedProjects,
                )
            }

        internal fun updateConnectionProjects(
            projects: List<RemoteProject>,
            oldServerUrl: String,
            name: String,
            serverUrl: String,
        ): List<RemoteProject> {
            val oldServer = oldServerUrl.trim().trimEnd('/')
            val newServer = serverUrl.trim().trimEnd('/')
            val connectionName = name.trim().ifBlank { hostLabel(newServer) }
            return projects.map { project ->
                if (project.serverUrl.trim().trimEnd('/') == oldServer) {
                    project.copy(connectionName = connectionName, serverUrl = newServer)
                } else {
                    project
                }
            }
        }

        private fun normalizeWorkspace(workspace: String): String = workspace.trim().let {
            if (it == "/") it else it.trimEnd('/')
        }

        private fun hostLabel(serverUrl: String): String = serverUrl
            .substringAfter("://", serverUrl)
            .substringBefore('/')
            .substringBefore(':')
            .ifBlank { "Remote" }
    }
}
