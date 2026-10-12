package app.deepseek.harness.mobile
import java.io.IOException
import java.io.OutputStream
/** Own the destination for one transfer, including failed or partial downloads. */
internal fun writeSessionExport(transport:HarnessTransport,sessionId:String,open:()->OutputStream?,delete:()->Unit) {
 try {open()?.use { transport.export(sessionId,true,it) } ?: throw IOException("Unable to open export destination")}
 catch(error:Exception){runCatching{delete()};throw error}
}
