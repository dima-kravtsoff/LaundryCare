package com.laundrycare.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Claude via the Messages API, asking for the answer as JSON that matches the
 * schema (structured outputs), so the reply never needs prose stripped off it.
 *
 * Refusal fallbacks are on: should a safety classifier decline a request, the
 * API re-runs it on Anthropic's recommended fallback model inside the same call.
 * If an account does not accept that parameter, the request is retried once
 * without it rather than failing the feature.
 */
class ClaudeModel(
    private val apiKey: String,
    private val model: String,
    private val onModelSwitched: (String) -> Unit = {},
) : VisionModel {

    override suspend fun readTag(tagPhotos: List<ByteArray>, symbolZooms: List<ByteArray>, garmentPhotos: List<ByteArray>): CareRead =
        withContext(Dispatchers.IO) {
            CarePrompts.parseCare(generate(CarePrompts.readTagParts(tagPhotos, garmentPhotos, symbolZooms), CarePrompts.careSchema.toJsonSchema(), effort = "high"))
        }

    override suspend fun locateSymbols(tagPhoto: ByteArray): IntArray? = withContext(Dispatchers.IO) {
        CarePrompts.parseBox(generate(CarePrompts.locateParts(tagPhoto), CarePrompts.locateSchema.toJsonSchema(), effort = "low"))
    }

    override suspend fun match(query: ByteArray, closet: List<MatchInput>): MatchResult =
        withContext(Dispatchers.IO) {
            if (closet.isEmpty()) throw AiException("Your closet is empty. Add a garment first.", retryable = false)
            CarePrompts.parseMatch(generate(CarePrompts.matchParts(query, closet), CarePrompts.matchSchema.toJsonSchema(), effort = "medium"), closet.size)
        }

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("Add your Claude API key in Settings first.", retryable = false)
        val request = Request.Builder()
            .url("$ENDPOINT/models?limit=100")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", VERSION)
            .get()
            .build()
        val (code, payload) = send(request)
        if (code !in 200..299) throw explain(code, payload, null)
        Json.parseToJsonElement(payload).obj()?.get("data").arr().orEmpty().mapNotNull { el ->
            val m = el.obj() ?: return@mapNotNull null
            val id = m["id"].str()?.takeIf { it.startsWith("claude") } ?: return@mapNotNull null
            ModelInfo(id, m["display_name"].str().orEmpty())
        }
    }

    /** The request as sent, for tests. */
    internal fun body(parts: List<Part>, schema: JsonObject, model: String, withFallbacks: Boolean, effort: String = "medium"): String = buildJsonObject {
        put("model", model)
        put("max_tokens", MAX_TOKENS)
        if (withFallbacks) put("fallbacks", "default")
        putJsonObject("output_config") {
            put("effort", effort)
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", schema)
            }
        }
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    parts.forEach { p ->
                        add(
                            when (p) {
                                is Part.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
                                is Part.Image -> buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", base64(p.jpeg))
                                    }
                                }
                            }
                        )
                    }
                }
            })
        }
    }.toString()

    private suspend fun generate(parts: List<Part>, schema: JsonObject, effort: String): JsonObject {
        if (apiKey.isBlank()) throw AiException("Add your Claude API key in Settings first.", retryable = false)
        try {
            return call(model, parts, schema, withFallbacks = true, effort = effort)
        } catch (e: AiException) {
            val msg = e.message.orEmpty()
            if (e.code == 400 && (msg.contains("fallback", ignoreCase = true) || msg.contains("beta", ignoreCase = true))) {
                return call(model, parts, schema, withFallbacks = false, effort = effort)
            }
            if (e.code == 404) {
                val alternative = runCatching { listModels() }.getOrDefault(emptyList()).map { it.id }
                    .let { ids -> ids.firstOrNull { it.startsWith("claude-opus") } ?: ids.firstOrNull { it.startsWith("claude-sonnet") } ?: ids.firstOrNull() }
                if (alternative != null && alternative != model) {
                    val out = call(alternative, parts, schema, withFallbacks = true, effort = effort)
                    onModelSwitched(alternative)
                    return out
                }
            }
            throw e
        }
    }

    private suspend fun call(model: String, parts: List<Part>, schema: JsonObject, withFallbacks: Boolean, effort: String): JsonObject {
        val builder = Request.Builder()
            .url("$ENDPOINT/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", VERSION)
            .addHeader("Content-Type", "application/json")
        if (withFallbacks) builder.addHeader("anthropic-beta", FALLBACK_BETA)
        val request = builder.post(body(parts, schema, model, withFallbacks, effort).toRequestBody(JSON_MEDIA)).build()
        val (code, payload) = send(request)
        android.util.Log.i("ClosetCare", "Claude $model effort=$effort -> HTTP $code, ${payload.length} chars")
        if (code !in 200..299) throw explain(code, payload, model)
        val root = Json.parseToJsonElement(payload).obj() ?: throw AiException("Claude returned something that is not JSON.")
        when (root["stop_reason"].str()) {
            "refusal" -> throw AiException("Claude declined to read this image.", retryable = false)
            "max_tokens" -> throw AiException("The answer ran past its length limit and came back cut off. Try fewer photos.", retryable = false)
        }
        val text = root["content"].arr().orEmpty()
            .mapNotNull { it.obj() }
            .filter { it["type"].str() == "text" }
            .mapNotNull { it["text"].str() }
            .joinToString("\n")
        if (text.isBlank()) throw AiException("Claude returned no text. Try a clearer photo.")
        return CarePrompts.extractJson(text)
    }

    private suspend fun send(request: Request): Pair<Int, String> {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw networkError(e)
        }
        return response.use { it.code to (it.body?.string().orEmpty()) }
    }

    private fun explain(code: Int, payload: String, model: String?): AiException {
        val error = runCatching { Json.parseToJsonElement(payload).obj()?.get("error").obj() }.getOrNull()
        val message = error?.get("message").str()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
        val text = when (code) {
            401 -> "Claude rejected the API key. Check it in Settings. $message"
            403 -> "This key is not allowed to do that (403). $message"
            404 -> "${model ?: "That model"} was not found. $message"
            429 -> "Claude rate limit reached. Wait a moment and try again. $message"
            529 -> "Claude is overloaded right now. Try again in a moment."
            400 -> "Claude rejected the request: $message"
            else -> "Claude error $code: $message"
        }
        return AiException(text, code = code, retryable = code == 429 || code >= 500, nextModel = code == 404, badKey = code == 401 || code == 403)
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1"
        private const val VERSION = "2023-06-01"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        /** Thinking tokens count against this too; the JSON itself is a few hundred tokens. */
        private const val MAX_TOKENS = 16000
        const val DEFAULT_MODEL = "claude-opus-5-5"
    }
}
