package command.plus

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private const val BIND_TIMEOUT_MS = 1000L
    private const val MAX_BIND_RETRY = 2
    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialized = false
    private var commandService: ICommandService? = null
    private var pendingCommand: String? = null

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
        ensureReadyOrRequest()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        commandService = null
        cancelBindTimeout()
        bindingInProgress = false
        emitState(State.DEAD)
    }
    
    fun getCurrentState(): State {
    return when {
        !Shizuku.pingBinder() -> State.NO_BINDER
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NEED_PERMISSION
        commandService != null -> State.READY
        else -> State.BINDING
    }
}

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            cancelBindTimeout()
            bindingInProgress = false

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

        ensureReadyOrRequest()
    }

    fun release() {
        if (!initialized) return
        initialized = false

        cancelBindTimeout()
        bindingInProgress = false

        try {
            // 你的版本如果是别的签名，就把这里改成对应的 unbindUserService 调用
            userServiceArgs?.let {
    try {
        Shizuku.unbindUserService(it, serviceConnection, true)
    } catch (_: Throwable) {}
}
        } catch (_: Throwable) {
        }

        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }

        // 如果你希望这个单例后面还能继续复用，就不要 cancel 掉 ioScope。
        // ioScope.cancel()
    }

    fun runWhenReady(command: String) {
        pendingCommand = command
        ensureReadyOrRequest()
    }

    fun isConnected(): Boolean = commandService != null

    private fun ensureReadyOrRequest() {
        if (Shizuku.isPreV11()) {
            emitState(State.NO_BINDER)
            return
        }

        if (!Shizuku.pingBinder()) {
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

        val args = Shizuku.UserServiceArgs(
    ComponentName(appContext, CommandUserService::class.java)
)
    .processNameSuffix(":shizuku")
    .daemon(false)
    .version(BuildConfig.VERSION_CODE)

userServiceArgs = args
            .processNameSuffix(":shizuku")
            .daemon(false)
            .version(BuildConfig.VERSION_CODE)
            // 如果你的版本支持 tag，建议加上
            // .tag("command_user_service")

        startBindTimeoutWatchdog()

        try {
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

            try {
                userServiceArgs?.let {
    try {
        Shizuku.unbindUserService(it, serviceConnection, true)
    } catch (_: Throwable) {}
}
            } catch (_: Throwable) {
            }

            commandService = null
            bindingInProgress = false

            if (bindRetryCount < MAX_BIND_RETRY) {
                bindRetryCount++
                emitState(State.NEED_PERMISSION)
                mainHandler.post {
                    ensureReadyOrRequest()
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

        // ✅ 更新缓存
        lastResult = result

        mainHandler.post {
            onCommandOutput?.invoke(result)
        }
    }
}

fun getLastResult(): String? {
    return lastResult
}

    private fun emitState(state: State) {
        currentState = state
        mainHandler.post {
            onStateChanged?.invoke(state)
        }
    }
}