package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.fixtures.BsbNames
import com.openbibleaudio.core.importer.fixtures.InMemoryFileTree
import com.openbibleaudio.core.importer.fixtures.Mp3Fixtures
import com.openbibleaudio.core.importer.probe.Mp3FrameProbe
import com.openbibleaudio.core.importer.profile.BsbGilbertProfile
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.pack.PackManifest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PackVerifierTest {
    private val gen1 = BsbNames.fileName(Book.GENESIS, 1)
    private val gen2 = BsbNames.fileName(Book.GENESIS, 2)
    private val files = mapOf(gen1 to Mp3Fixtures.cbr(100), gen2 to Mp3Fixtures.cbr(200))
    private val probes = listOf(Mp3FrameProbe)

    private val manifest: PackManifest = runBlocking {
        ImporterPipeline.run(InMemoryFileTree(files), BsbGilbertProfile.profile, ImportOptions(probes = probes)).manifest!!
    }

    private fun verify(tree: FileTree, contents: Boolean = true) =
        runBlocking { PackVerifier(probes).verify(tree, manifest, contents) }.issues.map { it.code }

    @Test
    fun matchingTreeHasNoIssues() {
        assertEquals(emptyList(), verify(InMemoryFileTree(files)))
    }

    @Test
    fun missingFile() {
        assertEquals(listOf(IssueCode.MISSING_FILE), verify(InMemoryFileTree(files - gen2)))
    }

    @Test
    fun sizeMismatch() {
        assertEquals(listOf(IssueCode.SIZE_MISMATCH), verify(InMemoryFileTree(files + (gen2 to Mp3Fixtures.cbr(201)))))
    }

    @Test
    fun hashMismatchOnlyWhenCheckingContents() {
        val corrupted = files.getValue(gen1).copyOf().also { it[500] = 0x42 }
        val tree = InMemoryFileTree(files + (gen1 to corrupted))
        assertEquals(listOf(IssueCode.HASH_MISMATCH), verify(tree))
        assertEquals(emptyList(), verify(tree, contents = false))
    }

    @Test
    fun durationMismatchIsAWarning() {
        val wrong = manifest.copy(files = manifest.files.map { if (it.path == gen1) it.copy(sha256 = null, durationMs = it.durationMs!! + 5000) else it })
        val issues = runBlocking { PackVerifier(probes).verify(InMemoryFileTree(files), wrong, true) }
        assertEquals(listOf(IssueCode.DURATION_MISMATCH), issues.issues.map { it.code })
        assertEquals(false, issues.hasErrors)
    }
}
