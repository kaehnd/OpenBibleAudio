package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.core.model.PodcastIds
import java.io.File

/**
 * Where episode files live: `filesDir/podcasts/{feedId}/{guidHash}.mp3` (spec 0003 §8.3). Paths
 * come from the hashed IDs only, never from feed content. [allocatableBytes] reports free space
 * the way import does (`StorageManager.getAllocatableBytes`).
 */
class PodcastStorage(filesDir: File, val allocatableBytes: () -> Long) {
    val root: File = File(filesDir, "podcasts").canonicalFile

    fun file(feedId: String, guidHash: String): File {
        require(PodcastIds.FEED_ID.matches(feedId)) { "bad feedId '$feedId'" }
        require(PodcastIds.GUID_HASH.matches(guidHash)) { "bad guidHash '$guidHash'" }
        val f = File(File(root, feedId), "$guidHash.mp3").canonicalFile
        check(f.path.startsWith(root.path + File.separator)) { "episode path escapes ${root.path}" }
        return f
    }

    fun partFile(file: File) = File(file.path + ".part")

    /** Holds the ETag or Last-Modified that the `.part` file was downloaded against (for If-Range). */
    fun validatorFile(file: File) = File(file.path + ".part.validator")

    /** Deletes an episode's file and any partial download. */
    fun delete(feedId: String, guidHash: String) {
        val f = file(feedId, guidHash)
        f.delete()
        partFile(f).delete()
        validatorFile(f).delete()
    }

    /** Space used by downloaded and partial episode files. */
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
