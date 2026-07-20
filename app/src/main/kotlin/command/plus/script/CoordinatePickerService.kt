package command.plus

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Binder
import android.os.IBinder
import android.view.*
import android.widget.TextView
import kotlin.math.abs

class CoordinatePickerService : Service() {

    private lateinit var windowManager: WindowManager

    private lateinit var ballView: View
    private lateinit var pointerView: View
    private lateinit var deleteZoneView: View
    private lateinit var touchInterceptorView: View
    private lateinit var coordinateView: TextView

    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var pointerParams: WindowManager.LayoutParams
    private lateinit var deleteZoneParams: WindowManager.LayoutParams
    private lateinit var coordinateParams: WindowManager.LayoutParams
    private lateinit var interceptorParams: WindowManager.LayoutParams

    private var isExpanded = false
    private var screenHeight = 0
    private var screenWidth = 0

    private val binder = LocalBinder()
    var onCoordinateSelected: ((Float, Float) -> Unit)? = null
    var onServiceDestroyRequest: (() -> Unit)? = null

    private val deleteZoneHeightPx = 240
    private lateinit var deleteZoneContainer: View

    inner class LocalBinder : Binder() {
        fun getService(): CoordinatePickerService = this@CoordinatePickerService
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        initViews()
    }

    private fun initViews() {
        val inflater = LayoutInflater.from(this)

        ballView = inflater.inflate(R.layout.layout_floating_ball, null)
        ballParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        pointerView = inflater.inflate(R.layout.layout_pointer, null)
        pointerView.visibility = View.GONE
        pointerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2
            y = screenHeight / 2
        }

        deleteZoneView = inflater.inflate(R.layout.layout_delete_zone, null)
        deleteZoneContainer = deleteZoneView.findViewById(R.id.delete_zone_container)
        deleteZoneParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            deleteZoneHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        touchInterceptorView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        interceptorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        coordinateView = inflater.inflate(R.layout.layout_coordinates, null) as TextView
        coordinateView.visibility = View.GONE
        coordinateParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 20
        }

        // 先加 pointer / ball / coordinate
        windowManager.addView(pointerView, pointerParams)
        windowManager.addView(ballView, ballParams)
        windowManager.addView(coordinateView, coordinateParams)

        setupBallTouchInteractions()
    }

    private fun setupBallTouchInteractions() {
        val collapsedLayout = ballView.findViewById<View>(R.id.layout_collapsed)
        val expandedLayout = ballView.findViewById<View>(R.id.layout_expanded)
        val btnCancel = ballView.findViewById<View>(R.id.btn_cancel)
        val btnConfirm = ballView.findViewById<View>(R.id.btn_confirm)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false

        collapsedLayout.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = ballParams.x
                    initialY = ballParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false

                    try {
                        windowManager.addView(deleteZoneView, deleteZoneParams)
                    } catch (_: Exception) {}
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 10 || abs(dy) > 10) isMoving = true

                    ballParams.x = initialX + dx
                    ballParams.y = initialY + dy
                    windowManager.updateViewLayout(ballView, ballParams)

                    if (isBallInDeleteZone()) {
                        deleteZoneContainer.setBackgroundColor(Color.parseColor("#FF1744"))
                    } else {
                        deleteZoneContainer.setBackgroundResource(R.drawable.bg_delete_zone)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    try {
                        windowManager.removeViewImmediate(deleteZoneView)
                    } catch (_: Exception) {}

                    if (isBallInDeleteZone()) {
                        cleanupAndStop()
                        return@setOnTouchListener true
                    }

                    if (!isMoving) {
                        isExpanded = true
                        collapsedLayout.visibility = View.GONE
                        expandedLayout.visibility = View.VISIBLE
                        enablePointerPicker()
                        setupExpandedDragInteractions(expandedLayout)
                    }
                    true
                }

                else -> false
            }
        }

        btnCancel.setOnClickListener {
            isExpanded = false
            expandedLayout.visibility = View.GONE
            collapsedLayout.visibility = View.VISIBLE
            disablePointerPicker()
        }

        btnConfirm.setOnClickListener {
            onCoordinateSelected?.invoke(
                pointerParams.x.toFloat() + (pointerView.width / 2f),
                pointerParams.y.toFloat() + (pointerView.height / 2f)
            )
            cleanupAndStop()
        }
    }

    private fun setupExpandedDragInteractions(expandedLayout: View) {
    val btnConfirm = ballView.findViewById<View>(R.id.btn_confirm)
    val btnCancel = ballView.findViewById<View>(R.id.btn_cancel)

    val touchSlop = 12
    var downX = 0f
    var downY = 0f
    var initialBallX = 0
    var initialBallY = 0
    var dragging = false
    var downOnConfirm = false
    var downOnCancel = false

    val confirmRect = android.graphics.Rect()
    val cancelRect = android.graphics.Rect()

    fun refreshButtonRects() {
        if (btnConfirm.width > 0 && btnConfirm.height > 0) {
            btnConfirm.getGlobalVisibleRect(confirmRect)
        }
        if (btnCancel.width > 0 && btnCancel.height > 0) {
            btnCancel.getGlobalVisibleRect(cancelRect)
        }
    }

    fun isOnConfirm(rawX: Float, rawY: Float): Boolean {
        return confirmRect.contains(rawX.toInt(), rawY.toInt())
    }

    fun isOnCancel(rawX: Float, rawY: Float): Boolean {
        return cancelRect.contains(rawX.toInt(), rawY.toInt())
    }

    fun startDrag(event: MotionEvent) {
        downX = event.rawX
        downY = event.rawY
        initialBallX = ballParams.x
        initialBallY = ballParams.y
        dragging = true
    }

    fun moveMenu(event: MotionEvent) {
        val dx = (event.rawX - downX).toInt()
        val dy = (event.rawY - downY).toInt()

        ballParams.x = initialBallX + dx
        ballParams.y = initialBallY + dy
        try {
            windowManager.updateViewLayout(ballView, ballParams)
        } catch (_: Exception) {}
    }

    fun handleTouch(target: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshButtonRects()
                downOnConfirm = isOnConfirm(event.rawX, event.rawY)
                downOnCancel = isOnCancel(event.rawX, event.rawY)
                startDrag(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val moveDx = abs(event.rawX - downX)
                val moveDy = abs(event.rawY - downY)

                if (!dragging && (moveDx > touchSlop || moveDy > touchSlop)) {
                    dragging = true
                }

                if (dragging) {
                    moveMenu(event)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val moveDist = abs(event.rawX - downX) + abs(event.rawY - downY)

                if (moveDist < touchSlop) {
                    // 视为点击
                    when {
                        downOnConfirm -> btnConfirm.performClick()
                        downOnCancel -> btnCancel.performClick()
                        else -> target.performClick()
                    }
                }

                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return false
    }

    // 菜单背景可拖拽
    expandedLayout.setOnTouchListener { v, event ->
        handleTouch(v, event)
    }

    // 两个按钮也可拖拽，同时保留点击
    btnConfirm.setOnTouchListener { v, event ->
        handleTouch(v, event)
    }

    btnCancel.setOnTouchListener { v, event ->
        handleTouch(v, event)
    }
}
    private fun isBallInDeleteZone(): Boolean {
        val ballH = when {
            ballView.height > 0 -> ballView.height
            ballView.measuredHeight > 0 -> ballView.measuredHeight
            else -> 0
        }
        val ballCenterY = ballParams.y + ballH / 2
        val deleteZoneTop = screenHeight - deleteZoneHeightPx
        return ballCenterY >= deleteZoneTop
    }

    private fun enablePointerPicker() {
        coordinateView.visibility = View.VISIBLE
        pointerView.visibility = View.VISIBLE

        // 关键：把拦截层放到菜单窗下面
        // 这样菜单按钮可以正常点击，而空白区域仍然能记录坐标
        try {
            windowManager.removeViewImmediate(touchInterceptorView)
        } catch (_: Exception) {}

        try {
            windowManager.removeViewImmediate(ballView)
        } catch (_: Exception) {}

        try {
            windowManager.addView(touchInterceptorView, interceptorParams)
        } catch (_: Exception) {}

        // 重新把菜单窗加到拦截层上面
        try {
            windowManager.addView(ballView, ballParams)
        } catch (_: Exception) {}

        val btnConfirm = ballView.findViewById<View>(R.id.btn_confirm)
        val btnCancel = ballView.findViewById<View>(R.id.btn_cancel)

        val confirmRect = android.graphics.Rect()
        val cancelRect = android.graphics.Rect()

        fun refreshButtonRects() {
            if (btnConfirm.width > 0 && btnConfirm.height > 0) {
                btnConfirm.getGlobalVisibleRect(confirmRect)
            }
            if (btnCancel.width > 0 && btnCancel.height > 0) {
                btnCancel.getGlobalVisibleRect(cancelRect)
            }
        }

        var downX = 0f
        var downY = 0f
        var downOnConfirm = false
        var downOnCancel = false

        fun updatePointer(event: MotionEvent) {
            pointerParams.x = event.rawX.toInt() - (pointerView.width / 2)
            pointerParams.y = event.rawY.toInt() - (pointerView.height / 2)
            try {
                windowManager.updateViewLayout(pointerView, pointerParams)
            } catch (_: Exception) {}

            coordinateView.text = "X: ${event.rawX.toInt()} Y: ${event.rawY.toInt()}"
        }

        touchInterceptorView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    refreshButtonRects()
                    downX = event.rawX
                    downY = event.rawY
                    downOnConfirm = confirmRect.contains(event.rawX.toInt(), event.rawY.toInt())
                    downOnCancel = cancelRect.contains(event.rawX.toInt(), event.rawY.toInt())

                    // 只有点在按钮外面，才记录指针
                    if (!downOnConfirm && !downOnCancel) {
                        updatePointer(event)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 无论滑动到哪里，只要起点不在按钮上，就继续更新
                    if (!downOnConfirm && !downOnCancel) {
                        updatePointer(event)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!downOnConfirm && !downOnCancel) {
                        updatePointer(event)
                    }

                    val moveDist = abs(event.rawX - downX) + abs(event.rawY - downY)
                    val isClick = moveDist < 20f

                    if (isClick && downOnConfirm) {
                        btnConfirm.performClick()
                    } else if (isClick && downOnCancel) {
                        btnCancel.performClick()
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun disablePointerPicker() {
        pointerView.visibility = View.GONE
        coordinateView.visibility = View.GONE

        try {
            windowManager.removeViewImmediate(touchInterceptorView)
        } catch (_: Exception) {}

        try {
            windowManager.removeViewImmediate(ballView)
        } catch (_: Exception) {}

        try {
            windowManager.addView(ballView, ballParams)
        } catch (_: Exception) {}
    }

    private var isCleaningUp = false

    private fun cleanupAndStop() {
        if (isCleaningUp) return
        isCleaningUp = true

        disablePointerPicker()
        onServiceDestroyRequest?.invoke()

        try { windowManager.removeViewImmediate(ballView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(pointerView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(touchInterceptorView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(coordinateView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(deleteZoneView) } catch (_: Exception) {}

        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { windowManager.removeViewImmediate(ballView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(pointerView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(touchInterceptorView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(coordinateView) } catch (_: Exception) {}
        try { windowManager.removeViewImmediate(deleteZoneView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent): IBinder = binder
}