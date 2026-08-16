package cool.rin.deepseekremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HarnessCommandRequestTest {
    @Test
    fun permissionUsesHostCommandPayloadInsteadOfPromptContent() {
        val payload = commandExecutionPayload("session-1", "/permission danger-full-access")
        val args = payload.getJSONObject("args")

        assertEquals("session-1", args.getString("agentId"))
        assertEquals("/permission danger-full-access", args.getString("line"))
        assertFalse(payload.has("content"))
        assertFalse(payload.has("mode"))
    }
}
