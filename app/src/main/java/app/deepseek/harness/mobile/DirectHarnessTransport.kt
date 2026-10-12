package app.deepseek.harness.mobile
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
internal class DirectHarnessTransport(private val root: String,private val authorization: String?,private val cookie: String?): HarnessTransport {
 private fun headers():Map<String,String> = buildMap { put("Accept","application/json");authorization?.let{put("Authorization","Bearer $it")};harnessCookies(root,cookie)?.let{put("Cookie",it)} }
 private fun check(method: String,status: Int,location: String?,challenge: String?) {
  if(status in 300..399 || status==401 || status==403) {
   if(method=="settings/describe" && status==403) throw HarnessApi.SettingsAccessForbidden()
   throw HarnessApi.AuthenticationRequired(isAccessChallenge(status,location,challenge))
  }
  if(status !in 200..299) throw IOException("Harness HTTP $status")
 }
 override fun rpc(envelope: JSONObject): JSONObject {
  val method=envelope.getString("method");val c=transportRequest("$root/api/$method","POST",envelope.toString(),headers(),if(method=="session/prompt")45000 else 25000)
  try {check(method,c.responseCode,c.getHeaderField("Location"),c.getHeaderField("WWW-Authenticate"));if(!c.contentType.orEmpty().lowercase().contains("application/json"))throw HarnessApi.AuthenticationRequired(false);return transportJSON(c)} finally{c.disconnect()}
 }
 override fun openMux(listener: WebSocketListener): WebSocket {
  val request=Request.Builder().url(root.replaceFirst("https://","wss://").replaceFirst("http://","ws://")+"/api/remote.mux").header("Origin",root)
  headers().forEach{(k,v)->request.header(k,v)};return harnessStreamClient.newWebSocket(request.build(),listener)
 }
 override fun streamFailure(response: Response?,cause: Throwable): IOException = if(response?.code in 300..399 || response?.code==401 || response?.code==403) HarnessApi.AuthenticationRequired(isAccessChallenge(response?.code?:0,response?.header("Location"),response?.header("WWW-Authenticate"))) else IOException("Harness stream disconnected",cause)
 override fun export(sessionId: String,includeDescendants: Boolean,sink: OutputStream) {
  val id=URLEncoder.encode(sessionId,"UTF-8");val c=transportRequest("$root/api/session.export?sessionId=$id&includeDescendants=$includeDescendants","GET",null,headers(),300000)
  try{check("session.export",c.responseCode,c.getHeaderField("Location"),c.getHeaderField("WWW-Authenticate"));c.inputStream.use{it.copyTo(sink)}}finally{c.disconnect()}
 }
}
