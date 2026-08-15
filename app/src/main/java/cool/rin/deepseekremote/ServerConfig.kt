package cool.rin.deepseekremote

import java.net.URI
import java.util.Locale

internal object ServerConfig {
    fun normalize(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "请输入 Harness 服务器地址" }
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }
            .getOrElse { throw IllegalArgumentException("服务器地址格式不正确") }
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        require(scheme == "https" || scheme == "http") { "仅支持 HTTPS 或内网 HTTP 地址" }
        require(!host.isNullOrBlank()) { "服务器地址缺少主机名或 IP" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "请只填写服务器地址，不要包含账号、参数或片段"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "服务器地址不能包含路径" }
        require(uri.port in -1..65535) { "服务器端口不正确" }
        require(scheme != "http" || isPrivateHost(host)) {
            "公网地址必须使用 HTTPS；HTTP 仅允许私有内网地址"
        }
        return URI(scheme, null, host, uri.port, null, null, null).toString()
    }

    fun isPrivateHost(host: String): Boolean {
        val normalized = host.trim('[', ']').lowercase(Locale.US)
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")) return true
        val ipv4 = normalized.split('.').mapNotNull { it.toIntOrNull()?.takeIf { part -> part in 0..255 } }
        if (ipv4.size == 4) {
            return ipv4[0] == 10 ||
                (ipv4[0] == 172 && ipv4[1] in 16..31) ||
                (ipv4[0] == 192 && ipv4[1] == 168) ||
                ipv4[0] == 127 ||
                (ipv4[0] == 169 && ipv4[1] == 254)
        }
        return normalized == "::1" ||
            normalized.startsWith("fc") ||
            normalized.startsWith("fd") ||
            normalized.startsWith("fe8") ||
            normalized.startsWith("fe9") ||
            normalized.startsWith("fea") ||
            normalized.startsWith("feb")
    }
}
