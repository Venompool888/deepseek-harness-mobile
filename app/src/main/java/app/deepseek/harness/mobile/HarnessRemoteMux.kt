package app.deepseek.harness.mobile

import java.io.IOException
import java.util.UUID
import org.json.JSONObject

/** Thread-safe routing for logical streams on one authenticated Remote mux. */
internal class RemoteStreamRouter(private val send: (JSONObject) -> Unit) {
    private data class Listener(val item: (JSONObject) -> Unit, val ended: (IOException?) -> Unit)
    private val listeners = mutableMapOf<String, Listener>()
    fun open(endpoint: String, args: JSONObject, ended: (IOException?) -> Unit = {}, item: (JSONObject) -> Unit): String {
        val id = UUID.randomUUID().toString()
        synchronized(listeners) { listeners[id] = Listener(item, ended) }
        try {
            send(JSONObject().put("type", "open").put("streamId", id).put("endpoint", endpoint)
                .put("payload", JSONObject().put("args", args)))
        } catch (e: Exception) { synchronized(listeners) { listeners.remove(id) }; throw e }
        return id
    }
    fun cancel(id: String) {
        val removed = synchronized(listeners) { listeners.remove(id) }
        if (removed != null) send(JSONObject().put("type", "cancel").put("streamId", id))
    }
    fun accept(frame: JSONObject) {
        val id = frame.optString("streamId")
        val listener = synchronized(listeners) { listeners[id] } ?: return
        when (frame.optString("type")) {
            "item" -> listener.item(frame.optJSONObject("value") ?: throw IOException("Invalid Remote stream item"))
            "error", "end" -> {
                synchronized(listeners) { listeners.remove(id) }
                val e = frame.optJSONObject("error")
                listener.ended(if (e != null) HarnessApi.RemoteFailure(e.optString("code"), e.optString("message")) else null)
            }
            else -> throw IOException("Invalid Remote stream frame")
        }
    }
    fun close() { synchronized(listeners) { listeners.clear() } }
}

/** A physical carrier belongs to one API consumer and one server generation. */
internal class HarnessRemoteConnection(private val transport: HarnessTransport) {
    constructor(root: String, authorization: String?, cookie: String?) : this(DirectHarnessTransport(root, authorization, cookie))
    private val opened = java.util.concurrent.CountDownLatch(1)
    private val stopped = java.util.concurrent.CountDownLatch(1)
    @Volatile private var failure: IOException? = null
    private lateinit var socket: okhttp3.WebSocket
    val router = RemoteStreamRouter { frame ->
        if (!opened.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw IOException("Harness stream connection timed out")
        failure?.let { throw it }
        if (!socket.send(frame.toString())) throw IOException("Harness stream is closed")
    }
    init {
        socket = transport.openMux(object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { opened.countDown() }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try { router.accept(JSONObject(text)) } catch (e: Exception) { fail(IOException("Invalid Harness stream", e)); webSocket.cancel() }
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { fail(IOException("Harness stream closed")) }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                fail(transport.streamFailure(response, t))
            }
        })
    }
    private fun fail(error: IOException) { failure = error; opened.countDown(); stopped.countDown() }
    fun abort(error: IOException) { fail(error); socket.cancel() }
    fun await() { stopped.await(); failure?.let { throw it } }
    fun close() { router.close(); socket.cancel(); stopped.countDown() }
    fun first(endpoint: String, args: JSONObject): JSONObject {
        val latch = java.util.concurrent.CountDownLatch(1)
        var item: JSONObject? = null
        var error: IOException? = null
        val id = router.open(endpoint, args, ended = { error = it ?: IOException("Stream ended before baseline"); latch.countDown() }) {
            if (item == null) { item = it; latch.countDown() }
        }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25)
            while (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                failure?.let { throw it }
                if (System.nanoTime() > deadline) throw IOException("Harness baseline timed out")
            }
            error?.let { throw it }
            return item ?: throw IOException("Missing Harness baseline")
        } finally { router.cancel(id) }
    }
    companion object {
        private val client = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).pingInterval(20, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).build()
    }
}
