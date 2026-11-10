package com.openbibleaudio.openbibleaudio.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 only adds the plan and podcast tables (spec 0003 §5). The SQL is copied from the
 * exported `2.json`; `MigrationTest` checks it against that schema (AD-7).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        V2_STATEMENTS.forEach(db::execSQL)
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)

private val V2_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS `plan` (`id` TEXT NOT NULL, `version` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
        "`definitionJson` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, PRIMARY KEY(`id`, `version`))",
    "CREATE TABLE IF NOT EXISTS `enrollment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` TEXT NOT NULL, " +
        "`planVersion` INTEGER NOT NULL, `versionId` TEXT, `startDate` TEXT NOT NULL, `podcastCycle` INTEGER, " +
        "`downloadMode` TEXT NOT NULL, `rollingDays` INTEGER NOT NULL, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
        "FOREIGN KEY(`planId`, `planVersion`) REFERENCES `plan`(`id`, `version`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_enrollment_planId_planVersion` ON `enrollment` (`planId`, `planVersion`)",
    "CREATE TABLE IF NOT EXISTS `enrollment_day` (`enrollmentId` INTEGER NOT NULL, `day` INTEGER NOT NULL, " +
        "`status` TEXT NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`enrollmentId`, `day`), " +
        "FOREIGN KEY(`enrollmentId`) REFERENCES `enrollment`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `enrollment_item_completion` (`enrollmentId` INTEGER NOT NULL, `day` INTEGER NOT NULL, " +
        "`itemKey` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, PRIMARY KEY(`enrollmentId`, `day`, `itemKey`), " +
        "FOREIGN KEY(`enrollmentId`) REFERENCES `enrollment`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `podcast_feed` (`feedId` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT, `etag` TEXT, " +
        "`lastModified` TEXT, `lastFetchedAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`feedId`))",
    "CREATE TABLE IF NOT EXISTS `podcast_episode` (`feedId` TEXT NOT NULL, `guidHash` TEXT NOT NULL, `guid` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `pubDate` INTEGER NOT NULL, `enclosureUrl` TEXT NOT NULL, `enclosureLength` INTEGER, " +
        "`durationMs` INTEGER, `episodeType` TEXT, `cycle` INTEGER, `matchedDay` INTEGER, `matchSource` TEXT, " +
        "`inFeed` INTEGER NOT NULL, `downloadState` TEXT NOT NULL, `localPath` TEXT, `downloadedBytes` INTEGER, " +
        "`downloadError` TEXT, `positionMs` INTEGER NOT NULL, `lastPlayedAt` INTEGER, `completedAt` INTEGER, " +
        "PRIMARY KEY(`feedId`, `guidHash`), " +
        "FOREIGN KEY(`feedId`) REFERENCES `podcast_feed`(`feedId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_podcast_episode_feedId_cycle_matchedDay` ON `podcast_episode` (`feedId`, `cycle`, `matchedDay`)",
    "CREATE TABLE IF NOT EXISTS `manual_pin` (`enrollmentId` INTEGER NOT NULL, `day` INTEGER NOT NULL, " +
        "`feedId` TEXT NOT NULL, `guidHash` TEXT NOT NULL, PRIMARY KEY(`enrollmentId`, `day`), " +
        "FOREIGN KEY(`enrollmentId`) REFERENCES `enrollment`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
)
