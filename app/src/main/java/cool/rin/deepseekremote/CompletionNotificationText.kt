package cool.rin.deepseekremote

internal object CompletionNotificationText {
    private const val MAX_CODE_POINTS = 96

    fun excerpt(messages: List<ChatMessage>, observedAt: Long): String? {
        val text = messages.lastOrNull {
            it.role == ChatMessage.Role.ASSISTANT && it.time >= observedAt && it.text.isNotBlank()
        }?.text ?: return null
        return clean(text).takeCodePoints(MAX_CODE_POINTS)
    }

    fun clean(source: String): String = source
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace(Regex("(?m)^[-*+]\\s+"), "")
        .replace(Regex("[`*_~]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.takeCodePoints(limit: Int): String {
        val count = codePointCount(0, length)
        if (count <= limit) return this
        return substring(0, offsetByCodePoints(0, limit)).trimEnd() + "…"
    }
}
