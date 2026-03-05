package com.example.myapplication.ui.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.agent.LangChainAgentEngine
import com.example.myapplication.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating window service to show agent execution status
 * Features:
 * - Draggable with smooth touch feedback
 * - Material Design styling
 * - Expandable/collapsible states
 * - Real-time status updates
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
    private var dragHandle: View? = null

    // Window params
    private var params: WindowManager.LayoutParams? = null
    private var minimizedParams: WindowManager.LayoutParams? = null

    // State tracking
    private var stateFlow: StateFlow<LangChainAgentEngine.AgentState>? = null
    private var stateJob: Job? = null

    // Log buffer
    private val logBuffer = StringBuilder()
    private val maxLogLines = 50

    // Dragging state
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

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
            (screenWidth * 0.9).toInt(),
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
            60,
            60,
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

        // Root container with Material Design background
        containerLayout = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.floating_window_bg)
            elevation = 8f
            outlineProvider = ViewOutlineProvider.BOUNDS
        }

        // Expanded layout
        expandedLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ===== Header Section =====
        val headerLayout = createHeaderSection(context)

        // ===== Drag Handle (for easy dragging) =====
        dragHandle = createDragHandle(context)

        // ===== Status Section =====
        val statusSection = createStatusSection(context)

        // ===== Action Section =====
        val actionSection = createActionSection(context)

        // ===== Thinking Section =====
        val thinkingSection = createThinkingSection(context)

        // ===== Log Section =====
        val logSection = createLogSection(context)

        // Add all sections to expanded layout
        expandedLayout?.addView(headerLayout)
        expandedLayout?.addView(dragHandle)
        expandedLayout?.addView(statusSection)
        expandedLayout?.addView(actionSection)
        expandedLayout?.addView(thinkingSection)
        expandedLayout?.addView(logSection)

        // Minimized layout (circular button)
        minimizedLayout = createMinimizedLayout(context)

        containerLayout?.addView(expandedLayout)
        containerLayout?.addView(minimizedLayout)

        // Make it draggable with improved touch handling
        setupDrag()

        return containerLayout!!
    }

    private fun createHeaderSection(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setPadding(0, 0, 0, 12)
            }
        }.apply {
            // Status indicator with glow effect
            val statusIndicator = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    resources.getDimensionPixelSize(android.R.dimen.app_icon_size),
                    resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
                ).apply {
                    marginEnd = 12
                }
                setBackgroundResource(R.drawable.status_dot_running)
                elevation = 2f
            }

            // Status text
            statusText = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "AI 助手运行中"
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            // Stop button with Material styling
            stopButton = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                    marginEnd = 4
                }
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundResource(R.drawable.btn_circle_material)
                setColorFilter(ContextCompat.getColor(context, android.R.color.white))
                backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.holo_red_dark)
                scaleType = ImageView.ScaleType.CENTER
                imageMatrix = android.graphics.Matrix().apply { preScale(0.8f, 0.8f) }
                setOnClickListener { stopTask() }
                contentDescription = "停止"
                visibility = View.GONE
                elevation = 4f
            }

            // Minimize button
            val minimizeBtn = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                    marginEnd = 4
                }
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundResource(R.drawable.btn_circle_material)
                setColorFilter(ContextCompat.getColor(context, android.R.color.white))
                backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.darker_gray)
                scaleType = ImageView.ScaleType.CENTER
                imageMatrix = android.graphics.Matrix().apply { preScale(0.6f, 0.6f) }
                rotation = 90f  // Rotate to look like minimize icon
                setOnClickListener { minimize() }
                contentDescription = "最小化"
                elevation = 4f
            }

            // Close button
            val closeBtn = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40)
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setBackgroundResource(R.drawable.btn_circle_material)
                setColorFilter(ContextCompat.getColor(context, android.R.color.white))
                backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.darker_gray)
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { hide() }
                contentDescription = "关闭"
                elevation = 4f
            }

            addView(statusIndicator)
            addView(statusText!!)
            addView(stopButton!!)
            addView(minimizeBtn)
            addView(closeBtn)
        }
    }

    private fun createDragHandle(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
            ).apply {
                setMargins(0, 0, 0, 8)
            }
            // Visual hint for draggability
            setBackgroundResource(R.drawable.drag_handle_bg)
            contentDescription = "拖动区域"
        }
    }

    private fun createStatusSection(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
        }.apply {
            // Step info with progress bar style
            stepText = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "步骤：0/20"
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }

            // Current action with emphasis
            actionText = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "等待任务..."
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                textSize = 14f
                setPadding(0, 8, 0, 8)
                setTypeface(null, android.graphics.Typeface.NORMAL)
            }

            addView(stepText!!)
            addView(actionText!!)
        }
    }

    private fun createActionSection(context: Context): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_light))
            textSize = 13f
            setPadding(0, 8, 0, 8)
            visibility = View.GONE
        }
    }

    private fun createThinkingSection(context: Context): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_light))
            textSize = 12f
            setPadding(0, 4, 0, 8)
            visibility = View.GONE
        }
    }

    private fun createLogSection(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 0)
            }
        }.apply {
            // Log label
            val logLabel = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "执行日志"
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                textSize = 11f
                setPadding(0, 4, 0, 4)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            // Scrollable log area
            logScrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    150
                )
                setBackgroundResource(R.drawable.log_bg)
                setPadding(12, 12, 12, 12)
                isVerticalScrollBarEnabled = true
            }

            logText = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                text = ""
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                textSize = 11f
                setLineSpacing(0f, 1.3f)
            }
            logScrollView?.addView(logText)

            addView(logLabel)
            addView(logScrollView!!)
        }
    }

    private fun createMinimizedLayout(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.floating_window_minimized_bg)
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            elevation = 8f

            setOnClickListener { expand() }
        }.apply {
            // AI icon/text in center
            val minimizedText = TextView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                text = "AI"
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(minimizedText)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        floatingView?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastTouchX = initialTouchX
                    lastTouchY = initialTouchY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - lastTouchX
                    val deltaY = event.rawY - lastTouchY

                    // Check if movement exceeds touch slop
                    if (!isDragging) {
                        val distanceX = kotlin.math.abs(event.rawX - initialTouchX)
                        val distanceY = kotlin.math.abs(event.rawY - initialTouchY)
                        isDragging = distanceX > touchSlop || distanceY > touchSlop
                    }

                    if (isDragging) {
                        params?.let { p ->
                            p.x = initialX + (event.rawX - initialTouchX).toInt()
                            p.y = initialY + (event.rawY - initialTouchY).toInt()

                            // Boundary check - keep window within screen
                            val displayMetrics = resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val screenHeight = displayMetrics.heightPixels

                            p.x = p.x.coerceIn(-screenWidth / 2, screenWidth / 2)
                            p.y = p.y.coerceIn(0, screenHeight - 200)

                            try {
                                windowManager?.updateViewLayout(floatingView, p)
                            } catch (e: Exception) {
                                logger.e("Failed to update view position: ${e.message}")
                            }
                        }
                    }
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    false
                }
                else -> false
            }
        }
    }

    private fun minimize() {
        isMinimized = true
        isExpanded = false
        expandedLayout?.visibility = View.GONE
        minimizedLayout?.visibility = View.VISIBLE

        // Animate transition
        minimizedLayout?.alpha = 0f
        minimizedLayout?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.start()

        try {
            windowManager?.updateViewLayout(floatingView, minimizedParams)
        } catch (e: Exception) {
            logger.e("Failed to minimize: ${e.message}")
        }
    }

    private fun expand() {
        isMinimized = false
        isExpanded = true
        minimizedLayout?.visibility = View.GONE
        expandedLayout?.visibility = View.VISIBLE

        // Animate transition
        expandedLayout?.alpha = 0f
        expandedLayout?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.start()

        try {
            windowManager?.updateViewLayout(floatingView, params)
        } catch (e: Exception) {
            logger.e("Failed to expand: ${e.message}")
        }
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

    fun setLangChainAgentState(state: LangChainAgentEngine.AgentState) {
        scope.launch(Dispatchers.Main) {
            updateLangChainUI(state)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateLangChainUI(state: LangChainAgentEngine.AgentState) {
        val isRunning = state.state == LangChainAgentEngine.AgentStateType.RUNNING
        val isFinished = state.state == LangChainAgentEngine.AgentStateType.COMPLETED
        val hasError = state.state == LangChainAgentEngine.AgentStateType.ERROR || state.error != null
        
        // Update stop button visibility
        stopButton?.visibility = if (isRunning) View.VISIBLE else View.GONE

        // Update status dot color based on state
        val headerLayout = expandedLayout?.getChildAt(0) as? LinearLayout
        val statusIndicator = headerLayout?.getChildAt(0)
        statusIndicator?.setBackgroundResource(
            when {
                isRunning -> R.drawable.status_dot_running
                isFinished -> R.drawable.status_dot_stopped
                hasError -> R.drawable.status_dot_stopped
                else -> R.drawable.status_dot_idle
            }
        )

        // Update status text
        statusText?.text = when {
            isRunning -> "AI 助手运行中"
            isFinished -> "任务完成"
            state.error != null -> "出错：${state.error.take(20)}"
            else -> "AI 助手待命"
        }

        // Update step (not applicable for LangChain, set to 0/0)
        stepText?.text = "步骤：0/0"

        // Update action
        actionText?.text = when (state.state) {
            LangChainAgentEngine.AgentStateType.RUNNING -> "正在处理..."
            LangChainAgentEngine.AgentStateType.COMPLETED -> state.result ?: "完成"
            LangChainAgentEngine.AgentStateType.ERROR -> state.error ?: "错误"
            LangChainAgentEngine.AgentStateType.CANCELLED -> "已取消"
            else -> "等待中..."
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
