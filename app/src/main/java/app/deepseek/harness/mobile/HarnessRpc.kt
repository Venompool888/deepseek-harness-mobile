package app.deepseek.harness.mobile

import java.io.IOException
import org.json.JSONObject

internal object HarnessRpc {
    fun request(rpcId: String, endpoint: String, args: JSONObject): JSONObject = JSONObject()
        .put("type", "client-request").put("rpcId", rpcId).put("method", endpoint)
        .put("payload", JSONObject().put("args", args))

    fun value(rpcId: String, response: JSONObject): Any? {
        if (response.optString("type") != "server-response" || response.optString("rpcId") != rpcId) {
            throw IOException("Harness response correlation failed")
        }
        val result = response.optJSONObject("result") ?: throw IOException("Invalid Harness response")
        return when (result.opt("ok")) {
            true -> result.opt("value").takeUnless { it === JSONObject.NULL }
            false -> {
                val error = result.optJSONObject("error") ?: throw IOException("Invalid Harness failure")
                throw HarnessApi.RemoteFailure(error.optString("code", "remote-error"), error.optString("message", "Harness request failed"))
            }
            else -> throw IOException("Invalid Harness result")
        }
    }
}
