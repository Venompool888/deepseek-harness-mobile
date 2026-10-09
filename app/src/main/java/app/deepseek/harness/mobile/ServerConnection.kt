package app.deepseek.harness.mobile

import java.net.URI
import java.net.URLDecoder

/** A launch secret is transient and is deliberately absent from diagnostic output. */
internal class ServerConnection(val origin: String, val launchToken: String?) {
    override fun toString(): String = origin
    companion object {
        fun parse(input: String, language: AppLanguage = AppLanguage.ENGLISH): ServerConnection {
            val text = input.trim().let { if (it.contains("://")) it else "https://$it" }
            val uri = runCatching { URI(text) }.getOrElse { throw IllegalArgumentException(language.text("连接链接格式不正确", "Invalid connection link")) }
            require(uri.fragment == null && uri.userInfo == null) { language.text("连接链接不能包含账号或片段", "Connection links cannot contain credentials or fragments") }
            val origin = ServerConfig.normalize(URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString(), language)
            val query = uri.rawQuery
            val token = if (query == null) null else {
                val pairs = query.split('&').map { part -> part.split('=', limit = 2) }
                require(pairs.size == 1 && pairs[0].size == 2 && pairs[0][0] == "token") {
                    language.text("请粘贴服务器地址或单个有效连接链接", "Paste a server address or a valid connection link")
                }
                URLDecoder.decode(pairs[0][1], "UTF-8").also {
                    require(it.isNotBlank() && it.length <= 4096) { language.text("连接凭据为空或无效", "Connection credentials are empty or invalid") }
                }
            }
            return ServerConnection(origin, token)
        }
    }
}
