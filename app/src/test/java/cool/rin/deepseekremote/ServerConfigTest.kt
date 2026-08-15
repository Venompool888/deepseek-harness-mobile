package cool.rin.deepseekremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {
    @Test
    fun normalizesHttpsAndRemovesTrailingSlash() {
        assertEquals("https://harness.example.com", ServerConfig.normalize("harness.example.com/"))
        assertEquals("https://harness.example.com:8443", ServerConfig.normalize("HTTPS://Harness.Example.com:8443"))
    }

    @Test
    fun allowsCleartextOnlyForPrivateNetworkHosts() {
        assertEquals("http://192.168.1.50:3000", ServerConfig.normalize("http://192.168.1.50:3000"))
        assertEquals("http://harness.local:3000", ServerConfig.normalize("http://harness.local:3000"))
        assertTrue(runCatching { ServerConfig.normalize("http://example.com") }.isFailure)
        assertTrue(runCatching { ServerConfig.normalize("http://8.8.8.8") }.isFailure)
    }

    @Test
    fun rejectsCredentialsPathsAndUnsupportedSchemes() {
        assertTrue(runCatching { ServerConfig.normalize("https://user@example.com") }.isFailure)
        assertTrue(runCatching { ServerConfig.normalize("https://example.com/harness") }.isFailure)
        assertTrue(runCatching { ServerConfig.normalize("ftp://example.com") }.isFailure)
    }
}
