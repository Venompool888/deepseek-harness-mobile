package cool.rin.deepseekremote

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `missing preference follows the system`() {
        assertEquals(AppLanguagePreference.SYSTEM, AppLanguagePreference.fromStored(null))
    }

    @Test
    fun `system supports Chinese and English`() {
        assertEquals(AppLanguage.CHINESE, AppLanguagePreference.SYSTEM.resolve("zh-CN"))
        assertEquals(AppLanguage.CHINESE, AppLanguagePreference.SYSTEM.resolve("zh-Hant-TW"))
        assertEquals(AppLanguage.ENGLISH, AppLanguagePreference.SYSTEM.resolve("en-AU"))
    }

    @Test
    fun `unsupported system language falls back to English`() {
        assertEquals(AppLanguage.ENGLISH, AppLanguagePreference.SYSTEM.resolve("ja-JP"))
        assertEquals(AppLanguage.ENGLISH, AppLanguagePreference.SYSTEM.resolve(""))
    }

    @Test
    fun `explicit preference overrides the system`() {
        assertEquals(AppLanguage.CHINESE, AppLanguagePreference.CHINESE.resolve("en-AU"))
        assertEquals(AppLanguage.ENGLISH, AppLanguagePreference.ENGLISH.resolve("zh-CN"))
    }
}
