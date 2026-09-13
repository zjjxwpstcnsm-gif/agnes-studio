package com.ppailab.agnesstudio.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.ppailab.agnesstudio.model.ChatConversation
import com.ppailab.agnesstudio.model.ChatMessage
import com.ppailab.agnesstudio.model.ChatParameters
import com.ppailab.agnesstudio.model.ErrorKind
import com.ppailab.agnesstudio.model.GenerationJob
import com.ppailab.agnesstudio.model.JobLog
import com.ppailab.agnesstudio.model.JobLogLevel
import com.ppailab.agnesstudio.model.JobStatus
import com.ppailab.agnesstudio.model.MediaAttachment
import com.ppailab.agnesstudio.model.MessageState
import com.ppailab.agnesstudio.model.Modality
import com.ppailab.agnesstudio.model.QueueReceipt
import com.ppailab.agnesstudio.model.RateReservation
import com.ppailab.agnesstudio.model.ToolCall
import com.ppailab.agnesstudio.queue.QueueTimingPolicy
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppDatabase(
    context: Context,
    private val json: Json,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val _changes = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 32).apply {
        tryEmit(Unit)
    }
    val changes: SharedFlow<Unit> = _changes

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                parameters_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                reasoning TEXT NOT NULL DEFAULT '',
                tool_calls_json TEXT NOT NULL DEFAULT '[]',
                tool_call_id TEXT,
                attachments_json TEXT NOT NULL DEFAULT '[]',
                state TEXT NOT NULL,
                error_message TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX messages_conversation_idx ON messages(conversation_id, created_at)")
        db.execSQL(
            """
            CREATE TABLE jobs (
                id TEXT PRIMARY KEY,
                modality TEXT NOT NULL,
                status TEXT NOT NULL,
                prompt TEXT NOT NULL,
                spec_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                next_attempt_at INTEGER NOT NULL,
                attempt INTEGER NOT NULL DEFAULT 0,
                progress INTEGER NOT NULL DEFAULT 0,
                remote_id TEXT,
                result_url TEXT,
                result_path TEXT,
                error_kind TEXT,
                error_message TEXT,
                http_status INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX jobs_status_idx ON jobs(status, next_attempt_at, created_at)")
        db.execSQL(
            """
            CREATE TABLE rate_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                bucket TEXT NOT NULL,
                occurred_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX rate_events_bucket_idx ON rate_events(bucket, occurred_at)")
        createJobLogsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createJobLogsTable(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN parameters_json TEXT NOT NULL DEFAULT '{}'")
        }
    }

    private fun createJobLogsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS job_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                level TEXT NOT NULL,
                stage TEXT NOT NULL,
                message TEXT NOT NULL,
                details TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS job_logs_job_idx ON job_logs(job_id, created_at, id)")
    }

    @Synchronized
    fun ensureConversation(defaultParameters: ChatParameters = ChatParameters()): ChatConversation {
        seedLegacyConversationParameters(defaultParameters)
        readableDatabase.rawQuery(
            "SELECT id, title, parameters_json, created_at, updated_at FROM conversations ORDER BY updated_at DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.toConversation()
        }
        return createConversation("新对话", defaultParameters)
    }

    @Synchronized
    fun createConversation(
        title: String = "新对话",
        parameters: ChatParameters = ChatParameters(),
    ): ChatConversation {
        val now = System.currentTimeMillis()
        val item = ChatConversation(UUID.randomUUID().toString(), title, parameters, now, now)
        writableDatabase.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("parameters_json", json.encodeToString(item.parameters))
            put("created_at", item.createdAt)
            put("updated_at", item.updatedAt)
        })
        changed()
        return item
    }

    @Synchronized
    fun conversations(): List<ChatConversation> = readableDatabase.rawQuery(
        "SELECT id, title, parameters_json, created_at, updated_at FROM conversations ORDER BY updated_at DESC",
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toConversation()) } }

    @Synchronized
    fun conversation(id: String): ChatConversation? = readableDatabase.rawQuery(
        "SELECT id, title, parameters_json, created_at, updated_at FROM conversations WHERE id = ? LIMIT 1",
        arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toConversation() else null }

    @Synchronized
    fun renameConversation(id: String, title: String) {
        writableDatabase.update("conversations", ContentValues().apply {
            put("title", title.take(80))
            put("updated_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun updateConversationParameters(id: String, parameters: ChatParameters) {
        writableDatabase.update("conversations", ContentValues().apply {
            put("parameters_json", json.encodeToString(parameters))
            put("updated_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun deleteConversation(id: String) {
        writableDatabase.delete("conversations", "id = ?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun insertMessage(message: ChatMessage) {
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("id", message.id)
            put("conversation_id", message.conversationId)
            put("role", message.role)
            put("content", message.content)
            put("reasoning", message.reasoning)
            put("tool_calls_json", json.encodeToString(message.toolCalls))
            put("tool_call_id", message.toolCallId)
            put("attachments_json", json.encodeToString(message.attachments))
            put("state", message.state.name)
            put("error_message", message.errorMessage)
            put("created_at", message.createdAt)
        })
        touchConversation(message.conversationId)
        changed()
    }

    @Synchronized
    fun messages(conversationId: String): List<ChatMessage> = readableDatabase.rawQuery(
        """
        SELECT id, conversation_id, role, content, reasoning, tool_calls_json, tool_call_id,
               attachments_json, state, error_message, created_at
        FROM messages WHERE conversation_id = ? ORDER BY created_at ASC
        """.trimIndent(),
        arrayOf(conversationId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }

    @Synchronized
    fun updateAssistantMessage(
        id: String,
        content: String,
        reasoning: String,
        toolCalls: List<ToolCall>,
        state: MessageState,
        errorMessage: String? = null,
    ) {
        writableDatabase.update("messages", ContentValues().apply {
            put("content", content)
            put("reasoning", reasoning)
            put("tool_calls_json", json.encodeToString(toolCalls))
            put("state", state.name)
            put("error_message", errorMessage)
        }, "id = ?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun enqueueJob(
        modality: Modality,
        prompt: String,
        specJson: String,
        maxQueueSize: Int,
    ): QueueReceipt {
        val db = writableDatabase
        return try {
            db.transaction {
                val active = rawQuery(
                    "SELECT COUNT(*) FROM jobs WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED')",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                if (active >= maxQueueSize) {
                    QueueReceipt(false, position = active, error = "本地队列已满（$active/$maxQueueSize）")
                } else {
                    val now = System.currentTimeMillis()
                    val id = UUID.randomUUID().toString()
                    insertOrThrow("jobs", null, ContentValues().apply {
                        put("id", id)
                        put("modality", modality.name)
                        put("status", JobStatus.QUEUED.name)
                        put("prompt", prompt)
                        put("spec_json", specJson)
                        put("created_at", now)
                        put("updated_at", now)
                        put("next_attempt_at", now)
                        put("attempt", 0)
                        put("progress", 0)
                    })
                    QueueReceipt(true, id, active + 1)
                }
            }
        } finally {
            changed()
        }
    }

    @Synchronized
    fun duplicateJob(id: String, maxQueueSize: Int): QueueReceipt {
        val source = job(id) ?: return QueueReceipt(false, error = "原任务已被清理，无法复制")
        // Only the request snapshot is copied. enqueueJob creates a new ID,
        // timestamps and execution state, and enforces the shared queue limit.
        val receipt = enqueueJob(source.modality, source.prompt, source.specJson, maxQueueSize)
        receipt.jobId?.let { newId ->
            addJobLog(newId, JobLogLevel.INFO, "复制并生成",
                "已复制原任务的提示词、参数和素材 · 第 ${receipt.position} 位",
                "source_job_id=$id")
        }
        return receipt
    }

    @Synchronized
    fun jobs(): List<GenerationJob> = readableDatabase.rawQuery(
        "SELECT * FROM jobs ORDER BY created_at DESC",
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toJob()) } }

    @Synchronized
    fun addJobLog(
        jobId: String,
        level: JobLogLevel,
        stage: String,
        message: String,
        details: String? = null,
    ) {
        val db = writableDatabase
        db.insert("job_logs", null, ContentValues().apply {
            put("job_id", jobId)
            put("created_at", System.currentTimeMillis())
            put("level", level.name)
            put("stage", stage.take(80))
            put("message", message.take(1_000))
            details?.let { put("details", it.take(MAX_LOG_DETAILS)) }
        })
        db.execSQL(
            """
            DELETE FROM job_logs
            WHERE job_id = ? AND id NOT IN (
                SELECT id FROM job_logs WHERE job_id = ? ORDER BY id DESC LIMIT $MAX_LOGS_PER_JOB
            )
            """.trimIndent(),
            arrayOf(jobId, jobId),
        )
        changed()
    }

    @Synchronized
    fun jobLogs(jobId: String): List<JobLog> = readableDatabase.rawQuery(
        "SELECT * FROM job_logs WHERE job_id = ? ORDER BY id ASC",
        arrayOf(jobId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toJobLog()) } }

    @Synchronized
    fun job(id: String): GenerationJob? = readableDatabase.rawQuery(
        "SELECT * FROM jobs WHERE id = ? LIMIT 1",
        arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }

    @Synchronized
    fun nextRunnableJob(now: Long = System.currentTimeMillis()): GenerationJob? =
        readableDatabase.rawQuery(
            """
            SELECT * FROM jobs
            WHERE status IN ('QUEUED','WAITING_RATE_LIMIT','RETRY_WAIT','SENDING','PROCESSING')
              AND next_attempt_at <= ?
            ORDER BY CASE WHEN status IN ('SENDING','PROCESSING') THEN 0 ELSE 1 END, created_at ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(now.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }

    @Synchronized
    fun earliestNextAttempt(): Long? = readableDatabase.rawQuery(
        """
        SELECT MIN(next_attempt_at) FROM jobs
        WHERE status IN ('QUEUED','WAITING_RATE_LIMIT','RETRY_WAIT','SENDING','PROCESSING')
        """.trimIndent(),
        null,
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }

    @Synchronized
    fun updateJob(
        id: String,
        status: JobStatus,
        nextAttemptAt: Long = System.currentTimeMillis(),
        attempt: Int? = null,
        progress: Int? = null,
        remoteId: String? = null,
        keepRemoteId: Boolean = true,
        resultUrl: String? = null,
        resultPath: String? = null,
        errorKind: ErrorKind? = null,
        errorMessage: String? = null,
        httpStatus: Int? = null,
    ) {
        writableDatabase.update("jobs", ContentValues().apply {
            put("status", status.name)
            put("updated_at", System.currentTimeMillis())
            put("next_attempt_at", nextAttemptAt)
            attempt?.let { put("attempt", it) }
            progress?.let { put("progress", it.coerceIn(0, 100)) }
            if (!keepRemoteId || remoteId != null) put("remote_id", remoteId)
            resultUrl?.let { put("result_url", it) }
            resultPath?.let { put("result_path", it) }
            if (errorKind == null) putNull("error_kind") else put("error_kind", errorKind.name)
            if (errorMessage == null) putNull("error_message") else put("error_message", errorMessage)
            if (httpStatus == null) putNull("http_status") else put("http_status", httpStatus)
        }, "id = ? AND status != 'CANCELLED'", arrayOf(id))
        changed()
    }

    /** A successful server response remains useful even if the user just stopped waiting. */
    @Synchronized
    fun recordVideoCreated(id: String, remoteId: String, progress: Int) {
        val current = job(id) ?: return
        writableDatabase.update("jobs", ContentValues().apply {
            put("remote_id", remoteId)
            put("updated_at", System.currentTimeMillis())
            if (current.status != JobStatus.CANCELLED) {
                put("status", JobStatus.PROCESSING.name)
                put("progress", progress.coerceIn(0, 100))
                put("next_attempt_at", System.currentTimeMillis() + QueueTimingPolicy.VIDEO_POLL_INTERVAL_MILLIS)
                putNull("error_kind")
                putNull("error_message")
                putNull("http_status")
            }
        }, "id = ?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun recoverInterruptedJob(id: String): Boolean {
        val current = job(id) ?: return false
        if (current.status in setOf(JobStatus.CANCELLED, JobStatus.FAILED, JobStatus.SUCCEEDED)) return false
        updateJob(
            id,
            if (current.remoteId == null) JobStatus.QUEUED else JobStatus.PROCESSING,
            nextAttemptAt = maxOf(
                current.nextAttemptAt,
                System.currentTimeMillis() + QueueTimingPolicy.minimumRetryDelayMillis(current),
            ),
            errorMessage = "后台执行已暂停，已保存的素材和任务将在调度恢复后继续使用。",
        )
        return true
    }

    @Synchronized
    fun updateJobSpec(id: String, specJson: String) {
        writableDatabase.update("jobs", ContentValues().apply {
            put("spec_json", specJson)
            put("updated_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun cancelJob(id: String) {
        updateJob(id, JobStatus.CANCELLED, errorKind = ErrorKind.CANCELLED, errorMessage = "已取消")
    }

    @Synchronized
    fun retryJob(id: String) {
        val current = job(id) ?: return
        if (current.status !in setOf(JobStatus.FAILED, JobStatus.CANCELLED)) return
        val resumeRemote = current.remoteId != null &&
            (current.status == JobStatus.CANCELLED || current.errorKind != ErrorKind.SERVER)
        writableDatabase.update("jobs", ContentValues().apply {
            put("status", if (resumeRemote) JobStatus.PROCESSING.name else JobStatus.QUEUED.name)
            put("updated_at", System.currentTimeMillis())
            put(
                "next_attempt_at",
                if (resumeRemote) maxOf(
                    current.nextAttemptAt,
                    System.currentTimeMillis() + QueueTimingPolicy.VIDEO_POLL_INTERVAL_MILLIS,
                ) else System.currentTimeMillis(),
            )
            put("attempt", 0)
            put("progress", 0)
            if (!resumeRemote) putNull("remote_id")
            putNull("result_url")
            putNull("result_path")
            putNull("error_kind")
            putNull("error_message")
            putNull("http_status")
        }, "id = ?", arrayOf(id))
        changed()
    }

    @Synchronized
    fun clearFinishedJobs() {
        writableDatabase.delete("jobs", "status IN ('SUCCEEDED','FAILED','CANCELLED')", null)
        changed()
    }

    @Synchronized
    fun reserveRateSlot(bucket: String, rpm: Int, now: Long = System.currentTimeMillis()): RateReservation {
        if (rpm <= 0) return RateReservation(false, now + 60_000L)
        val db = writableDatabase
        return db.transaction {
            val windowStart = now - 60_000L
            delete("rate_events", "occurred_at < ?", arrayOf(windowStart.toString()))
            val timestamps = rawQuery(
                "SELECT occurred_at FROM rate_events WHERE bucket = ? ORDER BY occurred_at ASC",
                arrayOf(bucket),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            if (timestamps.size < rpm) {
                insertOrThrow("rate_events", null, ContentValues().apply {
                    put("bucket", bucket)
                    put("occurred_at", now)
                })
                RateReservation(true)
            } else {
                RateReservation(false, timestamps.first() + 60_250L)
            }
        }
    }

    private fun touchConversation(id: String) {
        writableDatabase.update("conversations", ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
        }, "id = ?", arrayOf(id))
    }

    private fun seedLegacyConversationParameters(parameters: ChatParameters) {
        writableDatabase.update("conversations", ContentValues().apply {
            put("parameters_json", json.encodeToString(parameters))
        }, "parameters_json = ? OR TRIM(parameters_json) = ''", arrayOf("{}"))
    }

    private fun Cursor.toConversation() = ChatConversation(
        id = string("id"),
        title = string("title"),
        parameters = runCatching {
            json.decodeFromString<ChatParameters>(string("parameters_json"))
        }.getOrDefault(ChatParameters()),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
    )

    private fun Cursor.toMessage() = ChatMessage(
        id = string("id"),
        conversationId = string("conversation_id"),
        role = string("role"),
        content = string("content"),
        reasoning = string("reasoning"),
        toolCalls = decodeList(string("tool_calls_json")),
        toolCallId = nullableString("tool_call_id"),
        attachments = decodeList(string("attachments_json")),
        state = enumOrDefault(string("state"), MessageState.COMPLETE),
        errorMessage = nullableString("error_message"),
        createdAt = long("created_at"),
    )

    private fun Cursor.toJob() = GenerationJob(
        id = string("id"),
        modality = enumOrDefault(string("modality"), Modality.IMAGE),
        status = enumOrDefault(string("status"), JobStatus.FAILED),
        prompt = string("prompt"),
        specJson = string("spec_json"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        nextAttemptAt = long("next_attempt_at"),
        attempt = int("attempt"),
        progress = int("progress"),
        remoteId = nullableString("remote_id"),
        resultUrl = nullableString("result_url"),
        resultPath = nullableString("result_path"),
        errorKind = nullableString("error_kind")?.let { enumOrNull<ErrorKind>(it) },
        errorMessage = nullableString("error_message"),
        httpStatus = nullableInt("http_status"),
    )

    private fun Cursor.toJobLog() = JobLog(
        id = long("id"),
        jobId = string("job_id"),
        createdAt = long("created_at"),
        level = enumOrDefault(string("level"), JobLogLevel.INFO),
        stage = string("stage"),
        message = string("message"),
        details = nullableString("details"),
    )

    private inline fun <reified T> decodeList(value: String): List<T> =
        runCatching { json.decodeFromString<List<T>>(value) }.getOrDefault(emptyList())

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullableString(name: String): String? = getColumnIndexOrThrow(name).let {
        if (isNull(it)) null else getString(it)
    }
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.nullableInt(name: String): Int? = getColumnIndexOrThrow(name).let {
        if (isNull(it)) null else getInt(it)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
        enumOrNull<T>(value) ?: fallback

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private fun changed() {
        _changes.tryEmit(Unit)
    }

    private companion object {
        const val DB_NAME = "agnes_studio.db"
        const val DB_VERSION = 3
        const val MAX_LOGS_PER_JOB = 500
        const val MAX_LOG_DETAILS = 40_000
    }
}
