package com.openbibleaudio.core.model.plan

import com.openbibleaudio.core.model.Book
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Thrown when a plan file can't be decoded. [message] is suitable for showing to the user. */
class PlanFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads and writes plan JSON (spec 0003 §3.1). Same conventions as `PackManifestCodec`. */
object PlanCodec {
    const val SUPPORTED_FORMAT_VERSION = 1

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
        encodeDefaults = false
    }

    fun decode(text: String): PlanDefinition {
        val root: JsonElement = try {
            json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: SerializationException) {
            throw PlanFormatException("The plan file is not valid JSON: ${e.message}", e)
        }
        if (root !is JsonObject) throw PlanFormatException("The plan file must contain a JSON object")

        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw PlanFormatException("The plan file has no integer 'formatVersion'")
        if (version > SUPPORTED_FORMAT_VERSION) {
            throw PlanFormatException(
                "This plan needs a newer version of OpenBibleAudio (format $version > $SUPPORTED_FORMAT_VERSION)"
            )
        }
        if (version < 1) throw PlanFormatException("Unsupported formatVersion $version")

        checkReadings(root)

        return try {
            json.decodeFromJsonElement(PlanDefinition.serializer(), root)
        } catch (e: SerializationException) {
            throw PlanFormatException("The plan file is invalid: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw PlanFormatException("The plan file is invalid: ${e.message}", e)
        }
    }

    /** Pretty-printed, default-valued fields omitted, trailing newline. Deterministic. */
    fun encode(plan: PlanDefinition): String = json.encodeToString(PlanDefinition.serializer(), plan) + "\n"

    /** Names the offending reading for unknown books and (future) verse-range readings. */
    private fun checkReadings(root: JsonObject) {
        val days = root["days"] as? JsonArray ?: return
        days.forEachIndexed { d, day ->
            val readings = (day as? JsonObject)?.get("readings") as? JsonArray ?: return@forEachIndexed
            readings.forEachIndexed { r, reading ->
                val obj = reading as? JsonObject ?: return@forEachIndexed
                val at = "days[$d].readings[$r]"
                val book = obj["book"] as? JsonPrimitive
                if (book != null && book.isString && Book.fromUsfm(book.content) == null) {
                    throw PlanFormatException("$at.book: unknown book code '${book.content}'")
                }
                for (key in listOf("from", "to")) {
                    val v = obj[key] as? JsonPrimitive ?: continue
                    if (v.isString) {
                        throw PlanFormatException(
                            "$at.$key: verse ranges ('${v.content}') need a newer version of OpenBibleAudio"
                        )
                    }
                }
            }
        }
    }
}
