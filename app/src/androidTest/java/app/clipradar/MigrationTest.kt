package app.clipradar

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clipradar.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Обновление с версии 1.0.x: история моментов и стримеры должны сохраниться. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test
    fun v1DatabaseMigratesWithoutLosingHistory() {
        val ctx = TestFiles.ctx
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `moments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`sourceUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `channel` TEXT, `isLive` INTEGER NOT NULL, `sessionId` INTEGER, " +
                    "`positionSec` REAL NOT NULL, `wallTime` INTEGER, `startSec` REAL NOT NULL, `endSec` REAL NOT NULL, `sourceFile` TEXT, " +
                    "`score` INTEGER NOT NULL, `reason` TEXT NOT NULL, `status` TEXT NOT NULL, `clipUri` TEXT, `clipSize` INTEGER NOT NULL, `error` TEXT)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `streamers` (`login` TEXT NOT NULL, `displayName` TEXT NOT NULL, `watch` INTEGER NOT NULL, " +
                    "`isLive` INTEGER NOT NULL, `currentStreamId` TEXT, `ignoredStreamId` TEXT, `lastCheckedAt` INTEGER NOT NULL, PRIMARY KEY(`login`))",
            )
            db.execSQL(
                "INSERT INTO moments (createdAt, sourceUrl, title, channel, isLive, positionSec, startSec, endSec, score, reason, status, clipSize) " +
                    "VALUES (1, 'https://youtu.be/x', 'Old clip', 'someone', 0, 100.0, 80.0, 125.0, 77, '#1', 'READY', 12345)",
            )
            db.execSQL("INSERT INTO streamers (login, displayName, watch, isLive, lastCheckedAt) VALUES ('xqc', 'xQc', 1, 0, 0)")
            db.version = 1
        }
        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            val moments = runBlocking { room.moments().all() }
            assertEquals(1, moments.size)
            assertEquals("Old clip", moments[0].title)
            assertEquals(77, moments[0].score)
            assertEquals(0, moments[0].feedback)
            assertEquals(-1.0, moments[0].peakInSource, 0.0)
            assertEquals(1, runBlocking { room.streamers().all() }.size)
            TestFiles.report("migration.txt", "миграция 1→2: история сохранена (${moments.size} момент, 1 стример)")
        } finally {
            room.close()
        }
    }
}
