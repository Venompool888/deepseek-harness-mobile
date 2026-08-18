package cool.rin.deepseekremote

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface ProviderOnboarding {
    data object Ready : ProviderOnboarding

    data class MissingCredential(
        val providerName: String,
        val ref: String,
    ) : ProviderOnboarding

    data class Unavailable(val reason: String) : ProviderOnboarding
}

internal object ProviderOnboardingProjection {
    private const val OFFICIAL_PROVIDER = "deepseek-official"
    private const val OFFICIAL_SETTINGS_NAMESPACE = "llm-deepseek"

    fun credentialRefs(providersValue: JSONObject, settingsValue: JSONObject): Set<String> {
        val namespaces = namespaces(settingsValue)
        return providersValue.optJSONArray("providers").objectsOrEmpty().mapNotNullTo(linkedSetOf()) { provider ->
            credentialRef(provider, namespaces)
        }
    }

    fun project(
        providersValue: JSONObject,
        settingsValue: JSONObject,
        credentialsValue: JSONObject,
    ): ProviderOnboarding {
        val providers = providersValue.optJSONArray("providers").objectsOrEmpty()
        val namespaces = namespaces(settingsValue)
        val credentials = credentialsValue.optJSONObject("credentials") ?: JSONObject()

        val usable = providers.any { provider ->
            if (!provider.optBoolean("active")) return@any false
            val ref = credentialRef(provider, namespaces) ?: return@any true
            credentials.optJSONObject(ref)?.optBoolean("configured") == true
        }
        if (usable) return ProviderOnboarding.Ready

        val official = providers.firstOrNull { provider ->
            provider.optString("provider") == OFFICIAL_PROVIDER &&
                provider.optString("settingsNs") == OFFICIAL_SETTINGS_NAMESPACE &&
                provider.optJSONArray("settingsPath").objectsOrStringsEmpty().isEmpty()
        } ?: return ProviderOnboarding.Unavailable("DeepSeek 官方模型未安装")
        if (!official.optBoolean("active")) {
            return ProviderOnboarding.Unavailable("DeepSeek 官方模型当前不可用")
        }
        val ref = credentialRef(official, namespaces)
            ?: return ProviderOnboarding.Unavailable("DeepSeek 官方模型未公开凭据配置")
        val credential = credentials.optJSONObject(ref)
            ?: return ProviderOnboarding.Unavailable("无法读取 API Key 配置状态")
        if (!settingsValue.optBoolean("writable")) {
            return ProviderOnboarding.Unavailable("Harness 设置为只读")
        }
        if (!credential.optBoolean("writable")) {
            return ProviderOnboarding.Unavailable("API Key 由启动环境提供，无法在客户端修改")
        }
        return ProviderOnboarding.MissingCredential(
            providerName = official.optString("displayName", "DeepSeek"),
            ref = ref,
        )
    }

    private fun namespaces(settingsValue: JSONObject): Map<String, JSONObject> =
        settingsValue.optJSONArray("namespaces").objectsOrEmpty().associateBy { it.optString("ns") }

    private fun credentialRef(
        provider: JSONObject,
        namespaces: Map<String, JSONObject>,
    ): String? {
        val namespace = namespaces[provider.optString("settingsNs")] ?: return null
        var value: Any? = namespace.opt("value")
        provider.optJSONArray("settingsPath").objectsOrStringsEmpty().forEach { segment ->
            value = (value as? JSONObject)?.opt(segment)
        }
        val ref = (value as? JSONObject)?.optString("apiKeyEnv").orEmpty()
        return ref.takeIf(String::isNotBlank)
    }

    private fun JSONArray?.objectsOrEmpty(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.objectsOrStringsEmpty(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}

internal object ApiKeyInput {
    private val envLine = Regex("^[A-Z][A-Z0-9_]*=[^=]")

    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || envLine.containsMatchIn(value) || isQuoted(value)) return null
        return value.takeIf { key -> key.all { character -> character.code in 0x21..0x7e } }
    }

    private fun isQuoted(value: String): Boolean {
        val first = value.firstOrNull() ?: return false
        return first in charArrayOf('\'', '"', '`') && value.length > 1 && value.last() == first
    }
}
