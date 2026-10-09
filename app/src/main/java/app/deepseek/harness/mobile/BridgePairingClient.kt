package app.deepseek.harness.mobile
import org.json.JSONObject
import java.net.URI
internal data class BridgePairingConfig(val origin:String,val pairPath:String) {override fun toString()=origin}
internal class BridgePairingClient {
 fun pair(origin:String,pairPath:String,code:String,deviceName:String):BridgeConnection {
  val clean=privateOrigin(origin);BridgeConnection.validatePath(pairPath)
  require(code.length==43 && Regex("[A-Za-z0-9_-]+").matches(code)) { "Invalid pairing code" }
  require(deviceName.trim().isNotEmpty() && deviceName.codePointCount(0,deviceName.length)<=80 && deviceName.none{it.isISOControl()}) { "Invalid device name" }
  val body=JSONObject().put("deviceName",deviceName.trim()).put("pairingCode",code).toString()
  val c=transportRequest(clean+pairPath,"POST",body,emptyMap(),25000)
  try {if(c.responseCode==404)throw BridgeAuthenticationRequired();if(c.responseCode !in 200..299)throw java.io.IOException("Pairing could not be completed");val p=decode(clean,transportJSON(c));return BridgeConnection(p.origin,p.deviceId,p.token,p.rpcPath,p.streamPath,p.exportPath,p.protocolVersion,pairPath,deviceName.trim())}finally{c.disconnect()}
 }
 companion object {
  fun privateOrigin(origin:String):String {val clean=ServerConfig.normalize(origin);val uri=URI(clean);require(uri.scheme=="https" || uri.host.trim('[',']') in setOf("127.0.0.1","localhost","::1")) { "Private servers require HTTPS; HTTP is for local USB testing only" };return clean}
  fun parseConfig(text:String):BridgePairingConfig {
   try{val json=JSONObject(text);require(json.getInt("protocolVersion")==1) { "Unsupported private server version" };return BridgePairingConfig(privateOrigin(json.getString("origin")),BridgeConnection.validatePath(json.getString("pairPath")))}catch(e:IllegalArgumentException){throw e}catch(e:Exception){throw IllegalArgumentException("Invalid connection configuration")}
  }
  fun decode(origin:String,json:JSONObject):BridgeConnection {
   try{val routes=BridgeConnection.parseRoutes(origin,json.getJSONObject("routes"));return BridgeConnection(privateOrigin(origin),json.getString("deviceId"),json.getString("deviceToken"),routes.rpcPath,routes.streamPath,routes.exportPath,json.getInt("protocolVersion"))}catch(e:IllegalArgumentException){throw e}catch(e:Exception){throw IllegalArgumentException("Invalid pairing response")}
  }
 }
}
