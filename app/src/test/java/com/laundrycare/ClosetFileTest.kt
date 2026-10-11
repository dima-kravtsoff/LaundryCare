package com.laundrycare

import com.laundrycare.data.CareProfile
import com.laundrycare.data.ClosetFile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.Garment
import com.laundrycare.data.GarmentRepository
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosetFileTest {

    private val json = GarmentRepository.json

    @Test
    fun `closet round-trips through JSON with wire names for enums`() {
        val g = Garment(
            id = "g1", name = "Navy sweater",
            care = CareProfile(fabric = "wool", washAction = WashAction.GENTLE, tumbleHeat = TumbleHeat.LOW, lowConfidence = listOf("iron")),
            garmentPhotos = listOf("g1-a.jpg"), tagPhotos = listOf("g1-b.jpg"), colorGroup = ColorGroup.DARK,
            description = "navy knit", createdAt = 123L,
        )
        val text = json.encodeToString(ClosetFile.serializer(), ClosetFile(garments = listOf(g)))
        assertTrue(text.contains("\"washAction\": \"gentle\""))
        assertTrue(text.contains("\"colorGroup\": \"dark\""))
        assertTrue(text.contains("\"schemaVersion\": 1"))
        val back = json.decodeFromString(ClosetFile.serializer(), text)
        assertEquals(listOf(g), back.garments)
    }

    @Test
    fun `old or hand-edited files with missing and unknown fields still load`() {
        val text = """
            {"schemaVersion":1,"garments":[{"id":"x","name":"Old thing","createdAt":5,"care":{"fabric":"cotton","future_field":true},"colour":"nonsense"}]}
        """.trimIndent()
        val back = json.decodeFromString(ClosetFile.serializer(), text)
        val g = back.garments.single()
        assertEquals("Old thing", g.name)
        assertEquals("cotton", g.care.fabric)
        assertTrue(g.care.machineWash)
        assertEquals(5L, g.updatedAt)
        assertTrue(g.garmentPhotos.isEmpty())
    }
}
