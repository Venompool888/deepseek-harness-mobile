package app.deepseek.harness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRenderPolicyTest {
    private fun row(key: String, value: String = key) = MessageRenderPolicy.Row(key, value)

    @Test
    fun `unchanged transcript keeps the existing hierarchy`() {
        val rows = listOf(row("user-1"), row("assistant-1"))
        assertEquals(MessageRenderPolicy.Plan.KEEP, MessageRenderPolicy.plan(rows, rows))
    }

    @Test
    fun `new stream rows append without rebuilding history`() {
        val previous = listOf(row("user-1"), row("assistant-1"))
        val next = previous + row("tool-2")
        assertEquals(MessageRenderPolicy.Plan.APPEND, MessageRenderPolicy.plan(previous, next))
    }

    @Test
    fun `streaming tail changes replace only the final row`() {
        val previous = listOf(row("user-1"), row("stream-1", "hello"))
        val next = listOf(row("user-1"), row("stream-1", "hello world"))
        assertEquals(MessageRenderPolicy.Plan.REPLACE_LAST, MessageRenderPolicy.plan(previous, next))
    }

    @Test
    fun `older row changes require a safe rebuild`() {
        val previous = listOf(row("tool-1", "running"), row("assistant-2"))
        val next = listOf(row("tool-1", "complete"), row("assistant-2"))
        assertEquals(MessageRenderPolicy.Plan.REBUILD, MessageRenderPolicy.plan(previous, next))
    }

    @Test
    fun `a changed tail identity requires a safe rebuild`() {
        val previous = listOf(row("user-1"), row("stream-1", "partial"))
        val next = listOf(row("user-1"), row("event-9", "complete"))
        assertEquals(MessageRenderPolicy.Plan.REBUILD, MessageRenderPolicy.plan(previous, next))
    }
}
