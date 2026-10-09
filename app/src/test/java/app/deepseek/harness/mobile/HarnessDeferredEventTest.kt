package app.deepseek.harness.mobile

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HarnessDeferredEventTest {
    @Test fun staleDeferredResultAfterHostCancelDoesNotEscapeWorkerOrRemoveContinuedQuestion() {
        val transport = TimedQuestionTransport()
        val api = HarnessApi({ "http://127.0.0.1" }, transport = { transport })
        val frames = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val streamFailure = AtomicReference<Throwable?>()
        val uncaught = CountDownLatch(1)
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            streamFailure.compareAndSet(null, error)
            uncaught.countDown()
        }
        val streamThread = Thread {
            try { api.streamMux { frames += JSONObject(it.toString()) } }
            catch (error: Throwable) { streamFailure.compareAndSet(null, error) }
        }
        try {
            streamThread.start()
            assertTrue("question should be delivered", transport.questionDelivered.await(3, TimeUnit.SECONDS))
            transport.publishContinuedProjection()
            assertTrue("continued projection should be delivered", frames.any { it.optString("type") == "question/projection" })

            transport.endWait()
            assertTrue("deferred result should reach the host", transport.resultAttempted.await(3, TimeUnit.SECONDS))
            // The host's timeout path cancels the original waterfall before its `next` ack returns.
            transport.cancelOriginalWaterfall()
            transport.releaseResult.countDown()

            assertFalse("stale-result must be handled inside the event worker", uncaught.await(500, TimeUnit.MILLISECONDS))
            assertNull("the mux stream itself should remain healthy", streamFailure.get())
            assertTrue("continued question remains available after late cancel", frames.any {
                it.optString("type") == "question/projection" && it.optString("callId") == CALL_ID
            })
            assertFalse("late cancel must not resolve the continued projection", frames.any {
                it.optString("type") == "question/resolved" && it.isNull("eventId") && it.optString("callId") == CALL_ID
            })
        } finally {
            transport.releaseResult.countDown()
            api.closeMux()
            streamThread.join(3_000)
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        }
        assertFalse("stream thread should finish cleanly", streamThread.isAlive)
    }

    private class TimedQuestionTransport : HarnessTransport {
        private lateinit var listener: WebSocketListener
        private lateinit var socket: WebSocket
        private val streams = mutableMapOf<String, String>()
        val questionDelivered = CountDownLatch(1)
        val resultAttempted = CountDownLatch(1)
        val releaseResult = CountDownLatch(1)
        private var eventStreamId: String? = null
        private var waitStreamId: String? = null

        override fun rpc(envelope: JSONObject): JSONObject {
            assertEquals("\$events/result", envelope.getString("method"))
            resultAttempted.countDown()
            check(releaseResult.await(3, TimeUnit.SECONDS)) { "test did not release stale result" }
            return JSONObject().put("type", "server-response").put("rpcId", envelope.getString("rpcId"))
                .put("result", JSONObject().put("ok", false).put("error", JSONObject()
                    .put("code", "events/stale-result").put("message", "event already cancelled by host")))
        }

        override fun openMux(listener: WebSocketListener): WebSocket {
            this.listener = listener
            val fake = FakeWebSocket()
            socket = fake
            listener.onOpen(fake, Response.Builder()
                .request(Request.Builder().url("http://127.0.0.1/api/remote.mux").build())
                .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
            return fake
        }

        override fun export(sessionId: String, includeDescendants: Boolean, sink: OutputStream) = Unit
        override fun streamFailure(response: Response?, cause: Throwable): IOException = IOException(cause)

        fun publishContinuedProjection() {
            val id = streams.getValue("session/control")
            item(id, JSONObject().put("type", "projection").put("sessionId", SESSION_ID)
                .put("key", "userQuestions").put("value", JSONObject().put("active", org.json.JSONArray().put(
                    JSONObject().put("callId", CALL_ID).put("state", "continued").put("questions", org.json.JSONArray().put(
                        JSONObject().put("id", "q1").put("question", "Continue?")
                    )))
                )))
        }

        fun endWait() {
            listener.onMessage(socket, JSONObject().put("type", "end").put("streamId", waitStreamId).toString())
        }

        fun cancelOriginalWaterfall() {
            item(eventStreamId!!, JSONObject().put("type", "cancel").put("eventId", EVENT_ID))
        }

        private fun item(streamId: String, value: JSONObject) {
            listener.onMessage(socket, JSONObject().put("type", "item").put("streamId", streamId).put("value", value).toString())
        }

        private inner class FakeWebSocket : WebSocket {
            override fun request(): Request = Request.Builder().url("http://127.0.0.1/api/remote.mux").build()
            override fun queueSize(): Long = 0
            override fun send(bytes: okio.ByteString): Boolean = true
            override fun send(text: String): Boolean {
                val frame = JSONObject(text)
                if (frame.optString("type") != "open") return true
                val id = frame.getString("streamId")
                val endpoint = frame.getString("endpoint")
                streams[endpoint] = id
                when (endpoint) {
                    "session/control" -> item(id, JSONObject().put("type", "baseline").put("value", JSONObject().put("projections", JSONObject())))
                    "\$events" -> {
                        eventStreamId = id
                        item(id, JSONObject().put("type", "ready").put("clientId", "qa-client"))
                        item(id, JSONObject().put("type", "waterfall").put("event", "user-questions/request")
                            .put("eventId", EVENT_ID).put("agentId", SESSION_ID)
                            .put("request", JSONObject().put("questions", org.json.JSONArray().put(
                                JSONObject().put("id", "q1").put("question", "Continue?")
                            )).put("wait", JSONObject().put("callId", CALL_ID).put("timed", true))))
                        questionDelivered.countDown()
                    }
                    "userQuestions/attachWait" -> {
                        waitStreamId = id
                        item(id, JSONObject().put("remainingMs", 10_000))
                    }
                }
                return true
            }
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() { listener.onClosed(this, 1000, "closed") }
        }
    }

    companion object {
        private const val SESSION_ID = "qa-session"
        private const val EVENT_ID = "qa-event"
        private const val CALL_ID = "qa-call"
    }
}
