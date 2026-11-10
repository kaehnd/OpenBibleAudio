package com.openbibleaudio.openbibleaudio.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.openbibleaudio.data.db.ALL_MIGRATIONS
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

/**
 * Builds a v1 database from the exported `1.json`, then opens it with Room at the current version.
 * Room runs the migrations and fails the open if the result differs from the expected schema
 * (AD-7). The instrumented `MigrationTest` does the same with MigrationTestHelper.
 */
@RunWith(AndroidJUnit4::class)
class MigrationJvmTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun migratesFromV1KeepingData() {
        val v1 = Json.parseToJsonElement(File(SCHEMAS, "1.json").readText()).jsonObject["database"]!!.jsonObject
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (e in v1["entities"]!!.jsonArray) {
                val entity = e.jsonObject
                val table = entity["tableName"]!!.jsonPrimitive.content
                db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach {
                    db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            v1["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL(
                "INSERT INTO bible_version (versionId, abbreviation, name, language, versification, narrator, attribution, " +
                    "state, totalBytes, importedAt, sourceTreeUri, moveSource, failureReason) " +
                    "VALUES ('bsb', 'BSB', 'Berean Standard Bible', 'en', 'org', NULL, NULL, 'READY', 1, 1, NULL, 0, NULL)"
            )
            db.version = 1
        }

        val room = Room.databaseBuilder(context, OpenBibleAudioDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                assertEquals("bsb", room.libraryDao().version("bsb")?.versionId)
                assertEquals(0, room.planDao().activeEnrollments().size)
            }
            assertEquals(OpenBibleAudioDatabase.VERSION, room.openHelper.readableDatabase.version)
        } finally {
            room.close()
        }
    }

    private companion object {
        // Unit tests run with the module directory as the working directory.
        val SCHEMAS = File("schemas/${OpenBibleAudioDatabase::class.qualifiedName}")
    }
}
