package cool.rin.deepseekremote

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
    private val cookieHeader: (String) -> String? = { url ->
        CookieManager.getInstance().getCookie(url)
    },
) {
    private val streamClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
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
    )

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

    data class SessionExport(val url: String, val cookie: String?)

    class AuthenticationRequired : IOException("Cloudflare Access authentication is required")

    class RemoteFailure(val code: String, message: String) : IOException(message)

    fun sessions(): List<Session> {
        val value = call("session.list", JSONObject())
        return value.getJSONArray("items").objects().map { item ->
            val values = item.optJSONObject("projections")
                ?.optJSONObject("values")
            Session(
                id = item.getString("sessionId"),
                title = values?.optNullableString("title"),
                cwd = item.optNullableString("cwd"),
                agentPreset = item.optNullableString("agentPreset"),
                updatedAt = item.optLong("updatedAt"),
                running = item.optBoolean("running"),
                blank = item.optBoolean("blank"),
            )
        }
    }

    fun workspaces(): List<Workspace> {
        val value = call("workspace.list", JSONObject())
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
        val value = call("host.listDirectory", JSONObject().apply {
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

    fun createDirectory(path: String, name: String): String = call(
        "host.createDirectory",
        JSONObject().put("path", path).put("name", name),
    ).getString("path")

    fun createWorkspace(path: String): Workspace {
        val item = call("workspace.create", JSONObject().put("path", path)).getJSONObject("workspace")
        return Workspace(
            id = item.getString("workspaceId"),
            title = item.getString("title"),
            path = item.getString("path"),
            sessionIds = item.getJSONArray("sessionIds").strings(),
        )
    }

    fun history(sessionId: String): History {
        val value = call("session.history", JSONObject().apply {
            put("sessionId", sessionId)
            put("maxMessages", 80)
        })
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
        val value = call("session.models", JSONObject().put("sessionId", sessionId))
        val current = value.getJSONObject("current")
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
            routable = value.getBoolean("routable"),
            items = items,
        )
    }

    fun createSession(workspaceId: String?, agentPreset: String? = null): String {
        val payload = JSONObject()
        if (workspaceId != null) payload.put("workspaceId", workspaceId)
        if (agentPreset != null) payload.put("agentPreset", agentPreset)
        return call("session.create", payload).getString("sessionId")
    }

    fun agentPresets(): List<AgentPreset> {
        val value = call("agentPreset.list", JSONObject())
        return value.getJSONArray("presets").objects().map {
            AgentPreset(
                id = it.getString("id"),
                name = it.optString("name", it.getString("id")),
                description = it.optNullableString("description"),
                trust = it.getString("trust"),
                isDefault = it.optBoolean("isDefault"),
                broken = it.optNullableString("broken"),
            )
        }
    }

    fun selectAgentPreset(sessionId: String, agentPreset: String) {
        call("agentPreset.select", JSONObject().put("sessionId", sessionId).put("agentPreset", agentPreset))
    }

    fun renameSession(sessionId: String, title: String) {
        call("session.rename", JSONObject().put("sessionId", sessionId).put("title", title))
    }

    fun forkSession(sessionId: String): String = call(
        "session.fork",
        JSONObject().put("sessionId", sessionId),
    ).getString("sessionId")

    fun selectModel(sessionId: String, model: Model, effort: String?) {
        call("session.selectModel", JSONObject().apply {
            put("sessionId", sessionId)
            put("provider", model.provider)
            put("model", model.id)
            if (effort != null) put("reasoningEffort", effort)
        })
    }

    fun prompt(sessionId: String, content: JSONArray, mode: String = "queue") {
        call("session.prompt", JSONObject().apply {
            put("sessionId", sessionId)
            put("mode", mode)
            put("content", content)
            put("clientTimeZone", java.util.TimeZone.getDefault().id)
        })
    }

    fun command(sessionId: String, command: String) {
        prompt(
            sessionId,
            JSONArray().put(JSONObject().put("type", "text").put("text", command)),
            "queue",
        )
    }

    fun prepareSessionExport(sessionId: String): SessionExport {
        val root = baseUrl().trimEnd('/')
        val encodedId = URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        val address = "$root/api/session.export?sessionId=$encodedId&includeDescendants=true"
        val cookie = cookieHeader(root)?.takeIf { it.isNotBlank() }
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Accept", "application/zip")
            setRequestProperty("User-Agent", "DeepSeekHarnessMobile/${BuildConfig.VERSION_NAME}")
            cookie?.let { setRequestProperty("Cookie", it) }
        }
        try {
            val status = connection.responseCode
            if (status in 300..399 || status == 401 || status == 403) throw AuthenticationRequired()
            if (status !in 200..299) throw IOException("Harness HTTP $status")
            return SessionExport(address, cookie)
        } finally {
            connection.disconnect()
        }
    }

    fun cancel(sessionId: String) {
        call("session.cancel", JSONObject().put("sessionId", sessionId))
    }

    fun respondApproval(approval: PendingApproval, outcome: String) {
        require(outcome == "allowed-once" || outcome == "rejected")
        val body = JSONObject().apply {
            put("type", "client-response")
            put("rpcId", approval.rpcId)
            put("result", JSONObject().apply {
                put("ok", true)
                put("value", JSONObject().apply {
                    put("sessionId", approval.sessionId)
                    put("approvalId", approval.approvalId)
                    put("outcome", outcome)
                })
            })
        }
        val root = baseUrl()
        val connection = (URL("$root/api/respond")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DeepSeekHarnessMobile/${BuildConfig.VERSION_NAME}")
            cookieHeader(root)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 300..399 || status == 401 || status == 403) throw AuthenticationRequired()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Harness HTTP $status")
            val receipt = JSONObject(text)
            if (!receipt.optBoolean("accepted")) {
                throw RemoteFailure("approval-${receipt.optString("reason", "rejected")}", "Approval response was not accepted")
            }
        } finally {
            connection.disconnect()
        }
    }

    @Volatile
    private var muxSocket: WebSocket? = null

    /** Blocks while consuming the Harness WebSocket mux; call from a dedicated worker. */
    fun streamMux(onFrame: (JSONObject) -> Unit) {
        val stopped = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val root = baseUrl()
        val wsBase = root.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val request = Request.Builder()
            .url("$wsBase/api/events.mux")
            .header("Origin", root)
            .header("User-Agent", "DeepSeekHarnessMobile/${BuildConfig.VERSION_NAME}")
            .apply { cookieHeader(root)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
            .build()
        val socket = streamClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("HarnessStream", "mux connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val envelope = JSONObject(text)
                    envelope.optJSONObject("payload")?.let { payload ->
                        envelope.optNullableString("rpcId")?.let { payload.put("_rpcId", it) }
                        onFrame(payload)
                    }
                } catch (error: Exception) {
                    Log.w("HarnessStream", "dropping malformed mux frame", error)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopped.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set(t)
                stopped.countDown()
            }
        })
        muxSocket = socket
        try {
            stopped.await()
            failure.get()?.let { throw IOException("Harness event stream failed", it) }
        } finally {
            if (muxSocket === socket) muxSocket = null
            socket.cancel()
        }
    }

    fun closeMux() {
        muxSocket?.cancel()
        muxSocket = null
    }

    private fun call(method: String, payload: JSONObject): JSONObject {
        val rpcId = UUID.randomUUID().toString()
        val envelope = JSONObject().apply {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", method)
            put("payload", payload)
        }
        val root = baseUrl()
        val connection = (URL("$root/api/$method")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = if (method == "session.prompt") 45_000 else 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DeepSeekHarnessMobile/${BuildConfig.VERSION_NAME}")
            cookieHeader(root)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        try {
            connection.outputStream.use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 300..399 || status == 401 || status == 403) throw AuthenticationRequired()
            val contentType = connection.contentType.orEmpty().lowercase()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Harness HTTP $status")
            if (!contentType.contains("application/json") || text.trimStart().startsWith("<")) {
                throw AuthenticationRequired()
            }
            val response = JSONObject(text)
            if (response.optString("rpcId") != rpcId) throw IOException("Harness response correlation failed")
            val result = response.getJSONObject("result")
            if (!result.getBoolean("ok")) {
                val error = result.getJSONObject("error")
                throw RemoteFailure(error.optString("code", "remote-error"), error.optString("message", "Harness request failed"))
            }
            return result.optJSONObject("value") ?: JSONObject()
        } finally {
            connection.disconnect()
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

internal fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)
