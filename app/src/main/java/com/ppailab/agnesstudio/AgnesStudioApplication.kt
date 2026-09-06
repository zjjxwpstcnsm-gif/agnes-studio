package com.ppailab.agnesstudio

import android.app.Application
import com.ppailab.agnesstudio.data.AppDatabase
import com.ppailab.agnesstudio.data.MediaFileStore
import com.ppailab.agnesstudio.data.SecureKeyStore
import com.ppailab.agnesstudio.data.SettingsStore
import com.ppailab.agnesstudio.network.AgnesApiClient
import com.ppailab.agnesstudio.network.PayloadBuilder
import com.ppailab.agnesstudio.network.TemporaryMediaUploader
import com.ppailab.agnesstudio.queue.GenerationQueue
import com.ppailab.agnesstudio.queue.QueueProcessor
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AgnesStudioApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = true
        }
        val httpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
        val database = AppDatabase(this, json)
        val settings = SettingsStore(this, json)
        val credentials = SecureKeyStore(this)
        val api = AgnesApiClient(httpClient, json)
        val fileStore = MediaFileStore(this, httpClient)
        val uploader = TemporaryMediaUploader(httpClient)
        val payloadBuilder = PayloadBuilder(json)
        val generationQueue = GenerationQueue(this)
        val processor = QueueProcessor(
            database = database,
            settingsStore = settings,
            loadApiKey = credentials::load,
            api = api,
            payloadBuilder = payloadBuilder,
            fileStore = fileStore,
            uploader = uploader,
            json = json,
        )
        graph = AppGraph(
            json = json,
            database = database,
            settings = settings,
            credentials = credentials,
            api = api,
            payloadBuilder = payloadBuilder,
            fileStore = fileStore,
            uploader = uploader,
            generationQueue = generationQueue,
            queueProcessor = processor,
        )
        generationQueue.kick()
    }
}

data class AppGraph(
    val json: Json,
    val database: AppDatabase,
    val settings: SettingsStore,
    val credentials: SecureKeyStore,
    val api: AgnesApiClient,
    val payloadBuilder: PayloadBuilder,
    val fileStore: MediaFileStore,
    val uploader: TemporaryMediaUploader,
    val generationQueue: GenerationQueue,
    val queueProcessor: QueueProcessor,
)
