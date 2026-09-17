package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceMigrationTest {
    private val a = "remote-workspace://" + "a".repeat(64)
    private val b = "remote-workspace://" + "b".repeat(64)
    private fun row(index: Int, ref: String) = JSONObject().put("index", index).put("available", true).put("cwd", ref)
    private fun result(vararg rows: JSONObject) = JSONObject().put("workspaces", JSONArray(rows.toList()))

    @Test fun correlatesByIndexBeforeAnyDraftChanges() {
        assertEquals(mapOf("/first" to a, "/second" to b),
            WorkspaceMigration.mappings(listOf("/first", "/second"), result(row(1,b), row(0,a))))
    }

    @Test fun rejectsIncompleteFailedDuplicateAndRawResults() {
        val paths = listOf("/first", "/second")
        for (response in listOf(result(row(0,a)), result(row(0,a), row(0,b)),
            result(row(0,a), row(2,b)), result(row(0,a), row(1,b).put("available", false)),
            result(row(0,a), row(1,"/private/path")), result(row(0,a), row(1,a)))) {
            try { WorkspaceMigration.mappings(paths, response); fail("must reject malformed mapping") }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun unavailableDraftOnlyWorkspaceDoesNotBlockRequiredWorkspace() {
        val paths = listOf("/current", "/old-draft")
        val response = result(row(0,a), JSONObject().put("index",1).put("available",false))
        assertEquals(mapOf("/current" to a), WorkspaceMigration.mappings(paths, response, setOf("/old-draft")))
        try { WorkspaceMigration.mappings(paths, response, setOf("/current")); fail("required workspace must not be skipped") }
        catch (_: IllegalStateException) { }
        // Optional only relaxes actual unavailability, never malformed server data.
        for (value in listOf("false", JSONObject.NULL, 0)) {
            response.getJSONArray("workspaces").getJSONObject(1).put("available",value)
            try { WorkspaceMigration.mappings(paths,response,setOf("/old-draft")); fail("invalid state must fail") }
            catch (_: IllegalStateException) { }
        }
        for (index in listOf("0", 0.5, JSONObject.NULL)) {
            try { WorkspaceMigration.mappings(listOf("/current"),result(row(0,a).put("index",index))); fail("invalid index must fail") }
            catch (_: IllegalStateException) { }
        }
    }
}
