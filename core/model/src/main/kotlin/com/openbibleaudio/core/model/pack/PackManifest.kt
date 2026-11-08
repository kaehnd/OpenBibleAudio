package com.openbibleaudio.core.model.pack

import com.openbibleaudio.core.model.Book
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

/**
 * `manifest.json` of an Audio Bible Pack, format v1 (AD-4). Spec 0001 §4 is normative; this is
 * its serialization model. Read and write it through [PackManifestCodec].
 */
@Serializable
data class PackManifest(
    val formatVersion: Int,
    val bible: PackBible,
    val generator: PackGenerator? = null,
    val files: List<PackFile>,
    val chapters: List<PackChapter>,
)

/** Spec 0001 §4.3. */
@Serializable
data class PackBible(
    val versionId: String,
    val abbreviation: String,
    val name: String,
    val language: String,
    val versification: String,
    val narrator: String? = null,
    val attribution: String? = null,
)

/** Spec 0001 §4.4. [path] is relative to the pack root, '/'-separated. */
@Serializable
data class PackFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val durationMs: Long? = null,
)

/** Spec 0001 §4.5. [endMs] null = to end of file. */
@Serializable
data class PackChapter(
    @Serializable(with = BookUsfmSerializer::class) val book: Book,
    val chapter: Int,
    val file: String,
    val startMs: Long = 0,
    val endMs: Long? = null,
    /** Reserved for Phase 4 verse timing; accepted and ignored in format v1. */
    val verses: JsonElement? = null,
)

/** Spec 0001 §4.6. Informational only; readers must not change behaviour based on it. */
@Serializable
data class PackGenerator(
    val tool: String,
    val toolVersion: String,
    val profile: String? = null,
)

/** Serializes a [Book] as its USFM code (AD-11). */
object BookUsfmSerializer : KSerializer<Book> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.openbibleaudio.core.model.Book", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Book) = encoder.encodeString(value.usfm)

    override fun deserialize(decoder: Decoder): Book {
        val code = decoder.decodeString()
        return Book.fromUsfm(code) ?: throw SerializationException("Unknown book code '$code'")
    }
}
