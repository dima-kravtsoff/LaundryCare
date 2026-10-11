package com.laundrycare.care

import com.laundrycare.data.Bleach
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.DryClean
import com.laundrycare.data.GarmentKind
import com.laundrycare.data.NaturalDry
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction

/** What the words on the tag and in the name tell us beyond the symbols. */
data class Traits(
    val wool: Boolean,
    val denim: Boolean,
    val towel: Boolean,
    val silk: Boolean,
    val insideOut: Boolean,
    val likeColours: Boolean,
    val bleeds: Boolean,
)

enum class DryMode { MACHINE, AIR, NONE }

enum class ExtraKind { BAG, DYE }

/** Advice beyond the machine settings: a mesh bag, a colour-catcher sheet. */
data class Extra(val kind: ExtraKind, val text: String)

data class WashPlan(
    val program: WasherProgram?,
    val programReason: String? = null,
    val temp: WashTemp? = null,
    val tempReason: String? = null,
    val spin: Spin? = null,
    val options: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val extras: List<Extra> = emptyList(),
    val kind: GarmentKind,
)

data class DryPlan(
    val mode: DryMode,
    val program: DryerProgram? = null,
    val heat: DryHeat? = null,
    val options: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val airText: String? = null,
    val kind: GarmentKind,
)

/** Care profile → settings on a specific pair of machines. */
object Recommend {

    private val WOOL = Regex("wool|cashmere|merino|angora|mohair|alpaca")
    private val DENIM = Regex("\\bdenim\\b|\\bjeans?\\b")
    private val TOWEL = Regex("towel|terry|bathrobe|washcloth")
    private val SILK = Regex("\\bsilk\\b|satin|\\blace\\b|chiffon")
    private val INSIDE_OUT = Regex("inside[- ]out")
    private val LIKE_COLOURS = Regex("like colou?rs|similar colou?rs|dark colou?rs|with darks")
    private val BLEEDS = Regex("wash separately|wash alone|may bleed|colou?r may (transfer|run)|\\bbleed")

    fun traits(care: CareProfile, name: String): Traits {
        val s = "$name ${care.fabric} ${care.specialNotes}".lowercase()
        return Traits(
            wool = WOOL.containsMatchIn(s),
            denim = DENIM.containsMatchIn(s),
            towel = TOWEL.containsMatchIn(s),
            silk = SILK.containsMatchIn(s),
            insideOut = INSIDE_OUT.containsMatchIn(s),
            likeColours = LIKE_COLOURS.containsMatchIn(s),
            bleeds = care.washSeparately || BLEEDS.containsMatchIn(s),
        )
    }

    fun kindOf(care: CareProfile, name: String): GarmentKind {
        care.garmentKind?.let { return it }
        val s = "$name ${care.fabric} ${care.specialNotes}".lowercase()
        return when {
            Regex("t-?shirt|\\btee\\b|\\btees\\b").containsMatchIn(s) -> GarmentKind.TSHIRT
            Regex("jumper|sweater|knit|cardigan|pullover|hoodie|sweatshirt|fleece").containsMatchIn(s) -> GarmentKind.JUMPER
            DENIM.containsMatchIn(s) -> GarmentKind.JEANS
            TOWEL.containsMatchIn(s) -> GarmentKind.TOWEL
            Regex("sheet|duvet|pillow|bedding|bed linen|comforter").containsMatchIn(s) -> GarmentKind.BEDDING
            Regex("jacket|\\bcoat\\b|parka|anorak").containsMatchIn(s) -> GarmentKind.JACKET
            Regex("legging|tights|hosiery").containsMatchIn(s) -> GarmentKind.LEGGINGS
            Regex("\\bsock").containsMatchIn(s) -> GarmentKind.SOCKS
            Regex("underwear|briefs|boxer|\\bbra\\b|knickers|lingerie").containsMatchIn(s) -> GarmentKind.UNDERWEAR
            Regex("dress|skirt").containsMatchIn(s) -> GarmentKind.DRESS
            Regex("trouser|\\bpants\\b|chino|shorts").containsMatchIn(s) -> GarmentKind.TROUSERS
            Regex("shirt|blouse|oxford").containsMatchIn(s) -> GarmentKind.SHIRT
            else -> GarmentKind.OTHER
        }
    }

    /** The warmest machine setting that does not exceed the tag. Unknown → warm, or cold for anything delicate. */
    fun wantedTemp(care: CareProfile, cool: Boolean): WashTemp {
        val c = care.washTempC ?: return if (cool) WashTemp.COLD else WashTemp.WARM
        return WashTemp.entries.filter { it.maxC <= c }.maxByOrNull { it.maxC } ?: WashTemp.COLD
    }

    /** The closest the program can get without going over. */
    fun clampTemp(program: WasherProgram, wanted: WashTemp): WashTemp? =
        program.temps.filter { it.maxC <= wanted.maxC }.maxByOrNull { it.maxC }
            ?: program.temps.minByOrNull { it.maxC }

    fun clampSpin(program: WasherProgram, wanted: Spin): Spin? =
        program.spins.filter { it != Spin.NO_SPIN && it.rank <= wanted.rank }.maxByOrNull { it.rank }
            ?: program.spins.maxByOrNull { it.rank }

    /**
     * Mesh bags solve interaction problems: one garment harming another, or a
     * garment wrecking itself by tangling. They don't make a harsher cycle safe
     * and they don't create capacity, so robust things get no advice at all.
     */
    fun bagAdvice(care: CareProfile, kind: GarmentKind, name: String): String? {
        val s = "$name ${care.fabric} ${care.specialNotes}".lowercase()
        return when {
            Regex("tights|hosiery|stocking|pantyhose").containsMatchIn(s) ->
                "Use a mesh bag: tights ladder and knot themselves around everything else in the drum."
            Regex("\\bbras?\\b|lingerie|underwire").containsMatchIn(s) ->
                "Use a mesh bag, hooks fastened: hooks snag knitwear and a loose underwire can jam the drum or pump."
            Regex("velcro|hook-and-loop").containsMatchIn(s) ->
                "Use a mesh bag: Velcro shreds knitwear on contact."
            Regex("camisole|slip dress|spaghetti strap|thin strap").containsMatchIn(s) ->
                "Use a mesh bag: thin straps stretch and wrap around other items."
            Regex("\\bsilk\\b|\\blace\\b|chiffon|organza").containsMatchIn(s) ->
                "Use a mesh bag: fine weaves catch on zips and seams."
            kind == GarmentKind.UNDERWEAR -> "Worth a mesh bag: hooks and thin straps snag and stretch."
            kind == GarmentKind.SOCKS -> "Worth a mesh bag: you get pairs back and nothing hides in the door seal."
            kind == GarmentKind.JUMPER && WOOL.containsMatchIn(s) -> "Worth a mesh bag: it limits stretching and rubbing against coarser items."
            Regex("drawstring|tie waist|wrap dress|apron").containsMatchIn(s) -> "Worth a mesh bag: long ties tangle and get pulled out."
            else -> null
        }
    }

    /** Dye-bleed risk → colour-catcher sheet. */
    fun dyeAdvice(care: CareProfile, kind: GarmentKind, name: String, colour: ColorGroup?): String? {
        val s = "$name ${care.fabric} ${care.specialNotes}".lowercase()
        return when {
            care.washSeparately || Regex("wash separately|wash alone|like colou?rs|similar colou?rs|may bleed|bleeds?\\b").containsMatchIn(s) ->
                "If you must mix it with anything, drop a colour-catcher sheet in loose. It reduces the risk; it doesn't remove it."
            kind == GarmentKind.JEANS || Regex("denim|indigo").containsMatchIn(s) ->
                "Dark denim sheds indigo for its first several washes: wash separately, or add a colour-catcher sheet."
            Regex("\\bred\\b|crimson|burgundy|scarlet|maroon").containsMatchIn(s) ->
                "Reds bleed more than any other colour. A colour-catcher sheet is cheap insurance when mixing."
            colour == ColorGroup.DARK || Regex("\\bblack\\b|navy|charcoal|\\bdark\\b").containsMatchIn(s) ->
                "New dark items shed dye for the first few washes: a colour-catcher sheet protects lighter things in the load."
            else -> null
        }
    }

    fun wash(care: CareProfile, name: String, washer: Washer, colour: ColorGroup? = null): WashPlan {
        val t = traits(care, name)
        val kind = kindOf(care, name)
        val r = washer.roles

        if (care.doNotWash) {
            val notes = if (care.dryClean != null && care.dryClean != DryClean.DO_NOT)
                listOf("Take it to a professional dry cleaner instead.") else emptyList()
            return WashPlan(program = null, warnings = listOf("The tag says DO NOT WASH. Keep it out of the washer."), notes = notes, kind = kind)
        }
        if (care.dryCleanOnly) {
            return WashPlan(program = null, warnings = listOf("Dry clean only: the tag shows no washing symbol. Take it to a professional cleaner."), kind = kind)
        }

        val notes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val options = mutableListOf<String>()

        val (programName, reason) = when {
            t.wool -> (r.wool ?: r.delicates) to "wool or another animal fibre: the gentlest cycle, so it cannot felt or shrink"
            care.handWashOnly -> (r.wool ?: r.delicates) to "the tag shows hand-wash only; this cycle imitates a careful hand wash"
            t.denim && r.jeans != null -> r.jeans to "denim"
            t.towel && r.towels != null -> r.towels to "towelling"
            t.silk -> r.delicates to "silk, satin or lace"
            care.washAction == WashAction.GENTLE -> r.delicates to "gentle-wash symbol (two bars under the tub)"
            care.washAction == WashAction.PERMANENT_PRESS -> r.permPress to "permanent-press symbol (one bar under the tub)"
            else -> r.normal to "normal-wash symbol (plain tub)"
        }
        val program = washer.program(programName) ?: washer.programs.first()
        if (programName == r.normal && r.light != null) {
            notes += "Half a drum or less, lightly soiled? ${r.light} is quicker and a little gentler."
        }

        val cool = t.wool || t.silk || care.handWashOnly || care.washAction == WashAction.GENTLE
        val wanted = wantedTemp(care, cool)
        val temp = clampTemp(program, wanted)
        val tempReason = when {
            care.washTempC != null && temp != null && temp.maxC < wanted.maxC -> "tag max ${care.washTempC}°C; this cycle only goes to ${temp.label}"
            care.washTempC != null -> "tag max ${care.washTempC}°C"
            cool -> "no temperature on the tag; keeping a delicate cool"
            else -> "no temperature on the tag; warm is the safe default"
        }
        if (temp != null && temp.maxC > wanted.maxC) {
            warnings += "This cycle cannot go as cool as the tag asks (${wanted.label}). Choose a cooler cycle or hand wash."
        }

        val wantedSpin = when {
            program.name == r.delicates || program.name == r.wool || t.silk || care.handWashOnly -> Spin.LOW
            program.name == r.permPress || care.washAction == WashAction.PERMANENT_PRESS -> Spin.MED
            else -> Spin.HIGH
        }
        val spin = clampSpin(program, wantedSpin)

        if ("SpeedPerfect" in program.options && (program.name == r.normal || program.name == r.towels)) {
            options += "SpeedPerfect: optional, shortens the cycle"
        }
        if (t.wool) notes += "Use a wool detergent, never a regular one with enzymes."
        if (t.silk) notes += "Use a mesh laundry bag and a detergent for delicates."
        when (care.bleach) {
            Bleach.NONE -> warnings += "Do NOT use bleach on this garment."
            Bleach.NON_CHLORINE -> notes += "Non-chlorine bleach only: that means an oxygen bleach (sodium percarbonate, the OxiClean type), never chlorine. " +
                "Oxygen bleach needs warmth to work, so it does little in a cold wash; pre-soak in warm water instead."
            else -> {}
        }
        if (t.insideOut) notes += "Turn it inside out before washing."
        if (t.bleeds) warnings += "Wash it separately: the tag says the colour may bleed."
        else if (t.likeColours) notes += "Wash with like colours."
        val extras = listOfNotNull(
            bagAdvice(care, kind, name)?.let { Extra(ExtraKind.BAG, it) },
            dyeAdvice(care, kind, name, colour)?.let { Extra(ExtraKind.DYE, it) },
        )

        return WashPlan(
            program = program, programReason = reason, temp = temp, tempReason = tempReason, spin = spin,
            options = options, notes = notes, warnings = warnings, extras = extras, kind = kind,
        )
    }

    private val AIR_TEXT = mapOf(
        NaturalDry.LINE to "Hang it up to dry (line dry).",
        NaturalDry.FLAT to "Lay it flat to dry; don't hang it.",
        NaturalDry.DRIP to "Drip dry: hang it up dripping wet, without wringing or spinning.",
        NaturalDry.LINE_SHADE to "Hang to dry in the shade, out of direct sun.",
        NaturalDry.FLAT_SHADE to "Lay flat to dry in the shade.",
    )

    fun dry(care: CareProfile, name: String, dryer: Dryer): DryPlan {
        val t = traits(care, name)
        val kind = kindOf(care, name)
        val r = dryer.roles

        if (care.doNotWash) return DryPlan(mode = DryMode.NONE, notes = listOf("Not washed at home, so nothing to dry here."), kind = kind)
        if (care.dryCleanOnly) return DryPlan(mode = DryMode.NONE, notes = listOf("Dry clean only; the cleaner handles the drying."), kind = kind)

        val airOnly = care.doNotTumbleDry || (!care.tumbleDry && care.naturalDry != null) || (t.wool && !care.tumbleDry)
        if (airOnly) {
            val airText = care.naturalDry?.let { AIR_TEXT[it] }
                ?: if (t.wool) "Lay it flat to dry to keep its shape." else "Air dry; do not use the dryer."
            val notes = mutableListOf<String>()
            if (t.wool && r.wool != null) notes += "The dryer's ${r.wool} program only fluffs and refreshes wool; it won't dry it. Lay flat afterwards."
            if (r.air != null) notes += "For a quick freshen-up only, ${r.air} uses no heat, but finish by air-drying."
            val warnings = if (care.doNotTumbleDry) listOf("The tag says DO NOT tumble dry.") else emptyList()
            return DryPlan(mode = DryMode.AIR, airText = airText, notes = notes, warnings = warnings, kind = kind)
        }

        val warnings = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val options = mutableListOf<String>()
        val heat = when (care.tumbleHeat) {
            TumbleHeat.NO_HEAT -> DryHeat.NONE
            TumbleHeat.MEDIUM -> DryHeat.MEDIUM
            TumbleHeat.HIGH -> DryHeat.HIGH
            TumbleHeat.LOW, null -> DryHeat.LOW
        }
        if (!care.tumbleDry && care.tumbleHeat == null) {
            warnings += "No tumble-dry symbol on the tag. Low heat is the safe bet, or air dry."
        }

        val byHeat = { h: DryHeat ->
            when (h) {
                DryHeat.NONE -> r.air ?: r.low
                DryHeat.LOW -> r.low
                DryHeat.MEDIUM -> r.medium
                DryHeat.HIGH -> r.high
            }
        }
        var programName = when {
            heat == DryHeat.NONE -> byHeat(heat)
            t.denim && r.jeans != null -> r.jeans
            t.towel && r.towels != null -> r.towels
            else -> byHeat(heat)
        }
        var program = dryer.program(programName) ?: dryer.programs.first()
        if (program.heat.rank > heat.rank) {
            // A fabric cycle that runs hotter than the tag allows: drop to Low
            // Heat if the machine offers it on that cycle, otherwise change cycle.
            if (program.lowHeatOption && heat == DryHeat.LOW) {
                options += "Low Heat: the tag allows low heat only"
            } else {
                programName = byHeat(heat)
                program = dryer.program(programName) ?: program
            }
        }
        if (heat == DryHeat.LOW && program.name == r.low) notes += "Low heat protects elastic, prints and anything that could shrink."
        if (heat == DryHeat.HIGH && program.name == r.high) notes += "High heat is allowed; ${r.medium} is kinder to the fabric if you have the time."
        options += "Wrinkle Block: if you won't unload right away"

        return DryPlan(mode = DryMode.MACHINE, program = program, heat = heat, options = options, notes = notes, warnings = warnings, kind = kind)
    }
}
