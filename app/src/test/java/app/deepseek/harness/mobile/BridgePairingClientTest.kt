package app.deepseek.harness.mobile
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class BridgePairingClientTest {
 @Test fun doesNotAcceptUntrustedMapping() {
  val json=JSONObject("""{"protocolVersion":1,"deviceId":"d","deviceToken":"secret","routes":{"rpc":"//other.example/a","stream":"/a/b","export":"/c/d"}}""")
  assertThrows(IllegalArgumentException::class.java) { BridgePairingClient.decode("https://one.example",json) }
 }
 @Test fun configCannotContainCredentialsOrForeignPath() {
  for (text in listOf("""{"protocolVersion":1,"origin":"https://one.example","pairPath":"https://two.example/a"}""","""{"protocolVersion":1,"origin":"https://one.example/?token=secret","pairPath":"/a/b"}""")) {
   assertThrows(IllegalArgumentException::class.java) { BridgePairingClient.parseConfig(text) }
  }
 }
}
