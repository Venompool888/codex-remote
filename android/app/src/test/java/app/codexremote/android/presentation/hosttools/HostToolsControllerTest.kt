package app.codexremote.android.presentation.hosttools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class HostToolsControllerTest {
    private data class Call(
        val server: String,
        val method: String,
        val params: JSONObject,
        val done: (JSONObject?, String?) -> Unit,
    )
    private val calls = mutableListOf<Call>()
    private val opened = mutableListOf<Pair<String, String>>()
    private fun controller() = HostToolsController(
        rpc = { server, method, params, done -> calls += Call(server, method, params, done) },
        onOpenExternalUrl = { server, url -> opened += server to url },
    )
    private val scope = HostToolsScope("server-a", "device-a", "remote-workspace://workspace-a", "thread-a")

    @Test fun capabilitySetIsExactAndPreviewDoesNotExecute() {
        val controller = controller()
        controller.setScope(scope, setOf("host/account/logout", "host/arbitrary/admin"))
        assertEquals(setOf("host/account/logout"), controller.uiState.value.supportedMethods)

        controller.previewLogout()

        assertTrue(calls.isEmpty())
        val preview = controller.uiState.value.preview!!
        assertEquals("Codex account", preview.subject)
        assertTrue(preview.consequence.contains("Signs the host out"))
        controller.confirmAction(preview.id)
        val call = calls.single()
        assertEquals("server-a", call.server)
        assertEquals("host/account/logout", call.method)
        assertEquals("logout_account", call.params.getJSONObject("confirmation").getString("action"))
    }

    @Test fun lateResultsCannotCrossServerDeviceWorkspaceOrTaskScope() {
        val controller = controller()
        controller.setScope(scope, setOf("host/settings/read"))
        controller.refreshSettings()
        val old = calls.single()

        controller.setScope(HostToolsScope("server-b", "device-b", "remote-workspace://workspace-b", "thread-b"),
            setOf("host/settings/read"))
        old.done(JSONObject().put("settings", JSONObject().put("web_search", "live")), null)

        assertEquals("server-b", controller.uiState.value.scope?.serverId)
        assertNull(controller.uiState.value.settings.webSearch)
    }

    @Test fun browserAndOauthLinksOpenOnlyOnExplicitTap() {
        val controller = controller()
        controller.setScope(scope, setOf("host/account/login/start", "host/mcp/oauth/start"))
        controller.previewLogin(LoginFlow.BROWSER)
        val loginPreview = controller.uiState.value.preview!!
        controller.confirmAction(loginPreview.id)
        assertTrue(opened.isEmpty())
        calls.removeAt(0).done(JSONObject()
            .put("flow", "browser").put("loginId", "login-1").put("authUrl", "https://auth.example/start"), null)
        assertTrue(opened.isEmpty())
        val loginLink = controller.uiState.value.externalLink!!
        controller.openExternalLink(loginLink.id)
        assertEquals(listOf("server-a" to "https://auth.example/start"), opened)

        controller.previewMcpOauth("github", listOf("repo"))
        val oauthPreview = controller.uiState.value.preview!!
        controller.confirmAction(oauthPreview.id)
        val oauthCall = calls.single()
        assertEquals("start_mcp_oauth", oauthCall.params.getJSONObject("confirmation").getString("action"))
        assertEquals("thread-a", oauthCall.params.getString("threadId"))
        oauthCall.done(JSONObject().put("authorizationUrl", "https://mcp.example/oauth"), null)
        assertEquals(1, opened.size)
    }

    @Test fun mcpCompletionOnlyClearsItsMatchingDisplayedLink() {
        val controller = controller()
        controller.setScope(scope, setOf("host/mcp/oauth/start"))
        controller.previewMcpOauth("github")
        controller.confirmAction(controller.uiState.value.preview!!.id)
        calls.removeAt(0).done(JSONObject().put("authorizationUrl", "https://mcp.example/github"), null)

        controller.previewMcpOauth("linear")
        controller.confirmAction(controller.uiState.value.preview!!.id)
        calls.removeAt(0).done(JSONObject().put("authorizationUrl", "https://mcp.example/linear"), null)
        val linearLink = controller.uiState.value.externalLink

        assertFalse(controller.consumeEvent("server-a", "mcpServer/oauthLogin/completed", JSONObject()
            .put("name", "github").put("threadId", "thread-a").put("success", true)))
        assertEquals(linearLink, controller.uiState.value.externalLink)
        assertTrue(controller.consumeEvent("server-a", "mcpServer/oauthLogin/completed", JSONObject()
            .put("name", "linear").put("threadId", "thread-a").put("success", true)))
        assertNull(controller.uiState.value.externalLink)
    }

    @Test fun loginSurvivesTaskScopeChangeAndCompletionRefreshesAccount() {
        val controller = controller()
        controller.setScope(scope, setOf("host/account/login/start", "host/account/status"))
        controller.previewLogin(LoginFlow.DEVICE)
        controller.confirmAction(controller.uiState.value.preview!!.id)
        val login = calls.removeAt(0)

        controller.setScope(scope.copy(workspaceCwd = "", taskId = null), setOf("host/account/login/start", "host/account/status"))
        login.done(JSONObject().put("flow", "device").put("loginId", "login-1")
            .put("verificationUrl", "https://auth.example/device").put("userCode", "ABCD"), null)
        assertEquals("login-1", controller.uiState.value.account.loginId)

        controller.refreshAccount()
        val staleStatus = calls.removeAt(0)
        staleStatus.done(JSONObject().put("authenticated", false).put("ready", false).put("requiresOpenaiAuth", true), null)
        assertEquals("login-1", controller.uiState.value.account.loginId)
        assertEquals("ABCD", controller.uiState.value.account.deviceUserCode)

        assertTrue(controller.consumeEvent("server-a", "account/login/completed", JSONObject()
            .put("loginId", "login-1").put("success", true)))
        assertNull(controller.uiState.value.account.loginId)
        assertNull(controller.uiState.value.externalLink)
        val refreshed = calls.single()
        assertEquals("host/account/status", refreshed.method)
        refreshed.done(JSONObject().put("authenticated", true).put("ready", true).put("requiresOpenaiAuth", true)
            .put("email", "owner@example.com"), null)
        assertTrue(controller.uiState.value.account.authenticated)
    }

    @Test fun newerSameScopeReadWins() {
        val controller = controller()
        controller.setScope(scope, setOf("host/account/status"))
        controller.refreshAccount()
        controller.refreshAccount()
        val older = calls.removeAt(0)
        val newer = calls.removeAt(0)
        newer.done(JSONObject().put("authenticated", true).put("ready", true).put("email", "new@example.com"), null)
        older.done(JSONObject().put("authenticated", false).put("ready", false).put("email", "old@example.com"), null)
        assertEquals("new@example.com", controller.uiState.value.account.email)
        assertTrue(controller.uiState.value.account.authenticated)
    }

    @Test fun namedSettingsExposeNoArbitraryKeyPath() {
        val controller = controller()
        controller.setScope(scope, setOf("host/settings/set"))
        try {
            controller.previewSetting(NamedSetting.WEB_SEARCH, "force-login")
            fail("Expected closed enum rejection")
        } catch (_: IllegalArgumentException) {}
        assertTrue(calls.isEmpty())

        controller.previewSetting(NamedSetting.WEB_SEARCH, "live")
        val preview = controller.uiState.value.preview!!
        controller.confirmAction(preview.id)
        assertEquals(setOf("setting", "value", "confirmation"), calls.single().params.keys().asSequence().toSet())
        assertEquals("web_search", calls.single().params.getString("setting"))
    }

    @Test fun shellExecutionUsesExactShellArgvAndClientOwnedTerminalId() {
        val controller = controller()
        controller.setScope(scope, setOf("host/terminal/execute", "host/terminal/write", "host/terminal/kill"))
        controller.previewExecuteShell("printf hello", scope.workspaceCwd, tty = true, rows = 30, cols = 100)
        assertTrue(calls.isEmpty())
        val preview = controller.uiState.value.preview!!
        assertEquals("printf hello", preview.subject)
        controller.confirmAction(preview.id)

        val call = calls.single()
        assertEquals(listOf("/bin/sh", "-lc", "printf hello"), call.params.getJSONArray("argv").strings())
        val terminalId = call.params.getString("terminalId")
        assertTrue(terminalId.matches(Regex("[0-9a-f-]{36}")))
        assertEquals("/bin/sh -lc printf hello", call.params.getJSONObject("confirmation").getString("subject"))
        assertTrue(controller.uiState.value.terminals.getValue(terminalId).active)

        val delta = Base64.getEncoder().encodeToString("hello".toByteArray())
        assertFalse(controller.consumeEvent("server-b", "command/exec/outputDelta", JSONObject()
            .put("processId", terminalId).put("stream", "stdout").put("deltaBase64", delta)))
        assertTrue(controller.consumeEvent("server-a", "command/exec/outputDelta", JSONObject()
            .put("processId", terminalId).put("stream", "stdout").put("deltaBase64", delta)))
        assertEquals("hello", controller.uiState.value.terminals.getValue(terminalId).stdout)
        call.done(JSONObject().put("terminalId", terminalId).put("exitCode", 0).put("stdout", "hello").put("stderr", ""), null)
        assertFalse(controller.uiState.value.terminals.getValue(terminalId).active)
        assertEquals("hello", controller.uiState.value.terminals.getValue(terminalId).stdout)
    }

    @Test fun terminalUtf8SplitAcrossChunksIsDecodedOnceComplete() {
        val controller = controller()
        controller.setScope(scope, setOf("host/terminal/execute"))
        controller.previewExecuteArgv(listOf("printf", "€"), scope.workspaceCwd)
        controller.confirmAction(controller.uiState.value.preview!!.id)
        val call = calls.single()
        val terminalId = call.params.getString("terminalId")
        val euro = "€".toByteArray()

        assertTrue(controller.consumeEvent("server-a", "command/exec/outputDelta", JSONObject()
            .put("processId", terminalId).put("stream", "stdout")
            .put("deltaBase64", Base64.getEncoder().encodeToString(euro.copyOfRange(0, 2)))))
        assertEquals("", controller.uiState.value.terminals.getValue(terminalId).stdout)
        assertTrue(controller.consumeEvent("server-a", "command/exec/outputDelta", JSONObject()
            .put("processId", terminalId).put("stream", "stdout")
            .put("deltaBase64", Base64.getEncoder().encodeToString(euro.copyOfRange(2, 3)))))
        assertEquals("€", controller.uiState.value.terminals.getValue(terminalId).stdout)

        controller.setScope(scope.copy(workspaceCwd = "", taskId = null), setOf("host/terminal/execute"))
        call.done(JSONObject().put("terminalId", terminalId).put("exitCode", 0).put("stdout", "").put("stderr", ""), null)
        assertFalse(controller.uiState.value.terminals.getValue(terminalId).active)
        assertEquals("€", controller.uiState.value.terminals.getValue(terminalId).stdout)
    }

    @Test fun terminalInputAndResizeAlsoRequirePreviewConfirmation() {
        val controller = controller()
        controller.setScope(scope, setOf("host/terminal/write", "host/terminal/resize"))
        controller.previewTerminalWrite("term-1", "yes\n", closeStdin = true)
        assertTrue(calls.isEmpty())
        controller.confirmAction(controller.uiState.value.preview!!.id)
        assertEquals("yes\n", String(Base64.getDecoder().decode(calls.removeAt(0).params.getString("deltaBase64"))))

        controller.previewTerminalResize("term-1", 40, 120)
        assertTrue(calls.isEmpty())
        controller.confirmAction(controller.uiState.value.preview!!.id)
        assertEquals(40, calls.single().params.getInt("rows"))
        assertEquals(120, calls.single().params.getInt("cols"))
    }

    @Test fun typedCatalogAndMcpResultsAreProjectedWithoutUnknownFields() {
        val controller = controller()
        controller.setScope(scope, setOf("host/plugin/catalog", "host/mcp/status"))
        controller.refreshPlugins()
        calls.removeAt(0).done(JSONObject().put("marketplaces", JSONArray().put(JSONObject()
            .put("name", "official").put("plugins", JSONArray().put(JSONObject()
                .put("id", "plugin-1").put("name", "Plugin").put("installed", true).put("enabled", true)
                .put("authPolicy", "ON_USE").put("privatePath", "/private/plugin"))))), null)
        assertEquals("plugin-1", controller.uiState.value.plugins.single().id)
        assertEquals("official", controller.uiState.value.plugins.single().marketplace)

        controller.refreshMcp()
        calls.single().done(JSONObject().put("servers", JSONArray().put(JSONObject()
            .put("name", "github").put("authStatus", "oAuth").put("toolCount", 4).put("secret", "hidden"))), null)
        assertEquals(McpServerState("github", null, null, "oAuth", 4), controller.uiState.value.mcpServers.single())
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
}
