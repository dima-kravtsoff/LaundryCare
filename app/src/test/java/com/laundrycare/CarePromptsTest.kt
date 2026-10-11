package com.laundrycare

import com.laundrycare.ai.CarePrompts
import com.laundrycare.ai.ClaudeModel
import com.laundrycare.ai.GeminiModel
import com.laundrycare.ai.MatchInput
import com.laundrycare.ai.toGemini
import com.laundrycare.ai.toJsonSchema
import com.laundrycare.data.Bleach
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.GarmentKind
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePromptsTest {

    private val reply = """
        {"garment_guess":"Navy merino crewneck","visual_description":"Navy fine-knit crewneck sweater with ribbed cuffs","color_group":"dark",
         "garment_kind":"jumper","fabric":"100% merino wool","machine_wash":false,"hand_wash":true,"do_not_wash":false,
         "wash_temp_c":30,"wash_action":"gentle","bleach":"none","tumble_dry":false,"do_not_tumble_dry":true,"tumble_heat":null,
         "natural_dry":"flat","iron":"low","dry_clean":"pce","wash_separately":false,"special_notes":"Reshape whilst damp",
         "low_confidence":["wash_action","iron","bogus_field"]}
    """.trimIndent()

    @Test
    fun `parses a full reply including nulls and unknown low-confidence names`() {
        val read = CarePrompts.parseCare(CarePrompts.extractJson(reply))
        assertEquals("Navy merino crewneck", read.garmentGuess)
        assertEquals(ColorGroup.DARK, read.colorGroup)
        assertTrue(read.description.startsWith("Navy fine-knit"))
        val c = read.care
        assertEquals("100% merino wool", c.fabric)
        assertTrue(c.handWashOnly)
        assertEquals(30, c.washTempC)
        assertEquals(WashAction.GENTLE, c.washAction)
        assertEquals(Bleach.NONE, c.bleach)
        assertTrue(c.doNotTumbleDry)
        assertNull(c.tumbleHeat)
        assertEquals(GarmentKind.JUMPER, c.garmentKind)
        assertEquals(listOf("wash_action", "iron"), c.lowConfidence)
        assertEquals(listOf("Wash action (bars under the tub)", "Ironing"), c.unsureLabels)
    }

    @Test
    fun `strips code fences and prose around the JSON`() {
        val j = CarePrompts.extractJson("Here you go:\n```json\n{\"machine_wash\": true, \"tumble_heat\": \"medium\"}\n```")
        val c = CarePrompts.parseCare(j).care
        assertTrue(c.machineWash)
        assertEquals(TumbleHeat.MEDIUM, c.tumbleHeat)
    }

    @Test
    fun `match parse drops bad indexes, duplicates and caps at three`() {
        val j = CarePrompts.extractJson(
            """{"candidates":[{"index":2,"confidence":"high","reason":"a"},{"index":2,"confidence":"low","reason":"dup"},
               {"index":9,"confidence":"high","reason":"out of range"},{"index":0,"confidence":"weird","reason":"b"},
               {"index":1,"confidence":"medium","reason":"c"},{"index":3,"confidence":"low","reason":"d"}],"no_match":false}"""
        )
        val r = CarePrompts.parseMatch(j, closetSize = 4)
        assertEquals(listOf(2, 0, 1), r.candidates.map { it.index })
        assertEquals("low", r.candidates[1].confidence)
        assertFalse(r.noMatch)
        assertTrue(CarePrompts.parseMatch(CarePrompts.extractJson("""{"candidates":[],"no_match":false}"""), 4).noMatch)
    }

    @Test
    fun `gemini schema uses upper-case types and nullable, claude schema uses anyOf and no extra properties`() {
        val g = CarePrompts.careSchema.toGemini()
        assertEquals("OBJECT", g["type"]!!.jsonPrimitive.content)
        val props = g["properties"]!!.jsonObject
        assertEquals("INTEGER", props["wash_temp_c"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(true, props["wash_temp_c"]!!.jsonObject["nullable"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(g["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("wash_temp_c"))
        assertTrue(g["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("machine_wash"))

        val c = CarePrompts.careSchema.toJsonSchema()
        assertEquals("object", c["type"]!!.jsonPrimitive.content)
        assertEquals("false", c["additionalProperties"]!!.jsonPrimitive.content)
        val cProps = c["properties"]!!.jsonObject
        assertTrue(cProps["wash_temp_c"]!!.jsonObject.containsKey("anyOf"))
        assertEquals(cProps.size, c["required"]!!.jsonArray.size)
    }

    @Test
    fun `request bodies carry the prompt, the images and the schema`() {
        val tag = ByteArray(16) { it.toByte() }
        val parts = CarePrompts.readTagParts(listOf(tag), emptyList())
        val gemini = Json.parseToJsonElement(GeminiModel("k", "gemini-x").body(parts, CarePrompts.careSchema.toGemini())).jsonObject
        val gParts = gemini["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertTrue(gParts[0].jsonObject["text"]!!.jsonPrimitive.content.contains("care-label expert"))
        assertTrue(gParts.last().jsonObject.containsKey("inline_data"))
        assertEquals("application/json", gemini["generationConfig"]!!.jsonObject["response_mime_type"]!!.jsonPrimitive.content)

        val claude = Json.parseToJsonElement(ClaudeModel("k", "claude-x").body(parts, CarePrompts.careSchema.toJsonSchema(), "claude-x", withFallbacks = true)).jsonObject
        assertEquals("claude-x", claude["model"]!!.jsonPrimitive.content)
        assertEquals("default", claude["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("json_schema", claude["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val content = claude["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image", content.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(Json.parseToJsonElement(ClaudeModel("k", "claude-x").body(parts, CarePrompts.careSchema.toJsonSchema(), "claude-x", withFallbacks = false)).jsonObject.containsKey("fallbacks"))
    }

    @Test
    fun `match parts list every item and attach thumbs only where given`() {
        val closet = listOf(
            MatchInput(0, "Tee", "cotton", "", "white tee", ByteArray(4)),
            MatchInput(1, "Sweater", "", "", "", null),
        )
        val parts = CarePrompts.matchParts(ByteArray(4), closet)
        val texts = parts.filterIsInstance<com.laundrycare.ai.Part.Text>().map { it.text }
        assertTrue(texts.any { it.startsWith("Item 0:") && it.contains("looks like: white tee") })
        assertTrue(texts.any { it.startsWith("Item 1:") })
        assertTrue(texts.any { it.contains("no photo for this item") })
        assertEquals(2, parts.filterIsInstance<com.laundrycare.ai.Part.Image>().size)
    }

    @Test
    fun `zoomed symbol rows are appended after the labels and the box parser rejects nonsense`() {
        val parts = CarePrompts.readTagParts(listOf(ByteArray(4)), emptyList(), symbolZooms = listOf(ByteArray(8)))
        val texts = parts.filterIsInstance<com.laundrycare.ai.Part.Text>().map { it.text }
        assertTrue(texts.last().startsWith("ZOOMED SYMBOL ROW"))
        assertTrue(parts.last() is com.laundrycare.ai.Part.Image)

        assertEquals(listOf(600, 100, 760, 900), CarePrompts.parseBox(CarePrompts.extractJson("""{"found":true,"ymin":600,"xmin":100,"ymax":760,"xmax":900}"""))!!.toList())
        assertNull(CarePrompts.parseBox(CarePrompts.extractJson("""{"found":false,"ymin":0,"xmin":0,"ymax":0,"xmax":0}""")))
        assertNull(CarePrompts.parseBox(CarePrompts.extractJson("""{"found":true,"ymin":0,"xmin":0,"ymax":1000,"xmax":1000}""")))
        assertNull(CarePrompts.parseBox(CarePrompts.extractJson("""{"found":true,"ymin":500,"xmin":500,"ymax":510,"xmax":520}""")))
    }

    @Test
    fun `gemini model scoring prefers the newest plain flash`() {
        val ids = listOf("gemini-2.5-flash-lite", "gemini-3.8-flash", "gemini-3.8-pro", "gemini-3.8-flash-preview", "gemini-embedding-001")
        assertEquals("gemini-3.8-flash", ids.maxByOrNull { GeminiModel.score(it) })
    }
}
