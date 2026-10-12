package app.deepseek.harness.mobile

/** A lost receipt does not turn a retry of the same draft into another admission. */
internal class HarnessPromptSubmission {
    private data class Key(val server: String, val session: String, val text: String, val mode: String)
    private val pending = mutableMapOf<Key, String>()
    @Synchronized fun begin(server: String, session: String, text: String, mode: String): String {
        return pending.getOrPut(Key(server, session, text, mode)) { java.util.UUID.randomUUID().toString() }
    }
    @Synchronized fun accepted(id: String) { pending.entries.removeAll { it.value == id } }
}
