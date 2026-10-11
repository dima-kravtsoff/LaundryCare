package com.laundrycare

import com.laundrycare.care.LoadPlanner
import com.laundrycare.care.Machines
import com.laundrycare.care.Spin
import com.laundrycare.care.WashTemp
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.DryClean
import com.laundrycare.data.Garment
import com.laundrycare.data.GarmentKind
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadPlannerTest {

    private val machines = Machines.BOSCH_500
    private var n = 0
    private fun g(name: String, care: CareProfile, colour: ColorGroup? = null) =
        Garment(id = "g${n++}", name = name, care = care, colorGroup = colour, createdAt = 0L)

    private val cotton60 = CareProfile(fabric = "100% cotton", washTempC = 60, washAction = WashAction.NORMAL, tumbleHeat = TumbleHeat.HIGH, garmentKind = GarmentKind.TSHIRT)
    private val silk30 = CareProfile(fabric = "100% silk", washTempC = 30, washAction = WashAction.GENTLE, tumbleDry = false, doNotTumbleDry = true, garmentKind = GarmentKind.SHIRT)

    @Test
    fun `one silk among cottons drags the load to Delicates cold and names the culprit`() {
        val tee1 = g("White tee", cotton60, ColorGroup.WHITE)
        val tee2 = g("Grey tee", cotton60, ColorGroup.LIGHT)
        val blouse = g("Silk blouse", silk30, ColorGroup.LIGHT)
        val plan = LoadPlanner.plan(listOf(tee1, tee2, blouse), machines)

        assertEquals(3, plan.load.size)
        assertTrue(plan.pulled.isEmpty())
        assertEquals("Delicates", plan.wash?.program?.name)
        assertEquals(WashTemp.COLD, plan.wash?.temp)
        assertEquals(Spin.LOW, plan.wash?.spin)
        assertTrue(plan.limiters.any { it.setting == "Temperature" && it.garment.id == blouse.id })
        assertTrue(plan.limiters.any { it.setting == "Cycle" && it.garment.id == blouse.id })
        // The silk cannot tumble; the tees still can.
        assertEquals(listOf(blouse.id), plan.airDry.map { it.id })
        assertNotNull(plan.dry)
        assertEquals(2, plan.load.size - plan.airDry.size)
    }

    @Test
    fun `identical cottons run hot together with no limiters`() {
        val plan = LoadPlanner.plan(listOf(g("A", cotton60), g("B", cotton60), g("C", cotton60)), machines)
        assertEquals("Cotton Normal", plan.wash?.program?.name)
        assertEquals(WashTemp.HOT, plan.wash?.temp)
        assertEquals(Spin.HIGH, plan.wash?.spin)
        assertTrue(plan.limiters.isEmpty())
        assertEquals("Cotton Dry", plan.dry?.program?.name)
        assertNull(plan.secondLoad)
    }

    @Test
    fun `wool is pulled from a mixed load and gets its own second load`() {
        val wool = CareProfile(fabric = "100% wool", handWash = true, machineWash = false, washTempC = 30, tumbleDry = false, doNotTumbleDry = true)
        val sweater = g("Navy sweater", wool)
        val tee = g("Tee", cotton60)
        val plan = LoadPlanner.plan(listOf(sweater, tee), machines)
        assertEquals(listOf(tee.id), plan.load.map { it.id })
        assertEquals(1, plan.pulled.size)
        assertTrue(plan.pulled.first().reason.contains("wool"))
        val second = plan.secondLoad
        assertNotNull(second)
        assertEquals("Hand Wash/Wool", second!!.wash?.program?.name)
        assertEquals(listOf(sweater.id), second.load.map { it.id })
    }

    @Test
    fun `bleeders and dry-clean-only items are pulled, dry-clean never gets a second load`() {
        val red = g("Red shirt", cotton60.copy(washSeparately = true))
        val suit = g("Suit", CareProfile(machineWash = false, handWash = false, dryClean = DryClean.PCE))
        val tee = g("Tee", cotton60)
        val plan = LoadPlanner.plan(listOf(red, suit, tee), machines)
        assertEquals(listOf(tee.id), plan.load.map { it.id })
        assertEquals(2, plan.pulled.size)
        val second = plan.secondLoad
        assertNotNull(second)
        assertEquals(listOf(red.id), second!!.load.map { it.id })
        assertNull(second.secondLoad)
    }

    @Test
    fun `whites and darks are split, smaller group leaves`() {
        val w1 = g("W1", cotton60, ColorGroup.WHITE)
        val w2 = g("W2", cotton60, ColorGroup.WHITE)
        val d1 = g("D1", cotton60.copy(washTempC = 40), ColorGroup.DARK)
        val plan = LoadPlanner.plan(listOf(w1, w2, d1), machines)
        assertEquals(setOf(w1.id, w2.id), plan.load.map { it.id }.toSet())
        assertEquals(d1.id, plan.pulled.single().garment.id)
        assertEquals(WashTemp.HOT, plan.wash?.temp)
        assertEquals(WashTemp.WARM, plan.secondLoad?.wash?.temp)
    }

    @Test
    fun `nothing washable gives an empty load and no crash`() {
        val plan = LoadPlanner.plan(listOf(g("Suit", CareProfile(machineWash = false, handWash = false, dryClean = DryClean.PCE))), machines)
        assertTrue(plan.load.isEmpty())
        assertNull(plan.wash)
        assertNull(plan.secondLoad)
    }

    @Test
    fun `too many delicates for a third of the drum is flagged`() {
        val delicate = CareProfile(washAction = WashAction.GENTLE, washTempC = 30, garmentKind = GarmentKind.JUMPER)
        val many = (1..8).map { g("Knit $it", delicate) }
        val plan = LoadPlanner.plan(many, machines)
        assertEquals("Delicates", plan.wash?.program?.name)
        assertNotNull(plan.sizeWarning)
    }
}
