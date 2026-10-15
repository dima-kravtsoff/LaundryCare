package com.laundrycare.ai

import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup

/** What the tag reader returns: the care profile plus what it saw of the garment itself. */
data class CareRead(
    val care: CareProfile,
    val garmentGuess: String,
    val colorGroup: ColorGroup?,
    val description: String,
    /** What the model says it saw, symbol by symbol; kept for diagnostics. */
    val transcript: String = "",
)

/** One saved garment as the matcher sees it. */
data class MatchInput(
    val index: Int,
    val name: String,
    val fabric: String,
    val notes: String,
    val description: String,
    val thumb: ByteArray?,
)

data class MatchCandidate(val index: Int, val confidence: String, val reason: String)

data class MatchResult(val candidates: List<MatchCandidate>, val noMatch: Boolean)

data class ModelInfo(val id: String, val label: String)

/** A piece of a multimodal request, before it is shaped for a particular provider. */
sealed interface Part {
    data class Text(val text: String) : Part
    class Image(val jpeg: ByteArray) : Part
}

/**
 * A failure talking to the model, worded for the person holding the phone.
 *
 * @param retryable whether trying again a moment later could help (rate limits, overload, network).
 * @param nextModel whether the failure is about the model rather than the request: retired, or out of quota.
 * @param model the model id the failure is about, when it is about one.
 * @param retryAfterSeconds for a spent quota, how long until it refills; null for anything else.
 * @param badKey the key itself was refused, so every other call with it will fail the same way.
 */
class AiException(
    message: String,
    val code: Int? = null,
    val retryable: Boolean = true,
    val nextModel: Boolean = false,
    val model: String? = null,
    val retryAfterSeconds: Long? = null,
    val badKey: Boolean = false,
) : Exception(message)

/** Both features, provider-agnostic. Implementations: [GeminiModel], [ClaudeModel]. */
interface VisionModel {
    /**
     * @param tagPhotos the whole label(s), for the text and the overall layout.
     * @param symbolZooms the row of symbols cut out of each tag photo at full
     *        resolution and enlarged, so dots and bars can actually be counted.
     */
    suspend fun readTag(tagPhotos: List<ByteArray>, symbolZooms: List<ByteArray>, garmentPhotos: List<ByteArray>): CareRead

    /** Where the row of care symbols is in a tag photo: [ymin, xmin, ymax, xmax] on a 0–1000 scale, or null if not found. */
    suspend fun locateSymbols(tagPhoto: ByteArray): IntArray?
    suspend fun match(query: ByteArray, closet: List<MatchInput>): MatchResult
    suspend fun listModels(): List<ModelInfo>
}
