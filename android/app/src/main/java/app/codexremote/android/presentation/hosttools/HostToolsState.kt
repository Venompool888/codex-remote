package app.codexremote.android.presentation.hosttools

import androidx.compose.runtime.Immutable

@Immutable
data class HostToolsScope(
    val serverId: String,
    val deviceId: String,
    val workspaceCwd: String,
    val taskId: String? = null,
)

enum class LoginFlow { BROWSER, DEVICE }
enum class NamedSetting(val wireName: String) {
    WEB_SEARCH("web_search"), MODEL_VERBOSITY("model_verbosity"), MODEL_REASONING_SUMMARY("model_reasoning_summary")
}
enum class MemoryMode(val wireValue: String) { ENABLED("enabled"), DISABLED("disabled") }

@Immutable
data class AccountState(
    val loading: Boolean = false,
    val authenticated: Boolean = false,
    val ready: Boolean = false,
    val requiresOpenAiAuth: Boolean = false,
    val authMode: String? = null,
    val email: String? = null,
    val planType: String? = null,
    val credentialSource: String? = null,
    val loginId: String? = null,
    val deviceUserCode: String? = null,
    val error: String? = null,
)

@Immutable
data class McpServerState(val name: String, val title: String?, val version: String?, val authStatus: String, val toolCount: Int)

@Immutable
data class PluginState(
    val id: String,
    val name: String,
    val version: String?,
    val localVersion: String?,
    val installed: Boolean,
    val enabled: Boolean,
    val authPolicy: String,
    val availability: String?,
    val marketplace: String,
)

@Immutable
data class NamedSettingsState(
    val webSearch: String? = null,
    val modelVerbosity: String? = null,
    val reasoningSummary: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@Immutable
data class TerminalState(
    val terminalId: String,
    val cwdName: String = "",
    val tty: Boolean = false,
    val startedAt: String? = null,
    val active: Boolean = true,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val outputTruncated: Boolean = false,
    val error: String? = null,
)

enum class HostActionKind {
    START_LOGIN, CANCEL_LOGIN, LOGOUT, START_MCP_OAUTH, RELOAD_MCP, INSTALL_PLUGIN, UNINSTALL_PLUGIN,
    CHANGE_SETTING, SET_SKILL, SET_MEMORY_MODE, EXECUTE_TERMINAL, WRITE_TERMINAL, RESIZE_TERMINAL, KILL_TERMINAL,
}

@Immutable
data class HostActionPreview(
    val id: String,
    val kind: HostActionKind,
    val title: String,
    val subject: String,
    val consequence: String,
)

@Immutable
data class HostActionProgress(
    val id: String,
    val kind: HostActionKind,
    val running: Boolean = false,
    val completed: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

enum class ExternalLinkKind { ACCOUNT_LOGIN, MCP_OAUTH }

@Immutable
data class PendingExternalLink(val id: String, val kind: ExternalLinkKind, val label: String, val url: String)

@Immutable
data class HostToolsUiState(
    val scope: HostToolsScope? = null,
    val supportedMethods: Set<String> = emptySet(),
    val account: AccountState = AccountState(),
    val mcpServers: List<McpServerState> = emptyList(),
    val mcpLoading: Boolean = false,
    val mcpError: String? = null,
    val plugins: List<PluginState> = emptyList(),
    val pluginsLoading: Boolean = false,
    val pluginsError: String? = null,
    val settings: NamedSettingsState = NamedSettingsState(),
    val skillEnabled: Map<String, Boolean> = emptyMap(),
    val memoryMode: MemoryMode? = null,
    val terminals: Map<String, TerminalState> = emptyMap(),
    val preview: HostActionPreview? = null,
    val actions: Map<String, HostActionProgress> = emptyMap(),
    val externalLink: PendingExternalLink? = null,
)

