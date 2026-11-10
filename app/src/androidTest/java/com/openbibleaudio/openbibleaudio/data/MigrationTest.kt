package com.openbibleaudio.openbibleaudio.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openbibleaudio.openbibleaudio.data.db.MIGRATION_1_2
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** Every migration against the exported schemas in `app/schemas/` (AD-7, spec 0003 §5). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OpenBibleAudioDatabase::class.java)

    @Test
    fun migrate1To2() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO bible_version (versionId, abbreviation, name, language, versification, narrator, attribution, " +
                    "state, totalBytes, importedAt, sourceTreeUri, moveSource, failureReason) " +
                    "VALUES ('bsb', 'BSB', 'Berean Standard Bible', 'en', 'org', NULL, NULL, 'READY', 1, 1, NULL, 0, NULL)"
            )
        }
        helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT versionId FROM bible_version").use { c ->
                c.moveToFirst()
                assertEquals("bsb", c.getString(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
