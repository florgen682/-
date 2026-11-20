package app.clipradar.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Статусы момента. */
object MomentStatus {
    const val FOUND = "FOUND"       // найден, ждёт решения (нарезка выключена)
    const val WEAK = "WEAK"         // слабый — ниже порога, не режем
    const val QUEUED = "QUEUED"     // в очереди на нарезку
    const val CUTTING = "CUTTING"   // режется прямо сейчас
    const val READY = "READY"       // клип готов
    const val FAILED = "FAILED"     // ошибка
    const val REJECTED = "REJECTED" // отброшен: много тишины / стоп-кадра
}

@Entity(tableName = "moments")
data class MomentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Ссылка на видео или канал. */
    val sourceUrl: String,
    val title: String,
    /** Ник Twitch (для эфиров). */
    val channel: String? = null,
    val isLive: Boolean,
    /** Номер сессии наблюдения за эфиром — для итогового отчёта. */
    val sessionId: Long? = null,
    /** Для видео — секунда пика; для эфира — сколько шёл стрим (или -1). */
    val positionSec: Double,
    /** Для эфира — время момента по часам. */
    val wallTime: Long? = null,
    /** Какой кусок видео резать (для готовых видео). */
    val startSec: Double = 0.0,
    val endSec: Double = 0.0,
    /** Для эфира — путь к сохранённому «сырому» куску из буфера. */
    val sourceFile: String? = null,
    val score: Int,
    val reason: String,
    val status: String,
    val clipUri: String? = null,
    val clipSize: Long = 0,
    val error: String? = null,
    /** Выбранные границы клипа (секунды внутри файла-исходника). */
    @ColumnInfo(defaultValue = "0") val clipStartSec: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val clipEndSec: Double = 0.0,
    /** С какой секунды оригинального видео начинается файл-исходник (для готовых видео). */
    @ColumnInfo(defaultValue = "0") val sourceStartSec: Double = 0.0,
    /** Где внутри исходника пик (секунды), −1 — неизвестно. */
    @ColumnInfo(defaultValue = "-1") val peakInSource: Double = -1.0,
    /** Границы, выставленные вручную в редакторе. */
    val manualStartSec: Double? = null,
    val manualEndSec: Double? = null,
    /** Оценка пользователя: 1 — хороший момент, −1 — плохой, 0 — нет оценки. */
    @ColumnInfo(defaultValue = "0") val feedback: Int = 0,
    /** Подробности в JSON: расшифровка, причины границ, подписи для публикации, чат. */
    val details: String? = null,
)

/** Настройки для конкретного стримера/канала (подстройка по кнопкам «Хороший/плохой»). */
@Entity(tableName = "channel_prefs")
data class ChannelPrefsEntity(
    @PrimaryKey val channelKey: String,
    @ColumnInfo(defaultValue = "0") val thresholdAdjust: Int = 0,
    @ColumnInfo(defaultValue = "0") val good: Int = 0,
    @ColumnInfo(defaultValue = "0") val bad: Int = 0,
)

@Entity(tableName = "streamers")
data class StreamerEntity(
    @PrimaryKey val login: String,
    val displayName: String,
    val watch: Boolean = true,
    val isLive: Boolean = false,
    /** Номер текущей трансляции (чтобы не запускать наблюдение дважды). */
    val currentStreamId: String? = null,
    /** Трансляция, которую пользователь сам остановил кнопкой «Стоп». */
    val ignoredStreamId: String? = null,
    val lastCheckedAt: Long = 0,
)

@Dao
interface MomentDao {
    @Query("SELECT * FROM moments ORDER BY score DESC, createdAt DESC")
    fun observeAll(): Flow<List<MomentEntity>>

    @Query("SELECT * FROM moments WHERE id = :id")
    suspend fun get(id: Long): MomentEntity?

    @Query("SELECT * FROM moments")
    suspend fun all(): List<MomentEntity>

    @Query("SELECT * FROM moments WHERE sessionId = :sessionId")
    suspend fun bySession(sessionId: Long): List<MomentEntity>

    @Query("SELECT * FROM moments WHERE status IN ('QUEUED','CUTTING')")
    suspend fun unfinished(): List<MomentEntity>

    @Query("SELECT * FROM moments WHERE id = :id")
    fun observe(id: Long): Flow<MomentEntity?>

    @Query("SELECT * FROM moments WHERE (sourceUrl = :sourceUrl OR (channel IS NOT NULL AND channel = :channel)) AND id != :exceptId")
    suspend fun sameSource(sourceUrl: String, channel: String?, exceptId: Long): List<MomentEntity>

    @Insert
    suspend fun insert(m: MomentEntity): Long

    @Update
    suspend fun update(m: MomentEntity)

    @Query("UPDATE moments SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query("DELETE FROM moments WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StreamerDao {
    @Query("SELECT * FROM streamers ORDER BY login")
    fun observeAll(): Flow<List<StreamerEntity>>

    @Query("SELECT * FROM streamers")
    suspend fun all(): List<StreamerEntity>

    @Query("SELECT * FROM streamers WHERE watch = 1")
    suspend fun watched(): List<StreamerEntity>

    @Query("SELECT * FROM streamers WHERE login = :login")
    suspend fun get(login: String): StreamerEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: StreamerEntity)

    @Update
    suspend fun update(s: StreamerEntity)

    @Query("DELETE FROM streamers WHERE login = :login")
    suspend fun delete(login: String)
}

@Dao
interface ChannelPrefsDao {
    @Query("SELECT * FROM channel_prefs WHERE channelKey = :channelKey")
    suspend fun get(channelKey: String): ChannelPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(p: ChannelPrefsEntity)
}

@Database(
    entities = [MomentEntity::class, StreamerEntity::class, ChannelPrefsEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun moments(): MomentDao
    abstract fun streamers(): StreamerDao
    abstract fun channelPrefs(): ChannelPrefsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Версия 1 → 2: новые поля для умных границ, ручной правки и подписей. История сохраняется. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE moments ADD COLUMN clipStartSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE moments ADD COLUMN clipEndSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE moments ADD COLUMN sourceStartSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE moments ADD COLUMN peakInSource REAL NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE moments ADD COLUMN manualStartSec REAL")
                db.execSQL("ALTER TABLE moments ADD COLUMN manualEndSec REAL")
                db.execSQL("ALTER TABLE moments ADD COLUMN feedback INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE moments ADD COLUMN details TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `channel_prefs` (`channelKey` TEXT NOT NULL, " +
                        "`thresholdAdjust` INTEGER NOT NULL DEFAULT 0, `good` INTEGER NOT NULL DEFAULT 0, " +
                        "`bad` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`channelKey`))",
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "clipradar.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
