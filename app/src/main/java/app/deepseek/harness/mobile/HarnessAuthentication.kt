package app.deepseek.harness.mobile

import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal object HarnessAuthentication {
    fun cleanRedirect(origin: String, location: String): String {
        val target = URI("$origin/").resolve(location)
        val base = URI(origin)
        if (target.scheme != base.scheme || target.rawAuthority != base.rawAuthority || target.rawQuery != null || target.rawFragment != null || target.path != "/") {
            throw IOException("Harness authentication redirected outside the server root")
        }
        return target.toString()
    }

    /** Only index authentication receives the transient launch token. */
    fun exchange(connection: ServerConnection, accessToken: String? = null) {
        val token = connection.launchToken ?: return
        val request = URL("${connection.origin}/?token=${URLEncoder.encode(token, "UTF-8")}").openConnection() as HttpURLConnection
        request.instanceFollowRedirects = false
        request.connectTimeout = 15000
        request.readTimeout = 25000
        accessToken?.let { request.setRequestProperty("Authorization", "Bearer $it") }
        harnessCookies(connection.origin, CookieManager.getInstance().getCookie(connection.origin))?.let { request.setRequestProperty("Cookie", it) }
        try {
            val status = request.responseCode
            if (status != 303 && isAccessChallenge(status, request.getHeaderField("Location"), request.getHeaderField("WWW-Authenticate"))) {
                throw HarnessApi.AuthenticationRequired(access = true)
            }
            if (status != 303) throw IOException("Harness 登录链接已失效，请从本机 dsh 获取新的链接")
            cleanRedirect(connection.origin, request.getHeaderField("Location") ?: throw IOException("Missing authentication redirect"))
            val cookies = request.headerFields.entries.filter { it.key?.equals("Set-Cookie", true) == true }.flatMap { it.value }
            if (cookies.isEmpty()) throw IOException("Harness did not issue a session cookie")
            val manager = CookieManager.getInstance()
            val done = java.util.concurrent.CountDownLatch(cookies.size)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                cookies.forEach { manager.setCookie(connection.origin, it) { done.countDown() } }
            }
            if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw IOException("Harness cookie could not be saved")
            manager.flush()
        } finally { request.disconnect() }
    }
}

internal fun harnessCookieName(origin: String): String {
    val uri = URI(origin)
    val defaultPort = if (uri.scheme == "https") 443 else 80
    val port = uri.port.takeIf { it != -1 && it != defaultPort && it != 80 }
    val host = uri.host.lowercase().let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
    val authority = host + (port?.let { ":$it" } ?: "")
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(authority.toByteArray(Charsets.UTF_8))
    return "dsh-auth-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

/** Keep external access cookies while withholding Harness cookies for other ports. */
internal fun harnessCookies(origin: String, header: String?): String? {
    val expected = harnessCookieName(origin)
    return header?.split(';')?.map(String::trim)?.filter {
        val name = it.substringBefore('=')
        !name.startsWith("dsh-auth-") || name == expected
    }?.joinToString("; ")
}
