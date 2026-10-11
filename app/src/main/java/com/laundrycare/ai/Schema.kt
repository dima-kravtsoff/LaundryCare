package com.laundrycare.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * One schema, two dialects. Gemini wants the OpenAPI subset (upper-case
 * types, `nullable: true`); Claude's structured outputs want standard JSON
 * Schema (`additionalProperties: false`, nullability as `anyOf`). Describing
 * the schema once here keeps the two providers answering the same questions.
 */

sealed interface Schema

data class SObj(val props: List<SProp>, val description: String? = null) : Schema
data class SStr(val enum: List<String>? = null, val description: String? = null) : Schema
data class SInt(val description: String? = null) : Schema
data class SBool(val description: String? = null) : Schema
data class SArr(val items: Schema, val description: String? = null) : Schema

data class SProp(val name: String, val schema: Schema, val nullable: Boolean = false)

fun Schema.toGemini(nullable: Boolean = false): JsonObject = buildJsonObject {
    when (val s = this@toGemini) {
        is SObj -> {
            put("type", "OBJECT")
            putJsonObject("properties") { s.props.forEach { put(it.name, it.schema.toGemini(it.nullable)) } }
            val required = s.props.filter { !it.nullable }.map { it.name }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
            // Gemini otherwise emits properties alphabetically; the transcript must come first.
            putJsonArray("propertyOrdering") { s.props.forEach { add(it.name) } }
            s.description?.let { put("description", it) }
        }
        is SStr -> {
            put("type", "STRING")
            s.enum?.let { e -> putJsonArray("enum") { e.forEach { add(it) } } }
            s.description?.let { put("description", it) }
        }
        is SInt -> { put("type", "INTEGER"); s.description?.let { put("description", it) } }
        is SBool -> { put("type", "BOOLEAN"); s.description?.let { put("description", it) } }
        is SArr -> {
            put("type", "ARRAY")
            put("items", s.items.toGemini())
            s.description?.let { put("description", it) }
        }
    }
    if (nullable) put("nullable", true)
}

fun Schema.toJsonSchema(nullable: Boolean = false): JsonObject {
    val base = buildJsonObject {
        when (val s = this@toJsonSchema) {
            is SObj -> {
                put("type", "object")
                putJsonObject("properties") { s.props.forEach { put(it.name, it.schema.toJsonSchema(it.nullable)) } }
                putJsonArray("required") { s.props.forEach { add(it.name) } }
                put("additionalProperties", false)
                s.description?.let { put("description", it) }
            }
            is SStr -> {
                put("type", "string")
                s.enum?.let { e -> putJsonArray("enum") { e.forEach { add(it) } } }
                s.description?.let { put("description", it) }
            }
            is SInt -> { put("type", "integer"); s.description?.let { put("description", it) } }
            is SBool -> { put("type", "boolean"); s.description?.let { put("description", it) } }
            is SArr -> {
                put("type", "array")
                put("items", s.items.toJsonSchema())
                s.description?.let { put("description", it) }
            }
        }
    }
    if (!nullable) return base
    return buildJsonObject {
        putJsonArray("anyOf") {
            add(base)
            add(buildJsonObject { put("type", "null") })
        }
    }
}

/* ---------- lenient readers for model output ---------- */

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonElement?.bool(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull ?: when (p.contentOrNull?.lowercase()) { "true" -> true; "false" -> false; else -> null }
}
fun JsonElement?.int(): Int? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.intOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
}
