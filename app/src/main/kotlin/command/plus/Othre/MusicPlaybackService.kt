package command.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MusicPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val binder = LocalBinder()
    
    var onPositionUpdate: ((Int) -> Unit)? = null
    var onStateChange: ((Boolean) -> Unit)? = null
    var onPrepared: ((Int) -> Unit)? = null

    private var progressJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
    // 缓存当前正在播放的信息，方便 UI 重连时对齐
    private var currentTitle: String = ""

    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(101, createNotification("指令音符盒", "未在播放"))
    }

    fun playFile(path: String, title: String) {
        // 🟢 停止前一个循环，防并发产生的旧任务错乱
        stopProgressLoop() 
        mediaPlayer?.stop()
        mediaPlayer?.release()
        
        currentTitle = title
        updateNotification(title, "正在准备...")

        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            setOnPreparedListener { mp ->
                onPrepared?.invoke(mp.duration)
                mp.start()
                onStateChange?.invoke(true)
                updateNotification(title, "正在播放")
                startProgressLoop()
            }
            setOnCompletionListener {
                onStateChange?.invoke(false)
                onPositionUpdate?.invoke(0)
                updateNotification(title, "播放完成")
                stopProgressLoop()
            }
            prepareAsync()
        }
    }
    
    // 🟢 新增方法：仅加载和准备文件，不自动播放
    fun loadFile(path: String, title: String) {
        stopProgressLoop() 
        mediaPlayer?.stop()
        mediaPlayer?.release()
        
        currentTitle = title
        updateNotification(title, "正在准备...")

        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            setOnPreparedListener { mp ->
                onPrepared?.invoke(mp.duration)
                onPositionUpdate?.invoke(0) // 重置进度条到 0
                onStateChange?.invoke(false) // 通知 UI 处于暂停状态
                updateNotification(title, "已加载（暂停）")
                // 🛑 注意：这里不调用 mp.start()，也不启动 progress 循环
            }
            setOnCompletionListener {
                onStateChange?.invoke(false)
                onPositionUpdate?.invoke(0)
                updateNotification(title, "播放完成")
                stopProgressLoop()
            }
            prepareAsync()
        }
    }

    fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                pausePlay()
            } else {
                it.start()
                onStateChange?.invoke(true)
                startProgressLoop()
                updateNotification(currentTitle, "正在播放")
            }
        }
    }

    // 🟢 提供外部显式调用的纯粹暂停方法 (用来适配应用退到后台逻辑)
    fun pausePlay() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                onStateChange?.invoke(false)
                stopProgressLoop()
                updateNotification(currentTitle, "已暂停")
            }
        }
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        onPositionUpdate?.invoke(position)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun getCurrentTitle(): String = currentTitle

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isPlaying()) {
                mediaPlayer?.let { onPositionUpdate?.invoke(it.currentPosition) }
                delay(500)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
    }

    private fun createNotification(title: String, text: String): Notification {
        val channelId = "music_playback_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "音乐试听后台播放", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notification = createNotification(title, text)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(101, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressLoop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}