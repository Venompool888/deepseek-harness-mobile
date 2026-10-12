package app.deepseek.harness.mobile

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class HarnessNativeTransportTest {
    @Test fun directoryCreationPreservesStringResult() {
        ServerSocket(0).use { host ->
            val root = "http://127.0.0.1:${host.localPort}"
            val worker = Thread {
                host.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers = readHeaders(reader)
                    val body = CharArray(headers.getValue("content-length").toInt())
                    readBody(reader, body)
                    respond(socket, JSONObject(String(body)), "/synthetic/new")
                }
            }
            worker.start()
            assertEquals("/synthetic/new", HarnessApi({ root }, cookieHeader = { null }).createDirectory("/synthetic", "new"))
            worker.join(3000)
        }
    }

    @Test fun unaryHeadersIncludeAccessAndOnlyMatchingHarnessCookie() {
        ServerSocket(0).use { host ->
            val root = "http://127.0.0.1:${host.localPort}"
            val own = harnessCookieName(root)
            val other = harnessCookieName("http://127.0.0.1:${host.localPort + 1}")
            val captured = CompletableFuture<Map<String, String>>()
            val worker = Thread {
                host.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers = readHeaders(reader)
                    val body = CharArray(headers.getValue("content-length").toInt())
                    readBody(reader, body)
                    val request = JSONObject(String(body))
                    assertEquals("session/list", request.getString("method"))
                    assertTrue(request.getJSONObject("payload").getJSONObject("args").has("_request"))
                    captured.complete(headers)
                    respond(socket, request, JSONObject().put("items", JSONArray()))
                }
            }
            worker.start()
            val api = HarnessApi({ root }, { "external-test" }, { "$own=ours; $other=foreign; CF_Authorization=access" })
            assertTrue(api.sessions().isEmpty())
            val headers = captured.get(3, TimeUnit.SECONDS)
            assertEquals("Bearer external-test", headers["authorization"])
            assertEquals("$own=ours; CF_Authorization=access", headers["cookie"])
            worker.join(3000)
        }
    }

    @Test fun acceptedPromptWithLostReceiptAndRetryHasOneAdmission() {
        ServerSocket(0).use { host ->
            val root = "http://127.0.0.1:${host.localPort}"
            val admissions = linkedSetOf<String>()
            val finished = CompletableFuture<Unit>()
            val worker = Thread {
                try {
                    repeat(2) { index -> host.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        reader.readLine()
                        val headers = readHeaders(reader)
                        val body = CharArray(headers.getValue("content-length").toInt())
                        readBody(reader, body)
                        val envelope = JSONObject(String(body))
                        val request = envelope.getJSONObject("payload").getJSONObject("args").getJSONObject("request")
                        admissions += request.getString("requestId")
                        if (index == 0) {
                            // The host accepted the prompt but its transport receipt is lost.
                            socket.getOutputStream().write("HTTP/1.1 500 Lost receipt\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else respond(socket, envelope, JSONObject().put("accepted", true))
                    } }
                    finished.complete(Unit)
                } catch (e: Throwable) { finished.completeExceptionally(e) }
            }
            worker.start()
            val api = HarnessApi({ root }, cookieHeader = { null })
            val submission = HarnessPromptSubmission()
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Synthetic prompt"))
            val id = submission.begin(root, "session", "Synthetic prompt", "queue")
            assertThrows(java.io.IOException::class.java) { api.prompt("session", content, requestId = id) }
            api.prompt("session", content, requestId = submission.begin(root, "session", "Synthetic prompt", "queue"))
            finished.get(3, TimeUnit.SECONDS)
            assertEquals(1, admissions.size)
        }
    }

    @Test fun lostReceiptRetryAcrossSessionsHasOneAdmissionPerSession() {
        ServerSocket(0).use { host ->
            val root = "http://127.0.0.1:${host.localPort}"
            val admissions = mutableMapOf<String, MutableSet<String>>()
            val finished = CompletableFuture<Unit>()
            val worker = Thread {
                try {
                    repeat(3) { index -> host.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        reader.readLine()
                        val headers = readHeaders(reader)
                        val body = CharArray(headers.getValue("content-length").toInt())
                        readBody(reader, body)
                        val envelope = JSONObject(String(body))
                        val request = envelope.getJSONObject("payload").getJSONObject("args").getJSONObject("request")
                        admissions.getOrPut(request.getString("sessionId")) { linkedSetOf() }
                            .add(request.getString("requestId"))
                        if (index == 0) {
                            socket.getOutputStream().write("HTTP/1.1 500 Lost receipt\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else respond(socket, envelope, JSONObject().put("accepted", true))
                    } }
                    finished.complete(Unit)
                } catch (e: Throwable) { finished.completeExceptionally(e) }
            }
            worker.start()
            val api = HarnessApi({ root }, cookieHeader = { null })
            val submission = HarnessPromptSubmission()
            val contentA = JSONArray().put(JSONObject().put("type", "text").put("text", "Unconfirmed draft A"))
            val contentB = JSONArray().put(JSONObject().put("type", "text").put("text", "Unrelated draft B"))
            val idA = submission.begin(root, "session-a", "Unconfirmed draft A", "queue")
            assertThrows(java.io.IOException::class.java) { api.prompt("session-a", contentA, requestId = idA) }
            val idB = submission.begin(root, "session-b", "Unrelated draft B", "queue")
            api.prompt("session-b", contentB, requestId = idB)
            submission.accepted(idB)
            api.prompt("session-a", contentA, requestId = submission.begin(root, "session-a", "Unconfirmed draft A", "queue"))
            finished.get(3, TimeUnit.SECONDS)
            assertEquals(setOf(idA), admissions["session-a"])
            assertEquals(setOf(idB), admissions["session-b"])
        }
    }

    private fun readHeaders(reader: java.io.BufferedReader): Map<String, String> = buildMap {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
        }
    }
    private fun readBody(reader: java.io.BufferedReader, body: CharArray) {
        var offset = 0
        while (offset < body.size) {
            val count = reader.read(body, offset, body.size - offset)
            if (count < 0) error("Incomplete test request")
            offset += count
        }
    }
    private fun respond(socket: java.net.Socket, request: JSONObject, value: Any) {
        val result = JSONObject().put("type", "server-response").put("rpcId", request.getString("rpcId"))
            .put("result", JSONObject().put("ok", true).put("value", value)).toString().toByteArray()
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${result.size}\r\nConnection: close\r\n\r\n".toByteArray() + result)
    }
}
