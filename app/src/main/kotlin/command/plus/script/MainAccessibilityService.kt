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
    val ctx = ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)  
    val builder = AlertDialog.Builder(ctx)  
    builder.setTitle("加载指令文件")  

    val input = EditText(ctx).apply {  
        hint = "/sdcard/Download/data.txt"  
    }  
    builder.setView(input)  

    builder.setPositiveButton("确定") { _, _ ->  
        val path = input.text.toString()  
        FloatToast.show(applicationContext, "正在后台加载，请稍候...")  

        // 1. 开启后台线程执行耗时的文件读取和解析工作  
        Thread {  
            try {  
                DataManager.loadFromFile(path)  

                // 2. 解析成功后，回到主线程更新 UI  
                handler.post {  
                    updateInfoDisplay()  
                    FloatToast.show(applicationContext, "集合已加载: ${DataManager.getTotalSize()}")  
                }  
            } catch (e: Exception) {  
                e.printStackTrace()  
                // 3. 失败时，回到主线程进行弹窗提示  
                handler.post {  
                    FloatToast.show(applicationContext, "加载失败: ${e.message}")  
                }  
            }  
        }.start() // 启动线程  
    }  
    builder.setNegativeButton("取消", null)  

    val dialog = builder.create()  
    dialog.window?.let { win ->  
        win.setType(  
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)   
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY   
            else   
                WindowManager.LayoutParams.TYPE_PHONE  
        )  
    }  
    dialog.show()  
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
    // 依然保留非空兜底（防止 Java 层传入 null 触发运行时崩溃）
    val safeText = text ?: ""    
    
    // 如果文本为空，直接清空即可
    if (safeText.isEmpty()) {
        return performSetTextDirectly("")
    }

    val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (node == null) {    
        Log.w(TAG, "No editable node found to set text")    
        return false    
    }    
    
    // 1. 临时强制隐藏软键盘 (API 24+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            softKeyboardController.showMode = AccessibilityService.SHOW_MODE_HIDDEN
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set keyboard show mode to HIDDEN", e)
        }
    }
        
    var ok = false    
    try {    
        // 2. 直接一次性写入完整文本
        ok = performSetText(node, safeText)
    } catch (e: Exception) {    
        Log.w(TAG, "ACTION_SET_TEXT failed", e)    
        ok = false
    } finally {
        node.recycle()    
    }
  
    // 3. 输入完毕后，恢复系统默认的键盘弹出机制
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        try {
            softKeyboardController.showMode = AccessibilityService.SHOW_MODE_AUTO
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore keyboard show mode to AUTO", e)
        }
    }

    return ok    
}

/**
 * 辅助方法：直接调用 ACTION_SET_TEXT
 */
private fun performSetText(node: AccessibilityNodeInfo, targetText: String): Boolean {
    val args = Bundle().apply {    
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetText)    
    }    
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}

/**
 * 辅助方法：快速无节点调用（用于清除或空白输入）
 */
private fun performSetTextDirectly(targetText: String): Boolean {
    val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
    val ok = performSetText(node, targetText)
    node.recycle()
    return ok
}

private fun handleInputLogic(filename: String) {  
    // 完全在后台线程执行读取、解析以及脚本的运行启动
    Thread {  
        try {  
            val rawText = readTxt(filename)  
            val scriptLines = splitScriptTextToLines(rawText)  

            // 构建变量提供器
            val varMap = HashMap<String, Any>()
            
            val engine = ScriptEngine().apply {  
                variableProvider = object : ScriptEngine.VariableProvider {  
                    override fun getVariable(name: String): Any? {  
                        when (name) {  
            "Weight" -> return getRealScreenResolution(applicationContext).first  
            "Height" -> return getRealScreenResolution(applicationContext).second  
            "cmdResult" -> return ShizukuManager.getLastResult() ?: "默认"  
        }

        // 2. 只有获取需要依赖数据的变量时，才进行非空校验
        val currentItem = DataManager.getCurrentItem() ?: run {  
            handler.post {
                finishAll()  
            }
            return ""
        }  

        // 3. 返回与数据相关的变量
        return when (name) {  
            "isBlockType" -> currentItem.block?.type == DataManager.Block.TYPE_TASK  
            "ItemText" -> currentItem.getText()  
            "isNextLoop" -> currentItem.block?.isLoopTask()  
            "configA" -> currentItem.block?.configA  
            "configB" -> currentItem.block?.configB  
            "configC" -> currentItem.block?.configC  
            else -> ""
        }
                    }  
                }  
            }  

            val listener = object : ScriptListener {  
                override fun onScriptEnd(scriptId: String, finalVars: Map<String, Any?>) {  
                    handler.post { finishAll() }  
                }  
                override fun onInputFieldNotFound(scriptId: String, attemptedText: String) {  
                    handler.post {
                        FloatToast.show(applicationContext, "未找到输入框: ${attemptedText.take(100)}")  
                        ScriptEngine.stopAllScripts()  
                        finishAll()  
                    }
                }  
                override fun onScriptStopped(scriptId: String) {  
                    handler.post { finishAll() }  
                }  
                override fun onScriptError(scriptId: String, e: Exception) {  
                    handler.post {
                        FloatToast.show(applicationContext, "错误: ${e.message}")  
                        ScriptEngine.stopAllScripts()  
                        finishAll()  
                    }
                }  
                override fun onVariableError(scriptId: String, varName: String) {  
                    handler.post {
                        FloatToast.show(applicationContext, "变量无值: $varName")  
                        ScriptEngine.stopAllScripts()  
                        finishAll()  
                    }
                }  
                override fun onNextItem() {  
                    // 数据递增和 UI 更新回到主线程
                    handler.post {
                        DataManager.next()  
                        updateInfoDisplay()  
                    }
                }  
                override fun onFloatingToast(text: String) {  
                    handler.post { FloatToast.show(applicationContext, text) }  
                }  
            }  

            // 启动脚本运行（确保此处的异步执行不会强占主线程）
            engine.runScriptAsync("myscript1", scriptLines, varMap, this@MainAccessibilityService, this@MainAccessibilityService, listener)  

        } catch (e: Exception) {  
            e.printStackTrace()  
            handler.post {  
                FloatToast.show(applicationContext, "读取脚本失败: ${e.message}")  
                finishAll()  
            }  
        }  
    }.start()  
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
                
                // 【核心优化】：如果文本过长，只截取前 1600 个字符用于 UI 展示，防止 TextView 绘制卡死
                val previewContent = if (safeContent.length > 1600) {
                    safeContent.substring(0, 1600) + "..."
                } else {
                    safeContent
                }

                tvInfo?.text = String.format(  
                    "当前索引: %d/%d\n下一条类型: %s\n下一条长度: %d\n下一条内容: %s",  
                    DataManager.getCurrentIndex(),  
                    DataManager.getTotalSize(),  
                    if (item.block != null && item.block.type == DataManager.Block.TYPE_TASK) "输入聊天栏" else "输入命令方块",  
                    safeContent.length,  
                    previewContent // 这里使用截取后的预览文本
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
val file = File(filename)  
return try {  
    // Kotlin 提供的简便扩展，内部有 Buffer 优化，直接读取全部文本  
    file.readText(Charsets.UTF_8)  
} catch (e: IOException) {  
    e.printStackTrace()  
    ""  
}

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
