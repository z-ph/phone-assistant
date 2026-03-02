package com.example.myapplication.ui.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.agent.AgentAction
import com.example.myapplication.agent.AgentState
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating window service to show agent execution status
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"

        @Volatile
        private var instance: FloatingWindowService? = null

        // Callback for stop button click
        var onStopButtonClick: (() -> Unit)? = null

        fun getInstance(): FloatingWindowService? = instance

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            context.stopService(intent)
        }
    }

    private val logger = Logger(TAG)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isExpanded = true
    private var isMinimized = false

    // UI components
    private var containerLayout: FrameLayout? = null
    private var expandedLayout: LinearLayout? = null
    private var minimizedLayout: FrameLayout? = null
    private var statusText: TextView? = null
    private var stepText: TextView? = null
    private var actionText: TextView? = null
    private var thinkingText: TextView? = null
    private var logScrollView: ScrollView? = null
    private var logText: TextView? = null
    private var stopButton: ImageButton? = null

    // Window params
    private var params: WindowManager.LayoutParams? = null
    private var minimizedParams: WindowManager.LayoutParams? = null

    // State tracking
    private var stateFlow: StateFlow<AgentState>? = null
    private var stateJob: Job? = null

    // Log buffer
    private val logBuffer = StringBuilder()
    private val maxLogLines = 50

    override fun onCreate() {
        super.onCreate()
        instance = this
        logger.d("FloatingWindowService created")
        createFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.d("FloatingWindowService started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        logger.d("FloatingWindowService destroyed")
        stateJob?.cancel()
        scope.cancel()
        removeFloatingWindow()
        instance = null
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Create expanded view
        floatingView = createExpandedView()

        // Window params for expanded view
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        params = WindowManager.LayoutParams(
            (screenWidth * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        // Minimized params
        minimizedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            params!!.type,
            params!!.flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 100
        }

        try {
            windowManager?.addView(floatingView, params)
            logger.d("Floating window created")
        } catch (e: Exception) {
            logger.e("Failed to create floating window: ${e.message}")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createExpandedView(): View {
        val context = this

        // Root container
        containerLayout = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.floating_window_bg)
            setPadding(16, 16, 16, 16)
        }

        // Expanded layout
        expandedLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 12)
        }

        // Status indicator
        val statusIndicator = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(16, 16).apply {
                marginEnd = 12
            }
            setBackgroundResource(R.drawable.status_dot_running)
        }

        // Status text
        statusText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "AI 助手运行中"
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // Stop button
        stopButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(36, 36).apply {
                marginEnd = 8
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_light))
            setOnClickListener { stopTask() }
            contentDescription = "停止"
            visibility = View.GONE  // Only visible when task is running
        }

        // Minimize button
        val minimizeBtn = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(36, 36)
            setImageResource(android.R.drawable.ic_menu_crop)  // Use a built-in icon
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
            setOnClickListener { minimize() }
            contentDescription = "最小化"
        }

        // Close button
        val closeBtn = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(36, 36).apply {
                marginStart = 8
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
            setOnClickListener { hide() }
        }

        headerLayout.addView(statusIndicator)
        headerLayout.addView(statusText!!)
        headerLayout.addView(stopButton!!)
        headerLayout.addView(minimizeBtn)
        headerLayout.addView(closeBtn)

        // Step info
        stepText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "步骤: 0/20"
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            textSize = 12f
            setPadding(0, 4, 0, 8)
        }

        // Current action
        actionText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "等待任务..."
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }

        // Thinking text
        thinkingText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_light))
            textSize = 12f
            setPadding(0, 4, 0, 8)
            visibility = View.GONE
        }

        // Log section
        val logLabel = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "执行日志"
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            textSize = 11f
            setPadding(0, 8, 0, 4)
        }

        logScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            )
            setBackgroundResource(R.drawable.log_bg)
            setPadding(8, 8, 8, 8)
        }

        logText = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            text = ""
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            textSize = 11f
        }
        logScrollView?.addView(logText)

        expandedLayout!!.addView(headerLayout)
        expandedLayout!!.addView(stepText)
        expandedLayout!!.addView(actionText)
        expandedLayout!!.addView(thinkingText)
        expandedLayout!!.addView(logLabel)
        expandedLayout!!.addView(logScrollView)

        // Minimized layout
        minimizedLayout = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.floating_window_minimized_bg)
            setPadding(16, 12, 16, 12)
            visibility = View.GONE

            setOnClickListener { expand() }
        }

        val minimizedText = TextView(context).apply {
            text = "AI"
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        minimizedLayout!!.addView(minimizedText)

        containerLayout?.addView(expandedLayout)
        containerLayout?.addView(minimizedLayout)

        // Make it draggable
        setupDrag()

        return containerLayout!!
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params?.let { p ->
                        p.x = initialX + (event.rawX - initialTouchX).toInt()
                        p.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, p)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun minimize() {
        isMinimized = true
        expandedLayout?.visibility = View.GONE
        minimizedLayout?.visibility = View.VISIBLE
        windowManager?.updateViewLayout(floatingView, minimizedParams)
    }

    private fun expand() {
        isMinimized = false
        minimizedLayout?.visibility = View.GONE
        expandedLayout?.visibility = View.VISIBLE
        windowManager?.updateViewLayout(floatingView, params)
    }

    fun hide() {
        floatingView?.visibility = View.GONE
    }

    fun show() {
        floatingView?.visibility = View.VISIBLE
        if (isMinimized) {
            expand()
        }
    }

    fun setAgentState(state: AgentState) {
        scope.launch(Dispatchers.Main) {
            updateUI(state)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI(state: AgentState) {
        // Update stop button visibility
        stopButton?.visibility = if (state.isRunning) View.VISIBLE else View.GONE

        // Update status
        statusText?.text = if (state.isRunning) {
            "AI 助手运行中"
        } else if (state.isFinished) {
            "任务完成"
        } else if (state.error != null) {
            "出错: ${state.error.take(20)}"
        } else {
            "AI 助手待命"
        }

        // Update step
        stepText?.text = "步骤: ${state.currentStep}/${state.maxSteps}"

        // Update action
        val lastStep = state.steps.lastOrNull()
        actionText?.text = when {
            state.isRunning && lastStep != null -> {
                val action = lastStep.action
                when (action) {
                    is AgentAction.CaptureScreen -> "正在截图..."
                    is AgentAction.Click -> "点击 (${action.x.toInt()}, ${action.y.toInt()})"
                    is AgentAction.LongClick -> "长按 (${action.x.toInt()}, ${action.y.toInt()})"
                    is AgentAction.DoubleClick -> "双击 (${action.x.toInt()}, ${action.y.toInt()})"
                    is AgentAction.Swipe -> "滑动 ${action.direction}"
                    is AgentAction.Drag -> "拖拽"
                    is AgentAction.Type -> "输入: ${action.text.take(15)}"
                    is AgentAction.OpenApp -> "打开应用"
                    is AgentAction.ListApps -> "获取应用列表"
                    is AgentAction.Reply -> "回复: ${action.message.take(20)}"
                    is AgentAction.Finish -> "完成: ${action.summary.take(20)}"
                    else -> action.toDescription()
                }
            }
            state.isFinished -> "已完成"
            state.error != null -> "错误: ${state.error}"
            else -> "等待任务..."
        }

        // Update thinking
        if (lastStep?.thinking != null) {
            thinkingText?.text = "💭 ${lastStep.thinking}"
            thinkingText?.visibility = View.VISIBLE
        } else {
            thinkingText?.visibility = View.GONE
        }

        // Update log
        if (lastStep != null && state.isRunning) {
            addLog(lastStep.action.toDescription() + if (lastStep.observation != null) " → ${lastStep.observation.take(30)}" else "")
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logBuffer.append("[$timestamp] $message\n")

        // Trim log if too long
        val lines = logBuffer.toString().split("\n")
        if (lines.size > maxLogLines) {
            logBuffer.clear()
            lines.takeLast(maxLogLines).forEach { if (it.isNotEmpty()) logBuffer.append("$it\n") }
        }

        logText?.text = logBuffer.toString()

        // Scroll to bottom
        logScrollView?.post {
            logScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    fun clearLog() {
        logBuffer.clear()
        logText?.text = ""
    }

    fun stopTask() {
        logger.d("Stop button clicked in floating window")
        onStopButtonClick?.invoke()
    }

    private fun removeFloatingWindow() {
        try {
            windowManager?.removeView(floatingView)
        } catch (e: Exception) {
            logger.e("Failed to remove floating window: ${e.message}")
        }
    }
}
