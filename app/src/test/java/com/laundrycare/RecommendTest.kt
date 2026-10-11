package com.laundrycare

import com.laundrycare.care.DryHeat
import com.laundrycare.care.DryMode
import com.laundrycare.care.ExtraKind
import com.laundrycare.care.Machines
import com.laundrycare.care.Recommend
import com.laundrycare.care.Spin
import com.laundrycare.care.WashTemp
import com.laundrycare.data.Bleach
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.DryClean
import com.laundrycare.data.GarmentKind
import com.laundrycare.data.NaturalDry
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendTest {

    private val washer = Machines.BOSCH_500.washer
    private val dryer = Machines.BOSCH_500.dryer

    @Test
    fun `plain cotton tee runs Cotton Normal warm high`() {
        val care = CareProfile(fabric = "100% cotton", washTempC = 40, washAction = WashAction.NORMAL, tumbleHeat = TumbleHeat.MEDIUM)
        val w = Recommend.wash(care, "White tee", washer)
        assertEquals("Cotton Normal", w.program?.name)
        assertEquals(WashTemp.WARM, w.temp)
        assertEquals(Spin.HIGH, w.spin)
        val d = Recommend.dry(care, "White tee", dryer)
        assertEquals(DryMode.MACHINE, d.mode)
        assertEquals("Perm Press Dry", d.program?.name)
        assertEquals(DryHeat.MEDIUM, d.heat)
    }

    @Test
    fun `tag temperature never rounds up`() {
        assertEquals(WashTemp.COLD, Recommend.wantedTemp(CareProfile(washTempC = 30), cool = false))
        assertEquals(WashTemp.WARM, Recommend.wantedTemp(CareProfile(washTempC = 40), cool = false))
        assertEquals(WashTemp.WARM, Recommend.wantedTemp(CareProfile(washTempC = 50), cool = false))
        assertEquals(WashTemp.HOT, Recommend.wantedTemp(CareProfile(washTempC = 60), cool = false))
        assertEquals(WashTemp.HOT, Recommend.wantedTemp(CareProfile(washTempC = 70), cool = false))
        assertEquals(WashTemp.EXTRA_HOT, Recommend.wantedTemp(CareProfile(washTempC = 95), cool = false))
        assertEquals(WashTemp.WARM, Recommend.wantedTemp(CareProfile(washTempC = null), cool = false))
        assertEquals(WashTemp.COLD, Recommend.wantedTemp(CareProfile(washTempC = null), cool = true))
    }

    @Test
    fun `wool goes to Hand Wash Wool, cold, low spin, air dry flat`() {
        val care = CareProfile(fabric = "100% merino wool", washTempC = 30, handWash = true, machineWash = false, tumbleDry = false, doNotTumbleDry = true)
        val w = Recommend.wash(care, "Navy sweater", washer)
        assertEquals("Hand Wash/Wool", w.program?.name)
        assertEquals(WashTemp.COLD, w.temp)
        assertEquals(Spin.LOW, w.spin)
        assertTrue(w.notes.any { it.contains("wool detergent") })
        val d = Recommend.dry(care, "Navy sweater", dryer)
        assertEquals(DryMode.AIR, d.mode)
        assertTrue(d.airText!!.contains("flat"))
    }

    @Test
    fun `gentle symbol picks Delicates and clamps the spin the cycle allows`() {
        val care = CareProfile(washAction = WashAction.GENTLE, washTempC = 30, tumbleHeat = TumbleHeat.LOW)
        val w = Recommend.wash(care, "Blouse", washer)
        assertEquals("Delicates", w.program?.name)
        assertEquals(Spin.LOW, w.spin)
        assertTrue(w.program!!.spins.contains(w.spin))
        val d = Recommend.dry(care, "Blouse", dryer)
        assertEquals("Delicates", d.program?.name)
        assertEquals(DryHeat.LOW, d.heat)
    }

    @Test
    fun `jeans at low heat use the Jeans cycle with the Low Heat option`() {
        val care = CareProfile(fabric = "98% cotton 2% elastane", washTempC = 40, tumbleHeat = TumbleHeat.LOW, specialNotes = "wash inside out")
        val w = Recommend.wash(care, "Blue jeans", washer)
        assertEquals("Jeans", w.program?.name)
        assertTrue(w.notes.any { it.contains("inside out") })
        val d = Recommend.dry(care, "Blue jeans", dryer)
        assertEquals("Jeans", d.program?.name)
        assertTrue(d.options.any { it.startsWith("Low Heat") })
    }

    @Test
    fun `do not wash and dry clean only produce no program`() {
        val dnw = Recommend.wash(CareProfile(doNotWash = true, machineWash = false, dryClean = DryClean.ANY), "Blazer", washer)
        assertNull(dnw.program)
        assertTrue(dnw.warnings.first().contains("DO NOT WASH"))
        val dco = CareProfile(machineWash = false, handWash = false, dryClean = DryClean.PCE)
        assertTrue(dco.dryCleanOnly)
        assertNull(Recommend.wash(dco, "Suit", washer).program)
        assertEquals(DryMode.NONE, Recommend.dry(dco, "Suit", dryer).mode)
    }

    @Test
    fun `bleach and bleeding produce warnings`() {
        val care = CareProfile(bleach = Bleach.NONE, washSeparately = true)
        val w = Recommend.wash(care, "Red shirt", washer)
        assertTrue(w.warnings.any { it.contains("bleach") })
        assertTrue(w.warnings.any { it.contains("separately") })
    }

    @Test
    fun `do not tumble dry with a line symbol says hang`() {
        val care = CareProfile(tumbleDry = false, doNotTumbleDry = true, naturalDry = NaturalDry.LINE)
        val d = Recommend.dry(care, "Linen shirt", dryer)
        assertEquals(DryMode.AIR, d.mode)
        assertTrue(d.airText!!.contains("Hang"))
        assertTrue(d.warnings.any { it.contains("DO NOT tumble") })
    }

    @Test
    fun `kind is guessed from words when the AI did not say`() {
        assertEquals(GarmentKind.JUMPER, Recommend.kindOf(CareProfile(), "Grey hoodie"))
        assertEquals(GarmentKind.JEANS, Recommend.kindOf(CareProfile(fabric = "denim"), "Blue"))
        assertEquals(GarmentKind.TSHIRT, Recommend.kindOf(CareProfile(), "Striped tee"))
        assertEquals(GarmentKind.OTHER, Recommend.kindOf(CareProfile(), "Thing"))
        assertEquals(GarmentKind.TOWEL, Recommend.kindOf(CareProfile(garmentKind = GarmentKind.TOWEL), "Grey hoodie"))
    }

    @Test
    fun `mesh bag and colour catcher advice only where it earns its place`() {
        val tights = CareProfile(fabric = "nylon", garmentKind = GarmentKind.LEGGINGS)
        assertTrue(Recommend.bagAdvice(tights, GarmentKind.LEGGINGS, "Black tights")!!.contains("mesh bag"))
        assertNull(Recommend.bagAdvice(CareProfile(fabric = "100% cotton"), GarmentKind.TSHIRT, "White tee"))
        assertTrue(Recommend.dyeAdvice(CareProfile(fabric = "denim"), GarmentKind.JEANS, "Jeans", null)!!.contains("indigo"))
        assertTrue(Recommend.dyeAdvice(CareProfile(), GarmentKind.TSHIRT, "Tee", ColorGroup.DARK)!!.contains("dark"))
        assertNull(Recommend.dyeAdvice(CareProfile(), GarmentKind.TSHIRT, "White tee", ColorGroup.WHITE))

        val plan = Recommend.wash(CareProfile(fabric = "100% silk", washAction = WashAction.GENTLE), "Red silk blouse", washer)
        assertTrue(plan.extras.any { it.kind == ExtraKind.BAG })
        assertTrue(plan.extras.any { it.kind == ExtraKind.DYE && it.text.contains("Reds") })
    }

    @Test
    fun `generic machines still produce a full plan`() {
        val care = CareProfile(washTempC = 40, washAction = WashAction.PERMANENT_PRESS, tumbleHeat = TumbleHeat.LOW)
        val w = Recommend.wash(care, "Polo", Machines.GENERIC.washer)
        assertEquals("Permanent Press", w.program?.name)
        assertEquals(WashTemp.WARM, w.temp)
        val d = Recommend.dry(care, "Polo", Machines.GENERIC.dryer)
        assertEquals("Delicate / Low", d.program?.name)
    }
}
