package com.ppailab.agnesstudio.queue

import android.app.AlarmManager
import android.content.Intent
import android.os.PowerManager
import com.ppailab.agnesstudio.AgnesStudioApplication
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.Modality
import com.ppailab.agnesstudio.model.VideoParameters
import com.ppailab.agnesstudio.model.VideoTaskSpec
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AgnesStudioApplication::class)
class QueueRecoverySchedulerTest {
    private val app get() = RuntimeEnvironment.getApplication() as AgnesStudioApplication
    private val queue get() = app.graph.generationQueue
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun enqueue(): String {
        val spec = VideoTaskSpec("recovery test", VideoParameters(), emptyList())
        return requireNotNull(app.graph.database.enqueueJob(Modality.VIDEO, spec.prompt,
            app.graph.json.encodeToString(spec), 10).jobId)
    }

    @Test
    fun `repeated kicks never postpone an existing wakeup and cancellation removes it`() {
        val id = enqueue()
        queue.recovery.schedule()
        val first = alarms.scheduledAlarms.single().triggerAtTime
        app.graph.database.updateJob(id, JobStatus.RETRY_WAIT, nextAttemptAt = System.currentTimeMillis() + 300_000L)
        queue.recovery.schedule(foregroundRunning = true)
        assertEquals(first, alarms.scheduledAlarms.single().triggerAtTime)
        app.graph.database.cancelJob(id)
        queue.recovery.schedule()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `idle wakeup restarts an exempt stopped service without clearing its remote id`() {
        val id = enqueue()
        app.graph.database.recordVideoCreated(id, "already-created", 20)
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, true)
        QueueRecoveryReceiver().onReceive(app, Intent(QueueRecoveryScheduler.ACTION_RECOVER))
        assertEquals(GenerationQueueService::class.java.name, shadowOf(app).nextStartedService.component?.className)
        assertEquals("already-created", app.graph.database.job(id)?.remoteId)
        assertFalse(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `boot never starts dataSync even when exempt and timeout stays blocked until visible`() {
        enqueue()
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, true)
        QueueRecoveryReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        queue.recovery.foregroundTimedOut = true
        QueueRecoveryReceiver().onReceive(app, Intent(QueueRecoveryScheduler.ACTION_RECOVER))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(queue.recovery.foregroundTimedOut)
    }

    @Test
    fun `battery restricted recovery does not attempt forbidden foreground launch`() {
        enqueue()
        QueueRecoveryReceiver().onReceive(app, Intent(QueueRecoveryScheduler.ACTION_RECOVER))
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `overdue recovery records delay and battery context without inventing a kill reason`() {
        val id = enqueue()
        app.graph.database.updateJob(id, JobStatus.RETRY_WAIT, nextAttemptAt = System.currentTimeMillis() - 8 * 60 * 60_000L)
        queue.recovery.logRecovery("alarm")
        queue.recovery.logRecovery("visible_activity")
        val logs = app.graph.database.jobLogs(id).filter { it.stage == "后台恢复" }
        assertEquals(1, logs.size)
        assertTrue(logs.single().details.orEmpty().contains("battery_unrestricted="))
        assertTrue(logs.single().message.contains("28800"))
    }
}
