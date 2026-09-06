package com.ppailab.agnesstudio.data

import android.content.Context
import androidx.core.content.edit
import com.ppailab.agnesstudio.model.AppSettings
import com.ppailab.agnesstudio.model.ChatParameters
import com.ppailab.agnesstudio.model.ImageParameters
import com.ppailab.agnesstudio.model.VideoParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsStore(context: Context, private val json: Json) {
    private val preferences = context.getSharedPreferences("agnes_settings", Context.MODE_PRIVATE)

    private val _app = MutableStateFlow(read(KEY_APP, AppSettings()))
    private val _chat = MutableStateFlow(read(KEY_CHAT, ChatParameters()))
    private val _image = MutableStateFlow(read(KEY_IMAGE, ImageParameters()))
    private val _video = MutableStateFlow(read(KEY_VIDEO, VideoParameters()))

    val app: StateFlow<AppSettings> = _app.asStateFlow()
    val chat: StateFlow<ChatParameters> = _chat.asStateFlow()
    val image: StateFlow<ImageParameters> = _image.asStateFlow()
    val video: StateFlow<VideoParameters> = _video.asStateFlow()

    fun updateApp(value: AppSettings) = write(KEY_APP, value) { _app.value = it }
    fun updateChat(value: ChatParameters) = write(KEY_CHAT, value) { _chat.value = it }
    fun updateImage(value: ImageParameters) = write(KEY_IMAGE, value) { _image.value = it }
    fun updateVideo(value: VideoParameters) = write(KEY_VIDEO, value) { _video.value = it }

    private inline fun <reified T> read(key: String, fallback: T): T {
        val raw = preferences.getString(key, null) ?: return fallback
        return runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
    }

    private inline fun <reified T> write(key: String, value: T, update: (T) -> Unit) {
        preferences.edit { putString(key, json.encodeToString(value)) }
        update(value)
    }

    private companion object {
        const val KEY_APP = "app"
        const val KEY_CHAT = "chat"
        const val KEY_IMAGE = "image"
        const val KEY_VIDEO = "video"
    }
}
