package app.deepseek.harness.mobile

/**
 * Chooses the smallest safe mutation for the conversation's View hierarchy.
 * Most Harness stream frames either extend the transcript or update its tail;
 * rebuilding every historical row for those frames makes long sessions jank.
 */
internal object MessageRenderPolicy {
    data class Row(val key: String, val fingerprint: String)

    enum class Plan {
        REBUILD,
        APPEND,
        REPLACE_LAST,
        KEEP,
    }

    fun plan(previous: List<Row>, next: List<Row>): Plan {
        if (previous.isEmpty() || next.isEmpty()) {
            return if (previous == next) Plan.KEEP else Plan.REBUILD
        }
        if (previous == next) return Plan.KEEP
        if (next.size > previous.size && next.subList(0, previous.size) == previous) {
            return Plan.APPEND
        }
        if (
            next.size == previous.size &&
            previous.dropLast(1) == next.dropLast(1) &&
            previous.last().key == next.last().key
        ) {
            return Plan.REPLACE_LAST
        }
        return Plan.REBUILD
    }
}
