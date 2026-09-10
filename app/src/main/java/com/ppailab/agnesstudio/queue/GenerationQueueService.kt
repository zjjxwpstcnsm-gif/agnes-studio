package com.ppailab.agnesstudio.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ppailab.agnesstudio.AgnesStudioApplication
import com.ppailab.agnesstudio.MainActivity
import com.ppailab.agnesstudio.model.JobLogLevel
import com.ppailab.agnesstudio.model.JobStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns generation independently of the Activity and JobScheduler runtime quota. */
class GenerationQueueService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as AgnesStudioApplication).graph
    private var runner: Job? = null
    private var monitor: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    private var stopReason = "service_destroyed"

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "图片与视频生成", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (error: RuntimeException) {
            graph.database.nextRunnableJob()?.let {
                graph.database.addJobLog(it.id, JobLogLevel.WARNING, "后台调度",
                    "系统未允许持续后台执行，任务已保存并安排恢复。", error.javaClass.simpleName)
            }
            graph.generationQueue.kick()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        graph.generationQueue.foregroundRunning = true
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgnesStudio:generation")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKE_LOCK_MILLIS)
                }
        }
        if (monitor?.isActive != true) monitor = scope.launch {
            while (isActive) {
                // Renew a bounded lock only while this service has live work.
                // Battery exemption is still required for networking in Doze.
                wakeLock?.acquire(MAX_WAKE_LOCK_MILLIS)
                graph.generationQueue.recovery.heartbeat()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                delay(30_000L)
            }
        }
        graph.generationQueue.kick(GenerationQueue.RECOVERY_INTERVAL_MILLIS)
        graph.queueProcessor.wake()
        if (runner?.isActive != true) startDrain()
        // Android may recreate a killed service; the queue, URLs and remote IDs
        // are in SQLite. A task swipe does not explicitly stop this service.
        return START_STICKY
    }

    private fun startDrain() {
        runner = scope.launch {
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    graph.queueProcessor.drain("foreground-service") { stopReason }
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                graph.generationQueue.kick()
            } finally {
                runner = null
                if (isActive) {
                    // onStartCommand and this check run on Main. A new submission
                    // during an idle drain must not be lost at service shutdown.
                    if (completed && graph.database.earliestNextAttempt() != null) {
                        startDrain()
                    } else {
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 dataSync daily limit: stop promptly instead of crashing.
        stopReason = "data_sync_time_limit"
        graph.generationQueue.recovery.foregroundTimedOut = true
        logLifecycle("系统后台时长额度已用完，任务已保存，改由系统调度继续恢复。", stopReason)
        scope.cancel()
        graph.generationQueue.kick(GenerationQueue.RECOVERY_INTERVAL_MILLIS)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logLifecycle("应用已从最近任务划走，生成服务继续处理，已安排恢复唤醒。", "task_removed")
        graph.generationQueue.kick(GenerationQueue.RECOVERY_INTERVAL_MILLIS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        logLifecycle("生成服务停止，未完成任务已保留并安排系统恢复。", stopReason)
        graph.generationQueue.foregroundRunning = false
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        graph.generationQueue.kick()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun logLifecycle(message: String, reason: String) {
        graph.database.nextRunnableJob(Long.MAX_VALUE)?.let {
            graph.database.addJobLog(it.id, JobLogLevel.WARNING, "后台服务", message, "stop_reason=$reason")
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = graph.database.nextRunnableJob(Long.MAX_VALUE)
        val whenNext = next?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.nextAttemptAt)) }
        val status = when (next?.status) {
            JobStatus.RETRY_WAIT -> "等待重试 · 计划 $whenNext"
            JobStatus.WAITING_RATE_LIMIT -> "等待调用窗口 · 计划 $whenNext"
            JobStatus.PROCESSING -> "服务端生成中 · 下次查询 $whenNext"
            JobStatus.SENDING -> "正在上传素材或提交生成请求"
            else -> "正在处理生成队列"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Agnes Studio 正在生成")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    internal companion object {
        const val CHANNEL_ID = "agnes-generation"
        const val NOTIFICATION_ID = 1001
        const val MAX_WAKE_LOCK_MILLIS = 2 * 60_000L
    }
}
