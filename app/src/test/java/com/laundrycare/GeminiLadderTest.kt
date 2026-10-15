package com.laundrycare

import com.laundrycare.ai.AiException
import com.laundrycare.ai.GeminiModel
import com.laundrycare.ai.ModelInfo
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The model ladder against a fake Gemini. What matters here is the policy:
 * which models are asked in what order, which failures move on, and which
 * ones change the saved default.
 */
class GeminiLadderTest {

    private val server = MockWebServer()
    private val calls = mutableListOf<String>()
    private val switched = mutableListOf<String>()
    private val fallbacks = mutableListOf<Pair<String, String>>()
    private val listed = mutableListOf<List<ModelInfo>>()

    /** model id → response, or null for a 404. */
    private var answers: Map<String, MockResponse> = emptyMap()
    private var modelsListing: List<String> = emptyList()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/v1beta/models?")) {
                    val body = modelsListing.joinToString(",") { """{"name":"models/$it","displayName":"$it","supportedGenerationMethods":["generateContent"]}""" }
                    return MockResponse().setResponseCode(200).setBody("""{"models":[$body]}""")
                }
                val model = Regex("/v1beta/models/([^:]+):generateContent").find(path)?.groupValues?.get(1) ?: return MockResponse().setResponseCode(500)
                calls += model
                return answers[model] ?: notFound(model)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun model(preferred: String, known: Set<String> = emptySet(), key: String = "k") = GeminiModel(
        apiKey = key, preferredModel = preferred, knownModels = known,
        onModelSwitched = { switched += it },
        onFallback = { used, reason -> fallbacks += used to reason },
        onModelsListed = { listed += it },
        endpoint = server.url("/v1beta").toString(),
    )

    private fun ok(json: String) = MockResponse().setResponseCode(200)
        .setBody("""{"candidates":[{"content":{"parts":[{"text":${jsonString(json)}}]},"finishReason":"STOP"}]}""")

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun notFound(m: String) = MockResponse().setResponseCode(404)
        .setBody("""{"error":{"code":404,"message":"models/$m is not found for API version v1beta","status":"NOT_FOUND"}}""")

    private fun dailyQuota(m: String, retrySeconds: Long = 65751, limit: Int = 20) = MockResponse().setResponseCode(429).setBody(
        """{"error":{"code":429,"message":"You exceeded your current quota.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: $limit, model: $m\nPlease retry in 18h15m51.65s.","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests","quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier","quotaDimensions":{"location":"global","model":"$m"},"quotaValue":"$limit"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"${retrySeconds}s"}]}}""",
    )

    private fun perMinute(m: String) = MockResponse().setResponseCode(429).setBody(
        """{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier","quotaDimensions":{"model":"$m"}}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"23s"}]}}""",
    )

    private val box = """{"found":true,"ymin":600,"xmin":100,"ymax":760,"xmax":900}"""

    @Test
    fun `a spent daily quota steps down the ladder for this request only`() = runBlocking {
        answers = mapOf("gemini-3.8-flash" to dailyQuota("gemini-3.8-flash"), "gemini-3.7-flash" to ok(box))
        val result = model("gemini-3.8-flash").locateSymbols(ByteArray(4))
        assertEquals(listOf(600, 100, 760, 900), result!!.toList())
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.7-flash"), calls)
        assertTrue("a quota fallback must not change the saved model", switched.isEmpty())
        assertEquals("gemini-3.7-flash", fallbacks.single().first)
        assertTrue(fallbacks.single().second, fallbacks.single().second.contains("daily quota for gemini-3.8-flash"))
        assertTrue(fallbacks.single().second, fallbacks.single().second.contains("resets in about 18 h"))
    }

    @Test
    fun `a retired model is replaced and the replacement saved`() = runBlocking {
        answers = mapOf("gemini-3.7-flash" to ok(box))
        model("gemini-3.8-flash").locateSymbols(ByteArray(4))
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.7-flash"), calls)
        assertEquals(listOf("gemini-3.7-flash"), switched)
        assertTrue(fallbacks.isEmpty())
    }

    @Test
    fun `the Pro rung is only tried when the key is known to offer it, and a zero quota on it is skipped silently`() = runBlocking {
        val tag = listOf(ByteArray(4))
        val care = """{"machine_wash":true,"wash_temp_c":30,"low_confidence":[]}"""

        // Unknown key: no Pro guesswork, no wasted upload.
        answers = mapOf("gemini-3.8-flash" to ok(care))
        model("gemini-3.8-flash").readTag(tag, emptyList(), emptyList())
        assertEquals(listOf("gemini-3.8-flash"), calls)
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-flash-latest"), model("gemini-3.8-flash").rungs(preferPro = true))

        // Known to exist: tried first; a free key's zero quota on it is just a step down to the preferred Flash.
        calls.clear()
        answers = mapOf("gemini-3.8-pro" to dailyQuota("gemini-3.8-pro", limit = 0), "gemini-3.8-flash" to ok(care))
        val read = model("gemini-3.8-flash", known = setOf("gemini-3.8-pro", "gemini-3.8-flash")).readTag(tag, emptyList(), emptyList())
        assertEquals(30, read.care.washTempC)
        assertEquals(listOf("gemini-3.8-pro", "gemini-3.8-flash"), calls)
        assertTrue(switched.isEmpty())
        assertTrue("the preferred model answered, so nobody needs telling", fallbacks.isEmpty())
    }

    @Test
    fun `when the whole ladder is spent the key is asked and the best unknown model used`() = runBlocking {
        answers = GeminiModel.FALLBACK_MODELS.associateWith { dailyQuota(it) } + ("gemini-9-flash" to ok(box))
        modelsListing = listOf("gemini-9-flash", "gemini-9-pro", "gemini-embedding-001")
        val result = model("gemini-3.8-flash").locateSymbols(ByteArray(4))
        assertNotNull(result)
        assertEquals("gemini-9-flash", calls.last())
        assertEquals(GeminiModel.FALLBACK_MODELS.size + 1, calls.size)
        assertEquals(listOf("gemini-9-flash", "gemini-9-pro"), listed.single().map { it.id })
        assertTrue(switched.isEmpty())
        assertEquals("gemini-9-flash", fallbacks.single().first)
    }

    @Test
    fun `when nothing answers the error lists every model and is not retryable for spent daily quotas`() = runBlocking {
        answers = GeminiModel.FALLBACK_MODELS.associateWith { dailyQuota(it) }
        modelsListing = emptyList()
        try {
            model("gemini-3.8-flash").locateSymbols(ByteArray(4))
            fail("expected an AiException")
        } catch (e: AiException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!, e.message!!.contains("gemini-3.8-flash"))
            assertTrue(e.message!!, e.message!!.contains("gemini-flash-latest"))
            assertTrue(e.message!!, e.message!!.contains("Check key"))
        }
    }

    @Test
    fun `a per-minute limit on one model moves to the next, and is retryable if every model is busy`() = runBlocking {
        answers = mapOf("gemini-3.8-flash" to perMinute("gemini-3.8-flash"), "gemini-3.7-flash" to ok(box))
        assertNotNull(model("gemini-3.8-flash").locateSymbols(ByteArray(4)))
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.7-flash"), calls)
        assertTrue(fallbacks.single().second.contains("rate limit"))

        calls.clear()
        answers = GeminiModel.FALLBACK_MODELS.associateWith { perMinute(it) }
        try {
            model("gemini-3.8-flash").locateSymbols(ByteArray(4))
            fail()
        } catch (e: AiException) {
            assertTrue("every model busy for a minute is worth retrying", e.retryable)
        }
    }

    @Test
    fun `a bad request or bad key stops immediately instead of walking the ladder`() = runBlocking {
        answers = mapOf(
            "gemini-3.8-flash" to MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""),
        )
        try {
            model("gemini-3.8-flash").locateSymbols(ByteArray(4))
            fail()
        } catch (e: AiException) {
            assertEquals(400, e.code)
            assertFalse(e.nextModel)
            assertTrue(e.badKey)
            assertTrue(e.message!!.contains("rejected the API key"))
        }
        assertEquals(listOf("gemini-3.8-flash"), calls)
    }

    /** The reply Google gave in October 2026 to an AQ. key with one character too many, and to a made-up one. */
    @Test
    fun `an AQ key Google refuses gets advice, not Google's OAuth wording`() = runBlocking {
        answers = mapOf(
            "gemini-3.8-flash" to MockResponse().setResponseCode(401).setBody(
                """{"error":{"code":401,"message":"Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential. See https://developers.google.com/identity/sign-in/web/devconsole-project.","status":"UNAUTHENTICATED","details":[
                    {"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_TYPE_UNSUPPORTED","metadata":{"method":"google.ai.generativelanguage.v1beta.GenerativeService.GenerateContent","service":"generativelanguage.googleapis.com"}}]}}""",
            ),
        )
        try {
            model("gemini-3.8-flash", key = "AQ.Ab8test").locateSymbols(ByteArray(4))
            fail()
        } catch (e: AiException) {
            assertEquals(401, e.code)
            assertTrue(e.badKey)
            assertFalse(e.retryable)
            assertFalse(e.nextModel)
            val m = e.message!!
            assertTrue(m.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED"))
            assertTrue(m.contains("stray or missing character"))
            assertTrue(m.contains("AI Studio"))
            assertFalse(m.contains("OAuth"))
        }
        assertEquals(listOf("gemini-3.8-flash"), calls)
    }

    @Test
    fun `a blocked prompt, an empty answer and a cut-off answer are reported plainly`() = runBlocking {
        answers = mapOf("gemini-3.8-flash" to MockResponse().setResponseCode(200).setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try { model("gemini-3.8-flash").locateSymbols(ByteArray(4)); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("declined")); assertFalse(e.retryable) }

        answers = mapOf("gemini-3.8-flash" to MockResponse().setResponseCode(200).setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"found\":","thought":false}]},"finishReason":"MAX_TOKENS"}]}"""))
        try { model("gemini-3.8-flash").locateSymbols(ByteArray(4)); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("cut off")) }

        // Thought parts are not the answer; the real text after them is.
        answers = mapOf("gemini-3.8-flash" to MockResponse().setResponseCode(200).setBody("""{"candidates":[{"content":{"parts":[{"text":"let me think","thought":true},{"text":${jsonString(box)}}]},"finishReason":"STOP"}]}"""))
        assertNotNull(model("gemini-3.8-flash").locateSymbols(ByteArray(4)))

        // found=false is a legitimate "no symbols here".
        answers = mapOf("gemini-3.8-flash" to ok("""{"found":false,"ymin":0,"xmin":0,"ymax":0,"xmax":0}"""))
        assertNull(model("gemini-3.8-flash").locateSymbols(ByteArray(4)))
    }

    @Test
    fun `the request carries the key in a header, never in the URL`() = runBlocking {
        answers = mapOf("gemini-3.8-flash" to ok(box))
        model("gemini-3.8-flash").locateSymbols(ByteArray(4))
        val req = server.takeRequest()
        assertEquals("k", req.getHeader("x-goog-api-key"))
        assertFalse(req.path!!.contains("key="))
        assertTrue(req.body.readUtf8().contains("MEDIA_RESOLUTION_HIGH"))
    }

    @Test
    fun `durations read like a person would say them`() {
        assertEquals("18 h", GeminiModel.humanDuration(65751))
        assertEquals("2 h", GeminiModel.humanDuration(5400))
        assertEquals("23 min", GeminiModel.humanDuration(1400))
        assertEquals("a minute", GeminiModel.humanDuration(23))
    }
}
