package app.deepseek.harness.mobile
import org.junit.Test
import org.junit.Assert.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.*
class ConnectionLifecycleTest {
 @Test fun closeWhileMuxConstructionIsBlockedInvalidatesLateSocket(){
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
  val fake=object:HarnessTransport{
   override fun rpc(envelope:JSONObject)=JSONObject()
   override fun export(id:String,desc:Boolean,sink:OutputStream){}
   override fun streamFailure(response:Response?,cause:Throwable)=java.io.IOException(cause)
   override fun openMux(listener:WebSocketListener):WebSocket {entered.countDown();check(release.await(3,TimeUnit.SECONDS));val socket=FakeSocket();listener.onOpen(socket,Response.Builder().request(socket.request()).protocol(Protocol.HTTP_1_1).code(101).message("upgrade").build());return socket}
  }
  val api=HarnessApi({"http://127.0.0.1"},transport={fake});val executor=Executors.newSingleThreadExecutor()
  executor.execute{try{api.streamMux{}}catch(_:Exception){}finally{done.countDown()}}
  try{assertTrue(entered.await(2,TimeUnit.SECONDS));api.closeMux();release.countDown();assertTrue("late socket holds stream worker",done.await(500,TimeUnit.MILLISECONDS))}finally{release.countDown();api.closeMux();executor.shutdownNow()}
 }
 private class FakeSocket:WebSocket{
  override fun request()=Request.Builder().url("http://127.0.0.1").build()
  override fun queueSize()=0L
  override fun send(text:String)=true
  override fun send(bytes:ByteString)=true
  override fun close(code:Int,reason:String?)=true
  override fun cancel(){}
 }
}
