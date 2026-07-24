package command.plus

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

object ShizukuManager {

    enum class State {
        NO_BINDER,
        NEED_PERMISSION,
        BINDING,
        READY,
        DEAD
    }

    @Volatile
    private var lastResult: String? = null

    private const val REQUEST_CODE = 1001
    // 💡 优化 1：延长超时时间至 5 秒（冷启动 UserService 需要时间）
    private const val BIND_TIMEOUT_MS = 5000L 
    private const val MAX_BIND_RETRY = 2

    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialized = false
    private var commandService: ICommandService? = null
    private var pendingCommand: String? = null

    @Volatile
    private var currentState: State = State.NO_BINDER
    private var bindTimeoutRunnable: Runnable? = null
    private var bindRetryCount = 0
    private var bindingInProgress = false

    var onStateChanged: ((State) -> Unit)? = null
    var onCommandOutput: ((String) -> Unit)? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener

        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            bindRetryCount = 0
            bindUserServiceIfPossible()
        } else {
            emitState(State.NEED_PERMISSION)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
    // 当 Shizuku 服务真正准备好时触发
    mainHandler.post {
        ensureReadyOrRequest()
    }
}

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        commandService = null
        cancelBindTimeout()
        bindingInProgress = false
        emitState(State.DEAD)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // 💡 绑定成功，及时取消超时 Watchdog
            cancelBindTimeout()
            bindingInProgress = false
            bindRetryCount = 0

            commandService = ICommandService.Stub.asInterface(service)
            emitState(State.READY)

            pendingCommand?.let { command ->
                pendingCommand = null
                executeNow(command)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            commandService = null
            cancelBindTimeout()
            bindingInProgress = false
            emitState(State.DEAD)
        }
    }

    

fun init(context: Context) {
    if (initialized) return
    initialized = true

    appContext = context.applicationContext

    Shizuku.addRequestPermissionResultListener(permissionListener)
    Shizuku.addBinderReceivedListener(binderReceivedListener)
    Shizuku.addBinderDeadListener(binderDeadListener)

    // 💡 只有在 ping 得通时才尝试绑定，否则静默等待 BinderReceived 回调
    if (Shizuku.pingBinder()) {
        ensureReadyOrRequest()
    }
}

    fun release() {
        if (!initialized) return
        initialized = false

        cancelBindTimeout()
        bindingInProgress = false

        userServiceArgs?.let { args ->
            try {
                Shizuku.unbindUserService(args, serviceConnection, true)
            } catch (_: Throwable) {}
        }

        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}
    }

    fun runWhenReady(command: String) {
        pendingCommand = command
        if (currentState == State.READY) {
            pendingCommand = null
            executeNow(command)
        } else {
            ensureReadyOrRequest()
        }
    }

    fun isConnected(): Boolean = commandService != null

    fun getCurrentState(): State {
        return when {
            !Shizuku.pingBinder() -> State.NO_BINDER
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NEED_PERMISSION
            commandService != null -> State.READY
            bindingInProgress -> State.BINDING
            else -> currentState
        }
    }

    private fun ensureReadyOrRequest() {
        if (Shizuku.isPreV11() || !Shizuku.pingBinder()) {
            emitState(State.NO_BINDER)
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            emitState(State.NEED_PERMISSION)
            Shizuku.requestPermission(REQUEST_CODE)
            return
        }

        bindUserServiceIfPossible()
    }

    private fun bindUserServiceIfPossible() {
    if (!Shizuku.pingBinder()) {
        emitState(State.NO_BINDER)
        return
    }

    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        emitState(State.NEED_PERMISSION)
        Shizuku.requestPermission(REQUEST_CODE)
        return
    }

    if (commandService != null) {
        emitState(State.READY)
        pendingCommand?.let { command ->
            pendingCommand = null
            executeNow(command)
        }
        return
    }

    if (bindingInProgress) return

    bindingInProgress = true
    emitState(State.BINDING)

    // 💡 1. 采用动态 version，防止 Shizuku 误认为 Service 已存活而不触发新的 Connection
    val dynamicVersion = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()

    val args = Shizuku.UserServiceArgs(
        ComponentName(appContext, CommandUserService::class.java)
    )
        .processNameSuffix("shizuku")
        .daemon(false)
        .version(dynamicVersion)

    userServiceArgs = args

    startBindTimeoutWatchdog()

    try {
        // 💡 2. 绑定前清理残留的连接
        runCatching { Shizuku.unbindUserService(args, serviceConnection, true) }
        
        Shizuku.bindUserService(args, serviceConnection)
    } catch (e: Throwable) {
        cancelBindTimeout()
        bindingInProgress = false
        handleBindFailure(e)
    }
}

    private fun startBindTimeoutWatchdog() {
        cancelBindTimeout()

        bindTimeoutRunnable = Runnable {
            if (!bindingInProgress || commandService != null) return@Runnable

            userServiceArgs?.let { args ->
                try {
                    Shizuku.unbindUserService(args, serviceConnection, true)
                } catch (_: Throwable) {}
            }

            commandService = null
            bindingInProgress = false

            // 💡 优化 3：超时重试时，直接再次尝试绑定，不要误切到 NEED_PERMISSION 状态
            if (bindRetryCount < MAX_BIND_RETRY) {
                bindRetryCount++
                mainHandler.post {
                    bindUserServiceIfPossible()
                }
            } else {
                emitState(State.DEAD)
            }
        }

        mainHandler.postDelayed(bindTimeoutRunnable!!, BIND_TIMEOUT_MS)
    }

    private fun cancelBindTimeout() {
        bindTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bindTimeoutRunnable = null
    }

    private fun handleBindFailure(e: Throwable) {
        val msg = e.message ?: "bind failed"
        onCommandOutput?.invoke("ERROR: $msg")
        emitState(State.DEAD)
    }

    private fun executeNow(command: String) {
        val service = commandService
        if (service == null) {
            pendingCommand = command
            bindUserServiceIfPossible()
            return
        }

        ioScope.launch {
            val result = runCatching {
                service.exec(command)
            }.getOrElse { e ->
                "ERROR: ${e.message}"
            }

            lastResult = result

            mainHandler.post {
                onCommandOutput?.invoke(result)
            }
        }
    }

    fun getLastResult(): String? = lastResult

    private fun emitState(state: State) {
        currentState = state
        mainHandler.post {
            onStateChanged?.invoke(state)
        }
    }
}