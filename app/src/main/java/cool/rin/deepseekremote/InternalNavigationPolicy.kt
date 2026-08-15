package cool.rin.deepseekremote

import java.net.URI
import java.util.Locale

internal object InternalNavigationPolicy {
    fun isTrusted(baseUrl: String, scheme: String?, host: String?, port: Int = -1): Boolean {
        val normalizedScheme = scheme?.lowercase(Locale.US)
        val normalizedHost = host?.lowercase(Locale.US)
        val configured = URI(baseUrl)
        val sameOrigin = normalizedScheme == configured.scheme?.lowercase(Locale.US) &&
            normalizedHost == configured.host?.lowercase(Locale.US) &&
            effectivePort(normalizedScheme, port) == effectivePort(configured.scheme, configured.port)
        val cloudflareAccess = normalizedScheme == "https" &&
            normalizedHost?.endsWith(".cloudflareaccess.com") == true
        return sameOrigin || cloudflareAccess
    }

    fun isHarnessAuthDestination(baseUrl: String, scheme: String?, host: String?, port: Int, path: String?): Boolean {
        val configured = URI(baseUrl)
        return scheme.equals(configured.scheme, ignoreCase = true) &&
            host.equals(configured.host, ignoreCase = true) &&
            effectivePort(scheme, port) == effectivePort(configured.scheme, configured.port) &&
            (path == "/" || path == "/cdn-cgi/access/authorized")
    }

    private fun effectivePort(scheme: String?, port: Int): Int = when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
}
