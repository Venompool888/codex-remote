package app.codexremote.android

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.LruCache
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.codexremote.android.compose.*
import app.codexremote.android.presentation.artifacts.*
import app.codexremote.android.presentation.catalog.*
import app.codexremote.android.presentation.composer.*
import app.codexremote.android.presentation.connections.*
import app.codexremote.android.presentation.conversation.*
import app.codexremote.android.presentation.interactions.*
import app.codexremote.android.presentation.projects.*
import app.codexremote.android.presentation.sidebar.*
import app.codexremote.android.ui.CodexApp
import app.codexremote.android.ui.projects.*
import app.codexremote.android.ui.interactions.*
import app.codexremote.android.ui.common.RuntimeDialog
import app.codexremote.android.ui.common.RuntimeDialogState
import app.codexremote.android.ui.common.RuntimeDialogAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

internal fun applySidebarRunningEvent(
    threads: List<RemoteThread>,
    method: String,
    params: JSONObject
): List<RemoteThread>? {
    val threadId = params.optString("threadId").ifBlank {
        params.optJSONObject("thread")?.optString("id").orEmpty()
    }
    if (threadId.isBlank()) return null

    val status = when (method) {
        "turn/started" -> "active"
        "turn/completed" -> "completed"
        "turn/failed" -> "failed"
        "turn/cancelled" -> "cancelled"
        "thread/status/changed" -> params.optJSONObject("status")?.optString("type")?.takeIf(String::isNotBlank)
        else -> null
    } ?: return null
    val running = status.lowercase(Locale.ROOT) in setOf("active", "running", "inprogress", "in_progress", "started", "pending")
    var matched = false
    val updated = threads.map { thread ->
        if (thread.id == threadId) {
            matched = true
            thread.copy(status = status, isRunning = running)
        } else {
            thread
        }
    }
    return updated.takeIf { matched }
}

class MainActivity : AppCompatActivity() {
    private val BACKGROUND get() = getColor(R.color.app_background)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var utilityClient: RemoteClient
    private val capabilityPresentationCache = CapabilityPresentationCache()
    private var capabilityCatalogInvalidated: ((String) -> Unit)? = null
    private val connectionClients = mutableMapOf<String, RemoteClient>()
    private val workspaceMigrationAttempts = mutableMapOf<String, Long>()
    private val connectedServerUrls = mutableSetOf<String>()
    private val connectingServerUrls = mutableSetOf<String>()
    private val reconnectAttempts = mutableMapOf<String, Int>()
    private var connectionSelectionMode = false
    private val selectedConnectionUrls = mutableSetOf<String>()
    private val threadPages = ThreadPages()
    private val threadsByServer = mutableMapOf<String, List<RemoteThread>>()
    private val workspacesByServer = mutableMapOf<String, List<String>>()
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var projectStore: RemoteProjectStore
    private var pendingInteractionDrafts = JSONObject()
    private val callbacks = mutableMapOf<String, (JSONObject) -> Unit>()
    private val diagnosticLogs by lazy { DiagnosticLogs.get(this) }
    private val rpcDiagnostics by lazy { RpcDiagnostics(diagnosticLogs::record) }
    private var diagnosticReport by mutableStateOf<String?>(null)
    private var diagnosticReadGeneration = 0L
    private var diagnosticExportBusy = false
    private var lastDiagnosticThreadState: String? = null
    private var pendingDiagnosticSave: File? = null
    private val saveDiagnosticBundle = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingDiagnosticSave
        pendingDiagnosticSave = null
        if (uri != null && file != null) {
            Thread {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                        ?: error("Could not open destination")
                }.isSuccess
                runOnUiThread { if (!isDestroyed && !isFinishing) toast(if (ok) "Diagnostic bundle saved" else "Could not save diagnostic bundle") }
            }.start()
        }
    }
    private val callbackErrors = mutableMapOf<String, (String) -> Unit>()
    private val imageCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRemoteMonitor() else toast("Notifications are off; keep Remote open to see task requests")
    }
    private val choosePhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        acceptPickedAttachments(uris)
    }
    private val chooseAttachments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        acceptPickedAttachments(uris)
    }
    private fun rememberPickerScope() {
        persistComposer()
        getSharedPreferences("attachment_picker", MODE_PRIVATE).edit().putString("scope", composerScope).commit()
    }
    private fun acceptPickedAttachments(uris: List<android.net.Uri>) {
        val preferences = getSharedPreferences("attachment_picker", MODE_PRIVATE)
        val scope = preferences.getString("scope", null) ?: composerScope ?: return
        preferences.edit().remove("scope").apply()
        val saved = draftStore.read(scope)
        val available = (4 - jsonObjects(saved.optJSONArray("attachments")).count { it.optString("type") == "remoteAttachment" }).coerceAtLeast(0)
        if (uris.size > available) toast("A message can include up to four files")
        uris.take(available).forEach { queueAttachment(it, scope) }
    }
    private var restoreNavigation = true
    private var threadLoadError: String? = null
    private val navigationRequests = NavigationRequests()
    private var currentThreadId: String? = null
    private var currentThread: JSONObject? = null
    private var sidebarSwipeEnabled = false
    private var draftWorkspace: String? = null
    private var selectedWorkspace: String? = null
    private var listedThreads: List<RemoteThread> = emptyList()
    private var listedWorkspaces: List<String> = emptyList()
    private val workspaceSessionSources = mutableMapOf<String, List<RemoteThread>>()
    private val workspacesShowingAllSessions = mutableSetOf<String>()
    private lateinit var draftStore: DraftStore
    private var composerScope by mutableStateOf<String?>(null)
    private var composerServer: String? = null
    private val transfer = AttachmentTransfer()
    private val draftImageOpenGate = app.codexremote.android.compose.DraftImageOpenGate()
    private val uploadUiTick = object : Runnable {
        override fun run() {
            val scope = composerScope
            if (scope != null) {
                var attachmentChanged = false
                val saved = draftStore.read(scope)
                jsonObjects(saved.optJSONArray("attachments")).forEach { item ->
                    val index = composerAttachments.indexOfFirst { it.localId == item.optString("localId") }
                    if (index >= 0) {
                        val updated = ComposerAttachment.fromJson(item)
                        attachmentChanged = attachmentChanged || composerAttachments[index] != updated
                        composerAttachments[index] = updated
                    }
                }
                if (attachmentChanged) updateComposerPrimaryButton()
            }
            main.postDelayed(this, 750)
        }
    }
    private var composerDraft = ""
    private val composerAttachments = mutableListOf<ComposerAttachment>()
    private val remoteSkillsCache = mutableMapOf<String, List<ComposerAttachment>>()
    private val remotePluginsCache = mutableMapOf<String, List<ComposerAttachment>>()
    private val remoteAppsCache = mutableMapOf<String, List<RemoteInstalledApp>>()
    private var turnRunning = false
    private var models: List<ModelOption> = emptyList()
    private var permissionProfiles: List<PermissionProfile> = emptyList()
    private var selectedModel: String? = null
    private var selectedModelDisplay: String? = null
    private var selectedEffort: String? = null
    private var selectedServiceTier: String? = null
    private var planMode = false
    private val submittingScopes = mutableSetOf<String>()
    private val outgoingMessages = OutgoingMessageTracker()
    private val pendingSubmissionDrafts = mutableMapOf<String, JSONObject>()
    private var selectedPermissionId: String? = null
    private val usageByThread = mutableMapOf<String, UsageSnapshot>()
    private val liveTimelineStore = LiveTimelineStore()
    private var turnLifecycleRevision = 0L
    private var liveAssistantText = ""
    private var followTimelineLatest = true
    private var liveTimelineRenderScheduled = false
    private var displayedConversationKey: String? = null
    private var renderedTimelineSignature: Int? = null
    private val expandedTimelineCommentaryItems = mutableSetOf<String>()
    private var conversationHeaderHeight = 0
    private var conversationWorkspaceLabel = ""
    private val connectionFailureReasons = mutableMapOf<String, String>()
    private var remoteProjects: List<RemoteProject> = emptyList()
    private var activeProjectId: String? = null
    private var connectedServerUrl: String? = null
    private var sidebarConnectionUrl: String? = null
    private var pendingSidebarState: Bundle? = null
    private var sidebarProjectScope: Pair<String?, String>? = null
    private var sidebarSearchQuery = ""
    private var sidebarSearchVisible = false
    private var sidebarAllProjects = false
    private var pendingProject: RemoteProject? = null
    private var pendingDraftProject: RemoteProject? = null
    private var refreshScheduled = false
    private var refreshInFlight = false
    private var refreshAgain = false
    private var clearLiveOnNextRefresh = false
    private val refreshRunnable = Runnable {
        refreshScheduled = false
        refreshCurrentThread()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        super.onCreate(savedInstanceState)
        diagnosticLogs.record("app.started")
        savedInstanceState?.getString("diagnosticSaveFile")?.takeIf {
            it.matches(Regex("codex-remote-diagnostics-[a-f0-9-]+\\.zip"))
        }?.let { name -> pendingDiagnosticSave = File(cacheDir, "diagnostic-exports/$name").takeIf { it.isFile } }
        pendingSidebarState = savedInstanceState?.getBundle("remoteSidebar")
        pendingInteractionDrafts = runCatching { JSONObject(savedInstanceState?.getString("interactionDrafts") ?: "{}") }.getOrDefault(JSONObject())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        tokenStore = SecureTokenStore(this)
        projectStore = RemoteProjectStore(this)
        draftStore = DraftStore.shared(File(filesDir, "drafts"))
        draftStore.cleanupOrphans()
        TaskNotifications.channels(this)
        if (getSharedPreferences("remote_settings", MODE_PRIVATE).getBoolean("background_monitor", false)) startRemoteMonitor()
        main.post(uploadUiTick)
        utilityClient = RemoteClient(object : RemoteClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected(reason: String) = Unit
            override fun onMessage(message: JSONObject) = Unit
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        val legacyPreferences = getSharedPreferences("remote_settings", MODE_PRIVATE)
        val savedServer = legacyPreferences.getString("server_url", "").orEmpty().trimEnd('/')
        val savedWorkspace = legacyPreferences.getString("workspace", "").orEmpty()
        projectStore.migrateLegacy(savedServer, savedWorkspace)
        remoteProjects = projectStore.list()
        activeProjectId = projectStore.activeProjectId()?.takeIf { id -> remoteProjects.any { it.id == id } }
            ?: remoteProjects.firstOrNull()?.id
        projectStore.setActiveProject(activeProjectId)
        val activeServer = remoteProjects.firstOrNull { it.id == activeProjectId }?.serverUrl ?: savedServer
        connectedServerUrl = intent.getStringExtra("remote_server")?.takeIf { server -> remoteProjects.any { it.serverUrl == server } }
            ?: activeServer.takeIf(String::isNotBlank)
        sidebarConnectionUrl = connectedServerUrl
        val availableConnections = projectStore.connections()
            .mapNotNull { connection -> tokenStore.load(connection.serverUrl)?.let { connection.serverUrl to it } }
            .let { connections ->
                if (activeServer.isNotBlank() && connections.none { it.first == activeServer }) {
                    tokenStore.load(activeServer)?.let { listOf(activeServer to it) + connections } ?: connections
                } else {
                    connections.sortedByDescending { it.first == activeServer }
                }
            }
        mountCompose()
        if (availableConnections.isNotEmpty()) {
            if (remoteProjects.isEmpty()) showConnectionScreen() else showConnectingScreen()
            val requestedServer = connectedServerUrl
            availableConnections.forEach { (server, token) -> connectToServer(server, token, select = server == requestedServer) }
        } else {
            showConnectionScreen()
        }
        handlePairingLinkIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handlePairingLinkIntent(intent)) return
        val server = intent.getStringExtra("remote_server") ?: run {
            if (intent.action in setOf(android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_SEND_MULTIPLE))
                currentThread?.let(::showConversation)
            return
        }
        if (projectStore.connections().none { it.serverUrl == server }) return
        persistComposer()
        connectedServerUrl = server
        sidebarConnectionUrl = server
        if (server in connectedServerUrls) {
            intent.getStringExtra("remote_thread")?.let { intent.removeExtra("remote_thread"); openThread(it) }
        } else tokenStore.load(server)?.let { connectToServer(server, it, select = true) }
    }

    private fun handlePairingLinkIntent(intent: android.content.Intent): Boolean {
        if (intent.action != android.content.Intent.ACTION_VIEW ||
            !intent.data?.scheme.equals("codexremote", ignoreCase = true)
        ) return false
        val rawPairingLink = intent.dataString.orEmpty()
        intent.data = null
        val pairing = parsePairingLink(rawPairingLink)
        if (pairing == null) {
            toast("This pairing QR code is invalid")
            return true
        }
        closeSidebar()
        syncConnections()
        if (!addConnectionController.uiState.value.isBusy) projectsController.dismissDialog()
        addConnectionController.acceptLink(rawPairingLink)
        return true
    }

    private fun startRemoteMonitor() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        getSharedPreferences("remote_settings", MODE_PRIVATE).edit().putBoolean("background_monitor", true).apply()
        startForegroundService(android.content.Intent(this, RemoteMonitorService::class.java))
    }

    private fun enableNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else startRemoteMonitor()
    }

    override fun onStop() {
        persistComposer()
        super.onStop()
    }

    private fun onConnected(serverUrl: String): Unit = runOnUiThread {
        diagnosticLogs.record("connection.connected", serverUrl, "protocol=${connectionClients[serverUrl]?.negotiatedProtocolVersion}")
        val client = connectionClients[serverUrl] ?: return@runOnUiThread
        if (!client.opaqueWorkspaceRouting) { finishConnected(serverUrl); return@runOnUiThread }
        val device = tokenStore.loadCredential(serverUrl)?.deviceId.orEmpty()
        if (device.isBlank()) return@runOnUiThread
        val connectionEpoch = client.negotiatedConnectionEpoch
        if (connectionEpoch < 0) return@runOnUiThread
        val migrationAttempt = (workspaceMigrationAttempts[serverUrl] ?: 0L) + 1L
        workspaceMigrationAttempts[serverUrl] = migrationAttempt
        if (serverUrl == connectedServerUrl) persistComposer()
        val prefix = "$serverUrl\u0000$device\u0000draft:"
        val navigation = getSharedPreferences("remote_navigation", MODE_PRIVATE)
        val legacySettings = getSharedPreferences("remote_settings", MODE_PRIVATE)
        val requiredPaths = (projectStore.list().filter { it.serverUrl == serverUrl }.map { it.workspace } +
            listOfNotNull(navigation.getString("cwd:$serverUrl", null),
                legacySettings.getString("workspace", null).takeIf {
                    serverUrl == connectedServerUrl && legacySettings.getString("server_url", null)?.trimEnd('/') == serverUrl
                },
                selectedWorkspace.takeIf { serverUrl == connectedServerUrl },
                pendingProject?.takeIf { it.serverUrl == serverUrl }?.workspace))
            .filter { it.startsWith('/') }.distinct()
        val draftPaths = draftStore.scopes().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            .filter { it.startsWith('/') }.distinct()
        val optionalPaths = draftPaths.toSet() - requiredPaths.toSet()
        val paths = (requiredPaths + draftPaths).distinct()
        fun current() = !isDestroyed && !isFinishing && connectionClients[serverUrl] === client &&
            tokenStore.loadCredential(serverUrl)?.deviceId == device && client.opaqueWorkspaceRouting &&
            client.negotiatedConnectionEpoch == connectionEpoch && workspaceMigrationAttempts[serverUrl] == migrationAttempt
        fun fail(message: String) {
            if (!current()) return
            diagnosticLogs.record("workspace.migration.paused", serverUrl, message)
            connectionFailureReasons[serverUrl] = message
            connectingServerUrls.remove(serverUrl)
            if (pendingProject?.serverUrl == serverUrl) {
                pendingProject = null
                resetPendingProjectButton()
                projectsController.showError(message)
            }
            if (serverUrl != connectedServerUrl) { refreshOpenSidebar(); return }

            showInfo("Workspace upgrade paused", "$message\n\nYour drafts and attachments are saved.", listOf(
                RuntimeDialogAction("Retry") { if (current()) onConnected(serverUrl) },
                RuntimeDialogAction("Connections") { if (current()) { workspaceMigrationAttempts[serverUrl] = migrationAttempt + 1L; showConnectionManager() } },
                RuntimeDialogAction("Close") {}
            ))
        }
        val mapping = linkedMapOf<String, String>()
        fun migrateBatch(offset: Int) {
            if (!current()) return
            if (offset < paths.size) {
                val batch = paths.drop(offset).take(100)
                rpcOn(serverUrl, "host/workspace/migrate", JSONObject().put("paths", JSONArray(batch)), ::fail) { result ->
                    if (!current()) return@rpcOn
                    runCatching { WorkspaceMigration.mappings(batch, result, optionalPaths) }.onSuccess {
                        mapping.putAll(it); migrateBatch(offset + batch.size)
                    }.onFailure { fail(it.message ?: "Workspace migration failed; drafts are saved") }
                }
                return
            }
            runCatching {
                mapping.forEach { (old, replacement) -> draftStore.bindWorkspaceAlias(prefix + old, prefix + replacement) }
                projectStore.list().filter { it.serverUrl == serverUrl }.forEach { project ->
                    mapping[project.workspace]?.let { projectStore.save(project.copy(workspace = it)) }
                }
                navigation.getString("cwd:$serverUrl", null)?.let { old ->
                    mapping[old]?.let { check(navigation.edit().putString("cwd:$serverUrl", it).commit()) { "Workspace navigation could not be saved; drafts are retained" } }
                }
                if (serverUrl == connectedServerUrl) selectedWorkspace = mapping[selectedWorkspace] ?: selectedWorkspace
                if (serverUrl == connectedServerUrl) legacySettings.getString("workspace", null)?.let { old ->
                    mapping[old]?.let { check(legacySettings.edit().putString("workspace", it).commit()) { "Workspace settings could not be saved; drafts are retained" } }
                }
                pendingProject?.takeIf { it.serverUrl == serverUrl }?.let { project ->
                    mapping[project.workspace]?.let { pendingProject = project.copy(workspace = it) }
                }
                remoteProjects = projectStore.list()
            }.onSuccess {
                finishConnected(serverUrl)
                val unavailable = optionalPaths.count { it !in mapping }
                if (unavailable > 0 && serverUrl == connectedServerUrl) {
                    toast("$unavailable older workspace draft(s) are saved locally. Restore their workspace on the host to use them.")
                }
            }
                .onFailure { fail(it.message ?: "Workspace migration failed; drafts are saved") }
        }
        migrateBatch(0)
    }

    private fun finishConnected(serverUrl: String) = runOnUiThread {
        if (isDestroyed || isFinishing) return@runOnUiThread
        connectionFailureReasons.remove(serverUrl)
        reconnectAttempts.remove(serverUrl)
        connectingServerUrls.remove(serverUrl)
        connectedServerUrls += serverUrl
        capabilityCatalogInvalidated?.invoke(serverUrl)
        if (serverUrl != connectedServerUrl) {
            requestThreads(serverUrl = serverUrl)
            refreshOpenSidebar()
            return@runOnUiThread
        }

        updateConversationConnection()
        resumeUploads()
        val notificationThread = intent.getStringExtra("remote_thread")
        if (notificationThread != null && intent.getStringExtra("remote_server") == serverUrl) {
            intent.removeExtra("remote_thread")
            openThread(notificationThread)
            requestThreads()
            return@runOnUiThread
        }
        val project = pendingProject
        if (project != null && project.serverUrl == serverUrl) {
            validateWorkspaces(listOf(project.workspace), serverUrl = serverUrl, onError = { error ->
                if (!acceptsProjectValidation(pendingProject, project, connectedServerUrl) || isDestroyed || isFinishing) return@validateWorkspaces
                pendingProject = null
                resetPendingProjectButton()

                projectsController.showError(error)
                toast(error)
            }) { available ->
                if (!acceptsProjectValidation(pendingProject, project, connectedServerUrl) || isDestroyed || isFinishing) return@validateWorkspaces
                if (project.workspace !in available) {
                    pendingProject = null
                    resetPendingProjectButton()

                    projectsController.showError("Choose an existing remote folder")
                    toast("Choose an existing remote folder")
                    return@validateWorkspaces
                }
                projectStore.save(project)
                projectStore.setActiveProject(project.id)
                remoteProjects = projectStore.list()
                activeProjectId = project.id
                pendingProject = null
                resetPendingProjectButton()
                selectedWorkspace = project.workspace
                projectsController.dismissDialog()
                showThreadListLoading()
                requestThreads(openDefault = true)
                toast("Remote project added")
            }
            return@runOnUiThread
        }
        pendingDraftProject?.takeIf { it.serverUrl == serverUrl && it.id == activeProjectId }?.let { target ->
            pendingDraftProject = null
            openDraft(target.workspace)
            requestThreads()
            return@runOnUiThread
        }
        if (restoreNavigation) {
            restoreNavigation = false
            val navigation = getSharedPreferences("remote_navigation", MODE_PRIVATE)
            val last = navigation.getString("thread:$serverUrl", null)
            val cwd = navigation.getString("cwd:$serverUrl", null)
            val project = remoteProjects.firstOrNull { it.id == activeProjectId }
            if (!last.isNullOrBlank() && (cwd.isNullOrBlank() || project?.workspace == cwd)) {
                openThread(last)
                requestThreads()
                return@runOnUiThread
            }
        }
        if (currentThreadId != null) {
            if (currentThread != null) refreshCurrentThread()
            requestThreads()
            return@runOnUiThread
        }
        selectedWorkspace = null
        showThreadListLoading()
        requestThreads(openDefault = true)
    }

    private fun restoreOfflineDraft(server: String) {
        val navigation = getSharedPreferences("remote_navigation", MODE_PRIVATE)
        val id = navigation.getString("thread:$server", null) ?: return
        val cwd = navigation.getString("cwd:$server", null) ?: return
        val project = remoteProjects.firstOrNull { it.id == activeProjectId }
        if (project?.serverUrl != server || project.workspace != cwd) return
        val device = tokenStore.loadCredential(server)?.deviceId ?: return
        val saved = draftStore.read("$server\u0000$device\u0000$id")
        if (saved.optString("text").isBlank() && (saved.optJSONArray("attachments")?.length() ?: 0) == 0) return
        currentThreadId = id
        currentThread = JSONObject().put("id", id).put("cwd", cwd).put("name", "Saved draft")
            .put("turns", JSONArray()).put("status", "idle")
        showConversation(currentThread!!)
    }

    private fun onDisconnected(serverUrl: String, reason: String) = runOnUiThread {
        if (isDestroyed || isFinishing) return@runOnUiThread
        diagnosticLogs.record("connection.disconnected", serverUrl, reason, listOfNotNull(tokenStore.load(serverUrl)))
        capabilityPresentationCache.invalidateServer(serverUrl)
        connectionFailureReasons[serverUrl] = reason
        connectedServerUrls.remove(serverUrl)
        connectingServerUrls.remove(serverUrl)
        capabilityCatalogInvalidated?.invoke(serverUrl)
        if (pendingProject?.serverUrl == serverUrl) {
            pendingProject = null
            resetPendingProjectButton()
            projectsController.showError(reason)
        }
        if (serverUrl == connectedServerUrl) {

            if (currentThread == null) restoreOfflineDraft(serverUrl)
            // Losing the transport does not fail a task still executing on its host.
            updateConversationConnection()
        }
        refreshOpenSidebar()
        val attempt = (reconnectAttempts[serverUrl] ?: 0).plus(1).coerceAtMost(6)
        reconnectAttempts[serverUrl] = attempt
        val exponential = (2_000L * (1L shl (attempt - 1))).coerceAtMost(30_000L)
        val jitter = (serverUrl.hashCode().toLong().let(::abs) % 750L)
        main.postDelayed({
            if (serverUrl !in connectedServerUrls && serverUrl !in connectingServerUrls &&
                projectStore.connections().any { it.serverUrl == serverUrl }
            ) {
                tokenStore.load(serverUrl)?.let { connectToServer(serverUrl, it, select = false) }
            }
        }, exponential + jitter)
    }


    private fun deleteConnections(serverUrls: Set<String>, asRoot: Boolean) {
        val targets = serverUrls.map { it.trim().trimEnd('/') }.filter(String::isNotBlank).toSet()
        if (targets.isEmpty()) return
        val removedCurrent = connectedServerUrl in targets || composerServer in targets
        if (removedCurrent) resetConversationState()
        remoteProjects = ConnectionDeletion(this).delete(targets)
        targets.forEach { server ->
            // Remove ownership first so already queued listener events become no-ops.
            connectionClients.remove(server)?.close()
            connectedServerUrls.remove(server)
            connectingServerUrls.remove(server)
            reconnectAttempts.remove(server)
            connectionFailureReasons.remove(server)
            workspaceMigrationAttempts.remove(server)
            threadsByServer.remove(server)
            threadPages.remove(server)
            workspacesByServer.remove(server)
            capabilityPresentationCache.invalidateServer(server)
        }
        if (pendingProject?.serverUrl in targets) {
            pendingProject = null
            resetPendingProjectButton()
        }
        val active = remoteProjects.firstOrNull { it.id == activeProjectId } ?: remoteProjects.firstOrNull()
        activeProjectId = active?.id
        projectStore.setActiveProject(activeProjectId)
        if (removedCurrent) {
            connectedServerUrl = active?.serverUrl
            selectedWorkspace = active?.workspace
            listedThreads = threadsByServer[active?.serverUrl].orEmpty()
            listedWorkspaces = workspacesByServer[active?.serverUrl].orEmpty()
            clearInteractions()
        }
        if (sidebarConnectionUrl in targets) sidebarConnectionUrl = connectedServerUrl
        if (sidebarProjectScope?.first in targets) sidebarProjectScope = null
        pendingSidebarState = null

        if (asRoot || removedCurrent || remoteProjects.isEmpty()) showConnectionScreen() else showConnectionManager()
    }


    private fun connectToServer(serverUrl: String, token: String, select: Boolean = true) {
        val normalized = serverUrl.trim().trimEnd('/')
        if (select) {
            connectedServerUrl = normalized
            sidebarConnectionUrl = normalized
        }
        if (normalized in connectedServerUrls) {
            if (select) onConnected(normalized)
            return
        }
        val remoteClient = connectionClients.getOrPut(normalized) { createConnectionClient(normalized) }
        connectingServerUrls += normalized
        diagnosticLogs.record("connection.connecting", normalized)
        remoteClient.connect(normalized, token)
    }

    private fun createConnectionClient(serverUrl: String): RemoteClient {
        lateinit var source: RemoteClient
        // Saved opaque IDs must keep their negotiated format even when newly
        // paired connections default to direct paths. onConnected migrates any
        // paths discovered by an intervening legacy client before loading tasks.
        val needsOpaqueRouting = RemoteProjectStore.requiresOpaqueRouting(projectStore.list(), serverUrl)
        source = RemoteClient(object : RemoteClient.Listener {
            override fun onConnected() = runOnUiThread {
                if (connectionClients[serverUrl] === source) this@MainActivity.onConnected(serverUrl)
            }
            override fun onDisconnected(reason: String) = runOnUiThread {
                if (connectionClients[serverUrl] === source) this@MainActivity.onDisconnected(serverUrl, reason)
            }
            override fun onMessage(message: JSONObject) = runOnUiThread {
                if (connectionClients[serverUrl] === source) this@MainActivity.onMessage(serverUrl, message)
            }
        }, enableOpaqueWorkspaceRouting = needsOpaqueRouting)
        return source
    }


    private fun requestThreads(openDefault: Boolean = false, serverUrl: String? = connectedServerUrl, older: Boolean = false) {
        val server = serverUrl ?: return
        val navigationTicket = navigationRequests.capture(connectedServerUrl, currentThreadId)
        val updateLoadingState = older || threadPages.failed(server)
        val pageTicket = threadPages.begin(server, older) ?: return
        if (updateLoadingState) refreshOpenSidebar()
        rpcOn(
            server,
            "thread/list",
            JSONObject().put("limit", 50).put("sortKey", "updated_at").put("sortDirection", "desc")
                .apply { pageTicket.cursor?.let { put("cursor", it) } },
            onError = {
                if (threadPages.accepts(pageTicket)) {
                    threadPages.fail(pageTicket)
                    if (server == sidebarConnectionUrl) refreshOpenSidebar()
                }
            },
        ) { result ->
            if (!threadPages.accepts(pageTicket)) return@rpcOn
            val threads = threadPages.merge(pageTicket, threadsByServer[server].orEmpty(), ThreadProjection.threads(result))
            val nextCursor = result.opt("nextCursor") as? String
            val legacy = getSharedPreferences("remote_settings", MODE_PRIVATE)
            val configured = legacy.getString("workspace", "").orEmpty()
                .takeIf { legacy.getString("server_url", null)?.trimEnd('/') == server }.orEmpty()
            if (remoteProjects.isEmpty()) {
                val legacyWorkspace = configured.ifBlank { threads.firstOrNull()?.cwd.orEmpty() }
                projectStore.migrateLegacy(server, legacyWorkspace, threads.firstOrNull { it.cwd == legacyWorkspace }?.cwdName.orEmpty())
                remoteProjects = projectStore.list()
                activeProjectId = projectStore.activeProjectId() ?: remoteProjects.firstOrNull()?.id
            }
            val candidates = buildList {
                remoteProjects.filter { it.serverUrl == server }.map(RemoteProject::workspace).forEach(::add)
                if (server == connectedServerUrl && configured.isNotBlank()) add(configured)
                threads.map(RemoteThread::cwd).filter(String::isNotBlank).forEach(::add)
            }.distinct()
            validateWorkspaces(candidates, serverUrl = server, onError = {
                if (!threadPages.accepts(pageTicket)) return@validateWorkspaces
                threadPages.finish(pageTicket, nextCursor)
                threadsByServer[server] = threads
                workspacesByServer[server] = candidates
                if (server == sidebarConnectionUrl) {
                    listedThreads = threads
                    listedWorkspaces = candidates
                    refreshOpenSidebar()
                }
                if (openDefault && server == connectedServerUrl && navigationRequests.accepts(navigationTicket, connectedServerUrl, currentThreadId)) openInitialConversation(threads, candidates)
            }) { available ->
                if (!threadPages.accepts(pageTicket)) return@validateWorkspaces
                threadPages.finish(pageTicket, nextCursor)
                threadsByServer[server] = threads
                syncDiscoveredProjects(server, available)
                workspacesByServer[server] = available
                if (server == sidebarConnectionUrl) {
                    listedThreads = threads
                    listedWorkspaces = available
                    if (server == connectedServerUrl && selectedWorkspace !in available) selectedWorkspace = null
                    if (!openDefault || server != connectedServerUrl) refreshOpenSidebar()
                }
                if (openDefault && server == connectedServerUrl && navigationRequests.accepts(navigationTicket, connectedServerUrl, currentThreadId)) openInitialConversation(threads, available)
            }
        }
    }

    private fun syncDiscoveredProjects(server: String, workspaces: List<String>) {
        val connectionName = remoteProjects.firstOrNull { it.serverUrl == server }?.connectionName
            ?: hostLabel(server)
        val names = threadsByServer[server].orEmpty().associate { it.cwd to it.cwdName }
        remoteProjects = projectStore.mergeDiscovered(server, connectionName, workspaces, names)
        if (activeProjectId == null || remoteProjects.none { it.id == activeProjectId }) {
            activeProjectId = remoteProjects.firstOrNull { it.serverUrl == server }?.id
            projectStore.setActiveProject(activeProjectId)
        }
    }

    private fun openInitialConversation(threads: List<RemoteThread>, workspaces: List<String>) {
        val preferred = remoteProjects.firstOrNull { it.id == activeProjectId && it.serverUrl == connectedServerUrl }?.workspace
        val firstWorkspace = preferred?.takeIf { it in workspaces } ?: workspaces.firstOrNull()
        if (firstWorkspace != null) {
            selectedWorkspace = firstWorkspace
            openDraft(firstWorkspace)
        } else {
            threads.firstOrNull()?.let { openThread(it.id) } ?: showNoWorkspaceConversation()
        }
    }


    private fun openDraft(cwd: String) {
        val ticket = navigationRequests.capture(connectedServerUrl, currentThreadId)
        validateWorkspaces(listOf(cwd), onError = {
            if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) return@validateWorkspaces
            toast("Could not check this working directory")
            requestThreads()
        }) { available ->
            if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) return@validateWorkspaces
            if (cwd !in available) {
                toast("This working directory is no longer available")
                requestThreads()
                return@validateWorkspaces
            }
            resetConversationState()
            draftWorkspace = cwd
            currentThread = JSONObject().put("cwd", cwd).put("turns", JSONArray())
            showConversation(currentThread!!)
            loadComposerCapabilities(cwd)
        }
    }

    private fun activateSidebarConnection(workspace: String): Boolean {
        val server = sidebarConnectionUrl ?: connectedServerUrl ?: return false
        if (server !in connectedServerUrls) {
            val token = tokenStore.load(server)
            if (token == null) {
                toast("Pair ${hostLabel(server)} again to open this project")
                return false
            }
            // Browsing/reconnecting another host must not retarget the current conversation.
            if (server !in connectingServerUrls) connectToServer(server, token, select = false)
            refreshOpenSidebar()
            toast("Reconnecting to ${hostLabel(server)}…")
            return false
        }
        connectedServerUrl = server
        val project = remoteProjects.firstOrNull { it.serverUrl == server && it.workspace == workspace }
        if (project != null) {
            activeProjectId = project.id
            projectStore.setActiveProject(project.id)
        }
        return true
    }

    private fun showNoWorkspaceConversation() {
        resetConversationState()
        currentThread = JSONObject().put("turns", JSONArray())
        showConversation(currentThread!!)
        toast("Configure an available working directory to start a new chat")
    }

    private fun resetConversationState() {
        pendingDraftProject = null
        navigationRequests.invalidate()
        catalogController.closeCatalog()
        artifactsController.closeArtifacts()
        artifactsController.closeImageViewer()
        artifactRecords.clear()
        threadLoadError = null
        conversationController.setLoading(false)
        refreshInFlight = false
        refreshAgain = false
        clearLiveOnNextRefresh = false
        persistComposer()
        composerScope = null
        composerServer = null
        main.removeCallbacks(liveTimelineRenderRunnable)
        liveTimelineRenderScheduled = false
        currentThreadId = null
        currentThread = null
        draftWorkspace = null
        liveAssistantText = ""
        composerDraft = ""
        composerAttachments.clear()
        models = emptyList()
        permissionProfiles = emptyList()
        selectedModel = null
        selectedModelDisplay = null
        selectedEffort = null
        selectedServiceTier = null
        selectedPermissionId = null
        renderedTimelineSignature = null
        conversationController.resetDisclosures()
        expandedTimelineCommentaryItems.clear()
    }

    private fun validateWorkspaces(
        paths: List<String>,
        serverUrl: String? = connectedServerUrl,
        onError: (String) -> Unit,
        callback: (List<String>) -> Unit,
    ) {
        val params = JSONObject().put("paths", JSONArray(paths))
        val server = serverUrl ?: return onError("Not connected")
        rpcOn(server, "host/workspace/validate", params, onError) { result ->
            val available = jsonObjects(result.optJSONArray("workspaces"))
                .filter { it.optBoolean("available") }
                .map { it.optString("path") }
                .filter(String::isNotBlank)
            callback(available)
        }
    }

    private fun openThread(threadId: String) {
        restoreNavigation = false
        resetConversationState()
        currentThreadId = threadId
        conversationController.beginThreadLoad()
        val known = threadsByServer[connectedServerUrl].orEmpty().firstOrNull { it.id == threadId }
        showConversation(JSONObject().put("id", threadId).put("name", "Loading chat…").put("turns", JSONArray()).apply {
            known?.let { put("name", it.title); put("cwd", it.cwd); put("cwdName", it.cwdName) }
        })
        val ticket = navigationRequests.capture(connectedServerUrl, threadId)
        main.postDelayed({
            if (navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId) && conversationController.isThreadLoadPending) {
                navigationRequests.invalidate()
                refreshInFlight = false
                refreshAgain = false
                threadLoadError = "Loading took too long. Check your connection and try again."
                conversationController.failThreadLoad(threadLoadError!!)
            }
        }, 30_000L)
        val lifecycleRevision = turnLifecycleRevision
        rpc("thread/resume", JSONObject().put("threadId", threadId), { error ->
            if (navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) {
                threadLoadError = if (error.contains("no rollout found", ignoreCase = true))
                    "This task is no longer available on this host. Choose another task, or retry after reconnecting."
                else "Couldn't load this conversation. Check your connection and try again."
                conversationController.failThreadLoad(threadLoadError.orEmpty())
            }
        }) { result ->
            if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) return@rpc
            val thread = result.optJSONObject("thread")
            if (thread == null || thread.optString("id") != threadId) {
                conversationController.failThreadLoad("Couldn't load this conversation. Please try again.")
                return@rpc
            }
            selectedModel = result.optString("model").ifBlank { null }
            selectedEffort = result.optString("reasoningEffort").ifBlank { null }
            selectedServiceTier = result.optString("serviceTier").ifBlank { null }
            val activePermissionId = result.optJSONObject("activePermissionProfile")?.optString("id")?.ifBlank { null }
            selectedPermissionId = when {
                activePermissionId == PermissionProfiles.WORKSPACE && result.optString("approvalsReviewer") == "auto_review" -> PermissionProfiles.AUTO_REVIEW
                activePermissionId == null -> PermissionProfiles.CUSTOM_CONFIG
                else -> activePermissionId
            }
            if (lifecycleRevision == turnLifecycleRevision) {
                liveTimelineStore.reconcileThreadSnapshot(threadId, thread, lifecycleRevision, turnLifecycleRevision)
                currentThread = thread
                showConversation(thread)
            } else {
                // Keep navigation/composer setup from the resume result, but
                // omit lifecycle fields that predate a newer event.
                val latestRunning = turnRunning
                val shell = JSONObject(thread.toString()).put("turns", JSONArray())
                shell.remove("status")
                currentThread = shell
                showConversation(shell)
                turnRunning = latestRunning
                renderCurrentTimelineFromEvents(shell)
                updateComposerPrimaryButton()
            }
            loadComposerCapabilities(thread.optString("cwd"))
            // thread/resume can omit persisted custom tool calls. Always follow
            // it with the enriched includeTurns read so a cold-open timeline is
            // deterministic instead of depending on a later notification.
            refreshCurrentThread()
        }
    }

    private fun refreshCurrentThread() {
        val threadId = currentThreadId ?: return
        if (refreshInFlight) {
            refreshAgain = true
            return
        }
        refreshInFlight = true
        val ticket = navigationRequests.capture(connectedServerUrl, threadId)
        val lifecycleRevision = turnLifecycleRevision
        val clearLive = clearLiveOnNextRefresh
        clearLiveOnNextRefresh = false
        rpc("thread/read", JSONObject().put("threadId", threadId).put("includeTurns", true), { error ->
            if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) return@rpc
            refreshInFlight = false
            if (conversationController.isThreadLoadPending) {
                conversationController.failThreadLoad("Couldn't load the conversation history. Please try again.")
            } else toast(error)
            scheduleFollowUpRefreshIfNeeded()
        }) { result ->
            if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) return@rpc
            refreshInFlight = false
            val thread = result.optJSONObject("thread")
            if (thread != null && thread.optString("id") == threadId) {
                if (lifecycleRevision == turnLifecycleRevision) {
                    if (conversationController.isThreadLoadPending) conversationController.finishThreadLoad()
                    applyThreadSnapshot(thread, clearLive, lifecycleRevision)
                } else {
                    // The response was taken before a newer lifecycle event. Its
                    // thread status and turns must not replace the current view.
                    scheduleRefresh(clearLive = clearLive)
                }
            } else if (conversationController.isThreadLoadPending) {
                conversationController.failThreadLoad("Couldn't load the conversation history. Please try again.")
            }
            scheduleFollowUpRefreshIfNeeded()
        }
    }

    private fun scheduleFollowUpRefreshIfNeeded() {
        if (!refreshAgain) return
        refreshAgain = false
        scheduleRefresh()
    }


    private fun startTurn(
        threadId: String,
        input: JSONArray,
        submission: ComposerSubmission,
    ) {
        val submittedScope = submission.scope
        val submittedDraft = submission.draft
        val submittedText = submittedDraft.optString("text")
        val submittedIds = jsonObjects(submittedDraft.optJSONArray("attachments")).map { it.optString("localId") }
        val params = JSONObject().put("threadId", threadId).put("input", input)
        submission.model?.let { params.put("model", it) }
        submission.effort?.let { params.put("effort", it) }
        submission.tier?.let { params.put("serviceTier", it) }
        PermissionProfiles.applySelection(params, submission.permission)
        TurnCollaborationMode.apply(params, submission.plan, submission.model, submission.effort)
        params.put("_remoteWriteKey", durableWriteKey(submittedScope, "turn", params.toString()))
        fun acceptSubmission(result: JSONObject) {
            val acknowledgedTurn = result.optJSONObject("turn")
            val acknowledgedId = acknowledgedTurn?.optString("id")?.ifBlank { null } ?: result.optString("turnId").ifBlank { null }
            outgoingMessages.confirm(submittedScope, submission.messageId, acknowledgedId)
            if (acknowledgedTurn?.optString("status") in setOf("completed", "failed", "cancelled", "canceled", "interrupted")) {
                outgoingMessages.finish(submittedScope, acknowledgedId.orEmpty())
            }
            val editorHasNewDraft = composerScope == submittedScope && (composerDraft.isNotEmpty() || composerAttachments.isNotEmpty())
            if (editorHasNewDraft) persistComposer()
            submittingScopes.remove(submittedScope)
            pendingSubmissionDrafts.remove(submittedScope)
            val latestDraft = draftStore.read(submittedScope)
            val unchanged = !editorHasNewDraft && latestDraft.optString("text") == submittedText &&
                jsonObjects(latestDraft.optJSONArray("attachments")).map { it.optString("localId") } == submittedIds
            if (unchanged) draftStore.write(submittedScope, JSONObject().put("text", "").put("attachments", JSONArray()))
            if (composerScope == submittedScope) {
                renderCurrentTimelineFromEvents()
                updateComposerPrimaryButton()
            }
            requestThreads()
            if (composerScope == submittedScope) scheduleRefresh()
        }
        rpcOn(submission.server, "turn/start", params, { error ->
            if (outgoingMessages.isConfirmed(submittedScope, submission.messageId)) {
                acceptSubmission(JSONObject())
                return@rpcOn
            }
            outgoingMessages.reject(submittedScope, submission.messageId)
            val rejectedBeforeDispatch = error.startsWith("Selected capability") ||
                error.startsWith("Attachment is not complete") || error.startsWith("Attachment expired") ||
                error.startsWith("Attachment has expired")
            val draftScope = submission.newDraftScope
            if (rejectedBeforeDispatch && draftScope != null && submission.newDraftThread != null &&
                draftStore.restoreRejectedNewChat(submittedScope, draftScope) && composerScope == submittedScope) {
                currentThreadId = null
                currentThread = submission.newDraftThread
                draftWorkspace = submission.newDraftThread.optString("cwd")
                // Avoid persisting the old editor back into the now-cleared task record.
                composerScope = draftScope
                turnRunning = false
                showConversation(submission.newDraftThread)
            }
            if (error.startsWith("thread not found:") || error.startsWith("Selected capability") ||
                error.startsWith("Attachment is not complete") || error.startsWith("Attachment expired")) {
                synchronized(draftStore) {
                    val saved = draftStore.read(submittedScope)
                    saved.remove("turnKey")
                    draftStore.write(submittedScope, saved)
                }
            }
            submittingScopes.remove(submittedScope)
            pendingSubmissionDrafts.remove(submittedScope)
            if (composerScope == submittedScope) turnRunning = false
            restoreUnconfirmedSubmission(submission)
            renderCurrentTimelineFromEvents()
            updateComposerPrimaryButton()
            showInfo("Message was not confirmed", error + "\n\nYour draft and selected attachments are saved.")
        }) { result -> acceptSubmission(result) }
    }

    private fun restoreUnconfirmedSubmission(submission: ComposerSubmission) {
        val scope = composerScope ?: return
        if (scope != submission.scope && scope != submission.newDraftScope) return
        if (composerDraft.isNotEmpty() || composerAttachments.isNotEmpty()) return
        composerDraft = submission.draft.optString("text")
        restoreAttachments(submission.draft)
        composerController.updateText(composerDraft)
        updateComposerPrimaryButton()
    }

    private fun durableWriteKey(scope: String, kind: String, payload: String): String = synchronized(draftStore) {
        val saved = draftStore.read(scope)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        if (saved.optString("${kind}Fingerprint") != fingerprint || saved.optString("${kind}Key").isBlank()) {
            saved.put("${kind}Fingerprint", fingerprint).put("${kind}Key", UUID.randomUUID().toString())
            draftStore.write(scope, saved)
        }
        saved.getString("${kind}Key")
    }

    private fun attachmentMetadata(name: String, size: Long): String {
        val extension = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val type = when (extension) {
            "" -> getString(R.string.file_type_unknown)
            "ZIP", "GZ", "TAR" -> getString(R.string.file_type_archive, extension)
            else -> extension
        }
        return if (size > 0) getString(R.string.attachment_metadata, type, android.text.format.Formatter.formatShortFileSize(this, size)) else type
    }

    private fun attachmentLabel(item: JSONObject): String {
        val size = item.optLong("size")
        val metadata = if (size > 0) " · ${android.text.format.Formatter.formatShortFileSize(this, size)} · ${item.optString("mimeType")}" else ""
        return "${item.optString("name")}$metadata\n${item.optString("description")}"
    }


    private fun persistComposer() {
        val scope = composerScope ?: return
        if (!::draftStore.isInitialized) return
        // The editor clears optimistically, but retain the durable submitted draft
        // until acknowledgement. A newly typed draft still persists normally.
        if (scope in submittingScopes && composerDraft.isEmpty() && composerAttachments.isEmpty()) return
        synchronized(draftStore) {
        val saved = draftStore.read(scope)
        val previous = jsonObjects(saved.optJSONArray("attachments")).associateBy { it.optString("localId") }
        val items = JSONArray()
        composerAttachments.forEach { attachment ->
            // Worker checkpoints own transfer fields; editor changes cannot roll back its offset or ID.
            items.put(previous[attachment.localId] ?: attachment.toJson())
        }
        draftStore.write(scope, saved.put("text", composerDraft).put("attachments", items)
            .put("server", composerServer).put("device", composerServer?.let { tokenStore.loadCredential(it)?.deviceId }.orEmpty())
            .put("chunked", retainedAttachmentSupport(
                composerServer?.let { connectionClients[it]?.negotiatedProtocolVersion } ?: 0,
                composerServer?.let { connectionClients[it]?.supportsChunkedAttachments() } == true,
                saved.optBoolean("chunked"))))
        }
    }

    private fun restoreAttachments(saved: JSONObject) {
        composerAttachments.clear()
        jsonObjects(saved.optJSONArray("attachments")).forEach { composerAttachments += ComposerAttachment.fromJson(it) }
    }

    private fun resumeUploads() {
        AttachmentQueue.schedule(this)
        AttachmentQueue.run(this)
    }


    private fun queueAttachment(uri: android.net.Uri, targetScope: String? = composerScope) {
        val scope = targetScope ?: return toast("Open a task before adding attachments")
        if (scope == composerScope) persistComposer()
        val saved = draftStore.read(scope)
        if (!saved.optBoolean("chunked")) {
            showAttachmentUnavailable()
            return
        }
        runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty().ifBlank { "attachment" }
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
            ?.substringBefore(';')?.lowercase(Locale.ROOT) ?: inferredAttachmentMime(name)
        val pending = ComposerAttachment("remoteAttachment", name, "", "Preparing").toJson()
            .put("sourceUri", uri.toString()).put("mimeType", mime).put("state", "preparing")
        synchronized(draftStore) {
            val current = draftStore.read(scope)
            val items = current.optJSONArray("attachments") ?: JSONArray()
            items.put(pending)
            draftStore.write(scope, current.put("attachments", items))
        }
        if (composerScope == scope) {
            restoreAttachments(draftStore.read(scope))
            currentThread?.let(::showConversation)
        }
        resumeUploads()
    }


    private fun inferredAttachmentMime(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun threadIsRunning(thread: JSONObject): Boolean {
        // A turn-start event can arrive before the refreshed thread snapshot.
        if (liveTimelineStore.activeTurnId(thread.optString("id")) != null) return true
        val status = when (val value = thread.opt("status")) {
            is JSONObject -> value.optString("type")
            else -> value?.toString().orEmpty()
        }
        val runningStatuses = setOf("active", "running", "inprogress", "in_progress", "started", "pending")
        if (status.isNotBlank()) return status.lowercase(Locale.ROOT) in runningStatuses
        val turns = thread.optJSONArray("turns")
        if (turns != null) for (index in turns.length() - 1 downTo 0) {
            val turnStatus = turns.optJSONObject(index)?.optString("status").orEmpty()
            if (turnStatus.isNotBlank()) return turnStatus.lowercase(Locale.ROOT) in runningStatuses
        }
        return false
    }

    private fun snapshotRunningTurnId(threadId: String): String? = currentThread
        ?.takeIf { it.optString("id") == threadId }
        ?.takeUnless { thread ->
            val status = when (val value = thread.opt("status")) {
                is JSONObject -> value.optString("type")
                else -> value?.toString().orEmpty()
            }
            status.lowercase(Locale.ROOT) in setOf("idle", "completed", "failed", "cancelled", "canceled", "interrupted")
        }
        ?.optJSONArray("turns")
        ?.let { turns -> (turns.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
            turns.optJSONObject(index)?.takeIf { it.optString("status").lowercase(Locale.ROOT) in
                setOf("active", "running", "inprogress", "in_progress", "started", "pending") }
                ?.optString("id")?.ifBlank { null }
                ?.takeUnless { liveTimelineStore.isTurnTerminal(threadId, it) }
        } }


    private fun performComposerSend(thread: JSONObject) {
        if (turnRunning) {
            interruptActiveTurn()
            return
        }
        if (currentThreadId != null && currentThread == null) return toast("Loading task · your draft is saved")
        val sourceScope = composerScope ?: return
        val sourceServer = connectedServerUrl ?: return
        if (sourceServer !in connectedServerUrls) return toast("Offline · your draft is saved. Wait for reconnection to send.")
        if (sourceScope in submittingScopes) return toast("Waiting for the host to confirm this message")
        val value = composerDraft.trim()
        if (value.isBlank() && composerAttachments.isEmpty()) return
        if (composerAttachments.any { it.path.isBlank() }) return toast("Wait for attachments to finish uploading")
        val activeClient = connectionClients[sourceServer]
        if (composerAttachments.any { it.type == "remoteAttachment" } && activeClient?.supportsChunkedAttachments() != true)
            return toast("This host cannot use durable attachments. Update the host or remove the files; your draft is saved.")
        if (composerAttachments.any { it.type == "remoteCapability" } && (activeClient?.negotiatedProtocolVersion != 2 || !activeClient.supportsRpcMethod("host/capabilities/list")))
            return toast("This host cannot resolve selected capabilities. Refresh the catalog or update the host.")
        // Once a task is submitted the execution timeline, not the editor,
        // becomes the primary surface. Release IME space immediately so the
        // live card and its ticker are visible from the first event.
        dismissKeyboardForPopup()
        val input = JSONArray()
        composerAttachments.forEach { attachment ->
            val item = JSONObject().put("type", attachment.type)
            if (attachment.type == "remoteAttachment") {
                item.put("attachmentId", attachment.path).put("name", attachment.name)
            } else if (attachment.type == "remoteCapability") {
                item.put("capabilityId", attachment.path)
            } else {
                item.put("path", attachment.path)
                if (attachment.type != "localImage") item.put("name", attachment.name)
            }
            input.put(item)
        }
        if (value.isNotBlank()) input.put(JSONObject().put("type", "text").put("text", value))
        turnRunning = true
        followTimelineLatest = true
        updateComposerPrimaryButton()
        // Freeze the profile at submit time. Starting a new thread emits
        // asynchronous thread events that can refresh composer state before
        // turn/start is sent; reading the mutable field later could silently
        // downgrade a confirmed Full Access turn back to workspace access.
        val submittedPermissionId = PermissionProfiles.submissionId(selectedPermissionId, permissionProfiles)
        val submission = ComposerSubmission(sourceServer, sourceScope, selectedModel, selectedEffort, selectedServiceTier, submittedPermissionId, planMode,
            messageId = "outgoing-${UUID.randomUUID()}", draft = JSONObject(draftStore.read(sourceScope).toString()))
        if (currentThreadId == null && (draftWorkspace ?: thread.optString("cwd")).isBlank()) {
            turnRunning = false
            updateComposerPrimaryButton()
            return toast("Choose an available working directory first")
        }
        submittingScopes += sourceScope
        pendingSubmissionDrafts[sourceScope] = submission.draft
        val optimistic = TimelineItem(submission.messageId, "You", value, TimelineItem.Kind.USER,
            attachments = composerAttachments.filter { it.type == "remoteAttachment" }.map { attachment ->
                MessageAttachment(attachment.path, attachment.name,
                    if (jsonObjects(submission.draft.optJSONArray("attachments")).firstOrNull { it.optString("localId") == attachment.localId }
                            ?.optString("mimeType")?.startsWith("image/") == true) "image" else "file")
            })
        outgoingMessages.begin(sourceScope, optimistic, projectedTimeline(thread))
        composerDraft = ""
        composerAttachments.clear()
        composerController.updateText("")
        updateComposerPrimaryButton()
        renderCurrentTimelineFromEvents()
        val existingThreadId = currentThreadId
        if (existingThreadId != null) {
            startTurn(existingThreadId, input, submission)
        } else {
            val cwd = draftWorkspace ?: thread.optString("cwd")
            if (cwd.isBlank()) {
                submittingScopes.remove(sourceScope)
                pendingSubmissionDrafts.remove(sourceScope)
                turnRunning = false
                updateComposerPrimaryButton()
                return toast("Choose an available working directory first")
            }
            val threadParams = JSONObject().put("cwd", cwd).apply {
                PermissionProfiles.applySelection(this, submittedPermissionId)
            }
            threadParams.put("_remoteWriteKey", durableWriteKey(sourceScope, "thread", threadParams.toString()))
            rpcOn(sourceServer, "thread/start", threadParams, { error ->
                outgoingMessages.reject(sourceScope, submission.messageId)
                submittingScopes.remove(sourceScope)
                pendingSubmissionDrafts.remove(sourceScope)
                if (composerScope == sourceScope) turnRunning = false
                restoreUnconfirmedSubmission(submission)
                renderCurrentTimelineFromEvents()
                updateComposerPrimaryButton()
                toast("Could not start this chat: $error")
            }) { result ->
                val createdThread = result.optJSONObject("thread")
                val createdId = createdThread?.optString("id").orEmpty()
                if (createdId.isBlank()) {
                    outgoingMessages.reject(sourceScope, submission.messageId)
                    submittingScopes.remove(sourceScope)
                    pendingSubmissionDrafts.remove(sourceScope)
                    if (composerScope == sourceScope) turnRunning = false
                    restoreUnconfirmedSubmission(submission)
                    renderCurrentTimelineFromEvents()
                    updateComposerPrimaryButton()
                    toast("Server did not return a task id")
                } else {
                    if (composerScope == sourceScope) persistComposer()
                    val newScope = sourceScope.substringBeforeLast('\u0000') + "\u0000$createdId"
                    draftStore.write(newScope, draftStore.read(sourceScope))
                    draftStore.write(sourceScope, JSONObject().put("text", "").put("attachments", JSONArray()))
                    submittingScopes.remove(sourceScope)
                    pendingSubmissionDrafts.remove(sourceScope)
                    submittingScopes += newScope
                    pendingSubmissionDrafts[newScope] = submission.draft
                    outgoingMessages.move(sourceScope, newScope)
                    if (composerScope == sourceScope) {
                        composerScope = newScope
                        currentThreadId = createdId
                        draftWorkspace = null
                        currentThread = createdThread
                        // Persist the created task route before turn/start can fail.
                        // Empty tasks may not appear in the remote recent list yet.
                        createdThread?.let(::showConversation)
                    }
                    startTurn(createdId, input, submission.copy(scope = newScope,
                        newDraftScope = sourceScope, newDraftThread = JSONObject(thread.toString())))
                }
            }
        }
    }

    private fun interruptActiveTurn() {
        val threadId = currentThreadId ?: return
        val server = connectedServerUrl ?: return
        val device = tokenStore.loadCredential(server)?.deviceId
        val ticket = navigationRequests.capture(server, threadId)
        val turnId = liveTimelineStore.activeTurnId(threadId) ?: snapshotRunningTurnId(threadId)
        if (turnId == null) {
            // The button can be based on a stale thread status, or turn/start
            // may not have supplied an id yet. Read the server before retrying.
            refreshCurrentThread()
            toast("Checking the task state")
            return
        }
        val lifecycleRevision = turnLifecycleRevision
        liveTimelineStore.requestCancellation(threadId, turnId)
        renderCurrentTimelineFromEvents()
        rpc(
            "turn/interrupt",
            JSONObject().put("threadId", threadId).put("turnId", turnId),
            { error ->
                if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId) ||
                    tokenStore.loadCredential(server)?.deviceId != device) return@rpc
                if (error.contains("no active turn to interrupt", ignoreCase = true)) {
                    liveTimelineStore.settleActiveTurn(threadId, turnId)
                    if (currentThreadId == threadId && lifecycleRevision == turnLifecycleRevision) {
                        turnRunning = false
                        liveAssistantText = ""
                    }
                    scheduleRefresh(clearLive = true)
                } else liveTimelineStore.clearCancellationRequest(threadId, turnId,
                    restoreRunning = liveTimelineStore.activeTurnId(threadId) == null)
                renderCurrentTimelineFromEvents()
                updateComposerPrimaryButton()
                if (!error.contains("no active turn to interrupt", ignoreCase = true)) toast(error)
            },
        ) {
            if (navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId) &&
                tokenStore.loadCredential(server)?.deviceId == device) scheduleRefresh()
        }
    }


    private fun openDraftImage(
        attachment: ComposerAttachment,
        path: String,
        onLoadingStateChanged: (Boolean) -> Unit = {}
    ) {
        val scope = composerScope ?: return
        val lease = draftImageOpenGate.tryAcquire(scope, attachment.localId) ?: return
        val ticket = navigationRequests.capture(connectedServerUrl, currentThreadId)
        val server = connectedServerUrl
        val device = server?.let { tokenStore.loadCredential(it)?.deviceId }
        onLoadingStateChanged(true)
        try {
            attachmentPreviewExecutor.execute {
                val bitmap = runCatching {
                    File(path).takeIf { it.isFile && it.length() <= 20L * 1024 * 1024 }
                        ?.readBytes()?.let(::decodeRemoteBitmap)
                }.getOrNull()
                runOnUiThread {
                    try {
                        val current = draftStore.attachment(scope, attachment.localId)
                        val stillValid = !isDestroyed && !isFinishing && hasWindowFocus() &&
                            composerScope == scope &&
                            navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId) &&
                            server?.let { tokenStore.loadCredential(it)?.deviceId } == device &&
                            current != null && current.optString("state") == "ready" &&
                            composerAttachments.any { it.localId == attachment.localId }

                        if (stillValid) {
                            onLoadingStateChanged(false)
                            if (bitmap != null) showFullImage(bitmap, attachment.name) else attachmentActions(attachment)
                        } else {
                            bitmap?.recycle()
                            if (composerScope == scope && composerAttachments.any { it.localId == attachment.localId }) {
                                onLoadingStateChanged(false)
                            }
                        }
                    } finally {
                        draftImageOpenGate.release(lease)
                    }
                }
            }
        } catch (e: Throwable) {
            draftImageOpenGate.release(lease)
            onLoadingStateChanged(false)
        }
    }


    private fun decodeRemoteBitmap(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 6_000_000L) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun loadDiskCachedImage(source: String): Bitmap? = runCatching {
        val file = imageCacheFile(source)
        if (!file.isFile || file.length() > 12 * 1024 * 1024) null else decodeRemoteBitmap(file.readBytes())
    }.getOrNull()

    private fun cacheImageOnDisk(source: String, bytes: ByteArray) = runCatching {
        val directory = File(cacheDir, "remote-images").apply { mkdirs() }
        File(directory, imageCacheName(source)).writeBytes(bytes)
    }

    private fun imageCacheFile(source: String) = File(File(cacheDir, "remote-images"), imageCacheName(source))

    private fun imageCacheName(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }


    private fun handleCodexEvent(message: JSONObject) {
        val params = message.optJSONObject("params") ?: JSONObject()
        val eventThreadId = params.optString("threadId")
        if (eventThreadId.isNotBlank() && eventThreadId != currentThreadId) return
        val method = message.optString("method")
        val eventTurnId = params.optString("turnId").ifBlank { params.optJSONObject("turn")?.optString("id").orEmpty() }
        val terminalInSnapshot = if (method == "turn/started" || method == "item/agentMessage/delta")
            currentThread?.optJSONArray("turns")?.let { turns ->
                (0 until turns.length()).any { index -> turns.optJSONObject(index)?.let { turn ->
                    turn.optString("id") == eventTurnId && turn.optString("status").lowercase(Locale.ROOT) in
                        setOf("completed", "failed", "cancelled", "canceled", "interrupted")
                } == true }
            } == true else false
        if (eventTurnId.isNotBlank() && terminalInSnapshot) return
        if (method == "thread/status/changed" || method == "turn/started" || method in TURN_FINISHED_EVENTS) turnLifecycleRevision++
        val liveTimelineChanged = liveTimelineStore.record(method, params)
        if (method == "thread/tokenUsage/updated") {
            val usage = params.optJSONObject("tokenUsage")
            val total = usage?.optJSONObject("last")?.optLong("totalTokens", -1L) ?: -1L
            val context = usage?.optLong("modelContextWindow", -1L) ?: -1L
            if (eventThreadId.isNotBlank() && total >= 0L) {
                usageByThread[eventThreadId] = UsageSnapshot(total, context.takeIf { it > 0L })
                updateUsageButton()
            }
        } else if (method == "item/agentMessage/delta") {
            if (eventTurnId.isNotBlank() &&
                (liveTimelineStore.isTurnTerminal(eventThreadId, eventTurnId) ||
                    liveTimelineStore.isItemCompleted(eventThreadId, eventTurnId, params.optString("itemId")))) return
            turnRunning = true
            updateComposerPrimaryButton()
            if (liveTimelineChanged) {
                liveAssistantText = ""
                scheduleLiveTimelineRender()
            } else {
                appendLiveAssistantDelta(params.optString("delta"))
            }
        } else if (method == "thread/status/changed") {
            turnRunning = params.optJSONObject("status")?.optString("type") == "active"
            if (!turnRunning && eventThreadId.isNotBlank()) liveTimelineStore.settleActiveTurn(eventThreadId)
            updateComposerPrimaryButton()
            requestThreads()
            if (!turnRunning) scheduleRefresh(clearLive = true)
        } else if (currentThreadId != null && method in STRUCTURAL_REFRESH_EVENTS) {
            val itemType = params.optJSONObject("item")?.optString("type")
            val completesAssistant = method == "item/completed" && itemType == "agentMessage"
            if (method == "turn/started") {
                val startedTurn = params.optString("turnId").ifBlank { params.optJSONObject("turn")?.optString("id").orEmpty() }
                if (!liveTimelineStore.isTurnTerminal(eventThreadId, startedTurn)) {
                    turnRunning = true
                    updateComposerPrimaryButton()
                }
            }
            if (method in TURN_FINISHED_EVENTS) {
                val finishedTurn = params.optString("turnId").ifBlank { params.optJSONObject("turn")?.optString("id").orEmpty() }
                turnRunning = currentThreadId?.let(liveTimelineStore::activeTurnId) != null ||
                    currentThreadId?.let(::snapshotRunningTurnId)?.let { it != finishedTurn } == true ||
                    composerScope in submittingScopes
                composerScope?.let { outgoingMessages.finish(it, finishedTurn) }
                renderCurrentTimelineFromEvents()
                updateComposerPrimaryButton()
                requestThreads()
            }
            if (liveTimelineChanged) {
                liveAssistantText = ""
                renderCurrentTimelineFromEvents()
            }
            scheduleRefresh(clearLive = completesAssistant || method in TURN_FINISHED_EVENTS)
        } else if (liveTimelineChanged) {
            scheduleLiveTimelineRender()
        }
    }

    private fun projectedTimeline(thread: JSONObject): List<TimelineItem> {
        val threadId = currentThreadId ?: thread.optString("id")
        return ThreadProjection.timeline(thread, liveTimelineStore.snapshots(threadId))
    }

    private val liveTimelineRenderRunnable = Runnable {
        liveTimelineRenderScheduled = false
        renderCurrentTimelineFromEvents()
    }

    private fun scheduleLiveTimelineRender() {
        if (liveTimelineRenderScheduled) return
        liveTimelineRenderScheduled = true
        main.postDelayed(liveTimelineRenderRunnable, LIVE_TIMELINE_RENDER_MS)
    }


    private fun scheduleRefresh(clearLive: Boolean = false) {
        if (clearLive) clearLiveOnNextRefresh = true
        if (refreshScheduled) main.removeCallbacks(refreshRunnable)
        refreshScheduled = true
        main.postDelayed(refreshRunnable, 450)
    }


    private fun rpc(
        method: String,
        params: JSONObject,
        onError: (String) -> Unit = { toast(it) },
        callback: (JSONObject) -> Unit,
    ) {
        val server = connectedServerUrl ?: run {
            diagnosticLogs.record("rpc.local_error", detail = "method=$method Not connected")
            return onError("Not connected")
        }
        rpcOn(server, method, params, onError, callback)
    }

    private fun rpcOn(
        serverUrl: String,
        method: String,
        params: JSONObject,
        onError: (String) -> Unit = { toast(it) },
        callback: (JSONObject) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        rpcDiagnostics.begin(id, serverUrl, method, params, tokenStore.load(serverUrl))
        callbacks[id] = callback
        callbackErrors[id] = onError
        main.postDelayed({
            if (callbacks.remove(id) != null) {
                rpcDiagnostics.finish(id, serverUrl, "timeout", "No host confirmation within 65 seconds; outcome unknown")
                callbackErrors.remove(id)?.invoke(
                    "The host has not confirmed this request. Your draft is saved; check the connection and task before retrying.")
            }
        }, 65_000L)
        runCatching { connectionClients[serverUrl]?.rpc(id, method, params) ?: error("Not connected") }.onFailure {
            callbacks.remove(id)
            callbackErrors.remove(id)
            rpcDiagnostics.finish(id, serverUrl, "local_error", it.message ?: "Not connected")
            onError(it.message ?: "Not connected")
        }
    }

    private fun loadComposerCapabilities(cwd: String) {
        val ticket = navigationRequests.capture(connectedServerUrl, currentThreadId)
        rpc("model/list", JSONObject().put("limit", 100).put("includeHidden", false), { error ->
            if (navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) toast("Models unavailable: $error")
        }) { result ->
            if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) return@rpc
            models = jsonObjects(result.optJSONArray("data")).mapNotNull { value ->
                val model = value.optString("model")
                if (model.isBlank() || value.optBoolean("hidden")) return@mapNotNull null
                val efforts = jsonObjects(value.optJSONArray("supportedReasoningEfforts")).mapNotNull { effort ->
                    val id = effort.optString("reasoningEffort")
                    if (id.isBlank()) null else EffortOption(id, effort.optString("description"))
                }
                ModelOption(
                    model = model,
                    displayName = value.optString("displayName").ifBlank { model },
                    defaultEffort = value.optString("defaultReasoningEffort").ifBlank { efforts.firstOrNull()?.id },
                    efforts = efforts,
                    defaultServiceTier = jsonText(value, "defaultServiceTier").ifBlank { null },
                    serviceTiers = jsonObjects(value.optJSONArray("serviceTiers")).mapNotNull { tier ->
                        val id = tier.optString("id")
                        if (id.isBlank()) null else ServiceTierOption(id, jsonText(tier, "name").ifBlank { id }, jsonText(tier, "description"))
                    },
                    isDefault = value.optBoolean("isDefault"),
                )
            }
            val active = models.firstOrNull { it.model == selectedModel }
                ?: models.firstOrNull { it.isDefault }
                ?: models.firstOrNull()
            if (selectedModel == null) selectedModel = active?.model
            selectedModelDisplay = models.firstOrNull { it.model == selectedModel }?.displayName ?: selectedModel
            val supported = models.firstOrNull { it.model == selectedModel }?.efforts.orEmpty()
            if (selectedEffort == null || supported.none { it.id == selectedEffort }) {
                selectedEffort = models.firstOrNull { it.model == selectedModel }?.defaultEffort
            }
            val tiers = models.firstOrNull { it.model == selectedModel }?.serviceTiers.orEmpty()
            if (selectedServiceTier == null || (tiers.isNotEmpty() && tiers.none { it.id == selectedServiceTier })) {
                selectedServiceTier = models.firstOrNull { it.model == selectedModel }?.defaultServiceTier
            }
            updateComposerLabels()
        }
        rpc("permissionProfile/list", JSONObject().put("limit", 100), { error ->
            if (navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) toast("Permission profiles unavailable: $error")
        }) { result ->
            if (!navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId)) return@rpc
            permissionProfiles = jsonObjects(result.optJSONArray("data")).mapNotNull { value ->
                val id = value.optString("id")
                if (id.isBlank()) null else PermissionProfile(id, jsonText(value, "description"), value.optBoolean("allowed"))
            }
            if (selectedPermissionId == null)
                selectedPermissionId = PermissionProfiles.menu(permissionProfiles).firstOrNull { it.enabled }?.id
            updateComposerLabels()
        }
        if (cwd.isBlank()) return
    }


    private fun addComposerAttachment(attachment: ComposerAttachment) {
        if (composerAttachments.any { it.type == attachment.type && it.path == attachment.path }) {
            toast("${attachment.name} is already attached")
            return
        }
        composerAttachments += attachment
        persistComposer()
        currentThread?.let(::showConversation)
    }


    private fun modelEffortLabel() = listOf(shortModelLabel(selectedModelDisplay ?: selectedModel ?: "Model"), effortLabel(selectedEffort))
        .filter(String::isNotBlank).joinToString(" ")
    private fun shortModelLabel(value: String) = value.removePrefix("GPT-").replace('-', ' ')
    private fun effortLabel(value: String?): String = when (value) {
        null, "" -> ""
        "low" -> "Light"
        "xhigh" -> "Extra High"
        else -> value.split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
    private fun serviceTierLabel(value: String?): String {
        val model = models.firstOrNull { it.model == selectedModel }
        return model?.serviceTiers?.firstOrNull { it.id == value }?.name
            ?: value?.split('-', '_')?.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            ?: "Standard"
    }
    private fun permissionLabel() = PermissionProfiles.menu(permissionProfiles).firstOrNull { it.id == selectedPermissionId }?.label
        ?: selectedPermissionId?.let(::profileTitle)
        ?: "Access"
    private fun permissionIcon(id: String?) = when {
        isFullAccess(id) -> R.drawable.ic_codex_permission_full_access
        id?.contains("guardian", true) == true -> R.drawable.ic_codex_permission_guardian
        id?.contains("workspace", true) == true -> R.drawable.ic_codex_permission_hand
        id?.contains("read", true) == true -> R.drawable.ic_codex_permission_profile
        else -> R.drawable.ic_gpp_maybe_outline
    }
    private fun profileTitle(id: String) = id.substringAfterLast(':').split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    private fun isFullAccess(id: String?) = id?.contains("full", ignoreCase = true) == true || id == "danger-full-access"
    private fun formatTokens(value: Long): String = if (value >= 1000L) "${value / 1000L}k" else value.toString()
    private fun jsonObjects(array: JSONArray?): List<JSONObject> = buildList {
        if (array != null) for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
    }
    private fun jsonText(value: JSONObject, key: String) = value.optString(key).takeUnless { it == "null" }.orEmpty()


    private fun workspaceName(path: String): String {
        val label = currentThread?.takeIf { it.optString("cwd") == path }?.optString("cwdName")
            ?.takeIf(String::isNotBlank)
            ?: threadsByServer[connectedServerUrl].orEmpty().firstOrNull { it.cwd == path }?.cwdName.orEmpty()
        return workspaceDisplayName(path, label)
    }
    private fun hostLabel(serverUrl: String): String = runCatching { Uri.parse(serverUrl).host.orEmpty() }
        .getOrDefault("")
        .ifBlank { serverUrl.substringAfter("://", serverUrl).substringBefore('/').substringBefore(':').ifBlank { "Remote" } }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()


    // Feature controllers own presentation. Transport, durable drafts and source checks stay here.
    private val addConnectionController by lazy {
        app.codexremote.android.presentation.connections.AddConnectionController(::addConnection)
    }
    private fun scanConnection() {
        val ticket = addConnectionController.beginScan() ?: return
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .enableAutoZoom().build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                if (!isDestroyed && !isFinishing) addConnectionController.completeScan(ticket, barcode.rawValue ?: "")
            }
            .addOnCanceledListener { if (!isDestroyed) addConnectionController.completeScan(ticket, null) }
            .addOnFailureListener { if (!isDestroyed) addConnectionController.failScan(ticket) }
    }
    private fun addConnection(server: String, code: String, ticket: Long) {
        utilityClient.pair(server, code, android.os.Build.MODEL) { result -> runOnUiThread {
            if (isDestroyed || isFinishing || !addConnectionController.accepts(ticket)) return@runOnUiThread
            result.onSuccess { credential ->
                tokenStore.save(server, credential)
                projectStore.saveConnection(server, projectStore.connections().firstOrNull { it.serverUrl == server }?.name.orEmpty())
                connectionClients.remove(server)?.close()
                connectedServerUrls.remove(server); connectingServerUrls.remove(server)
                addConnectionController.finish(ticket)
                showConnectionScreen()
                connectToServer(server, credential.token, select = false)
            }.onFailure { addConnectionController.finish(ticket, "Pairing failed. Check the server and one-time code, then try again.") }
        } }
    }

    private val connectionsController: ConnectionsController by lazy { ConnectionsController(
        onConnectServer = { server -> tokenStore.load(server)?.let { connectToServer(server, it) }
            ?: connectionsController.startPairing(server) },
        onDeleteConfirmed = { deleteConnections(it, connectionsController.uiState.value.isRoot) },
        onPairSubmit = ::pairConnection,
        onAddProject = { showAddRemoteProject(false, it) },
        onSelectProject = ::activateRemoteProject,
        onSaveConnection = ::saveConnection,
        onRotateCredential = ::rotateCredential,
        onRevokeCredential = ::revokeCredential,
        onRemoveProject = ::removeProject
    ) }
    private val projectsController: ProjectsController by lazy { ProjectsController(onBrowse = ::browseProjects, onSubmit = ::submitProject) }
    private val sidebarController: SidebarController by lazy { SidebarController(
        onSelectProject = ::activateRemoteProject,
        onSelectThread = { thread -> if (activateSidebarConnection(thread.cwd)) openThread(thread.id) },
        onNewThread = ::newThreadFromSidebar,
        onOpenConnections = ::showConnectionManager,
        onOpenNewProject = { showAddRemoteProject(false, sidebarConnectionUrl ?: connectedServerUrl) },
        onSelectConnection = ::selectSidebarConnection,
        onRefresh = { requestThreads(serverUrl = sidebarConnectionUrl ?: connectedServerUrl) },
        onOpenProject = { server, workspace -> sidebarConnectionUrl = server; sidebarProjectScope = server to workspace; refreshOpenSidebar(); requestThreads(serverUrl = server) },
        onLoadMore = { requestThreads(serverUrl = sidebarConnectionUrl ?: connectedServerUrl, older = true) },
        onTrashProject = { project ->
            projectStore.trashProject(project.id)
            if (sidebarController.uiState.value.scopedServer == project.serverUrl &&
                sidebarController.uiState.value.scopedWorkspace == project.workspace) {
                sidebarController.setProjectScope(null, null)
                sidebarProjectScope = null
            }
            refreshOpenSidebar()
        },
        onRestoreProject = { project ->
            projectStore.restoreProject(project.id)
            refreshOpenSidebar()
        }
    ) }
    private val conversationController: ConversationController by lazy { ConversationController(
        onOpenSidebar = { showSidebar() }, onOpenArtifacts = ::showTaskArtifacts,
        onCopyText = ::copyText,
        onOpenConnections = ::showConnectionManager,
        onEnableNotifications = ::enableNotifications,
        onExportDiagnostics = ::showDiagnosticExport,
        onShowDiagnostics = {
            val client = connectedServerUrl?.let(connectionClients::get)
            runtimeDialog = RuntimeDialogState("Connection diagnostics", client?.diagnosticsSummary() ?: "Disconnected", listOf(
                RuntimeDialogAction("Diagnostic log") { refreshDiagnosticLog() },
                RuntimeDialogAction("OK") {}, RuntimeDialogAction("Protocol details") { showInfo("Protocol details", client?.diagnostics() ?: "Disconnected") }
            ))
        },
        onOpenModel = { composerController.toggleModelMenu(true) },
        onOpenPermissions = { composerController.togglePermissionMenu(true) },
        onRetryLoading = { currentThreadId?.let(::openThread) }
    ) }
    private val composerController: ComposerController by lazy { ComposerController(
        onSend = { text, _, done -> composerDraft = text; persistComposer(); currentThread?.let(::performComposerSend); done(false) },
        onStopTurn = ::interruptActiveTurn,
        onPickFiles = { pickAttachments(false) }, onPickPhotos = { pickAttachments(true) },
        onOpenSkillsCatalog = ::openCatalog,
        onOpenAttachmentImage = ::openAttachment,
        onRemoveAttachment = { removeComposerAttachment(it.localId) },
        onModelChanged = { id ->
            models.firstOrNull { it.model == id }?.let { selectedModel = id; selectedModelDisplay = it.displayName
                selectedEffort = it.defaultEffort; selectedServiceTier = it.defaultServiceTier; updateComposerLabels() }
        },
        onReasoningEffortChanged = { id -> if (models.firstOrNull { it.model == selectedModel }?.efforts?.any { it.id == id } == true) { selectedEffort = id; updateComposerLabels() } },
        onPermissionModeChanged = { id -> if (PermissionProfiles.menu(permissionProfiles).any { it.id == id && it.enabled }) { selectedPermissionId = id; updateComposerLabels() } },
        onServiceTierChanged = { id -> if (id.isBlank() || models.firstOrNull { it.model == selectedModel }?.serviceTiers?.any { it.id == id } == true) { selectedServiceTier = id.ifBlank { null }; updateComposerLabels() } },
        onPlanModeChanged = { planMode = it },
        onTextChanged = { composerDraft = it.text; persistComposer() }
    ) }
    private val catalogController: CatalogController by lazy { CatalogController(::fetchCatalog, ::fetchApps) { item ->
        if (catalogScope == composerScope && catalogCurrent?.invoke() == true && item.isEnabled)
            addComposerAttachment(ComposerAttachment("remoteCapability", item.name, item.id, item.description))
    } }
    private var catalogScope: String? = null
    private var catalogCurrent: (() -> Boolean)? = null
    private val interactionsController: InteractionsController by lazy { InteractionsController(
        onSubmitReply = { key, result, done -> answerInteraction(key, result, false, done) },
        onCancelReply = { key, result, done -> answerInteraction(key, result, true, done) },
        onOpenUrl = { url -> runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("Could not open the browser") } }
    ) }
    private val artifactsController: ArtifactsController by lazy { ArtifactsController(::fetchArtifacts, { copyText(it, "Artifact") }, ::downloadArtifact) }
    private var artifactSource: RemoteImageSource? = null
    private val artifactRecords = mutableMapOf<String, JSONObject>()
    private val imageRepository by lazy { RemoteImageRepository(
        File(cacheDir, "remote-images"), ::captureImageSource,
        { source, method, params, done -> rpcOn(source.server, method, params, { done(null) }) { done(it) } },
        attachmentPreviewExecutor, java.util.concurrent.Executor { main.post(it) }, ::decodeRemoteBitmap,
        { imageCache.get(it) }, { key, bitmap -> imageCache.put(key, bitmap) }, ::downloadImageArtifact
    ) }
    private var runtimeDialog by mutableStateOf<RuntimeDialogState?>(null)
    private var approvalState by mutableStateOf<ApprovalUiState?>(null)
    private data class PendingInteraction(val key: String, val server: String, val client: RemoteClient?, val message: JSONObject,
        var done: ((Boolean) -> Unit)? = null)
    private val pendingInteractions = linkedMapOf<String, PendingInteraction>()

    private fun mountCompose() {
        setContent {
            val editingServer = connectionsController.uiState.value.editingConnection?.serverUrl
            LaunchedEffect(editingServer) { editingServer?.let { refreshConnectionSettings(it) } }
            CodexApp(connectionsController, projectsController, sidebarController, conversationController,
                composerController, catalogController, interactionsController, artifactsController, imageRepository,
                addConnectionController = addConnectionController, onScanConnection = ::scanConnection,
                modifier = Modifier.safeDrawingPadding(), approvalState = approvalState, onApprovalChoice = ::answerApproval,
                imageScope = composerScope, onOpenAttachment = ::openSentAttachment, onOpenImage = { content, description ->
                    content.bitmap?.let { artifactsController.openImageViewer(it, description, composerScope,
                        content.share != null, content.save != null, content.share, content.save) }
                })
            RuntimeDialog(runtimeDialog) { runtimeDialog = null }
            diagnosticReport?.let { report ->
                app.codexremote.android.ui.theme.CodexTheme {
                    app.codexremote.android.ui.diagnostics.DiagnosticLogDialog(
                        report = report, onRefresh = ::refreshDiagnosticLog, onShare = ::shareDiagnosticLog,
                        onClear = { diagnosticLogs.clear { ok -> runOnUiThread {
                            lastDiagnosticThreadState = null
                            if (!ok) toast("Could not clear diagnostic log")
                            if (diagnosticReport != null) refreshDiagnosticLog()
                        } } },
                        onDismiss = { diagnosticReadGeneration++; diagnosticReport = null }
                    )
                }
            }
        }
    }
    private fun refreshDiagnosticLog() {
        val generation = ++diagnosticReadGeneration
        diagnosticReport = diagnosticReport ?: "Loading diagnostic log…"
        diagnosticLogs.read { report -> runOnUiThread {
            if (!isDestroyed && !isFinishing && generation == diagnosticReadGeneration) diagnosticReport = report
        } }
    }
    private fun recordDiagnosticThreadState(thread: JSONObject) {
        val server = connectedServerUrl ?: return
        if (thread.optString("id").isBlank()) return
        val state = DiagnosticThreadState.capture(server, thread, liveTimelineStore.snapshots(thread.optString("id")),
            turnRunning, server in connectedServerUrls, composerScope in submittingScopes,
            listOfNotNull(tokenStore.load(server)))
        val signature = state.toString()
        if (signature != lastDiagnosticThreadState) {
            lastDiagnosticThreadState = signature
            diagnosticLogs.recordThreadState(state)
        }
    }

    private fun showDiagnosticExport() {
        runtimeDialog = RuntimeDialogState("Export logs",
            "Create a ZIP with recent operation logs, app version and the state of up to 20 recently viewed tasks. " +
                "Chat text, drafts, attachments and credentials are excluded. Logs may contain host names and paths. " +
                "Save to your phone or choose an app to share with. Nothing is uploaded automatically.",
            listOf(RuntimeDialogAction("Save ZIP") { exportDiagnosticBundle(false) },
                RuntimeDialogAction("Share ZIP") { exportDiagnosticBundle(true) }, RuntimeDialogAction("Cancel") {}))
    }

    private fun shareDiagnosticLog() {
        diagnosticReadGeneration++
        diagnosticReport = null
        showDiagnosticExport()
    }

    private fun exportDiagnosticBundle(share: Boolean) {
        if (diagnosticExportBusy || pendingDiagnosticSave != null) { toast("Diagnostic export already in progress"); return }
        currentThread?.let(::recordDiagnosticThreadState)
        val context = JSONObject().put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            .put("buildType", BuildConfig.BUILD_TYPE).put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("apiLevel", android.os.Build.VERSION.SDK_INT).put("deviceModel", android.os.Build.MODEL)
            .put("composerRunning", turnRunning).put("connected", connectedServerUrl in connectedServerUrls)
            .put("pendingRpcCount", callbacks.size).put("submittingScopeCount", submittingScopes.size)
        diagnosticExportBusy = true
        toast("Preparing diagnostic bundle…")
        diagnosticLogs.exportBundle(context) { file -> runOnUiThread {
            diagnosticExportBusy = false
            if (isDestroyed || isFinishing) return@runOnUiThread
            if (file == null) { toast("Could not export diagnostic bundle"); return@runOnUiThread }
            runCatching {
                if (share) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.artifacts", file)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("Diagnostic bundle", uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Share diagnostic bundle"))
                } else {
                    pendingDiagnosticSave = file
                    saveDiagnosticBundle.launch("codex-remote-diagnostics.zip")
                }
            }.onFailure { pendingDiagnosticSave = null; toast("Could not open diagnostic export") }
        } }
    }
    private fun showInfo(title: String, message: String, actions: List<RuntimeDialogAction> = listOf(RuntimeDialogAction("OK") {})) {
        runtimeDialog = RuntimeDialogState(title, message, actions)
    }
    private fun copyText(text: String, label: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }
    private fun dismissKeyboardForPopup() { WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime()) }
    private fun showConnectionScreen() { syncConnections(); closeSidebar(); connectionsController.openManager(true) }
    private fun showConnectionManager() { syncConnections(); closeSidebar(); connectionsController.openManager(false) }
    private fun showConnectingScreen() { syncConnections(); connectionsController.showConversation(); conversationController.setLoading(true) }
    private fun showThreadListLoading() { connectionsController.showConversation(); conversationController.setLoading(true) }
    private fun syncConnections() {
        remoteProjects = projectStore.list()
        connectionsController.updateConnections(remoteProjects, activeProjectId, connectedServerUrl, connectedServerUrls.toSet(), connectingServerUrls.toSet(), projectStore.connections())
    }
    private fun updateConversationConnection() {
        val thread = currentThread ?: JSONObject()
        conversationController.updateThread(currentThreadId, ThreadProjection.threadTitle(thread), thread.optString("cwd"),
            conversationWorkspaceLabel, connectedServerUrl?.let(::hostLabel).orEmpty(), connectedServerUrl in connectedServerUrls,
            connectionFailureReasons[connectedServerUrl])
        syncConnections()
    }
    private fun showSidebar(refresh: Boolean = true) { refreshOpenSidebar(); sidebarController.openSidebar() }
    private fun closeSidebar() { sidebarController.closeSidebar() }
    private fun selectSidebarConnection(server: String) {
        if (projectStore.connections().none { it.serverUrl == server }) return
        sidebarConnectionUrl = server
        sidebarProjectScope = null
        refreshOpenSidebar()
        if (server in connectedServerUrls) {
            requestThreads(serverUrl = server)
        } else if (server !in connectingServerUrls) {
            tokenStore.load(server)?.let { connectToServer(server, it, select = false) }
            refreshOpenSidebar()
        }
    }

    private fun newThreadFromSidebar() {
        val server = sidebarConnectionUrl ?: connectedServerUrl
        val state = sidebarController.uiState.value
        val projects = projectStore.visibleProjects().filter { it.serverUrl == server }
        val workspace = state.scopedWorkspace.takeIf { state.scopedServer == server }
            ?: selectedWorkspace.takeIf { connectedServerUrl == server }
            ?: projects.firstOrNull { it.id == activeProjectId }?.workspace
            ?: projects.firstOrNull()?.workspace
            ?: workspacesByServer[server]?.firstOrNull()
        val project = projects.firstOrNull { it.workspace == workspace }
        if (project != null) {
            activateRemoteProject(project)
        } else if (workspace != null) {
            if (activateSidebarConnection(workspace)) openDraft(workspace)
        } else {
            showAddRemoteProject(false, server)
        }
    }

    private fun refreshOpenSidebar() {
        syncConnections()
        val connections = projectStore.connections()
        val server = sidebarConnectionUrl?.takeIf { selected -> connections.any { it.serverUrl == selected } }
            ?: connectedServerUrl?.takeIf { selected -> connections.any { it.serverUrl == selected } }
            ?: connections.firstOrNull()?.serverUrl
        if (sidebarConnectionUrl != server) sidebarProjectScope = null
        sidebarConnectionUrl = server
        sidebarController.setConnections(connections, server, connectedServerUrls.toSet(), connectingServerUrls.toSet())
        val visibleProjects = projectStore.visibleProjects()
        sidebarController.setProjects(visibleProjects, activeProjectId?.takeIf { id -> visibleProjects.any { it.id == id } })
        sidebarController.setTrashedProjects(projectStore.trashedProjects())
        sidebarController.setConnectionInfo(server, connections.firstOrNull { it.serverUrl == server }?.name.orEmpty())
        sidebarController.setThreads(threadsByServer[server].orEmpty(), threadPages.loading(server), currentThreadId.takeIf { server == connectedServerUrl })
        sidebarController.setPaging(threadPages.hasMore(server) || threadPages.failed(server), threadPages.loading(server))
    }
    private fun renderThreadList(threads: List<RemoteThread>, workspaces: List<String>) {
        listedThreads = threads; listedWorkspaces = workspaces; refreshOpenSidebar(); sidebarController.openSidebar()
    }
    private fun restoreSidebarIfReady(): Boolean {
        val saved = pendingSidebarState ?: return false
        val server = saved.getString("server") ?: return false
        if (saved.getString("device").isNullOrBlank() || tokenStore.loadCredential(server)?.deviceId != saved.getString("device") || remoteProjects.none { it.serverUrl == server }) {
            pendingSidebarState = null; return false
        }
        if (currentThread == null || server !in threadsByServer) return false
        pendingSidebarState = null; sidebarConnectionUrl = server
        sidebarProjectScope = saved.getString("project")?.let { server to it }
        showSidebar(false)
        sidebarController.setProjectScope(server, sidebarProjectScope?.second)
        sidebarController.updateSearchQuery(saved.getString("query").orEmpty())
        return true
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingDiagnosticSave?.let { outState.putString("diagnosticSaveFile", it.name) }
        val drafts = JSONObject(pendingInteractionDrafts.toString())
        interactionsController.uiState.value?.activeKey?.let { key ->
            tokenStore.loadCredential(key.substringBefore('\u0000'))?.deviceId?.let { device ->
                drafts.put(key, JSONObject().put("device", device).put("draft", interactionsController.saveDraft()))
            }
        }
        drafts.toString().takeIf { it.toByteArray().size <= 128 * 1024 }?.let { outState.putString("interactionDrafts", it) }
        if (sidebarController.uiState.value.isOpen) {
            val server = sidebarConnectionUrl ?: connectedServerUrl
            outState.putBundle("remoteSidebar", Bundle().apply {
                putString("server", server); putString("device", server?.let { tokenStore.loadCredential(it)?.deviceId })
                putString("project", sidebarController.uiState.value.scopedWorkspace); putString("query", sidebarController.uiState.value.searchQuery)
            })
        } else pendingSidebarState?.let { outState.putBundle("remoteSidebar", it) }
    }
    override fun onDestroy() {
        persistComposer(); main.removeCallbacksAndMessages(null)
        connectionClients.values.forEach(RemoteClient::close); utilityClient.close(); super.onDestroy()
    }
    private fun navigateBack() {
        when {
            addConnectionController.uiState.value.isOpen -> {
                if (addConnectionController.uiState.value.isConfirming) addConnectionController.edit() else addConnectionController.dismiss()
            }
            connectionsController.uiState.value.selectionMode -> connectionsController.exitSelection()
            runtimeDialog != null -> runtimeDialog = null
            approvalState != null || interactionsController.uiState.value != null -> Unit
            artifactsController.uiState.value.isViewerOpen -> artifactsController.closeImageViewer()
            artifactsController.uiState.value.isOpen -> artifactsController.closeArtifacts()
            catalogController.uiState.value.isOpen -> catalogController.closeCatalog()
            projectsController.uiState.value.isOpen -> projectsController.dismissDialog()
            connectionsController.uiState.value.editingConnection != null -> connectionsController.dismissEditing()
            connectionsController.uiState.value.isManagerOpen && !connectionsController.uiState.value.isRoot -> connectionsController.closeManager()
            composerController.closeMenus() -> Unit
            sidebarController.uiState.value.isOpen -> { sidebarController.back(); if (!sidebarController.uiState.value.isOpen) sidebarProjectScope = null }
            else -> finish()
        }
    }
    private fun showAddRemoteProject(asRoot: Boolean, defaultServer: String? = null) {
        syncConnections()
        projectsController.openDialog(projectStore.connections().map { ProjectHost(it.serverUrl, it.name) }, defaultServer ?: connectedServerUrl)
    }
    private fun browseProjects(server: String, path: String, complete: (List<ProjectFolder>, String?) -> Unit) {
        when {
            server !in connectedServerUrls -> complete(emptyList(), "Pair and connect to this host before browsing, or enter its folder path")
            connectionClients[server]?.opaqueWorkspaceRouting == true -> complete(remoteProjects.filter { it.serverUrl == server }.map { ProjectFolder(it.name, it.workspace) }.distinctBy { it.path }, "Choose a known project or enter a folder path")
            else -> rpcOn(server, "host/workspace/list", JSONObject().put("path", path), { complete(emptyList(), it) }) { result ->
                complete(jsonObjects(result.optJSONArray("directories")).map { ProjectFolder(it.optString("name"), it.optString("path")) }, null)
            }
        }
    }
    private fun resetPendingProjectButton() { projectsController.setBusy(false) }
    private fun submitProject(input: ProjectSubmission) {
        val server = input.serverUrl.trim().trimEnd('/'); val path = input.workspace.trim()
        projectInputError(server, path)?.let { projectsController.showError(it); return }
        val token = tokenStore.load(server); val pairing = input.pairingCode.trim()
        if (token == null && pairing.isBlank()) { projectsController.showError("Enter the one-time pairing code for this host"); return }
        val project = RemoteProjectStore.create(input.name.ifBlank { workspaceName(path) }, input.connectionName.ifBlank { hostLabel(server) }, server, path)
        pendingProject = project; projectsController.setBusy(true)
        fun current() = !isDestroyed && !isFinishing && pendingProject?.id == project.id && projectsController.uiState.value.isOpen
        fun connect() {
            if (!current()) return
            persistComposer(); resetConversationState(); selectedWorkspace = project.workspace; restoreNavigation = false
            tokenStore.load(server)?.let { connectToServer(server, it) } ?: projectsController.showError("Pair this host again")
        }
        if (pairing.isBlank()) connect() else utilityClient.pair(server, pairing, android.os.Build.MODEL) { result -> runOnUiThread {
            if (!current()) return@runOnUiThread
            result.onSuccess { credential -> tokenStore.save(server, credential); connectionClients.remove(server)?.close()
                connectedServerUrls.remove(server); connectingServerUrls.remove(server); connect()
            }.onFailure { pendingProject = null; projectsController.showError(it.message ?: "Pairing failed") }
        } }
    }
    private fun activateRemoteProject(project: RemoteProject) {
        // Save the old task under its original host before changing project scope.
        // A reconnect must open this project's draft, never refresh the previous task id.
        resetConversationState(); restoreNavigation = false
        projectStore.setActiveProject(project.id); activeProjectId = project.id
        pendingDraftProject = project
        selectedWorkspace = project.workspace; closeSidebar(); connectionsController.showConversation()
        connectedServerUrl = project.serverUrl; sidebarConnectionUrl = project.serverUrl
        listedThreads = threadsByServer[project.serverUrl].orEmpty(); listedWorkspaces = workspacesByServer[project.serverUrl].orEmpty()
        if (project.serverUrl in connectedServerUrls) { openDraft(project.workspace); return }
        val token = tokenStore.load(project.serverUrl)
        if (token == null) { showConnectionScreen(); connectionsController.startPairing(project.serverUrl); return }
        showConnectingScreen(); connectToServer(project.serverUrl, token, true)
    }
    private fun pairConnection(server: String, code: String) {
        utilityClient.pair(server, code, android.os.Build.MODEL) { result -> runOnUiThread {
            if (isDestroyed || connectionsController.uiState.value.pairingServerUrl != server) return@runOnUiThread
            result.onSuccess { credential -> tokenStore.save(server, credential); connectionClients.remove(server)?.close()
                connectedServerUrls.remove(server); connectingServerUrls.remove(server); connectionsController.finishPairing(); connectToServer(server, credential.token)
            }.onFailure { connectionsController.finishPairing(it.message ?: "Pairing failed") }
        } }
    }
    private fun showConversation(thread: JSONObject) {
        val conversationKey = thread.optString("id").ifBlank { "draft:${thread.optString("cwd")}" }
        val server = connectedServerUrl
        val device = server?.let { tokenStore.loadCredential(it)?.deviceId }.orEmpty()
        val scope = "$server\u0000$device\u0000$conversationKey"
        if (server != null) getSharedPreferences("remote_navigation", MODE_PRIVATE).edit()
            .putString("thread:$server", thread.optString("id").takeIf(String::isNotBlank))
            .putString("cwd:$server", thread.optString("cwd")).apply()
        if (composerScope != scope) {
            draftImageOpenGate.clear()
            persistComposer()
            composerScope = scope
            composerServer = server
            val saved = draftStore.read(scope)
            val submitted = pendingSubmissionDrafts[scope]
            val awaitingSameDraft = submitted != null && saved.optString("text") == submitted.optString("text") &&
                jsonObjects(saved.optJSONArray("attachments")).map { it.optString("localId") } ==
                jsonObjects(submitted.optJSONArray("attachments")).map { it.optString("localId") }
            composerDraft = if (awaitingSameDraft) "" else saved.optString("text")
            if (awaitingSameDraft) composerAttachments.clear() else restoreAttachments(saved)
            resumeUploads()
        }
        if (intent.action in setOf(android.content.Intent.ACTION_SEND, android.content.Intent.ACTION_SEND_MULTIPLE)) {
            val shared = mutableListOf<Uri>()
            if (intent.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                shared += intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)?.let(shared::add)
            }
            val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            intent.action = android.content.Intent.ACTION_MAIN
            if (!sharedText.isNullOrBlank()) { composerDraft += sharedText; persistComposer() }
            val availableSlots = (4 - composerAttachments.count { it.type == "remoteAttachment" }).coerceAtLeast(0)
            val sharedAttachments = shared.distinct()
            val acceptedAttachments = sharedAttachments.take(availableSlots)
            // The next main-loop iteration may already belong to a different task or host.
            main.post {
                acceptedAttachments.forEach { queueAttachment(it, scope) }
                if (sharedAttachments.size > availableSlots) toast("Up to 4 attachments per draft; remaining files were not added")
            }
        }
        connectionsController.showConversation(); closeSidebar()
        composerController.setDraftIdentity(scope)
        if (composerController.uiState.value.text != composerDraft) composerController.updateText(composerDraft)
        turnRunning = threadIsRunning(thread) || liveAssistantText.isNotBlank() || scope in submittingScopes
        conversationWorkspaceLabel = workspaceDisplayName(thread.optString("cwd"), thread.optString("cwdName"))
        updateConversationConnection(); renderCurrentTimelineFromEvents(thread); updateComposerPrimaryButton(); updateComposerLabels(); updateUsageButton()
        restoreSidebarIfReady()
    }
    private fun applyThreadSnapshot(thread: JSONObject, clearLive: Boolean, lifecycleRevision: Long = turnLifecycleRevision) {
        currentThread = thread
        if (liveTimelineStore.reconcileThreadSnapshot(thread.optString("id"), thread, lifecycleRevision, turnLifecycleRevision)) {
            turnRunning = threadIsRunning(thread) || composerScope in submittingScopes
            updateComposerPrimaryButton()
        }
        if (clearLive) liveAssistantText = ""
        updateConversationConnection(); renderCurrentTimelineFromEvents(thread)
    }
    private fun renderCurrentTimelineFromEvents(thread: JSONObject? = currentThread) {
        thread ?: return
        recordDiagnosticThreadState(thread)
        val items = projectedTimeline(thread).toMutableList()
        if (liveAssistantText.isNotBlank()) items += TimelineItem("live", "Codex", liveAssistantText, TimelineItem.Kind.ASSISTANT)
        val turns = mutableMapOf<String, DeliveryTurn>()
        val terminal = setOf("completed", "failed", "cancelled", "canceled", "interrupted")
        jsonObjects(thread.optJSONArray("turns")).forEach { turn ->
            turns[turn.optString("id")] = DeliveryTurn(jsonObjects(turn.optJSONArray("items"))
                .filter { it.optString("type") == "userMessage" }.map { it.optString("id") }.toSet(), turn.optString("status") in terminal)
        }
        liveTimelineStore.snapshots(currentThreadId ?: thread.optString("id")).forEach { (id, turn) ->
            val old = turns[id]
            turns[id] = DeliveryTurn(old?.userItemIds.orEmpty() + turn.items.filter { it.kind == TimelineItem.Kind.USER }.map { it.id },
                old?.finished == true || turn.status in (terminal - "cancelled"))
        }
        val delivered = outgoingMessages.project(composerScope.orEmpty(), items, turns)
        conversationController.setTimelineItems(delivered.items, turnRunning, delivered.statuses)
    }
    private fun appendLiveAssistantDelta(delta: String) { liveAssistantText += delta; scheduleLiveTimelineRender() }
    private fun updateComposerPrimaryButton() { syncAttachments(); composerController.setTurnRunning(turnRunning) }
    private fun updateComposerActionBarState() = updateComposerPrimaryButton()
    private fun updateComposerLabels() {
        val model = models.firstOrNull { it.model == selectedModel }
        composerController.setModelOptions(models.map { ComposerOption(it.model, it.displayName) }, selectedModel)
        composerController.setEffortOptions(model?.efforts.orEmpty().map { ComposerOption(it.id, effortLabel(it.id), it.description) }, selectedEffort)
        composerController.setServiceTierOptions(listOf(ComposerOption("", "Standard")) + model?.serviceTiers.orEmpty().map { ComposerOption(it.id, it.name, it.description) }, selectedServiceTier)
        composerController.setPermissionOptions(PermissionProfiles.menu(permissionProfiles).map { ComposerOption(it.id, it.label, it.description, it.enabled) }, selectedPermissionId)
        composerController.setPlanMode(planMode)
    }
    private fun updateUsageButton() {
        val usage = currentThreadId?.let(usageByThread::get)
        conversationController.updateTokenUsage(if (usage?.contextWindow != null) ((usage.totalTokens * 100L) / usage.contextWindow).toInt() else 0, usage?.totalTokens, usage?.contextWindow)
    }
    private fun removeComposerAttachment(id: String) {
        val scope = composerScope ?: return
        draftImageOpenGate.cancel(scope, id); draftStore.removeAttachment(scope, id)
        restoreAttachments(draftStore.read(scope)); resumeUploads(); syncAttachments()
    }
    private val thumbnailLoads = mutableSetOf<String>()
    private fun syncAttachments() {
        val scope = composerScope ?: return
        val saved = jsonObjects(draftStore.read(scope).optJSONArray("attachments")).associateBy { it.optString("localId") }
        val items = composerAttachments.map { attachment ->
            val item = saved[attachment.localId] ?: attachment.toJson()
            val state = item.optString("state")
            val upload = when {
                state == "ready" || state.isBlank() && attachment.path.isNotBlank() -> AttachmentUploadState.Ready
                state == "uploading" -> AttachmentUploadState.Uploading(item.optInt("progress"))
                state == "failed" -> AttachmentUploadState.Failed()
                state == "cancelled" -> AttachmentUploadState.Cancelled
                state == "expired" -> AttachmentUploadState.Expired
                state == "waiting" -> AttachmentUploadState.Waiting
                else -> AttachmentUploadState.Preparing
            }
            if (attachment.type in setOf("remoteCapability", "skill", "plugin")) CapabilityTagUiState(attachment.localId, attachment.name, attachment.description)
            else if (item.optString("mimeType").startsWith("image/") || attachment.type == "localImage") {
                val path = item.optString("previewPath").takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("localFile").takeIf { it.isNotBlank() && it != "null" }
                val key = "draft\u0000$scope\u0000${attachment.localId}\u0000$path"
                if (path != null && imageCache.get(key) == null && thumbnailLoads.add(key)) {
                    attachmentPreviewExecutor.execute {
                        val bitmap = runCatching { File(path).takeIf { it.isFile && it.length() <= 20L * 1024 * 1024 }?.readBytes()?.let(::decodeRemoteBitmap) }.getOrNull()
                        main.post {
                            thumbnailLoads.remove(key)
                            if (bitmap != null) imageCache.put(key, bitmap)
                            if (bitmap != null && composerScope == scope && composerAttachments.any { it.localId == attachment.localId }) syncAttachments()
                        }
                    }
                }
                ImageAttachmentUiState(attachment.localId, attachment.name, previewBitmap = imageCache.get(key), localFilePath = path, uploadState = upload)
            } else FileAttachmentUiState(attachment.localId, attachment.name, item.optLong("size"), attachmentMetadata(attachment.name, item.optLong("size")), upload)
        }
        composerController.setAttachments(items, composerAttachments.any { it.path.isBlank() })
    }
    private fun openAttachment(item: AttachmentItemUiState) {
        val attachment = composerAttachments.firstOrNull { it.localId == item.localId } ?: return
        if (item is ImageAttachmentUiState && item.uploadState == AttachmentUploadState.Ready && item.localFilePath != null)
            openDraftImage(attachment, item.localFilePath)
        else attachmentActions(attachment)
    }
    private fun attachmentActions(attachment: ComposerAttachment) {
        val scope = composerScope ?: return
        val state = draftStore.attachment(scope, attachment.localId)?.optString("state")
        val actions = mutableListOf<RuntimeDialogAction>()
        if (attachment.type == "remoteAttachment" && state !in setOf("ready", "expired")) {
            val retry = state in setOf("cancelled", "failed")
            actions += RuntimeDialogAction(if (retry) "Retry upload" else "Cancel upload") {
                draftStore.updateAttachment(scope, attachment.localId) {
                    if (retry) { if (it.optString("path").isBlank()) it.put("state", "queued").put("description", "Queued") else it.put("state", "ready").put("description", "Ready") }
                    else it.put("state", "cancelled").put("description", "Cancelled · tap to retry")
                }
                resumeUploads(); if (composerScope == scope) { restoreAttachments(draftStore.read(scope)); syncAttachments() }
            }
        }
        actions += RuntimeDialogAction("Remove") { draftStore.removeAttachment(scope, attachment.localId); if (composerScope == scope) { restoreAttachments(draftStore.read(scope)); syncAttachments() } }
        actions += RuntimeDialogAction("Close") {}
        showInfo(attachment.name, attachmentLabel(draftStore.attachment(scope, attachment.localId) ?: attachment.toJson()), actions)
    }
    private fun pickAttachments(photos: Boolean) {
        if (connectionClients[connectedServerUrl]?.supportsChunkedAttachments() != true) { showAttachmentUnavailable(); return }
        rememberPickerScope()
        if (photos) choosePhotos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else chooseAttachments.launch(arrayOf("*/*"))
    }
    private fun showAttachmentUnavailable() { showInfo("Attachments unavailable", "This host does not support resumable file uploads. Update the Remote Host before adding photos or files. Your existing draft and attachments are kept.") }
    private fun showFullImage(bitmap: Bitmap, description: String) { artifactsController.openImageViewer(bitmap, description, composerScope) }
    private fun openSentAttachment(attachment: MessageAttachment) {
        val source = captureImageSource() ?: return toast("Open a connected task first")
        ArtifactDownloads(this, artifactsController::onDownloadEvent, { name, text -> showInfo(name, text) })
            .downloadAttachment(source.server, source.token, attachment.id, source.stillCurrent)
    }
    private fun captureImageSource(): RemoteImageSource? {
        val server = connectedServerUrl ?: return null
        val thread = currentThreadId ?: return null
        val client = connectionClients[server] ?: return null
        val credential = tokenStore.loadCredential(server) ?: return null
        val ticket = navigationRequests.capture(server, thread)
        return RemoteImageSource(server, credential.deviceId ?: return null, thread, credential.token,
            client.supportsAttachmentPreviews(), client.supportsRpcMethod("host/artifacts/list")) {
            !isDestroyed && !isFinishing && navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId) &&
                connectionClients[server] === client && tokenStore.loadCredential(server)?.deviceId == credential.deviceId
        }
    }
    private fun downloadImageArtifact(source: RemoteImageSource, reference: String, share: Boolean) {
        if (!source.stillCurrent()) return
        rpcOn(source.server, "host/artifacts/list", JSONObject().put("threadId", source.threadId), { if (source.stillCurrent()) toast(it) }) { result ->
            if (!source.stillCurrent()) return@rpcOn
            val artifact = jsonObjects(result.optJSONArray("artifacts")).singleOrNull { it.optString("imageReference") == reference && it.optString("mimeType").startsWith("image/") }
            if (artifact == null) toast("Original image is unavailable; refresh the task")
            else ArtifactDownloads(this, artifactsController::onDownloadEvent).download(source.server, source.token, artifact, share, source.stillCurrent)
        }
    }
    private fun showTaskArtifacts() {
        val source = captureImageSource() ?: return toast("Open a connected task first")
        if (!source.artifacts) return toast("This host does not support secure artifact downloads; update the host")
        artifactSource = source; artifactsController.openArtifacts(source.threadId ?: return, composerScope)
    }
    private fun fetchArtifacts(thread: String, done: (List<ArtifactItem>, String?) -> Unit) {
        val source = artifactSource ?: return done(emptyList(), "Disconnected")
        rpcOn(source.server, "host/artifacts/list", JSONObject().put("threadId", thread), { if (source.stillCurrent()) done(emptyList(), it) }) { result ->
            if (!source.stillCurrent()) return@rpcOn
            artifactRecords.clear()
            val items = jsonObjects(result.optJSONArray("artifacts")).map { value ->
                val id = value.optString("id"); artifactRecords[id] = value
                ArtifactItem(id, value.optString("name"), value.optString("path"), value.optLong("size"), value.optString("mimeType"))
            }
            done(items, null)
        }
    }
    private fun downloadArtifact(item: ArtifactItem) {
        val source = artifactSource ?: return
        val artifact = artifactRecords[item.id] ?: return
        if (source.stillCurrent()) ArtifactDownloads(this, artifactsController::onDownloadEvent).download(source.server, source.token, artifact, stillCurrent = source.stillCurrent)
    }
    private fun openCatalog() {
        val server = connectedServerUrl ?: return
        if (connectionClients[server]?.supportsRpcMethod("host/capabilities/list") != true) { toast("Update this host to discover plugins and skills"); return }
        val ticket = navigationRequests.capture(server, currentThreadId)
        val device = tokenStore.loadCredential(server)?.deviceId
        catalogScope = composerScope
        catalogCurrent = { !isDestroyed && navigationRequests.accepts(ticket, connectedServerUrl, currentThreadId) && tokenStore.loadCredential(server)?.deviceId == device }
        catalogController.openCatalog(server, currentThread?.optString("cwd").orEmpty())
    }
    private fun fetchCatalog(cwd: String, done: (List<CatalogCapabilityItem>, List<String>) -> Unit) {
        val server = connectedServerUrl ?: return done(emptyList(), listOf("Disconnected"))
        val current = catalogCurrent ?: return
        rpcOn(server, "host/capabilities/list", JSONObject().put("cwd", cwd), { if (current()) done(emptyList(), listOf(it)) }) { result ->
            if (!current()) return@rpcOn
            done(jsonObjects(result.optJSONArray("entries")).map { value -> CatalogCapabilityItem(
                value.optString("id"), value.optString("name"), value.optString("description"), value.optString("kind"), value.optString("state"), value.optString("detail"), value.optString("source")) },
                result.optJSONArray("errors")?.let { errors -> (0 until errors.length()).map { errors.optString(it) } }.orEmpty())
        }
    }
    private fun fetchApps(done: (List<ConnectedAppItem>, String?) -> Unit) {
        val server = connectedServerUrl ?: return done(emptyList(), "Disconnected")
        val current = catalogCurrent ?: return
        if (connectionClients[server]?.supportsRpcMethod("host/apps/installed") != true) { done(emptyList(), "This host does not advertise connected apps"); return }
        rpcOn(server, "host/apps/installed", JSONObject(), { if (current()) done(emptyList(), it) }) { result ->
            if (current()) done(jsonObjects(result.optJSONArray("apps")).map { ConnectedAppItem(it.optString("name"), it.optBoolean("callable"), it.optBoolean("enabled")) }, null)
        }
    }
    private fun saveConnection(original: String, name: String, serverValue: String, codeValue: String) {
        val server = serverValue.trim().trimEnd('/'); val code = codeValue.trim()
        if (server.isBlank()) return refreshConnectionSettings(original, "Enter the server URL")
        if (server != original && projectStore.connections().any { it.serverUrl == server }) return refreshConnectionSettings(original, "A connection with this server URL already exists")
        if (server != original && code.isBlank()) return refreshConnectionSettings(original, "Enter a new pairing code when changing the server URL")
        fun current() = !isDestroyed && projectStore.connections().any { it.serverUrl == original } && connectionsController.uiState.value.editingConnection?.serverUrl == original
        fun finish(credential: DeviceCredential?) {
            if (!current()) return
            val wasActive = connectedServerUrl == original
            if (wasActive) persistComposer()
            projectStore.updateConnection(original, name.trim().ifBlank { hostLabel(server) }, server)
            credential?.let { tokenStore.save(server, it) }
            if (server != original) {
                tokenStore.clear(original); connectionClients.remove(original)?.close()
                connectedServerUrls.remove(original); connectingServerUrls.remove(original)
                threadsByServer.remove(original); threadPages.remove(original); workspacesByServer.remove(original)
                if (wasActive) { resetConversationState(); connectedServerUrl = server }
                if (sidebarConnectionUrl == original) sidebarConnectionUrl = server
            }
            syncConnections()
            if (server != original || credential != null) {
                connectionClients.remove(server)?.close(); connectedServerUrls.remove(server); connectingServerUrls.remove(server)
                tokenStore.load(server)?.let { connectToServer(server, it, wasActive) }
            }
            projectStore.connections().firstOrNull { it.serverUrl == server }?.let(connectionsController::startEditing)
            refreshConnectionSettings(server, "Connection settings saved")
        }
        if (code.isBlank()) finish(null) else {
            refreshConnectionSettings(original, "Pairing…", true)
            utilityClient.pair(server, code, android.os.Build.MODEL) { result -> runOnUiThread {
                if (!current()) return@runOnUiThread
                result.onSuccess(::finish).onFailure { refreshConnectionSettings(original, it.message ?: "Pairing failed") }
            } }
        }
    }
    private fun refreshConnectionSettings(server: String, status: String = "", busy: Boolean = false) {
        val credential = tokenStore.loadCredential(server)
        val summary = credential?.expiresAt?.take(10)?.let { "Credential expires $it · ${credential.scopes.size} scopes" }
            ?: "Legacy credential metadata will be added on the next rotation."
        val client = connectionClients[server]
        fun current() = !isDestroyed && connectionsController.uiState.value.editingConnection?.serverUrl == server && connectionClients[server] === client
        connectionsController.updateSettingsStatus(server, status, summary, "Checking Codex sign-in on the host…", busy)
        if (server !in connectedServerUrls || client?.supportsRpcMethod("host/account/status") != true) {
            connectionsController.updateSettingsStatus(server, status, summary, "Connect with a v2 Host to inspect Codex sign-in.", busy); return
        }
        rpcOn(server, "host/account/status", JSONObject(), { if (current()) connectionsController.updateSettingsStatus(server, status, summary, "Codex account status unavailable · $it", busy) }) { result ->
            if (!current()) return@rpcOn
            val account = when {
                result.optBoolean("authenticated") -> listOf("Signed in with " + result.optString("authMode").ifBlank { "Codex" }, result.optString("planType"), result.optString("email")).filter(String::isNotBlank).joinToString(" · ")
                result.optBoolean("requiresOpenaiAuth") -> "Codex sign-in is required on the host."
                result.optBoolean("ready") -> "Host provider is ready without OpenAI credentials."
                else -> "Codex account is not ready."
            }
            connectionsController.updateSettingsStatus(server, status, summary, account, busy)
        }
    }
    private fun rotateCredential(server: String) {
        val credential = tokenStore.loadCredential(server) ?: return toast("No saved credential for this host")
        refreshConnectionSettings(server, "Rotating device credential…", true)
        utilityClient.rotateCredential(server, credential.token) { result -> runOnUiThread {
            if (isDestroyed || tokenStore.loadCredential(server)?.token != credential.token) return@runOnUiThread
            result.onSuccess { replacement -> tokenStore.save(server, replacement); connectionClients.remove(server)?.close()
                connectedServerUrls.remove(server); connectingServerUrls.remove(server)
                connectToServer(server, replacement.token, connectedServerUrl == server); refreshConnectionSettings(server, "Device credential rotated")
            }.onFailure { refreshConnectionSettings(server, it.message ?: "Credential rotation failed") }
        } }
    }
    private fun revokeCredential(server: String) {
        val credential = tokenStore.loadCredential(server) ?: return toast("No saved credential for this host")
        utilityClient.revokeCredential(server, credential.token) { result -> runOnUiThread {
            if (isDestroyed || tokenStore.loadCredential(server)?.token != credential.token) return@runOnUiThread
            result.onSuccess { tokenStore.clear(server); connectionClients.remove(server)?.close(); connectedServerUrls.remove(server)
                connectingServerUrls.remove(server); syncConnections(); refreshConnectionSettings(server, "This device is revoked")
            }.onFailure { refreshConnectionSettings(server, it.message ?: "Credential revocation failed") }
        } }
    }
    private fun removeProject(project: RemoteProject) {
        showInfo("Remove ${project.name}?", "This removes the saved remote project from this device. Chats and files on the host are not deleted.", listOf(
            RuntimeDialogAction("Remove") {
                projectStore.saveConnection(project.serverUrl, project.connectionName)
                projectStore.delete(project.id); remoteProjects = projectStore.list()
                if (activeProjectId == project.id) {
                    val replacement = remoteProjects.firstOrNull { it.serverUrl == project.serverUrl } ?: remoteProjects.firstOrNull()
                    activeProjectId = replacement?.id; selectedWorkspace = replacement?.workspace; projectStore.setActiveProject(activeProjectId)
                }
                // Removing a project must not remove its independently configured connection.
                syncConnections(); connectionsController.dismissEditing()
            }, RuntimeDialogAction("Cancel") {}
        ))
    }
    private fun onMessage(serverUrl: String, message: JSONObject) = runOnUiThread {
        if (isDestroyed || isFinishing) return@runOnUiThread
        TaskNotifications.event(this, serverUrl, message)
        val type = message.optString("type")
        if (type == "codex_event") {
            val method = message.optString("method")
            val params = message.optJSONObject("params") ?: JSONObject()
            applySidebarRunningEvent(threadsByServer[serverUrl].orEmpty(), method, params)?.let { updated ->
                threadsByServer[serverUrl] = updated
                if (serverUrl == sidebarConnectionUrl) {
                    listedThreads = updated
                    refreshOpenSidebar()
                }
            }
            if (method in SIDEBAR_RUNNING_REFRESH_EVENTS) requestThreads(serverUrl = serverUrl)
        }
        if (type == "codex_event" && message.optString("method") in setOf("turn/started", "turn/completed", "turn/failed", "turn/cancelled", "error")) {
            val params = message.optJSONObject("params") ?: JSONObject()
            val turn = params.optJSONObject("turn")
            val error = turn?.optJSONObject("error") ?: params.optJSONObject("error")
            diagnosticLogs.record("task.${message.optString("method")}", serverUrl,
                "threadId=${params.optString("threadId")} turnId=${turn?.optString("id") ?: params.optString("turnId")} status=${turn?.optString("status").orEmpty()}" +
                    (error?.optString("message")?.takeIf(String::isNotBlank)?.let { "\n$it" } ?: ""),
                listOfNotNull(tokenStore.load(serverUrl)))
        }
        if (type == "host_session_changed" || type == "codex_event" && Regex("skills/changed|plugin.*changed|app.*updated").containsMatchIn(message.optString("method"))) {
            capabilityPresentationCache.invalidateServer(serverUrl)
            if (catalogController.uiState.value.isOpen && catalogController.uiState.value.serverUrl == serverUrl) catalogController.refresh()
        }
        when (type) {
            "rpc_result" -> { val id = message.optString("id"); rpcDiagnostics.finish(id, serverUrl, "success"); callbackErrors.remove(id); callbacks.remove(id)?.invoke(message.optJSONObject("result") ?: JSONObject()) }
            "rpc_error", "protocol_error", "host_error" -> {
                val id = message.optString("id"); callbacks.remove(id)
                val detail = message.optString("error").ifBlank { message.optString("message", "Remote error") }
                val errorObject = message.optJSONObject("error")
                rpcDiagnostics.finish(id, serverUrl, type,
                    errorObject?.optString("message") ?: detail,
                    errorObject?.opt("code")?.toString() ?: message.opt("code")?.toString().orEmpty(),
                    listOfNotNull(tokenStore.load(serverUrl)))
                callbackErrors.remove(id)?.invoke(detail) ?: toast(detail)
            }
            "codex_event" -> if (serverUrl == connectedServerUrl) handleCodexEvent(message)
            "host_session_changed" -> {
                pendingInteractionDrafts.keys().asSequence().filter { it.startsWith("$serverUrl\u0000") }.toList().forEach(pendingInteractionDrafts::remove)
                pendingInteractions.values.filter { it.server == serverUrl }.map { it.key }.forEach { finishInteraction(it, false) }
                remoteSkillsCache.clear(); remotePluginsCache.clear(); remoteAppsCache.clear()
            }
            "server_response_ack" -> {
                val key = "$serverUrl\u0000${message.optString("requestId")}"
                if (message.optString("status") == "invalid") {
                    pendingInteractions[key]?.done?.invoke(false); pendingInteractions[key]?.done = null
                    if (interactionsController.uiState.value?.activeKey == key) interactionsController.rejected(message.optString("error", "Check your answers"))
                    if (approvalState?.key == key) approvalState = approvalState?.copy(isBusy = false, error = message.optString("error", "Check this approval on the host"))
                } else {
                    finishInteraction(key, true)
                    if (shouldNotifyInteractionExpiry(message)) toast("This request expired or was answered elsewhere")
                    if (serverUrl == connectedServerUrl) scheduleRefresh()
                }
            }
            "codex_request" -> showApproval(message, connectionClients[serverUrl], serverUrl)
        }
    }
    private fun clearInteractions() {
        interactionsController.uiState.value?.activeKey?.let(interactionsController::dismissInteraction)
        approvalState = null; pendingInteractions.clear()
    }
    private fun finishInteraction(key: String, acknowledged: Boolean) {
        val request = pendingInteractions.remove(key)
        pendingInteractionDrafts.remove(key)
        request?.done?.invoke(acknowledged)
        interactionsController.dismissInteraction(key)
        if (approvalState?.key == key) approvalState = null
        presentNextInteraction()
    }
    private fun showApproval(message: JSONObject, sourceClient: RemoteClient?, sourceServer: String) {
        val key = "$sourceServer\u0000${message.optString("requestId")}"
        if (pendingInteractions.containsKey(key)) return
        pendingInteractions[key] = PendingInteraction(key, sourceServer, sourceClient, message)
        presentNextInteraction()
    }
    private fun presentNextInteraction() {
        if (approvalState != null || interactionsController.uiState.value != null) return
        val request = pendingInteractions.values.firstOrNull() ?: return
        val method = request.message.optString("method"); val params = request.message.optJSONObject("params") ?: JSONObject()
        if (method in setOf("item/tool/requestUserInput", "mcpServer/elicitation/request")) {
            val saved = pendingInteractionDrafts.remove(request.key) as? JSONObject
            val draft = saved?.takeIf { it.optString("device").isNotBlank() && it.optString("device") == tokenStore.loadCredential(request.server)?.deviceId }?.optJSONObject("draft")
            if (runCatching { InteractionFormModel(method, params, draft) }.isSuccess) {
                interactionsController.showInteraction(request.key, method, params, draft); return
            }
            approvalState = ApprovalUiState(request.key, "Host action required", "This interaction requires an unsupported form or host-side authorization. Open this task on the host to handle it.", listOf(ApprovalUiChoice("cancel", "Cancel request")))
            return
        }
        if (method !in setOf("item/permissions/requestApproval", "item/commandExecution/requestApproval", "item/fileChange/requestApproval")) {
            runCatching { request.client?.answerError(request.message.optString("requestId"), "Unsupported interactive request; handle it on the host") }
            pendingInteractions.remove(request.key); toast("Host action required for $method"); presentNextInteraction(); return
        }
        val choices = approvalChoices(method, params)
        val detail = ApprovalDetails.format(method, params) + if (!choices.allow) "\n\nReview this request on the host, or deny it here." else ""
        approvalState = ApprovalUiState(request.key, when { method.contains("permissions") -> "Allow additional permissions?"; method.contains("commandExecution") -> "Allow command?"; else -> "Allow file changes?" }, detail,
            buildList { if (choices.allow) add(ApprovalUiChoice("allow", "Allow")); add(ApprovalUiChoice("deny", choices.negativeLabel)) })
    }
    private fun approvalChoices(method: String, params: JSONObject): ApprovalChoices = when {
        method == "item/commandExecution/requestApproval" -> ApprovalChoices.from(params)
        method == "item/fileChange/requestApproval" && (params.optJSONObject("fileChangeReview")?.optString("status") != "available" || !params.isNull("grantRoot") && params.optString("grantRoot").isNotBlank()) -> ApprovalChoices(false, "decline")
        else -> ApprovalChoices(true, "decline")
    }
    private fun answerInteraction(key: String, result: JSONObject, cancel: Boolean, done: (Boolean) -> Unit) {
        val request = pendingInteractions[key] ?: return done(false)
        val client = request.client ?: return done(false)
        if (connectionClients[request.server] !== client) { done(false); interactionsController.rejected("Reconnect to this host before replying"); return }
        request.done = done
        runCatching {
            if (cancel && request.message.optString("method") == "item/tool/requestUserInput") client.answerError(request.message.optString("requestId"), "User cancelled the question")
            else client.answer(request.message.optString("requestId"), result)
        }.onFailure { request.done = null; done(false); interactionsController.rejected("Disconnected. Your reply will be retried when this host reconnects.") }
        if (!client.supportsInteractionAcknowledgements()) finishInteraction(key, true)
    }
    private fun answerApproval(expectedKey: String, choice: String) {
        val state = approvalState ?: return
        // An inline card can remain in composition while a request expires or
        // navigation changes. Its callback must never answer the next request.
        if (state.key != expectedKey || state.isBusy) return
        if (state.choices.none { it.id == choice && it.enabled }) return
        val request = pendingInteractions[state.key] ?: return
        val client = request.client ?: return
        if (connectionClients[request.server] !== client) return
        val method = request.message.optString("method"); val params = request.message.optJSONObject("params") ?: JSONObject()
        val choices = approvalChoices(method, params); val allow = choice == "allow"
        if (allow && !choices.allow) return
        val permissions = method == "item/permissions/requestApproval"
        val result = if (permissions) JSONObject().put("permissions", if (allow) params.optJSONObject("permissions") ?: JSONObject() else JSONObject()).put("scope", "turn")
            else JSONObject().put("decision", if (allow) "accept" else choices.negativeDecision)
        approvalState = state.copy(isBusy = true, error = null)
        runCatching {
            if (choice == "cancel" || !permissions && !allow && choices.negativeDecision == null) client.answerError(request.message.optString("requestId"), "Unsupported approval decisions; user cancelled the request")
            else client.answer(request.message.optString("requestId"), result)
        }.onFailure { approvalState = state.copy(error = "Reply queued until the host reconnects") }
        if (!client.supportsInteractionAcknowledgements()) finishInteraction(state.key, true)
    }

    private companion object {
        val attachmentPreviewExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

        val STRUCTURAL_REFRESH_EVENTS = setOf(
            "turn/started",
            "item/started",
            "item/completed",
            "turn/completed",
            "turn/failed",
            "turn/cancelled",
        )
        val TURN_FINISHED_EVENTS = setOf("turn/completed", "turn/failed", "turn/cancelled")
        val SIDEBAR_RUNNING_REFRESH_EVENTS = setOf(
            "thread/status/changed",
            "turn/started",
            "turn/completed",
            "turn/failed",
            "turn/cancelled",
        )
        const val DRAWER_ANIMATION_MS = 260L
        const val DRAWER_MIN_SETTLE_MS = 90L
        const val DRAWER_FLING_VELOCITY_DP = 400
        const val DRAWER_OPEN_GESTURE_FRACTION = 0.28f
        const val DRAWER_SETTLE_OPEN_FRACTION = 0.72f
        const val WORKSPACE_ANIMATION_MS = 220L
        const val MAX_VISIBLE_PROJECT_SESSIONS = 5
        const val LIVE_TIMELINE_RENDER_MS = 80L
    }
}

private data class ComposerAttachment(
    val type: String,
    val name: String,
    val path: String,
    val description: String,
    val previewPath: String? = null,
    val localId: String = UUID.randomUUID().toString(),
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("name", name).put("path", path)
        .put("description", description).put("previewPath", previewPath).put("localId", localId)
    companion object {
        fun fromJson(item: JSONObject) = ComposerAttachment(item.optString("type"), item.optString("name"),
            item.optString("path"), item.optString("description"),
            item.optString("previewPath").takeIf { it.isNotBlank() && it != "null" }, item.getString("localId"))
    }
}

private data class RemoteInstalledApp(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val callable: Boolean,
)

private enum class ModelMenuPage { ROOT, MODEL, EFFORT, SPEED }

private data class EffortOption(val id: String, val description: String)
private data class ServiceTierOption(val id: String, val name: String, val description: String)

private data class ModelOption(
    val model: String,
    val displayName: String,
    val defaultEffort: String?,
    val efforts: List<EffortOption>,
    val defaultServiceTier: String?,
    val serviceTiers: List<ServiceTierOption>,
    val isDefault: Boolean,
)


private data class UsageSnapshot(val totalTokens: Long, val contextWindow: Long?)

private data class ComposerSubmission(val server: String, val scope: String, val model: String?, val effort: String?, val tier: String?, val permission: String?, val plan: Boolean,
    val messageId: String, val draft: JSONObject,
    val newDraftScope: String? = null, val newDraftThread: JSONObject? = null)
