package app.deepseek.harness.mobile

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/** One follow generation. A fresh opening replaces all state; gaps require reopening. */
internal class HarnessSessionState {
    private var records = mutableListOf<JSONObject>()
    private var cursor = -1L
    private var revision = -1L
    private var ready = false
    private val projectionSeqs = mutableMapOf<String, Long>()
    private val pendingProjections = mutableMapOf<String, Pair<Long, Any?>>()
    private var attempt: JSONObject? = null
    private var chunks = mutableListOf<JSONObject>()
    var projections = JSONObject()
        private set
    var hasMore = false
        private set

    @Synchronized fun accept(item: JSONObject) {
        when (item.getString("type")) {
            "snapshot" -> {
                records = item.getJSONArray("records").objects().toMutableList()
                cursor = item.getLong("cursor")
                projections = item.getJSONObject("projections")
                hasMore = item.optBoolean("hasMore")
                val baseline = item.optJSONObject("assistantStream")
                revision = baseline?.optLong("revision") ?: -1L
                attempt = baseline?.optJSONObject("activeAttempt")
                chunks.clear()
                attempt?.let { a -> expandCompactStream(a.optJSONArray("stream") ?: JSONArray()).forEach { chunks += it } }
                projectionSeqs.clear()
                projections.getJSONObject("values").keys().forEach { projectionSeqs[it] = projections.optLong("asOfSeq", cursor) }
                ready = true
                pendingProjections.toMap().forEach { (key, update) -> projection(key, update.second, update.first) }
                pendingProjections.clear()
            }
            "event" -> {
                if (!ready) throw IOException("Session event arrived before snapshot")
                val seq = item.getJSONObject("event").getLong("seq")
                if (seq <= cursor) return
                if (seq != cursor + 1) throw IOException("Session history has a gap")
                records += item
                cursor = seq
                val event = item.getJSONObject("event")
                if (event.optString("type") in setOf("assistant/message", "assistant/attempt")) {
                    val data = event.optJSONObject("data")
                    if (data?.optInt("turn") == attempt?.optInt("turn") && data?.optInt("step") == attempt?.optInt("step")) {
                        chunks.clear() // durable settlement now owns presentation
                    }
                }
            }
            "assistant-stream" -> {
                if (!ready) throw IOException("Assistant output arrived before snapshot")
                val frame = item.getJSONObject("frame")
                if (frame.getLong("revision") != revision + 1) throw IOException("Assistant output has a revision gap")
                revision++
                when (frame.getString("type")) {
                    "start" -> { attempt = JSONObject(frame.toString()).put("nextIndex", 0); chunks.clear() }
                    "chunk" -> attempt?.let { a ->
                        if (frame.optString("attemptId") != a.optString("attemptId")) throw IOException("Assistant attempt changed")
                        if (frame.getInt("index") != a.optInt("nextIndex")) throw IOException("Assistant chunk has an index gap")
                        a.put("nextIndex", a.optInt("nextIndex") + 1)
                        chunks += JSONObject().put("time", frame.getLong("time")).put("chunk", frame.getJSONObject("chunk"))
                    }
                    "end" -> {
                        if (attempt?.optString("attemptId") != frame.optString("attemptId") || attempt?.optInt("nextIndex") != frame.optInt("index")) throw IOException("Invalid assistant end")
                        if (frame.optJSONObject("outcome")?.optString("kind") == "abandoned") chunks.clear()
                        attempt = null
                    }
                }
            }
        }
    }

    @Synchronized fun pageRequest(sessionId: String): JSONObject {
        if (!ready) throw IOException("History is still synchronizing")
        val earliest = records.minOfOrNull { it.getJSONObject("event").getLong("seq") } ?: cursor
        return JSONObject().put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("throughSeq", cursor).put("beforeSeq", earliest).put("maxMessages", 80)
    }
    @Synchronized fun prependPage(page: JSONObject) {
        val current = records.map { it.getJSONObject("event").getLong("seq") }.toSet()
        records.addAll(0, page.getJSONArray("records").objects().filter { it.getJSONObject("event").getLong("seq") !in current })
        hasMore = page.optBoolean("hasMore")
    }
    @Synchronized fun isReady() = ready
    @Synchronized fun history() = JSONObject().put("events", events()).put("projections", projections).put("hasMore", hasMore)
    @Synchronized fun projection(key: String, value: Any?, seq: Long) {
        if (!ready) {
            if (seq >= (pendingProjections[key]?.first ?: -1L)) pendingProjections[key] = seq to value
            return
        }
        if (seq < (projectionSeqs[key] ?: projections.optLong("asOfSeq", cursor))) return
        projections.getJSONObject("values").put(key, value ?: JSONObject.NULL)
        projectionSeqs[key] = seq
    }
    @Synchronized fun events(): JSONArray {
        val out = JSONArray()
        val retired = mutableSetOf<Long>()
        records.forEach { entry ->
            val op = entry.getJSONObject("event").optJSONObject("surfaceOp")
            if (op?.optString("op") == "replace") {
                val start = op.getLong("startSeq")
                val end = op.getLong("endSeq")
                records.forEach { prior ->
                    val e = prior.getJSONObject("event")
                    if (e.has("surfaceOp") && e.optLong("seq") in start..end) retired += e.optLong("seq")
                }
            }
        }
        records.forEach { entry ->
            val event = entry.getJSONObject("event")
            if (event.optLong("seq") in retired) return@forEach
            val data = event.optJSONObject("data")
            data?.optJSONArray("stream")?.let { stream ->
                expandCompactStream(stream).forEach { c -> out.put(chunkEntry(data, c, event.optLong("seq"))) }
            }
            out.put(entry)
        }
        attempt?.let { a -> chunks.forEach { c -> out.put(chunkEntry(a, c, cursor + 1)) } }
        return out
    }
    private fun chunkEntry(data: JSONObject, timed: JSONObject, seq: Long) = JSONObject().put("event", JSONObject()
        .put("type", "assistant/chunk").put("seq", seq).put("time", timed.optLong("time"))
        .put("data", JSONObject().put("turn", data.optInt("turn")).put("step", data.optInt("step")).put("chunk", timed.getJSONObject("chunk"))))
}

internal fun expandCompactStream(stream: JSONArray): List<JSONObject> = buildList {
    stream.objects().forEach { record ->
        if (record.optString("type") == "chunk") { add(record); return@forEach }
        val type = record.optString("type")
        val members = record.optJSONArray(if (type == "tool-call-chunks") "args" else "texts") ?: return@forEach
        val dt = record.optJSONArray("dt") ?: return@forEach
        var time = record.optLong("time0")
        for (i in 0 until members.length()) {
            if (i > 0) time += dt.optLong(i - 1)
            val chunk = JSONObject().put("index", record.optInt("index"))
            when (type) {
                "text-chunks" -> chunk.put("type", "text-delta").put("text", members.getString(i))
                "reasoning-chunks" -> chunk.put("type", "reasoning-delta").put("text", members.getString(i))
                "tool-call-chunks" -> { chunk.put("type", "tool-call-delta").put("id", record.optString("id")).put("argumentsDelta", members.getString(i)); if (record.has("name")) chunk.put("name", record.getString("name")) }
                else -> continue
            }
            add(JSONObject().put("time", time).put("chunk", chunk))
        }
    }
}
