package app.deepseek.harness.mobile
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
class BridgeConnectionTest {
 @Test fun rejectsForeignOrEncodedRoutes() {
  for (path in listOf("https://two.example/rpc","//two.example/a","/a/%62","/a/../b","/a/b?x=1","/a/b#x","/a\\b/c")) {
   assertThrows(IllegalArgumentException::class.java) { BridgeConnection.parseRoutes("https://one.example", JSONObject().put("rpc",path).put("stream","/c/d").put("export","/e/f")) }
  }
 }
 @Test fun profileRedactsCredentials() {
  val profile=BridgeConnection("https://one.example","device","private-secret", "/a/b","/c/d","/e/f",1)
  assertFalse(profile.toString().contains("private-secret"))
  assertEquals("https://one.example",profile.origin)
 }
 @Test fun invalidVersionAndDuplicatesRejected() {
  assertThrows(IllegalArgumentException::class.java) { BridgeConnection("https://one.example","device","secret","/a/b","/c/d","/e/f",2) }
  assertThrows(IllegalArgumentException::class.java) { BridgeConnection.parseRoutes("https://one.example",JSONObject().put("rpc","/a/b").put("stream","/a/b").put("export","/e/f")) }
 }
}
