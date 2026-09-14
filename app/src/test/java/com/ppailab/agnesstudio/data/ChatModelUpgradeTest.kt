package com.ppailab.agnesstudio.data

import android.app.Application
import android.content.Context
import com.ppailab.agnesstudio.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatModelUpgradeTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `upgrade saved defaults once and respect subsequent explicit legacy selection`() {
        val prefs = context.getSharedPreferences("agnes_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("chat", """{"model":"agnes-2.5-flash","maxTokens":8192,"enableThinking":false,"future_field":7}""").commit()
        val settings = SettingsStore(context, json)
        assertEquals(ChatModel.DEFAULT, settings.chat.value.model)
        assertEquals(8192, settings.chat.value.maxTokens)
        assertFalse(settings.chat.value.enableThinking)
        assertTrue(prefs.getString("chat", "")!!.contains("future_field"))
        settings.updateChat(settings.chat.value.copy(model = ChatModel.LEGACY))
        assertEquals(ChatModel.LEGACY, SettingsStore(context, json).chat.value.model)
    }

    @Test
    fun `database v3 upgrades old default without changing custom models messages or parameters`() {
        context.deleteDatabase("agnes_studio.db")
        var db = AppDatabase(context, json)
        val old = ChatParameters(model = ChatModel.LEGACY, systemPrompt = "keep me", maxTokens = 12000, enableThinking = false)
        val legacy = db.createConversation("legacy", old)
        val custom = db.createConversation("custom", old.copy(model = "agnes-2.5-pro"))
        val message = ChatMessage("message", legacy.id, "user", "keep history", "", emptyList(), null,
            emptyList(), MessageState.COMPLETE, null, 100L)
        db.insertMessage(message)
        val originalUpdatedAt = db.conversation(legacy.id)!!.updatedAt
        db.writableDatabase.version = 3
        db.close()
        db = AppDatabase(context, json)
        assertEquals(old.copy(model = ChatModel.DEFAULT), db.conversation(legacy.id)!!.parameters)
        assertEquals(custom.parameters, db.conversation(custom.id)!!.parameters)
        assertEquals(listOf(message), db.messages(legacy.id))
        assertEquals(originalUpdatedAt, db.conversation(legacy.id)!!.updatedAt)
        db.updateConversationParameters(legacy.id, old)
        db.close()
        db = AppDatabase(context, json)
        assertEquals(old, db.conversation(legacy.id)!!.parameters)
        db.close()
    }

    @Test
    fun `migration preserves unknown fields custom models and malformed records`() {
        val raw = """{"model":"agnes-2.5-flash","future":{"enabled":true}}"""
        assertTrue(ChatModel.upgradeLegacyParameters(raw, json).contains("\"future\":{\"enabled\":true}"))
        for (unchanged in listOf("not json", "{}", """{"model":"custom"}""")) {
            assertEquals(unchanged, ChatModel.upgradeLegacyParameters(unchanged, json))
        }
        assertEquals(ChatModel.DEFAULT, json.decodeFromString<ChatParameters>("{}").model)
    }
}
