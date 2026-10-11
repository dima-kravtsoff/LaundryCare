package com.laundrycare

import com.laundrycare.ai.CarePrompts
import com.laundrycare.care.CareText
import com.laundrycare.care.DryHeat
import com.laundrycare.care.DryMode
import com.laundrycare.care.LoadPlanner
import com.laundrycare.care.Machines
import com.laundrycare.care.Recommend
import com.laundrycare.care.WashTemp
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.DryClean
import com.laundrycare.data.Garment
import com.laundrycare.data.GarmentKind
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction
import com.laundrycare.ui.ClosetFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The corners: odd tags, odd loads, every machine profile. Nothing here may throw. */
class EdgeCasesTest {

    private var n = 0
    private fun g(name: String, care: CareProfile, colour: ColorGroup? = null) =
        Garment(id = "g${n++}", name = name, care = care, colorGroup = colour, createdAt = n.toLong())

    private val wool = CareProfile(fabric = "100% wool", handWash = true, machineWash = false, washTempC = 30, tumbleDry = false, doNotTumbleDry = true)
    private val cotton = CareProfile(fabric = "cotton", washTempC = 60, tumbleHeat = TumbleHeat.HIGH, garmentKind = GarmentKind.TSHIRT)

    @Test
    fun `an all-wool load is not pulled apart and runs on the wool cycle`() {
        val plan = LoadPlanner.plan(listOf(g("A", wool), g("B", wool), g("C", wool)), Machines.BOSCH_500)
        assertEquals(3, plan.load.size)
        assertTrue(plan.pulled.isEmpty())
        assertEquals("Hand Wash/Wool", plan.wash?.program?.name)
        assertNull("nothing tumbles", plan.dry)
        assertEquals(3, plan.airDry.size)
        assertTrue(plan.limiters.isEmpty())
    }

    @Test
    fun `when everything bleeds nothing is pulled for bleeding, and whites still leave the darks`() {
        val bleeds = cotton.copy(washSeparately = true)
        val plan = LoadPlanner.plan(listOf(g("Red", bleeds, ColorGroup.BRIGHT), g("Navy", bleeds, ColorGroup.DARK), g("White", bleeds, ColorGroup.WHITE)), Machines.BOSCH_500)
        assertEquals(2, plan.load.size)
        assertEquals("White", plan.pulled.single().garment.name)
        assertTrue(plan.pulled.single().reason.contains("white"))
        assertNotNull(plan.secondLoad)
        assertEquals(listOf("White"), plan.secondLoad!!.load.map { it.name })
    }

    @Test
    fun `fabric cycles that disagree fall back to Cotton Normal without claiming a limiter`() {
        val jeans = CareProfile(fabric = "denim", washTempC = 40, tumbleHeat = TumbleHeat.MEDIUM)
        val towel = CareProfile(fabric = "cotton terry", washTempC = 60, tumbleHeat = TumbleHeat.HIGH, garmentKind = GarmentKind.TOWEL)
        val plan = LoadPlanner.plan(listOf(g("Jeans", jeans), g("Bath towel", towel)), Machines.BOSCH_500)
        assertEquals("Cotton Normal", plan.wash?.program?.name)
        assertEquals(WashTemp.WARM, plan.wash?.temp)
        assertTrue(plan.limiters.none { it.setting == "Cycle" })
        assertTrue(plan.limiters.any { it.setting == "Temperature" && it.garment.name == "Jeans" })
        assertEquals("Perm Press Dry", plan.dry?.program?.name)
        assertEquals(DryHeat.MEDIUM, plan.dry?.heat)
    }

    @Test
    fun `three rounds of pulling stop at depth two without losing anything to a crash`() {
        val items = listOf(
            g("Wool", wool), g("Red bleeder", cotton.copy(washSeparately = true), ColorGroup.BRIGHT),
            g("White tee", cotton, ColorGroup.WHITE), g("Black tee", cotton, ColorGroup.DARK),
            g("Suit", CareProfile(machineWash = false, handWash = false, dryClean = DryClean.PCE)),
            g("Leather", CareProfile(doNotWash = true, machineWash = false)),
        )
        val plan = LoadPlanner.plan(items, Machines.BOSCH_500)
        var p: com.laundrycare.care.LoadPlan? = plan
        var depth = 0
        val seen = mutableSetOf<String>()
        while (p != null) {
            seen += p.load.map { it.name }
            depth++
            p = p.secondLoad
        }
        assertTrue("depth was $depth", depth in 2..3)
        assertFalse(seen.contains("Suit"))
        assertFalse(seen.contains("Leather"))
    }

    @Test
    fun `a hundred garments plan quickly on both machine profiles`() {
        val kinds = GarmentKind.entries
        val many = (0 until 120).map { i ->
            g(
                "Item $i",
                CareProfile(
                    fabric = listOf("cotton", "wool", "silk", "denim", "polyester")[i % 5],
                    washTempC = listOf(30, 40, 60, null)[i % 4],
                    washAction = WashAction.entries[i % 3],
                    tumbleHeat = TumbleHeat.entries[i % 4],
                    washSeparately = i % 17 == 0,
                    garmentKind = kinds[i % kinds.size],
                ),
                ColorGroup.entries[i % 5],
            )
        }
        for (machines in Machines.ALL) {
            val start = System.nanoTime()
            val plan = LoadPlanner.plan(many, machines)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("planning took $ms ms", ms < 2000)
            assertNotNull(plan.wash)
            assertNotNull(plan.sizeWarning)
            // Every garment is either in some load or pulled with a reason, exactly once.
            var p: com.laundrycare.care.LoadPlan? = plan
            val placed = mutableListOf<String>()
            val pulledAtEnd = mutableListOf<String>()
            while (p != null) {
                placed += p.load.map { it.id }
                if (p.secondLoad == null) pulledAtEnd += p.pulled.map { it.garment.id }
                p = p.secondLoad
            }
            assertEquals(placed.size, placed.toSet().size)
            assertEquals(many.size, placed.size + pulledAtEnd.size)
        }
    }

    @Test
    fun `temperatures off the usual scale clamp sensibly`() {
        val washer = Machines.BOSCH_500.washer
        assertEquals(WashTemp.COLD, Recommend.wash(CareProfile(washTempC = 20), "Tee", washer).temp)
        assertEquals(WashTemp.EXTRA_HOT, Recommend.wash(CareProfile(washTempC = 100), "Sheet", washer).temp)
        val delicate95 = Recommend.wash(CareProfile(washTempC = 95, washAction = WashAction.GENTLE), "Thing", washer)
        assertEquals("Delicates", delicate95.program?.name)
        assertEquals(WashTemp.HOT, delicate95.temp)
        assertTrue(delicate95.tempReason!!, delicate95.tempReason!!.contains("only goes to"))
        assertTrue(delicate95.warnings.none { it.contains("cannot go as cool") })
    }

    @Test
    fun `towels and jeans respect a low-heat tag through the option, and medium through a cycle change`() {
        val dryer = Machines.BOSCH_500.dryer
        val towelLow = Recommend.dry(CareProfile(fabric = "cotton terry", tumbleHeat = TumbleHeat.LOW), "Bath towel", dryer)
        assertEquals("Towels", towelLow.program?.name)
        assertTrue(towelLow.options.any { it.startsWith("Low Heat") })
        val towelMed = Recommend.dry(CareProfile(fabric = "cotton terry", tumbleHeat = TumbleHeat.MEDIUM), "Bath towel", dryer)
        assertEquals("Perm Press Dry", towelMed.program?.name)
        val jeansHigh = Recommend.dry(CareProfile(fabric = "denim", tumbleHeat = TumbleHeat.HIGH), "Jeans", dryer)
        assertEquals("Jeans", jeansHigh.program?.name)
        assertEquals(DryHeat.HIGH, jeansHigh.heat)
        val noHeat = Recommend.dry(CareProfile(tumbleHeat = TumbleHeat.NO_HEAT), "Cap", dryer)
        assertEquals("Air Fluff/No Heat", noHeat.program?.name)
        // No tumble symbol at all: low with a warning, not a crash.
        val none = Recommend.dry(CareProfile(tumbleDry = false, tumbleHeat = null), "Mystery", dryer)
        assertEquals(DryMode.MACHINE, none.mode)
        assertTrue(none.warnings.any { it.contains("No tumble-dry symbol") })
    }

    @Test
    fun `a model answer with every wash flag false still washes, unless it is dry-clean-only`() {
        val nothing = CarePrompts.parseCare(CarePrompts.extractJson("""{"machine_wash":false,"hand_wash":false,"do_not_wash":false,"dry_clean":null}""")).care
        assertTrue(nothing.machineWash)
        assertFalse(nothing.dryCleanOnly)
        val dco = CarePrompts.parseCare(CarePrompts.extractJson("""{"machine_wash":false,"hand_wash":false,"do_not_wash":false,"dry_clean":"pce"}""")).care
        assertFalse(dco.machineWash)
        assertTrue(dco.dryCleanOnly)
        val hand = CarePrompts.parseCare(CarePrompts.extractJson("""{"machine_wash":false,"hand_wash":true,"do_not_wash":false}""")).care
        assertTrue(hand.handWashOnly)
        assertFalse(hand.machineWash)
        // Garbage values are dropped, not crashed on.
        val junk = CarePrompts.parseCare(CarePrompts.extractJson("""{"wash_temp_c":"warm","wash_action":"vigorous","tumble_heat":7,"low_confidence":"iron","garment_kind":["tshirt"]}""")).care
        assertNull(junk.washTempC); assertNull(junk.washAction); assertNull(junk.tumbleHeat); assertNull(junk.garmentKind)
        assertTrue(junk.lowConfidence.isEmpty())
        assertNull("500 °C is not a wash temperature", CarePrompts.parseCare(CarePrompts.extractJson("""{"wash_temp_c":500}""")).care.washTempC)
    }

    @Test
    fun `share text and tag rows render for every kind of garment`() {
        val cases = listOf(
            g("Leather jacket", CareProfile(doNotWash = true, machineWash = false, dryClean = DryClean.ANY)),
            g("Suit", CareProfile(machineWash = false, handWash = false, dryClean = DryClean.PCE)),
            g("Wool", wool),
            g("Tee", cotton, ColorGroup.WHITE),
            g("Blank", CareProfile.blank()),
            g("Nothing", CareProfile(washAction = null, tumbleHeat = null, bleach = null, tumbleDry = false)),
        )
        for (machines in Machines.ALL) for (garment in cases) {
            val wash = Recommend.wash(garment.care, garment.name, machines.washer, garment.colorGroup)
            val dry = Recommend.dry(garment.care, garment.name, machines.dryer)
            val text = CareText.share(garment, wash, dry, machines)
            assertTrue(text.startsWith(garment.name))
            assertTrue(text.contains("WASH") && text.contains("DRY") && text.contains("Tag:"))
            assertTrue(CareText.rows(garment.care, garment).isNotEmpty())
            ClosetFilter.entries.forEach { it.matches(garment) }
        }
    }

    @Test
    fun `closet filters agree with the recommender`() {
        val suit = g("Suit", CareProfile(machineWash = false, handWash = false, dryClean = DryClean.PCE))
        assertTrue(ClosetFilter.DRY_CLEAN.matches(suit))
        assertFalse(ClosetFilter.MACHINE.matches(suit))
        val woolly = g("Wool", wool)
        assertTrue(ClosetFilter.HAND.matches(woolly))
        assertTrue(ClosetFilter.NO_TUMBLE.matches(woolly))
        val unsure = g("Tee", cotton.copy(lowConfidence = listOf("iron")))
        assertTrue(ClosetFilter.CHECK.matches(unsure))
        assertFalse(ClosetFilter.CHECK.matches(unsure.copy(care = unsure.care.confirmed("iron"))))
    }
}
