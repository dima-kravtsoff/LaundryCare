package com.laundrycare.care

import com.laundrycare.data.ColorGroup
import com.laundrycare.data.Garment
import kotlin.math.roundToInt

/*
 * "How do I run this load?" — the question you actually have standing at the
 * machine. Constraints are intersected, never averaged: one 30 °C silk among
 * 60 °C cottons means the whole load runs cold, and the plan says which
 * garment dragged it down so you can pull that one out instead.
 */

data class Pulled(val garment: Garment, val reason: String)

/** The garment that forced a setting: "Cold because of the silk blouse". */
data class Limiter(val setting: String, val value: String, val garment: Garment, val detail: String)

data class LoadPlan(
    val load: List<Garment>,
    val pulled: List<Pulled>,
    val wash: WashPlan?,
    val dry: DryPlan?,
    /** In the wash, but not in the dryer. */
    val airDry: List<Garment>,
    val limiters: List<Limiter>,
    val sizeWarning: String?,
    /** What to do with the pulled items that can still be machine washed. */
    val secondLoad: LoadPlan?,
)

object LoadPlanner {

    fun plan(garments: List<Garment>, machines: MachinePair, depth: Int = 0): LoadPlan {
        val pulled = mutableListOf<Pulled>()
        val load = garments.toMutableList()

        // 1. Things that never go in a washer.
        load.removeAll { g ->
            when {
                g.care.doNotWash -> { pulled += Pulled(g, "the tag says do not wash"); true }
                g.care.dryCleanOnly -> { pulled += Pulled(g, "dry clean only"); true }
                else -> false
            }
        }

        // 2. Wool and hand-wash-only items need the gentlest cycle; they only share a load with each other.
        val gentleOnly = load.filter { it.care.handWashOnly || Recommend.traits(it.care, it.name).wool }
        if (gentleOnly.isNotEmpty() && gentleOnly.size < load.size) {
            gentleOnly.forEach {
                val wool = Recommend.traits(it.care, it.name).wool
                pulled += Pulled(it, if (wool) "wool: needs the hand-wash / wool cycle on its own" else "hand wash only: needs the hand-wash / wool cycle on its own")
            }
            load.removeAll(gentleOnly)
        }

        // 3. Anything that bleeds goes alone.
        if (load.size > 1) {
            val bleeders = load.filter { Recommend.traits(it.care, it.name).bleeds }
            if (bleeders.isNotEmpty() && bleeders.size < load.size) {
                bleeders.forEach { pulled += Pulled(it, "may bleed: the tag says wash separately") }
                load.removeAll(bleeders)
            }
        }

        // 4. Whites apart from darks and brights. The smaller group leaves.
        val whites = load.filter { it.colorGroup == ColorGroup.WHITE }
        val darks = load.filter { it.colorGroup == ColorGroup.DARK || it.colorGroup == ColorGroup.BRIGHT }
        if (whites.isNotEmpty() && darks.isNotEmpty()) {
            val out = if (darks.size <= whites.size) darks else whites
            out.forEach {
                pulled += Pulled(it, if (it in darks) "dark or bright colour: keep it away from the whites in this load" else "white: keep it away from the darks in this load")
            }
            load.removeAll(out)
        }

        if (load.isEmpty()) {
            return LoadPlan(load, pulled, null, null, emptyList(), emptyList(), null, secondLoad(pulled, machines, depth))
        }

        val washer = machines.washer
        val r = washer.roles
        val limiters = mutableListOf<Limiter>()
        val plans = load.map { it to Recommend.wash(it.care, it.name, washer, it.colorGroup) }

        // Cycle: the gentlest wins, unless everything agrees on a fabric cycle (all jeans, all towels).
        val names = plans.map { it.second.program!!.name }
        val programName = if (names.distinct().size == 1) names.first() else {
            val gentlest = plans.minBy { rank(it.second.program!!.name, r) }
            val chosen = when (rank(gentlest.second.program!!.name, r)) {
                0 -> r.wool ?: r.delicates
                1 -> r.delicates
                2 -> r.permPress
                else -> r.normal
            }
            if (rank(chosen, r) < plans.maxOf { rank(it.second.program!!.name, r) }) {
                limiters += Limiter("Cycle", chosen, gentlest.first, gentlest.second.programReason ?: "needs a gentler cycle")
            }
            chosen
        }
        val program = washer.program(programName) ?: washer.programs.first()

        // Temperature: the lowest maximum across the load.
        val coldest = plans.minBy { it.second.temp!!.maxC }
        val temp = Recommend.clampTemp(program, coldest.second.temp!!)
        if (plans.any { it.second.temp!!.maxC > coldest.second.temp!!.maxC }) {
            limiters += Limiter("Temperature", temp?.label ?: coldest.second.temp!!.label, coldest.first, coldest.second.tempReason ?: "needs it cooler")
        }

        // Spin: the lowest allowed.
        val slowest = plans.minBy { it.second.spin?.rank ?: Spin.HIGH.rank }
        val spin = Recommend.clampSpin(program, slowest.second.spin ?: Spin.HIGH)
        if (plans.any { (it.second.spin?.rank ?: 3) > (slowest.second.spin?.rank ?: 3) }) {
            limiters += Limiter("Spin", spin?.label ?: "", slowest.first, "delicate: spin gently")
        }

        val washNotes = plans.flatMap { it.second.notes }.distinct()
        val washWarnings = plans.flatMap { it.second.warnings }.distinct()
            .filterNot { it.startsWith("Wash it separately") }   // handled by pulling
        val washOptions = plans.flatMap { it.second.options }.distinct()
            .filter { "SpeedPerfect" !in it || program.name == r.normal || program.name == r.towels }
        val wash = WashPlan(
            program = program, temp = temp, spin = spin, options = washOptions,
            notes = washNotes, warnings = washWarnings,
            extras = plans.flatMap { it.second.extras }.distinctBy { it.text },
            kind = plans.first().second.kind,
        )

        // Dryer: lowest heat across the items that may tumble; the rest air-dry.
        val dryer = machines.dryer
        val dr = dryer.roles
        val dryPlans = load.map { it to Recommend.dry(it.care, it.name, dryer) }
        val airDry = dryPlans.filter { it.second.mode == DryMode.AIR }.map { it.first }
        val tumble = dryPlans.filter { it.second.mode == DryMode.MACHINE }
        val dry: DryPlan? = if (tumble.isEmpty()) null else {
            val coolest = tumble.minBy { it.second.heat!!.rank }
            val heat = coolest.second.heat!!
            val dryNames = tumble.map { it.second.program!!.name }
            val dryProgramName = if (dryNames.distinct().size == 1 && tumble.all { it.second.program!!.heat.rank <= heat.rank || it.second.options.any { o -> o.startsWith("Low Heat") } }) {
                dryNames.first()
            } else when (heat) {
                DryHeat.NONE -> dr.air ?: dr.low
                DryHeat.LOW -> dr.low
                DryHeat.MEDIUM -> dr.medium
                DryHeat.HIGH -> dr.high
            }
            val dryProgram = dryer.program(dryProgramName) ?: dryer.programs.first()
            if (tumble.any { it.second.heat!!.rank > heat.rank }) {
                limiters += Limiter("Dryer heat", heat.label, coolest.first, "its tag allows ${heat.label.lowercase()} at most")
            }
            val dryOptions = tumble.flatMap { it.second.options }.distinct()
                .filter { !it.startsWith("Low Heat") || (dryProgram.heat.rank > heat.rank && dryProgram.lowHeatOption) }
            DryPlan(
                mode = DryMode.MACHINE, program = dryProgram, heat = heat, options = dryOptions,
                notes = tumble.flatMap { it.second.notes }.distinct(),
                warnings = tumble.flatMap { it.second.warnings }.distinct(),
                kind = coolest.second.kind,
            )
        }

        return LoadPlan(
            load = load, pulled = pulled, wash = wash, dry = dry, airDry = airDry, limiters = limiters,
            sizeWarning = sizeWarning(load, program, washer),
            secondLoad = secondLoad(pulled, machines, depth),
        )
    }

    /** Lower is gentler. Fabric cycles (jeans, towels) count as normal. */
    private fun rank(name: String, r: WasherRoles): Int = when (name) {
        r.wool -> 0
        r.delicates -> 1
        r.permPress -> 2
        else -> 3
    }

    private fun secondLoad(pulled: List<Pulled>, machines: MachinePair, depth: Int): LoadPlan? {
        if (depth >= 2) return null
        val washable = pulled.map { it.garment }.filter { !it.care.doNotWash && !it.care.dryCleanOnly }
        if (washable.isEmpty()) return null
        return plan(washable, machines, depth + 1)
    }

    private fun sizeWarning(load: List<Garment>, program: WasherProgram, washer: Washer): String? {
        if (program.maxLoad == LoadFraction.ONE_ITEM) {
            return if (load.size > 1) "${program.name} takes one item at a time." else null
        }
        val liters = load.sumOf { Recommend.kindOf(it.care, it.name).liters }
        val capacity = washer.drumLiters * program.maxLoad.value
        if (liters <= capacity) return null
        return "Probably too much for ${program.name}: it is limited to ${program.maxLoad.label.lowercase()} " +
            "(about ${capacity.roundToInt()} L of loosely placed clothes) and this load is roughly ${liters.roundToInt()} L. Split it in two."
    }
}
