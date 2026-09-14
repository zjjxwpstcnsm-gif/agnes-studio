package com.ppailab.agnesstudio.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ChatModel {
    const val DEFAULT = "agnes-3.0-flash"
    const val LEGACY = "agnes-2.5-flash"

    // Called only by versioned storage migrations. Preserve custom models,
    // unknown fields and the user's other conversation settings verbatim.
    fun upgradeLegacyParameters(raw: String, json: Json): String {
        val fields = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return raw
        if (fields["model"] != JsonPrimitive(LEGACY)) return raw
        return JsonObject(fields + ("model" to JsonPrimitive(DEFAULT))).toString()
    }
}
