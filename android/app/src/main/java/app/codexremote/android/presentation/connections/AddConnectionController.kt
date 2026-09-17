package app.codexremote.android.presentation.connections

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import app.codexremote.android.parsePairingLink
import app.codexremote.android.connectionServerUrl
import java.net.URI

data class AddConnectionUiState(
    val isOpen: Boolean = false,
    val protocol: String = "https",
    val host: TextFieldValue = TextFieldValue(),
    val port: TextFieldValue = TextFieldValue("443"),
    val basePath: TextFieldValue = TextFieldValue("/"),
    val code: TextFieldValue = TextFieldValue(),
    val isConfirming: Boolean = false,
    val isBusy: Boolean = false,
    val isScanning: Boolean = false,
    val error: String? = null,
) {
    val serverPreview: String get() = connectionServerUrl(protocol, host.text, port.text, basePath.text).getOrNull().orEmpty()
}

/** Transient credentials only; generation guards reject late scanner/pairing callbacks. */
class AddConnectionController(private val onSubmit: (String, String, Long) -> Unit = { _, _, _ -> }) {
    private val state = mutableStateOf(AddConnectionUiState())
    val uiState: State<AddConnectionUiState> = state
    private var generation = 0L
    private var customPort = false
    fun open() { generation++; customPort = false; state.value = AddConnectionUiState(isOpen = true) }
    fun dismiss() { if (!state.value.isBusy) { generation++; state.value = AddConnectionUiState() } }
    fun edit() { if (!state.value.isBusy) state.value = state.value.copy(isConfirming = false, error = null) }
    private fun change(block: (AddConnectionUiState) -> AddConnectionUiState) {
        if (!state.value.isBusy && !state.value.isScanning) state.value = block(state.value).copy(error = null)
    }
    fun updateProtocol(value: String) = change {
        if (value !in setOf("https", "http")) it else it.copy(protocol = value,
            port = if (customPort) it.port else TextFieldValue(if (value == "https") "443" else "80"))
    }
    fun updateHost(value: TextFieldValue) = change { it.copy(host = value) }
    fun updatePort(value: TextFieldValue) = change { customPort = true; it.copy(port = value) }
    fun updateBasePath(value: TextFieldValue) = change { it.copy(basePath = value) }
    fun updateCode(value: TextFieldValue) = change { it.copy(code = value) }
    fun beginScan(): Long? {
        if (!state.value.isOpen || state.value.isBusy || state.value.isScanning) return null
        state.value = state.value.copy(isScanning = true, error = null)
        return ++generation
    }
    fun completeScan(ticket: Long, raw: String?) {
        if (ticket != generation || !state.value.isOpen || !state.value.isScanning) return
        state.value = state.value.copy(isScanning = false)
        if (raw != null) acceptLink(raw)
    }
    fun failScan(ticket: Long) {
        if (ticket == generation && state.value.isOpen && state.value.isScanning) state.value = state.value.copy(isScanning = false,
            error = "Scanner unavailable. Check Google Play services and try again, or enter the connection details manually.")
    }
    fun acceptLink(raw: String): Boolean {
        if (state.value.isBusy) return false
        val link = parsePairingLink(raw)
        if (link == null) {
            if (!state.value.isOpen) open()
            state.value = state.value.copy(error = "Invalid pairing QR code. Scan a codexremote pairing code.", isScanning = false)
            return false
        }
        val uri = URI(link.serverUrl)
        generation++; customPort = uri.port != -1
        state.value = AddConnectionUiState(isOpen = true, protocol = uri.scheme,
            host = TextFieldValue(uri.host.removePrefix("[").removeSuffix("]")),
            port = TextFieldValue((if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port).toString()),
            basePath = TextFieldValue(uri.rawPath.orEmpty().ifBlank { "/" }), code = TextFieldValue(link.code), isConfirming = true)
        return true
    }
    fun submit() {
        val current = state.value
        if (!current.isOpen || current.isBusy || current.isScanning) return
        val server = connectionServerUrl(current.protocol, current.host.text, current.port.text, current.basePath.text)
        if (server.isFailure) { state.value = current.copy(error = server.exceptionOrNull()?.message); return }
        if (current.code.text.trim().isEmpty()) { state.value = current.copy(error = "Enter the one-time Pair Code"); return }
        val ticket = ++generation
        state.value = current.copy(isBusy = true, error = null)
        onSubmit(server.getOrThrow(), current.code.text.trim(), ticket)
    }
    fun accepts(ticket: Long): Boolean = ticket == generation && state.value.isOpen && state.value.isBusy
    fun finish(ticket: Long, error: String? = null) {
        if (!accepts(ticket)) return
        state.value = if (error == null) AddConnectionUiState() else state.value.copy(isBusy = false, error = error)
    }
}
