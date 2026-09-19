package app.codexremote.android

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DeviceCredential(
    val token: String,
    val deviceId: String? = null,
    val expiresAt: String? = null,
    val scopes: Set<String> = emptySet(),
)

// Paired hosts browse directories directly by default. Opaque routing remains an
// explicit opt-in for deployments that must hide server paths from the client.
class RemoteClient(private val listener: Listener, enableOpaqueWorkspaceRouting: Boolean = false) {
    private val workspaceRouting = WorkspaceRoutingNegotiation(enableOpaqueWorkspaceRouting)
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(message: JSONObject)
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var generation = 0L
    @Volatile
    var negotiatedConnectionEpoch: Long = -1L
        private set
    private var lastSequence = -1L
    private var lastSessionId: String? = null
    private var offeredSessionId: String? = null
    private val pendingAnswers = linkedMapOf<String, JSONObject>()
    private val pendingWriteRpcs = linkedMapOf<String, JSONObject>()
    @Volatile
    var negotiatedProtocolVersion = 0
        private set
    @Volatile
    private var negotiatedCapabilities: JSONObject? = null
    @Volatile
    var opaqueWorkspaceRouting: Boolean = false
        private set

    fun supportsRpcMethod(method: String): Boolean {
        val methods = negotiatedCapabilities?.optJSONArray("rpcMethods") ?: return true
        return (0 until methods.length()).any { methods.optString(it) == method }
    }

    /** New feature panels require explicit advertisement, unlike legacy RPC fallback. */
    fun advertisedRpcMethods(): Set<String> {
        val methods = negotiatedCapabilities?.optJSONArray("rpcMethods") ?: return emptySet()
        return (0 until methods.length()).mapNotNull { methods.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    fun supportsExperimentalPluginList(): Boolean = negotiatedCapabilities
        ?.optJSONObject("plugins")
        ?.optBoolean("experimentalList", false)
        ?: true

    fun supportsInstalledApps(): Boolean = negotiatedCapabilities
        ?.optJSONObject("plugins")
        ?.optBoolean("installedApps", false)
        ?: false

    fun diagnostics(): String = "Remote protocol: $negotiatedProtocolVersion\n" +
        "Private workspace routing: ${if (opaqueWorkspaceRouting) "enabled" else "legacy compatibility"}\n" +
        (negotiatedCapabilities?.toString(2) ?: "Legacy host: capability negotiation is unavailable; durable attachments and artifact downloads are disabled.")

    fun diagnosticsSummary(): String {
        if (negotiatedProtocolVersion == 0) return "Waiting for the host to negotiate supported features."
        val capabilities = negotiatedCapabilities
            ?: return "Remote protocol: $negotiatedProtocolVersion\n\nLegacy host: detailed feature negotiation is unavailable. Durable attachments and artifact downloads are disabled. Update the host to enable them."
        fun feature(group: String, key: String): String {
            val section = capabilities.optJSONObject(group)
            return when {
                section?.has(key) != true -> "Not advertised"
                section.optBoolean(key) -> "Supported"
                else -> "Unavailable"
            }
        }
        val methods = capabilities.optJSONArray("rpcMethods")
        val artifactSupport = if (methods == null) "Not advertised" else
            if ((0 until methods.length()).any { methods.optString(it) == "host/artifacts/list" }) "Supported" else "Unavailable"
        return listOf(
            "Remote protocol: $negotiatedProtocolVersion",
            "Private workspace routing: ${if (opaqueWorkspaceRouting) "Enabled" else "Legacy compatibility"}",
            "Resumable attachments: ${feature("attachments", "chunkedHttp")}",
            "Artifact downloads: $artifactSupport",
            "Event replay: ${feature("events", "replay")}",
            "Agent questions: ${feature("interactions", "userInput")}",
            "MCP forms: ${feature("interactions", "elicitation")}",
            "Approvals: ${feature("interactions", "approvals")}",
            "Skill discovery: ${feature("skills", "list")}",
            "Plugin discovery: ${feature("plugins", "experimentalList")}",
            "Credential rotation: ${feature("auth", "rotation")}",
            "Device revocation: ${feature("auth", "selfRevocation")}",
        ).joinToString("\n\n")
    }

    fun supportsInteractionAcknowledgements(): Boolean = negotiatedCapabilities?.optJSONObject("interactions")?.optBoolean("acknowledgement") == true

    fun supportsAttachmentPreviews(): Boolean = negotiatedProtocolVersion >= 2 && negotiatedCapabilities
        ?.optJSONObject("attachments")?.optBoolean("restrictedImagePreview") == true

    fun supportsChunkedAttachments(): Boolean = negotiatedProtocolVersion >= 2 && negotiatedCapabilities
        ?.optJSONObject("attachments")
        ?.optBoolean("chunkedHttp", false)
        ?: false

    fun uploadAttachment(
        serverUrl: String,
        token: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit,
        callback: (Result<JSONObject>) -> Unit,
    ) {
        val wholeSha = sha256(bytes)
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", mimeType)
            .put("size", bytes.size)
            .put("sha256", wholeSha)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${normalizeHttp(serverUrl)}/v2/attachments")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(Result.failure(e))

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) return callback(Result.failure(httpFailure("Attachment setup", it.code, text)))
                    runCatching {
                        val value = JSONObject(text)
                        val id = value.getJSONObject("attachment").getString("id")
                        val chunkBytes = value.optInt("chunkBytes", 1024 * 1024).coerceAtLeast(1)
                        uploadAttachmentChunk(serverUrl, token, id, bytes, 0, chunkBytes, onProgress, callback)
                    }.onFailure { callback(Result.failure(it)) }
                }
            }
        })
    }

    private fun uploadAttachmentChunk(
        serverUrl: String,
        token: String,
        id: String,
        bytes: ByteArray,
        offset: Int,
        chunkBytes: Int,
        onProgress: (Int) -> Unit,
        callback: (Result<JSONObject>) -> Unit,
        recoveryAttempts: Int = 0,
    ) {
        if (offset >= bytes.size) {
            val request = Request.Builder()
                .url("${normalizeHttp(serverUrl)}/v2/attachments/$id/complete")
                .header("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) = recoverAttachmentUpload(
                    serverUrl, token, id, bytes, chunkBytes, onProgress, callback, recoveryAttempts, e,
                )
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (!it.isSuccessful && it.code >= 500) recoverAttachmentUpload(
                            serverUrl, token, id, bytes, chunkBytes, onProgress, callback, recoveryAttempts,
                            httpFailure("Attachment verification", it.code, text),
                        )
                        else if (!it.isSuccessful) callback(Result.failure(httpFailure("Attachment verification", it.code, text)))
                        else callback(runCatching { JSONObject(text).getJSONObject("attachment") })
                    }
                }
            })
            return
        }
        val endExclusive = minOf(offset + chunkBytes, bytes.size)
        val chunk = bytes.copyOfRange(offset, endExclusive)
        val request = Request.Builder()
            .url("${normalizeHttp(serverUrl)}/v2/attachments/$id")
            .header("Authorization", "Bearer $token")
            .header("Content-Range", "bytes $offset-${endExclusive - 1}/${bytes.size}")
            .header("X-Chunk-SHA256", sha256(chunk))
            .put(chunk.toRequestBody(null))
            .build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = recoverAttachmentUpload(
                serverUrl, token, id, bytes, chunkBytes, onProgress, callback, recoveryAttempts, e,
            )
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val failure = httpFailure("Attachment upload", it.code, text)
                        if (it.code == 409 || it.code >= 500) recoverAttachmentUpload(
                            serverUrl, token, id, bytes, chunkBytes, onProgress, callback, recoveryAttempts, failure,
                        ) else callback(Result.failure(failure))
                        return
                    }
                    onProgress((endExclusive * 100 / bytes.size).coerceIn(0, 100))
                    uploadAttachmentChunk(serverUrl, token, id, bytes, endExclusive, chunkBytes, onProgress, callback, 0)
                }
            }
        })
    }

    private fun recoverAttachmentUpload(
        serverUrl: String,
        token: String,
        id: String,
        bytes: ByteArray,
        chunkBytes: Int,
        onProgress: (Int) -> Unit,
        callback: (Result<JSONObject>) -> Unit,
        recoveryAttempts: Int,
        originalFailure: Throwable,
    ) {
        if (recoveryAttempts >= MAX_ATTACHMENT_RECOVERY_ATTEMPTS) {
            callback(Result.failure(originalFailure))
            return
        }
        val request = Request.Builder()
            .url("${normalizeHttp(serverUrl)}/v2/attachments/$id")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(Result.failure(originalFailure))
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        callback(Result.failure(originalFailure))
                        return
                    }
                    runCatching { JSONObject(text).getJSONObject("attachment") }
                        .onFailure { callback(Result.failure(originalFailure)) }
                        .onSuccess { attachment ->
                            if (attachment.optString("status") == "complete") {
                                callback(Result.success(attachment))
                                return@onSuccess
                            }
                            val offset = attachment.optInt("offset", -1)
                            if (offset !in 0..bytes.size) {
                                callback(Result.failure(IOException("Remote Host returned an invalid attachment offset")))
                                return@onSuccess
                            }
                            onProgress((offset * 100 / bytes.size).coerceIn(0, 100))
                            uploadAttachmentChunk(
                                serverUrl, token, id, bytes, offset, chunkBytes, onProgress, callback,
                                recoveryAttempts + 1,
                            )
                        }
                }
            }
        })
    }

    fun pair(serverUrl: String, code: String, deviceName: String, callback: (Result<DeviceCredential>) -> Unit) {
        val body = JSONObject()
            .put("code", code.trim())
            .put("deviceName", deviceName.trim())
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        pairAt(serverUrl, body, 2, callback)
    }

    private fun pairAt(
        serverUrl: String,
        body: okhttp3.RequestBody,
        protocolVersion: Int,
        callback: (Result<DeviceCredential>) -> Unit,
    ) {
        val request = Request.Builder()
            .url("${normalizeHttp(serverUrl)}/v$protocolVersion/pair")
            .post(body)
            .build()
        http.newCall(request).apply { timeout().timeout(30, TimeUnit.SECONDS) }.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(Result.failure(e))

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    if (protocolVersion == 2 && it.code == 404) {
                        pairAt(serverUrl, body, 1, callback)
                        return
                    }
                    if (!it.isSuccessful) {
                        val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        callback(Result.failure(IOException(message?.ifBlank { null } ?: "Pairing failed: HTTP ${it.code}")))
                        return
                    }
                    callback(runCatching { parseCredential(JSONObject(text)) })
                }
            }
        })
    }

    fun rotateCredential(serverUrl: String, token: String, callback: (Result<DeviceCredential>) -> Unit) {
        authenticatedCredentialRequest(serverUrl, token, "POST") { code, text, failure ->
            if (failure != null) {
                Result.failure<DeviceCredential>(failure)
            } else if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                Result.failure(IOException(detail?.ifBlank { null } ?: "Credential rotation failed: HTTP $code"))
            } else {
                runCatching { parseCredential(JSONObject(text)) }
            }.let(callback)
        }
    }

    fun revokeCredential(serverUrl: String, token: String, callback: (Result<Unit>) -> Unit) {
        authenticatedCredentialRequest(serverUrl, token, "DELETE") { code, text, failure ->
            if (failure != null) {
                callback(Result.failure(failure))
            } else if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
                callback(Result.failure(IOException(detail?.ifBlank { null } ?: "Credential revocation failed: HTTP $code")))
            } else {
                callback(Result.success(Unit))
            }
        }
    }

    private fun authenticatedCredentialRequest(
        serverUrl: String,
        token: String,
        method: String,
        callback: (code: Int, body: String, failure: IOException?) -> Unit,
    ) {
        val builder = Request.Builder()
            .url("${normalizeHttp(serverUrl)}/v2/device${if (method == "POST") "/rotate" else ""}")
            .header("Authorization", "Bearer $token")
        val request = if (method == "POST") {
            builder.post(ByteArray(0).toRequestBody(null)).build()
        } else {
            builder.delete().build()
        }
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = callback(0, "", e)

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { callback(it.code, it.body?.string().orEmpty(), null) }
            }
        })
    }

    @Synchronized
    fun connect(serverUrl: String, token: String) {
        closeSocket(clearPending = false)
        negotiatedProtocolVersion = 0
        negotiatedCapabilities = null
        opaqueWorkspaceRouting = false
        val attempt = ++generation
        connectAt(serverUrl, token, protocolVersion = 2, attempt = attempt)
    }

    private fun connectAt(serverUrl: String, token: String, protocolVersion: Int, attempt: Long) {
        val request = Request.Builder()
            .url("${normalizeWebSocket(serverUrl)}/v$protocolVersion/ws")
            .header("Authorization", "Bearer $token")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(this@RemoteClient) {
                if (attempt == generation && protocolVersion == 1) {
                    try { workspaceRouting.legacy() } catch (error: IllegalStateException) {
                        webSocket.close(1002, "Workspace routing downgrade rejected")
                        listener.onDisconnected(error.message ?: "Workspace routing unavailable")
                        return
                    }
                    negotiatedProtocolVersion = 1
                    negotiatedConnectionEpoch = attempt
                    Log.i(LOG_TAG, "Connected with Remote protocol v1 compatibility mode")
                    listener.onConnected()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(this@RemoteClient) {
                if (attempt != generation) return
                runCatching { JSONObject(text) }
                    .onSuccess { message ->
                        when {
                            protocolVersion == 2 && message.optString("type") == "server_hello" -> {
                                val opaque = try { workspaceRouting.request(message) } catch (error: IllegalStateException) {
                                    webSocket.close(1002, "Workspace routing unavailable")
                                    listener.onDisconnected(error.message ?: "Workspace routing unavailable")
                                    return
                                }
                                offeredSessionId = message.optString("sessionId").takeIf(String::isNotBlank)
                                val hello = JSONObject()
                                    .put("type", "client_hello")
                                    .put("supportedProtocolVersions", JSONArray().put(2))
                                    .put("features", JSONObject().put("opaqueWorkspaceRouting", opaque))
                                    .put("client", JSONObject()
                                        .put("name", "Codex Remote Android")
                                        .put("version", BuildConfig.VERSION_NAME)
                                        .put("platform", "Android ${android.os.Build.VERSION.SDK_INT}"))
                                if (lastSequence >= 0) hello.put("lastSequence", lastSequence)
                                lastSessionId?.let { hello.put("lastSessionId", it) }
                                if (!webSocket.send(hello.toString())) {
                                    listener.onDisconnected("Protocol negotiation could not be sent")
                                }
                            }
                            protocolVersion == 2 && message.optString("type") == "hello_ack" -> {
                                negotiatedProtocolVersion = message.optInt("protocolVersion", 2)
                                negotiatedCapabilities = message.optJSONObject("capabilities")
                                opaqueWorkspaceRouting = try { workspaceRouting.acknowledge(message) } catch (error: IllegalStateException) {
                                    webSocket.close(1002, "Workspace routing acknowledgement rejected")
                                    listener.onDisconnected(error.message ?: "Workspace routing unavailable")
                                    return
                                }
                                val previousSession = lastSessionId
                                val currentSession = message.optString("sessionId").takeIf(String::isNotBlank) ?: offeredSessionId
                                val sameSession = previousSession == null || previousSession == currentSession
                                if (!sameSession) {
                                    listener.onMessage(JSONObject().put("type", "host_session_changed"))
                                    lastSequence = -1L
                                    val abandoned = synchronized(pendingWriteRpcs) {
                                        pendingWriteRpcs.values.toList().also { pendingWriteRpcs.clear() }
                                    }
                                    abandoned.forEach { pending ->
                                        listener.onMessage(JSONObject()
                                            .put("type", "rpc_error")
                                            .put("id", pending.optString("id"))
                                            .put("error", "Host restarted before this operation was confirmed; it was not retried"))
                                    }
                                }
                                lastSessionId = currentSession
                                Log.i(LOG_TAG, "Negotiated Remote protocol v$negotiatedProtocolVersion")
                                if (!sameSession) synchronized(pendingAnswers) {
                                    pendingAnswers.keys.forEach { id -> listener.onMessage(JSONObject().put("type", "server_response_ack").put("requestId", id).put("status", "expired")) }
                                    pendingAnswers.clear()
                                }
                                negotiatedConnectionEpoch = attempt
                                listener.onMessage(message)
                                if (attempt != generation) return
                                listener.onConnected()
                                if (attempt != generation) return
                                if (sameSession) synchronized(pendingAnswers) { pendingAnswers.values.forEach { webSocket.send(it.toString()) } }
                                if (sameSession) synchronized(pendingWriteRpcs) {
                                    pendingWriteRpcs.values.forEach { webSocket.send(it.toString()) }
                                }
                            }
                            else -> {
                                if (message.optString("type") == "codex_event" && message.has("sequence")) {
                                    val sequence = message.optLong("sequence", -1L)
                                    if (sequence >= 0L && sequence <= lastSequence) return
                                    lastSequence = maxOf(lastSequence, sequence)
                                }
                                if (message.optString("type") == "server_response_ack") synchronized(pendingAnswers) { pendingAnswers.remove(message.optString("requestId")) }
                                if (message.optString("type") == "rpc_result" || message.optString("type") == "rpc_error") {
                                    synchronized(pendingWriteRpcs) { pendingWriteRpcs.remove(message.optString("id")) }
                                }
                                listener.onMessage(message)
                            }
                        }
                    }
                    .onFailure {
                        negotiatedConnectionEpoch = -1L
                        webSocket.close(1002, "Invalid server message")
                        listener.onDisconnected("Invalid server message")
                    }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(this@RemoteClient) {
                if (attempt == generation) negotiatedConnectionEpoch = -1L
                // Complete the peer-initiated close handshake so onClosed can reconnect.
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(this@RemoteClient) {
                if (attempt == generation) {
                    negotiatedConnectionEpoch = -1L
                    listener.onDisconnected(ConnectionFailureText.closed(code))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?): Unit = synchronized(this@RemoteClient) {
                if (attempt != generation) return
                negotiatedConnectionEpoch = -1L
                if (protocolVersion == 2 && response?.code == 404) {
                    connectAt(serverUrl, token, protocolVersion = 1, attempt = attempt)
                } else {
                    listener.onDisconnected(ConnectionFailureText.http(response?.code))
                }
            }
        })
    }

    @Synchronized
    fun rpc(id: String, method: String, params: JSONObject = JSONObject()) {
        requireNegotiatedSocket()
        val wireParams = JSONObject(params.toString())
        val durableKey = wireParams.optString("_remoteWriteKey").ifBlank { id }
        wireParams.remove("_remoteWriteKey")
        val message = JSONObject().put("type", "rpc").put("id", id).put("method", method).put("params", wireParams)
        if (negotiatedProtocolVersion >= 2 && isWriteRpc(method)) {
            message.put("idempotencyKey", durableKey)
            synchronized(pendingWriteRpcs) { pendingWriteRpcs[id] = JSONObject(message.toString()) }
        }
        send(message)
    }

    @Synchronized
    fun answer(requestId: String, result: JSONObject) {
        requireNegotiatedSocket()
        val message = JSONObject().put("type", "server_response").put("requestId", requestId).put("result", result)
        if (negotiatedCapabilities?.optJSONObject("interactions")?.optBoolean("acknowledgement") == true)
            synchronized(pendingAnswers) { pendingAnswers[requestId] = message }
        send(message)
    }

    @Synchronized
    fun answerError(requestId: String, message: String) {
        requireNegotiatedSocket()
        val response = JSONObject().put("type", "server_response").put("requestId", requestId)
            .put("error", JSONObject().put("code", -32601).put("message", message))
        if (negotiatedCapabilities?.optJSONObject("interactions")?.optBoolean("acknowledgement") == true)
            synchronized(pendingAnswers) { pendingAnswers[requestId] = response }
        send(response)
    }

    @Synchronized
    fun close() {
        closeSocket(clearPending = true)
    }

    private fun closeSocket(clearPending: Boolean) {
        negotiatedConnectionEpoch = -1L
        generation += 1
        socket?.close(1000, "Client closing")
        socket = null
        if (clearPending) synchronized(pendingWriteRpcs) { pendingWriteRpcs.clear() }
    }

    // Hold the client monitor from readiness check through send (and queue updates).
    // A new WebSocket accepts frames before server_hello/hello_ack; it is not yet usable.
    private fun requireNegotiatedSocket(): WebSocket {
        check(negotiatedConnectionEpoch >= 0L && negotiatedConnectionEpoch == generation) {
            "Connection is still being established. Please retry when connected."
        }
        return checkNotNull(socket) { "Remote is not connected" }
    }

    private fun send(message: JSONObject) {
        check(requireNegotiatedSocket().send(message.toString())) { "Remote is not connected" }
    }

    private fun normalizeHttp(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    private fun normalizeWebSocket(value: String): String = normalizeHttp(value)
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    internal companion object {
        const val LOG_TAG = "CodexRemoteProtocol"
        const val MAX_ATTACHMENT_RECOVERY_ATTEMPTS = 3
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun httpFailure(operation: String, code: Int, body: String): IOException {
            val detail = runCatching { JSONObject(body).optString("error") }.getOrNull()
            return IOException(detail?.ifBlank { null } ?: "$operation failed: HTTP $code")
        }

        fun isWriteRpc(method: String): Boolean = method.startsWith("turn/") || method in setOf(
            "thread/start", "thread/resume", "thread/fork", "thread/archive", "thread/unarchive",
            "thread/name/set", "thread/delete", "thread/compact/start", "review/start",
            "thread/goal/set", "thread/goal/clear",
            "thread/realtime/start", "thread/realtime/appendAudio", "thread/realtime/appendText",
            "thread/realtime/appendSpeech", "thread/realtime/stop",
            "host/account/login/start", "host/account/login/cancel", "host/account/logout",
            "host/mcp/oauth/start", "host/mcp/reload", "host/plugin/install", "host/plugin/uninstall",
            "host/settings/set", "host/skill/setEnabled", "host/thread/memoryMode/set",
            "host/terminal/execute", "host/terminal/write", "host/terminal/resize", "host/terminal/kill",
            "host/guardian/approveDenied",
            "host/workspace/text/save", "host/workspace/text/create", "host/memory/reset",
            "host/thread/backgroundTerminals/terminate", "host/thread/backgroundTerminals/clean",
        )
    }
}

private fun parseCredential(value: JSONObject): DeviceCredential {
    val metadata = value.optJSONObject("credential")
    val device = value.optJSONObject("device")
    val scopeArray = metadata?.optJSONArray("scopes")
    val scopes = buildSet {
        if (scopeArray != null) for (index in 0 until scopeArray.length()) {
            scopeArray.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
    return DeviceCredential(
        token = value.getString("token"),
        deviceId = device?.optString("id")?.takeIf(String::isNotBlank),
        expiresAt = metadata?.optString("expiresAt")?.takeIf(String::isNotBlank),
        scopes = scopes,
    )
}
