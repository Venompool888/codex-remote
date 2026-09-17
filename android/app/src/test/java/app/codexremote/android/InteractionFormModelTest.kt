package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InteractionFormModelTest {
    private fun question(secret: Boolean = false) = JSONObject("""{"questions":[{"id":"choice","question":"Choose","isSecret":$secret,"options":[{"label":"One"},{"label":"Two"}]}]}""")
    private fun form(properties: String, required: String = "[]") = JSONObject("""{"mode":"form","requestedSchema":{"type":"object","properties":$properties,"required":$required}}""")
    private fun model(properties: String, required: String = "[]") = InteractionFormModel("mcpServer/elicitation/request", form(properties, required))
    private fun invalid(action: () -> Unit) { try { action(); fail("Expected validation failure") } catch (_: IllegalArgumentException) {} }

    @Test fun noDefaultAnswerAndFreeTextOverridesSelection() {
        val model = InteractionFormModel("item/tool/requestUserInput", question())
        invalid { model.reply() }
        model.select("choice", "One")
        assertEquals("One", model.reply().getJSONObject("answers").getJSONObject("choice").getJSONArray("answers").getString(0))
        model.setText("choice", "  Custom  ")
        assertEquals("Custom", model.reply().getJSONObject("answers").getJSONObject("choice").getJSONArray("answers").getString(0))
    }
    @Test fun draftCompatibilityAndSecretOmission() {
        val first = InteractionFormModel("item/tool/requestUserInput", question(true))
        first.select("choice", "Two"); first.setText("choice", "never-persist-this")
        val saved = first.saveDraft()
        assertEquals(3, saved.getJSONArray("controls").length())
        assertFalse(saved.toString().contains("never-persist-this"))
        val restored = InteractionFormModel("item/tool/requestUserInput", question(true), saved)
        assertEquals(setOf("Two"), restored.selected("choice")); assertEquals("", restored.text("choice"))
        val changed = InteractionFormModel("item/tool/requestUserInput", question(false), saved)
        assertTrue(changed.selected("choice").isEmpty())
    }
    @Test fun ordinaryDraftRestoresOnlyExactForm() {
        val original = model("""{"text":{"type":"string"}}""")
        original.setText("text", "draft")
        val saved = original.saveDraft()
        val restored = InteractionFormModel("mcpServer/elicitation/request", form("""{"text":{"type":"string"}}"""), saved)
        assertEquals("draft", restored.text("text"))
        saved.getJSONArray("controls").put(JSONObject().put("text", "extra"))
        assertEquals("", InteractionFormModel("mcpServer/elicitation/request", form("""{"text":{"type":"string"}}"""), saved).text("text"))
    }
    @Test fun validatesMultipleSelectionBoundsAndStableOptionOrder() {
        val model = model("""{"items":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string","enum":["a","b","c"]}}}""")
        invalid { model.reply() }
        model.select("items", "b"); model.select("items", "a")
        assertEquals("[\"a\",\"b\"]", model.reply().getJSONObject("content").getJSONArray("items").toString())
        model.select("items", "c"); invalid { model.reply() }
    }
    @Test fun requiredSelectionIsNotAutomaticallyChosen() {
        val model = model("""{"item":{"type":"string","enum":["a","b"]}}""", "[\"item\"]")
        invalid { model.reply() }; model.select("item", "b")
        assertEquals("b", model.reply().getJSONObject("content").getString("item"))
    }
    @Test fun validatesNumberAndIntegerWithoutNonFiniteValues() {
        val model = model("""{"count":{"type":"integer","minimum":1,"maximum":5}}""", "[\"count\"]")
        for (value in listOf("", "NaN", "Infinity", "2.5", "0", "6")) { model.setText("count", value); invalid { model.reply() } }
        model.setText("count", "3")
        assertEquals(3.0, model.reply().getJSONObject("content").getDouble("count"), 0.0)
    }
    @Test fun optionalBlankIsOmittedAndRequiredBooleanCanBeFalse() {
        val model = model("""{"note":{"type":"string"},"enabled":{"type":"boolean"}}""", "[\"enabled\"]")
        val result = model.reply().getJSONObject("content")
        assertFalse(result.has("note")); assertFalse(result.getBoolean("enabled"))
    }
    @Test fun validatesStringFormatsAndLengths() {
        val model = model("""{"email":{"type":"string","format":"email","minLength":3}}""", "[\"email\"]")
        model.setText("email", "bad"); invalid { model.reply() }
        model.setText("email", "person@example.com")
        assertEquals("person@example.com", model.reply().getJSONObject("content").getString("email"))
    }
    @Test fun websiteIsHttpsAndExplicitlyAcknowledged() {
        for (url in listOf("http://example.com", "https://user@example.com", "file:///tmp/x")) {
            invalid { InteractionFormModel("mcpServer/elicitation/request", JSONObject().put("mode", "url").put("url", url)) }
        }
        val model = InteractionFormModel("mcpServer/elicitation/request", JSONObject().put("mode", "url").put("url", "https://example.com/authorize"))
        assertTrue(model.fields.isEmpty()); assertEquals("accept", model.reply().getString("action")); assertTrue(model.reply().isNull("content"))
        assertEquals("cancel", model.cancelReply().getString("action"))
    }
    @Test fun questionsCancelRetainsExpectedWireShape() {
        val model = InteractionFormModel("item/tool/requestUserInput", question())
        assertEquals("{\"answers\":{}}", model.cancelReply().toString())
    }
    @Test fun unsupportedSchemaIsRejectedRatherThanDropped() {
        invalid { model("""{"object":{"type":"object"}}""") }
        invalid { model("""{"text":{"type":"string","pattern":"secret"}}""") }
    }
}
