package com.laundrycare.care

/*
 * The machines the recommendations are written for.
 *
 * The Bosch pair is transcribed from the program selection tables in the
 * WAT28401UC and WTG86401UC use-and-care manuals: which temperatures and spin
 * speeds each cycle actually offers, and how full the drum may be. A generic
 * pair is kept for anyone with different appliances; it uses the names most
 * front-loaders share.
 */

enum class WashTemp(val label: String, val maxC: Int) {
    COLD("Cold", 30),
    WARM("Warm", 40),
    HOT("Hot", 60),
    EXTRA_HOT("Extra Hot", 95),
}

enum class Spin(val label: String, val rank: Int) {
    NO_SPIN("No Spin", 0),
    LOW("Low", 1),
    MED("Med", 2),
    HIGH("High", 3),
}

enum class LoadFraction(val label: String, val value: Double, val tight: Boolean) {
    FULL("Full drum", 1.0, false),
    HALF("½ drum", 0.5, false),
    THIRD("⅓ drum", 1.0 / 3, true),
    QUARTER("¼ drum", 0.25, true),
    ONE_ITEM("One item at a time", 0.5, true),
}

enum class DryHeat(val label: String, val rank: Int) {
    NONE("No heat (air only)", 0),
    LOW("Low heat", 1),
    MEDIUM("Medium heat", 2),
    HIGH("High heat", 3),
}

data class WasherProgram(
    val name: String,
    val maxLoad: LoadFraction,
    val temps: List<WashTemp>,
    val spins: List<Spin>,
    val options: List<String> = emptyList(),
    val forText: String = "",
)

data class DryerProgram(
    val name: String,
    val maxLoad: LoadFraction,
    val heat: DryHeat,
    /** Whether the machine offers a Low Heat option on this program. */
    val lowHeatOption: Boolean = false,
    val forText: String = "",
)

/** Which program plays which role, so the recommender never hard-codes a name. */
data class WasherRoles(
    val normal: String,
    val light: String? = null,
    val permPress: String,
    val delicates: String,
    val wool: String? = null,
    val jeans: String? = null,
    val towels: String? = null,
    val sanitize: String? = null,
)

data class DryerRoles(
    val high: String,
    val medium: String,
    val low: String,
    val air: String? = null,
    val wool: String? = null,
    val jeans: String? = null,
    val towels: String? = null,
)

data class Washer(
    val model: String,
    val name: String,
    val drumLiters: Int,
    val maxKg: Double?,
    val programs: List<WasherProgram>,
    val roles: WasherRoles,
    val optionNotes: List<String> = emptyList(),
) {
    fun program(name: String?): WasherProgram? = programs.firstOrNull { it.name == name }
}

data class Dryer(
    val model: String,
    val name: String,
    val drumLiters: Int,
    val maxKg: Double?,
    /** Wet laundry needs more tumbling room than dry; a volume multiplier. */
    val wetFactor: Double,
    val programs: List<DryerProgram>,
    val roles: DryerRoles,
    val optionNotes: List<String> = emptyList(),
) {
    fun program(name: String?): DryerProgram? = programs.firstOrNull { it.name == name }
}

data class MachinePair(val id: String, val label: String, val washer: Washer, val dryer: Dryer)

object Machines {

    private val ALL_TEMPS = listOf(WashTemp.COLD, WashTemp.WARM, WashTemp.HOT, WashTemp.EXTRA_HOT)
    private val TO_HOT = listOf(WashTemp.COLD, WashTemp.WARM, WashTemp.HOT)
    private val ALL_SPINS = listOf(Spin.NO_SPIN, Spin.LOW, Spin.MED, Spin.HIGH)
    private val TO_MED = listOf(Spin.NO_SPIN, Spin.LOW, Spin.MED)
    private val STD_OPTIONS = listOf("SpeedPerfect", "Prewash", "Extra Rinse +1 / +2")

    val BOSCH_500 = MachinePair(
        id = "bosch500",
        label = "Bosch 500 Series (WAT28401UC + WTG86401UC)",
        washer = Washer(
            model = "Bosch WAT28401UC/14",
            name = "Bosch 500 Series compact washer (24\", 2.2 cu ft)",
            drumLiters = 62,
            maxKg = 8.0,   // 17.7 lb per the manual, for regular cotton
            programs = listOf(
                WasherProgram("Cotton Normal", LoadFraction.FULL, ALL_TEMPS, listOf(Spin.HIGH), STD_OPTIONS,
                    "Sturdy cotton or linen that withstands very hot water"),
                WasherProgram("Cotton Light Soil", LoadFraction.HALF, ALL_TEMPS, ALL_SPINS, listOf("Prewash", "Extra Rinse +1 / +2"),
                    "Half load or less of lightly soiled sturdy cotton or linen"),
                WasherProgram("Perm Press Light Soil", LoadFraction.HALF, TO_HOT, ALL_SPINS, STD_OPTIONS,
                    "Cotton, linen, synthetic or blended fabrics"),
                WasherProgram("Perm Press Heavy Soil", LoadFraction.HALF, TO_HOT, ALL_SPINS, STD_OPTIONS,
                    "Heavily soiled cotton, linen, synthetic or blended fabrics"),
                WasherProgram("Delicates", LoadFraction.THIRD, TO_HOT, TO_MED, STD_OPTIONS,
                    "Delicate washable fabrics: cotton, satin, synthetics, blends"),
                WasherProgram("Hand Wash/Wool", LoadFraction.THIRD, TO_HOT, TO_MED, emptyList(),
                    "Hand- and machine-washable wool and wool blends; very gentle, with long pauses"),
                WasherProgram("Jeans", LoadFraction.HALF, TO_HOT, ALL_SPINS, STD_OPTIONS,
                    "Dark cotton and dark easy-care textiles, washed inside out"),
                WasherProgram("Towels", LoadFraction.HALF, ALL_TEMPS, ALL_SPINS, STD_OPTIONS,
                    "Durable cotton towels"),
                WasherProgram("Sanitize", LoadFraction.FULL, listOf(WashTemp.EXTRA_HOT), ALL_SPINS, STD_OPTIONS,
                    "Sturdy cotton or linen; longer wash and rinse with more water"),
                WasherProgram("Bulky/Large Items", LoadFraction.ONE_ITEM, ALL_TEMPS, ALL_SPINS, listOf("SpeedPerfect", "Extra Rinse +1 / +2"),
                    "Down- or synthetic-filled items such as pillows and duvets, one at a time"),
                WasherProgram("Quick 30", LoadFraction.HALF, TO_HOT, ALL_SPINS, emptyList(),
                    "Lightly soiled easy-care cotton, linen, synthetics or blends, in about 30 minutes"),
                WasherProgram("Rinse/Spin", LoadFraction.FULL, emptyList(), ALL_SPINS, listOf("Extra Rinse +1 / +2"),
                    "Rinse and spin, or spin only"),
                WasherProgram("Drum Clean", LoadFraction.FULL, listOf(WashTemp.EXTRA_HOT), listOf(Spin.HIGH), emptyList(),
                    "Empty drum; cleans the machine"),
                WasherProgram("Drain", LoadFraction.FULL, emptyList(), emptyList(), emptyList(), "Pumps the water out"),
            ),
            roles = WasherRoles(
                normal = "Cotton Normal", light = "Cotton Light Soil", permPress = "Perm Press Light Soil",
                delicates = "Delicates", wool = "Hand Wash/Wool", jeans = "Jeans", towels = "Towels", sanitize = "Sanitize",
            ),
            optionNotes = listOf(
                "SpeedPerfect: same result in less time; do not exceed the cycle's max load.",
                "Extra Rinse +1 / +2: for sensitive skin or very soft water.",
                "Prewash: heavily soiled loads; split the detergent ⅓ / ⅔ between chambers I and II.",
                "Finish in: delays the end of the cycle by up to 24 h.",
            ),
        ),
        dryer = Dryer(
            model = "Bosch WTG86401UC/01",
            name = "Bosch 500 Series compact ventless dryer (24\", 4.0 cu ft)",
            drumLiters = 113,
            maxKg = null,     // Bosch publishes no weight limit; drum space decides
            wetFactor = 1.6,
            programs = listOf(
                DryerProgram("Cotton Dry", LoadFraction.FULL, DryHeat.HIGH, true, "Normal cotton and linen"),
                DryerProgram("Cotton Extra Dry", LoadFraction.FULL, DryHeat.HIGH, true, "Thick cotton that must come out completely dry"),
                DryerProgram("Perm Press Damp", LoadFraction.HALF, DryHeat.MEDIUM, true, "Easy-care items left slightly damp for ironing"),
                DryerProgram("Perm Press Dry", LoadFraction.HALF, DryHeat.MEDIUM, true, "Easy-care synthetics and blends"),
                DryerProgram("Perm Press Extra Dry", LoadFraction.HALF, DryHeat.MEDIUM, true, "Easy-care items that must come out completely dry"),
                DryerProgram("Delicates", LoadFraction.QUARTER, DryHeat.LOW, false, "Delicate synthetics, lingerie, blends; low heat built in"),
                DryerProgram("Quick 40", LoadFraction.QUARTER, DryHeat.MEDIUM, true, "A few items that need drying fast"),
                DryerProgram("Quick Dry Auto", LoadFraction.THIRD, DryHeat.MEDIUM, true, "Small loads, sensor-controlled"),
                DryerProgram("Time Dry Variable", LoadFraction.THIRD, DryHeat.MEDIUM, true, "Pre-dried or small items; 20 min to 3 h"),
                DryerProgram("Hand Wash/Wool", LoadFraction.THIRD, DryHeat.LOW, false, "Refreshes and fluffs wool only; it does not dry it"),
                DryerProgram("Air Fluff/No Heat", LoadFraction.THIRD, DryHeat.NONE, false, "Freshens or airs lightly worn items"),
                DryerProgram("Towels", LoadFraction.HALF, DryHeat.HIGH, true, "Terry towels and bathrobes"),
                DryerProgram("Sanitize", LoadFraction.HALF, DryHeat.HIGH, false, "Cotton that tolerates high heat, for hygiene"),
                DryerProgram("Jeans", LoadFraction.HALF, DryHeat.MEDIUM, true, "Jeans and other denim"),
                DryerProgram("Bulky/Large Items", LoadFraction.ONE_ITEM, DryHeat.MEDIUM, true, "Down jackets, pillows, blankets; one at a time"),
            ),
            roles = DryerRoles(
                high = "Cotton Dry", medium = "Perm Press Dry", low = "Delicates", air = "Air Fluff/No Heat",
                wool = "Hand Wash/Wool", jeans = "Jeans", towels = "Towels",
            ),
            optionNotes = listOf(
                "Dry Level 0 … +3: drier in three steps, on programs that allow it.",
                "Low Heat: reduced temperature for delicate synthetics; takes longer.",
                "Wrinkle Block: tumbles now and then for 60 or 90 min after the end.",
                "Finish in: delays the end of the cycle by up to 24 h.",
            ),
        ),
    )

    val GENERIC = MachinePair(
        id = "generic",
        label = "Generic front-load washer and dryer",
        washer = Washer(
            model = "Any washer",
            name = "Generic front-load washer",
            drumLiters = 70,
            maxKg = 8.0,
            programs = listOf(
                WasherProgram("Normal / Cottons", LoadFraction.FULL, ALL_TEMPS, ALL_SPINS, emptyList(), "Sturdy cotton and linen"),
                WasherProgram("Permanent Press", LoadFraction.HALF, TO_HOT, TO_MED, emptyList(), "Synthetics and blends"),
                WasherProgram("Delicates", LoadFraction.THIRD, listOf(WashTemp.COLD, WashTemp.WARM), listOf(Spin.NO_SPIN, Spin.LOW), emptyList(), "Delicate fabrics"),
                WasherProgram("Hand Wash / Wool", LoadFraction.THIRD, listOf(WashTemp.COLD, WashTemp.WARM), listOf(Spin.NO_SPIN, Spin.LOW), emptyList(), "Wool and hand-wash items"),
                WasherProgram("Quick Wash", LoadFraction.HALF, TO_HOT, ALL_SPINS, emptyList(), "Lightly soiled small loads"),
            ),
            roles = WasherRoles(normal = "Normal / Cottons", permPress = "Permanent Press", delicates = "Delicates", wool = "Hand Wash / Wool"),
        ),
        dryer = Dryer(
            model = "Any dryer",
            name = "Generic tumble dryer",
            drumLiters = 150,
            maxKg = null,
            wetFactor = 1.6,
            programs = listOf(
                DryerProgram("Normal / High", LoadFraction.FULL, DryHeat.HIGH, false, "Cotton and linen"),
                DryerProgram("Permanent Press / Medium", LoadFraction.HALF, DryHeat.MEDIUM, false, "Synthetics and blends"),
                DryerProgram("Delicate / Low", LoadFraction.THIRD, DryHeat.LOW, false, "Delicates and anything that may shrink"),
                DryerProgram("Air Fluff", LoadFraction.THIRD, DryHeat.NONE, false, "No heat"),
            ),
            roles = DryerRoles(high = "Normal / High", medium = "Permanent Press / Medium", low = "Delicate / Low", air = "Air Fluff"),
        ),
    )

    val ALL = listOf(BOSCH_500, GENERIC)
    val DEFAULT = BOSCH_500

    fun byId(id: String?): MachinePair = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
