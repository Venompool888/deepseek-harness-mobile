package cool.rin.deepseekremote

internal object MessageScrollPolicy {
    fun shouldFollowBottom(distanceFromBottomPx: Int, thresholdPx: Int, force: Boolean = false): Boolean =
        force || distanceFromBottomPx <= thresholdPx
}
