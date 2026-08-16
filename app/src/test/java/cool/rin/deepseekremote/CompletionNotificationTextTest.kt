package cool.rin.deepseekremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionNotificationTextTest {
    @Test fun usesLatestAssistantReplyAfterTaskStarted() {
        val messages = listOf(
            ChatMessage("old", ChatMessage.Role.ASSISTANT, "旧回复", 100),
            ChatMessage("new", ChatMessage.Role.ASSISTANT, "## 完成\n\n- 已修复 **通知**，请测试。", 300),
        )
        assertEquals("完成 已修复 通知，请测试。", CompletionNotificationText.excerpt(messages, 200))
    }

    @Test fun ignoresOldReplyWhenTaskStopsWithoutAnswer() {
        assertNull(CompletionNotificationText.excerpt(
            listOf(ChatMessage("old", ChatMessage.Role.ASSISTANT, "旧回复", 100)),
            200,
        ))
    }

    @Test fun truncatesLongReplyByCodePoint() {
        val excerpt = CompletionNotificationText.excerpt(
            listOf(ChatMessage("new", ChatMessage.Role.ASSISTANT, "好".repeat(120), 300)),
            200,
        )!!
        assertTrue(excerpt.endsWith("…"))
        assertEquals(97, excerpt.codePointCount(0, excerpt.length))
    }
}
