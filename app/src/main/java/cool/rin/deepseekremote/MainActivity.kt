package cool.rin.deepseekremote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private var serverUrl: String? = null
    private val api = HarnessApi(baseUrl = { serverUrl ?: throw IOException("尚未配置 Harness 服务器") })
    private val worker = Executors.newSingleThreadExecutor()
    private val streamWorker = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { android.os.Handler(mainLooper) }

    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
    private lateinit var modelButton: TextView
    private lateinit var contextSeat: LinearLayout
    private lateinit var contextPercentView: TextView
    private lateinit var contextMeterView: ContextMeterView
    private lateinit var messageContainer: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var emptyView: TextView
    private lateinit var composer: EditText
    private lateinit var composerSeat: LinearLayout
    private lateinit var normalComposerCard: LinearLayout
    private lateinit var sendButton: ImageButton
    private lateinit var modeButton: TextView
    private lateinit var deliveryButton: TextView
    private lateinit var permissionButton: TextView
    private lateinit var statsView: TextView
    private lateinit var todoPanelHost: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var authOverlay: FrameLayout
    private lateinit var authWebView: WebView
    private lateinit var drawerOverlay: FrameLayout
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerToolbarHost: FrameLayout
    private lateinit var sessionList: LinearLayout

    private var sessions = emptyList<HarnessApi.Session>()
    private var drawerWorkspaces = emptyList<HarnessApi.Workspace>()
    private var drawerSearchExpanded = false
    private var drawerSearchQuery = ""
    private var drawerGroupByWorkspace = true
    private var drawerOrderLastUpdated = true
    private var currentSession: HarnessApi.Session? = null
    private var currentModels: HarnessApi.Models? = null
    private var currentControls = HarnessApi.SessionControls()
    private var currentStats = HarnessApi.ConversationStats()
    private var currentTodos = emptyList<HarnessApi.TodoItem>()
    private var currentContextUsage: HarnessApi.ContextUsage? = null
    private var todosExpanded = false
    private val pendingApprovalsBySession = mutableMapOf<String, HarnessApi.PendingApproval>()
    private var approvalResponding = false
    private var runningStartedAt: Long? = null
    private var runClockView: TextView? = null
    private var promptMode = "queue"
    private var lastRenderedSignature = ""
    private var refreshGeneration = 0
    @Volatile private var paused = false
    private var requestRunning = false
    private var refreshQueued = false
    private var debugTodoPreview = false
    private var debugControlsPreview = false
    private var debugApprovalPreview = false
    private var debugActivityPreview = false
    private var drawerSwipeTracking = false
    private var drawerSwipeConsuming = false
    private var drawerSwipeStartX = 0f
    private var drawerSwipeStartY = 0f
    @Volatile private var streamGeneration = 0
    private val streamingRendered = mutableMapOf<String, String>()
    private val streamingAnimations = mutableMapOf<String, Runnable>()
    private val locallyAnimatedMessages = mutableSetOf<String>()
    private var knownAssistantKeysBeforePrompt = emptySet<String>()
    private var animateNextAssistant = false
    private var lastMessages = emptyList<ChatMessage>()
    private var liveRefreshScheduled = false
    private val liveRefresh = Runnable {
        liveRefreshScheduled = false
        refresh(showSpinner = false)
    }
    private val runClockTick = object : Runnable {
        override fun run() {
            updateRunClock()
            if (currentSession?.running == true) mainHandler.postDelayed(this, 1_000L)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!paused && serverUrl != null && authOverlay.visibility != View.VISIBLE) refresh(showSpinner = false)
            mainHandler.postDelayed(this, if (currentSession?.running == true) 2_500 else 6_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).let { prefs ->
            drawerGroupByWorkspace = prefs.getBoolean(PREF_DRAWER_GROUP_WORKSPACE, true)
            drawerOrderLastUpdated = prefs.getBoolean(PREF_DRAWER_ORDER_UPDATED, true)
            serverUrl = prefs.getString(PREF_SERVER_URL, null)?.let { saved ->
                runCatching { ServerConfig.normalize(saved) }.getOrNull()
            }
        }
        debugTodoPreview = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_TODO_PREVIEW, false)
        debugControlsPreview = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_CONTROLS_PREVIEW, false)
        debugApprovalPreview = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_APPROVAL_PREVIEW, false)
        debugActivityPreview = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_ACTIVITY_PREVIEW, false)
        configureWindow()
        setContentView(buildScreen())
        configureAuthWebView()
        configureBackNavigation()
        when {
            debugActivityPreview -> renderDebugActivityPreview()
            debugApprovalPreview -> renderDebugApprovalPreview()
            debugControlsPreview -> renderDebugControlsPreview()
            debugTodoPreview -> renderDebugTodoPreview()
            serverUrl == null -> showServerSetup()
            else -> refresh(showSpinner = true)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::drawerOverlay.isInitialized) {
            val drawerVisible = drawerOverlay.visibility == View.VISIBLE
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val systemGestureInset = dp(48).toFloat()
                    val canOpenFromMain = !drawerVisible &&
                        (!::authOverlay.isInitialized || authOverlay.visibility != View.VISIBLE) &&
                        event.x >= systemGestureInset &&
                        event.x <= window.decorView.width - systemGestureInset
                    drawerSwipeTracking = if (drawerVisible) {
                        event.x <= drawerPanel.width
                    } else {
                        canOpenFromMain
                    }
                    drawerSwipeConsuming = false
                    drawerSwipeStartX = event.x
                    drawerSwipeStartY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (drawerSwipeTracking && !drawerSwipeConsuming) {
                    val deltaX = event.x - drawerSwipeStartX
                    val deltaY = event.y - drawerSwipeStartY
                    val horizontalDistance = kotlin.math.abs(deltaX)
                    val swipedInExpectedDirection = if (drawerVisible) {
                        deltaX <= -dp(56)
                    } else {
                        deltaX >= dp(56)
                    }
                    if (swipedInExpectedDirection && horizontalDistance > kotlin.math.abs(deltaY) * 1.25f) {
                        drawerSwipeTracking = false
                        drawerSwipeConsuming = true
                        MotionEvent.obtain(event).also { cancelEvent ->
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                        if (drawerVisible) closeDrawer() else showSessions()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drawerSwipeTracking = false
                    if (drawerSwipeConsuming) {
                        drawerSwipeConsuming = false
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG && (
                intent.hasExtra(EXTRA_DEBUG_TODO_PREVIEW) ||
                    intent.hasExtra(EXTRA_DEBUG_CONTROLS_PREVIEW) ||
                    intent.hasExtra(EXTRA_DEBUG_APPROVAL_PREVIEW)
                    || intent.hasExtra(EXTRA_DEBUG_ACTIVITY_PREVIEW)
                )
        ) {
            recreate()
        }
    }

    private fun renderDebugControlsPreview() {
        currentSession = HarnessApi.Session(
            id = "debug-controls",
            title = "DeepSeek",
            cwd = "/workspace",
            agentPreset = null,
            updatedAt = System.currentTimeMillis(),
            running = false,
            blank = true,
        )
        currentModels = HarnessApi.Models(
            currentProvider = "deepseek",
            currentModel = "deepseek-v4-flash",
            currentEffort = "high",
            routable = true,
            items = listOf(
                HarnessApi.Model("deepseek", "DeepSeek", "deepseek-v4-flash", "DeepSeek-V4-Flash", "high", listOf("off" to "Off", "high" to "High", "max" to "Max")),
                HarnessApi.Model("deepseek", "DeepSeek", "deepseek-v4-pro", "DeepSeek-V4-Pro", null, emptyList()),
            ),
        )
        currentControls = HarnessApi.SessionControls(
            permissionOptions = listOf(
                HarnessApi.PermissionOption("read-only", "read-only", null),
                HarnessApi.PermissionOption("workspace-write", "workspace-write", null),
                HarnessApi.PermissionOption("danger-full-access", "danger-full-access", null),
            ),
            permission = "workspace-write",
        )
        currentContextUsage = HarnessApi.ContextUsage(
            usedTokens = 64_000,
            contextWindow = 800_000,
            systemTokens = 8_000,
            toolsTokens = 34_000,
            messageTokens = 22_000,
        )
        renderHeader()
        renderControls()
        renderStats()
        renderMessages(emptyList())
        renderComposerSeat()
        updateStatus("已连接", STATUS_CONNECTED)
    }

    private fun renderDebugTodoPreview() {
        currentSession = HarnessApi.Session(
            id = "debug-todos",
            title = "Todo interaction preview",
            cwd = "/workspace",
            agentPreset = null,
            updatedAt = System.currentTimeMillis(),
            running = true,
            blank = false,
        )
        currentTodos = listOf(
            HarnessApi.TodoItem("Review the Android client implementation", "in_progress"),
            HarnessApi.TodoItem("Run unit tests", "pending"),
            HarnessApi.TodoItem("Run Android lint", "pending"),
            HarnessApi.TodoItem("Summarize verification results", "pending"),
        )
        runningStartedAt = System.currentTimeMillis() - 345_000L
        renderHeader()
        renderControls()
        renderStats()
        renderMessages(listOf(
            ChatMessage(
                key = "debug-user",
                role = ChatMessage.Role.USER,
                text = "Please verify the mobile client and summarize the results.",
                time = 1L,
            ),
            ChatMessage(
                key = "debug-think",
                role = ChatMessage.Role.REASONING,
                text = "Create a short checklist, then verify each item.",
                time = 2L,
                title = "Think",
            ),
            ChatMessage(
                key = "debug-todo-write",
                role = ChatMessage.Role.TOOL,
                text = "0/4 completed · Review the Android client implementation",
                time = 3L,
                title = "Update to-do list",
                state = ChatMessage.State.OK,
            ),
            ChatMessage(
                key = "debug-bash",
                role = ChatMessage.Role.TOOL,
                text = "Inspect the Android project",
                time = 4L,
                title = "Bash",
                state = ChatMessage.State.RUNNING,
            ),
        ))
        renderComposerSeat()
        updateStatus("运行中", STATUS_CONNECTED)
    }

    private fun renderDebugApprovalPreview() {
        val sessionId = "debug-approval"
        val callId = "debug-approval-call"
        currentSession = HarnessApi.Session(
            id = sessionId,
            title = "Approval preview",
            cwd = "/workspace",
            agentPreset = null,
            updatedAt = System.currentTimeMillis(),
            running = true,
            blank = false,
        )
        pendingApprovalsBySession[sessionId] = HarnessApi.PendingApproval(
            rpcId = "debug-approval-rpc",
            sessionId = sessionId,
            approvalId = "debug-approval-id",
            toolName = "bash",
            callId = callId,
            reason = "Run the requested project verification command with elevated workspace access.",
        )
        runningStartedAt = System.currentTimeMillis() - 32_000L
        renderHeader()
        renderControls()
        renderStats()
        renderMessages(listOf(
            ChatMessage(
                key = "debug-approval-think",
                role = ChatMessage.Role.REASONING,
                text = "The project verification command needs approval.",
                time = 1L,
                title = "Think",
            ),
            ChatMessage(
                key = "debug-approval-bash",
                role = ChatMessage.Role.TOOL,
                text = "Run Android project checks",
                time = 2L,
                title = "Bash",
                detail = "IN\n{\"command\":\"./gradlew testDebugUnitTest lintDebug\"}",
                callId = callId,
                state = ChatMessage.State.RUNNING,
            ),
        ))
        renderComposerSeat()
        updateStatus("运行中", STATUS_CONNECTED)
    }

    private fun renderDebugActivityPreview() {
        currentSession = HarnessApi.Session(
            id = "debug-activity",
            title = "Activity events",
            cwd = "/workspace",
            agentPreset = null,
            updatedAt = System.currentTimeMillis(),
            running = false,
            blank = false,
        )
        currentModels = HarnessApi.Models(
            currentProvider = "deepseek",
            currentModel = "deepseek-v4-flash",
            currentEffort = "high",
            routable = true,
            items = emptyList(),
        )
        currentControls = HarnessApi.SessionControls(permission = "workspace-write")
        currentContextUsage = HarnessApi.ContextUsage(70_494, 800_000)
        renderHeader()
        renderControls()
        renderStats()
        renderMessages(listOf(
            ChatMessage("debug-think", ChatMessage.Role.REASONING, "Checking the loaded Harness event registry", 1L, title = "Think", activityKind = ChatMessage.ActivityKind.THINK),
            ChatMessage("debug-bash", ChatMessage.Role.TOOL, "Inspect conversation node renderers", 2L, title = "Bash", activityKind = ChatMessage.ActivityKind.TERMINAL),
            ChatMessage(
                key = "debug-compact",
                role = ChatMessage.Role.ACTIVITY,
                text = "Compacted 121 history items (~70494 tokens)",
                time = 3L,
                title = "compact",
                detail = "Earlier context was summarized while preserving current goals, decisions, and unresolved work.",
                activityKind = ChatMessage.ActivityKind.TERMINAL,
            ),
            ChatMessage("debug-context", ChatMessage.Role.ACTIVITY, "skills · Loaded project instructions", 4L, title = "Context injection", detail = "Project instructions and available skills were added to model context.", activityKind = ChatMessage.ActivityKind.CONTEXT),
            ChatMessage("debug-retry", ChatMessage.Role.ACTIVITY, "Waiting to retry model request (1/3) · 2s", 5L, title = "Retry", detail = "Retry delay: 2000ms\nFailure reason: provider temporarily unavailable", pending = true, state = ChatMessage.State.RUNNING, activityKind = ChatMessage.ActivityKind.RETRY),
            ChatMessage("debug-max", ChatMessage.Role.ACTIVITY, "The reply was cut off because it reached the output limit. Send “continue” to keep going.", 6L, title = "Output token limit reached", state = ChatMessage.State.STOPPED, activityKind = ChatMessage.ActivityKind.WARNING),
        ))
        renderComposerSeat()
        updateStatus("已连接", STATUS_CONNECTED)
    }

    @Suppress("DEPRECATION")
    private fun configureWindow() {
        window.statusBarColor = COLOR_SURFACE
        window.navigationBarColor = COLOR_SURFACE
        window.decorView.systemUiVisibility = 0
    }

    private fun buildScreen(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_SURFACE)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_SURFACE)
        }
        page.addView(buildHeader(), LinearLayout.LayoutParams(MATCH, dp(60)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_TEXT)
            visibility = View.GONE
        }
        page.addView(progress, LinearLayout.LayoutParams(MATCH, dp(2)))
        page.addView(buildMessages(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        page.addView(buildComposer(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(buildDrawer(), FrameLayout.LayoutParams(MATCH, MATCH))

        authOverlay = FrameLayout(this).apply {
            setBackgroundColor(COLOR_SURFACE)
            visibility = View.GONE
        }
        authWebView = WebView(this)
        authOverlay.addView(authWebView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(authOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(COLOR_SURFACE)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(5))
            addView(iconAction(R.drawable.ic_sidebar_outline, "打开会话列表") { showSessions() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
                titleView = TextView(this@MainActivity).apply {
                    text = "DeepSeek"
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setTextColor(COLOR_TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true
                    contentDescription = "选择模型"
                    setOnClickListener { showModels() }
                    maxWidth = dp(150)
                }
                addView(titleView, LinearLayout.LayoutParams(WRAP, dp(32)))
                statusView = TextView(this@MainActivity).apply {
                    text = "· 连接中"
                    textSize = 10f
                    setTextColor(COLOR_MUTED)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                }
                addView(statusView, LinearLayout.LayoutParams(WRAP, dp(32)).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(buildContextSeat(), LinearLayout.LayoutParams(WRAP, dp(34)).apply { marginEnd = dp(2) })
            addView(iconAction(R.drawable.ic_new_session_harness, "新建会话") { showNewSession() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(View(this@MainActivity).apply { setBackgroundColor(COLOR_BORDER_SUBTLE) }, LinearLayout.LayoutParams(MATCH, dp(1)))
    }

    private fun buildContextSeat(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        contentDescription = "上下文使用情况"
        setPadding(dp(8), 0, dp(8), 0)
        background = roundedStroke(Color.TRANSPARENT, COLOR_BORDER_SUBTLE, 17f)
        setOnClickListener { showContextDetails() }
        contextSeat = this
        contextPercentView = TextView(this@MainActivity).apply {
            textSize = 10f
            setTextColor(COLOR_CONTROL_TEXT)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        addView(contextPercentView, LinearLayout.LayoutParams(WRAP, dp(30)).apply { marginEnd = dp(3) })
        contextMeterView = ContextMeterView(this@MainActivity)
        addView(contextMeterView, LinearLayout.LayoutParams(dp(20), dp(20)))
    }

    private fun buildMessages(): View {
        val frame = FrameLayout(this)
        messageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        messageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messageScroll.addView(messageContainer, ViewGroup.LayoutParams(MATCH, WRAP))
        frame.addView(messageScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        emptyView = TextView(this).apply {
            text = "有什么可以帮忙的？\n\n在远端工作区开始一项任务"
            textSize = 16f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(36), dp(36), dp(36))
        }
        frame.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }

    private fun buildComposer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(8))
        setBackgroundColor(COLOR_SURFACE)
        todoPanelHost = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addView(todoPanelHost, LinearLayout.LayoutParams(MATCH, WRAP))
        val card = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
            background = roundedStroke(COLOR_COMPOSER, COLOR_BORDER_SUBTLE, 22f)
        }
        normalComposerCard = card
        composer = EditText(this@MainActivity).apply {
            hint = "Message the agent"
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            minLines = 1
            maxLines = 6
            minimumHeight = dp(44)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(10), dp(4), dp(10), dp(8))
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateSendState()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        card.addView(composer, LinearLayout.LayoutParams(MATCH, WRAP))
        val toolbar = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolbar.addView(ImageButton(this@MainActivity).apply {
            setImageResource(R.drawable.ic_add_outline)
            imageTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = rounded(COLOR_MENU_SELECTED, 18f)
            contentDescription = "Commands"
            isClickable = true
            isFocusable = true
            setOnClickListener { showCommands(it) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(4) })
        val leading = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        permissionButton = toolbarChip("Workspace Write", R.drawable.ic_shield_outline) { showPermissionPicker() }
        modeButton = toolbarChip("默认", null) { showModePicker() }
        deliveryButton = toolbarChip("排队", null) { showDeliveryPicker() }
        leading.addView(permissionButton)
        leading.addView(modeButton)
        leading.addView(deliveryButton)
        toolbar.addView(HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            addView(leading)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        modelButton = TextView(this@MainActivity).apply {
            text = "DeepSeek"
            textSize = 11f
            setTextColor(COLOR_CONTROL_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(5), 0, dp(3), 0)
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_down, 0)
            compoundDrawableTintList = ColorStateList.valueOf(COLOR_MUTED)
            compoundDrawablePadding = dp(2)
            isClickable = true
            setOnClickListener { showModels() }
        }
        toolbar.addView(modelButton, LinearLayout.LayoutParams(dp(116), dp(36)))
        sendButton = ImageButton(this@MainActivity).apply {
            setImageResource(R.drawable.ic_send_harness)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(COLOR_BLUE, 22f)
            contentDescription = "发送消息"
            isClickable = true
            setOnClickListener { if (currentSession?.running == true) cancelCurrent() else sendPrompt() }
        }
        toolbar.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))
        composerSeat = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        addView(composerSeat, LinearLayout.LayoutParams(MATCH, WRAP))
        statsView = TextView(this@MainActivity).apply {
            textSize = 10f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(4), dp(8), 0)
            visibility = View.GONE
        }
        addView(statsView, LinearLayout.LayoutParams(MATCH, dp(22)))
        updateSendState()
    }

    private fun toolbarChip(label: String, icon: Int?, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 10f
        setTextColor(COLOR_CONTROL_TEXT)
        gravity = Gravity.CENTER
        setPadding(dp(5), 0, dp(5), 0)
        maxLines = 1
        background = null
        if (icon != null) {
            setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
            compoundDrawablePadding = dp(4)
        }
        isClickable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(3) }
    }

    private fun renderComposerSeat() {
        if (!::composerSeat.isInitialized || !::normalComposerCard.isInitialized) return
        val approval = currentSession?.id?.let(pendingApprovalsBySession::get)
        if (approval == null) {
            renderTodoDock()
            if (composerSeat.childCount != 1 || composerSeat.getChildAt(0) !== normalComposerCard) {
                composerSeat.removeAllViews()
                (normalComposerCard.parent as? ViewGroup)?.removeView(normalComposerCard)
                composerSeat.addView(normalComposerCard, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            renderStats()
        } else {
            composerSeat.removeAllViews()
            todoPanelHost.visibility = View.GONE
            statsView.visibility = View.GONE
            composerSeat.addView(buildApprovalCard(approval), LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun renderTodoDock() {
        if (!::todoPanelHost.isInitialized) return
        todoPanelHost.removeAllViews()
        if (currentTodos.isEmpty()) {
            todoPanelHost.visibility = View.GONE
            return
        }
        todoPanelHost.visibility = View.VISIBLE
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedStroke(COLOR_TODO_PANEL, COLOR_TODO_BORDER, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "To-dos，${if (todosExpanded) "点击收起" else "点击展开"}"
            setOnClickListener {
                todosExpanded = !todosExpanded
                renderTodoDock()
            }
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_checklist_harness)
            imageTintList = ColorStateList.valueOf(COLOR_MUTED)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
        header.addView(TextView(this).apply {
            text = "To-dos"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(WRAP, dp(30)))
        header.addView(TextView(this).apply {
            text = todoProgressLabel()
            textSize = 13f
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, dp(30), 1f))
        header.addView(ImageView(this).apply {
            setImageResource(if (todosExpanded) R.drawable.ic_chevron_down_harness else R.drawable.ic_chevron_up_harness)
            imageTintList = ColorStateList.valueOf(COLOR_MUTED)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        panel.addView(header, LinearLayout.LayoutParams(MATCH, dp(36)))

        if (todosExpanded) {
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                currentTodos.forEach { todo ->
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TodoStatusView(this@MainActivity, todo.status), LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                            marginEnd = dp(10)
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = todo.content
                            textSize = 13f
                            setTextColor(COLOR_CONTROL_TEXT)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            includeFontPadding = false
                            gravity = Gravity.CENTER_VERTICAL
                        }, LinearLayout.LayoutParams(0, dp(36), 1f))
                    }, LinearLayout.LayoutParams(MATCH, dp(36)))
                }
            }
            val listHeight = minOf(dp(180), dp(36) * currentTodos.size)
            panel.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = currentTodos.size > 5
                addView(list, ViewGroup.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, listHeight))
        }
        todoPanelHost.addView(panel, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
    }

    private fun todoProgressLabel(): String {
        val completed = currentTodos.count { it.status == "completed" }
        val active = currentTodos.count { it.status == "in_progress" }
        val pending = currentTodos.size - completed - active
        return buildList {
            if (completed > 0) add("$completed completed")
            if (active > 0) add("$active in progress")
            if (pending > 0) add("$pending pending")
        }.joinToString("  ·  ")
    }

    private fun buildApprovalCard(approval: HarnessApi.PendingApproval): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(COLOR_CODE_SURFACE, 20f)
        foreground = roundedStroke(Color.TRANSPARENT, COLOR_AMBER, 20f)
        clipToOutline = true

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(COLOR_APPROVAL_STRIP)
            addView(TextView(this@MainActivity).apply {
                background = rounded(COLOR_AMBER, 4f)
                contentDescription = "等待审批"
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
            addView(TextView(this@MainActivity).apply {
                text = "Waiting for approval"
                textSize = 13f
                setTextColor(COLOR_AMBER)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, MATCH, 1f))
        }, LinearLayout.LayoutParams(MATCH, dp(44)))

        val detailColumn = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = approval.reason ?: "${approval.toolName} requires approval"
                textSize = 15f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(COLOR_TEXT)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            approvalCommand(approval)?.let { command ->
                addView(TextView(this@MainActivity).apply {
                    text = command
                    textSize = 13f
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setTextColor(COLOR_MUTED)
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(0, dp(8), 0, 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        addView(ScrollView(this@MainActivity).apply {
            isFillViewport = false
            addView(detailColumn, ViewGroup.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, dp(220)))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(14))
            addView(approvalButton("Reject", false, !approvalResponding) {
                answerApproval(approval, "rejected")
            }, LinearLayout.LayoutParams(dp(92), dp(44)).apply { marginEnd = dp(8) })
            addView(approvalButton("Allow once", true, !approvalResponding) {
                answerApproval(approval, "allowed-once")
            }, LinearLayout.LayoutParams(dp(122), dp(44)))
        }, LinearLayout.LayoutParams(MATCH, dp(66)))
    }

    private fun approvalButton(label: String, primary: Boolean, enabledNow: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        isEnabled = enabledNow
        alpha = if (enabledNow) 1f else .55f
        setTextColor(if (primary) Color.rgb(24, 24, 24) else COLOR_TEXT)
        background = if (primary) rounded(Color.rgb(246, 246, 246), 22f)
        else roundedStroke(Color.TRANSPARENT, COLOR_BORDER, 22f)
        setOnClickListener { if (isEnabled) action() }
    }

    private fun approvalCommand(approval: HarnessApi.PendingApproval): String? {
        val detail = lastMessages.lastOrNull { it.callId == approval.callId }?.detail ?: return null
        val json = detail.removePrefix("IN\n").substringBefore("\n\nOUT\n")
        return runCatching { JSONObject(json).optString("command").takeIf(String::isNotBlank) }.getOrNull()
    }

    private fun answerApproval(approval: HarnessApi.PendingApproval, outcome: String) {
        if (approvalResponding) return
        approvalResponding = true
        renderComposerSeat()
        worker.execute {
            try {
                api.respondApproval(approval, outcome)
                mainHandler.post { refresh(showSpinner = false) }
            } catch (error: Exception) {
                mainHandler.post {
                    approvalResponding = false
                    renderComposerSeat()
                    Toast.makeText(this, error.message ?: "Approval response failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun controlChip(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(COLOR_CONTROL_TEXT)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(6), dp(11), dp(6))
        background = rounded(COLOR_CONTROL, 15f)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(32)).apply { marginEnd = dp(6) }
    }

    private fun iconAction(icon: Int, description: String, action: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = null
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener(action)
    }

    private fun actionText(label: String, description: String, action: (View) -> Unit) = TextView(this).apply {
        text = label
        textSize = if (label == "☰") 22f else 28f
        gravity = Gravity.CENTER
        setTextColor(COLOR_TEXT)
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener(action)
    }

    private fun buildDrawer(): View {
        drawerOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(145, 0, 0, 0))
            isClickable = true
            setOnClickListener { closeDrawer() }
        }
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(12))
            setBackgroundColor(COLOR_DRAWER)
            isClickable = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "deepseek"
                    textSize = 19f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(WRAP, dp(48)))
                addView(TextView(this@MainActivity).apply {
                    text = "HARNESS"
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = .08f
                    setTextColor(COLOR_DRAWER)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = rounded(COLOR_TEXT, 3f)
                    setPadding(dp(6), 0, dp(6), 0)
                }, LinearLayout.LayoutParams(WRAP, dp(22)).apply {
                    marginStart = dp(8)
                })
                addView(android.widget.Space(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(drawerIconButton(R.drawable.ic_sidebar_outline, "关闭侧栏") { closeDrawer() }, LinearLayout.LayoutParams(dp(40), dp(40)))
            }, LinearLayout.LayoutParams(MATCH, dp(56)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = roundedStroke(COLOR_MENU_SELECTED, COLOR_BORDER, 12f)
                contentDescription = "New Session"
                setOnClickListener { closeDrawer(); showNewSession() }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_add_outline)
                    imageTintList = ColorStateList.valueOf(COLOR_TEXT)
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                addView(TextView(this@MainActivity).apply {
                    text = "New Session"
                    textSize = 14f
                    setTextColor(COLOR_TEXT)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(WRAP, MATCH))
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply {
                topMargin = dp(6)
                bottomMargin = dp(14)
            })
            drawerToolbarHost = FrameLayout(this@MainActivity)
            addView(drawerToolbarHost, LinearLayout.LayoutParams(MATCH, dp(40)))
            renderDrawerToolbar()
            sessionList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = true
                addView(sessionList, ViewGroup.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "Settings"
                textSize = 14f
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, dp(6), 0)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_settings_outline, 0, 0, 0)
                compoundDrawablePadding = dp(12)
                compoundDrawableTintList = ColorStateList.valueOf(COLOR_TEXT)
                isClickable = true
                setOnClickListener { anchor -> showDrawerSettings(anchor) }
            }, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(6) })
        }
        drawerOverlay.addView(drawerPanel, FrameLayout.LayoutParams(drawerWidthPx(), MATCH, Gravity.START))
        return drawerOverlay
    }

    private fun drawerWidthPx(): Int {
        val viewportWidth = drawerOverlay.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        return (viewportWidth * DRAWER_WIDTH_FRACTION).roundToInt()
    }

    private fun drawerIconButton(icon: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
        background = null
        contentDescription = description
        setPadding(dp(9), dp(9), dp(9), dp(9))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
    }

    private fun renderDrawerToolbar() {
        if (!::drawerToolbarHost.isInitialized) return
        drawerToolbarHost.removeAllViews()
        if (drawerSearchExpanded) {
            val search = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedStroke(Color.TRANSPARENT, COLOR_BORDER, 10f)
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_search_outline)
                    imageTintList = ColorStateList.valueOf(COLOR_MUTED)
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
                val input = EditText(this@MainActivity).apply {
                    hint = "Search sessions..."
                    setText(drawerSearchQuery)
                    setSingleLine(true)
                    textSize = 13f
                    setTextColor(COLOR_TEXT)
                    setHintTextColor(COLOR_MUTED)
                    background = null
                    setPadding(0, 0, 0, 0)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            drawerSearchQuery = s?.toString().orEmpty()
                            renderSessionList()
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
                addView(input, LinearLayout.LayoutParams(0, dp(38), 1f))
                addView(drawerIconButton(R.drawable.ic_close_outline, "Clear search") {
                    drawerSearchQuery = ""
                    drawerSearchExpanded = false
                    hideKeyboard()
                    renderDrawerToolbar()
                    renderSessionList()
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
                mainHandler.post {
                    input.requestFocus()
                    input.setSelection(input.text.length)
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            drawerToolbarHost.addView(search, FrameLayout.LayoutParams(MATCH, dp(38), Gravity.CENTER_VERTICAL))
            return
        }

        drawerToolbarHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Workspaces"
                textSize = 13f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(2) })
            addView(drawerIconButton(R.drawable.ic_search_outline, "Search sessions") {
                drawerSearchExpanded = true
                renderDrawerToolbar()
            })
            val viewOptions = drawerIconButton(R.drawable.ic_tune_outline, "View options") {}
            viewOptions.setOnClickListener { showDrawerViewOptions(viewOptions) }
            addView(viewOptions)
            addView(drawerIconButton(R.drawable.ic_folder_add_outline, "Add workspace") {
                showAddWorkspaceDialog()
            })
        }, FrameLayout.LayoutParams(MATCH, dp(40), Gravity.CENTER_VERTICAL))
    }

    private fun showDrawerViewOptions(anchor: View) {
        val surface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedStroke(COLOR_MENU, COLOR_TODO_BORDER, 12f)
        }
        var popup: PopupWindow? = null
        fun header(label: String) = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        fun option(label: String, checked: Boolean, action: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            background = if (checked) rounded(COLOR_SELECTED, 8f) else null
            isClickable = true
            setOnClickListener { action(); popup?.dismiss() }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(TextView(this@MainActivity).apply {
                text = if (checked) "✓" else ""
                textSize = 18f
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(30), MATCH))
        }
        surface.addView(header("Group by"), LinearLayout.LayoutParams(MATCH, dp(34)))
        surface.addView(option("WorkSpace", drawerGroupByWorkspace) { setDrawerGroup(true) }, LinearLayout.LayoutParams(MATCH, dp(48)))
        surface.addView(option("In one list", !drawerGroupByWorkspace) { setDrawerGroup(false) }, LinearLayout.LayoutParams(MATCH, dp(48)))
        surface.addView(View(this).apply { setBackgroundColor(COLOR_BORDER_SUBTLE) }, LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(5); bottomMargin = dp(5)
        })
        surface.addView(header("Order by"), LinearLayout.LayoutParams(MATCH, dp(34)))
        surface.addView(option("Manual", !drawerOrderLastUpdated) { setDrawerOrder(false) }, LinearLayout.LayoutParams(MATCH, dp(48)))
        surface.addView(option("Last updated", drawerOrderLastUpdated) { setDrawerOrder(true) }, LinearLayout.LayoutParams(MATCH, dp(48)))
        popup = popupFor(anchor, surface, 220)
        anchor.background = rounded(COLOR_SELECTED, 20f)
        popup.setOnDismissListener { anchor.background = null }
        popup.showAsDropDown(anchor, -dp(180), dp(2), Gravity.START)
    }

    private fun setDrawerGroup(workspace: Boolean) {
        drawerGroupByWorkspace = workspace
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_DRAWER_GROUP_WORKSPACE, workspace).apply()
        renderSessionList()
    }

    private fun setDrawerOrder(updated: Boolean) {
        drawerOrderLastUpdated = updated
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_DRAWER_ORDER_UPDATED, updated).apply()
        renderSessionList()
    }

    private fun showAddWorkspaceDialog(initialPath: String? = null) {
        val pathInput = EditText(this).apply {
            hint = "Absolute host path"
            setSingleLine(true)
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            background = roundedStroke(Color.TRANSPARENT, COLOR_BORDER, 8f)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val newFolder = TextView(this).apply {
            text = "New folder"
            textSize = 12f
            setTextColor(COLOR_CONTROL_TEXT)
            gravity = Gravity.CENTER
            background = rounded(COLOR_CONTROL, 8f)
            isClickable = true
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pathInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(newFolder, LinearLayout.LayoutParams(dp(92), dp(42)).apply { marginStart = dp(8) })
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hiddenToggle = TextView(this).apply {
            text = "○  Show hidden files"
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
            isClickable = true
        }
        val cancelButton = TextView(this).apply {
            text = "Cancel"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
            background = roundedStroke(Color.TRANSPARENT, COLOR_BORDER, 18f)
            isClickable = true
        }
        val openButton = TextView(this).apply {
            text = "Open"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 18f)
            isClickable = true
        }
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hiddenToggle, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(cancelButton, LinearLayout.LayoutParams(dp(82), dp(40)).apply { marginEnd = dp(8) })
            addView(openButton, LinearLayout.LayoutParams(dp(82), dp(40)))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedStroke(COLOR_MENU, COLOR_BORDER, 16f)
            addView(TextView(this@MainActivity).apply {
                text = "Select Workspace Directory"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(MATCH, dp(44)))
            addView(toolbar, LinearLayout.LayoutParams(MATCH, dp(42)))
            addView(ScrollView(this@MainActivity).apply { addView(list, ViewGroup.LayoutParams(MATCH, WRAP)) },
                LinearLayout.LayoutParams(MATCH, dp(300)).apply { topMargin = dp(12) })
            addView(actionBar, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })
        }
        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .create()
        var current: HarnessApi.DirectoryListing? = null
        var showHidden = false
        lateinit var loadDirectory: (String?) -> Unit

        fun directoryRow(entry: HarnessApi.DirectoryEntry) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(Color.TRANSPARENT, 7f)
            isClickable = true
            setOnClickListener { loadDirectory(entry.path) }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_folder_outline)
                imageTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(TextView(this@MainActivity).apply {
                text = entry.name
                textSize = 13f
                setTextColor(COLOR_TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 20f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), MATCH))
        }

        fun renderDirectory() {
            val listing = current ?: return
            pathInput.setText(listing.path)
            pathInput.setSelection(pathInput.text.length)
            list.removeAllViews()
            list.addView(TextView(this).apply {
                text = "⌂  Home"
                textSize = 13f
                setTextColor(COLOR_CONTROL_TEXT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true
                setOnClickListener { loadDirectory(listing.home) }
            }, LinearLayout.LayoutParams(MATCH, dp(42)))
            listing.entries.filter { showHidden || !it.hidden }.forEach {
                list.addView(directoryRow(it), LinearLayout.LayoutParams(MATCH, dp(42)))
            }
            if (listing.truncated) list.addView(TextView(this).apply {
                text = "Too many folders to list; only the beginning is shown."
                textSize = 11f
                setTextColor(COLOR_MUTED)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }

        loadDirectory = { path ->
            list.removeAllViews()
            list.addView(TextView(this).apply {
                text = "Loading…"
                textSize = 13f
                setTextColor(COLOR_MUTED)
                setPadding(dp(8), dp(18), dp(8), dp(18))
            })
            worker.execute {
                try {
                    val listing = api.listDirectory(path)
                    mainHandler.post { current = listing; renderDirectory() }
                } catch (error: Exception) {
                    mainHandler.post {
                        list.removeAllViews()
                        list.addView(TextView(this).apply {
                            text = error.message ?: "Unable to list directory"
                            textSize = 12f
                            setTextColor(COLOR_RED)
                            setPadding(dp(8), dp(18), dp(8), dp(18))
                        })
                    }
                }
            }
        }

        pathInput.setOnEditorActionListener { _, _, _ ->
            loadDirectory(pathInput.text.toString().trim())
            hideKeyboard()
            true
        }
        hiddenToggle.setOnClickListener {
            showHidden = !showHidden
            hiddenToggle.text = if (showHidden) "●  Show hidden files" else "○  Show hidden files"
            renderDirectory()
        }
        newFolder.setOnClickListener {
            val listing = current ?: return@setOnClickListener
            val name = EditText(this).apply {
                hint = "Folder name"
                setSingleLine(true)
                setTextColor(COLOR_TEXT)
                setHintTextColor(COLOR_MUTED)
            }
            AlertDialog.Builder(this)
                .setTitle("New folder in \"${listing.path.substringAfterLast('/').ifBlank { listing.path }}\"")
                .setView(name)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create") { _, _ ->
                    val value = name.text.toString().trim()
                    if (value.isNotBlank()) worker.execute {
                        runCatching { api.createDirectory(listing.path, value) }
                            .onSuccess { mainHandler.post { loadDirectory(it) } }
                            .onFailure { mainHandler.post { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() } }
                    }
                }
                .show()
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        openButton.setOnClickListener {
                val listing = current ?: return@setOnClickListener
                openButton.isEnabled = false
                openButton.alpha = .55f
                worker.execute {
                    try {
                        val workspace = api.createWorkspace(listing.path)
                        val latest = api.workspaces()
                        mainHandler.post {
                            drawerWorkspaces = latest
                            renderSessionList()
                            dialog.dismiss()
                            Toast.makeText(this, "Added ${workspace.title}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (error: Exception) {
                        mainHandler.post {
                            openButton.isEnabled = true
                            openButton.alpha = 1f
                            Toast.makeText(this, error.message ?: "Unable to add workspace", Toast.LENGTH_LONG).show()
                        }
                    }
                }
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            loadDirectory(initialPath)
        }
        dialog.show()
    }

    private fun showDrawerSettings(anchor: View) {
        lateinit var popup: PopupWindow
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = roundedStroke(COLOR_WEB_SETTINGS, COLOR_WEB_SETTINGS_BORDER, 14f)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(1), 0)
                addView(TextView(this@MainActivity).apply {
                    text = "Settings"
                    textSize = 14f
                    typeface = Typeface.DEFAULT
                    setTextColor(COLOR_TEXT)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(34), 1f))
                addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_close_outline)
                    imageTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
                    background = null
                    contentDescription = "Close settings"
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { popup.dismiss() }
                }, LinearLayout.LayoutParams(dp(32), dp(32)))
            }, LinearLayout.LayoutParams(MATCH, dp(32)))
        }
        fun addSettingsRow(icon: Int, title: String, action: () -> Unit) {
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), 0, dp(9), 0)
                isClickable = true
                isFocusable = true
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
                }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 13f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(COLOR_TEXT)
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, MATCH, 1f))
                setOnClickListener {
                    popup.dismiss()
                    closeDrawer()
                    action()
                }
            }, LinearLayout.LayoutParams(MATCH, dp(42)).apply {
                topMargin = dp(2)
            })
        }
        addSettingsRow(R.drawable.ic_terminal_harness, "Server connection") {
            showServerSetup()
        }
        addSettingsRow(R.drawable.ic_settings_outline, "Web Settings") {
            serverUrl?.let { openExternal(Uri.parse(it)) }
        }
        popup = PopupWindow(panel, dp(284), WRAP, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(14).toFloat()
            animationStyle = android.R.style.Animation_Dialog
            showAtLocation(anchor, Gravity.START or Gravity.BOTTOM, dp(16), dp(68))
        }
    }

    private fun showServerSetup() {
        val required = serverUrl == null
        if (required) updateStatus("未配置", STATUS_VERIFY)
        val addressInput = EditText(this).apply {
            hint = "服务器地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            background = roundedStroke(COLOR_CONTROL, COLOR_BORDER, 10f)
            setText(serverUrl.orEmpty())
            setSelectAllOnFocus(false)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val errorView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(255, 120, 120))
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), 0)
            addView(TextView(this@MainActivity).apply {
                text = "输入运行 Harness 的服务器地址。公网地址必须使用 HTTPS；HTTP 仅允许私有内网地址。\n\n示例：https://harness.example.com 或 http://192.168.1.50:3000\n连接成功后仍可在 Settings 中更换。"
                textSize = 13f
                setTextColor(COLOR_MUTED)
                setPadding(0, 0, 0, dp(14))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(addressInput, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(errorView, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(TextView(this).apply {
                text = if (required) "连接 Harness" else "服务器连接"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                setPadding(dp(24), dp(22), dp(24), dp(8))
            })
            .setView(content)
            .setPositiveButton("测试并连接", null)
            .apply { if (!required) setNegativeButton("取消", null) }
            .create()
        dialog.setCancelable(!required)
        dialog.setCanceledOnTouchOutside(!required)
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(COLOR_COMPOSER, 18f))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(COLOR_BLUE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(COLOR_MUTED)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = try {
                    ServerConfig.normalize(addressInput.text.toString())
                } catch (error: IllegalArgumentException) {
                    errorView.text = error.message
                    errorView.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val connectButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                connectButton.isEnabled = false
                addressInput.isEnabled = false
                errorView.text = "正在验证 Harness…"
                errorView.setTextColor(COLOR_MUTED)
                errorView.visibility = View.VISIBLE
                worker.execute {
                    val candidateApi = HarnessApi(baseUrl = { candidate })
                    try {
                        candidateApi.sessions()
                        mainHandler.post {
                            applyServer(candidate)
                            dialog.dismiss()
                            hideAuth()
                            refresh(showSpinner = true)
                            startMuxStream()
                        }
                    } catch (_: HarnessApi.AuthenticationRequired) {
                        mainHandler.post {
                            applyServer(candidate)
                            dialog.dismiss()
                            showAuth()
                        }
                    } catch (error: Exception) {
                        mainHandler.post {
                            connectButton.isEnabled = true
                            addressInput.isEnabled = true
                            errorView.setTextColor(Color.rgb(255, 120, 120))
                            errorView.text = error.message ?: "无法连接或服务器不是兼容的 Harness"
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun applyServer(url: String) {
        stopMuxStream()
        mainHandler.removeCallbacks(poll)
        val changed = serverUrl != url
        serverUrl = url
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(PREF_SERVER_URL, url)
            if (changed) remove(PREF_DEFAULT_WORKSPACE_ID)
            apply()
        }
        if (changed) {
            sessions = emptyList()
            drawerWorkspaces = emptyList()
            currentSession = null
            currentModels = null
            currentControls = HarnessApi.SessionControls()
            pendingApprovalsBySession.clear()
            lastMessages = emptyList()
            lastRenderedSignature = ""
        }
        if (!paused) mainHandler.postDelayed(poll, 1_000)
    }

    private fun workspaceHeader(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_folder_outline)
            imageTintList = ColorStateList.valueOf(COLOR_BLUE)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 13f
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(4) }
    }

    private fun refresh(showSpinner: Boolean) {
        if (requestRunning) {
            if (!showSpinner) refreshQueued = true
            return
        }
        requestRunning = true
        if (showSpinner) progress.visibility = View.VISIBLE
        val generation = ++refreshGeneration
        val selectedId = currentSession?.id
        worker.execute {
            try {
                val newSessions = api.sessions()
                val selected = newSessions.firstOrNull { it.id == selectedId }
                    ?: newSessions.firstOrNull { !it.blank }
                    ?: newSessions.firstOrNull()
                val history = selected?.let { api.history(it.id) }
                val models = selected?.let { api.models(it.id) }
                mainHandler.post {
                    if (generation != refreshGeneration || isFinishing) return@post
                    requestRunning = false
                    val runQueued = refreshQueued
                    refreshQueued = false
                    progress.visibility = View.GONE
                    hideAuth()
                    sessions = newSessions
                    val keptStart = runningStartedAt.takeIf { currentSession?.id == selected?.id && currentSession?.running == true }
                    if (currentSession?.id != selected?.id) todosExpanded = false
                    currentSession = selected
                    currentModels = models
                    currentControls = history?.controls ?: HarnessApi.SessionControls()
                    currentStats = history?.stats ?: HarnessApi.ConversationStats()
                    currentTodos = history?.todos.orEmpty()
                    currentContextUsage = history?.contextUsage
                    runningStartedAt = if (selected?.running == true) {
                        history?.runningStartedAt ?: keptStart ?: System.currentTimeMillis()
                    } else null
                    renderHeader()
                    renderControls()
                    renderStats()
                    renderMessages(history?.messages.orEmpty())
                    renderComposerSeat()
                    updateStatus(if (selected?.running == true) "运行中" else "已连接", STATUS_CONNECTED)
                    if (runQueued) mainHandler.post { refresh(showSpinner = false) }
                }
            } catch (_: HarnessApi.AuthenticationRequired) {
                mainHandler.post {
                    requestRunning = false
                    progress.visibility = View.GONE
                    showAuth()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    requestRunning = false
                    progress.visibility = View.GONE
                    updateStatus("连接失败", STATUS_ERROR)
                    if (showSpinner) Toast.makeText(this, error.message ?: "无法连接 Harness", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun selectSession(session: HarnessApi.Session) {
        currentSession = session
        currentModels = null
        currentControls = HarnessApi.SessionControls()
        currentStats = HarnessApi.ConversationStats()
        currentContextUsage = null
        lastRenderedSignature = ""
        renderHeader()
        messageContainer.removeAllViews()
        emptyView.text = "正在载入会话…"
        emptyView.visibility = View.VISIBLE
        refresh(showSpinner = true)
    }

    private fun renderHeader() {
        val session = currentSession
        if (session == null) {
            titleView.text = "DeepSeek"
            modelButton.text = "选择模型"
            return
        }
        titleView.text = session.title ?: if (session.blank) "新会话" else "未命名会话"
        val models = currentModels
        if (models == null) modelButton.text = "载入模型…"
    }

    private fun renderControls() {
        val plan = currentControls.planActive
        modeButton.visibility = if (plan == null) View.GONE else View.VISIBLE
        modeButton.text = when {
            currentControls.planPending -> "切换中"
            plan == true -> "Plan"
            else -> "默认"
        }
        deliveryButton.text = if (promptMode == "steer") "插话" else "排队"
        val models = currentModels
        val current = models?.items?.firstOrNull {
            it.provider == models.currentProvider && it.id == models.currentModel
        }
        modelButton.text = if (models == null) "载入模型…" else buildString {
            append((current?.name ?: models.currentModel).removePrefix("DeepSeek-"))
            (models.currentEffort ?: current?.defaultEffort)?.let { id ->
                append("  ")
                append(current?.efforts?.firstOrNull { it.first == id }?.second ?: id)
            }
        }
        permissionButton.visibility = if (currentControls.permission == null) View.GONE else View.VISIBLE
        permissionButton.text = permissionLabel(currentControls.permission)
        permissionButton.setCompoundDrawablesWithIntrinsicBounds(
            permissionIcon(currentControls.permission),
            0,
            R.drawable.ic_chevron_down_harness,
            0,
        )
        permissionButton.compoundDrawableTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
        permissionButton.compoundDrawablePadding = dp(4)
        val running = currentSession?.running == true
        sendButton.setImageResource(if (running) R.drawable.ic_stop_square else R.drawable.ic_send_harness)
        sendButton.contentDescription = if (running) "停止当前任务" else "发送消息"
        val context = currentContextUsage
        contextSeat.visibility = if (context == null) View.GONE else View.VISIBLE
        if (context != null) {
            contextPercentView.text = "${context.percent}%"
            contextMeterView.percent = context.percent
            contextSeat.contentDescription = "${context.percent}% of context used，点击查看详情"
        }
        updateSendState()
    }

    private fun renderStats() {
        val stats = currentStats
        if (stats.steps <= 0 && stats.inputTokens <= 0 && stats.outputTokens <= 0) {
            statsView.visibility = View.GONE
            return
        }
        val groups = mutableListOf<String>()
        if (stats.steps > 0) {
            groups += "${stats.turns} turns · ${stats.steps} steps"
            val durations = mutableListOf<String>()
            if (stats.llmMs > 0) durations += "LLM ${formatDuration(stats.llmMs)}"
            if (stats.toolMs > 0) durations += "Tool call ${formatDuration(stats.toolMs)}"
            if (durations.isNotEmpty()) groups += durations.joinToString(" · ")
            val speeds = mutableListOf<String>()
            if (stats.ttftSteps > 0) speeds += "TTFT avg ${formatDuration(stats.ttftMs / stats.ttftSteps)}"
            if (stats.decodeMs > 0) speeds += "${formatCompact(stats.decodeTokens * 1000.0 / stats.decodeMs) } tok/s"
            if (speeds.isNotEmpty()) groups += speeds.joinToString(" · ")
        }
        if (stats.inputTokens > 0 || stats.outputTokens > 0) {
            if (stats.inputTokens > 0) groups += "Cache hit ${stats.cacheReadTokens * 100 / stats.inputTokens}%"
            groups += "Input ${formatCompact(stats.inputTokens.toDouble())} tok · Output ${formatCompact(stats.outputTokens.toDouble())} tok"
        }
        statsView.text = groups.joinToString("  |  ")
        statsView.visibility = View.VISIBLE
    }

    private fun formatDuration(ms: Long): String {
        if (ms < 60_000) return "${(ms / 100.0).toInt() / 10.0}s"
        val seconds = (ms / 1000.0).toInt()
        return "${seconds / 60}m${seconds % 60}s"
    }

    private fun formatCompact(value: Double): String = when {
        value >= 1_000_000 -> "${(value / 100_000).toInt() / 10.0}M"
        value >= 1_000 -> "${(value / 100).toInt() / 10.0}K"
        else -> value.toInt().toString()
    }

    private fun renderMessages(messages: List<ChatMessage>) {
        val signature = "${currentSession?.running}:${runningStartedAt}:" + messages.joinToString("|") {
            "${it.key}:${it.text.hashCode()}:${it.detail?.hashCode()}:${it.pending}:${it.state}"
        }
        if (signature == lastRenderedSignature) return
        lastRenderedSignature = signature
        if (animateNextAssistant) {
            messages.lastOrNull {
                it.role == ChatMessage.Role.ASSISTANT &&
                    it.key !in knownAssistantKeysBeforePrompt &&
                    it.text.isNotEmpty()
            }?.let {
                locallyAnimatedMessages += it.key
                animateNextAssistant = false
            }
        }
        streamingAnimations.values.forEach(mainHandler::removeCallbacks)
        streamingAnimations.clear()
        mainHandler.removeCallbacks(runClockTick)
        runClockView = null
        messageContainer.removeAllViews()
        emptyView.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = if (currentSession?.blank == true) "有什么可以帮忙的？\n\n在远端工作区开始一项任务" else "还没有可显示的消息"
        messages.forEach { messageContainer.addView(messageBubble(it)) }
        if (currentSession?.running == true) {
            messageContainer.addView(buildTurnStatus(), LinearLayout.LayoutParams(WRAP, dp(42)))
            mainHandler.post(runClockTick)
        }
        lastMessages = messages
        messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun buildTurnStatus(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(8), 0, dp(8))
        contentDescription = "模型正在运行"
        addView(ShimmerTextView(this@MainActivity).apply {
            text = "Deep diving..."
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        runClockView = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
            setPadding(dp(8), 0, 0, 0)
            visibility = View.GONE
        }
        addView(runClockView, LinearLayout.LayoutParams(WRAP, WRAP))
        updateRunClock()
    }

    private fun updateRunClock() {
        val started = runningStartedAt ?: return
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
        runClockView?.apply {
            visibility = if (elapsed >= 15_000L) View.VISIBLE else View.GONE
            text = formatRunDuration(elapsed)
        }
    }

    private fun formatRunDuration(ms: Long): String {
        val totalSeconds = ms / 1_000L
        return if (totalSeconds < 60L) "${totalSeconds}s"
        else "${totalSeconds / 60L}m ${totalSeconds % 60L}s"
    }

    private fun messageBubble(message: ChatMessage): View {
        if (message.activityKind != null || message.role == ChatMessage.Role.REASONING || message.role == ChatMessage.Role.TOOL || message.role == ChatMessage.Role.ACTIVITY) {
            return activityDisclosure(message)
        }
        val shouldAnimate = message.role == ChatMessage.Role.ASSISTANT &&
            (message.pending || message.key in locallyAnimatedMessages)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (message.role == ChatMessage.Role.USER) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(11))
        }
        val bubble = TextView(this).apply {
            if (!shouldAnimate) {
                text = styledMessage(message.text)
            }
            textSize = if (message.role == ChatMessage.Role.TOOL) 13f else 16f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(COLOR_TEXT)
            setTextIsSelectable(true)
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(
                if (message.role == ChatMessage.Role.ASSISTANT) dp(1) else dp(14),
                if (message.role == ChatMessage.Role.ASSISTANT) dp(4) else dp(9),
                if (message.role == ChatMessage.Role.ASSISTANT) dp(1) else dp(14),
                if (message.role == ChatMessage.Role.ASSISTANT) dp(4) else dp(9),
            )
            background = when (message.role) {
                ChatMessage.Role.USER -> rounded(COLOR_USER_BUBBLE, 20f)
                ChatMessage.Role.TOOL -> rounded(COLOR_TOOL, 13f)
                ChatMessage.Role.NOTICE -> rounded(COLOR_NOTICE, 13f)
                ChatMessage.Role.ACTIVITY -> null
                ChatMessage.Role.ASSISTANT -> null
                ChatMessage.Role.REASONING -> null
            }
        }
        if (shouldAnimate) {
            animateStreamingText(bubble, message)
        }
        if (message.role == ChatMessage.Role.USER) bubble.maxWidth = dp(285)
        val width = if (message.role == ChatMessage.Role.USER) WRAP else MATCH
        outer.addView(bubble, LinearLayout.LayoutParams(width, WRAP))
        if (message.role == ChatMessage.Role.ASSISTANT) {
            outer.addView(TextView(this).apply {
                text = if (message.pending) "●  正在生成" else "复制   ·   重新生成"
                textSize = 10f
                setTextColor(COLOR_MUTED)
                setPadding(dp(2), dp(4), 0, 0)
            })
        }
        return outer
    }

    private fun activityDisclosure(message: ChatMessage): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(3))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
            minimumHeight = dp(34)
        }
        val icon = when (message.activityKind) {
            ChatMessage.ActivityKind.THINK -> R.drawable.ic_think_harness
            ChatMessage.ActivityKind.READ -> R.drawable.ic_folder_outline
            ChatMessage.ActivityKind.SEARCH -> R.drawable.ic_search_outline
            ChatMessage.ActivityKind.WRITE -> R.drawable.ic_create_outline
            ChatMessage.ActivityKind.TODO -> R.drawable.ic_checklist_harness
            ChatMessage.ActivityKind.CONTEXT -> R.drawable.ic_build_outline
            ChatMessage.ActivityKind.RETRY -> R.drawable.ic_think_harness
            ChatMessage.ActivityKind.ERROR, ChatMessage.ActivityKind.WARNING, ChatMessage.ActivityKind.UNKNOWN,
            ChatMessage.ActivityKind.TERMINAL, null -> R.drawable.ic_terminal_harness
        }
        header.addView(ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(COLOR_ACTIVITY)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = null
            isClickable = false
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        header.addView(TextView(this).apply {
            text = message.title ?: if (message.role == ChatMessage.Role.REASONING) "Think" else "Tool call"
            textSize = 14f
            setTextColor(COLOR_ACTIVITY)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(WRAP, dp(32)))
        header.addView(TextView(this).apply {
            text = "·"
            textSize = 14f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), dp(32)))
        header.addView(TextView(this).apply {
            text = if (message.role == ChatMessage.Role.REASONING && message.pending) {
                message.text.trimEnd().lineSequence().lastOrNull().orEmpty()
            } else message.text.lineSequence().firstOrNull().orEmpty()
            textSize = 14f
            setTextColor(when (message.state) {
                ChatMessage.State.ERROR -> COLOR_RED
                ChatMessage.State.STOPPED -> COLOR_AMBER
                else -> COLOR_MUTED
            })
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        val details = TextView(this).apply {
            text = message.detail ?: message.text
            textSize = 13f
            typeface = if (message.role == ChatMessage.Role.TOOL || message.activityKind == ChatMessage.ActivityKind.TERMINAL) Typeface.MONOSPACE else Typeface.DEFAULT
            setTextColor(COLOR_MUTED)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(30), dp(7), dp(12), dp(10))
            background = if (message.role == ChatMessage.Role.TOOL || message.activityKind == ChatMessage.ActivityKind.TERMINAL) rounded(COLOR_CODE_SURFACE, 12f) else null
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        val expandable = details.text.isNotBlank()
        if (expandable) {
            header.isClickable = true
            header.isFocusable = true
            header.contentDescription = "${message.title ?: "Think"}，点击展开"
            header.setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                header.contentDescription = "${message.title ?: "Think"}，${if (details.visibility == View.VISIBLE) "点击收起" else "点击展开"}"
            }
        }
        outer.addView(header, LinearLayout.LayoutParams(MATCH, dp(34)))
        outer.addView(details, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = dp(4)
            topMargin = dp(2)
            bottomMargin = dp(4)
        })
        return outer
    }

    private fun animateStreamingText(view: TextView, message: ChatMessage) {
        val target = message.text
        val previous = streamingRendered[message.key].orEmpty()
        var shown = if (target.startsWith(previous)) previous else ""
        if (shown.isEmpty() && target.codePointCount(0, target.length) > MAX_STREAM_BACKLOG) {
            val prefixPoints = target.codePointCount(0, target.length) - MAX_STREAM_BACKLOG
            shown = target.substring(0, target.offsetByCodePoints(0, prefixPoints))
        }
        view.text = styledMessage(shown)
        if (shown == target) return

        val animation = object : Runnable {
            override fun run() {
                if (shown.length >= target.length) {
                    view.text = styledMessage(target)
                    streamingRendered[message.key] = target
                    streamingAnimations.remove(message.key)
                    if (!message.pending) locallyAnimatedMessages.remove(message.key)
                    return
                }
                val next = shown.length + Character.charCount(Character.codePointAt(target, shown.length))
                shown = target.substring(0, next)
                streamingRendered[message.key] = shown
                view.text = styledStreamingMessage(shown)
                messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
                mainHandler.postDelayed(this, if (shown.length < target.length) STREAM_CHARACTER_MS else STREAM_FADE_MS)
            }
        }
        streamingAnimations[message.key] = animation
        mainHandler.post(animation)
    }

    private fun styledStreamingMessage(source: String): CharSequence {
        val text = SpannableStringBuilder(styledMessage(source))
        if (text.isNotEmpty()) {
            val newestStart = text.length - Character.charCount(Character.codePointBefore(text, text.length))
            text.setSpan(
                ForegroundColorSpan(Color.argb(STREAM_NEWEST_ALPHA, 244, 244, 244)),
                newestStart,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return text
    }

    private fun styledMessage(source: String): CharSequence {
        val text = SpannableStringBuilder(source)
        Regex("(?m)^(#{1,6})[ \\t]+(.+)$").findAll(text).toList().asReversed().forEach { match ->
            val markerStart = match.range.first
            val contentStart = match.groups[2]?.range?.first ?: return@forEach
            val level = match.groups[1]?.value?.length ?: 6
            val contentLength = match.groups[2]?.value?.length ?: 0
            text.delete(markerStart, contentStart)
            val headingEnd = (markerStart + contentLength).coerceAtMost(text.length)
            text.setSpan(StyleSpan(Typeface.BOLD), markerStart, headingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val scale = when (level) {
                1 -> 1.16f
                2 -> 1.12f
                3 -> 1.08f
                else -> 1.04f
            }
            text.setSpan(RelativeSizeSpan(scale), markerStart, headingEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("(?m)^[ \\t]*-{3,}[ \\t]*$").findAll(text).toList().asReversed().forEach { match ->
            text.replace(match.range.first, match.range.last + 1, "────────")
            text.setSpan(
                ForegroundColorSpan(COLOR_MUTED),
                match.range.first,
                (match.range.first + 8).coerceAtMost(text.length),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        Regex("\\*\\*(.+?)\\*\\*", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(text).toList().asReversed().forEach { match ->
            val start = match.range.first
            val endExclusive = match.range.last + 1
            text.delete(endExclusive - 2, endExclusive)
            text.delete(start, start + 2)
            text.setSpan(StyleSpan(Typeface.BOLD), start, endExclusive - 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("`([^`\\n]+)`").findAll(text).toList().asReversed().forEach { match ->
            val start = match.range.first
            val endExclusive = match.range.last + 1
            text.delete(endExclusive - 1, endExclusive)
            text.delete(start, start + 1)
            val styledEnd = endExclusive - 2
            text.setSpan(TypefaceSpan("monospace"), start, styledEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(BackgroundColorSpan(COLOR_INLINE_CODE), start, styledEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private fun sendPrompt() {
        val session = currentSession ?: run {
            Toast.makeText(this, "请先新建或选择会话", Toast.LENGTH_SHORT).show()
            return
        }
        val text = composer.text.toString().trim()
        if (text.isEmpty()) return
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        composer.setText("")
        knownAssistantKeysBeforePrompt = lastMessages
            .filter { it.role == ChatMessage.Role.ASSISTANT }
            .mapTo(mutableSetOf()) { it.key }
        animateNextAssistant = true
        setComposerEnabled(false)
        hideKeyboard()
        worker.execute {
            try {
                api.prompt(session.id, content, promptMode)
                mainHandler.post {
                    setComposerEnabled(true)
                    currentSession = currentSession?.copy(running = true, blank = false)
                    runningStartedAt = System.currentTimeMillis()
                    updateStatus("运行中", STATUS_CONNECTED)
                    refresh(showSpinner = false)
                }
            } catch (_: HarnessApi.AuthenticationRequired) {
                mainHandler.post {
                    animateNextAssistant = false
                    setComposerEnabled(true)
                    showAuth()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    animateNextAssistant = false
                    setComposerEnabled(true)
                    composer.setText(text)
                    Toast.makeText(this, error.message ?: "发送失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setComposerEnabled(enabled: Boolean) {
        composer.isEnabled = enabled
        updateSendState()
    }

    private fun updateSendState() {
        if (!::sendButton.isInitialized || !::composer.isInitialized) return
        val running = currentSession?.running == true
        val hasDraft = composer.text?.isNotBlank() == true
        val enabled = running || (composer.isEnabled && currentSession != null && hasDraft)
        sendButton.isEnabled = enabled
        sendButton.alpha = 1f
        sendButton.imageTintList = ColorStateList.valueOf(if (enabled) Color.WHITE else COLOR_SEND_DISABLED_ICON)
        sendButton.background = rounded(if (enabled) COLOR_BLUE else COLOR_SEND_DISABLED, 22f)
    }

    private fun showSessions() {
        composer.clearFocus()
        hideKeyboard()
        renderSessionList()
        val drawerWidth = drawerWidthPx()
        drawerPanel.layoutParams = (drawerPanel.layoutParams as FrameLayout.LayoutParams).apply {
            width = drawerWidth
        }
        drawerOverlay.visibility = View.VISIBLE
        drawerPanel.translationX = -drawerWidth.toFloat()
        drawerPanel.animate().translationX(0f).setDuration(180).start()
        worker.execute {
            val latest = runCatching { api.workspaces() }.getOrDefault(emptyList())
            mainHandler.post {
                if (latest.isNotEmpty()) {
                    drawerWorkspaces = latest
                    if (drawerOverlay.visibility == View.VISIBLE) renderSessionList()
                }
            }
        }
    }

    private fun renderSessionList() {
        sessionList.removeAllViews()
        val query = drawerSearchQuery.trim()
        val orderedSessions = sessions.filterNot { it.blank }.let {
            if (drawerOrderLastUpdated) it.sortedByDescending { session -> session.updatedAt } else it
        }
        val matchedWorkspacePaths = drawerWorkspaces.filter {
            query.isNotBlank() && (it.title.contains(query, true) || it.path.contains(query, true))
        }.map { it.path }.toSet()
        val matchedWorkspaceSessions = drawerWorkspaces.filter {
            query.isNotBlank() && (it.title.contains(query, true) || it.path.contains(query, true))
        }.flatMap { it.sessionIds }.toSet()
        val visibleSessions = orderedSessions.filter { session ->
            query.isBlank() ||
                session.title.orEmpty().contains(query, true) ||
                session.cwd.orEmpty().contains(query, true) ||
                session.id in matchedWorkspaceSessions ||
                session.cwd in matchedWorkspacePaths
        }
        if (visibleSessions.isEmpty()) {
            sessionList.addView(TextView(this).apply {
                text = if (query.isBlank()) "No sessions yet" else "No matching sessions"
                textSize = 13f
                setTextColor(COLOR_MUTED)
                setPadding(dp(38), dp(14), dp(8), dp(14))
            })
            return
        }

        if (!drawerGroupByWorkspace) {
            visibleSessions.forEach { sessionList.addView(sessionRow(it)) }
            return
        }

        val placed = mutableSetOf<String>()
        drawerWorkspaces.forEach { workspace ->
            val members = visibleSessions.filter { session ->
                session.id in workspace.sessionIds || session.cwd == workspace.path
            }
            if (members.isNotEmpty()) {
                sessionList.addView(workspaceHeader(workspace.title))
                members.forEach { session ->
                    sessionList.addView(sessionRow(session))
                    placed += session.id
                }
            }
        }

        visibleSessions.filterNot { it.id in placed }
            .groupBy { it.cwd.orEmpty() }
            .forEach { (path, members) ->
                sessionList.addView(workspaceHeader(path.trimEnd('/').substringAfterLast('/').ifBlank { "Workspace" }))
                members.forEach { session -> sessionList.addView(sessionRow(session)) }
            }
    }

    private fun sessionRow(session: HarnessApi.Session) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(34), 0, dp(10), 0)
        background = if (session.id == currentSession?.id) rounded(COLOR_SELECTED, 8f) else null
        isClickable = true
        isFocusable = true
        setOnClickListener { closeDrawer(); selectSession(session) }
        setOnLongClickListener { showSessionActions(session); true }
        addView(TextView(this@MainActivity).apply {
            text = session.title ?: session.cwd?.substringAfterLast('/') ?: "Untitled"
            textSize = 13f
            setTextColor(COLOR_TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(TextView(this@MainActivity).apply {
            text = relativeSessionAge(session.updatedAt)
            textSize = 12f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }, LinearLayout.LayoutParams(WRAP, MATCH).apply { marginStart = dp(8) })
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(42)).apply { bottomMargin = dp(2) }
    }

    private fun relativeSessionAge(value: Long): String {
        val updatedAtMs = if (value in 1..999_999_999_999L) value * 1_000L else value
        val elapsed = (System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L)
        val minutes = (elapsed / 60_000L).coerceAtLeast(1L)
        return when {
            minutes < 60 -> "${minutes}min"
            minutes < 1_440 -> "${minutes / 60}h"
            else -> "${minutes / 1_440}d"
        }
    }

    private fun showSessionActions(session: HarnessApi.Session) {
        AlertDialog.Builder(this)
            .setTitle(session.title ?: "会话操作")
            .setItems(arrayOf("重命名", "Fork 会话")) { _, which ->
                if (which == 0) showRenameSession(session) else confirmForkSession(session)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameSession(session: HarnessApi.Session) {
        val input = EditText(this).apply {
            setText(session.title.orEmpty())
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名会话")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) mutateSession { api.renameSession(session.id, title) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmForkSession(session: HarnessApi.Session) {
        AlertDialog.Builder(this)
            .setTitle("Fork 会话")
            .setMessage("从最后一个完整回合创建一个新会话？")
            .setPositiveButton("创建") { _, _ ->
                progress.visibility = View.VISIBLE
                worker.execute {
                    try {
                        val id = api.forkSession(session.id)
                        mainHandler.post {
                            progress.visibility = View.GONE
                            currentSession = HarnessApi.Session(id, session.title, session.cwd, session.agentPreset, System.currentTimeMillis(), false, false)
                            closeDrawer()
                            refresh(true)
                        }
                    } catch (error: Exception) {
                        mainHandler.post { progress.visibility = View.GONE; Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun mutateSession(block: () -> Unit) {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                block()
                mainHandler.post { progress.visibility = View.GONE; refresh(true) }
            } catch (error: Exception) {
                mainHandler.post { progress.visibility = View.GONE; Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun closeDrawer() {
        if (drawerOverlay.visibility != View.VISIBLE) return
        val drawerWidth = drawerPanel.width.takeIf { it > 0 } ?: drawerWidthPx()
        drawerPanel.animate().translationX(-drawerWidth.toFloat()).setDuration(160).withEndAction {
            drawerOverlay.visibility = View.GONE
        }.start()
    }

    private fun showNewSession() {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                val workspaces = api.workspaces()
                mainHandler.post {
                    progress.visibility = View.GONE
                    val savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getString(PREF_DEFAULT_WORKSPACE_ID, null)
                    val preferred = workspaces.firstOrNull { it.id == savedId }
                    if (savedId != null && preferred == null) {
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .remove(PREF_DEFAULT_WORKSPACE_ID)
                            .apply()
                    }
                    val defaultLabel = preferred?.let { "${it.title}\n${it.path}" }
                        ?: "Harness 默认工作目录"
                    AlertDialog.Builder(this)
                        .setTitle("新建会话")
                        .setItems(arrayOf("使用默认目录\n$defaultLabel", "手动选择目录…")) { _, which ->
                            if (which == 0) createSession(preferred?.id) else showWorkspacePicker(workspaces, savedId)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (error: Exception) {
                mainHandler.post { progress.visibility = View.GONE; Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showWorkspacePicker(workspaces: List<HarnessApi.Workspace>, selectedId: String?) {
        val labels = arrayOf("Harness 默认工作目录", *workspaces.map { "${it.title}\n${it.path}" }.toTypedArray())
        val checked = workspaces.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this)
            .setTitle("手动选择目录")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val workspaceId = if (which == 0) null else workspaces[which - 1].id
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                    if (workspaceId == null) remove(PREF_DEFAULT_WORKSPACE_ID)
                    else putString(PREF_DEFAULT_WORKSPACE_ID, workspaceId)
                }.apply()
                dialog.dismiss()
                createSession(workspaceId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createSession(workspaceId: String?) {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                val id = api.createSession(workspaceId)
                mainHandler.post {
                    currentSession = HarnessApi.Session(id, null, null, null, System.currentTimeMillis(), false, true)
                    refresh(showSpinner = true)
                }
            } catch (error: Exception) {
                mainHandler.post { progress.visibility = View.GONE; Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showModels() {
        val session = currentSession ?: return
        val models = currentModels ?: run { refresh(true); return }
        if (!models.routable) {
            Toast.makeText(this, "当前模型路由不可用", Toast.LENGTH_LONG).show()
            return
        }
        val current = models.items.firstOrNull { it.provider == models.currentProvider && it.id == models.currentModel }
        val root = menuSurface()
        val popup = popupFor(modelButton, root, 250)
        root.addView(drillMenuRow("Model", current?.name ?: models.currentModel) {
            popup.dismiss()
            modelButton.post { showModelPicker(session, models) }
        })
        if (current?.efforts?.isNotEmpty() == true) {
            val effort = models.currentEffort?.let { id -> current.efforts.firstOrNull { it.first == id }?.second ?: id }
                ?: current.defaultEffort?.let { id -> current.efforts.firstOrNull { it.first == id }?.second ?: id }
                ?: "Off"
            root.addView(drillMenuRow("Effort", effort) {
                popup.dismiss()
                modelButton.post { showEffortPicker() }
            })
        }
        showPopupAbove(modelButton, popup, root)
    }

    private fun showCommands(anchor: View) {
        val commands = listOf(
            "compact" to "Compact older conversation history",
            "export" to "Download this Session log as a ZIP archive",
            "feedback" to "record feedback about this session",
            "goal" to "set or view the goal for a long-running task",
            "permission" to "Switch the permission preset (sandbox mode + approval policy)",
            "plan" to "Enter or leave plan mode",
            "model" to "Select the model for this conversation",
        )
        val surface = menuSurface()
        surface.addView(menuSection("Commands"))
        var popup: PopupWindow? = null
        commands.forEach { (name, description) ->
            surface.addView(commandMenuRow(name, description) {
                popup?.dismiss()
                when (name) {
                    "compact" -> currentSession?.id?.let { runCommand(it, "/compact") }
                    "export" -> exportSessionLog()
                    "permission" -> anchor.post { showPermissionPicker() }
                    "plan" -> anchor.post { showModePicker() }
                    "model" -> anchor.post { showModels() }
                    else -> insertCommand(name)
                }
            })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(surface, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        popup = popupFor(anchor, scroll, 300).apply { height = dp(418) }
        showPopupAbove(anchor, popup, scroll)
    }

    private fun commandMenuRow(name: String, description: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(5), dp(10), dp(5))
        isClickable = true
        isFocusable = true
        background = rounded(Color.TRANSPARENT, 10f)
        contentDescription = "$name, $description"
        addView(TextView(this@MainActivity).apply {
            text = name
            textSize = 14f
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(MATCH, dp(22)))
        addView(TextView(this@MainActivity).apply {
            text = description
            textSize = 11f
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(MATCH, dp(20)))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(54))
    }

    private fun insertCommand(name: String) {
        val command = "/$name "
        composer.setText(command)
        composer.setSelection(command.length)
        composer.requestFocus()
        composer.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun exportSessionLog() {
        val session = currentSession ?: return
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                val export = api.prepareSessionExport(session.id)
                mainHandler.post {
                    progress.visibility = View.GONE
                    val filename = "deepseek-harness-session-${session.id.take(8)}-${System.currentTimeMillis()}.zip"
                    val request = DownloadManager.Request(Uri.parse(export.url)).apply {
                        setTitle("DeepSeek Harness Session log")
                        setDescription("Downloading Session ZIP")
                        setMimeType("application/zip")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                        export.cookie?.let { addRequestHeader("Cookie", it) }
                        addRequestHeader("User-Agent", "DeepSeekHarnessMobile/${BuildConfig.VERSION_NAME}")
                    }
                    getSystemService(DownloadManager::class.java).enqueue(request)
                    AlertDialog.Builder(this)
                        .setTitle("Session download started")
                        .setMessage("The Session ZIP is downloading to Downloads.")
                        .setPositiveButton("Close", null)
                        .show()
                }
            } catch (_: HarnessApi.AuthenticationRequired) {
                mainHandler.post { progress.visibility = View.GONE; showAuth() }
            } catch (error: Exception) {
                mainHandler.post {
                    progress.visibility = View.GONE
                    Toast.makeText(this, error.message ?: "Unable to export Session log", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showModelPicker(session: HarnessApi.Session, models: HarnessApi.Models) {
        val surface = menuSurface()
        var popup: PopupWindow? = null
        models.items.groupBy { it.providerName }.forEach { (provider, group) ->
            surface.addView(menuSection(provider))
            group.forEach { model ->
                val selected = model.provider == models.currentProvider && model.id == models.currentModel
                surface.addView(choiceMenuRow(model.name, selected) {
                    popup?.dismiss()
                    applyModel(session, model, model.defaultEffort)
                })
            }
        }
        popup = popupFor(modelButton, surface, 250)
        showPopupAbove(modelButton, popup, surface)
    }

    private fun showEffortPicker() {
        val session = currentSession ?: return
        val models = currentModels ?: return
        val model = models.items.firstOrNull { it.provider == models.currentProvider && it.id == models.currentModel } ?: return
        if (model.efforts.isEmpty()) return
        val surface = menuSurface()
        var popup: PopupWindow? = null
        model.efforts.forEach { (id, name) ->
            surface.addView(choiceMenuRow(name, id == models.currentEffort) {
                popup?.dismiss()
                applyModel(session, model, id)
            })
        }
        popup = popupFor(modelButton, surface, 220)
        showPopupAbove(modelButton, popup, surface)
    }

    private fun showDeliveryPicker() {
        val choices = arrayOf("排队发送（queue）", "立即插话（steer）")
        AlertDialog.Builder(this)
            .setTitle("消息发送方式")
            .setSingleChoiceItems(choices, if (promptMode == "steer") 1 else 0) { dialog, which ->
                promptMode = if (which == 1) "steer" else "queue"
                renderControls()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showModePicker() {
        val session = currentSession ?: return
        val active = currentControls.planActive ?: run {
            Toast.makeText(this, "此 Harness 未启用 plan mode", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Mode")
            .setSingleChoiceItems(arrayOf("默认模式", "计划模式（Plan）"), if (active) 1 else 0) { dialog, which ->
                dialog.dismiss()
                runCommand(session.id, if (which == 1) "/plan" else "/plan off")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPermissionPicker() {
        val session = currentSession ?: return
        val options = currentControls.permissionOptions
        if (options.isEmpty()) {
            Toast.makeText(this, "此 Harness 未公开权限预设", Toast.LENGTH_SHORT).show()
            return
        }
        val ordered = listOf("read-only", "workspace-write", "danger-full-access")
            .mapNotNull { value -> options.firstOrNull { it.value == value } }
        val surface = menuSurface()
        var popup: PopupWindow? = null
        ordered.forEach { option ->
            surface.addView(permissionMenuRow(option.value, option.value == currentControls.permission) {
                popup?.dismiss()
                if (option.value == "danger-full-access" && option.value != currentControls.permission) {
                    confirmFullAccess(session.id)
                } else if (option.value != currentControls.permission) {
                    runCommand(session.id, "/permission ${option.value}")
                }
            })
        }
        popup = popupFor(permissionButton, surface, 220)
        showPopupAbove(permissionButton, popup, surface)
    }

    private fun permissionLabel(value: String?): String = when (value) {
        "workspace-write" -> "Workspace Write"
        "danger-full-access" -> "Full access"
        "read-only" -> "Read Only"
        "custom" -> "Custom"
        else -> value ?: "未知"
    }

    private fun permissionIcon(value: String?): Int = when (value) {
        "read-only" -> R.drawable.ic_permission_read_only
        "danger-full-access" -> R.drawable.ic_permission_full_access
        else -> R.drawable.ic_permission_workspace_write
    }

    private fun menuSurface() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = roundedStroke(COLOR_MENU, COLOR_TODO_BORDER, 12f)
    }

    private fun popupFor(anchor: View, content: View, widthDp: Int) = PopupWindow(
        content,
        dp(widthDp),
        WRAP,
        true,
    ).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        elevation = dp(10).toFloat()
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
    }

    private fun showPopupAbove(anchor: View, popup: PopupWindow, content: View) {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(420), View.MeasureSpec.AT_MOST),
        )
        popup.showAsDropDown(anchor, 0, -anchor.height - content.measuredHeight - dp(8), Gravity.END)
    }

    private fun menuSection(label: String) = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(COLOR_MUTED)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(2))
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(30))
    }

    private fun drillMenuRow(label: String, value: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(8), 0)
        isClickable = true
        isFocusable = true
        background = rounded(Color.TRANSPARENT, 10f)
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 14f
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(8) })
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_chevron_down_harness)
            rotation = -90f
            imageTintList = ColorStateList.valueOf(COLOR_MUTED)
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(44))
    }

    private fun choiceMenuRow(label: String, selected: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        if (selected) background = rounded(COLOR_MENU_SELECTED, 10f)
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(ImageView(this@MainActivity).apply {
            if (selected) setImageResource(R.drawable.ic_check_harness)
            imageTintList = ColorStateList.valueOf(COLOR_TEXT)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(44))
    }

    private fun permissionMenuRow(value: String, selected: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        if (selected) background = rounded(COLOR_MENU_SELECTED, 10f)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(permissionIcon(value))
            imageTintList = ColorStateList.valueOf(COLOR_ACTIVITY)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = permissionLabel(value)
            textSize = 14f
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(ImageView(this@MainActivity).apply {
            if (selected) setImageResource(R.drawable.ic_check_harness)
            imageTintList = ColorStateList.valueOf(COLOR_TEXT)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(48))
    }

    private fun confirmFullAccess(sessionId: String) {
        val dialog = Dialog(this)
        val dialogRegular = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.SANS_SERIF, 400, false)
        } else {
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
            background = roundedStroke(COLOR_WEB_SETTINGS, COLOR_WEB_SETTINGS_BORDER, 18f)
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Enable Full access?"
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(COLOR_TEXT)
                typeface = dialogRegular
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_close_outline)
                imageTintList = ColorStateList.valueOf(COLOR_CONTROL_TEXT)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = null
                contentDescription = "Close"
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }, LinearLayout.LayoutParams(MATCH, dp(40)))

        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(16), 0, 0)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.web_full_access_warning)
                contentDescription = "Warning"
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = "Full access reduces confirmation steps and lets the agent perform more actions directly, including sensitive operations, file changes, or external commands. Only use it when you trust the current task."
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                typeface = dialogRegular
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextColor(COLOR_CONTROL_TEXT)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val acknowledgement = CheckBox(this).apply {
            text = "I understand the risks and want to continue"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            typeface = dialogRegular
            setTextColor(COLOR_TEXT)
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(COLOR_BLUE, Color.rgb(132, 132, 135)),
            )
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(acknowledgement, LinearLayout.LayoutParams(MATCH, dp(44)).apply {
            topMargin = dp(8)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = TextView(this).apply {
            text = "Cancel"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            typeface = dialogRegular
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
            background = roundedStroke(Color.TRANSPARENT, COLOR_BORDER, 24f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        val enable = TextView(this).apply {
            text = "Enable Full access"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            typeface = dialogRegular
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        fun renderEnableState(checked: Boolean) {
            enable.isEnabled = checked
            enable.alpha = if (checked) 1f else 0.62f
            enable.setTextColor(Color.rgb(42, 42, 44))
            enable.background = rounded(
                if (checked) COLOR_TEXT else Color.rgb(157, 157, 160),
                24f,
            )
        }
        renderEnableState(false)
        acknowledgement.setOnCheckedChangeListener { _, checked -> renderEnableState(checked) }
        enable.setOnClickListener {
            if (acknowledgement.isChecked) {
                dialog.dismiss()
                runCommand(sessionId, "/permission danger-full-access")
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(dp(96), dp(44)).apply { marginEnd = dp(8) })
        actions.addView(enable, LinearLayout.LayoutParams(dp(174), dp(44)))
        panel.addView(actions, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(8) })

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.78f }
            setLayout(
                minOf(resources.displayMetrics.widthPixels - dp(32), dp(420)),
                WRAP,
            )
        }
    }

    private fun showContextDetails() {
        val context = currentContextUsage ?: return
        val surface = menuSurface().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
        surface.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "${context.percent}% of context used"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(TextView(this@MainActivity).apply {
                text = "~${formatCompact(context.usedTokens.toDouble())} / ${formatCompact(context.contextWindow.toDouble())}"
                textSize = 12f
                setTextColor(COLOR_TEXT)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }, LinearLayout.LayoutParams(WRAP, dp(28)))
        }, LinearLayout.LayoutParams(MATCH, dp(30)))
        surface.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = context.percent
            progressTintList = ColorStateList.valueOf(COLOR_BLUE)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_CONTROL)
        }, LinearLayout.LayoutParams(MATCH, dp(10)).apply { topMargin = dp(6); bottomMargin = dp(8) })
        listOf(
            "System prompt" to context.systemTokens,
            "Tools" to context.toolsTokens,
            "Messages" to context.messageTokens,
        ).filter { it.second != null }.forEach { (label, tokens) ->
            surface.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12f
                    setTextColor(COLOR_CONTROL_TEXT)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(0, dp(28), 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "~${formatCompact(tokens!!.toDouble())}"
                    textSize = 12f
                    setTextColor(COLOR_TEXT)
                    includeFontPadding = false
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(WRAP, dp(28)))
            })
        }
        val popup = popupFor(contextSeat, surface, 270)
        popup.showAsDropDown(contextSeat, 0, dp(4), Gravity.END)
    }

    private fun runCommand(sessionId: String, command: String) {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                api.command(sessionId, command)
                mainHandler.post { progress.visibility = View.GONE; refresh(false) }
            } catch (error: Exception) {
                mainHandler.post { progress.visibility = View.GONE; Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun applyModel(session: HarnessApi.Session, model: HarnessApi.Model, effort: String?) {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                api.selectModel(session.id, model, effort)
                mainHandler.post { progress.visibility = View.GONE; refresh(false) }
            } catch (error: Exception) {
                mainHandler.post { progress.visibility = View.GONE; Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSessionSearch() {
        val input = EditText(this).apply {
            hint = "标题或目录"
            setSingleLine(true)
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("搜索会话")
            .setView(input)
            .setPositiveButton("搜索") { _, _ ->
                val query = input.text.toString().trim()
                val matches = sessions.filter {
                    query.isBlank() || it.title.orEmpty().contains(query, true) || it.cwd.orEmpty().contains(query, true)
                }
                if (matches.isEmpty()) Toast.makeText(this, "没有匹配会话", Toast.LENGTH_SHORT).show()
                else showSessionMatches(matches)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSessionMatches(matches: List<HarnessApi.Session>) {
        AlertDialog.Builder(this)
            .setTitle("搜索结果")
            .setItems(matches.map { it.title ?: it.cwd?.substringAfterLast('/') ?: "未命名会话" }.toTypedArray()) { _, which ->
                closeDrawer()
                selectSession(matches[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showWorkspaceManager() {
        worker.execute {
            try {
                val workspaces = api.workspaces()
                mainHandler.post {
                    val savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DEFAULT_WORKSPACE_ID, null)
                    val labels = arrayOf("Harness 默认工作目录", *workspaces.map {
                        "${if (it.id == savedId) "● " else ""}${it.title}\n${it.path}"
                    }.toTypedArray())
                    AlertDialog.Builder(this)
                        .setTitle("工作区与新会话默认目录")
                        .setItems(labels) { _, which ->
                            val id = if (which == 0) null else workspaces[which - 1].id
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                                if (id == null) remove(PREF_DEFAULT_WORKSPACE_ID) else putString(PREF_DEFAULT_WORKSPACE_ID, id)
                            }.apply()
                            Toast.makeText(this, "已更新默认目录；新建时仍可手动选择", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (error: Exception) {
                mainHandler.post { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showAgentPresets() {
        val session = currentSession
        worker.execute {
            try {
                val presets = api.agentPresets()
                mainHandler.post {
                    if (presets.isEmpty()) {
                        Toast.makeText(this, "此 Harness 未配置 Agent preset", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    val labels = presets.map {
                        val selected = it.id == session?.agentPreset || session?.agentPreset == null && it.isDefault
                        val trust = if (it.trust == "system") "系统" else "用户"
                        "${if (selected) "● " else ""}${it.name}  ·  $trust${it.description?.let { d -> "\n$d" }.orEmpty()}${it.broken?.let { b -> "\n不可用：$b" }.orEmpty()}"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Agent 模式")
                        .setItems(labels) { _, which ->
                            val chosen = presets[which]
                            when {
                                session == null -> Toast.makeText(this, "请先新建会话", Toast.LENGTH_SHORT).show()
                                !session.blank -> Toast.makeText(this, "Agent 模式只能在会话开始前切换", Toast.LENGTH_LONG).show()
                                chosen.broken != null -> Toast.makeText(this, chosen.broken, Toast.LENGTH_LONG).show()
                                else -> applyAgentPreset(session, chosen)
                            }
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (error: Exception) {
                mainHandler.post { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun applyAgentPreset(session: HarnessApi.Session, preset: HarnessApi.AgentPreset) {
        progress.visibility = View.VISIBLE
        worker.execute {
            try {
                api.selectAgentPreset(session.id, preset.id)
                mainHandler.post {
                    progress.visibility = View.GONE
                    currentSession = currentSession?.copy(agentPreset = preset.id)
                    refresh(false)
                }
            } catch (error: Exception) {
                mainHandler.post { progress.visibility = View.GONE; Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("刷新")
            menu.add(if (currentSession?.running == true) "停止当前任务" else "新建会话")
            menu.add("在浏览器中打开")
            menu.add("分享地址")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "刷新" -> refresh(true)
                    "停止当前任务" -> cancelCurrent()
                    "新建会话" -> showNewSession()
                    "在浏览器中打开" -> serverUrl?.let { openExternal(Uri.parse(it)) }
                    "分享地址" -> shareAddress()
                }
                true
            }
            show()
        }
    }

    private fun cancelCurrent() {
        val id = currentSession?.id ?: return
        worker.execute {
            try {
                api.cancel(id)
                mainHandler.post { refresh(false) }
            } catch (error: Exception) {
                mainHandler.post { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun shareAddress() {
        val address = serverUrl ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, address)
        }, "Share DeepSeek Harness Mobile"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureAuthWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(authWebView, true)
        }
        authWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString DeepSeekHarnessMobile/${BuildConfig.VERSION_NAME}"
        }
        authWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = routeAuth(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = routeAuth(Uri.parse(url))

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                val uri = url?.let(Uri::parse)
                val base = serverUrl
                if (base != null && uri != null && InternalNavigationPolicy.isHarnessAuthDestination(
                        base,
                        uri.scheme,
                        uri.host,
                        uri.port,
                        uri.path,
                    )) {
                    mainHandler.postDelayed({ refresh(showSpinner = true) }, 500)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                Toast.makeText(this@MainActivity, "安全证书验证失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun routeAuth(uri: Uri): Boolean {
        val base = serverUrl ?: return true
        if (InternalNavigationPolicy.isTrusted(base, uri.scheme, uri.host, uri.port)) return false
        openExternal(uri)
        return true
    }

    private fun showAuth() {
        val address = serverUrl ?: run {
            showServerSetup()
            return
        }
        updateStatus("请登录", STATUS_VERIFY)
        if (authOverlay.visibility != View.VISIBLE) {
            authOverlay.visibility = View.VISIBLE
            authWebView.loadUrl(address)
        }
    }

    private fun hideAuth() {
        if (authOverlay.visibility == View.VISIBLE) {
            authOverlay.visibility = View.GONE
            authWebView.stopLoading()
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus(label: String, kind: Int) {
        statusView.text = "· $label"
        statusView.setTextColor(
            when (kind) {
                STATUS_CONNECTED -> if (label == "运行中") COLOR_BLUE else COLOR_MUTED
                STATUS_VERIFY -> COLOR_AMBER
                STATUS_ERROR -> COLOR_RED
                else -> COLOR_MUTED
            },
        )
    }

    private fun startMuxStream() {
        val generation = ++streamGeneration
        streamWorker.execute {
            while (!paused && generation == streamGeneration && !Thread.currentThread().isInterrupted) {
                try {
                    api.streamMux { frame ->
                        if (generation != streamGeneration) return@streamMux
                        val sessionId = frame.optNullableString("sessionId")
                        when (frame.optString("type")) {
                            "approval/requested" -> {
                                val rpcId = frame.optNullableString("_rpcId")
                                val approvalId = frame.optNullableString("approvalId")
                                if (sessionId != null && rpcId != null && approvalId != null) {
                                    val approval = HarnessApi.PendingApproval(
                                        rpcId = rpcId,
                                        sessionId = sessionId,
                                        approvalId = approvalId,
                                        toolName = frame.optString("toolName", "tool"),
                                        callId = frame.optNullableString("callId"),
                                        reason = frame.optNullableString("reason"),
                                    )
                                    mainHandler.post {
                                        pendingApprovalsBySession[sessionId] = approval
                                        approvalResponding = false
                                        if (sessionId == currentSession?.id) renderComposerSeat()
                                    }
                                }
                            }
                            "approval/resolved" -> if (sessionId != null) {
                                val approvalId = frame.optNullableString("approvalId")
                                mainHandler.post {
                                    val pending = pendingApprovalsBySession[sessionId]
                                    if (pending != null && (approvalId == null || pending.approvalId == approvalId)) {
                                        pendingApprovalsBySession.remove(sessionId)
                                        approvalResponding = false
                                        if (sessionId == currentSession?.id) renderComposerSeat()
                                    }
                                }
                            }
                        }
                        if (sessionId == currentSession?.id && frame.optString("type") in LIVE_SESSION_FRAMES) {
                            mainHandler.post {
                                if (!liveRefreshScheduled) {
                                    liveRefreshScheduled = true
                                    mainHandler.postDelayed(liveRefresh, LIVE_REFRESH_MS)
                                }
                            }
                        }
                    }
                } catch (_: HarnessApi.AuthenticationRequired) {
                    mainHandler.post { if (!paused) showAuth() }
                    return@execute
                } catch (error: Exception) {
                    // The slower history poll remains the recovery path while reconnecting.
                    Log.w("HarnessStream", "mux disconnected", error)
                }
                if (!paused && generation == streamGeneration) {
                    try {
                        Thread.sleep(STREAM_RECONNECT_MS)
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                }
            }
        }
    }

    private fun stopMuxStream() {
        streamGeneration += 1
        api.closeMux()
        mainHandler.removeCallbacks(liveRefresh)
        liveRefreshScheduled = false
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(composer.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        paused = false
        authWebView.onResume()
        if (debugTodoPreview || debugControlsPreview || debugApprovalPreview) return
        if (serverUrl == null) return
        mainHandler.removeCallbacks(poll)
        mainHandler.postDelayed(poll, 1_000)
        startMuxStream()
    }

    override fun onPause() {
        paused = true
        stopMuxStream()
        mainHandler.removeCallbacks(poll)
        CookieManager.getInstance().flush()
        authWebView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        refreshGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        streamWorker.shutdownNow()
        authWebView.stopLoading()
        authWebView.destroy()
        super.onDestroy()
    }

    private fun configureBackNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }
    }

    private fun handleBack() {
        when {
            authOverlay.visibility == View.VISIBLE && authWebView.canGoBack() -> authWebView.goBack()
            drawerOverlay.visibility == View.VISIBLE -> closeDrawer()
            else -> moveTaskToBack(true)
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun roundedStroke(color: Int, stroke: Int, radiusDp: Float) = rounded(color, radiusDp).apply {
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private const val PREFS_NAME = "deepseek_remote_preferences"
        private const val PREF_SERVER_URL = "server_base_url"
        private const val EXTRA_DEBUG_TODO_PREVIEW = "debug_todo_preview"
        private const val EXTRA_DEBUG_CONTROLS_PREVIEW = "debug_controls_preview"
        private const val EXTRA_DEBUG_APPROVAL_PREVIEW = "debug_approval_preview"
        private const val EXTRA_DEBUG_ACTIVITY_PREVIEW = "debug_activity_preview"
        private const val DRAWER_WIDTH_FRACTION = 0.86f
        private const val PREF_DEFAULT_WORKSPACE_ID = "default_workspace_id"
        private const val PREF_DRAWER_GROUP_WORKSPACE = "drawer_group_workspace"
        private const val PREF_DRAWER_ORDER_UPDATED = "drawer_order_updated"
        private const val MAX_STREAM_BACKLOG = 64
        private const val STREAM_CHARACTER_MS = 24L
        private const val STREAM_FADE_MS = 90L
        private const val STREAM_NEWEST_ALPHA = 92
        private const val LIVE_REFRESH_MS = 90L
        private const val STREAM_RECONNECT_MS = 800L
        private val LIVE_SESSION_FRAMES = setOf("session/event", "session/projection", "session/queue", "session/jobs")
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val STATUS_CONNECTED = 1
        private const val STATUS_VERIFY = 2
        private const val STATUS_ERROR = 3
        private val COLOR_SURFACE = Color.rgb(0, 0, 0)
        private val COLOR_DRAWER = Color.rgb(15, 15, 15)
        private val COLOR_COMPOSER = Color.rgb(33, 33, 33)
        private val COLOR_CODE_SURFACE = Color.rgb(24, 24, 26)
        private val COLOR_CONTROL = Color.rgb(28, 28, 28)
        private val COLOR_CONTROL_TEXT = Color.rgb(214, 214, 214)
        private val COLOR_WEB_SETTINGS = Color.rgb(46, 46, 49)
        private val COLOR_WEB_SETTINGS_BORDER = Color.rgb(62, 62, 65)
        private val COLOR_SELECTED = Color.rgb(38, 38, 38)
        private val COLOR_USER_BUBBLE = Color.rgb(47, 47, 47)
        private val COLOR_TEXT = Color.rgb(244, 244, 244)
        private val COLOR_MUTED = Color.rgb(171, 171, 171)
        private val COLOR_BORDER = Color.rgb(76, 76, 76)
        private val COLOR_BORDER_SUBTLE = Color.rgb(62, 62, 62)
        private val COLOR_TOOL = Color.rgb(30, 30, 30)
        private val COLOR_TODO_PANEL = Color.rgb(54, 54, 56)
        private val COLOR_TODO_BORDER = Color.rgb(72, 72, 75)
        private val COLOR_MENU = Color.rgb(55, 55, 57)
        private val COLOR_MENU_SELECTED = Color.rgb(73, 73, 76)
        private val COLOR_NOTICE = Color.rgb(63, 48, 20)
        private val COLOR_INLINE_CODE = Color.rgb(38, 38, 38)
        private val COLOR_GREEN = Color.rgb(91, 207, 139)
        private val COLOR_BLUE = Color.rgb(82, 139, 255)
        private val COLOR_SEND_DISABLED = Color.rgb(55, 68, 94)
        private val COLOR_SEND_DISABLED_ICON = Color.rgb(145, 148, 154)
        private val COLOR_ACTIVITY = Color.rgb(190, 193, 199)
        private val COLOR_AMBER = Color.rgb(251, 191, 36)
        private val COLOR_APPROVAL_STRIP = Color.rgb(39, 35, 24)
        private val COLOR_RED = Color.rgb(248, 113, 113)
    }
}
