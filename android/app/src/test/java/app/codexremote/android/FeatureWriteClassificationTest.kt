package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class FeatureWriteClassificationTest {
    @Test fun featureMutationsReceiveDurableKeysWhileReadsDoNot() {
        listOf("thread/name/set", "thread/delete", "thread/compact/start", "review/start", "thread/goal/set", "thread/goal/clear",
            "thread/realtime/start", "thread/realtime/appendAudio", "thread/realtime/appendText", "thread/realtime/appendSpeech", "thread/realtime/stop",
            "host/account/login/start", "host/account/login/cancel", "host/account/logout", "host/mcp/oauth/start", "host/mcp/reload",
            "host/plugin/install", "host/plugin/uninstall", "host/settings/set", "host/skill/setEnabled", "host/thread/memoryMode/set",
            "host/terminal/execute", "host/terminal/write", "host/terminal/resize", "host/terminal/kill", "host/guardian/approveDenied", "host/workspace/text/save", "host/workspace/text/create", "host/memory/reset",
            "host/thread/backgroundTerminals/terminate", "host/thread/backgroundTerminals/clean")
            .forEach { assertTrue(it, RemoteClient.isWriteRpc(it)) }
        listOf("thread/realtime/listVoices", "thread/search", "thread/turns/list", "thread/goal/get", "host/account/usage",
            "host/plugin/catalog", "host/settings/read", "host/terminal/list", "host/file/readReference", "host/mcp/resource/read")
            .forEach { assertFalse(it, RemoteClient.isWriteRpc(it)) }
    }
}
