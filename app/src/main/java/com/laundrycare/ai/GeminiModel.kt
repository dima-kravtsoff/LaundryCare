package com.laundrycare.ai

import kotlinx.coroutines.CancellationException
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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Gemini via generateContent, with the key in the x-goog-api-key header rather
 * than the URL (a secret in a URL ends up in proxy logs).
 *
 * Model ids are retired faster than this app is rebuilt, and a retired id is a
 * 404 that no retry fixes. Free-tier quotas are also small and charged per
 * model (20 requests a day on a Flash model at the time of writing). So the
 * configured model is only the first rung of a ladder: when it is retired or
 * out of quota, the next model is tried, and if the whole ladder is gone the
 * key is asked what it can run.
 *
 * Only a *retired* model (404) changes the saved default, through
 * [onModelSwitched]. A quota fallback is for this request only: tomorrow the
 * quota is back and the better model should be used again. [onFallback] tells
 * the person which model actually answered and why.
 */
class GeminiModel(
    private val apiKey: String,
    private val preferredModel: String,
    /** Model ids the key was last seen to offer. The Pro rung is only tried when it is in here. */
    private val knownModels: Set<String> = emptySet(),
    private val onModelSwitched: (String) -> Unit = {},
    /** Another model took this one request. `reason` says why the preferred one could not. */
    private val onFallback: (used: String, reason: String) -> Unit = { _, _ -> },
    /** The key was asked what it can run; the answer is worth remembering. */
    private val onModelsListed: (List<ModelInfo>) -> Unit = {},
    private val endpoint: String = ENDPOINT,
) : VisionModel {

    override suspend fun readTag(tagPhotos: List<ByteArray>, symbolZooms: List<ByteArray>, garmentPhotos: List<ByteArray>): CareRead =
        withContext(Dispatchers.IO) {
            CarePrompts.parseCare(generate(CarePrompts.readTagParts(tagPhotos, garmentPhotos, symbolZooms), CarePrompts.careSchema.toGemini(), preferPro = true))
        }

    override suspend fun locateSymbols(tagPhoto: ByteArray): IntArray? = withContext(Dispatchers.IO) {
        CarePrompts.parseBox(generate(CarePrompts.locateParts(tagPhoto), CarePrompts.locateSchema.toGemini()))
    }

    override suspend fun match(query: ByteArray, closet: List<MatchInput>): MatchResult =
        withContext(Dispatchers.IO) {
            if (closet.isEmpty()) throw AiException("Your closet is empty. Add a garment first.", retryable = false)
            CarePrompts.parseMatch(generate(CarePrompts.matchParts(query, closet), CarePrompts.matchSchema.toGemini()), closet.size)
        }

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("Add your Gemini API key in Settings first.", retryable = false)
        val request = Request.Builder()
            .url("$endpoint/models?pageSize=1000")
            .addHeader("x-goog-api-key", apiKey)
            .get()
            .build()
        val (code, payload) = send(request)
        if (code !in 200..299) throw explain(code, payload, null)
        val models = Json.parseToJsonElement(payload).obj()?.get("models").arr().orEmpty()
        models.mapNotNull { el ->
            val m = el.obj() ?: return@mapNotNull null
            val methods = m["supportedGenerationMethods"].arr().orEmpty().mapNotNull { it.str() }
            if ("generateContent" !in methods) return@mapNotNull null
            val id = m["name"].str()?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (EXCLUDED.containsMatchIn(id)) return@mapNotNull null
            ModelInfo(id, m["displayName"].str().orEmpty())
        }.sortedByDescending { score(it.id) }.also { onModelsListed(it) }
    }

    /** The request as sent, for tests. */
    internal fun body(parts: List<Part>, schema: JsonObject, highRes: Boolean = true): String = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    parts.forEach { p ->
                        add(
                            when (p) {
                                is Part.Text -> buildJsonObject { put("text", p.text) }
                                is Part.Image -> buildJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64(p.jpeg))
                                    }
                                }
                            }
                        )
                    }
                }
            })
        }
        putJsonObject("generationConfig") {
            put("temperature", 0)
            put("response_mime_type", "application/json")
            put("response_schema", schema)
            // More image tokens per picture: dots on a care label are tiny.
            if (highRes) put("mediaResolution", "MEDIA_RESOLUTION_HIGH")
        }
    }.toString()

    /**
     * @param preferPro try the Pro sibling of a Flash model first, if the key
     *        is known to offer it. Reading dots and bars on a label is the one
     *        job where the stronger eye earns its price; it is never saved as
     *        the default, and a Pro model that is out of quota (free keys get
     *        none) is simply skipped.
     */
    private suspend fun generate(parts: List<Part>, schema: JsonObject, preferPro: Boolean = false): JsonObject {
        if (apiKey.isBlank()) throw AiException("Add your Gemini API key in Settings first.", retryable = false)
        return try {
            ladder(body(parts, schema, highRes = true).toRequestBody(JSON_MEDIA), preferPro)
        } catch (e: AiException) {
            // An older API surface that does not know the field: ask again without it.
            if (e.code == 400 && e.message.orEmpty().contains("media", ignoreCase = true)) {
                ladder(body(parts, schema, highRes = false).toRequestBody(JSON_MEDIA), preferPro)
            } else throw e
        }
    }

    /** The models tried for one request, in order. Exposed for tests. */
    internal fun rungs(preferPro: Boolean): List<String> {
        val pro = if (preferPro) proSibling(preferredModel)?.takeIf { it in knownModels } else null
        return listOfNotNull(pro, preferredModel) + FALLBACK_MODELS.filter { it != preferredModel && it != pro }
    }

    private suspend fun ladder(body: RequestBody, preferPro: Boolean): JsonObject {
        val rungs = rungs(preferPro)
        val pro = rungs.first().takeIf { it != preferredModel }
        val failures = mutableListOf<AiException>()
        var preferredRetired = false

        suspend fun answered(model: String, out: JsonObject): JsonObject {
            if (model != preferredModel && model != pro) {
                if (preferredRetired) onModelSwitched(model)
                else onFallback(model, failures.firstOrNull { it.model == preferredModel }?.message ?: "$preferredModel did not answer")
            }
            return out
        }

        for (model in rungs) {
            try {
                return answered(model, call(model, body))
            } catch (e: AiException) {
                if (!e.nextModel) throw e
                if (model == preferredModel && e.code == 404) preferredRetired = true
                failures += e
            }
        }
        // Every id in the ladder is retired or spent. Ask what the key can reach.
        val available = try {
            listModels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val best = available.map { it.id }.filter { it !in rungs }.maxByOrNull { score(it) }
        if (best != null) {
            try {
                return answered(best, call(best, body))
            } catch (e: AiException) {
                if (!e.nextModel) throw e
                failures += e
            }
        }
        val transient = failures.any { it.code == 429 && it.retryAfterSeconds == null }
        throw AiException(
            buildString {
                append("No Gemini model could take this request.\n")
                failures.forEach { append("\n• ").append(it.message) }
                append("\n\nOpen Settings → Check key to see which models your key can use.")
            },
            code = failures.lastOrNull()?.code, retryable = transient,
        )
    }

    private suspend fun call(model: String, body: RequestBody): JsonObject {
        val request = Request.Builder()
            .url("$endpoint/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        val started = System.currentTimeMillis()
        val (code, payload) = send(request)
        android.util.Log.i("ClosetCare", "Gemini $model -> HTTP $code, ${payload.length} chars, ${System.currentTimeMillis() - started} ms")
        if (code !in 200..299) throw explain(code, payload, model)
        return extract(Json.parseToJsonElement(payload).obj() ?: throw AiException("Gemini returned something that is not JSON."))
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
        val root = runCatching { Json.parseToJsonElement(payload).obj() }.getOrNull()
        val error = root?.get("error").obj()
        val message = error?.get("message").str()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
        val details = error?.get("details").arr().orEmpty().mapNotNull { it.obj() }
        val quotaIds = details.flatMap { d -> d["violations"].arr().orEmpty().mapNotNull { it.obj()?.get("quotaId").str() } }
        val daily = quotaIds.any { it.contains("PerDay", ignoreCase = true) } || message.contains("PerDay", ignoreCase = true)
        val noQuota = Regex("limit: 0\\b").containsMatchIn(message)
        val retryAfter = details.firstNotNullOfOrNull { d -> d["retryDelay"].str() }
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?: Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?", RegexOption.IGNORE_CASE).find(message)?.let { m ->
                val h = m.groupValues[1].toLongOrNull() ?: 0L
                val min = m.groupValues[2].toLongOrNull() ?: 0L
                val s = m.groupValues[3].toDoubleOrNull()?.toLong() ?: 0L
                (h * 3600 + min * 60 + s).takeIf { it > 0 }
            }
        val who = model ?: "That model"
        val text = when {
            code == 400 && message.contains("API key not valid", ignoreCase = true) ->
                "Gemini rejected the API key. Check it in Settings; a truncated paste is the usual cause."
            code == 401 -> "Gemini rejected the key: $message"
            code == 403 -> "Your key was refused (403). The Generative Language API may not be enabled for its project, or the key is restricted. $message"
            code == 404 -> "$who is not available to this key (retired or renamed)."
            code == 429 && noQuota -> "$who has no free-tier quota on this key."
            code == 429 && (daily || (retryAfter ?: 0) > 600) ->
                "The daily quota for $who is used up" + (retryAfter?.let { "; it resets in about ${humanDuration(it)}" } ?: "") + "."
            code == 429 -> "Gemini rate limit reached on $who. Wait a moment and try again."
            code == 503 -> "Gemini is overloaded right now. Try again in a moment."
            else -> "Gemini error $code: ${message.lineSequence().first()}"
        }
        return AiException(
            text, code = code,
            retryable = code == 429 || code >= 500,
            // Another model has its own quota, so any 429 is worth a step down the ladder.
            nextModel = code == 404 || code == 429,
            model = model,
            retryAfterSeconds = if (code == 429 && (daily || noQuota || (retryAfter ?: 0) > 600)) (retryAfter ?: 24 * 3600L) else null,
        )
    }

    private fun extract(root: JsonObject): JsonObject {
        root["promptFeedback"].obj()?.get("blockReason").str()?.let {
            throw AiException("Gemini declined to read this image ($it).", retryable = false)
        }
        val candidate = root["candidates"].arr()?.firstOrNull().obj()
            ?: throw AiException("Gemini returned nothing. Try a flatter, brighter photo.")
        val parts = candidate["content"].obj()?.get("parts").arr().orEmpty()
        val text = parts.mapNotNull { it.obj() }
            // Thinking models emit reasoning parts; those are not the answer.
            .filterNot { it["thought"].bool() == true }
            .mapNotNull { it["text"].str()?.takeIf { t -> t.isNotBlank() } }
            .joinToString("\n")
        if (candidate["finishReason"].str() == "MAX_TOKENS") {
            throw AiException("The answer ran past its length limit and came back cut off. Try fewer photos.", retryable = false)
        }
        if (text.isBlank()) {
            throw AiException("Gemini returned no readable text (${candidate["finishReason"].str() ?: "empty reply"}). Try a clearer photo.")
        }
        return CarePrompts.extractJson(text)
    }

    companion object {
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"

        /**
         * Best first, then down: every one a Flash-class model that takes an
         * image, which is the shape this job wants. Checked October 2026;
         * when one goes, the ladder walks past it. The `-latest` alias at the
         * end is Google's own pointer to a current Flash, so it outlives the
         * numbered ids.
         */
        val FALLBACK_MODELS = listOf(
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash",
            "gemini-flash-latest",
        )

        private val EXCLUDED = Regex("embed|aqa|imagen|veo|tts|audio|image-generation|learnlm|live|native-audio|-image|banana|lyria|transcribe|robotics|computer-use|antigravity|deep-research|gemma|omni", RegexOption.IGNORE_CASE)

        /** "gemini-3.6-flash" → "gemini-3.6-pro"; null when the id is not a plain Flash. */
        fun proSibling(id: String): String? =
            Regex("^(gemini-[0-9]+(?:[.][0-9]+)?)-flash$").find(id)?.groupValues?.get(1)?.let { "$it-pro" }

        /** Prefer the newest plain Flash: fast, cheap, vision-capable, stable. */
        fun score(id: String): Double {
            var s = 0.0
            val version = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            s += version * 10
            if (Regex("flash", RegexOption.IGNORE_CASE).containsMatchIn(id)) s += 30
            else if (Regex("pro", RegexOption.IGNORE_CASE).containsMatchIn(id)) s += 15
            if (Regex("lite", RegexOption.IGNORE_CASE).containsMatchIn(id)) s -= 8
            if (Regex("preview|exp|experimental|thinking", RegexOption.IGNORE_CASE).containsMatchIn(id)) s -= 12
            if (Regex("latest", RegexOption.IGNORE_CASE).containsMatchIn(id)) s -= 2
            return s
        }

        /** "65751" seconds → "18 h"; short waits in minutes. */
        fun humanDuration(seconds: Long): String = when {
            seconds >= 5400 -> "${(seconds + 1800) / 3600} h"
            seconds >= 90 -> "${(seconds + 30) / 60} min"
            else -> "a minute"
        }
    }
}
