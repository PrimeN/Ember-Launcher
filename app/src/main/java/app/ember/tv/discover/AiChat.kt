package app.ember.tv.discover

import app.ember.tv.data.AiProvider
import app.ember.tv.data.AiSettings
import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * #438: one request to whichever AI service is chosen. OpenAI-style services share a format;
 * Claude's is converted to it, so callers read `choices[0].message.content` and `finish_reason` either way.
 */
object AiChat {
    data class Request(val url: String, val body: JsonObject, val headers: Map<String, String>)

    fun request(p: AiProvider, base: String, key: String, model: String, system: String, user: String, json: Boolean, maxTokens: Int): Request {
        val auth = buildMap {
            put("Cache-Control", "no-store")
            if (p.anthropic) { put("x-api-key", key); put("anthropic-version", "2023-06-01") }
            else if (key.isNotBlank()) put("Authorization", "Bearer $key")
            if (p == AiProvider.OPENROUTER) { put("HTTP-Referer", "https://github.com/PrimeN/Ember-Launcher"); put("X-Title", "Ember") }
        }
        val body = if (p.anthropic) buildJsonObject {
            put("model", model); put("max_tokens", maxTokens); put("temperature", 0)
            put("system", system)
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", user) }) })
        } else buildJsonObject {
            put("model", model); put("temperature", 0); put("max_tokens", maxTokens)
            if (json && p.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }
        return Request(base + if (p.anthropic) "messages" else "chat/completions", body, auth)
    }

    /** Claude's reply in the OpenAI shape; finish "stop" when it ended on its own. Others pass through. */
    fun normalize(p: AiProvider, response: JsonElement): JsonElement {
        if (!p.anthropic) return response
        val text = response.arr("content").list().filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("")
        val finish = when (response.str("stop_reason")) { "end_turn", "stop_sequence" -> "stop"; "max_tokens" -> "length"; else -> response.str("stop_reason") ?: "" }
        return buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", finish)
                put("message", buildJsonObject { put("content", text) })
            }) })
        }
    }

    /** The reply's text with any Markdown fence removed, for services without a JSON mode. */
    fun jsonText(content: String): String {
        val t = content.trim()
        if (t.startsWith("{")) return t
        val start = t.indexOf('{'); val end = t.lastIndexOf('}')
        return if (start >= 0 && end > start) t.substring(start, end + 1) else t
    }

    /** Sends [system] and [user] to the chosen service and returns the reply's text. */
    suspend fun ask(system: String, user: String, json: Boolean = true, maxTokens: Int = 1200,
        post: suspend (String, JsonObject, Map<String, String>) -> JsonElement = { u, b, h -> Http.privatePost(u, b, h) }): String {
        val p = AiSettings.provider
        check(AiSettings.ready(p)) { "Choose an AI service and add its key in Discover settings." }
        val r = request(p, AiSettings.base(p), AiSettings.key(p), AiSettings.model(p), system, user, json, maxTokens)
        val reply = normalize(p, post(r.url, r.body, r.headers))
        val choice = reply.arr("choices")?.firstOrNull() ?: error("The AI service gave no answer.")
        check(choice.str("finish_reason") in setOf("stop", "end_turn", "")) { "The AI answer was cut short. Try a shorter request." }
        return choice.obj("message").str("content")?.trim()?.takeIf { it.isNotEmpty() } ?: error("The AI service gave no answer.")
    }

    /** The models the service offers, for the model picker (free ones first on OpenRouter). */
    suspend fun models(p: AiProvider): List<String> {
        val headers = buildMap {
            if (p.anthropic) { put("x-api-key", AiSettings.key(p)); put("anthropic-version", "2023-06-01") }
            else AiSettings.key(p).takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        val ids = Http.privateGet(AiSettings.base(p) + "models", headers).arr("data").list().mapNotNull { it.str("id") }
            .map { it.removePrefix("models/") }
            .filterNot { id -> listOf("embed", "tts", "whisper", "dall-e", "image", "audio", "moderation", "transcribe", "realtime", "guard").any { id.contains(it, true) } }
        return ids.sortedWith(compareBy({ !(p == AiProvider.OPENROUTER && it.endsWith(":free")) }, { it }))
    }
}
