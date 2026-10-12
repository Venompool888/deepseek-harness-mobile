package app.deepseek.harness.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Question vocabulary shared by Remote waterfalls and the userQuestions projection. */
internal data class UserQuestionOption(val label: String, val description: String? = null)

internal data class UserQuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<UserQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val intentKind: String? = null,
    val approveLabel: String? = null,
    val intentCallId: String? = null,
)

internal data class UserQuestionAnswerItem(
    val id: String,
    val selected: List<String>,
    val custom: String? = null,
)

internal data class PendingQuestion(
    val rpcId: String?,
    val sessionId: String,
    val questionId: String,
    val generation: Long = 0,
    val eventId: String? = null,
    val callId: String? = null,
    val questions: List<UserQuestionItem>,
    val state: String = "open",
    val deadline: Long? = null,
) {
    val key: String get() = "$sessionId:${callId ?: eventId ?: questionId}"
}

/** Parse only the fields in the rc.2 client-safe question schema. */
internal fun parseUserQuestions(value: Any?): List<UserQuestionItem>? {
    val array = value as? JSONArray ?: return null
    if (array.length() == 0) return null
    val result = ArrayList<UserQuestionItem>(array.length())
    val ids = HashSet<String>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: return null
        val id = item.optNullableString("id")?.takeIf(String::isNotBlank) ?: return null
        val question = item.optNullableString("question") ?: return null
        if (!ids.add(id)) return null
        val options = ArrayList<UserQuestionOption>()
        val optionArray = item.optJSONArray("options")
        if (optionArray != null) for (optionIndex in 0 until optionArray.length()) {
            val option = optionArray.optJSONObject(optionIndex) ?: return null
            val label = option.optNullableString("label")?.takeIf(String::isNotBlank) ?: return null
            options += UserQuestionOption(label, option.optNullableString("description"))
        }
        val intent = item.optJSONObject("intent")
        result += UserQuestionItem(
            id = id,
            question = question,
            detail = item.optNullableString("detail"),
            header = item.optNullableString("header"),
            options = options,
            multiSelect = item.optBoolean("multiSelect", false),
            intentKind = intent?.optNullableString("kind"),
            approveLabel = intent?.optNullableString("approve"),
            intentCallId = intent?.optNullableString("callId"),
        )
    }
    return result
}

/** Parse a pending Remote-event frame; projection cards are built separately from active rows. */
internal fun parsePendingQuestion(frame: JSONObject): PendingQuestion? {
    val sessionId = frame.optNullableString("sessionId")?.takeIf(String::isNotBlank) ?: return null
    val questions = parseUserQuestions(frame.opt("questions")) ?: return null
    val eventId = frame.optNullableString("eventId")
    val callId = frame.optNullableString("callId")
    val questionId = callId ?: eventId ?: return null
    val rpcId = frame.optNullableString("_rpcId")
    return PendingQuestion(
        rpcId = rpcId,
        sessionId = sessionId,
        questionId = questionId,
        generation = frame.optLong("generation", 0),
        eventId = eventId,
        callId = callId,
        questions = questions,
        state = frame.optString("state", "open"),
        deadline = frame.optLong("deadline").takeIf { frame.has("deadline") && !frame.isNull("deadline") },
    )
}

/** Validate a full answer batch before it can be sent to rc.2. */
internal fun validateQuestionAnswers(
    questions: List<UserQuestionItem>,
    answers: List<UserQuestionAnswerItem>,
) {
    require(questions.isNotEmpty()) { "ask_user_question requires at least one question" }
    require(answers.size == questions.size) { "answer batch must answer every question exactly once" }
    val byId = questions.associateBy { it.id }
    require(byId.size == questions.size) { "question ids must be unique" }
    val answerIds = answers.map { it.id }
    require(answerIds.toSet().size == answerIds.size && answerIds.toSet() == byId.keys) {
        "answer batch must name every question exactly once"
    }
    for (answer in answers) {
        val question = byId.getValue(answer.id)
        require(answer.selected.toSet().size == answer.selected.size) { "selected options must be unique" }
        require(question.multiSelect || answer.selected.size <= 1) { "question allows one selected option" }
        val offered = question.options.map { it.label }.toSet()
        require(answer.selected.all(offered::contains)) { "selected option was not offered" }
    }
}

internal fun questionAnswersJson(answers: List<UserQuestionAnswerItem>): JSONObject = JSONObject().put(
    "answers",
    JSONArray().apply {
        answers.forEach { answer ->
            put(JSONObject().put("id", answer.id).put("selected", JSONArray(answer.selected)).also { item ->
                answer.custom?.let { item.put("custom", it) }
            })
        }
    },
)
