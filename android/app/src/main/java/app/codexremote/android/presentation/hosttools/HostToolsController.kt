package app.codexremote.android.presentation.hosttools

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class HostToolsController(
    private val rpc: (serverId: String, method: String, params: JSONObject, done: (JSONObject?, String?) -> Unit) -> Unit,
    private val onOpenExternalUrl: (serverId: String, url: String) -> Unit = { _, _ -> },
) {
    private data class Pending(
        val preview: HostActionPreview,
        val revision: Long,
        val serverDeviceRevision: Long,
        val method: String,
        val params: JSONObject,
        val terminalId: String? = null,
    )
    private data class DisplayedMcpOauth(val linkId: String, val name: String, val threadId: String?)

    private val _uiState = mutableStateOf(HostToolsUiState())
    val uiState: State<HostToolsUiState> = _uiState
    private var revision = 0L
    private var serverDeviceRevision = 0L
    private var readSequence = 0L
    private val activeReads = mutableMapOf<String, Long>()
    private val terminalUtf8Carry = mutableMapOf<String, ByteArray>()
    private var pending: Pending? = null
    private var displayedMcpOauth: DisplayedMcpOauth? = null

    fun setScope(scope: HostToolsScope?, supportedMethods: Set<String>) {
        val exactMethods = supportedMethods.intersect(EXACT_METHODS)
        val old = _uiState.value.scope
        if (old == scope && _uiState.value.supportedMethods == exactMethods) return
        val sameServerDevice = old != null && scope != null &&
            old.serverId == scope.serverId && old.deviceId == scope.deviceId
        revision++
        pending = null
        if (!sameServerDevice) {
            serverDeviceRevision++
            activeReads.clear()
            terminalUtf8Carry.clear()
            displayedMcpOauth = null
            _uiState.value = HostToolsUiState(scope = scope, supportedMethods = exactMethods)
        } else {
            val oldState = _uiState.value
            activeReads.keys.removeAll { it == "host/mcp/status" }
            displayedMcpOauth = null
            _uiState.value = HostToolsUiState(
                scope = scope,
                supportedMethods = exactMethods,
                account = oldState.account,
                terminals = oldState.terminals,
                actions = oldState.actions,
                externalLink = oldState.externalLink?.takeIf { it.kind == ExternalLinkKind.ACCOUNT_LOGIN },
            )
        }
    }

    fun supports(method: String): Boolean = method in _uiState.value.supportedMethods

    fun refreshAccount() = read(
        method = "host/account/status",
        apply = { result ->
            val old = _uiState.value.account
            _uiState.value = _uiState.value.copy(account = old.copy(
                loading = false, authenticated = result.optBoolean("authenticated"), ready = result.optBoolean("ready"),
                requiresOpenAiAuth = result.optBoolean("requiresOpenaiAuth"), authMode = result.string("authMode"),
                email = result.string("email"), planType = result.string("planType"), credentialSource = result.string("credentialSource"),
                error = null,
            ))
        },
        onStart = { _uiState.value = _uiState.value.copy(account = _uiState.value.account.copy(loading = true, error = null)) },
    )

    fun refreshMcp(threadId: String? = _uiState.value.scope?.taskId) = read(
        "host/mcp/status",
        JSONObject().apply { threadId?.let { put("threadId", it) } },
        apply = { result ->
            _uiState.value = _uiState.value.copy(
                mcpServers = result.array("servers").objects().map { value -> McpServerState(
                    value.optString("name"), value.string("title"), value.string("version"), value.optString("authStatus"), value.optInt("toolCount"),
                ) }, mcpLoading = false, mcpError = null,
            )
        },
        onStart = { _uiState.value = _uiState.value.copy(mcpLoading = true, mcpError = null) },
    )

    fun refreshPlugins() = read(
        method = "host/plugin/catalog",
        apply = { result ->
            val plugins = result.array("marketplaces").objects().flatMap { market ->
                market.array("plugins").objects().map { plugin -> PluginState(
                    plugin.optString("id"), plugin.optString("name"), plugin.string("version"), plugin.string("localVersion"),
                    plugin.optBoolean("installed"), plugin.optBoolean("enabled"), plugin.optString("authPolicy"),
                    plugin.string("availability"), market.optString("name"),
                ) }
            }
            _uiState.value = _uiState.value.copy(plugins = plugins, pluginsLoading = false, pluginsError = null)
        },
        onStart = { _uiState.value = _uiState.value.copy(pluginsLoading = true, pluginsError = null) },
    )

    fun refreshSettings() = read(
        method = "host/settings/read",
        apply = { result ->
            val values = result.optJSONObject("settings") ?: JSONObject()
            _uiState.value = _uiState.value.copy(settings = NamedSettingsState(
                values.string("web_search"), values.string("model_verbosity"), values.string("model_reasoning_summary"),
            ))
        },
        onStart = { _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(loading = true, error = null)) },
    )

    fun refreshTerminals() = read(
        method = "host/terminal/list",
        apply = { result ->
            val fresh = result.array("terminals").objects().associate { value ->
                val id = value.optString("terminalId")
                id to (_uiState.value.terminals[id] ?: TerminalState(id)).copy(
                    cwdName = value.optString("cwdName"), tty = value.optBoolean("tty"), startedAt = value.string("startedAt"), active = true,
                )
            }
            val history = _uiState.value.terminals.filterValues { !it.active }
            _uiState.value = _uiState.value.copy(terminals = (history + fresh).takeLastEntries(MAX_TERMINALS))
        },
    )

    fun previewLogin(flow: LoginFlow) = preview(
        HostActionKind.START_LOGIN, "Sign in to Codex", flow.name.lowercase(),
        if (flow == LoginFlow.BROWSER) "Starts browser sign-in; the link opens only after you tap Open" else "Starts device sign-in and shows a temporary code",
        "host/account/login/start", JSONObject().put("flow", flow.name.lowercase()),
    )

    fun previewCancelLogin(loginId: String) {
        requireId(loginId)
        previewConfirmed(
            HostActionKind.CANCEL_LOGIN, "Cancel sign-in", loginId, "Invalidates this pending sign-in attempt",
            "host/account/login/cancel", JSONObject().put("loginId", loginId), "cancel_login", loginId,
        )
    }

    fun previewLogout() = previewConfirmed(
        HostActionKind.LOGOUT, "Log out", "Codex account", "Signs the host out of its current Codex account",
        "host/account/logout", JSONObject(), "logout_account", "Codex account",
    )

    fun previewMcpOauth(name: String, scopes: List<String> = emptyList()) {
        requireSimple(name, 200)
        require(scopes.size <= 20 && scopes.all { it.isNotBlank() && it.length <= 200 && it.none { char -> char in "\r\n\u0000" } })
        previewConfirmed(
            HostActionKind.START_MCP_OAUTH, "Authorize MCP server", name, "Starts OAuth; the authorization link opens only after you tap Open",
            "host/mcp/oauth/start", JSONObject().put("name", name).put("scopes", JSONArray(scopes))
                .apply { _uiState.value.scope?.taskId?.let { put("threadId", it) } }, "start_mcp_oauth", name,
        )
    }

    fun previewMcpReload() = previewConfirmed(
        HostActionKind.RELOAD_MCP, "Reload MCP", "MCP configuration", "Restarts discovery for configured MCP servers",
        "host/mcp/reload", JSONObject(), "reload_mcp", "MCP configuration",
    )

    fun previewPluginInstall(pluginName: String, marketplace: String? = null) {
        requireSimple(pluginName, 200)
        marketplace?.let { requireSimple(it, 200) }
        previewConfirmed(
            HostActionKind.INSTALL_PLUGIN, "Install plugin", pluginName, "Installs this catalog plugin on the host",
            "host/plugin/install", JSONObject().put("pluginName", pluginName).apply { marketplace?.let { put("remoteMarketplaceName", it) } },
            "install_plugin", pluginName,
        )
    }

    fun previewPluginUninstall(pluginId: String) {
        requireId(pluginId)
        previewConfirmed(
            HostActionKind.UNINSTALL_PLUGIN, "Uninstall plugin", pluginId, "Removes this installed plugin from the host",
            "host/plugin/uninstall", JSONObject().put("pluginId", pluginId), "uninstall_plugin", pluginId,
        )
    }

    fun previewSetting(setting: NamedSetting, value: String) {
        require(value in SETTING_VALUES.getValue(setting)) { "Unsupported setting value" }
        previewConfirmed(
            HostActionKind.CHANGE_SETTING, "Change setting", setting.wireName,
            "Sets ${setting.wireName} to $value on the host", "host/settings/set",
            JSONObject().put("setting", setting.wireName).put("value", value), "change_setting", setting.wireName,
        )
    }

    fun previewSkill(name: String, enabled: Boolean) {
        requireSimple(name, 200)
        previewConfirmed(
            HostActionKind.SET_SKILL, if (enabled) "Enable skill" else "Disable skill", name,
            if (enabled) "Allows this skill to be used on the host" else "Prevents this skill from being used on the host",
            "host/skill/setEnabled", JSONObject().put("name", name).put("enabled", enabled), "set_skill_enabled", name,
        )
    }

    fun previewMemoryMode(mode: MemoryMode) {
        val threadId = _uiState.value.scope?.taskId ?: throw IllegalStateException("Select a task")
        previewConfirmed(
            HostActionKind.SET_MEMORY_MODE, "Change task memory", threadId,
            "Sets memory mode to ${mode.wireValue} for this task", "host/thread/memoryMode/set",
            JSONObject().put("threadId", threadId).put("mode", mode.wireValue), "set_memory_mode", threadId,
        )
    }

    fun previewExecuteArgv(argv: List<String>, cwd: String, tty: Boolean = false, rows: Int = 24, cols: Int = 80) =
        previewTerminal(argv, cwd, tty, rows, cols, argv.joinToString(" "))

    fun previewExecuteShell(command: String, cwd: String, tty: Boolean = false, rows: Int = 24, cols: Int = 80) =
        previewTerminal(listOf("/bin/sh", "-lc", command), cwd, tty, rows, cols, command)

    private fun previewTerminal(argv: List<String>, cwd: String, tty: Boolean, rows: Int, cols: Int, exactPreview: String) {
        require(argv.isNotEmpty() && argv.size <= 64 && argv.all { it.isNotEmpty() && it.length <= 4096 && it.none { char -> char in "\u0000\r\n" } })
        require(argv.sumOf { it.length } <= 16_384)
        require(cwd.isNotBlank())
        require(rows in 2..500 && cols in 2..500)
        val terminalId = UUID.randomUUID().toString()
        val params = JSONObject().put("terminalId", terminalId).put("cwd", cwd).put("argv", JSONArray(argv)).put("tty", tty)
            .put("size", JSONObject().put("rows", rows).put("cols", cols))
        previewConfirmed(
            HostActionKind.EXECUTE_TERMINAL, "Run command", exactPreview, "Executes this exact command in the selected workspace",
            "host/terminal/execute", params, "execute_command", argv.joinToString(" ").take(500), terminalId,
        )
    }

    fun previewTerminalWrite(terminalId: String, text: String, closeStdin: Boolean = false) {
        requireId(terminalId)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 64 * 1024)
        require(text.isNotEmpty() || closeStdin)
        val params = JSONObject().put("terminalId", terminalId).put("closeStdin", closeStdin)
        if (text.isNotEmpty()) params.put("deltaBase64", Base64.getEncoder().encodeToString(bytes))
        preview(HostActionKind.WRITE_TERMINAL, "Send terminal input", terminalId,
            "Sends ${bytes.size} bytes${if (closeStdin) " and closes stdin" else ""}", "host/terminal/write", params)
    }

    fun previewTerminalResize(terminalId: String, rows: Int, cols: Int) {
        requireId(terminalId)
        require(rows in 2..500 && cols in 2..500)
        preview(HostActionKind.RESIZE_TERMINAL, "Resize terminal", terminalId, "Changes terminal size to $cols × $rows",
            "host/terminal/resize", JSONObject().put("terminalId", terminalId).put("rows", rows).put("cols", cols))
    }

    fun previewTerminalKill(terminalId: String) {
        requireId(terminalId)
        previewConfirmed(
            HostActionKind.KILL_TERMINAL, "Stop command", terminalId, "Terminates this running command",
            "host/terminal/kill", JSONObject().put("terminalId", terminalId), "terminate_command", terminalId,
        )
    }

    fun dismissPreview(id: String) {
        if (pending?.preview?.id == id) { pending = null; _uiState.value = _uiState.value.copy(preview = null) }
    }

    fun confirmAction(id: String) {
        val action = pending?.takeIf { it.preview.id == id && it.revision == revision } ?: return
        val scope = _uiState.value.scope ?: return
        if (!supports(action.method)) return actionError(action, "This action is unavailable on this host")
        pending = null
        val progress = HostActionProgress(id, action.preview.kind, running = true, message = action.preview.title)
        _uiState.value = _uiState.value.copy(preview = null, actions = (_uiState.value.actions + (id to progress)).takeLastEntries(MAX_ACTIONS))
        if (action.preview.kind == HostActionKind.EXECUTE_TERMINAL && action.terminalId != null) {
            val terminal = TerminalState(action.terminalId, cwdName = action.params.optString("cwd").trimEnd('/').substringAfterLast('/'),
                tty = action.params.optBoolean("tty"))
            _uiState.value = _uiState.value.copy(terminals = (_uiState.value.terminals + (action.terminalId to terminal)).takeLastEntries(MAX_TERMINALS))
        }
        rpc(scope.serverId, action.method, JSONObject(action.params.toString())) { result, error ->
            val currentScope = _uiState.value.scope ?: return@rpc
            val serverDeviceScoped = action.method !in TASK_SCOPED_ACTIONS
            if (serverDeviceScoped) {
                if (action.serverDeviceRevision != serverDeviceRevision || currentScope.serverId != scope.serverId ||
                    currentScope.deviceId != scope.deviceId) return@rpc
            } else if (action.revision != revision || currentScope != scope) return@rpc
            if (error != null || result == null) return@rpc actionError(action, error ?: "Host action failed")
            applyResult(action, result)
            _uiState.value = _uiState.value.copy(actions = _uiState.value.actions + (id to progress.copy(
                running = false, completed = true, message = "${action.preview.title} completed",
            )))
        }
    }

    fun openExternalLink(id: String) {
        val link = _uiState.value.externalLink?.takeIf { it.id == id } ?: return
        val scope = _uiState.value.scope ?: return
        onOpenExternalUrl(scope.serverId, link.url)
    }

    fun consumeEvent(serverId: String, method: String, params: JSONObject): Boolean {
        if (_uiState.value.scope?.serverId != serverId) return false
        if (method == "account/login/completed") {
            val loginId = params.string("loginId") ?: return false
            if (_uiState.value.account.loginId != loginId) return false
            val success = params.optBoolean("success")
            _uiState.value = _uiState.value.copy(
                account = _uiState.value.account.copy(
                    loginId = null,
                    deviceUserCode = null,
                    error = if (success) null else params.string("error") ?: "Sign-in failed",
                ),
                externalLink = _uiState.value.externalLink?.takeUnless { it.kind == ExternalLinkKind.ACCOUNT_LOGIN },
            )
            if (success) refreshAccount()
            return true
        }
        if (method == "mcpServer/oauthLogin/completed") {
            val displayed = displayedMcpOauth ?: return false
            if (params.string("name") != displayed.name || params.string("threadId") != displayed.threadId ||
                _uiState.value.externalLink?.id != displayed.linkId) return false
            displayedMcpOauth = null
            _uiState.value = _uiState.value.copy(
                mcpError = if (params.optBoolean("success")) null else params.string("error") ?: "MCP authorization failed",
                externalLink = _uiState.value.externalLink?.takeUnless { it.kind == ExternalLinkKind.MCP_OAUTH },
            )
            if (params.optBoolean("success")) refreshMcp(params.string("threadId") ?: _uiState.value.scope?.taskId)
            return true
        }
        if (method != "command/exec/outputDelta") return false
        val terminalId = params.optString("processId")
        val current = _uiState.value.terminals[terminalId]?.takeIf(TerminalState::active) ?: return false
        val bytes = runCatching { Base64.getDecoder().decode(params.optString("deltaBase64")) }.getOrNull() ?: return false
        val stream = params.optString("stream")
        if (stream != "stdout" && stream != "stderr") return false
        val text = decodeTerminalChunk(terminalId, stream, bytes)
        val stdout = if (stream == "stdout") (current.stdout + text).takeLast(MAX_OUTPUT) else current.stdout
        val stderr = if (stream == "stderr") (current.stderr + text).takeLast(MAX_OUTPUT) else current.stderr
        _uiState.value = _uiState.value.copy(terminals = _uiState.value.terminals + (terminalId to current.copy(
            stdout = stdout, stderr = stderr, outputTruncated = current.outputTruncated || params.optBoolean("capReached") ||
                current.stdout.length + current.stderr.length + text.length > MAX_OUTPUT,
        )))
        return true
    }

    private fun applyResult(action: Pending, result: JSONObject) {
        when (action.preview.kind) {
            HostActionKind.START_LOGIN -> {
                val flow = result.optString("flow")
                val url = if (flow == "browser") result.string("authUrl") else result.string("verificationUrl")
                _uiState.value = _uiState.value.copy(
                    account = _uiState.value.account.copy(loginId = result.string("loginId"), deviceUserCode = result.string("userCode")),
                    externalLink = url?.let { PendingExternalLink(UUID.randomUUID().toString(), ExternalLinkKind.ACCOUNT_LOGIN, "Open Codex sign-in", it) },
                )
                displayedMcpOauth = null
            }
            HostActionKind.CANCEL_LOGIN -> _uiState.value = _uiState.value.copy(account = _uiState.value.account.copy(loginId = null, deviceUserCode = null), externalLink = null)
            HostActionKind.LOGOUT -> _uiState.value = _uiState.value.copy(account = AccountState(), externalLink = null)
            HostActionKind.START_MCP_OAUTH -> result.string("authorizationUrl")?.let { url ->
                val link = PendingExternalLink(UUID.randomUUID().toString(), ExternalLinkKind.MCP_OAUTH, "Open MCP authorization", url)
                displayedMcpOauth = DisplayedMcpOauth(link.id, action.params.optString("name"), action.params.string("threadId"))
                _uiState.value = _uiState.value.copy(externalLink = link)
            }
            HostActionKind.CHANGE_SETTING -> applySetting(result.optString("setting"), result.string("value"))
            HostActionKind.INSTALL_PLUGIN -> {
                val name = action.params.optString("pluginName")
                _uiState.value = _uiState.value.copy(plugins = _uiState.value.plugins.map { plugin ->
                    if (plugin.name == name) plugin.copy(installed = true) else plugin
                })
            }
            HostActionKind.UNINSTALL_PLUGIN -> {
                val id = action.params.optString("pluginId")
                _uiState.value = _uiState.value.copy(plugins = _uiState.value.plugins.map { plugin ->
                    if (plugin.id == id) plugin.copy(installed = false, enabled = false) else plugin
                })
            }
            HostActionKind.SET_SKILL -> result.string("name")?.let { name ->
                _uiState.value = _uiState.value.copy(skillEnabled = _uiState.value.skillEnabled + (name to result.optBoolean("effectiveEnabled")))
            }
            HostActionKind.SET_MEMORY_MODE -> _uiState.value = _uiState.value.copy(memoryMode = MemoryMode.entries.firstOrNull { it.wireValue == result.optString("mode") })
            HostActionKind.EXECUTE_TERMINAL -> action.terminalId?.let { id ->
                val old = _uiState.value.terminals[id] ?: TerminalState(id)
                val stdoutTail = flushTerminalDecoder(id, "stdout")
                val stderrTail = flushTerminalDecoder(id, "stderr")
                _uiState.value = _uiState.value.copy(terminals = _uiState.value.terminals + (id to old.copy(
                    active = false, exitCode = result.optInt("exitCode").takeIf { result.has("exitCode") && !result.isNull("exitCode") },
                    stdout = if (old.stdout.isNotEmpty() || stdoutTail.isNotEmpty()) (old.stdout + stdoutTail).takeLast(MAX_OUTPUT) else result.optString("stdout").takeLast(MAX_OUTPUT),
                    stderr = if (old.stderr.isNotEmpty() || stderrTail.isNotEmpty()) (old.stderr + stderrTail).takeLast(MAX_OUTPUT) else result.optString("stderr").takeLast(MAX_OUTPUT),
                )))
            }
            HostActionKind.KILL_TERMINAL -> action.params.string("terminalId")?.let { id ->
                terminalUtf8Carry.keys.removeAll { it.startsWith("$id\u0000") }
                _uiState.value.terminals[id]?.let { terminal ->
                    _uiState.value = _uiState.value.copy(terminals = _uiState.value.terminals + (id to terminal.copy(active = false)))
                }
            }
            else -> Unit
        }
    }

    private fun applySetting(setting: String, value: String?) {
        val old = _uiState.value.settings
        val next = when (setting) {
            "web_search" -> old.copy(webSearch = value)
            "model_verbosity" -> old.copy(modelVerbosity = value)
            "model_reasoning_summary" -> old.copy(reasoningSummary = value)
            else -> old
        }
        _uiState.value = _uiState.value.copy(settings = next)
    }

    private fun previewConfirmed(kind: HostActionKind, title: String, subject: String, consequence: String,
        method: String, params: JSONObject, confirmationAction: String, confirmationSubject: String, terminalId: String? = null) {
        params.put("confirmation", JSONObject().put("action", confirmationAction).put("subject", confirmationSubject))
        preview(kind, title, subject, consequence, method, params, terminalId)
    }

    private fun preview(kind: HostActionKind, title: String, subject: String, consequence: String,
        method: String, params: JSONObject, terminalId: String? = null) {
        require(method in EXACT_METHODS) { "Unsupported host action" }
        val item = HostActionPreview(UUID.randomUUID().toString(), kind, title, subject, consequence)
        pending = Pending(item, revision, serverDeviceRevision, method, JSONObject(params.toString()), terminalId)
        _uiState.value = _uiState.value.copy(preview = item)
    }

    private fun read(method: String, params: JSONObject = JSONObject(), apply: (JSONObject) -> Unit, onStart: () -> Unit = {}) {
        val scope = _uiState.value.scope ?: return
        if (!supports(method)) return
        val captured = revision
        val capturedServerDevice = serverDeviceRevision
        val request = ++readSequence
        activeReads[method] = request
        onStart()
        rpc(scope.serverId, method, params) { result, error ->
            if (activeReads[method] != request || capturedServerDevice != serverDeviceRevision) return@rpc
            val currentScope = _uiState.value.scope ?: return@rpc
            val serverScoped = method != "host/mcp/status"
            if (currentScope.serverId != scope.serverId || currentScope.deviceId != scope.deviceId ||
                !serverScoped && (captured != revision || currentScope != scope)) return@rpc
            if (error != null || result == null) {
                when (method) {
                    "host/account/status" -> _uiState.value = _uiState.value.copy(account = _uiState.value.account.copy(loading = false, error = error ?: "Account request failed"))
                    "host/mcp/status" -> _uiState.value = _uiState.value.copy(mcpLoading = false, mcpError = error ?: "MCP request failed")
                    "host/plugin/catalog" -> _uiState.value = _uiState.value.copy(pluginsLoading = false, pluginsError = error ?: "Plugin request failed")
                    "host/settings/read" -> _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(loading = false, error = error ?: "Settings request failed"))
                }
            } else apply(result)
        }
    }

    private fun actionError(action: Pending, message: String) {
        val old = _uiState.value.actions[action.preview.id] ?: HostActionProgress(action.preview.id, action.preview.kind)
        action.terminalId?.let { id -> _uiState.value.terminals[id]?.let { terminal ->
            terminalUtf8Carry.keys.removeAll { it.startsWith("$id\u0000") }
            _uiState.value = _uiState.value.copy(terminals = _uiState.value.terminals + (id to terminal.copy(active = false, error = message)))
        } }
        _uiState.value = _uiState.value.copy(actions = (_uiState.value.actions + (action.preview.id to old.copy(
            running = false, completed = false, error = message,
        ))).takeLastEntries(MAX_ACTIONS))
    }

    private fun decodeTerminalChunk(terminalId: String, stream: String, bytes: ByteArray): String {
        val key = "$terminalId\u0000$stream"
        val previous = terminalUtf8Carry.remove(key) ?: byteArrayOf()
        val combined = previous + bytes
        val carryStart = incompleteUtf8SuffixStart(combined)
        if (carryStart < combined.size) terminalUtf8Carry[key] = combined.copyOfRange(carryStart, combined.size)
        return combined.copyOfRange(0, carryStart).toString(StandardCharsets.UTF_8)
    }

    private fun flushTerminalDecoder(terminalId: String, stream: String): String =
        terminalUtf8Carry.remove("$terminalId\u0000$stream")?.toString(StandardCharsets.UTF_8).orEmpty()

    private fun incompleteUtf8SuffixStart(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var lead = bytes.lastIndex
        var continuationCount = 0
        while (lead >= 0 && bytes[lead].toInt() and 0xC0 == 0x80 && continuationCount < 3) {
            lead--
            continuationCount++
        }
        if (lead < 0) return bytes.size
        val first = bytes[lead].toInt() and 0xFF
        val expected = when {
            first and 0x80 == 0 -> 1
            first and 0xE0 == 0xC0 -> 2
            first and 0xF0 == 0xE0 -> 3
            first and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (expected > bytes.size - lead) lead else bytes.size
    }

    private fun JSONObject.string(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }
    private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
    private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
    private fun <K, V> Map<K, V>.takeLastEntries(limit: Int) = entries.toList().takeLast(limit).associate { it.toPair() }
    private fun requireSimple(value: String, max: Int) = require(value.isNotBlank() && value.length <= max && value.none { it in "\u0000\r\n" })
    private fun requireId(value: String) = require(value.length in 1..256 && value.all { it.isLetterOrDigit() || it in "._:-" })

    companion object {
        val EXACT_METHODS = setOf(
            "host/account/status", "host/account/login/start", "host/account/login/cancel", "host/account/logout",
            "host/mcp/status", "host/mcp/oauth/start", "host/mcp/reload", "host/plugin/catalog", "host/plugin/install",
            "host/plugin/uninstall", "host/settings/read", "host/settings/set", "host/skill/setEnabled",
            "host/thread/memoryMode/set", "host/terminal/execute", "host/terminal/write", "host/terminal/resize",
            "host/terminal/kill", "host/terminal/list",
        )
        val SETTING_VALUES = mapOf(
            NamedSetting.WEB_SEARCH to setOf("disabled", "cached", "indexed", "live"),
            NamedSetting.MODEL_VERBOSITY to setOf("low", "medium", "high"),
            NamedSetting.MODEL_REASONING_SUMMARY to setOf("auto", "concise", "detailed", "none"),
        )
        private const val MAX_OUTPUT = 128 * 1024
        private const val MAX_TERMINALS = 20
        private const val MAX_ACTIONS = 30
        private val TASK_SCOPED_ACTIONS = setOf("host/mcp/oauth/start", "host/thread/memoryMode/set")
    }
}
