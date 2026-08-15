package cool.rin.deepseekremote

import org.json.JSONArray
import org.json.JSONObject

internal data class ChatMessage(
    val key: String,
    val role: Role,
    val text: String,
    val time: Long,
    val pending: Boolean = false,
    val title: String? = null,
    val detail: String? = null,
    val callId: String? = null,
    val state: State = State.OK,
    val activityKind: ActivityKind? = null,
) {
    enum class Role { USER, ASSISTANT, REASONING, TOOL, ACTIVITY, NOTICE }
    enum class State { RUNNING, OK, ERROR, STOPPED }
    enum class ActivityKind { THINK, TERMINAL, READ, SEARCH, WRITE, TODO, CONTEXT, RETRY, ERROR, WARNING, UNKNOWN }
}

internal object ChatProjection {
    fun fromHistory(entries: JSONArray): List<ChatMessage> {
        val events = entries.objects().map { it.getJSONObject("event") }
        val compactions = compactionEvidence(events)
        val commands = commandEvidence(events)
        val retries = retryEvidence(events)
        val dispatchedTools = dispatchEvidence(events)
        val retryTurns = events.filter { it.optString("type").startsWith("llm/retry") }
            .mapNotNull { it.optJSONObject("data")?.optInt("turn", -1)?.takeIf { turn -> turn >= 0 } }
            .toSet()
        val compactCommandIds = compactions.values.mapNotNull { it.sourceCommandId }.toSet() +
            commands.values.filter { it.name == "compact" }.map { it.id }
        val messages = mutableListOf<ChatMessage>()
        val streaming = linkedMapOf<String, StreamingBlock>()
        val streamingTimes = mutableMapOf<String, Long>()
        val emittedCommands = mutableSetOf<String>()
        val emittedRetries = mutableSetOf<String>()

        for (event in events) {
            val type = event.getString("type")
            val data = event.optJSONObject("data") ?: continue
            val seq = event.optLong("seq")
            val time = event.optLong("time")
            when (type) {
                "user/message" -> {
                    val source = data.optJSONObject("source")
                    val text = visibleText(data.optJSONArray("content"))
                    if (source?.optString("kind") == "user") {
                        if (text.isNotBlank()) messages += ChatMessage("event-$seq", ChatMessage.Role.USER, text, time)
                    } else if (source?.optString("kind") == "plugin" && source.optString("plugin") == "compact") {
                        val id = source.optString("compactionId")
                        val evidence = compactions[id] ?: CompactionEvidence(id = id)
                        messages += compactionMessage(seq, time, evidence.copy(
                            sourceCommandId = source.optString("sourceCommandId").takeIf(String::isNotBlank)
                                ?: evidence.sourceCommandId,
                        ), commands)
                    } else {
                        val plugin = source?.optString("plugin").orEmpty()
                        val recall = source?.optString("role") == "recall" || plugin.contains("recall", ignoreCase = true)
                        messages += ChatMessage(
                            key = "event-$seq-context",
                            role = ChatMessage.Role.ACTIVITY,
                            text = contextSummary(source, text),
                            time = time,
                            title = if (recall) "Session recall" else "Context injection",
                            detail = text.ifBlank { data.toString(2) },
                            activityKind = ChatMessage.ActivityKind.CONTEXT,
                        )
                    }
                }
                "assistant/message" -> {
                    val message = data.optJSONObject("message") ?: data
                    val content = message.optJSONArray("content")
                    content?.objects()?.forEachIndexed { index, block ->
                        val text = block.optString("text").trim()
                        when (block.optString("type")) {
                            "reasoning" -> if (text.isNotBlank()) messages += ChatMessage(
                                "event-$seq-reasoning-$index", ChatMessage.Role.REASONING, text, time,
                                title = "Think",
                                activityKind = ChatMessage.ActivityKind.THINK,
                            )
                            "text" -> if (text.isNotBlank()) messages += ChatMessage(
                                "event-$seq-text-$index", ChatMessage.Role.ASSISTANT, text, time,
                            )
                        }
                    }
                    streaming.keys.removeAll { it.startsWith("${stepKey(data)}:") }
                }
                "assistant/chunk" -> {
                    val chunk = data.optJSONObject("chunk") ?: continue
                    val index = chunk.optInt("index")
                    val key = "${stepKey(data)}:$index"
                    when (chunk.optString("type")) {
                        "block-start" -> {
                            val kind = chunk.optString("blockType")
                            if (kind == "text" || kind == "reasoning") streaming[key] = StreamingBlock(kind)
                        }
                        "text-delta" -> streaming.getOrPut(key) { StreamingBlock("text") }.text.append(chunk.optString("text"))
                        "reasoning-delta" -> streaming.getOrPut(key) { StreamingBlock("reasoning") }.text.append(chunk.optString("text"))
                    }
                    streamingTimes[key] = time
                }
                "tool/call" -> {
                    val name = data.optString("name", data.optString("tool", "工具"))
                    val callId = data.optString("callId", "event-$seq")
                    val arguments = data.opt("arguments")?.let { if (it is String) it else it.toString() }.orEmpty()
                    val dispatchDetail = dispatchedTools[callId]?.joinToString("\n\n")
                    messages += ChatMessage(
                        key = "tool-$callId",
                        role = ChatMessage.Role.TOOL,
                        text = toolSummary(name, arguments),
                        time = time,
                        pending = true,
                        title = toolTitle(name),
                        detail = listOfNotNull(prettyArguments(arguments), dispatchDetail).joinToString("\n\n").ifBlank { null },
                        callId = callId,
                        state = ChatMessage.State.RUNNING,
                        activityKind = toolActivityKind(name),
                    )
                }
                "tool/result" -> {
                    val name = data.optString("name", data.optString("tool", "工具"))
                    val resultMessage = data.optJSONObject("message")
                    val firstResult = resultMessage?.optJSONArray("content")?.optJSONObject(0)
                    val callId = data.optString("callId").ifBlank {
                        resultMessage?.optJSONObject("source")?.optString("callId").orEmpty()
                    }
                    val output = contentText(data.optJSONArray("content") ?: firstResult?.optJSONArray("content"))
                    val index = messages.indexOfLast { it.role == ChatMessage.Role.TOOL && it.callId == callId }
                    val failed = data.optBoolean("isError") || firstResult?.optBoolean("isError") == true
                    val state = when {
                        data.optJSONObject("error")?.optString("code") == "interrupted" -> ChatMessage.State.STOPPED
                        failed -> ChatMessage.State.ERROR
                        else -> ChatMessage.State.OK
                    }
                    if (index >= 0) {
                        val call = messages[index]
                        messages[index] = call.copy(
                            pending = false,
                            detail = listOfNotNull(
                                call.detail?.takeIf { it.isNotBlank() }?.let { "IN\n$it" },
                                output.takeIf { it.isNotBlank() }?.let { "OUT\n$it" },
                            ).joinToString("\n\n").ifBlank { null },
                            state = state,
                        )
                    } else {
                        messages += ChatMessage(
                            "tool-${callId.ifBlank { seq.toString() }}", ChatMessage.Role.TOOL,
                            output.lineSequence().firstOrNull().orEmpty(), time,
                            title = toolTitle(name), detail = output, callId = callId, state = state,
                            activityKind = toolActivityKind(name),
                        )
                    }
                }
                "command/run", "command/done" -> {
                    val commandId = data.optString("commandId")
                    if (commandId.isBlank() || commandId in compactCommandIds || !emittedCommands.add(commandId)) continue
                    val command = commands[commandId] ?: continue
                    messages += commandMessage(command)
                }
                "compaction/start", "compaction/summary", "compaction/end" -> Unit
                "llm/retry", "llm/retry-started" -> {
                    val retryId = data.optString("retryId")
                    if (retryId.isBlank() || !emittedRetries.add(retryId)) continue
                    retries[retryId]?.let { messages += retryMessage(it) }
                }
                "turn/end" -> {
                    val reason = data.optJSONObject("reason")
                    when (reason?.optString("kind")) {
                        "error" -> {
                            if (data.optInt("turn", -1) in retryTurns) continue
                            val error = reason.optJSONObject("error")
                            messages += ChatMessage(
                                key = "event-$seq-error",
                                role = ChatMessage.Role.ACTIVITY,
                                text = error?.optString("message").orEmpty().ifBlank { "The model request failed." },
                                time = time,
                                title = "This turn failed",
                                detail = error?.toString(2),
                                state = ChatMessage.State.ERROR,
                                activityKind = ChatMessage.ActivityKind.ERROR,
                            )
                        }
                        "max-tokens" -> messages += ChatMessage(
                            key = "event-$seq-max-tokens",
                            role = ChatMessage.Role.ACTIVITY,
                            text = "The reply was cut off because it reached the output limit. Send “continue” to keep going.",
                            time = time,
                            title = "Output token limit reached",
                            state = ChatMessage.State.STOPPED,
                            activityKind = ChatMessage.ActivityKind.WARNING,
                        )
                    }
                }
                else -> if (event.optString("surfaceOp") == "append") {
                    messages += ChatMessage(
                        key = "event-$seq-unknown",
                        role = ChatMessage.Role.ACTIVITY,
                        text = type,
                        time = time,
                        title = "Unknown surface event",
                        detail = data.toString(2),
                        activityKind = ChatMessage.ActivityKind.UNKNOWN,
                    )
                }
            }
        }
        commands.values.filter { it.name == "compact" && it.id !in compactions.values.mapNotNull(CompactionEvidence::sourceCommandId) }
            .forEach { command ->
                messages += ChatMessage(
                    key = "command-${command.id}",
                    role = ChatMessage.Role.ACTIVITY,
                    text = command.outcomeText ?: if (command.outcomeKind == null) "Compacting context…" else "Context compacted",
                    time = command.time,
                    pending = command.outcomeKind == null,
                    title = "compact",
                    detail = command.outcomeText?.takeIf { it.contains('\n') },
                    state = command.state,
                    activityKind = ChatMessage.ActivityKind.TERMINAL,
                )
            }
        streaming.forEach { (key, block) ->
            val text = block.text.toString()
            if (text.isNotBlank()) {
                val role = if (block.kind == "reasoning") ChatMessage.Role.REASONING else ChatMessage.Role.ASSISTANT
                messages += ChatMessage(
                    "stream-$key", role, text, streamingTimes[key] ?: 0L, true,
                    title = if (role == ChatMessage.Role.REASONING) "Think" else null,
                    state = ChatMessage.State.RUNNING,
                    activityKind = if (role == ChatMessage.Role.REASONING) ChatMessage.ActivityKind.THINK else null,
                )
            }
        }
        return messages.sortedBy { it.time }
    }

    private data class StreamingBlock(val kind: String, val text: StringBuilder = StringBuilder())

    private data class CompactionEvidence(
        val id: String,
        val sourceCommandId: String? = null,
        val summary: String? = null,
        val itemCount: Int? = null,
        val tokenCount: Long? = null,
    )

    private data class CommandEvidence(
        val id: String,
        val name: String?,
        val args: String?,
        val time: Long,
        val outcomeKind: String? = null,
        val outcomeText: String? = null,
    ) {
        val state: ChatMessage.State get() = when (outcomeKind) {
            null -> ChatMessage.State.RUNNING
            "error" -> ChatMessage.State.ERROR
            else -> ChatMessage.State.OK
        }
    }

    private data class RetryEvidence(
        val id: String,
        val time: Long,
        val retry: Int,
        val maximum: String,
        val delayMs: Long,
        val failure: String,
        val started: Boolean,
    )

    private fun dispatchEvidence(events: List<JSONObject>): Map<String, List<String>> {
        val results = linkedMapOf<String, MutableList<String>>()
        events.forEach { event ->
            val type = event.optString("type")
            if (type != "tool/code-dispatch-start" && type != "tool/code-dispatch") return@forEach
            val data = event.optJSONObject("data") ?: return@forEach
            val root = data.optString("rootCallId")
            if (root.isBlank()) return@forEach
            val name = data.optString("name", "tool")
            val state = when {
                type == "tool/code-dispatch-start" -> "RUNNING"
                data.optBoolean("isError") -> "ERROR"
                else -> "DONE"
            }
            val arguments = data.opt("arguments")?.toString().orEmpty()
            val output = contentText(data.optJSONArray("content"))
            results.getOrPut(root) { mutableListOf() }.add(buildString {
                append("SUBTOOL ")
                append(name)
                append(" · ")
                append(state)
                if (arguments.isNotBlank()) append("\nIN\n").append(arguments)
                if (output.isNotBlank()) append("\nOUT\n").append(output)
            })
        }
        return results
    }

    private fun compactionEvidence(events: List<JSONObject>): Map<String, CompactionEvidence> {
        val results = linkedMapOf<String, CompactionEvidence>()
        events.forEach { event ->
            val data = event.optJSONObject("data") ?: return@forEach
            when (event.optString("type")) {
                "compaction/start", "compaction/end" -> {
                    val id = data.optString("compactionId")
                    if (id.isNotBlank()) results[id] = (results[id] ?: CompactionEvidence(id)).copy(
                        sourceCommandId = data.optString("sourceCommandId").takeIf(String::isNotBlank)
                            ?: results[id]?.sourceCommandId,
                    )
                }
                "compaction/summary" -> {
                    val id = data.optString("compactionId")
                    if (id.isBlank()) return@forEach
                    val shadowed = data.optJSONArray("shadowedSeqs")
                    val tokenCount = data.optLong("shadowedTokenCount", -1L).takeIf { it >= 0L }
                    results[id] = (results[id] ?: CompactionEvidence(id)).copy(
                        sourceCommandId = data.optString("sourceCommandId").takeIf(String::isNotBlank)
                            ?: results[id]?.sourceCommandId,
                        summary = visibleText(data.optJSONArray("summary")).takeIf(String::isNotBlank),
                        itemCount = shadowed?.length(),
                        tokenCount = tokenCount,
                    )
                }
                "user/message" -> {
                    val source = data.optJSONObject("source") ?: return@forEach
                    if (source.optString("kind") != "plugin" || source.optString("plugin") != "compact") return@forEach
                    val id = source.optString("compactionId")
                    if (id.isNotBlank()) results[id] = (results[id] ?: CompactionEvidence(id)).copy(
                        sourceCommandId = source.optString("sourceCommandId").takeIf(String::isNotBlank)
                            ?: results[id]?.sourceCommandId,
                    )
                }
            }
        }
        return results
    }

    private fun commandEvidence(events: List<JSONObject>): Map<String, CommandEvidence> {
        val results = linkedMapOf<String, CommandEvidence>()
        events.forEach { event ->
            val data = event.optJSONObject("data") ?: return@forEach
            val id = data.optString("commandId")
            if (id.isBlank()) return@forEach
            when (event.optString("type")) {
                "command/run" -> results[id] = CommandEvidence(
                    id = id,
                    name = data.optString("name").takeIf(String::isNotBlank),
                    args = data.opt("args")?.let { if (it is String) it else it.toString() },
                    time = event.optLong("time"),
                )
                "command/done" -> {
                    val old = results[id]
                    results[id] = CommandEvidence(
                        id = id,
                        name = old?.name,
                        args = old?.args,
                        time = old?.time ?: event.optLong("time"),
                        outcomeKind = data.optString("kind", "success"),
                        outcomeText = data.optString("text").takeIf(String::isNotBlank),
                    )
                }
            }
        }
        return results
    }

    private fun retryEvidence(events: List<JSONObject>): Map<String, RetryEvidence> {
        val results = linkedMapOf<String, RetryEvidence>()
        events.forEach { event ->
            val data = event.optJSONObject("data") ?: return@forEach
            val id = data.optString("retryId")
            if (id.isBlank()) return@forEach
            when (event.optString("type")) {
                "llm/retry" -> {
                    val failure = data.optJSONObject("failure")?.optString("message")
                        ?: data.optString("failure")
                    results[id] = RetryEvidence(
                        id = id,
                        time = results[id]?.time ?: event.optLong("time"),
                        retry = data.optInt("retry"),
                        maximum = if (data.optString("mode") == "normal") data.optInt("maxRetries").toString() else "∞",
                        delayMs = data.optLong("delayMs"),
                        failure = failure,
                        started = false,
                    )
                }
                "llm/retry-started" -> results[id]?.let { results[id] = it.copy(started = true) }
            }
        }
        return results
    }

    private fun compactionMessage(
        seq: Long,
        time: Long,
        evidence: CompactionEvidence,
        commands: Map<String, CommandEvidence>,
    ): ChatMessage {
        val manual = evidence.sourceCommandId != null
        val command = evidence.sourceCommandId?.let(commands::get)
        val completed = if (evidence.itemCount != null && evidence.tokenCount != null) {
            "Compacted ${evidence.itemCount} history items (~${evidence.tokenCount} tokens)"
        } else command?.outcomeText ?: if (evidence.summary != null) "View compaction summary" else "Compaction summary unavailable"
        return ChatMessage(
            key = "event-$seq-compaction",
            role = ChatMessage.Role.ACTIVITY,
            text = completed,
            time = time,
            title = if (manual) "compact" else "Context compacted",
            detail = evidence.summary,
            state = command?.state ?: ChatMessage.State.OK,
            activityKind = if (manual) ChatMessage.ActivityKind.TERMINAL else ChatMessage.ActivityKind.CONTEXT,
        )
    }

    private fun commandMessage(command: CommandEvidence): ChatMessage {
        val summary = when {
            command.outcomeText != null -> command.outcomeText.lineSequence().firstOrNull().orEmpty()
            command.outcomeKind == "error" -> "Command failed"
            command.outcomeKind != null -> "Done"
            else -> "Running…"
        }
        val detail = command.outcomeText?.takeIf { it.contains('\n') } ?: command.args
        return ChatMessage(
            key = "command-${command.id}",
            role = ChatMessage.Role.ACTIVITY,
            text = summary,
            time = command.time,
            pending = command.outcomeKind == null,
            title = command.name ?: "Command",
            detail = detail,
            state = command.state,
            activityKind = ChatMessage.ActivityKind.TERMINAL,
        )
    }

    private fun retryMessage(retry: RetryEvidence): ChatMessage {
        val seconds = maxOf(1L, (retry.delayMs + 999L) / 1_000L)
        val label = if (retry.started) "Retried model request" else "Waiting to retry model request"
        return ChatMessage(
            key = "retry-${retry.id}",
            role = ChatMessage.Role.ACTIVITY,
            text = "$label (${retry.retry}/${retry.maximum}) · ${seconds}s",
            time = retry.time,
            pending = !retry.started,
            title = "Retry",
            detail = "Retry delay: ${retry.delayMs}ms\nFailure reason: ${retry.failure}",
            state = if (retry.started) ChatMessage.State.OK else ChatMessage.State.RUNNING,
            activityKind = ChatMessage.ActivityKind.RETRY,
        )
    }

    private fun contextSummary(source: JSONObject?, text: String): String {
        val sourceLabel = source?.optString("label").orEmpty()
            .ifBlank { source?.optString("plugin").orEmpty() }
            .ifBlank { source?.optString("kind").orEmpty() }
        val firstLine = text.lineSequence().firstOrNull().orEmpty().trim()
        return listOf(sourceLabel, firstLine).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Injected model context" }
    }

    private fun stepKey(data: JSONObject): String = "${data.optInt("turn")}:${data.optInt("step")}"

    private fun visibleText(content: JSONArray?): String {
        if (content == null) return ""
        return content.objects().mapNotNull { block ->
            when (block.optString("type")) {
                "text" -> block.optString("text")
                "image", "image_ref" -> "[图片]"
                else -> null
            }
        }.joinToString("\n").trim()
    }

    private fun contentText(content: JSONArray?): String = content?.objects()?.joinToString("\n") { block ->
        if (block.optString("type") == "text") block.optString("text") else block.toString(2)
    }.orEmpty().trim()

    private fun toolTitle(name: String): String = when (name.lowercase()) {
        "bash" -> "Bash"
        "pwsh" -> "Pwsh"
        "read", "web_fetch" -> "Read"
        "grep", "glob", "web_search" -> "Search"
        "write" -> "Write"
        "edit" -> "Edit"
        "run_code" -> "Code"
        "todo_write" -> "Update to-do list"
        else -> name.ifBlank { "Tool call" }.replaceFirstChar { it.uppercase() }
    }

    private fun toolActivityKind(name: String): ChatMessage.ActivityKind = when (name.lowercase()) {
        "read", "web_fetch" -> ChatMessage.ActivityKind.READ
        "grep", "glob", "web_search" -> ChatMessage.ActivityKind.SEARCH
        "write", "edit" -> ChatMessage.ActivityKind.WRITE
        "todo_write" -> ChatMessage.ActivityKind.TODO
        else -> ChatMessage.ActivityKind.TERMINAL
    }

    private fun toolSummary(name: String, arguments: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        val keys = when (name) {
            "bash", "pwsh" -> listOf("description", "command")
            "read", "web_fetch" -> listOf("path", "file_path", "url")
            "grep", "glob", "web_search" -> listOf("query", "pattern", "url")
            "write", "edit" -> listOf("path", "file_path")
            "run_code" -> listOf("description")
            "todo_write" -> emptyList()
            else -> emptyList()
        }
        if (name == "todo_write") return todoSummary(parsed) ?: arguments
        val picked = keys.firstNotNullOfOrNull { key -> parsed?.optString(key)?.takeIf(String::isNotBlank) }
            ?: parsed?.keys()?.asSequence()?.mapNotNull { key -> parsed.optString(key).takeIf(String::isNotBlank) }?.firstOrNull()
            ?: arguments
        val summary = picked.lineSequence().firstOrNull().orEmpty()
        return summary
    }

    private fun todoSummary(parsed: JSONObject?): String? {
        val todos = parsed?.optJSONArray("todos")?.objects() ?: return null
        val done = todos.count { it.optString("status") == "completed" }
        val active = todos.filter { it.optString("status") == "in_progress" }
        val firstActive = active.firstOrNull()?.optString("content")?.trim().orEmpty()
        return buildString {
            append(done)
            append('/')
            append(todos.size)
            append(" completed")
            if (firstActive.isNotBlank()) {
                append(" · ")
                append(firstActive)
                if (active.size > 1) append(" +${active.size - 1}")
            }
        }
    }

    private fun prettyArguments(arguments: String): String? {
        if (arguments.isBlank()) return null
        return runCatching { JSONObject(arguments).toString(2) }.getOrDefault(arguments)
    }
}
