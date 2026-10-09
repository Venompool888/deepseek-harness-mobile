package app.deepseek.harness.mobile

import android.webkit.CookieManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class HarnessApi(
    private val baseUrl: () -> String,
    private val accessToken: (String) -> String? = { null },
    private val cookieHeader: (String) -> String? = { url ->
        CookieManager.getInstance().getCookie(url)
    },
    private val transport: (() -> HarnessTransport)? = null,
) {
    private val sessionMetadata = java.util.concurrent.ConcurrentHashMap<String, Session>()

    data class Session(
        val id: String,
        val title: String?,
        val cwd: String?,
        val agentPreset: String?,
        val updatedAt: Long,
        val running: Boolean,
        val blank: Boolean,
        val origin: String? = null,
        val parentSessionId: String? = null,
    )

    data class Workspace(
        val id: String,
        val title: String,
        val path: String,
        val sessionIds: List<String>,
    )

    data class DirectoryEntry(val name: String, val path: String, val hidden: Boolean)

    data class DirectoryListing(
        val path: String,
        val home: String,
        val crumbs: List<DirectoryEntry>,
        val entries: List<DirectoryEntry>,
        val truncated: Boolean,
    )

    data class Model(
        val provider: String,
        val providerName: String,
        val id: String,
        val name: String,
        val defaultEffort: String?,
        val efforts: List<Pair<String, String>>,
    )

    data class Models(
        val currentProvider: String,
        val currentModel: String,
        val currentEffort: String?,
        val routable: Boolean,
        val items: List<Model>,
        val failures: List<ModelFailure> = emptyList(),
    )

    data class ModelFailure(val id: String, val name: String, val message: String)

    data class PermissionOption(val value: String, val name: String, val description: String?)

    data class SessionControls(
        val permissionOptions: List<PermissionOption> = emptyList(),
        val permission: String? = null,
        val planActive: Boolean? = null,
        val planPending: Boolean = false,
    )

    data class AgentPreset(
        val id: String,
        val name: String,
        val description: String?,
        val trust: String,
        val isDefault: Boolean,
        val broken: String?,
    )

    data class ConversationStats(
        val turns: Int = 0,
        val steps: Int = 0,
        val llmMs: Long = 0,
        val toolMs: Long = 0,
        val ttftMs: Long = 0,
        val ttftSteps: Int = 0,
        val decodeMs: Long = 0,
        val decodeTokens: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheReadTokens: Long = 0,
    )

    data class TodoItem(val content: String, val status: String)

    data class ContextUsage(
        val usedTokens: Long,
        val contextWindow: Long,
        val systemTokens: Long? = null,
        val toolsTokens: Long? = null,
        val messageTokens: Long? = null,
    ) {
        val percent: Int = kotlin.math.round(usedTokens.toDouble() / contextWindow * 100).toInt().coerceIn(0, 100)
    }

    data class History(
        val messages: List<ChatMessage>,
        val hasMore: Boolean,
        val controls: SessionControls,
        val stats: ConversationStats,
        val todos: List<TodoItem> = emptyList(),
        val contextUsage: ContextUsage? = null,
        val runningStartedAt: Long? = null,
        val questions: List<PendingQuestion> = emptyList(),
    )

    data class PendingApproval(
        val rpcId: String,
        val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val callId: String?,
        val reason: String?,
        val displayReason: JSONObject? = null,
        val generation: Long = 0,
    )

    data class SessionExport(val url: String, val cookie: String?, val authorization: String?)

    data class MessageFeedback(
        val messageId: String,
        val rating: String,
        val note: String?,
        val version: String,
    )

    data class MessageFeedbackMutation(
        val ok: Boolean,
        val item: MessageFeedback? = null,
        val conflict: Boolean = false,
        val errorCode: String? = null,
    )

    open class AuthenticationRequired(val access: Boolean = false, message: String = if (access) "Cloudflare Access authentication is required" else "Harness 登录凭据已过期，请提供新的 dsh 登录链接") : IOException(message)

    class RemoteFailure(val code: String, message: String) : IOException(message)

    class SettingsAccessForbidden : IOException("Harness settings access is forbidden")

    fun sessions(): List<Session> {
        val value = call("session/list", JSONObject())
        return value.getJSONArray("items").objects().map(::parseSessionSummary).also { rows ->
            rows.forEach { sessionMetadata[it.id] = it }
        }
    }

    /** Whether current metadata is enough to construct a legal follow/page address. */
    fun isSessionAddressResolved(sessionId: String): Boolean = sessionAddress(sessionId) != null

    private fun sessionAddress(sessionId: String): HarnessSessionAddress? = sessionMetadata[sessionId]?.let {
        resolveHarnessSessionAddress(it.id, it.origin, it.parentSessionId)
    }

    private fun resolveSessionAddress(sessionId: String): HarnessSessionAddress {
        sessionAddress(sessionId)?.let { return it }
        // Legacy callers may request history directly; consult the authoritative
        // directory before ever issuing a follow/page for an unknown id.
        sessions()
        return sessionAddress(sessionId)
            ?: throw IOException("Session address is not resolved; refresh the session directory")
    }

    private fun parseSessionSummary(item: JSONObject): Session {
        val values = item.optJSONObject("projections")?.optJSONObject("values")
        return Session(
            id = item.getString("sessionId"),
            title = values?.optNullableString("title"),
            cwd = item.optNullableString("cwd"),
            agentPreset = values?.optNullableString("agentPreset") ?: item.optNullableString("agentPreset"),
            updatedAt = item.optLong("updatedAt"),
            running = item.optBoolean("running"),
            blank = item.optBoolean("blank"),
            origin = item.optNullableString("origin"),
            parentSessionId = item.optNullableString("parentSessionId"),
        )
    }

    fun workspaces(): List<Workspace> {
        val value = firstStream("workspace/follow", JSONObject()).getJSONObject("value")
        return value.getJSONArray("items").objects().map { item ->
            Workspace(
                id = item.getString("workspaceId"),
                title = item.getString("title"),
                path = item.getString("path"),
                sessionIds = item.getJSONArray("sessionIds").strings(),
            )
        }
    }

    fun listDirectory(path: String? = null): DirectoryListing {
        val value = call("directoryPicker/list", JSONObject().apply {
            if (!path.isNullOrBlank()) put("path", path)
        })
        fun entry(item: JSONObject) = DirectoryEntry(
            name = item.getString("name"),
            path = item.getString("path"),
            hidden = item.optBoolean("hidden"),
        )
        return DirectoryListing(
            path = value.getString("path"),
            home = value.getString("home"),
            crumbs = value.getJSONArray("crumbs").objects().map(::entry),
            entries = value.getJSONArray("entries").objects().map(::entry),
            truncated = value.optBoolean("truncated"),
        )
    }

    fun createDirectory(path: String, name: String): String = remoteValue(
        "directoryPicker/createDirectory",
        JSONObject().put("path", path).put("name", name),
    ) as? String ?: throw IOException("Invalid directory result")

    fun createWorkspace(path: String): Workspace {
        val item = call("workspace/create", JSONObject().put("path", path)).getJSONObject("workspace")
        return Workspace(
            id = item.getString("workspaceId"),
            title = item.getString("title"),
            path = item.getString("path"),
            sessionIds = item.getJSONArray("sessionIds").strings(),
        )
    }

    fun loadOlder(sessionId: String) {
        val state = synchronized(this) { followState?.takeIf { followSession == sessionId } }
            ?: throw IOException("History is still synchronizing")
        val page = call("session/page", state.pageRequest(sessionId))
        if (followState === state && desiredSession == sessionId) state.prependPage(page)
    }

    fun history(sessionId: String): History {
        val value = sessionHistory(sessionId)
        val values = value.optJSONObject("projections")?.optJSONObject("values")
        val permissions = values?.optJSONObject("permissions")
        val plan = values?.optJSONObject("plan")
        val sessionStats = values?.optJSONObject("sessionStats")
        val usage = values?.optJSONObject("tokenUsage")
        val contextPressure = values?.optJSONObject("contextPressure")
        val contextBreakdown = values?.optJSONObject("contextBreakdown")
        val contextWindow = contextPressure?.optLong("contextWindow")?.takeIf { it > 0 }
        val contextUsed = contextPressure?.let {
            when {
                it.has("projectedTokens") -> it.optLong("projectedTokens")
                it.has("pressureTokens") -> it.optLong("pressureTokens")
                else -> null
            }
        }
        return History(
            messages = ChatProjection.fromHistory(value.getJSONArray("events")),
            hasMore = value.optBoolean("hasMore"),
            controls = SessionControls(
                permissionOptions = permissions?.optJSONArray("options")?.objects()?.map {
                    PermissionOption(
                        value = it.getString("value"),
                        name = it.optString("name", it.getString("value")),
                        description = it.optNullableString("description"),
                    )
                }.orEmpty(),
                permission = permissions?.optNullableString("currentValue"),
                planActive = plan?.optBoolean("active"),
                planPending = plan?.optBoolean("pending") ?: false,
            ),
            stats = ConversationStats(
                turns = sessionStats?.optInt("turns") ?: 0,
                steps = sessionStats?.optInt("steps") ?: 0,
                llmMs = sessionStats?.optLong("llmMs") ?: 0,
                toolMs = sessionStats?.optLong("toolMs") ?: 0,
                ttftMs = sessionStats?.optLong("ttftMs") ?: 0,
                ttftSteps = sessionStats?.optInt("ttftSteps") ?: 0,
                decodeMs = sessionStats?.optLong("decodeMs") ?: 0,
                decodeTokens = sessionStats?.optLong("decodeTokens") ?: 0,
                inputTokens = (usage?.optLong("uncachedInputTokens") ?: 0) +
                    (usage?.optLong("cacheReadTokens") ?: 0) +
                    (usage?.optLong("cacheWriteTokens") ?: 0),
                outputTokens = usage?.optLong("outputTokens") ?: 0,
                cacheReadTokens = usage?.optLong("cacheReadTokens") ?: 0,
            ),
            todos = values?.optJSONArray("todos")?.objects()?.mapNotNull { item ->
                val content = item.optString("content").trim()
                val status = item.optString("status")
                if (content.isBlank() || status !in setOf("pending", "in_progress", "completed")) null
                else TodoItem(content, status)
            }.orEmpty(),
            contextUsage = if (contextWindow != null && contextUsed != null) ContextUsage(
                usedTokens = contextUsed,
                contextWindow = contextWindow,
                systemTokens = contextBreakdown?.takeIf { it.has("systemTokens") }?.optLong("systemTokens"),
                toolsTokens = contextBreakdown?.takeIf { it.has("toolsTokens") }?.optLong("toolsTokens"),
                messageTokens = contextBreakdown?.takeIf { it.has("messageTokens") }?.optLong("messageTokens"),
            ) else null,
            runningStartedAt = runningTurnStart(value.getJSONArray("events")),
            questions = parseContinuedQuestions(values?.optJSONObject("userQuestions"), sessionId, values?.optJSONObject("inbox")),
        )
    }

    private fun runningTurnStart(entries: JSONArray): Long? {
        var startedAt: Long? = null
        entries.objects().forEach { entry ->
            val event = entry.optJSONObject("event") ?: return@forEach
            when (event.optString("type")) {
                "turn/start" -> startedAt = event.optLong("time").takeIf { it > 0 }
                "turn/end" -> startedAt = null
            }
        }
        return startedAt
    }

    fun models(sessionId: String): Models {
        val value = call("session/modelCatalog", JSONObject())
        val selection = call("session/projections", JSONObject().put("sessionId", sessionId)).optJSONObject("values")?.optJSONObject("modelSelection")
        val current = selection?.optJSONObject("next") ?: selection?.optJSONObject("lastUsed") ?: value.getJSONObject("default")
        val items = value.getJSONArray("groups").objects().flatMap { group ->
            group.getJSONArray("models").objects().map { model ->
                val reasoning = model.optJSONObject("reasoning")
                Model(
                    provider = group.getString("id"),
                    providerName = group.getString("name"),
                    id = model.getString("id"),
                    name = model.getString("name"),
                    defaultEffort = reasoning?.optNullableString("defaultEffort"),
                    efforts = reasoning?.optJSONArray("efforts")?.objects()?.map {
                        it.getString("id") to it.getString("name")
                    }.orEmpty(),
                )
            }
        }
        return Models(
            currentProvider = current.getString("provider"),
            currentModel = current.getString("model"),
            currentEffort = current.optNullableString("reasoningEffort"),
            routable = value.optJSONArray("routableProviders")?.strings()?.contains(current.optString("provider")) == true,
            items = items,
            failures = value.optJSONArray("failures")?.objects()?.map { failure ->
                ModelFailure(
                    id = failure.optString("id"),
                    name = failure.optString("name", failure.optString("id", "Provider")),
                    message = failure.optString("message", "模型目录加载失败"),
                )
            }.orEmpty(),
        )
    }

    fun providerOnboarding(): ProviderOnboarding {
        val registered = remoteValue("llm/listProviders", JSONObject()) as JSONArray
        val directory = remoteValue("llm/listConfigurableProviders", JSONObject()) as JSONArray
        val active = registered.objects().map { it.getString("id") }.toSet()
        val providers = JSONObject().put("providers", JSONArray(directory.objects().map { it.put("active", it.optString("provider") in active) }))
        val settings = try {
            call("settings/describe", JSONObject())
        } catch (_: SettingsAccessForbidden) {
            return ProviderOnboarding.Unavailable("当前连接无权读取 Harness 设置")
        }
        val refs = ProviderOnboardingProjection.credentialRefs(providers, settings)
        val credentials = if (refs.isEmpty()) {
            JSONObject().put("credentials", JSONObject())
        } else {
            JSONObject().put("credentials", call("credentials/describe", JSONObject().put("refs", JSONArray(refs.toList()))))
        }
        return ProviderOnboardingProjection.project(providers, settings, credentials)
    }

    fun setCredential(ref: String, value: String) {
        call("credentials/set", JSONObject().put("ref", ref).put("value", value))
    }

    fun createSession(workspaceId: String?, agentPreset: String? = null): String {
        val payload = JSONObject()
        if (workspaceId != null) payload.put("workspaceId", workspaceId)
        if (agentPreset != null) payload.put("agentPreset", agentPreset)
        val id = call("session/create", payload).getString("sessionId")
        sessionMetadata[id] = Session(id, null, null, agentPreset, 0, false, true)
        return id
    }

    fun agentPresets(): List<AgentPreset> {
        val value = call("agentPresets/list", JSONObject())
        return value.getJSONArray("presets").objects().map {
            AgentPreset(
                id = it.getString("id"),
                name = it.optString("name", it.getString("id")),
                description = it.optNullableString("description"),
                trust = it.optString("trust"),
                isDefault = it.optBoolean("isDefault"),
                broken = it.optNullableString("broken"),
            )
        }
    }

    fun selectAgentPreset(sessionId: String, agentPreset: String) {
        call("agentPresets/select", JSONObject().put("sessionId", sessionId).put("agentPreset", agentPreset))
    }

    fun renameSession(sessionId: String, title: String) {
        call("session/rename", JSONObject().put("sessionId", sessionId).put("title", title))
    }

    fun forkSession(sessionId: String, atSeq: Long? = null): String {
        val id = call("session/fork", JSONObject().put("sessionId", sessionId).apply {
            if (atSeq != null) put("atSeq", atSeq)
        }).getString("sessionId")
        sessionMetadata[id] = Session(id, null, sessionMetadata[sessionId]?.cwd, null, 0, false, true)
        return id
    }

    fun messageFeedback(sessionId: String): List<MessageFeedback> {
        val result = callRemote("messageFeedback/list", JSONObject().put("sessionId", sessionId))
        if (!result.optBoolean("ok")) {
            val error = result.optJSONObject("error")
            throw RemoteFailure(error?.optString("code", "feedback-list-failed").orEmpty(), "Message feedback could not be loaded")
        }
        return result.getJSONObject("value").getJSONArray("items").objects().map(::parseMessageFeedback)
    }

    fun putMessageFeedback(
        sessionId: String,
        messageId: String,
        rating: String,
        note: String?,
        ifVersion: String?,
    ): MessageFeedbackMutation {
        require(rating == "positive" || rating == "negative")
        val result = callRemote("messageFeedback/put", JSONObject().apply {
            put("sessionId", sessionId)
            put("messageId", messageId)
            put("rating", rating)
            if (note != null) put("note", note)
            put("ifVersion", ifVersion ?: JSONObject.NULL)
        })
        return parseMessageFeedbackMutation(result, deleted = false)
    }

    fun deleteMessageFeedback(sessionId: String, item: MessageFeedback): MessageFeedbackMutation {
        val result = callRemote("messageFeedback/delete", JSONObject()
            .put("sessionId", sessionId)
            .put("messageId", item.messageId)
            .put("ifVersion", item.version))
        return parseMessageFeedbackMutation(result, deleted = true)
    }

    private fun parseMessageFeedbackMutation(result: JSONObject, deleted: Boolean): MessageFeedbackMutation {
        if (result.optBoolean("ok")) {
            val item = if (deleted) null else result.getJSONObject("value").let(::parseMessageFeedback)
            return MessageFeedbackMutation(ok = true, item = item)
        }
        val error = result.optJSONObject("error") ?: JSONObject()
        val code = error.optString("code", "feedback-failed")
        return MessageFeedbackMutation(
            ok = false,
            item = error.optJSONObject("current")?.let(::parseMessageFeedback),
            conflict = code == "version-conflict",
            errorCode = code,
        )
    }

    private fun parseMessageFeedback(item: JSONObject): MessageFeedback = MessageFeedback(
        messageId = item.getString("messageId"),
        rating = item.getString("rating"),
        note = item.optNullableString("note"),
        version = item.getString("version"),
    )

    fun selectModel(sessionId: String, model: Model, effort: String?) {
        call("session/selectModel", JSONObject().apply {
            put("sessionId", sessionId)
            put("provider", model.provider)
            put("model", model.id)
            if (effort != null) put("reasoningEffort", effort)
        })
    }

    fun prompt(sessionId: String, content: JSONArray, mode: String = "queue", requestId: String = UUID.randomUUID().toString()) {
        call("session/prompt", JSONObject().apply {
            put("requestId", requestId)
            put("sessionId", sessionId)
            put("mode", mode)
            put("content", content)
            put("clientTimeZone", java.util.TimeZone.getDefault().id)
        })
    }

    fun command(sessionId: String, command: String) {
        call("commands/execute", commandExecutionPayload(sessionId, command))
    }

    fun cancel(sessionId: String) {
        call("session/cancel", JSONObject().put("sessionId", sessionId))
    }

    @Volatile private var liveConnection: HarnessRemoteConnection? = null
    @Volatile private var followSession: String? = null
    private var followId: String? = null
    private val runningBySession = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun liveRunning(sessionId: String): Boolean? = runningBySession[sessionId]
    @Volatile private var desiredSession: String? = null
    fun watchSession(sessionId: String?) { desiredSession = sessionId }
    private var followState: HarnessSessionState? = null
    private var liveSignal: ((JSONObject) -> Unit)? = null
    @Volatile private var eventClientId: String? = null
    private val approvals = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val approvalCarriers = java.util.concurrent.ConcurrentHashMap<String, HarnessTransport>()
    private val pendingQuestions = java.util.concurrent.ConcurrentHashMap<String, PendingQuestion>()
    private val questionCarriers = java.util.concurrent.ConcurrentHashMap<String, HarnessTransport>()
    private val questionWaitStreams = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val queuedReplyCalls = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val continuedQuestionCalls = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private val userQuestionProjections = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    @Volatile private var liveCarrier: HarnessTransport? = null
    private val remoteEvents = HarnessRemoteEvents()

    fun respondApproval(approval: PendingApproval, outcome: String) {
        require(outcome == "allowed-once" || outcome == "rejected")
        val (client, carrier, connection) = synchronized(muxLock) {
            val currentClient = eventClientId ?: throw IOException("Approval connection has expired")
            val currentConnection = liveConnection ?: throw IOException("Approval connection has expired")
            if (currentClient != approval.rpcId || !remoteEvents.reserve(currentClient, approval.approvalId, approval.sessionId, "approval", approval.generation)) {
                throw IOException("Approval has already been resolved")
            }
            Triple(currentClient, approvalCarriers[approval.approvalId] ?: liveCarrier
                ?: throw IOException("Approval connection has expired"), currentConnection)
        }
        try {
            remoteValue("\$events/result", eventResult(client, approval.approvalId,
                JSONObject().put("kind", "result").put("value", outcome)), carrier)
            synchronized(muxLock) {
                if (remoteEvents.currentGeneration() != approval.generation || eventClientId != client || liveConnection !== connection) {
                    throw IOException("Approval connection has expired")
                }
                // A Host cancel can race the result acknowledgement and remove the row first.
                if (approvals[approval.approvalId] == approval.sessionId && approvalCarriers[approval.approvalId] === carrier) {
                    approvals.remove(approval.approvalId)
                    approvalCarriers.remove(approval.approvalId)
                    remoteEvents.settle(approval.approvalId, approval.generation)
                }
            }
        } catch (error: Exception) {
            remoteEvents.unreserve(approval.approvalId, approval.generation)
            throw error
        }
    }

    fun respondQuestion(question: PendingQuestion, answers: List<UserQuestionAnswerItem>) {
        validateQuestionAnswers(question.questions, answers)
        val generation = remoteEvents.currentGeneration()
        if (question.generation != 0L && question.generation != generation) throw IOException("Question connection has expired")
        val eventId = question.eventId
        if (eventId != null) {
            val client = question.rpcId ?: throw IOException("Question connection has expired")
            val (carrier, connection) = synchronized(muxLock) {
                val currentConnection = liveConnection ?: throw IOException("Question connection has expired")
                if (eventClientId != client || !remoteEvents.reserve(client, eventId, question.sessionId, "question", question.generation)) {
                    throw IOException("Question has already been resolved")
                }
                (questionCarriers[eventId] ?: liveCarrier ?: throw IOException("Question connection has expired")) to currentConnection
            }
            try {
                remoteValue("\$events/result", eventResult(client, eventId,
                    JSONObject().put("kind", "result").put("value", questionAnswersJson(answers))), carrier)
                synchronized(muxLock) {
                    if (remoteEvents.currentGeneration() != question.generation || eventClientId != client || liveConnection !== connection) {
                        throw IOException("Question connection has expired")
                    }
                    // Host cancellation may resolve and remove the row before this ack returns.
                    val current = pendingQuestions[eventId]
                    if (current?.rpcId == client && current.sessionId == question.sessionId && current.generation == question.generation && questionCarriers[eventId] === carrier) {
                        pendingQuestions.remove(eventId)
                        questionCarriers.remove(eventId)
                        questionWaitStreams.remove(eventId)?.let(connection.router::cancel)
                        remoteEvents.settle(eventId, question.generation)
                    }
                }
            } catch (error: Exception) {
                remoteEvents.unreserve(eventId, question.generation)
                throw error
            }
            return
        }
        if (question.state != "continued" || question.callId == null) throw IOException("Question is no longer answerable")
        val (carrier, connection) = synchronized(muxLock) {
            if (question.generation != 0L && question.generation != remoteEvents.currentGeneration()) throw IOException("Question connection has expired")
            (liveCarrier ?: throw IOException("Question connection has expired")) to (liveConnection ?: throw IOException("Question connection has expired"))
        }
        val result = remoteValue("userQuestions/answer", JSONObject()
            .put("agentId", question.sessionId)
            .put("callId", question.callId)
            .put("answer", questionAnswersJson(answers)), carrier) as? JSONObject
            ?: throw IOException("Invalid question response")
        if (!result.optBoolean("ok")) {
            val error = result.optJSONObject("error") ?: JSONObject()
            throw IOException(error.optString("message", "Question is no longer answerable"))
        }
        // False is authoritative evidence that the continued request already closed.
        if (result.opt("value") != true) throw IOException("Question is no longer answerable")
        synchronized(muxLock) {
            if (liveConnection !== connection || liveCarrier !== carrier) throw IOException("Question connection has expired")
        }
    }

    fun cancelQuestion(question: PendingQuestion) {
        val eventId = question.eventId ?: throw IOException("Continued questions cannot be cancelled remotely")
        val client = question.rpcId ?: throw IOException("Question connection has expired")
        val (carrier, connection) = synchronized(muxLock) {
            val currentConnection = liveConnection ?: throw IOException("Question connection has expired")
            if (eventClientId != client || !remoteEvents.reserve(client, eventId, question.sessionId, "question", question.generation)) {
                throw IOException("Question has already been resolved")
            }
            (questionCarriers[eventId] ?: liveCarrier ?: throw IOException("Question connection has expired")) to currentConnection
        }
        try {
            remoteValue("\$events/result", eventResult(client, eventId, JSONObject()
                .put("kind", "rejected")
                .put("error", JSONObject()
                    .put("name", "UserQuestionError")
                    .put("code", "ASK_CANCELLED")
                    .put("message", "the user cancelled ask_user_question"))),
                carrier)
            synchronized(muxLock) {
                if (remoteEvents.currentGeneration() != question.generation || eventClientId != client || liveConnection !== connection) {
                    throw IOException("Question connection has expired")
                }
                val current = pendingQuestions[eventId]
                if (current?.rpcId == client && current.sessionId == question.sessionId && current.generation == question.generation && questionCarriers[eventId] === carrier) {
                    pendingQuestions.remove(eventId)
                    questionCarriers.remove(eventId)
                    questionWaitStreams.remove(eventId)?.let(connection.router::cancel)
                    remoteEvents.settle(eventId, question.generation)
                }
            }
        } catch (error: Exception) {
            remoteEvents.unreserve(eventId, question.generation)
            throw error
        }
    }

    private fun currentTransport(): HarnessTransport {
        transport?.let { return it() }
        val root = baseUrl()
        return DirectHarnessTransport(root, accessToken(root), cookieHeader(root))
    }
    private fun newConnection() = HarnessRemoteConnection(currentTransport())
    private val muxLock = Any()
    private var muxGeneration = 0L
    fun exportCarrier(): HarnessTransport = currentTransport()
    fun exportSession(sessionId: String, sink: java.io.OutputStream) = currentTransport().export(sessionId, true, sink)
    private fun firstStream(endpoint: String, args: JSONObject): JSONObject {
        val connection = newConnection()
        try { return connection.first(endpoint, args) } finally { connection.close() }
    }
    private fun followArgs(address: HarnessSessionAddress) = JSONObject().put("request", JSONObject()
        .put("address", address.toJson())
        .put("assistantStream", true).put("maxMessages", 80))

    private fun sessionHistory(sessionId: String): JSONObject {
        val address = resolveSessionAddress(sessionId)
        if (desiredSession == null) desiredSession = sessionId
        val connection = liveConnection
        if (connection != null && desiredSession == sessionId) synchronized(this) {
            if (followSession != sessionId || followState == null) {
                followId?.let { connection.router.cancel(it) }
                followSession = sessionId
                val state = HarnessSessionState(address)
                followState = state
                followId = connection.router.open("session/follow", followArgs(address), ended = { connection.abort(it ?: IOException("Required Harness stream ended")) }) { item ->
                    if (liveConnection === connection && followState === state) {
                        state.accept(item)
                        liveSignal?.invoke(JSONObject().put("type", "session/event").put("sessionId", sessionId))
                    }
                }
            }
            followState?.takeIf { it.isReady() }?.let { return it.history() }
        }
        val state = HarnessSessionState()
        state.accept(firstStream("session/follow", followArgs(address)))
        return state.history()
    }

    fun streamMux(onFrame: (JSONObject) -> Unit) {
        val admission = synchronized(muxLock) { ++muxGeneration }
        val carrier = currentTransport()
        val connection = HarnessRemoteConnection(carrier)
        synchronized(muxLock) {
            if (admission != muxGeneration) { connection.close(); return }
            liveConnection = connection
            liveCarrier = carrier
            runningBySession.clear()
            liveSignal = onFrame
        }
        onFrame(JSONObject().put("type", "session/list-changed"))
        try {
            desiredSession?.let { sessionId ->
                if (!isSessionAddressResolved(sessionId)) runCatching { sessions() }
                if (isSessionAddressResolved(sessionId)) sessionHistory(sessionId)
            }
            connection.router.open("session/control", JSONObject(), ended = { connection.abort(it ?: IOException("Required Harness stream ended")) }) { item ->
                if (liveConnection !== connection) return@open
                if (item.optString("type") == "baseline") {
                    val baselines = item.getJSONObject("value").getJSONObject("projections")
                    for (sessionId in baselines.keys()) {
                        val baseline = baselines.optJSONObject(sessionId) ?: continue
                        val values = baseline.optJSONObject("values") ?: continue
                        if (sessionId == followSession) values.keys().forEach { key ->
                            followState?.projection(key, values.opt(key), baseline.optLong("asOfSeq"))
                        }
                        emitQuestionProjection(sessionId, values.optJSONObject("userQuestions"), values.optJSONObject("inbox"), onFrame)
                    }
                }
                if (item.optString("type") == "projection") {
                    val sessionId = item.optString("sessionId")
                    val key = item.optString("key")
                    val value = item.opt("value")
                    if (followSession == sessionId) followState?.projection(key, value, item.optLong("seq"))
                    if (key == "userQuestions") emitQuestionProjection(sessionId, value as? JSONObject, null, onFrame)
                    if (key == "inbox") emitQuestionProjection(sessionId, null, value as? JSONObject, onFrame)
                    onFrame(JSONObject().put("type", "session/projection").put("sessionId", sessionId))
                }
            }
            connection.router.open("\$events", JSONObject(), ended = { connection.abort(it ?: IOException("Required Harness stream ended")) }) { item ->
                if (liveConnection !== connection) return@open
                when (item.optString("type")) {
                    "ready" -> { eventClientId = item.getString("clientId"); remoteEvents.ready(item.getString("clientId")) }
                    "emit" -> {
                        val args = item.optJSONArray("args") ?: return@open
                        when (item.optString("event")) {
                            "api-session/status" -> {
                                runningBySession[args.getString(0)] = args.getBoolean(1)
                                onFrame(JSONObject().put("type", "session/status").put("sessionId", args.getString(0)).put("running", args.getBoolean(1)))
                            }
                            "api-session/added" -> {
                                args.optJSONObject(0)?.let { summary ->
                                    runCatching { parseSessionSummary(summary) }.getOrNull()?.let {
                                        sessionMetadata[it.id] = it
                                        if (it.id == desiredSession) runCatching { sessionHistory(it.id) }
                                    }
                                }
                                onFrame(JSONObject().put("type", "session/list-changed"))
                            }
                            "api-session/removed" -> {
                                args.optString(0).takeIf(String::isNotBlank)?.let(sessionMetadata::remove)
                                onFrame(JSONObject().put("type", "session/list-changed"))
                            }
                            "api-session/activity" -> onFrame(JSONObject().put("type", "session/list-changed"))
                        }
                    }
                    "waterfall" -> {
                        val eventId = item.optString("eventId")
                        val client = eventClientId ?: return@open
                        val sessionId = item.optNullableString("agentId")?.takeIf(String::isNotBlank)
                        val request = item.optJSONObject("request")
                        val generation = remoteEvents.currentGeneration()
                        if (eventId.isBlank() || sessionId == null || request == null) {
                            respondNext(connection, carrier, client, eventId, generation)
                        } else when (item.optString("event")) {
                            "approval/request" -> {
                                approvals[eventId] = sessionId
                                approvalCarriers[eventId] = carrier
                                remoteEvents.offer(eventId, sessionId, "approval", generation)
                                val frame = JSONObject(request.toString()).put("type", "approval/requested")
                                    .put("sessionId", sessionId).put("approvalId", eventId).put("_rpcId", client)
                                    .put("generation", generation)
                                request.optJSONObject("displayReason")?.let { frame.put("displayReason", it) }
                                onFrame(frame)
                            }
                            "user-questions/request" -> receiveQuestionWaterfall(
                                connection, carrier, client, generation, eventId, sessionId, request, onFrame,
                            )
                            else -> respondNext(connection, carrier, client, eventId, generation)
                        }
                    }
                    "cancel" -> {
                        val eventId = item.getString("eventId")
                        remoteEvents.settle(eventId)
                        approvals.remove(eventId)?.let { onFrame(JSONObject().put("type", "approval/resolved").put("sessionId", it).put("approvalId", eventId)) }
                        approvalCarriers.remove(eventId)
                        pendingQuestions.remove(eventId)?.let { onFrame(JSONObject().put("type", "question/resolved").put("sessionId", it.sessionId).put("questionId", it.questionId).put("eventId", eventId)) }
                        questionCarriers.remove(eventId)
                        questionWaitStreams.remove(eventId)?.let(connection.router::cancel)
                    }
                }
            }
            connection.await()
        } finally {
            synchronized(muxLock) { if (liveConnection === connection) {
                liveConnection = null; liveCarrier = null; liveSignal = null; followState = null; followSession = null; followId = null; eventClientId = null
                approvals.entries.toList().forEach { onFrame(JSONObject().put("type", "approval/resolved").put("sessionId", it.value).put("approvalId", it.key)) }
                pendingQuestions.values.forEach { onFrame(JSONObject().put("type", "question/resolved").put("sessionId", it.sessionId).put("questionId", it.questionId).put("eventId", it.eventId).put("callId", it.callId)) }
                approvals.clear(); approvalCarriers.clear(); pendingQuestions.clear(); questionCarriers.clear(); questionWaitStreams.clear()
                continuedQuestionCalls.clear(); queuedReplyCalls.clear(); userQuestionProjections.clear()
                remoteEvents.clear()
            } }
            connection.close()
        }
    }
    private fun respondNext(connection: HarnessRemoteConnection, carrier: HarnessTransport, client: String, eventId: String, generation: Long) {
        if (eventId.isBlank()) return
        eventWorker.execute {
            if (liveConnection !== connection || remoteEvents.currentGeneration() != generation) return@execute
            runCatching { remoteValue("\$events/result", eventResult(client, eventId, JSONObject().put("kind", "next")), carrier) }
        }
    }

    private fun receiveQuestionWaterfall(
        connection: HarnessRemoteConnection,
        carrier: HarnessTransport,
        client: String,
        generation: Long,
        eventId: String,
        sessionId: String,
        request: JSONObject,
        onFrame: (JSONObject) -> Unit,
    ) {
        val questions = parseUserQuestions(request.opt("questions"))
        if (questions == null) {
            respondNext(connection, carrier, client, eventId, generation)
            return
        }
        val wait = request.optJSONObject("wait")
        val callId = wait?.optNullableString("callId")?.takeIf(String::isNotBlank)
        val timed = wait?.optBoolean("timed") == true && callId != null
        val pending = PendingQuestion(client, sessionId, callId ?: eventId, generation, eventId, callId, questions)
        remoteEvents.offer(eventId, sessionId, "question", generation)
        questionCarriers[eventId] = carrier
        pendingQuestions[eventId] = pending
        val publish: (Long?) -> Unit = { remainingMs ->
            if (liveConnection === connection && remoteEvents.valid(client, eventId, sessionId, "question", generation)) {
                val shown = if (remainingMs == null) pending else pending.copy(deadline = System.currentTimeMillis() + remainingMs)
                pendingQuestions[eventId] = shown
                onFrame(questionFrame("question/requested", shown).apply {
                    if (remainingMs != null) put("remainingMs", remainingMs)
                })
            }
        }
        if (!timed) {
            publish(null)
            return
        }
        try {
            val streamId = connection.router.open(
                "userQuestions/attachWait",
                JSONObject().put("agentId", sessionId).put("callId", callId),
                ended = { _ ->
                    if (liveConnection === connection && !remoteEvents.responding(eventId, generation)) {
                        delegateQuestion(connection, carrier, client, eventId, sessionId, generation, onFrame)
                    }
                },
            ) { opening ->
                val remainingMs = opening.optLong("remainingMs", -1).takeIf { it >= 0 } ?: return@open
                publish(remainingMs)
            }
            if (remoteEvents.valid(client, eventId, sessionId, "question", generation)) {
                questionWaitStreams[eventId] = streamId
            } else {
                connection.router.cancel(streamId)
            }
        } catch (_: Exception) {
            pendingQuestions.remove(eventId)
            questionCarriers.remove(eventId)
            remoteEvents.settle(eventId, generation)
            respondNext(connection, carrier, client, eventId, generation)
        }
    }

    private fun questionFrame(type: String, question: PendingQuestion): JSONObject = JSONObject()
        .put("type", type)
        .put("rpcId", question.rpcId)
        .put("_rpcId", question.rpcId)
        .put("sessionId", question.sessionId)
        .put("questionId", question.questionId)
        .put("eventId", question.eventId)
        .put("callId", question.callId)
        .put("questions", JSONArray().apply {
            question.questions.forEach { q ->
                val item = JSONObject().put("id", q.id).put("question", q.question)
                    .put("multiSelect", q.multiSelect)
                    .put("options", JSONArray().apply {
                        q.options.forEach { option -> put(JSONObject().put("label", option.label).put("description", option.description)) }
                    })
                q.detail?.let { item.put("detail", it) }
                q.header?.let { item.put("header", it) }
                if (q.intentKind != null) item.put("intent", JSONObject()
                    .put("kind", q.intentKind).put("approve", q.approveLabel).put("callId", q.intentCallId))
                put(item)
            }
        })
        .put("state", question.state)
        .put("generation", question.generation)
        .apply { question.deadline?.let { put("deadline", it) } }

    private fun delegateQuestion(
        connection: HarnessRemoteConnection,
        carrier: HarnessTransport,
        client: String,
        eventId: String,
        sessionId: String,
        generation: Long,
        onFrame: (JSONObject) -> Unit,
    ) {
        if (!remoteEvents.reserve(client, eventId, sessionId, "question", generation)) return
        val question = pendingQuestions.remove(eventId)
        questionCarriers.remove(eventId)
        questionWaitStreams.remove(eventId)
        question?.let { onFrame(JSONObject().put("type", "question/resolved").put("sessionId", it.sessionId).put("questionId", it.questionId).put("eventId", eventId).put("callId", it.callId)) }
        eventWorker.execute {
            try {
                if (liveConnection === connection && remoteEvents.currentGeneration() == generation) {
                    remoteValue("\$events/result", eventResult(client, eventId, JSONObject().put("kind", "next")), carrier)
                }
            } catch (error: Exception) {
                // The Host can cancel the waterfall after ending attachWait but before this ack arrives.
                // That stale result is expected; other failures must also stay on this worker thread.
                if (error !is RemoteFailure || error.code != "events/stale-result") {
                    val detail = (error as? RemoteFailure)?.code ?: error.javaClass.simpleName
                    Log.w("HarnessApi", "Timed question continuation acknowledgement failed ($detail)")
                }
            } finally {
                synchronized(muxLock) {
                    if (remoteEvents.currentGeneration() == generation && eventClientId == client && liveConnection === connection) {
                        remoteEvents.settle(eventId, generation)
                    }
                }
            }
        }
    }

    private fun emitQuestionProjection(sessionId: String, questions: JSONObject?, inbox: JSONObject?, onFrame: (JSONObject) -> Unit) {
        if (questions != null) userQuestionProjections[sessionId] = questions
        if (inbox != null) queuedReplyCalls[sessionId] = queuedQuestionReplyIds(inbox)
        val projection = userQuestionProjections[sessionId] ?: return
        val queued = queuedReplyCalls[sessionId].orEmpty()
        val active = projection.optJSONArray("active")
        val visible = LinkedHashMap<String, PendingQuestion>()
        if (active != null) for (index in 0 until active.length()) {
            val row = active.optJSONObject(index) ?: continue
            if (row.optString("state") != "continued") continue
            val callId = row.optNullableString("callId")?.takeIf(String::isNotBlank) ?: continue
            if (callId in queued) continue
            val items = parseUserQuestions(row.opt("questions")) ?: continue
            visible[callId] = PendingQuestion(null, sessionId, callId, 0, callId = callId, questions = items, state = "continued")
        }
        val previous = continuedQuestionCalls.computeIfAbsent(sessionId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        for (callId in previous.toSet() - visible.keys) {
            onFrame(JSONObject().put("type", "question/resolved").put("sessionId", sessionId).put("questionId", callId).put("callId", callId))
            previous.remove(callId)
        }
        for ((callId, question) in visible) {
            onFrame(questionFrame("question/projection", question))
            previous.add(callId)
        }
    }

    private val eventWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun closeMux() {
        val (old, notify, rows) = synchronized(muxLock) {
            muxGeneration++
            val connection = liveConnection
            val notify = liveSignal
            val rows = approvals.entries.map { entry -> JSONObject().put("type", "approval/resolved").put("sessionId", entry.value).put("approvalId", entry.key) } +
                pendingQuestions.values.map { question -> JSONObject().put("type", "question/resolved").put("sessionId", question.sessionId).put("questionId", question.questionId).put("eventId", question.eventId).put("callId", question.callId) }
            liveConnection = null; liveCarrier = null; liveSignal = null; followState = null; followSession = null; followId = null; eventClientId = null
            approvals.clear(); approvalCarriers.clear(); pendingQuestions.clear(); questionCarriers.clear(); questionWaitStreams.clear()
            continuedQuestionCalls.clear(); queuedReplyCalls.clear(); userQuestionProjections.clear(); remoteEvents.clear(); runningBySession.clear()
            Triple(connection, notify, rows)
        }
        if (notify != null) rows.forEach { notify.invoke(it) }
        old?.close()
    }
    fun hasLiveConnection() = liveConnection != null

    private fun call(method: String, payload: JSONObject): JSONObject {
        return remoteValue(method, rc2Arguments(method, payload)) as? JSONObject ?: JSONObject()
    }

    private fun callRemote(endpoint: String, request: JSONObject): JSONObject = remoteValue(endpoint, JSONObject().put("request", request)) as JSONObject

    private fun remoteValue(method: String, args: JSONObject, carrier: HarnessTransport = currentTransport()): Any? {
        val rpcId = UUID.randomUUID().toString()
        val envelope = HarnessRpc.request(rpcId, method, args)
        return HarnessRpc.value(rpcId, carrier.rpc(envelope))
    }

    private fun eventResult(client: String, eventId: String, outcome: JSONObject) = JSONObject()
        .put("clientId", client).put("eventId", eventId).put("outcome", outcome)

}

internal fun parseContinuedQuestions(values: JSONObject?, sessionId: String, inbox: JSONObject?): List<PendingQuestion> {
    val active = values?.optJSONArray("active") ?: return emptyList()
    val queued = inbox?.let(::queuedQuestionReplyIds).orEmpty()
    val result = ArrayList<PendingQuestion>()
    for (index in 0 until active.length()) {
        val row = active.optJSONObject(index) ?: continue
        if (row.optString("state") != "continued") continue
        val callId = row.optNullableString("callId")?.takeIf(String::isNotBlank) ?: continue
        if (callId in queued) continue
        val questions = parseUserQuestions(row.opt("questions")) ?: continue
        result += PendingQuestion(
            rpcId = null,
            sessionId = sessionId,
            questionId = callId,
            generation = 0,
            callId = callId,
            questions = questions,
            state = "continued",
        )
    }
    return result
}

private fun queuedQuestionReplyIds(inbox: JSONObject): Set<String> = buildSet {
    for (target in listOf("next-step", "next-turn")) {
        val messages = inbox.optJSONArray(target) ?: continue
        for (index in 0 until messages.length()) {
            val source = messages.optJSONObject(index)?.optJSONObject("source") ?: continue
            if (source.optString("kind") == "user-question-reply") {
                source.optNullableString("callId")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
}

internal enum class HarnessHttpFailure {
    AUTHENTICATION,
    SETTINGS_ACCESS_FORBIDDEN,
    HTTP,
}

internal fun classifyHarnessHttpFailure(method: String, status: Int): HarnessHttpFailure? = when {
    status in 200..299 -> null
    status in 300..399 || status == 401 -> HarnessHttpFailure.AUTHENTICATION
    status == 403 && method == "settings/describe" -> HarnessHttpFailure.SETTINGS_ACCESS_FORBIDDEN
    status == 403 -> HarnessHttpFailure.AUTHENTICATION
    else -> HarnessHttpFailure.HTTP
}

internal fun commandExecutionPayload(sessionId: String, command: String): JSONObject = JSONObject().put(
    "args",
    JSONObject()
        .put("agentId", sessionId)
        .put("line", command),
)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

internal fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

internal fun rc2Arguments(endpoint: String, payload: JSONObject): JSONObject = when (endpoint) {
    "session/list" -> JSONObject().put("_request", payload)
    "session/page", "session/create", "session/projections", "session/selectModel", "session/rename", "session/fork", "session/prompt", "session/cancel", "workspace/create" -> JSONObject().put("request", payload)
    "agentPresets/select" -> JSONObject().put("agentId", payload.getString("sessionId")).put("agentPreset", payload.getString("agentPreset"))
    "commands/execute" -> payload.getJSONObject("args").put("submittedAttachments", JSONArray())
    else -> payload
}

internal fun isAccessChallenge(status: Int, location: String?, challenge: String?): Boolean =
    status in 300..399 || location?.contains("cloudflareaccess.com") == true || challenge?.contains("Bearer", ignoreCase = true) == true
