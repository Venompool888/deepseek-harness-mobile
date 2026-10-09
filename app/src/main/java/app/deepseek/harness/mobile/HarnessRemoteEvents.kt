package app.deepseek.harness.mobile

/** Correlation exists only for the current Remote event client lifetime. */
internal class HarnessRemoteEvents {
    private var client: String? = null
    private val pending = mutableMapOf<String, String>()
    @Synchronized fun ready(clientId: String) { pending.clear(); client = clientId }
    @Synchronized fun offer(eventId: String, sessionId: String) { pending[eventId] = sessionId }
    @Synchronized fun valid(clientId: String, eventId: String, sessionId: String): Boolean = client == clientId && pending[eventId] == sessionId
    @Synchronized fun settle(eventId: String): String? = pending.remove(eventId)
    @Synchronized fun clear() { pending.clear(); client = null }
}
