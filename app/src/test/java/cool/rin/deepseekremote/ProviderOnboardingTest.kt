package cool.rin.deepseekremote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderOnboardingTest {
    @Test
    fun promptsForWritableMissingOfficialCredential() {
        val projected = ProviderOnboardingProjection.project(
            providers(),
            settings(),
            credentials(configured = false, writable = true),
        )

        assertEquals(
            ProviderOnboarding.MissingCredential("DeepSeek", "DEEPSEEK_API_KEY"),
            projected,
        )
    }

    @Test
    fun anyUsableProviderSkipsOfficialCredentialPrompt() {
        val other = JSONObject()
            .put("provider", "native-auth")
            .put("displayName", "Native")
            .put("settingsNs", "")
            .put("settingsPath", JSONArray())
            .put("active", true)
        val value = providers().apply { getJSONArray("providers").put(other) }

        assertEquals(
            ProviderOnboarding.Ready,
            ProviderOnboardingProjection.project(value, settings(), credentials(false, true)),
        )
    }

    @Test
    fun readOnlyCredentialDoesNotOfferAnInputThatCannotSave() {
        assertEquals(
            ProviderOnboarding.Unavailable("API Key 由启动环境提供，无法在客户端修改"),
            ProviderOnboardingProjection.project(providers(), settings(), credentials(false, false)),
        )
    }

    @Test
    fun keyValidationMatchesWebInputRules() {
        assertEquals("sk-live", ApiKeyInput.normalize("  sk-live  "))
        assertNull(ApiKeyInput.normalize("   "))
        assertNull(ApiKeyInput.normalize("DEEPSEEK_API_KEY=sk-live"))
        assertNull(ApiKeyInput.normalize("\"sk-live\""))
        assertNull(ApiKeyInput.normalize("sk live"))
    }

    private fun providers() = JSONObject().put("providers", JSONArray().put(JSONObject()
        .put("provider", "deepseek-official")
        .put("displayName", "DeepSeek")
        .put("settingsNs", "llm-deepseek")
        .put("settingsPath", JSONArray())
        .put("active", true)))

    private fun settings() = JSONObject()
        .put("writable", true)
        .put("namespaces", JSONArray().put(JSONObject()
            .put("ns", "llm-deepseek")
            .put("value", JSONObject().put("apiKeyEnv", "DEEPSEEK_API_KEY"))))

    private fun credentials(configured: Boolean, writable: Boolean) = JSONObject()
        .put("credentials", JSONObject().put("DEEPSEEK_API_KEY", JSONObject()
            .put("configured", configured)
            .put("writable", writable)))
}
