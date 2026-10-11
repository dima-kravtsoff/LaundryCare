package com.laundrycare.ai

import com.laundrycare.data.Bleach
import com.laundrycare.data.CareFields
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.DryClean
import com.laundrycare.data.GarmentKind
import com.laundrycare.data.Iron
import com.laundrycare.data.NaturalDry
import com.laundrycare.data.TumbleHeat
import com.laundrycare.data.WashAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The questions put to the model, and how its answers are read back. Shared by both providers. */
object CarePrompts {

    val LOW_CONFIDENCE_FIELDS = listOf(
        CareFields.WASH_TEMP, CareFields.WASH_ACTION, CareFields.BLEACH, CareFields.TUMBLE_HEAT,
        CareFields.NATURAL_DRY, CareFields.IRON, CareFields.DRY_CLEAN, CareFields.FABRIC,
    )

    val careSchema = SObj(
        listOf(
            // First on purpose: writing out each symbol with its dot count makes
            // the model actually count before it commits to the fields below.
            SProp("symbols_transcript", SStr(description = "Before anything else, list every care symbol on the label(s) left to right, one per line, each with the exact count of dots, bars, lines or the letter inside it, e.g. 'wash tub, 30 inside, 2 bars under' / 'triangle, 2 diagonal lines' / 'tumble-dry circle in square, 2 dots' / 'iron, 1 dot' / 'dry-clean circle, crossed out'. Then transcribe any printed text.")),
            SProp("garment_guess", SStr(description = "Short name for the garment, e.g. 'Navy cotton crewneck t-shirt'")),
            SProp("visual_description", SStr(description = "One sentence on how the garment looks, so it can be picked out of a laundry basket later: type, colour, pattern, knit or weave, collar and sleeve style, prints, logos, hardware. Empty if no garment photo was given.")),
            SProp("color_group", SStr(ColorGroup.entries.map { it.wire }, "Overall colour of the garment for sorting laundry: white, light/pastel, dark, bright saturated colour, or mixed/patterned."), nullable = true),
            SProp("garment_kind", SStr(GarmentKind.entries.map { it.wire }, "What kind of item this is, from the garment photo; used to estimate how many fit in a load."), nullable = true),
            SProp("fabric", SStr(description = "Fibre content from the tag, e.g. '100% cotton'; empty if not shown")),
            SProp("machine_wash", SBool()),
            SProp("hand_wash", SBool()),
            SProp("do_not_wash", SBool()),
            SProp("wash_temp_c", SInt("Max wash temperature in Celsius if shown by number or dots; else null"), nullable = true),
            SProp("wash_action", SStr(WashAction.entries.map { it.wire }, "Bars under the wash tub: none = normal, one = permanent_press, two = gentle"), nullable = true),
            SProp("bleach", SStr(Bleach.entries.map { it.wire }), nullable = true),
            SProp("tumble_dry", SBool()),
            SProp("do_not_tumble_dry", SBool()),
            SProp("tumble_heat", SStr(TumbleHeat.entries.map { it.wire }, "Dots inside the tumble-dry circle: one = low, two = medium, three = high, no dots = medium, filled circle = no_heat"), nullable = true),
            SProp("natural_dry", SStr(NaturalDry.entries.map { it.wire }, "Only if tumble drying is not allowed"), nullable = true),
            SProp("iron", SStr(Iron.entries.map { it.wire }), nullable = true),
            SProp("dry_clean", SStr(DryClean.entries.map { it.wire }), nullable = true),
            SProp("wash_separately", SBool("True only if the text says wash separately, wash alone, may bleed, or colour may transfer")),
            SProp("special_notes", SStr(description = "Any written instructions or fabric keywords: wool, silk, denim, towel, 'wash inside out', 'wash with like colours', 'do not wring', etc.")),
            SProp("low_confidence", SArr(SStr(LOW_CONFIDENCE_FIELDS), "Field names you INFERRED or guessed rather than clearly read. An empty list claims every value was plainly legible.")),
        )
    )

    val matchSchema = SObj(
        listOf(
            SProp(
                "candidates",
                SArr(
                    SObj(
                        listOf(
                            SProp("index", SInt("The Item number from the closet list")),
                            SProp("confidence", SStr(listOf("high", "medium", "low"))),
                            SProp("reason", SStr(description = "One short sentence on what matched or differed (colour, pattern, type, details).")),
                        )
                    ),
                    "Up to 3 closet items that could be the photographed garment, best first.",
                ),
            ),
            SProp("no_match", SBool("True if none of the closet items plausibly match the photo.")),
        )
    )

    const val CARE_PROMPT = """You are a laundry care-label expert. You get photo(s) of ONE garment and photo(s) of its sewn-in CARE LABEL / tag.
Read every international (ISO 3758 / GINETEX) and North American (ASTM D5489) laundry symbol AND any printed text, then fill the JSON.

Rules for reading symbols:
- Wash tub = washing. A number inside = max °C. Dots inside: 1 dot ≈ 30 °C, 2 ≈ 40, 3 ≈ 50, 4 ≈ 60, 5 ≈ 70, 6 ≈ 95. A hand in the tub = hand_wash only (machine_wash false). Tub crossed out = do_not_wash.
- Bars under the wash tub = mechanical action: no bar = "normal", one bar = "permanent_press", two bars = "gentle".
- Triangle = bleach: empty = "any", two diagonal lines inside = "non_chlorine", crossed out = "none".
- Square with a circle = tumble dry (tumble_dry true). Dots inside: 1 = "low", 2 = "medium", 3 = "high"; no dots at all = "medium" (normal). A filled black circle = "no_heat". Crossed out = do_not_tumble_dry (tumble_dry false).
- Plain square symbols = natural drying (only relevant when tumble drying is not allowed): a vertical line inside = "line" (hang), a horizontal line = "flat", three vertical lines = "drip", an added diagonal in the corner = the "_shade" variants.
- Iron symbol dots: 1 = "low", 2 = "medium", 3 = "high"; crossed out = "none".
- Circle = dry cleaning: F / P / W inside = "petroleum" / "pce" / "gentle", a plain circle = "any", crossed out = "do_not".
- Put the fibre content in "fabric". Put written phrases ("wash inside out", "wash with like colours", "wash separately", "do not wring", "reshape whilst damp") and fabric words (wool, silk, denim, towel, cashmere) in "special_notes". Set wash_separately true only when the text says wash separately / wash alone / may bleed / colour may transfer.
- From the GARMENT photos, not the label: give a short name in garment_guess, the garment_kind, the color_group, and a one-sentence visual_description that would let someone pick this exact garment out of a laundry basket.
- If a value truly cannot be determined, use null for the nullable fields.

COUNT, DON'T GUESS. Fill symbols_transcript first: go symbol by symbol, left to right, and write down the exact number of dots inside the tumble-dry circle and the iron, and the exact number of bars under the wash tub, as you see them. Dots are usually drawn large and clearly separated; one dot sits in the centre, two dots sit side by side, three in a row. Then set tumble_heat, iron and wash_action from your own transcript. A plain tumble-dry square with a circle and no dots is "medium". Do not let the fibre content (cotton, synthetic) influence the counts.

THE BARS UNDER THE WASH TUB are the hardest detail. They are thin horizontal lines directly beneath the tub, often stitched as dashed or dotted lines on woven labels, and when there are two they sit very close together. Look for a narrow gap that splits the underline into two separate lines. An underline that looks thick or doubled is TWO bars, not one; a single bar is as thin as the tub's own outline. Report the number of distinct horizontal lines, and if you cannot resolve them, list wash_action in low_confidence.

Many labels print the same symbols twice: an ISO row and a North American row (the second often has a stylised tub and a sun-like dryer circle). The two rows always agree, so read whichever is clearer and do not report the symbols twice.

BE HONEST ABOUT WHAT YOU COULD NOT SEE. The bars under the wash tub are small and are the single most misread detail on a care label. If the image is blurred, angled, low-resolution, overexposed, or the bars are not plainly countable, say so by listing "wash_action" in low_confidence rather than guessing. The same goes for counting the dots inside the tumble-dry circle or the iron. List in "low_confidence" every field you inferred from context, fibre type or likelihood instead of plainly reading it. Listing nothing claims you read every value clearly, so only do that when it is true.

Return ONLY the JSON object."""

    const val MATCH_PROMPT = """You identify which saved garment a new photo shows.
You get one PHOTO TO IDENTIFY, then a numbered CLOSET list. Each item has a name, usually a written description of its look, often a small reference photo, and sometimes fabric or notes.

Compare on: garment type (t-shirt vs sweater vs trousers), colour, pattern, knit or weave texture, collar and sleeve style, buttons, prints, logos, and any distinctive details.
Allow for different lighting, angle, wrinkles, folding and background: the same garment can look quite different photographed on a hanger versus crumpled in a laundry basket. Judge the garment itself, not the setting.

Return up to 3 candidates ranked best first, using the Item numbers exactly as given.
Set confidence honestly: "high" only when distinctive features clearly agree; "medium" when the type and colour match but details are unclear; "low" for a weak guess.
If nothing in the closet plausibly matches, set no_match to true and return an empty candidates list. Never invent an Item number that was not listed.
Return ONLY the JSON object."""

    fun readTagParts(tagPhotos: List<ByteArray>, garmentPhotos: List<ByteArray>, symbolZooms: List<ByteArray> = emptyList()): List<Part> {
        require(tagPhotos.isNotEmpty()) { "Add at least one photo of the care tag first." }
        val parts = mutableListOf<Part>(Part.Text(CARE_PROMPT))
        if (garmentPhotos.isNotEmpty()) {
            val n = garmentPhotos.size.coerceAtMost(3)
            parts += Part.Text("THE GARMENT ($n photo${if (n > 1) "s" else ""}, for naming and describing it):")
            garmentPhotos.take(3).forEach { parts += Part.Image(it) }
        }
        parts += Part.Text(
            if (tagPhotos.size > 1)
                "THE CARE LABELS: ${tagPhotos.size} photos of this ONE garment's tags. They may show different parts (fibre content on one, symbols on another) or the same tag twice. Read them ALL and merge into a single care profile; if two disagree, prefer the clearer image."
            else "THE CARE LABEL / tag (read this carefully):"
        )
        tagPhotos.forEach { parts += Part.Image(it) }
        if (symbolZooms.isNotEmpty()) {
            parts += Part.Text(
                "ZOOMED SYMBOL ROW (${symbolZooms.size} image${if (symbolZooms.size > 1) "s" else ""}): the care symbols cut out of the label photo${if (symbolZooms.size > 1) "s" else ""} above at full resolution and enlarged. " +
                    "COUNT THE DOTS AND BARS IN THESE ZOOMED IMAGES, not in the small overview. Every dot is a separate filled circle; count each one."
            )
            symbolZooms.forEach { parts += Part.Image(it) }
        }
        return parts
    }

    val locateSchema = SObj(
        listOf(
            SProp("found", SBool("True if the photo shows a row of laundry care symbols")),
            SProp("ymin", SInt("Top edge of the box around ALL the symbols, 0–1000 of image height")),
            SProp("xmin", SInt("Left edge, 0–1000 of image width")),
            SProp("ymax", SInt("Bottom edge, 0–1000 of image height")),
            SProp("xmax", SInt("Right edge, 0–1000 of image width")),
        )
    )

    const val LOCATE_PROMPT = """This is a photo of a clothing care label. Find the laundry care SYMBOLS: the wash tub, the triangle, the square (with or without a circle), the iron, and the dry-cleaning circle. They are usually printed in a row, sometimes in two rows.
Return one bounding box that encloses ALL of the symbols and nothing else, with a small margin (about 3% of the image) around them, as integers from 0 to 1000 relative to the image height (ymin, ymax) and width (xmin, xmax). If there are no symbols in the photo, set found to false.
Return ONLY the JSON object."""

    fun locateParts(tagPhoto: ByteArray): List<Part> = listOf(Part.Text(LOCATE_PROMPT), Part.Image(tagPhoto))

    /** A sane box or null: at least a sliver of the image, not the whole thing, edges in order. */
    fun parseBox(j: JsonObject): IntArray? {
        if (j["found"].bool() == false) return null
        val ymin = j["ymin"].int() ?: return null
        val xmin = j["xmin"].int() ?: return null
        val ymax = j["ymax"].int() ?: return null
        val xmax = j["xmax"].int() ?: return null
        val box = intArrayOf(ymin.coerceIn(0, 1000), xmin.coerceIn(0, 1000), ymax.coerceIn(0, 1000), xmax.coerceIn(0, 1000))
        val h = box[2] - box[0]
        val w = box[3] - box[1]
        if (h < 30 || w < 80) return null
        if (h > 950 && w > 950) return null
        return box
    }

    fun matchParts(query: ByteArray, closet: List<MatchInput>): List<Part> {
        val parts = mutableListOf<Part>(
            Part.Text(MATCH_PROMPT),
            Part.Text("PHOTO TO IDENTIFY:"),
            Part.Image(query),
            Part.Text("CLOSET: ${closet.size} saved item(s):"),
        )
        closet.forEach { c ->
            var label = "Item ${c.index}: \"${c.name}\""
            if (c.description.isNotBlank()) label += " — looks like: ${c.description}"
            if (c.fabric.isNotBlank()) label += " — fabric: ${c.fabric}"
            if (c.notes.isNotBlank()) label += " — notes: ${c.notes}"
            parts += Part.Text(label)
            val thumb = c.thumb
            if (thumb != null) parts += Part.Image(thumb)
            else parts += Part.Text("(no photo for this item; match by the description)")
        }
        return parts
    }

    fun parseCare(j: JsonObject): CareRead {
        val lowConfidence = j["low_confidence"].arr()
            ?.mapNotNull { it.str() }
            ?.filter { it in LOW_CONFIDENCE_FIELDS }
            ?.distinct()
            ?: emptyList()
        val machineWash = j["machine_wash"].bool() ?: false
        val handWash = j["hand_wash"].bool() ?: false
        val doNotWash = j["do_not_wash"].bool() ?: false
        val dryClean = DryClean.fromWire(j["dry_clean"].str())
        val dryCleanOnly = !machineWash && !handWash && !doNotWash && dryClean != null && dryClean != DryClean.DO_NOT
        val care = CareProfile(
            fabric = j["fabric"].str().orEmpty().trim(),
            // A tag with no washing symbol at all is still usually machine washable;
            // only an explicit hand-wash, do-not-wash or dry-clean-only takes that
            // away. Without this, "no washing of any kind" would be stored and then
            // still be recommended a Cotton Normal cycle.
            machineWash = machineWash || (!handWash && !doNotWash && !dryCleanOnly),
            handWash = handWash,
            doNotWash = doNotWash,
            washTempC = j["wash_temp_c"].int()?.takeIf { it in 10..100 },
            washAction = WashAction.fromWire(j["wash_action"].str()),
            bleach = Bleach.fromWire(j["bleach"].str()),
            tumbleDry = j["tumble_dry"].bool() ?: false,
            doNotTumbleDry = j["do_not_tumble_dry"].bool() ?: false,
            tumbleHeat = TumbleHeat.fromWire(j["tumble_heat"].str()),
            naturalDry = NaturalDry.fromWire(j["natural_dry"].str()),
            iron = Iron.fromWire(j["iron"].str()),
            dryClean = dryClean,
            washSeparately = j["wash_separately"].bool() ?: false,
            specialNotes = j["special_notes"].str().orEmpty().trim(),
            garmentKind = GarmentKind.fromWire(j["garment_kind"].str()),
            lowConfidence = lowConfidence,
        )
        return CareRead(
            care = care,
            garmentGuess = j["garment_guess"].str().orEmpty().trim(),
            colorGroup = ColorGroup.fromWire(j["color_group"].str()),
            description = j["visual_description"].str().orEmpty().trim(),
            transcript = j["symbols_transcript"].str().orEmpty().trim(),
        )
    }

    fun parseMatch(j: JsonObject, closetSize: Int): MatchResult {
        val seen = HashSet<Int>()
        val candidates = j["candidates"].arr().orEmpty().mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val i = o["index"].int() ?: return@mapNotNull null
            if (i < 0 || i >= closetSize || !seen.add(i)) return@mapNotNull null
            val conf = o["confidence"].str()?.lowercase()?.takeIf { it in setOf("high", "medium", "low") } ?: "low"
            MatchCandidate(i, conf, o["reason"].str().orEmpty())
        }.take(3)
        val noMatch = (j["no_match"].bool() ?: false) || candidates.isEmpty()
        return MatchResult(candidates, noMatch)
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The JSON object in a model reply, tolerating code fences and stray prose around it. */
    fun extractJson(text: String): JsonObject {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return lenient.parseToJsonElement(trimmed).obj() ?: error("not an object") }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("Could not read the model's answer. Try a clearer, well-lit photo.", retryable = true)
        return runCatching { lenient.parseToJsonElement(trimmed.substring(start, end + 1)).obj() }.getOrNull()
            ?: throw AiException("Could not read the model's answer. Try a clearer, well-lit photo.", retryable = true)
    }
}
