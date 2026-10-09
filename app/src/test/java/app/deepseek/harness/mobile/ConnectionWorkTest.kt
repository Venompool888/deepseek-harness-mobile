package app.deepseek.harness.mobile
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.OutputStream
import okhttp3.*
class ConnectionWorkTest {
 @Test fun queuedOldMutationCannotReachNewServer(){
  val queue=Executors.newSingleThreadExecutor();val owner=ConnectionWorkOwner("A");val worker=ConnectionWorkExecutor(queue,owner)
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=mutableListOf<String>()
  worker.execute{entered.countDown();release.await()};assertTrue(entered.await(2,TimeUnit.SECONDS))
  worker.execute{synchronized(calls){calls.add(owner.value())}}
  owner.replace("B");release.countDown();worker.execute{synchronized(calls){calls.add(owner.value())}}
  worker.shutdown();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));assertEquals(listOf("B"),calls)
 }
 @Test fun delayedOldPromptHasZeroRequestsAtReplacementCarrier(){
  val hitsA=mutableListOf<String>();val hitsB=mutableListOf<String>()
  fun api(root:String,hits:MutableList<String>):HarnessApi {
   val transport=object:HarnessTransport {
    override fun rpc(envelope:JSONObject):JSONObject {hits.add(envelope.getJSONObject("payload").getJSONObject("args").getJSONObject("request").getString("sessionId"));return JSONObject().put("type","server-response").put("rpcId",envelope.getString("rpcId")).put("result",JSONObject().put("ok",true).put("value",JSONObject.NULL))}
    override fun openMux(listener:WebSocketListener):WebSocket=throw UnsupportedOperationException()
    override fun streamFailure(response:Response?,cause:Throwable)=java.io.IOException(cause)
    override fun export(sessionId:String,includeDescendants:Boolean,sink:OutputStream){}
   }
   return HarnessApi({root},transport={transport})
  }
  val owner=ConnectionWorkOwner(api("https://a.invalid",hitsA));val worker=ConnectionWorkExecutor(Executors.newSingleThreadExecutor(),owner)
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  worker.execute{entered.countDown();release.await()};assertTrue(entered.await(2,TimeUnit.SECONDS))
  worker.execute{owner.value().prompt("A-session",JSONArray())}
  owner.replace(api("https://b.invalid",hitsB));release.countDown();worker.execute{owner.value().prompt("B-session",JSONArray())}
  worker.shutdown();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));assertTrue(hitsA.isEmpty());assertEquals(listOf("B-session"),hitsB)
 }
 @Test fun inFlightOperationKeepsItsOwnCarrierAndDiscardsLateCallback(){
  val owner=ConnectionWorkOwner("A");var callback:Runnable?=null;var used=""
  val work=owner.bind(Runnable {owner.replace("B");used=owner.value();callback=owner.callback(Runnable{used="polluted B"})})
  work.run();callback!!.run();assertEquals("A",used);assertEquals("B",owner.value())
 }
 @Test fun sameOriginRePairAndForgetInvalidateCallbacksAndExportAdmission(){
  val owner=ConnectionWorkOwner("origin:deviceA");var applied=false
  val before=owner.capture();val callback=owner.callback(Runnable{applied=true})
  owner.replace("origin:deviceB");callback.run();assertFalse(applied);assertFalse(owner.isCurrent(before))
  val export=owner.capture();owner.replace("unconfigured");var deleted=false
  owner.bind(export,Runnable{fail("stale export started")},Runnable{deleted=true}).run();assertTrue(deleted)
 }
 @Test fun oldBackgroundPollCannotConsumeNewWatch(){
  val owner=ConnectionWorkOwner("A");val poll=owner.capture();val watched=mutableListOf("A-session")
  owner.replace("B");watched.clear();watched.add("B-session")
  owner.callback(poll,Runnable{watched.clear()}).run();assertEquals(listOf("B-session"),watched)
 }
}
