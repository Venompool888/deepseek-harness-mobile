package app.deepseek.harness.mobile

/** A lost receipt does not turn a retry of the same draft into another admission. */
internal class HarnessPromptSubmission {
    private data class Pending(val server: String, val session: String, val text: String, val mode: String, val id: String)
    private var pending: Pending? = null
    @Synchronized fun begin(server: String, session: String, text: String, mode: String): String {
        pending?.takeIf { it.server == server && it.session == session && it.text == text && it.mode == mode }?.let { return it.id }
        return java.util.UUID.randomUUID().toString().also { pending = Pending(server, session, text, mode, it) }
    }
    @Synchronized fun accepted(id: String) { if (pending?.id == id) pending = null }
}
