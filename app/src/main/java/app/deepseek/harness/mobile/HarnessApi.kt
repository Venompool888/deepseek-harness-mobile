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
    data class Session(
        val id: String,
        val title: String?,
        val cwd: String?,
        val agentPreset: String?,
        val updatedAt: Long,
        val running: Boolean,
        val blank: Boolean,
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
    )

    data class PendingApproval(
        val rpcId: String,
        val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val callId: String?,
        val reason: String?,
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
        return value.getJSONArray("items").objects().map { item ->
            val values = item.optJSONObject("projections")
                ?.optJSONObject("values")
            Session(
                id = item.getString("sessionId"),
                title = values?.optNullableString("title"),
                cwd = item.optNullableString("cwd"),
                agentPreset = values?.optNullableString("agentPreset") ?: item.optNullableString("agentPreset"),
                updatedAt = item.optLong("updatedAt"),
                running = item.optBoolean("running"),
                blank = item.optBoolean("blank"),
            )
        }
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
        return call("session/create", payload).getString("sessionId")
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

    fun forkSession(sessionId: String, atSeq: Long? = null): String = call(
        "session/fork",
        JSONObject().put("sessionId", sessionId).apply {
            if (atSeq != null) put("atSeq", atSeq)
        },
    ).getString("sessionId")

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
    private val remoteEvents = HarnessRemoteEvents()

    fun respondApproval(approval: PendingApproval, outcome: String) {
        require(outcome == "allowed-once" || outcome == "rejected")
        val client = eventClientId ?: throw IOException("Approval connection has expired")
        if (!remoteEvents.valid(approval.rpcId, approval.approvalId, approval.sessionId) || client != approval.rpcId) throw IOException("Approval has already been resolved")
        remoteValue("\$events/result", JSONObject().put("clientId", client).put("eventId", approval.approvalId)
            .put("outcome", JSONObject().put("kind", "result").put("value", outcome)))
        approvals.remove(approval.approvalId)
        remoteEvents.settle(approval.approvalId)
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
    private fun followArgs(sessionId: String) = JSONObject().put("request", JSONObject()
        .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
        .put("assistantStream", true).put("maxMessages", 80))

    private fun sessionHistory(sessionId: String): JSONObject {
        if (desiredSession == null) desiredSession = sessionId
        val connection = liveConnection
        if (connection != null && desiredSession == sessionId) synchronized(this) {
            if (followSession != sessionId || followState == null) {
                followId?.let { connection.router.cancel(it) }
                followSession = sessionId
                val state = HarnessSessionState()
                followState = state
                followId = connection.router.open("session/follow", followArgs(sessionId), ended = { connection.abort(it ?: IOException("Required Harness stream ended")) }) { item ->
                    if (liveConnection === connection && followState === state) {
                        state.accept(item)
                        liveSignal?.invoke(JSONObject().put("type", "session/event").put("sessionId", sessionId))
                    }
                }
            }
            followState?.takeIf { it.isReady() }?.let { return it.history() }
        }
        val state = HarnessSessionState()
        state.accept(firstStream("session/follow", followArgs(sessionId)))
        return state.history()
    }

    fun streamMux(onFrame: (JSONObject) -> Unit) {
        val admission = synchronized(muxLock) { ++muxGeneration }
        val carrier = currentTransport()
        val connection = HarnessRemoteConnection(carrier)
        synchronized(muxLock) {
            if (admission != muxGeneration) { connection.close(); return }
            liveConnection = connection
            runningBySession.clear()
            liveSignal = onFrame
        }
        onFrame(JSONObject().put("type", "session/list-changed"))
        try {
            desiredSession?.let { sessionHistory(it) }
            connection.router.open("session/control", JSONObject(), ended = { connection.abort(it ?: IOException("Required Harness stream ended")) }) { item ->
                if (liveConnection !== connection) return@open
                if (item.optString("type") == "baseline") {
                    val baselines = item.getJSONObject("value").getJSONObject("projections")
                    desiredSession?.let { selected -> baselines.optJSONObject(selected)?.let { baseline ->
                        baseline.getJSONObject("values").keys().forEach { key -> followState?.projection(key, baseline.getJSONObject("values").opt(key), baseline.optLong("asOfSeq")) }
                    } }
                }
                if (item.optString("type") == "projection") {
                    val sessionId = item.optString("sessionId")
                    if (followSession == sessionId) followState?.projection(item.optString("key"), item.opt("value"), item.optLong("seq"))
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
                            "api-session/added" -> onFrame(JSONObject().put("type", "session/list-changed"))
                            "api-session/removed" -> onFrame(JSONObject().put("type", "session/list-changed"))
                            "api-session/activity" -> onFrame(JSONObject().put("type", "session/list-changed"))
                        }
                    }
                    "waterfall" -> {
                        val eventId = item.getString("eventId")
                        val client = eventClientId ?: return@open
                        if (item.optString("event") == "approval/request" && item.optString("agentId") == desiredSession) {
                            val sessionId = item.getString("agentId")
                            approvals[eventId] = sessionId
                            remoteEvents.offer(eventId, sessionId)
                            onFrame(JSONObject(item.getJSONObject("request").toString()).put("type", "approval/requested")
                                .put("sessionId", sessionId).put("approvalId", eventId).put("_rpcId", client))
                        } else {
                            eventWorker.execute { if (liveConnection === connection) runCatching { remoteValue("\$events/result", JSONObject().put("clientId", client).put("eventId", eventId).put("outcome", JSONObject().put("kind", "next")), carrier) } }
                        }
                    }
                    "cancel" -> {
                        val eventId = item.getString("eventId")
                        remoteEvents.settle(eventId)
                        approvals.remove(eventId)?.let { onFrame(JSONObject().put("type", "approval/resolved").put("sessionId", it).put("approvalId", eventId)) }
                    }
                }
            }
            connection.await()
        } finally {
            synchronized(muxLock) { if (liveConnection === connection) {
                liveConnection = null; liveSignal = null; followState = null; followSession = null; followId = null; eventClientId = null
                approvals.entries.toList().forEach { onFrame(JSONObject().put("type", "approval/resolved").put("sessionId", it.value).put("approvalId", it.key)) }
                approvals.clear()
                remoteEvents.clear()
            } }
            connection.close()
        }
    }
    private val eventWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    fun closeMux() {
        val old = synchronized(muxLock) {
            muxGeneration++
            val connection = liveConnection
            liveConnection = null; liveSignal = null; followState = null; followSession = null; followId = null; eventClientId = null
            approvals.clear(); remoteEvents.clear(); runningBySession.clear()
            connection
        }
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
