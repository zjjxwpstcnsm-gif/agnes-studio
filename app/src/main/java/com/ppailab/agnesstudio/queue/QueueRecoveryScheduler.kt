package com.ppailab.agnesstudio.queue

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.ppailab.agnesstudio.AgnesStudioApplication
import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.model.JobLogLevel

/** Inexact, idle-capable safety net; the service still owns normal 30-second polling. */
class QueueRecoveryScheduler(private val context: Context, private val database: AppDatabase) {
    private val preferences = context.getSharedPreferences("queue_recovery", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    var foregroundTimedOut: Boolean
        get() = preferences.getBoolean("foreground_timed_out", false)
        set(value) { preferences.edit().putBoolean("foreground_timed_out", value).apply() }

    fun schedule(foregroundRunning: Boolean = false) {
        val next = database.earliestNextAttempt()
        if (next == null) {
            alarmManager.cancel(pendingIntent())
            alarmDelivered()
            return
        }
        val elapsed = SystemClock.elapsedRealtime()
        val trigger = elapsed + if (foregroundRunning) WATCHDOG_INTERVAL
            else (next - System.currentTimeMillis()).coerceIn(60_000L, WATCHDOG_INTERVAL)
        val existing = preferences.getLong("alarm_elapsed", 0L)
        // Repeated UI/Worker wakeups must not keep postponing the safety net.
        if (existing > elapsed && existing <= trigger) return
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent())
        preferences.edit().putLong("alarm_elapsed", trigger).apply()
    }

    fun alarmDelivered() {
        preferences.edit().remove("alarm_elapsed").apply()
    }

    fun heartbeat() {
        preferences.edit().putLong("last_heartbeat", System.currentTimeMillis()).apply()
    }

    fun logRecovery(source: String) {
        val now = System.currentTimeMillis()
        val job = database.nextRunnableJob(now - 120_000L) ?: return
        // Do not spam the same delayed task on every Activity or Worker wakeup.
        if (now - preferences.getLong("last_recovery_log", 0L) < 120_000L) return
        preferences.edit().putLong("last_recovery_log", now).apply()
        database.addJobLog(job.id, JobLogLevel.WARNING, "后台恢复",
            "调度比计划晚了 ${(now - job.nextAttemptAt) / 1_000} 秒，正在恢复；不会重建已有视频任务。",
            "source=$source\nlast_heartbeat=${preferences.getLong("last_heartbeat", 0L)}\n" +
                "battery_unrestricted=${powerManager.isIgnoringBatteryOptimizations(context.packageName)}\n" +
                "device_idle=${powerManager.isDeviceIdleMode}\npower_save=${powerManager.isPowerSaveMode}\n" +
                "manufacturer=${Build.MANUFACTURER}\nsdk=${Build.VERSION.SDK_INT}")
    }

    private fun pendingIntent() = PendingIntent.getBroadcast(
        context, 1002, Intent(context, QueueRecoveryReceiver::class.java).setAction(ACTION_RECOVER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_RECOVER = "com.ppailab.agnesstudio.RECOVER_QUEUE"
        const val WATCHDOG_INTERVAL = 10 * 60_000L
    }
}

class QueueRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val queue = (context.applicationContext as AgnesStudioApplication).graph.generationQueue
        queue.recovery.alarmDelivered()
        // Android 15 disallows dataSync FGS launches from boot receivers, even
        // with a battery exemption. Boot/update only restore persisted work.
        queue.recoverFromSystem(intent.action.orEmpty(), allowForeground = intent.action == QueueRecoveryScheduler.ACTION_RECOVER)
    }
}
