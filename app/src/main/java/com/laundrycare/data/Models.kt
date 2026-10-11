package com.laundrycare.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Everything the app stores, and the vocabulary the AI reader fills in.
 *
 * Each enum carries its `wire` name: the snake_case value the care-label
 * schema uses when talking to Gemini or Claude, and the value written into
 * the closet file. Keeping the two identical means a backup file reads like
 * the AI's answer and can be hand-edited.
 */

@Serializable
enum class WashAction(val wire: String, val label: String) {
    @SerialName("normal") NORMAL("normal", "Normal"),
    @SerialName("permanent_press") PERMANENT_PRESS("permanent_press", "Permanent press (1 bar)"),
    @SerialName("gentle") GENTLE("gentle", "Gentle / delicate (2 bars)");

    companion object { fun fromWire(s: String?): WashAction? = entries.firstOrNull { it.wire == s } }
}

@Serializable
enum class Bleach(val wire: String, val label: String) {
    @SerialName("any") ANY("any", "Any bleach OK"),
    @SerialName("non_chlorine") NON_CHLORINE("non_chlorine", "Non-chlorine only"),
    @SerialName("none") NONE("none", "Do not bleach");

    companion object { fun fromWire(s: String?): Bleach? = entries.firstOrNull { it.wire == s } }
}

@Serializable
enum class TumbleHeat(val wire: String, val label: String) {
    @SerialName("no_heat") NO_HEAT("no_heat", "No heat / air"),
    @SerialName("low") LOW("low", "Low heat"),
    @SerialName("medium") MEDIUM("medium", "Medium heat"),
    @SerialName("high") HIGH("high", "High heat");

    companion object { fun fromWire(s: String?): TumbleHeat? = entries.firstOrNull { it.wire == s } }
}

@Serializable
enum class NaturalDry(val wire: String, val label: String) {
    @SerialName("line") LINE("line", "Hang / line dry"),
    @SerialName("flat") FLAT("flat", "Lay flat"),
    @SerialName("drip") DRIP("drip", "Drip dry"),
    @SerialName("line_shade") LINE_SHADE("line_shade", "Hang in the shade"),
    @SerialName("flat_shade") FLAT_SHADE("flat_shade", "Flat, in the shade");

    companion object { fun fromWire(s: String?): NaturalDry? = entries.firstOrNull { it.wire == s } }
}

@Serializable
enum class Iron(val wire: String, val label: String) {
    @SerialName("none") NONE("none", "Do not iron"),
    @SerialName("low") LOW("low", "Low (1 dot)"),
    @SerialName("medium") MEDIUM("medium", "Medium (2 dots)"),
    @SerialName("high") HIGH("high", "High (3 dots)");

    companion object { fun fromWire(s: String?): Iron? = entries.firstOrNull { it.wire == s } }
}

@Serializable
enum class DryClean(val wire: String, val label: String) {
    @SerialName("any") ANY("any", "Dry-clean safe"),
    @SerialName("do_not") DO_NOT("do_not", "Do not dry clean"),
    @SerialName("petroleum") PETROLEUM("petroleum", "Dry clean (petroleum solvent)"),
    @SerialName("pce") PCE("pce", "Dry clean (PCE solvent)"),
    @SerialName("gentle") GENTLE("gentle", "Dry clean (gentle)");

    companion object { fun fromWire(s: String?): DryClean? = entries.firstOrNull { it.wire == s } }
}

/** Overall colour, for keeping whites away from anything that could bleed into them. */
@Serializable
enum class ColorGroup(val wire: String, val label: String) {
    @SerialName("white") WHITE("white", "White"),
    @SerialName("light") LIGHT("light", "Light / pastel"),
    @SerialName("dark") DARK("dark", "Dark"),
    @SerialName("bright") BRIGHT("bright", "Bright colour"),
    @SerialName("mixed") MIXED("mixed", "Mixed / patterned");

    companion object { fun fromWire(s: String?): ColorGroup? = entries.firstOrNull { it.wire == s } }
}

/**
 * What kind of item this is, so "⅓ drum" can be turned into "about 4 of these".
 *
 * `grams` is a typical dry weight; `liters` is the room it takes loosely placed
 * in a drum, not its folded volume (clothes pack badly). Both are deliberate
 * estimates, and the UI says so.
 */
@Serializable
enum class GarmentKind(val wire: String, val label: String, val plural: String, val grams: Int, val liters: Double) {
    @SerialName("tshirt") TSHIRT("tshirt", "T-shirt", "T-shirts", 175, 3.0),
    @SerialName("shirt") SHIRT("shirt", "Shirt / blouse", "shirts", 220, 3.5),
    @SerialName("jumper") JUMPER("jumper", "Sweater / hoodie", "sweaters", 450, 6.0),
    @SerialName("jeans") JEANS("jeans", "Jeans", "pairs of jeans", 700, 5.0),
    @SerialName("trousers") TROUSERS("trousers", "Trousers / shorts", "pairs of trousers", 400, 4.0),
    @SerialName("dress") DRESS("dress", "Dress / skirt", "dresses", 300, 3.5),
    @SerialName("underwear") UNDERWEAR("underwear", "Underwear", "pieces of underwear", 60, 0.8),
    @SerialName("socks") SOCKS("socks", "Socks", "pairs of socks", 50, 0.6),
    @SerialName("towel") TOWEL("towel", "Towel", "bath towels", 650, 6.0),
    @SerialName("bedding") BEDDING("bedding", "Bedding", "sheets", 800, 10.0),
    @SerialName("jacket") JACKET("jacket", "Jacket / coat", "jackets", 800, 10.0),
    @SerialName("leggings") LEGGINGS("leggings", "Leggings / tights", "leggings", 200, 2.0),
    @SerialName("other") OTHER("other", "Other", "items", 250, 3.5);

    companion object { fun fromWire(s: String?): GarmentKind? = entries.firstOrNull { it.wire == s } }
}

/** The field names the AI may list as guessed rather than read, with labels for the review screen. */
object CareFields {
    const val FABRIC = "fabric"
    const val WASH_TEMP = "wash_temp_c"
    const val WASH_ACTION = "wash_action"
    const val BLEACH = "bleach"
    const val TUMBLE_HEAT = "tumble_heat"
    const val NATURAL_DRY = "natural_dry"
    const val IRON = "iron"
    const val DRY_CLEAN = "dry_clean"

    val LABELS = linkedMapOf(
        WASH_TEMP to "Max wash temperature",
        WASH_ACTION to "Wash action (bars under the tub)",
        BLEACH to "Bleach",
        TUMBLE_HEAT to "Tumble heat",
        NATURAL_DRY to "Air-dry method",
        IRON to "Ironing",
        DRY_CLEAN to "Dry cleaning",
        FABRIC to "Fabric",
    )
}

@Serializable
data class CareProfile(
    val fabric: String = "",
    val machineWash: Boolean = true,
    val handWash: Boolean = false,
    val doNotWash: Boolean = false,
    val washTempC: Int? = null,
    val washAction: WashAction? = WashAction.NORMAL,
    val bleach: Bleach? = null,
    val tumbleDry: Boolean = true,
    val doNotTumbleDry: Boolean = false,
    val tumbleHeat: TumbleHeat? = TumbleHeat.LOW,
    val naturalDry: NaturalDry? = null,
    val iron: Iron? = null,
    val dryClean: DryClean? = null,
    /** The tag says wash separately / may bleed. */
    val washSeparately: Boolean = false,
    val specialNotes: String = "",
    val garmentKind: GarmentKind? = null,
    /** Fields the AI inferred rather than read; see [CareFields]. Emptied as you confirm them. */
    val lowConfidence: List<String> = emptyList(),
) {
    fun isUnsure(field: String) = field in lowConfidence
    fun confirmed(field: String) = if (field in lowConfidence) copy(lowConfidence = lowConfidence - field) else this

    val handWashOnly: Boolean get() = handWash && !machineWash && !doNotWash
    val dryCleanOnly: Boolean
        get() = !doNotWash && !machineWash && !handWash && dryClean != null && dryClean != DryClean.DO_NOT

    /** Everything the AI flagged, in display order, with labels. */
    val unsureLabels: List<String> get() = CareFields.LABELS.filterKeys { it in lowConfidence }.values.toList()

    companion object {
        /** Sensible starting values for a tag typed in by hand. */
        fun blank() = CareProfile(bleach = Bleach.NON_CHLORINE, iron = Iron.MEDIUM)
    }
}

@Serializable
data class Garment(
    val id: String,
    val name: String,
    val care: CareProfile = CareProfile(),
    /** File names inside the photo store, main photo first. */
    val garmentPhotos: List<String> = emptyList(),
    val tagPhotos: List<String> = emptyList(),
    val colorGroup: ColorGroup? = null,
    /** One sentence from the AI describing the look, used to find the garment from a photo without re-uploading every reference photo. */
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
) {
    val coverPhoto: String? get() = garmentPhotos.firstOrNull() ?: tagPhotos.firstOrNull()
    val allPhotos: List<String> get() = garmentPhotos + tagPhotos
}

/** The closet on disk, and the backup file. */
@Serializable
data class ClosetFile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val app: String = "Closet Care",
    val exportedAt: Long? = null,
    val garments: List<Garment> = emptyList(),
) {
    companion object { const val SCHEMA_VERSION = 1 }
}
