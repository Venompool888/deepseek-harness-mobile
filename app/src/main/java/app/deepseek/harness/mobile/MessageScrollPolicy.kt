package app.deepseek.harness.mobile

internal object MessageScrollPolicy {
    fun shouldFollowBottom(distanceFromBottomPx: Int, thresholdPx: Int, force: Boolean = false): Boolean =
        force || distanceFromBottomPx <= thresholdPx
}
