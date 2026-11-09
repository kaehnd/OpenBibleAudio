package com.openbibleaudio.core.model.pack

import com.openbibleaudio.core.model.Book
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Thrown when `manifest.json` can't be decoded. [message] is suitable for showing to the user. */
class ManifestFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads and writes `manifest.json` (spec 0001 §4.2, §4.8). */
object PackManifestCodec {
    const val SUPPORTED_FORMAT_VERSION = 1

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true // §4.2: additive fields must not break v1 readers
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
        encodeDefaults = false
    }

    /** Decodes [text]. Throws [ManifestFormatException] with a readable message on any problem. */
    fun decode(text: String): PackManifest {
        val root: JsonElement = try {
            json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: SerializationException) {
            throw ManifestFormatException("manifest.json is not valid JSON: ${e.message}", e)
        }
        if (root !is JsonObject) throw ManifestFormatException("manifest.json must contain a JSON object")

        // Check the format version before the full decode, so a pack from a newer tool gets a
        // clear message rather than a confusing field error (§4.8).
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw ManifestFormatException("manifest.json has no integer 'formatVersion'")
        if (version > SUPPORTED_FORMAT_VERSION) {
            throw ManifestFormatException(
                "This pack needs a newer version of OpenBibleAudio " +
                    "(format $version > $SUPPORTED_FORMAT_VERSION)"
            )
        }
        if (version < 1) throw ManifestFormatException("Unsupported formatVersion $version")

        checkBookCodes(root)

        return try {
            json.decodeFromJsonElement(PackManifest.serializer(), root)
        } catch (e: SerializationException) {
            throw ManifestFormatException("manifest.json is invalid: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ManifestFormatException("manifest.json is invalid: ${e.message}", e)
        }
    }

    /** Pretty-printed, default-valued fields omitted, trailing newline. Deterministic. */
    fun encode(manifest: PackManifest): String =
        json.encodeToString(PackManifest.serializer(), manifest) + "\n"

    /** Names the offending entry for an unknown book code, e.g. `chapters[12].book`. */
    private fun checkBookCodes(root: JsonObject) {
        val chapters = root["chapters"] as? JsonArray ?: return
        chapters.forEachIndexed { i, element ->
            val book = ((element as? JsonObject)?.get("book") as? JsonPrimitive) ?: return@forEachIndexed
            if (book.isString && Book.fromUsfm(book.content) == null) {
                throw ManifestFormatException("chapters[$i].book: unknown book code '${book.content}'")
            }
        }
    }
}
