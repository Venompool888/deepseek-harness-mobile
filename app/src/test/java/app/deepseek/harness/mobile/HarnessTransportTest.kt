package app.deepseek.harness.mobile
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
class HarnessTransportTest {
 @Test fun directHTMLResponseRequestsAuthentication() {
  ServerSocket(0).use { server ->
   val worker=Thread { server.accept().use { socket ->
    val reader=socket.getInputStream().bufferedReader();var length=0
    while(true){val line=reader.readLine();if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()};repeat(length){reader.read()}
    val body="<html>Login</html>";socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
   } };worker.start()
   assertThrows(HarnessApi.AuthenticationRequired::class.java){ DirectHarnessTransport("http://127.0.0.1:${server.localPort}",null,null).rpc(HarnessRpc.request("r","session/list",JSONObject())) }
   worker.join(2000)
  }
 }
 @Test fun bridgeUsesRandomPathWithoutCookieAndPreservesEnvelope() {
  ServerSocket(0).use { server ->
   val captured=CompletableFuture<String>()
   val worker=Thread { server.accept().use { socket ->
    val input=socket.getInputStream().bufferedReader();val headers=mutableListOf<String>();while(true){val line=input.readLine();if(line.isEmpty())break;headers.add(line)}
    val size=headers.first{it.startsWith("Content-Length:",true)}.substringAfter(':').trim().toInt();val body=CharArray(size);var off=0;while(off<size){off+=input.read(body,off,size-off)}
    captured.complete(headers.joinToString("\n")+"\n\n"+String(body));val response="""{"type":"server-response","rpcId":"r","result":{"ok":true,"value":null}}"""
    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray())
   } };worker.start()
   val profile=BridgeConnection("http://127.0.0.1:${server.localPort}","device","secret","/a/b","/c/d","/e/f",1)
   val envelope=HarnessRpc.request("r","session/list",JSONObject().put("_request",JSONObject()))
   val response=BridgeHarnessTransport(profile).rpc(envelope)
   assertEquals("r",response.getString("rpcId"));val request=captured.get();assertTrue(request.startsWith("POST /a/b HTTP/1.1"));assertTrue(request.contains("Authorization: Bearer secret"));assertFalse(request.contains("Cookie:"));assertTrue(request.endsWith(envelope.toString()));worker.join()
  }
 }
 @Test fun bridgeDoesNotFollowRedirectOrFallback() {
  ServerSocket(0).use { redirectTarget ->
   redirectTarget.soTimeout=250
   ServerSocket(0).use { server ->
    val worker=Thread { server.accept().use { socket ->
     val input=socket.getInputStream().bufferedReader();var length=0
     while(true){val line=input.readLine();if(line.isEmpty())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
     repeat(length){input.read()}
     socket.getOutputStream().write("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:${redirectTarget.localPort}/target\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    } };worker.start()
    val profile=BridgeConnection("http://127.0.0.1:${server.localPort}","device","secret","/a/b","/c/d","/e/f",1)
    assertThrows(java.io.IOException::class.java) { BridgeHarnessTransport(profile).rpc(HarnessRpc.request("r","session/list",JSONObject())) }
    assertThrows(java.net.SocketTimeoutException::class.java) { redirectTarget.accept().close() }
    worker.join()
   }
  }
 }
}
