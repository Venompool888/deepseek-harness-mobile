package app.deepseek.harness.mobile
import org.json.JSONObject
internal data class BridgeRoutes(val rpcPath: String,val streamPath: String,val exportPath: String)
internal class BridgeConnection(origin: String,val deviceId: String,val token: String,val rpcPath: String,val streamPath: String,val exportPath: String,val protocolVersion: Int,val pairPath: String? = null,val deviceName: String = "Android") {
 val origin=ServerConfig.normalize(origin)
 init {
  require(protocolVersion==1) { "Unsupported private server version" }
  require(deviceId.isNotBlank() && deviceId.length<=256 && token.isNotBlank() && token.length<=512) { "Invalid device credentials" }
  parseRoutes(this.origin,JSONObject().put("rpc",rpcPath).put("stream",streamPath).put("export",exportPath))
  pairPath?.let(::validatePath)
 }
 override fun toString()=origin
 fun json()=JSONObject().put("protocolVersion",protocolVersion).put("origin",origin).put("deviceId",deviceId).put("deviceToken",token).put("deviceName",deviceName).put("pairPath",pairPath)
  .put("routes",JSONObject().put("rpc",rpcPath).put("stream",streamPath).put("export",exportPath))
 companion object {
  fun validatePath(path: String): String { require(path.length<=256 && Regex("^/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+$").matches(path)) { "Invalid private server path" };return path }
  fun parseRoutes(origin: String,json: JSONObject): BridgeRoutes {
   ServerConfig.normalize(origin)
   val routes=BridgeRoutes(validatePath(json.getString("rpc")),validatePath(json.getString("stream")),validatePath(json.getString("export")))
   require(setOf(routes.rpcPath,routes.streamPath,routes.exportPath).size==3) { "Duplicate private server paths" };return routes
  }
 }
}
