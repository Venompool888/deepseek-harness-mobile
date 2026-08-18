package cool.rin.deepseekremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageScrollPolicyTest {
    @Test
    fun followsWhenViewportIsAlreadyNearBottom() {
        assertTrue(MessageScrollPolicy.shouldFollowBottom(distanceFromBottomPx = 0, thresholdPx = 72))
        assertTrue(MessageScrollPolicy.shouldFollowBottom(distanceFromBottomPx = 72, thresholdPx = 72))
    }

    @Test
    fun preservesManualReadingPositionAwayFromBottom() {
        assertFalse(MessageScrollPolicy.shouldFollowBottom(distanceFromBottomPx = 73, thresholdPx = 72))
    }

    @Test
    fun newPromptCanForceFollowing() {
        assertTrue(MessageScrollPolicy.shouldFollowBottom(distanceFromBottomPx = 500, thresholdPx = 72, force = true))
    }
}
