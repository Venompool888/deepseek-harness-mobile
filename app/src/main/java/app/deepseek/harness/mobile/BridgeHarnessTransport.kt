package app.deepseek.harness.mobile
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
internal class BridgeHarnessTransport(private val profile: BridgeConnection): HarnessTransport {
 private fun headers()=mapOf("Authorization" to "Bearer ${profile.token}","Accept" to "application/json")
 private fun check(status: Int) {when {status==404||status==401||status==403->throw BridgeAuthenticationRequired();status==502||status==503->throw BridgeUpstreamUnavailable();status !in 200..299->throw IOException("Private server HTTP $status")}}
 override fun rpc(envelope: JSONObject): JSONObject {val c=transportRequest(profile.origin+profile.rpcPath,"POST",envelope.toString(),headers());try{check(c.responseCode);return transportJSON(c)}finally{c.disconnect()}}
 override fun openMux(listener: WebSocketListener): WebSocket {val url=(profile.origin+profile.streamPath).replaceFirst("https://","wss://").replaceFirst("http://","ws://");return harnessStreamClient.newWebSocket(Request.Builder().url(url).header("Authorization","Bearer ${profile.token}").build(),listener)}
 override fun streamFailure(response: Response?,cause: Throwable): IOException = when(response?.code){404,401,403->BridgeAuthenticationRequired();502,503->BridgeUpstreamUnavailable();else->IOException("Private server connection interrupted",cause)}
 override fun export(sessionId: String,includeDescendants: Boolean,sink: OutputStream) {val body=JSONObject().put("sessionId",sessionId).put("includeDescendants",includeDescendants).toString();val c=transportRequest(profile.origin+profile.exportPath,"POST",body,headers(),300000);try{check(c.responseCode);if(!c.contentType.orEmpty().lowercase().contains("application/zip"))throw IOException("Invalid export response");c.inputStream.use{it.copyTo(sink)}}finally{c.disconnect()}}
}
