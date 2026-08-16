package cool.rin.deepseekremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun `missing preference preserves the existing dark default`() {
        assertEquals(AppThemePreference.DARK, AppThemePreference.fromStored(null))
        assertEquals(AppThemePreference.DARK, AppThemePreference.fromStored("unknown"))
    }

    @Test
    fun `system follows the resolved Android appearance`() {
        assertTrue(AppThemePreference.SYSTEM.resolvesDark(systemDark = true))
        assertFalse(AppThemePreference.SYSTEM.resolvesDark(systemDark = false))
        assertTrue(AppThemePreference.DARK.resolvesDark(systemDark = false))
        assertFalse(AppThemePreference.LIGHT.resolvesDark(systemDark = true))
    }

    @Test
    fun `light and dark palettes keep semantic contrast`() {
        assertTrue(AppPalettes.DARK.surface != AppPalettes.DARK.text)
        assertTrue(AppPalettes.LIGHT.surface != AppPalettes.LIGHT.text)
        assertTrue(AppPalettes.DARK.primaryButtonFill != AppPalettes.DARK.primaryButtonText)
        assertTrue(AppPalettes.LIGHT.primaryButtonFill != AppPalettes.LIGHT.primaryButtonText)
    }
}
