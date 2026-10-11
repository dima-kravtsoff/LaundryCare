package com.laundrycare

import com.laundrycare.ai.GeminiModel
import com.laundrycare.ai.MatchInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Talks to the real Gemini API. Skipped unless two environment variables are
 * set, so the normal test run never spends quota or needs a network:
 *
 *   GEMINI_API_KEY        the key
 *   LAUNDRY_TAG_PHOTO     path to a JPEG of a care label
 *   LAUNDRY_GARMENT_PHOTO optional, a JPEG of the garment itself
 *
 * It exercises the real client end to end: ladder, schema, parsing.
 */
class GeminiLiveTest {

    private val key = System.getenv("GEMINI_API_KEY").orEmpty()
    private val tagPath = System.getenv("LAUNDRY_TAG_PHOTO").orEmpty()
    private val garmentPath = System.getenv("LAUNDRY_GARMENT_PHOTO").orEmpty()
    private val preferred = System.getenv("GEMINI_MODEL")?.takeIf { it.isNotBlank() } ?: "gemini-3.8-flash"

    private fun live() = assumeTrue("set GEMINI_API_KEY and LAUNDRY_TAG_PHOTO to run", key.isNotBlank() && File(tagPath).isFile)

    private val events = mutableListOf<String>()

    private fun model(known: Set<String> = emptySet()) = GeminiModel(
        apiKey = key, preferredModel = preferred, knownModels = known,
        onModelSwitched = { events += "switched→$it" },
        onFallback = { used, why -> events += "fallback→$used ($why)" },
        onModelsListed = { events += "listed ${it.size}" },
    )

    @Test
    fun `the key lists vision models, best first`() = runBlocking {
        live()
        val models = model().listModels()
        println("models: " + models.joinToString { it.id })
        assertTrue(models.isNotEmpty())
        assertTrue(models.first().id.contains("flash"))
    }

    @Test
    fun `a real tag is located and read`() = runBlocking {
        live()
        val tag = File(tagPath).readBytes()
        val m = model()
        val box = m.locateSymbols(tag)
        println("box: ${box?.toList()}  events: $events")
        val garment = File(garmentPath).takeIf { it.isFile }?.readBytes()
        val read = m.readTag(listOf(tag), emptyList(), listOfNotNull(garment))
        println("transcript: ${read.transcript}")
        println("guess: ${read.garmentGuess} / ${read.colorGroup} / ${read.description}")
        println("care: ${read.care}")
        println("events: $events")
        assertNotNull(read.care)
        assertTrue("a care label should produce some wash info", read.care.machineWash || read.care.handWash || read.care.doNotWash)
    }

    @Test
    fun `the matcher answers with closet indexes only`() = runBlocking {
        live()
        val garment = File(garmentPath).takeIf { it.isFile }?.readBytes() ?: File(tagPath).readBytes()
        val closet = listOf(
            MatchInput(0, "Red Tommy Hilfiger crewneck t-shirt", "100% cotton", "", "Red short-sleeve crewneck t-shirt with a navy collar and a white and red flag chest stripe", null),
            MatchInput(1, "Black embroidered stripe t-shirt", "100% cotton", "", "Black short-sleeve crewneck t-shirt with a white and red horizontal chest stripe", null),
            MatchInput(2, "Grey wool sweater", "merino", "", "Grey fine-knit crewneck sweater", null),
        )
        val result = model().match(garment, closet)
        println("match: $result  events: $events")
        assertTrue(result.candidates.all { it.index in 0..2 })
    }
}
