@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.content.Intent
import android.os.Bundle
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.interactions.InteractionsController
import org.json.JSONObject

class InteractionDraftDeviceTest : InstrumentationTestCase() {
    fun testUnsubmittedAnswersRestoreWithoutSecretsOrImplicitSubmission() {
        val params = JSONObject("""{"questions":[{"id":"color","question":"Choose","options":[{"label":"Green"},{"label":"Blue"}]},{"id":"note","question":"Notes"},{"id":"secret","question":"Secret","isSecret":true}]}""")
        val first = InteractionFormModel("item/tool/requestUserInput", params)
        first.select("color", "Blue"); first.setText("note", "unfinished ordinary answer"); first.setText("secret", "DO-NOT-PERSIST-SECRET")
        val saved = first.saveDraft(); assertFalse(saved.toString().contains("DO-NOT-PERSIST-SECRET"))
        val restored = InteractionFormModel("item/tool/requestUserInput", params, JSONObject(saved.toString()))
        assertEquals("unfinished ordinary answer", restored.text("note")); assertEquals("", restored.text("secret")); assertTrue("Blue" in restored.selected("color"))
        val changed = InteractionFormModel("item/tool/requestUserInput", JSONObject(params.toString()).put("threadId", "different-context"), saved)
        assertEquals("", changed.text("note")); assertTrue(changed.selected("color").isEmpty())
    }
    fun testMcpDraftRestoresTypedSubmission() {
        val params = JSONObject("""{"mode":"form","requestedSchema":{"type":"object","properties":{"name":{"type":"string"},"count":{"type":"integer"},"confirmed":{"type":"boolean"}}}}""")
        val first = InteractionFormModel("mcpServer/elicitation/request", params)
        first.setText("name", "unfinished"); first.setText("count", "-7"); first.setChecked("confirmed", true)
        val restored = InteractionFormModel("mcpServer/elicitation/request", params, first.saveDraft()).reply().getJSONObject("content")
        assertEquals("unfinished", restored.getString("name")); assertEquals(-7, restored.getInt("count")); assertTrue(restored.getBoolean("confirmed"))
    }
    fun testActivityBundlePreservesPendingAnswer() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val server = "https://draft-qa.invalid"
        val store = SecureTokenStore(activity)
        val previous = store.loadCredential(server)
        val request = JSONObject("""{"method":"item/tool/requestUserInput","requestId":"draft-test","params":{"questions":[{"id":"note","question":"QA unfinished answer"}]}}""")
        try {
            instrumentation.runOnMainSync {
                store.save(server, DeviceCredential("test-token", "draft-device"))
                val show = MainActivity::class.java.getDeclaredMethod("showApproval", JSONObject::class.java, RemoteClient::class.java, String::class.java).apply { isAccessible = true }
                show.invoke(activity, request, null, server)
                val getter = MainActivity::class.java.getDeclaredMethod("getInteractionsController").apply { isAccessible = true }
                val controller = getter.invoke(activity) as InteractionsController
                controller.setText("note", "ActivityRestore4829")
                val bundle = Bundle()
                MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java).apply { isAccessible = true }.invoke(activity, bundle)
                val entry = JSONObject(bundle.getString("interactionDrafts")!!).getJSONObject(server + "\u0000draft-test")
                assertEquals("draft-device", entry.getString("device"))
                val restored = InteractionFormModel("item/tool/requestUserInput", request.getJSONObject("params"), entry.getJSONObject("draft"))
                assertEquals("ActivityRestore4829", restored.text("note"))
            }
        } finally { instrumentation.runOnMainSync { if (previous == null) store.clear(server) else store.save(server, previous); activity.finish() } }
    }
}
