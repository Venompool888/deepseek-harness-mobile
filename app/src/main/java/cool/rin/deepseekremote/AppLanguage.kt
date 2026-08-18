package cool.rin.deepseekremote

import android.content.Context
import java.util.Locale

internal const val APP_PREFERENCES_NAME = "deepseek_remote_preferences"
internal const val APP_LANGUAGE_KEY = "app_language"

internal enum class AppLanguagePreference(val storedValue: String) {
    CHINESE("zh"),
    ENGLISH("en"),
    SYSTEM("system");

    companion object {
        fun fromStored(value: String?): AppLanguagePreference = entries.firstOrNull {
            it.storedValue == value
        } ?: SYSTEM
    }
}

internal enum class AppLanguage { CHINESE, ENGLISH }

internal fun AppLanguagePreference.resolve(systemLanguageTag: String?): AppLanguage = when (this) {
    AppLanguagePreference.CHINESE -> AppLanguage.CHINESE
    AppLanguagePreference.ENGLISH -> AppLanguage.ENGLISH
    AppLanguagePreference.SYSTEM -> when (Locale.forLanguageTag(systemLanguageTag.orEmpty()).language.lowercase(Locale.ROOT)) {
        "zh" -> AppLanguage.CHINESE
        "en" -> AppLanguage.ENGLISH
        else -> AppLanguage.ENGLISH
    }
}

internal fun AppLanguage.text(chinese: String, english: String): String =
    if (this == AppLanguage.CHINESE) chinese else english

internal fun Context.resolvedAppLanguage(): AppLanguage {
    val preference = AppLanguagePreference.fromStored(
        getSharedPreferences(APP_PREFERENCES_NAME, Context.MODE_PRIVATE).getString(APP_LANGUAGE_KEY, null),
    )
    return preference.resolve(resources.configuration.locales.get(0).toLanguageTag())
}
