package cool.rin.deepseekremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HarnessHttpFailureTest {
    @Test
    fun settingsForbiddenIsNotTreatedAsAuthentication() {
        assertEquals(
            HarnessHttpFailure.SETTINGS_ACCESS_FORBIDDEN,
            classifyHarnessHttpFailure("settings.describe", 403),
        )
    }

    @Test
    fun authenticationResponsesKeepOpeningTheSignInFlow() {
        assertEquals(HarnessHttpFailure.AUTHENTICATION, classifyHarnessHttpFailure("session.list", 302))
        assertEquals(HarnessHttpFailure.AUTHENTICATION, classifyHarnessHttpFailure("session.list", 401))
        assertEquals(HarnessHttpFailure.AUTHENTICATION, classifyHarnessHttpFailure("session.list", 403))
    }

    @Test
    fun successAndServerErrorsRemainDistinct() {
        assertNull(classifyHarnessHttpFailure("session.list", 200))
        assertEquals(HarnessHttpFailure.HTTP, classifyHarnessHttpFailure("session.list", 500))
    }
}
