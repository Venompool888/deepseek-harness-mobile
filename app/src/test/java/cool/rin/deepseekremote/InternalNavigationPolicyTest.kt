package cool.rin.deepseekremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalNavigationPolicyTest {
    private val harness = "https://harness.example.com:8443"

    @Test
    fun harnessAndCloudflareAccessAreTrustedOverHttps() {
        assertTrue(InternalNavigationPolicy.isTrusted(harness, "https", "harness.example.com", 8443))
        assertTrue(InternalNavigationPolicy.isTrusted(harness, "HTTPS", "team.cloudflareaccess.com"))
    }

    @Test
    fun lookalikeAndInsecureHostsAreRejected() {
        assertFalse(InternalNavigationPolicy.isTrusted(harness, "http", "harness.example.com", 8443))
        assertFalse(InternalNavigationPolicy.isTrusted(harness, "https", "harness.example.com"))
        assertFalse(InternalNavigationPolicy.isTrusted(harness, "https", "harness.example.com.example.org", 8443))
        assertFalse(InternalNavigationPolicy.isTrusted(harness, "https", "evilcloudflareaccess.com"))
        assertFalse(InternalNavigationPolicy.isTrusted(harness, "https", null))
    }

    @Test
    fun onlyHarnessRootAndAccessCallbackAreRecoveryDestinations() {
        assertTrue(InternalNavigationPolicy.isHarnessAuthDestination(harness, "https", "harness.example.com", 8443, "/"))
        assertTrue(
            InternalNavigationPolicy.isHarnessAuthDestination(
                harness,
                "https",
                "harness.example.com",
                8443,
                "/cdn-cgi/access/authorized",
            ),
        )
        assertFalse(InternalNavigationPolicy.isHarnessAuthDestination(harness, "http", "harness.example.com", 8443, "/"))
        assertFalse(InternalNavigationPolicy.isHarnessAuthDestination(harness, "https", "example.com", 8443, "/"))
        assertFalse(InternalNavigationPolicy.isHarnessAuthDestination(harness, "https", "harness.example.com", 443, "/"))
        assertFalse(InternalNavigationPolicy.isHarnessAuthDestination(harness, "https", "harness.example.com", 8443, "/api"))
    }
}
