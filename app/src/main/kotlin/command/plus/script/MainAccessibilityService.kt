package command.plus

import android.animation.ValueAnimator
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.WindowMetrics
import android.view.WindowManager

class MainAccessibilityService : AccessibilityService(), 
    ScriptEngine.ActionExecutor, ScriptEngine.TextSetter {

    companion object {
        private const val TAG = "MainAccessibilityService"
        private var instance: MainAccessibilityService? = null

        @JvmStatic
        fun getInstance(): MainAccessibilityService? = instance

        // 将多行文本拆成按行的 List<String>
        fun splitScriptTextToLines(scriptText: String?): List<String> {
            if (scriptText == null) return emptyList()
            var text = scriptText
            if (text.startsWith("\uFEFF")) text = text.substring(1)
            val rawLines = text.split(Regex("\\r?\\n|\\r")).toTypedArray()
            return listOf(*rawLines)
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tvInfo: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val CHECK_INTERVAL_MS = 1000L
    private var checkRunnable: Runnable? = null

    fun interface WindowStateListener {
        fun onWindowStateChanged(isShown: Boolean)
    }

    private var listener: WindowStateListener? = null
    private var windowShown = false

    fun setWindowStateListener(l: WindowStateListener?) {
        this.listener = l
    }

    private fun notifyState() {
        listener?.onWindowStateChanged(windowShown)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingWindow(filename: String) {
        // 直接访问 DataManager 单例
        handler.post {
            if (windowShown) return@post
            windowShown = true

            notifyState()
            FloatToast.show(applicationContext, "悬浮助手已开启")

            try {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

                layoutParams = WindowManager.LayoutParams().apply {
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    format = PixelFormat.TRANSLUCENT
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    gravity = Gravity.TOP or Gravity.START
                    x = 100
                    y = 200
                    width = 500
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                }

                val inflater = LayoutInflater.from(applicationContext)
                floatingView = inflater.inflate(R.layout.layout_floating_window, null)

                floatingView?.let { view ->
                    tvInfo = view.findViewById(R.id.tv_info)
                    val btnBack = view.findViewById<Button>(R.id.btn_back)
                    val btnNext = view.findViewById<Button>(R.id.btn_next)
                    val resizeHandle = view.findViewById<View>(R.id.view_resize_handle)

                    updateInfoDisplay()

                    btnBack.setOnClickListener {
                        DataManager.back() // 适配单例
                        updateInfoDisplay()
                    }
                    btnBack.setOnLongClickListener {
                        showJumpDialog()
                        true
                    }

                    btnNext.setOnClickListener {
                        layoutParams?.apply {
                            alpha = 0.4f
                            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            windowManager?.updateViewLayout(view, this)
                        }
                        handler.postDelayed({ handleInputLogic(filename) }, 100)
                    }

                    tvInfo?.setOnLongClickListener {
                        showMenuDialog()
                        true
                    }

                    // 移动逻辑
                    view.setOnTouchListener(object : View.OnTouchListener {
                        private var initialX = 0
                        private var initialY = 0
                        private var initialTouchX = 0f
                        private var initialTouchY = 0f

                        override fun onTouch(v: View, event: MotionEvent): Boolean {
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    initialX = layoutParams?.x ?: 0
                                    initialY = layoutParams?.y ?: 0
                                    initialTouchX = event.rawX
                                    initialTouchY = event.rawY
                                    return true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                                    layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                                    try {
                                        windowManager?.updateViewLayout(floatingView, layoutParams)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    return true
                                }
                            }
                            return false
                        }
                    })

                    // 缩放逻辑
                    resizeHandle.setOnTouchListener(object : View.OnTouchListener {
                        private var initialWidth = 0
                        private var initialHeight = 0
                        private var initialTouchX = 0f
                        private var initialTouchY = 0f

                        override fun onTouch(v: View, event: MotionEvent): Boolean {
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    initialWidth = view.width
                                    initialHeight = view.height
                                    initialTouchX = event.rawX
                                    initialTouchY = event.rawY
                                    return true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val newWidth = initialWidth + (event.rawX - initialTouchX).toInt()
                                    val newHeight = initialHeight + (event.rawY - initialTouchY).toInt()
                                    layoutParams?.width = newWidth.coerceAtLeast(250)
                                    layoutParams?.height = newHeight.coerceAtLeast(200)
                                    try {
                                        windowManager?.updateViewLayout(floatingView, layoutParams)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    return true
                                }
                            }
                            return false
                        }
                    })

                    windowManager?.addView(view, layoutParams)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                FloatToast.show(applicationContext, "悬浮窗启动失败: ${e.message}")
            }
            startAutoCheck()
        }
    }

    fun hideFloatingWindow() {
        if (!windowShown) return
        handler.post {
            if (windowShown && windowManager != null && floatingView != null) {
                try {
                    windowManager?.removeView(floatingView)
                    windowShown = false
                    notifyState()
                    FloatToast.show(applicationContext, "悬浮窗已隐藏")
                } catch (e: Exception) {
                    e.printStackTrace()
                    FloatToast.show(applicationContext, "关闭失败: ${e.message}")
                }
            }
        }
    }

    private fun showJumpDialog() {
        try {
            val ctx = ContextThemeWrapper(applicationContext, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
            val builder = AlertDialog.Builder(ctx)
            builder.setTitle("跳转位置")

            val input = EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "输入数字索引"
            }
            builder.setView(input)

            builder.setPositiveButton("确定") { _, _ ->
                try {
                    val target = input.text.toString().toInt()
                    DataManager.jumpTo(target) // 适配单例
                    updateInfoDisplay()
                } catch (e: Exception) {
                    FloatToast.show(applicationContext, "输入错误")
                }
            }
            builder.setNegativeButton("取消", null)

            builder.create().apply {
                window?.setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                    else 
                        WindowManager.LayoutParams.TYPE_PHONE
                )
                show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            FloatToast.show(applicationContext, "弹窗失败: ${e.message}")
        }
    }

    private fun showMenuDialog() {
        try {
            val ctx = ContextThemeWrapper(applicationContext, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
            val options = arrayOf("隐藏悬浮窗", "关闭服务", "选择文件", "取消")
            val builder = AlertDialog.Builder(ctx)
            builder.setItems(options) { dialog, which ->
                when (which) {
                    0 -> hideFloatingWindow()
                    1 -> {
                        hideFloatingWindow()
                        disableSelf()
                    }
                    2 -> showChangeSetDialog()
                    3 -> dialog.dismiss()
                }
            }

            builder.create().apply {
                window?.setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                    else 
                        WindowManager.LayoutParams.TYPE_PHONE
                )
                show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showChangeSetDialog() {
        try {
            val ctx = ContextThemeWrapper(applicationContext, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
            val builder = AlertDialog.Builder(ctx)
            builder.setTitle("加载指令文件")

            val input = EditText(ctx).apply {
                hint = "/sdcard/Download/data.txt"
            }
            builder.setView(input)

            builder.setPositiveButton("确定") { _, _ ->
                val path = input.text.toString()
                try {
                    DataManager.loadFromFile(path) // 适配单例
                    updateInfoDisplay()
                    FloatToast.show(applicationContext, "集合已加载: ${DataManager.getTotalSize()}")
                } catch (e: Exception) {
                    FloatToast.show(applicationContext, "加载失败: ${e.message}")
                }
            }
            builder.setNegativeButton("取消", null)

            builder.create().apply {
                window?.setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                    else 
                        WindowManager.LayoutParams.TYPE_PHONE
                )
                show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -------------------- ActionExecutor --------------------
    override fun exeAction(x: Int, y: Int, x1: Int, y1: Int, timeMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture requires API 24+. exeAction skipped.")
            try { Thread.sleep(1L.coerceAtLeast(timeMs)) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            return
        }

        val latch = CountDownLatch(1)
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x1.toFloat(), y1.toFloat())
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 1L.coerceAtLeast(timeMs))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    latch.countDown()
                }
            }, null)

            if (!dispatched) {
                Log.w(TAG, "dispatchGesture returned false")
                try { Thread.sleep(timeMs.coerceAtMost(200)) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            } else {
                val waitTimeout = timeMs + 2000
                try {
                    latch.await(waitTimeout, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "dispatchGesture exception", e)
        }
    }

    // -------------------- TextSetter --------------------
    override fun setText(text: String): Boolean {
        var safeText = text ?: ""
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            Log.w(TAG, "No editable node found to set text")
            return false
        }
        // 模拟输入每个字符（包括 \n）
        
        var ok: Boolean
        try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, safeText)
            }
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "ACTION_SET_TEXT -> $ok")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SET_TEXT failed", e)
            ok = false
        }

        if (!ok) {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.let {
                    val clip = ClipData.newPlainText("script_input", safeText)
                    it.setPrimaryClip(clip)
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    Log.d(TAG, "ACTION_PASTE -> $ok")
                }
            } catch (e: Exception) {
                Log.w(TAG, "clipboard paste fallback failed", e)
            }
        }

        node.recycle()
        return ok
    }

    private fun handleInputLogic(filename: String) {
        
        val engine = ScriptEngine().apply {
    variableProvider = object : ScriptEngine.VariableProvider {
        override fun getVariable(name: String): Any? {
            
            
            
            val currentItem = DataManager.getCurrentItem() ?: run {
                FloatToast.show(applicationContext, "流程完成")
                finishAll()
                return null
            }
            

            return when (name) {
                "isBlockType" -> currentItem.block?.type == DataManager.Block.TYPE_TASK
                "ItemText" -> currentItem.getText()
                "isNextLoop" -> currentItem.block?.isLoopTask()
                "configA" -> currentItem.block?.configA
                "configB" -> currentItem.block?.configB
                "configC" -> currentItem.block?.configC
                "cmdResult" -> ShizukuManager.getLastResult() ?: "默认"
                "Weight" -> getRealScreenResolution(applicationContext).first
                "Height" -> getRealScreenResolution(applicationContext).second
                
                else -> null
            }
            
        }
    }
}

        val listener = object : ScriptListener {
            override fun onScriptEnd(scriptId: String, finalVars: Map<String, Any?>) {
                //FloatToast.show(applicationContext, "脚本结束: $scriptId")
                // 发送回车键事件
// 需要 Android 7.0+ 的 dispatchGesture API
                finishAll()
            }
            override fun onInputFieldNotFound(scriptId: String, attemptedText: String) {
                FloatToast.show(applicationContext, "未找到输入框: $attemptedText")
                ScriptEngine.stopAllScripts()
                finishAll()
            }
            override fun onScriptStopped(scriptId: String) {
                //FloatToast.show(applicationContext, "脚本停止: $scriptId")
                finishAll()
            }
            override fun onScriptError(scriptId: String, e: Exception) {
                FloatToast.show(applicationContext, "错误: $e")
                ScriptEngine.stopAllScripts()
                finishAll()
            }
            override fun onVariableError(scriptId: String, varName: String) {
                FloatToast.show(applicationContext, "变量无值: $varName")
                ScriptEngine.stopAllScripts()
                finishAll()
            }
            override fun onNextItem() {
                DataManager.next() // 适配单例
                updateInfoDisplay()
            }
            override fun onFloatingToast(text: String) {
                FloatToast.show(applicationContext, text)
            }
        }

        val scriptLines = splitScriptTextToLines(readTxt(filename))
        engine.runScriptAsync("myscript1", scriptLines, HashMap(), this, this, listener)
    }

    private fun updateInfoDisplay() {
        tvInfo?.post {
            try {
                val item = DataManager.getCurrentItem() // 适配单例
                if (item == null) {
                    tvInfo?.text = "状态: 已完成或未加载\n总数: ${DataManager.getTotalSize()}"
                } else {
                    val content = item.getText()
                    val safeContent = content ?: "无内容"
                    tvInfo?.text = String.format(
                        "当前索引: %d/%d\n下一条类型: %s\n下一条长度: %d\n下一条内容: %s",
                        DataManager.getCurrentIndex(),
                        DataManager.getTotalSize(),
                        if (item.block != null && item.block.type == DataManager.Block.TYPE_TASK) "输入聊天栏" else "输入命令方块",
                        safeContent.length,
                        safeContent
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                tvInfo?.text = "显示异常: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        hideFloatingWindow()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {
        hideFloatingWindow()
    }

    fun isWindowShown(): Boolean = windowShown

    private fun finishAll() {
        handler.post {
            layoutParams?.apply {
                alpha = 1.0f
                flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windowManager?.updateViewLayout(floatingView, this)
            }
        }
    }

    private fun startAutoCheck() {
        checkRunnable = object : Runnable {
            override fun run() {
                checkAndBringBackIfNeeded(true)
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        handler.postDelayed(checkRunnable!!, CHECK_INTERVAL_MS)
    }

    private fun checkAndBringBackIfNeeded(animate: Boolean) {
        val vw = floatingView?.width ?: 0
        val vh = floatingView?.height ?: 0
        if (vw == 0 || vh == 0) {
            floatingView?.post { checkAndBringBackIfNeeded(animate) }
            return
        }

        val real = getRealScreenSize()
        val screenW = real.x
        val screenH = real.y

        val left = layoutParams?.x ?: 0
        val top = layoutParams?.y ?: 0
        val right = left + vw
        val bottom = top + vh

        val completelyOutside = (right < 0 || left > screenW || bottom < 0 || top > screenH)
        val visibleWidth = 0.coerceAtLeast(right.coerceAtMost(screenW) - left.coerceAtLeast(0))
        val visibleHeight = 0.coerceAtLeast(bottom.coerceAtMost(screenH) - top.coerceAtLeast(0))
        val visibleAreaRatio = (visibleWidth * visibleHeight).toFloat() / (vw * vh)

        if (completelyOutside || visibleAreaRatio == 0f) {
            var targetX = (screenW - vw) / 2
            var targetY = (screenH - vh) / 2

            if (completelyOutside) {
                if (right < 0) targetX = 10
                else if (left > screenW) targetX = screenW - vw - 10
                if (bottom < 0) targetY = 10
                else if (top > screenH) targetY = screenH - vh - 10
            }

            if (animate) animateMoveTo(targetX, targetY)
            else {
                layoutParams?.x = targetX
                layoutParams?.y = targetY
                windowManager?.updateViewLayout(floatingView, layoutParams)
            }
        }
    }

    private fun animateMoveTo(targetX: Int, targetY: Int) {
        val startX = layoutParams?.x ?: 0
        val startY = layoutParams?.y ?: 0
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { valueAnimator ->
                val t = valueAnimator.animatedValue as Float
                layoutParams?.x = (startX + (targetX - startX) * t).toInt()
                layoutParams?.y = (startY + (targetY - startY) * t).toInt()
                try {
                    windowManager?.updateViewLayout(floatingView, layoutParams)
                } catch (ignored: IllegalArgumentException) {}
            }
            start()
        }
    }

    private fun getRealScreenSize(): Point {
        val p = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = resources.displayMetrics
            p.x = dm.widthPixels
            p.y = dm.heightPixels
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(p)
        }
        return p
    }

    fun readTxt(filename: String): String {
        val content = StringBuilder()
        val file = File(filename)

        try {
            BufferedReader(FileReader(file)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    content.append(line).append("\n")
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return content.toString()
    }
}

fun getRealScreenResolution(context: Context): Pair<Int, Int> {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // API 30 (Android 11) 及以上推荐做法
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        Pair(bounds.width(), bounds.height())
    } else {
        // API 30 以下的传统做法
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }
}