package app.deepseek.harness.mobile

import org.json.JSONObject

/** Durable follow/page address resolved from a Session list row. */
internal data class HarnessSessionAddress(
    val kind: String,
    val sessionId: String,
    val parentSessionId: String? = null,
) {
    fun toJson(): JSONObject = if (kind == "subagent") {
        JSONObject().put("kind", kind).put("parentSessionId", parentSessionId)
            .put("childSessionId", sessionId).put("mode", "unknown")
    } else {
        JSONObject().put("kind", "session").put("sessionId", sessionId)
    }
}

/** No parent is inferred: unresolved or malformed child metadata has no address. */
internal fun resolveHarnessSessionAddress(
    sessionId: String,
    origin: String?,
    parentSessionId: String?,
): HarnessSessionAddress? {
    if (sessionId.isBlank()) return null
    if (origin == "subagent") {
        val parent = parentSessionId?.takeIf(String::isNotBlank) ?: return null
        return HarnessSessionAddress("subagent", sessionId, parent)
    }
    return HarnessSessionAddress("session", sessionId)
}
