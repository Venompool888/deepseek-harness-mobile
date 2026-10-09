package app.deepseek.harness.mobile

/** Correlation exists only for the current Remote event client lifetime. */
internal class HarnessRemoteEvents {
    private var client: String? = null
    private var generation = 0L
    private data class Entry(val sessionId: String, val kind: String, val generation: Long, var responding: Boolean = false)
    private val pending = mutableMapOf<String, Entry>()

    @Synchronized fun ready(clientId: String): Long {
        pending.clear()
        client = clientId
        generation += 1
        return generation
    }

    @Synchronized fun currentGeneration(): Long = generation

    @Synchronized fun offer(eventId: String, sessionId: String, kind: String = "approval", eventGeneration: Long = generation) {
        if (client != null && eventGeneration == generation) pending[eventId] = Entry(sessionId, kind, eventGeneration)
    }

    @Synchronized fun valid(
        clientId: String,
        eventId: String,
        sessionId: String,
        kind: String? = null,
        eventGeneration: Long = generation,
    ): Boolean {
        val entry = pending[eventId] ?: return false
        return client == clientId && entry.sessionId == sessionId && (kind == null || entry.kind == kind)
            && entry.generation == generation && eventGeneration == generation && !entry.responding
    }

    /** Atomically claim a response so duplicate taps cannot answer a waterfall twice. */
    @Synchronized fun reserve(clientId: String, eventId: String, sessionId: String, kind: String, eventGeneration: Long): Boolean {
        if (!valid(clientId, eventId, sessionId, kind, eventGeneration)) return false
        pending[eventId]?.responding = true
        return true
    }

    @Synchronized fun unreserve(eventId: String, eventGeneration: Long) {
        pending[eventId]?.takeIf { it.generation == eventGeneration }?.responding = false
    }

    @Synchronized fun responding(eventId: String, eventGeneration: Long): Boolean =
        pending[eventId]?.let { it.generation == eventGeneration && it.responding } == true

    @Synchronized fun settle(eventId: String, eventGeneration: Long = generation): String? {
        if (pending[eventId]?.generation != eventGeneration) return null
        return pending.remove(eventId)?.sessionId
    }

    @Synchronized fun clear() { pending.clear(); client = null; generation += 1 }
}
