package cool.rin.deepseekremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskNotificationDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TaskMonitorService.postDebugPreview(context, intent.getBooleanExtra("completed", false))
    }
}
