package app.deepseek.harness.mobile
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
internal interface HarnessTransport {
 fun rpc(envelope: JSONObject): JSONObject
 fun openMux(listener: WebSocketListener): WebSocket
 fun export(sessionId: String,includeDescendants: Boolean,sink: OutputStream)
 fun streamFailure(response: Response?,cause: Throwable): IOException
}
internal class BridgeAuthenticationRequired : HarnessApi.AuthenticationRequired(false, "Device access is unavailable; pair again")
internal class BridgeUpstreamUnavailable : IOException("The server is reachable but its backend is unavailable")
internal val harnessStreamClient: OkHttpClient by lazy { OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() }
internal fun transportRequest(address: String,method: String,body: String?,headers: Map<String,String>,readTimeout: Int = 60000): HttpURLConnection {
 return (URL(address).openConnection() as HttpURLConnection).apply {
  requestMethod=method;instanceFollowRedirects=false;connectTimeout=15000;this.readTimeout=readTimeout
  headers.forEach { (key,value)->setRequestProperty(key,value) }
  if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json; charset=utf-8");val bytes=body.toByteArray(Charsets.UTF_8);setFixedLengthStreamingMode(bytes.size);outputStream.use{it.write(bytes)}}
 }
}
internal fun transportJSON(connection: HttpURLConnection): JSONObject {
 val contentType=connection.contentType.orEmpty().lowercase()
 if(!contentType.contains("application/json")) throw IOException("Invalid server response")
 val bytes=connection.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192);var total=0;while(true){val n=input.read(b);if(n<0)break;total+=n;if(total>16*1024*1024)throw IOException("Server response too large");out.write(b,0,n)};out.toByteArray()}
 return JSONObject(String(bytes,Charsets.UTF_8))
}
