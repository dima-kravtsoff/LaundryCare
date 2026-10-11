package com.laundrycare.care

import com.laundrycare.data.CareProfile
import com.laundrycare.data.Garment

/** Plain-language summaries of a care profile, for the detail screen and for sharing. */
object CareText {

    enum class Row(val label: String) {
        FABRIC("Fabric"), WASH("Washing"), TEMP("Wash temp & action"), BLEACH("Bleach"),
        DRY("Drying"), IRON("Ironing"), DRY_CLEAN("Dry clean"), COLOUR("Colour"), NOTES("Notes"),
    }

    fun rows(care: CareProfile, garment: Garment? = null): List<Pair<Row, String>> {
        val rows = mutableListOf<Pair<Row, String>>()
        if (care.fabric.isNotBlank()) rows += Row.FABRIC to care.fabric
        rows += Row.WASH to when {
            care.doNotWash -> "Do not wash"
            care.handWashOnly -> "Hand wash only"
            care.dryCleanOnly -> "Dry clean only"
            else -> "Machine washable"
        }
        if (!care.doNotWash) {
            val t = care.washTempC?.let { "Max $it°C" } ?: "No temperature shown"
            val a = care.washAction?.let { " · " + it.label.substringBefore(" (").lowercase() } ?: ""
            rows += Row.TEMP to (t + a)
        }
        rows += Row.BLEACH to (care.bleach?.label ?: "Not specified")
        rows += Row.DRY to when {
            care.doNotTumbleDry -> "Do not tumble dry" + (care.naturalDry?.let { "; ${it.label.lowercase()}" } ?: "")
            care.tumbleDry -> "Tumble dry, " + (care.tumbleHeat?.label?.lowercase() ?: "heat not shown")
            care.naturalDry != null -> "Air dry: ${care.naturalDry.label.lowercase()}"
            else -> "Not specified"
        }
        rows += Row.IRON to (care.iron?.label ?: "Not specified")
        care.dryClean?.let { rows += Row.DRY_CLEAN to it.label }
        garment?.colorGroup?.let { rows += Row.COLOUR to it.label }
        val notes = buildList {
            if (care.washSeparately) add("Wash separately")
            if (care.specialNotes.isNotBlank()) add(care.specialNotes)
        }
        if (notes.isNotEmpty()) rows += Row.NOTES to notes.joinToString(". ")
        return rows
    }

    fun share(garment: Garment, wash: WashPlan, dry: DryPlan, machines: MachinePair): String = buildString {
        appendLine(garment.name)
        if (garment.care.fabric.isNotBlank()) appendLine(garment.care.fabric)
        appendLine()
        appendLine("WASH — ${machines.washer.model}")
        if (wash.program != null) {
            appendLine("Program: ${wash.program.name}")
            wash.temp?.let { appendLine("Temperature: ${it.label}") }
            wash.spin?.let { appendLine("Spin: ${it.label}") }
            appendLine("Max load: ${wash.program.maxLoad.label}")
            wash.options.forEach { appendLine("Option: $it") }
        }
        wash.warnings.forEach { appendLine("! $it") }
        wash.notes.forEach { appendLine("- $it") }
        wash.extras.forEach { appendLine("- ${it.text}") }
        appendLine()
        appendLine("DRY — ${machines.dryer.model}")
        when (dry.mode) {
            DryMode.MACHINE -> {
                appendLine("Program: ${dry.program?.name}")
                dry.heat?.let { appendLine("Heat: ${it.label}") }
                dry.program?.let { appendLine("Max load: ${it.maxLoad.label}") }
                dry.options.forEach { appendLine("Option: $it") }
            }
            DryMode.AIR -> appendLine("Air dry. ${dry.airText ?: ""}")
            DryMode.NONE -> {}
        }
        dry.warnings.forEach { appendLine("! $it") }
        dry.notes.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Tag:")
        rows(garment.care, garment).forEach { (row, value) -> appendLine("${row.label}: $value") }
    }
}
