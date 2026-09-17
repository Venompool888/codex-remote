package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Protocol form validation and draft serialization, independent of the UI toolkit. */
class InteractionFormModel(val method: String, params: JSONObject, savedDraft: JSONObject? = null) {
    enum class Kind { QUESTION, TEXT, NUMBER, INTEGER, BOOLEAN, SINGLE, MULTIPLE }
    data class Option(val value: String, val title: String, val description: String = "")
    data class Field(val id: String, val title: String, val description: String, val kind: Kind,
                     val required: Boolean = false, val secret: Boolean = false,
                     val header: String = "", val options: List<Option> = emptyList())
    private data class Answer(var text: String = "", var checked: Boolean = false,
                              val selected: MutableSet<String> = linkedSetOf())
    private val request = JSONObject(params.toString())
    private val identity = MessageDigest.getInstance("SHA-256").digest((method + "\n" + params).toByteArray())
        .joinToString("") { "%02x".format(it) }
    val isElicitation = method == "mcpServer/elicitation/request"
    val websiteUrl: String? = if (isElicitation && request.optString("mode") == "url") {
        val value = request.getString("url")
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "This authorization URL must be handled on the host"
        }
        value
    } else null
    val title: String = if (isElicitation) request.optString("serverName") +
        if (websiteUrl != null) " · website request" else " needs input" else "Codex needs your input"
    val message: String = request.optString("message")
    private val constraints = linkedMapOf<String, JSONObject>()
    val fields: List<Field> = when {
        websiteUrl != null -> emptyList()
        !isElicitation -> parseQuestions()
        else -> parseSchema()
    }
    private val answers = fields.associate { it.id to Answer() }

    init { restoreDraft(savedDraft) }

    fun text(id: String): String = answer(id).text
    fun checked(id: String): Boolean = answer(id).checked
    fun selected(id: String): Set<String> = answer(id).selected.toSet()
    fun setText(id: String, value: String) { answer(id).text = value }
    fun setChecked(id: String, value: Boolean) { answer(id).checked = value }
    fun select(id: String, value: String, selected: Boolean = true) {
        val field = fields.single { it.id == id }
        require(field.options.any { it.value == value }) { "Unknown choice" }
        val answer = answer(id)
        if (field.kind != Kind.MULTIPLE && selected) answer.selected.clear()
        if (selected) answer.selected.add(value) else answer.selected.remove(value)
    }
    private fun answer(id: String) = answers[id] ?: throw IllegalArgumentException("Unknown field")

    fun reply(): JSONObject {
        if (websiteUrl != null) return JSONObject().put("action", "accept").put("content", JSONObject.NULL)
        val values = JSONObject()
        fields.forEach { field -> value(field)?.let { values.put(field.id, it) } }
        return if (isElicitation) JSONObject().put("action", "accept").put("content", values)
        else JSONObject().put("answers", values)
    }
    fun cancelReply(): JSONObject = if (isElicitation) JSONObject().put("action", "cancel").put("content", JSONObject.NULL)
        else JSONObject().put("answers", JSONObject())

    /** Uses the original native dialog's ordered controls format, omitting secret text. */
    fun saveDraft(): JSONObject = JSONObject().put("identity", identity).put("controls", JSONArray().apply {
        fields.forEach { field ->
            val answer = answer(field.id)
            when (field.kind) {
                Kind.BOOLEAN -> put(JSONObject().put("checked", answer.checked))
                Kind.SINGLE, Kind.MULTIPLE -> field.options.forEach { put(JSONObject().put("checked", it.value in answer.selected)) }
                Kind.QUESTION -> {
                    field.options.forEach { put(JSONObject().put("checked", it.value in answer.selected)) }
                    put(if (field.secret) JSONObject() else JSONObject().put("text", answer.text))
                }
                else -> put(if (field.secret) JSONObject() else JSONObject().put("text", answer.text))
            }
        }
    })

    private fun restoreDraft(saved: JSONObject?) {
        if (saved?.optString("identity") != identity) return
        val controls = saved.optJSONArray("controls") ?: return
        val expected = fields.sumOf { field -> when (field.kind) {
            Kind.SINGLE, Kind.MULTIPLE -> field.options.size
            Kind.QUESTION -> field.options.size + 1
            else -> 1
        } }
        if (controls.length() != expected) return
        var index = 0
        fields.forEach { field ->
            val answer = answer(field.id)
            if (field.kind in setOf(Kind.QUESTION, Kind.SINGLE, Kind.MULTIPLE)) {
                field.options.forEach { option ->
                    if (controls.optJSONObject(index++)?.optBoolean("checked") == true) select(field.id, option.value)
                }
                if (field.kind != Kind.QUESTION) return@forEach
            }
            val item = controls.optJSONObject(index++) ?: return@forEach
            if (field.kind == Kind.BOOLEAN) answer.checked = item.optBoolean("checked")
            else if (!field.secret && item.has("text")) answer.text = item.optString("text")
        }
    }

    private fun value(field: Field): Any? {
        val answer = answer(field.id)
        val schema = constraints[field.id] ?: JSONObject()
        return when (field.kind) {
            Kind.QUESTION -> {
                val text = answer.text.trim().ifBlank { answer.selected.firstOrNull().orEmpty() }
                require(text.isNotBlank()) { "Answer every question before submitting" }
                JSONObject().put("answers", JSONArray().put(text))
            }
            Kind.BOOLEAN -> answer.checked
            Kind.SINGLE -> answer.selected.firstOrNull().also {
                require(!field.required || it != null) { "Complete ${field.id}" }
            }
            Kind.MULTIPLE -> {
                val selected = field.options.map { it.value }.filter { it in answer.selected }
                require(selected.size >= schema.optInt("minItems", 0) && selected.size <= schema.optInt("maxItems", field.options.size)) {
                    "Check the number of selected options"
                }
                JSONArray(selected)
            }
            else -> {
                val value = answer.text
                require(!field.required || value.isNotBlank()) { "Complete ${field.id}" }
                if (value.isBlank() && !field.required) return null
                if (field.kind == Kind.TEXT) {
                    require(value.length >= schema.optInt("minLength", 0) && value.length <= schema.optInt("maxLength", 20_000)) {
                        "Check the length of ${field.id}"
                    }
                    val format = schema.optString("format")
                    val valid = when (format) {
                        "email" -> androidx.core.util.PatternsCompat.EMAIL_ADDRESS.matcher(value).matches()
                        "uri" -> runCatching { URI(value).isAbsolute }.getOrDefault(false)
                        "date" -> runCatching { java.time.LocalDate.parse(value); true }.getOrDefault(false)
                        "date-time" -> runCatching { java.time.OffsetDateTime.parse(value); true }.getOrDefault(false)
                        else -> true
                    }
                    require(valid) { "Enter a valid $format" }
                    value
                } else {
                    val number = value.toDoubleOrNull()
                    require(number != null && number.isFinite() && (field.kind != Kind.INTEGER || number % 1.0 == 0.0) &&
                        number >= schema.optDouble("minimum", -Double.MAX_VALUE) && number <= schema.optDouble("maximum", Double.MAX_VALUE)) {
                        "Enter a valid ${field.id}"
                    }
                    number
                }
            }
        }
    }

    private fun parseQuestions(): List<Field> {
        require(method == "item/tool/requestUserInput") { "Unsupported interactive request" }
        val questions = request.getJSONArray("questions")
        require(questions.length() in 1..20) { "Unsupported question count" }
        return (0 until questions.length()).map { index ->
            val question = questions.getJSONObject(index)
            val options = question.optJSONArray("options") ?: JSONArray()
            Field(question.getString("id"), question.getString("question"), "Your answer", Kind.QUESTION,
                required = true, secret = question.optBoolean("isSecret"), header = question.optString("header"),
                options = (0 until options.length()).map { i -> options.getJSONObject(i).let {
                    Option(it.getString("label"), it.getString("label"), it.optString("description"))
                } })
        }.also { require(it.map(Field::id).distinct().size == it.size) { "Duplicate question IDs" } }
    }

    private fun parseSchema(): List<Field> {
        require(request.optString("mode", "form") in setOf("form", "openai/form")) { "This request requires authorization on the host" }
        val schema = request.getJSONObject("requestedSchema")
        require(schema.optString("type") == "object") { "Unsupported form; complete it on the host" }
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        require(properties.length() <= 30) { "This form is too large; complete it on the host" }
        val required = schema.optJSONArray("required") ?: JSONArray()
        val requiredKeys = (0 until required.length()).map { required.getString(it) }.toSet()
        return properties.keys().asSequence().map { id ->
            val spec = properties.getJSONObject(id)
            require(!spec.has("pattern") && spec.optString("format") in setOf("", "email", "uri", "date", "date-time")) {
                "Unsupported text constraint; complete it on the host"
            }
            constraints[id] = spec
            val type = spec.optString("type")
            val multiple = type == "array"
            val item = if (multiple) spec.getJSONObject("items") else spec
            val options = item.optJSONArray("enum") ?: item.optJSONArray(if (multiple) "anyOf" else "oneOf")
            val kind = when {
                type == "boolean" -> Kind.BOOLEAN
                multiple -> Kind.MULTIPLE
                options != null -> Kind.SINGLE
                type == "string" -> Kind.TEXT
                type == "number" -> Kind.NUMBER
                type == "integer" -> Kind.INTEGER
                else -> throw IllegalArgumentException("Unsupported form field; complete it on the host")
            }
            val choices = if (kind in setOf(Kind.SINGLE, Kind.MULTIPLE)) {
                require(options != null && options.length() in 1..100 && item.optString("type", if (multiple) "string" else "") == "string") {
                    "Unsupported selection; complete it on the host"
                }
                val names = item.optJSONArray("enumNames")
                (0 until options.length()).map { i ->
                    val option = options.optJSONObject(i)
                    val value = if (option != null) option.opt("const") else options.opt(i)
                    require(value is String) { "Unsupported selection; complete it on the host" }
                    Option(value, option?.optString("title")?.takeIf(String::isNotBlank)
                        ?: names?.optString(i)?.takeIf(String::isNotBlank) ?: value)
                }.also { require(it.map(Option::value).distinct().size == it.size) { "Duplicate choices; complete it on the host" } }
            } else emptyList()
            Field(id, spec.optString("title", id), spec.optString("description", id), kind, id in requiredKeys, options = choices)
        }.toList()
    }
}
