package cool.rin.deepseekremote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProjectionTest {
    @Test
    fun keepsHumanContextReasoningAndVisibleAssistantMessages() {
        val history = JSONArray()
            .put(event(1, "user/message", JSONObject()
                .put("source", JSONObject().put("kind", "user"))
                .put("content", JSONArray().put(text("hello")))))
            .put(event(2, "user/message", JSONObject()
                .put("source", JSONObject().put("kind", "plugin"))
                .put("content", JSONArray().put(text("hidden context")))))
            .put(event(3, "assistant/message", JSONObject()
                .put("turn", 1).put("step", 1)
                .put("message", JSONObject().put("content", JSONArray()
                    .put(JSONObject().put("type", "reasoning").put("text", "hidden reasoning"))
                    .put(text("answer"))))))

        val projected = ChatProjection.fromHistory(history)

        assertEquals(listOf("hello", "plugin · hidden context", "hidden reasoning", "answer"), projected.map { it.text })
        assertEquals(
            listOf(ChatMessage.Role.USER, ChatMessage.Role.ACTIVITY, ChatMessage.Role.REASONING, ChatMessage.Role.ASSISTANT),
            projected.map { it.role },
        )
        assertEquals(ChatMessage.ActivityKind.CONTEXT, projected[1].activityKind)
    }

    @Test
    fun joinsToolCallAndResultIntoExpandableActivityRow() {
        val history = JSONArray()
            .put(event(1, "tool/call", JSONObject()
                .put("callId", "call-1")
                .put("name", "bash")
                .put("arguments", "{\"description\":\"Check environment\",\"command\":\"pwd\"}")))
            .put(event(2, "tool/result", JSONObject()
                .put("callId", "call-1")
                .put("content", JSONArray().put(text("/workspace")))
                .put("isError", false)))

        val projected = ChatProjection.fromHistory(history)

        assertEquals(1, projected.size)
        assertEquals(ChatMessage.Role.TOOL, projected.single().role)
        assertEquals("Bash", projected.single().title)
        assertEquals("Check environment", projected.single().text)
        assertTrue(projected.single().detail.orEmpty().contains("OUT\n/workspace"))
        assertFalse(projected.single().pending)
    }

    @Test
    fun exposesOnlyUnfinalizedTextChunksAsPending() {
        val history = JSONArray()
            .put(event(1, "assistant/chunk", chunk(1, 1, "half ")))
            .put(event(2, "assistant/chunk", chunk(1, 1, "answer")))
            .put(event(3, "assistant/chunk", chunk(2, 1, "finished draft")))
            .put(event(4, "assistant/message", JSONObject()
                .put("turn", 2).put("step", 1)
                .put("message", JSONObject().put("content", JSONArray().put(text("finished"))))))

        val projected = ChatProjection.fromHistory(history)

        assertEquals(listOf("half answer", "finished"), projected.map { it.text })
        assertTrue(projected.first().pending)
        assertFalse(projected.last().pending)
    }

    @Test
    fun summarizesTodoWriteWithDedicatedActivityChrome() {
        val args = JSONObject().put("todos", JSONArray()
            .put(JSONObject().put("content", "done").put("status", "completed"))
            .put(JSONObject().put("content", "verify services").put("status", "in_progress"))
            .put(JSONObject().put("content", "report").put("status", "pending")))
        val history = JSONArray().put(event(1, "tool/call", JSONObject()
            .put("callId", "todo-1")
            .put("name", "todo_write")
            .put("arguments", args.toString())))

        val projected = ChatProjection.fromHistory(history).single()

        assertEquals("Update to-do list", projected.title)
        assertEquals("1/3 completed · verify services", projected.text)
        assertTrue(projected.pending)
    }

    @Test
    fun projectsManualCompactionLikeHarnessWeb() {
        val history = JSONArray()
            .put(event(1, "command/run", JSONObject()
                .put("commandId", "cmd-1").put("name", "compact")))
            .put(event(2, "compaction/summary", JSONObject()
                .put("compactionId", "compact-1")
                .put("sourceCommandId", "cmd-1")
                .put("shadowedSeqs", JSONArray((1..121).toList()))
                .put("shadowedTokenCount", 70_494)
                .put("summary", JSONArray().put(text("Preserved working context.")))))
            .put(event(3, "user/message", JSONObject()
                .put("source", JSONObject()
                    .put("kind", "plugin")
                    .put("plugin", "compact")
                    .put("compactionId", "compact-1")
                    .put("sourceCommandId", "cmd-1"))
                .put("content", JSONArray().put(text("replacement checkpoint")))))
            .put(event(4, "command/done", JSONObject()
                .put("commandId", "cmd-1").put("kind", "success")))

        val projected = ChatProjection.fromHistory(history).single()

        assertEquals(ChatMessage.Role.ACTIVITY, projected.role)
        assertEquals(ChatMessage.ActivityKind.TERMINAL, projected.activityKind)
        assertEquals("compact", projected.title)
        assertEquals("Compacted 121 history items (~70494 tokens)", projected.text)
        assertEquals("Preserved working context.", projected.detail)
    }

    @Test
    fun projectsAutomaticCompactionWithoutManualCommandTitle() {
        val history = JSONArray()
            .put(event(1, "compaction/summary", JSONObject()
                .put("compactionId", "auto-1")
                .put("shadowedSeqs", JSONArray().put(4).put(5))
                .put("shadowedTokenCount", 900)
                .put("summary", JSONArray().put(text("Automatic summary.")))))
            .put(event(2, "user/message", JSONObject()
                .put("source", JSONObject().put("kind", "plugin").put("plugin", "compact").put("compactionId", "auto-1"))
                .put("content", JSONArray().put(text("replacement")))))

        val projected = ChatProjection.fromHistory(history).single()

        assertEquals("Context compacted", projected.title)
        assertEquals("Compacted 2 history items (~900 tokens)", projected.text)
        assertEquals(ChatMessage.ActivityKind.CONTEXT, projected.activityKind)
    }

    @Test
    fun keepsNestedCodeDispatchInsideOwningToolDisclosure() {
        val history = JSONArray()
            .put(event(1, "tool/call", JSONObject()
                .put("callId", "root-1").put("name", "run_code").put("arguments", "{\"description\":\"Run helper\"}")))
            .put(event(2, "tool/code-dispatch", JSONObject()
                .put("rootCallId", "root-1").put("parentCallId", "root-1").put("subCallId", "child-1")
                .put("name", "read").put("arguments", JSONObject().put("path", "/tmp/a"))
                .put("content", JSONArray().put(text("file contents")))))
            .put(event(3, "tool/result", JSONObject()
                .put("message", JSONObject()
                    .put("source", JSONObject().put("callId", "root-1"))
                    .put("content", JSONArray().put(JSONObject()
                        .put("content", JSONArray().put(text("done")))
                        .put("isError", false))))))

        val projected = ChatProjection.fromHistory(history).single()

        assertEquals("Code", projected.title)
        assertTrue(projected.detail.orEmpty().contains("SUBTOOL read · DONE"))
        assertTrue(projected.detail.orEmpty().contains("OUT\ndone"))
    }

    @Test
    fun projectsCommandsRetryErrorsLimitsAndUnknownSurfaceEvents() {
        val history = JSONArray()
            .put(event(1, "command/run", JSONObject()
                .put("commandId", "cmd-2").put("name", "doctor").put("args", JSONObject().put("quick", true))))
            .put(event(2, "command/done", JSONObject()
                .put("commandId", "cmd-2").put("kind", "success").put("text", "All checks passed")))
            .put(event(3, "llm/retry", JSONObject()
                .put("retryId", "retry-1").put("retry", 1).put("mode", "normal").put("maxRetries", 3)
                .put("delayMs", 2_000).put("failure", JSONObject().put("message", "busy"))))
            .put(event(4, "turn/end", JSONObject().put("turn", 1).put("reason", JSONObject()
                .put("kind", "error").put("error", JSONObject().put("code", "provider").put("message", "Provider failed")))))
            .put(event(5, "turn/end", JSONObject().put("turn", 2).put("reason", JSONObject().put("kind", "max-tokens"))))
            .put(event(6, "future/surface", JSONObject().put("value", 1), surfaceOp = "append"))

        val projected = ChatProjection.fromHistory(history)

        assertEquals(listOf("doctor", "Retry", "This turn failed", "Output token limit reached", "Unknown surface event"), projected.map { it.title })
        assertEquals(ChatMessage.State.ERROR, projected[2].state)
        assertEquals(ChatMessage.ActivityKind.WARNING, projected[3].activityKind)
        assertTrue(projected[4].detail.orEmpty().contains("value"))
    }

    @Test
    fun contextUsageMatchesWebRoundingAndClamp() {
        assertEquals(8, HarnessApi.ContextUsage(64_000, 800_000).percent)
        assertEquals(25, HarnessApi.ContextUsage(32_000, 128_000).percent)
        assertEquals(100, HarnessApi.ContextUsage(300_000, 128_000).percent)
    }

    private fun event(seq: Int, type: String, data: JSONObject, surfaceOp: String? = null) = JSONObject()
        .put("event", JSONObject().put("seq", seq).put("time", seq * 1000L).put("type", type).put("data", data).apply {
            if (surfaceOp != null) put("surfaceOp", surfaceOp)
        })

    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)

    private fun chunk(turn: Int, step: Int, value: String) = JSONObject()
        .put("turn", turn)
        .put("step", step)
        .put("chunk", JSONObject().put("type", "text-delta").put("text", value))
}
