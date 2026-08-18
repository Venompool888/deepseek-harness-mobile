package cool.rin.deepseekremote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors

class TaskMonitorService : Service() {
    private data class WatchedSession(
        var title: String,
        val observedAt: Long,
        var confirmedRunning: Boolean,
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val watched = linkedMapOf<String, WatchedSession>()
    @Volatile private var polling = false
    @Volatile private var serverUrl: String? = null
    private var foregroundId = PLACEHOLDER_NOTIFICATION_ID

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incomingUrl = intent?.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        if (incomingUrl.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val command = requireNotNull(intent)
        val ids = command.getStringArrayListExtra(EXTRA_SESSION_IDS).orEmpty()
        val titles = command.getStringArrayListExtra(EXTRA_SESSION_TITLES).orEmpty()
        val observedAt = command.getLongExtra(EXTRA_OBSERVED_AT, System.currentTimeMillis())
        val confirmed = command.getBooleanExtra(EXTRA_CONFIRMED_RUNNING, true)
        synchronized(lock) {
            if (serverUrl != null && serverUrl != incomingUrl) watched.clear()
            serverUrl = incomingUrl
            ids.forEachIndexed { index, id ->
                val title = titles.getOrNull(index).orEmpty().ifBlank { resolvedAppLanguage().text("未命名会话", "Untitled session") }
                val existing = watched[id]
                if (existing == null) watched[id] = WatchedSession(title, observedAt, confirmed)
                else {
                    existing.title = title
                    existing.confirmedRunning = existing.confirmedRunning || confirmed
                }
            }
        }
        val first = synchronized(lock) { watched.entries.firstOrNull() }
        if (first == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground(first.key, first.value.title)
        if (!polling) {
            polling = true
            worker.execute(::pollLoop)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        polling = false
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollLoop() {
        while (polling && !Thread.currentThread().isInterrupted) {
            try {
                pollOnce()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to poll Harness tasks", error)
            }
            if (!polling) break
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun pollOnce() {
        val root = serverUrl ?: return
        val api = HarnessApi(baseUrl = { root })
        val sessions = api.sessions()
        val running = sessions.filter { it.running }.associateBy { it.id }
        val completed = mutableListOf<Pair<String, WatchedSession>>()
        synchronized(lock) {
            running.forEach { (id, session) ->
                val title = session.title.orEmpty().ifBlank { resolvedAppLanguage().text("未命名会话", "Untitled session") }
                watched[id]?.let {
                    it.title = title
                    it.confirmedRunning = true
                }
            }
            watched.entries.toList().forEach { (id, state) ->
                if (id !in running && state.confirmedRunning) {
                    completed += id to state
                    watched.remove(id)
                }
            }
        }

        val manager = getSystemService(NotificationManager::class.java)
        val active = synchronized(lock) { watched.toMap() }
        active.forEach { (id, state) -> manager.notify(notificationId(id), liveNotification(this, id, state.title)) }
        active.entries.firstOrNull()?.let { (id, state) -> startAsForeground(id, state.title) }

        completed.forEach { (id, state) ->
            val excerpt = runCatching {
                CompletionNotificationText.excerpt(api.history(id).messages, state.observedAt)
            }.getOrNull()
            if (excerpt != null) manager.notify(notificationId(id), completedNotification(this, id, state.title, excerpt))
            else manager.cancel(notificationId(id))
        }

        if (active.isEmpty()) {
            polling = false
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            stopSelf()
        }
    }

    private fun startAsForeground(sessionId: String, title: String) {
        foregroundId = notificationId(sessionId)
        val notification = liveNotification(this, sessionId, title)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(foregroundId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(foregroundId, notification)
        }
    }

    companion object {
        const val EXTRA_OPEN_SESSION_ID = "open_session_id"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_SESSION_IDS = "session_ids"
        private const val EXTRA_SESSION_TITLES = "session_titles"
        private const val EXTRA_OBSERVED_AT = "observed_at"
        private const val EXTRA_CONFIRMED_RUNNING = "confirmed_running"
        private const val CHANNEL_LIVE = "harness_live_tasks"
        private const val PLACEHOLDER_NOTIFICATION_ID = 4100
        private const val POLL_INTERVAL_MS = 3_000L
        private const val COMPLETION_CLOUD_DURATION_MS = 60_000L
        private const val TAG = "HarnessTaskMonitor"

        internal fun watch(context: Context, serverUrl: String, sessions: List<HarnessApi.Session>, observedAt: Long = System.currentTimeMillis()) {
            val running = sessions.filter { it.running }
            if (running.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, TaskMonitorService::class.java)
                .putExtra(EXTRA_SERVER_URL, serverUrl)
                .putStringArrayListExtra(EXTRA_SESSION_IDS, ArrayList(running.map { it.id }))
                .putStringArrayListExtra(EXTRA_SESSION_TITLES, ArrayList(running.map { it.title.orEmpty() }))
                .putExtra(EXTRA_OBSERVED_AT, observedAt)
                .putExtra(EXTRA_CONFIRMED_RUNNING, true)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "Unable to start background task monitoring", it) }
        }

        internal fun postDebugPreview(context: Context, completed: Boolean) {
            ensureChannels(context)
            val id = notificationId("debug-notification-preview")
            val notification = if (completed) {
                completedNotification(
                    context,
                    "debug-notification-preview",
                    "通知功能验收",
                    "已完成通知与 OPPO 流体云呈现测试，正文会从最终回复开头自动截取。",
                )
            } else {
                liveNotification(context, "debug-notification-preview", "通知功能验收")
            }
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }

        private fun liveNotification(context: Context, sessionId: String, title: String): Notification {
            val language = context.resolvedAppLanguage()
            val builder = Notification.Builder(context, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_notification_harness)
                .setContentTitle(title)
                .setContentText(language.text("Harness 任务运行中", "Harness task running"))
                .setSubText("DeepSeek Harness")
                .setContentIntent(openAppIntent(context, sessionId))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
            if (Build.VERSION.SDK_INT >= 36) {
                builder.setProgress(100, 0, false)
                    .setStyle(
                        Notification.ProgressStyle()
                            .setStyledByProgress(false)
                            .setProgress(0)
                            .setProgressTrackerIcon(harnessIcon(context)),
                    )
                builder.setShortCriticalText(title.take(7))
                builder.addExtras(android.os.Bundle().apply {
                    putBoolean("android.requestPromotedOngoing", true)
                })
            } else {
                builder.setProgress(0, 0, true)
                    .setStyle(Notification.BigTextStyle().bigText(language.text("Harness 任务运行中", "Harness task running")))
            }
            return builder.build()
        }

        private fun completedNotification(context: Context, sessionId: String, title: String, excerpt: String): Notification {
            val language = context.resolvedAppLanguage()
            val builder = Notification.Builder(context, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_notification_harness)
                .setColor(Color.rgb(52, 199, 89))
                .setContentTitle(language.text("✅ 已完成 · $title", "✅ Completed · $title"))
                .setContentText(excerpt)
                .setSubText("DeepSeek Harness")
                .setContentIntent(openAppIntent(context, sessionId))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setTimeoutAfter(COMPLETION_CLOUD_DURATION_MS)
            if (Build.VERSION.SDK_INT >= 36) {
                builder.setProgress(100, 100, false)
                    .setStyle(
                        Notification.ProgressStyle()
                            .setStyledByProgress(false)
                            .setProgress(100)
                            .setProgressTrackerIcon(harnessIcon(context)),
                    )
                    .setShortCriticalText(language.text("✅ 已完成", "✅ Completed"))
                    .addExtras(android.os.Bundle().apply {
                        putBoolean("android.requestPromotedOngoing", true)
                    })
            } else {
                builder.setProgress(100, 100, false)
                    .setStyle(Notification.BigTextStyle().bigText(excerpt))
            }
            return builder.build()
        }

        private fun harnessIcon(context: Context): Icon =
            Icon.createWithResource(context, R.drawable.ic_notification_harness)

        private fun openAppIntent(context: Context, sessionId: String): PendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun ensureChannels(context: Context) {
            val language = context.resolvedAppLanguage()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_LIVE,
                language.text("运行中的 Harness 任务", "Running Harness tasks"),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = language.text("在任务运行期间显示状态，并允许系统提升为实时活动", "Shows task status while running and lets the system promote it to a live activity")
                setSound(null, null)
                enableVibration(false)
            })
        }

        private fun notificationId(sessionId: String): Int = 4_200 + (sessionId.hashCode() and 0x3fffffff) % 10_000
    }
}
