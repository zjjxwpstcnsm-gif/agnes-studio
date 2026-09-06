package com.ppailab.agnesstudio.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ApiTrace(
    val method: String,
    val url: String,
    val statusCode: Int?,
    val durationMillis: Long,
    val responseBody: String? = null,
    val transportError: String? = null,
)

object LogSanitizer {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val secretKeys = setOf(
        "authorization", "api_key", "apikey", "access_token", "token", "secret",
    )
    private val binaryKeys = setOf("b64_json", "base64", "image_base64", "video_base64")

    fun json(value: String): String {
        val clean = runCatching { json.parseToJsonElement(value) }
            .map { sanitizeElement(it, null).toString() }
            .getOrElse { sanitizePlain(value) }
        return clean.take(MAX_LENGTH)
    }

    fun url(value: String): String = sanitizeString(value)

    fun throwable(value: String?): String? = value?.let(::sanitizePlain)?.take(MAX_LENGTH)

    private fun sanitizeElement(element: JsonElement, key: String?): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (childKey, child) ->
            when (childKey.lowercase()) {
                in secretKeys -> JsonPrimitive("<redacted>")
                in binaryKeys -> JsonPrimitive("<binary omitted>")
                else -> sanitizeElement(child, childKey)
            }
        })
        is JsonArray -> JsonArray(element.map { sanitizeElement(it, key) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(sanitizeString(element.content)) else element
        JsonNull -> JsonNull
    }

    private fun sanitizeString(value: String): String {
        if (value.startsWith("data:", ignoreCase = true)) {
            val mediaType = value.substringAfter("data:").substringBefore(';').take(80)
            return "<data URI omitted: $mediaType, ${value.length} chars>"
        }
        value.toHttpUrlOrNull()?.let { parsed ->
            if (parsed.querySize > 0 || parsed.fragment != null) {
                return parsed.newBuilder().query(null).fragment(null).build().toString() + "?<redacted>"
            }
        }
        return if (value.length > MAX_STRING_LENGTH) {
            value.take(MAX_STRING_LENGTH) + "… <${value.length - MAX_STRING_LENGTH} chars omitted>"
        } else value
    }

    private fun sanitizePlain(value: String): String = value
        .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+"), "$1<redacted>")
        .replace(Regex("(?i)(\\\"(?:api_?key|token|secret)\\\"\\s*:\\s*\\\")[^\\\"]+"), "$1<redacted>")
        .replace(Regex("data:[^;,\\s]+;base64,[A-Za-z0-9+/=_-]+"), "<data URI omitted>")

    private const val MAX_STRING_LENGTH = 4_000
    private const val MAX_LENGTH = 40_000
}
