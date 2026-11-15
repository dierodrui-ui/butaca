package app.butaca.tv.data

import android.content.Context
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
import kotlinx.coroutines.flow.Flow

/** Un perfil de la casa: cada uno con sus listas, su historial y, si es infantil, sus límites. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,
    val kid: Boolean,
    /** Edad máxima del contenido en perfiles infantiles: 3 (solo todos los públicos), 7 o 12. */
    val kidsLimit: Int,
    /** Temporizador en minutos (0 = sin límite), lo gastado y si ya se agotó. */
    val timerMin: Int,
    val timerUsedMs: Long,
    val timerExpired: Boolean,
    /** Cada cuántos minutos sale un consejo animado en el reproductor (0 = nunca). */
    val adsEvery: Int,
    /** Consejos pequeños en la esquina (mosca) mientras ve algo. */
    val tips: Boolean,
    val position: Int,
    val createdAt: Long,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile ORDER BY position, id")
    fun observe(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile ORDER BY position, id")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(p: ProfileEntity): Long

    @Update
    suspend fun update(p: ProfileEntity)

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE profile SET timerUsedMs = :used, timerExpired = :expired WHERE id = :id")
    suspend fun setTimer(id: Long, used: Long, expired: Boolean)
}

// ---------------- Datos de cada perfil (una base de datos pequeña por perfil) ----------------

/** Tiempo de uso por día: con la app abierta y viendo algo. */
@Entity(tableName = "usage_day")
data class UsageDayEntity(
    @PrimaryKey val day: String,     // 2026-11-15
    val appMs: Long,
    val watchMs: Long,
)

/** Historial: qué se ha visto cada día y cuánto rato. */
@Entity(tableName = "watch_log", primaryKeys = ["day", "stableKey"])
data class WatchLogEntity(
    val day: String,
    val stableKey: String,
    val type: String,
    val title: String,
    val icon: String?,
    val subtitle: String?,           // «T1 · E3» en series
    val watchedMs: Long,
    val lastAt: Long,
)

/** Tu nota (1 a 10) de cada título: es lo que más pesa en el «% para ti». */
@Entity(tableName = "taste")
data class TasteEntity(
    @PrimaryKey val stableKey: String,
    val type: String,
    val title: String,
    val icon: String?,
    val score: Int,          // 1..10
    val genre: String?,      // géneros de TMDB en el momento de valorar
    val tmdbId: Int?,
    val at: Long,
)

@Dao
interface TasteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(t: TasteEntity)

    @Query("DELETE FROM taste WHERE stableKey = :key")
    suspend fun clear(key: String)

    @Query("SELECT * FROM taste ORDER BY at DESC")
    suspend fun all(): List<TasteEntity>

    @Query("SELECT * FROM taste ORDER BY at DESC")
    fun observeAll(): Flow<List<TasteEntity>>

    @Query("SELECT score FROM taste WHERE stableKey = :key")
    fun observe(key: String): Flow<Int?>

    @Query("SELECT score FROM taste WHERE stableKey = :key")
    suspend fun get(key: String): Int?
}

/** Cada reproducción: qué, cuándo empezó y cuánto rato se vio. */
@Entity(tableName = "play_log", primaryKeys = ["session", "stableKey", "part"])
data class PlayLogEntity(
    val session: Long,       // cuándo se abrió el reproductor
    val stableKey: String,
    val part: String,        // «T1 · E3» en series; vacío en películas
    val type: String,
    val title: String,
    val icon: String?,
    val startedAt: Long,
    val lastAt: Long,
    val watchedMs: Long,
)

@Dao
interface PlayLogDao {
    @Query("SELECT * FROM play_log WHERE session = :session AND stableKey = :key AND part = :part")
    suspend fun get(session: Long, key: String, part: String): PlayLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PlayLogEntity)

    @Query("SELECT * FROM play_log ORDER BY startedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<PlayLogEntity>>

    @Query("SELECT COUNT(*) FROM play_log")
    fun observeCount(): Flow<Int>
}

data class TitleTotal(val stableKey: String, val type: String, val title: String, val icon: String?, val total: Long, val lastAt: Long)

@Dao
interface TrackedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracked(t: TrackedSeriesEntity)

    @Query("SELECT * FROM tracked_series WHERE stableKey = :key")
    suspend fun tracked(key: String): TrackedSeriesEntity?

    @Query("SELECT * FROM tracked_series WHERE newEpisodes > 0 OR newSeason = 1 ORDER BY lastModified DESC")
    fun observeNewEpisodes(): Flow<List<TrackedSeriesEntity>>

    @Query("UPDATE tracked_series SET newEpisodes = 0, newSeason = 0, knownEpisodes = :episodes, knownSeasons = :seasons WHERE stableKey = :key")
    suspend fun markSeen(key: String, episodes: Int, seasons: Int)
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM usage_day WHERE day = :day")
    suspend fun usage(day: String): UsageDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(u: UsageDayEntity)

    @Query("SELECT * FROM usage_day ORDER BY day DESC LIMIT :days")
    fun observeDays(days: Int): Flow<List<UsageDayEntity>>

    @Query("SELECT COALESCE(SUM(appMs), 0) FROM usage_day")
    fun observeTotalApp(): Flow<Long>

    @Query("SELECT COALESCE(SUM(watchMs), 0) FROM usage_day")
    fun observeTotalWatch(): Flow<Long>

    @Query("SELECT MIN(day) FROM usage_day")
    fun observeFirstDay(): Flow<String?>

    @Query("SELECT * FROM watch_log WHERE day = :day AND stableKey = :key")
    suspend fun log(day: String, key: String): WatchLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLog(l: WatchLogEntity)

    @Query("SELECT * FROM watch_log ORDER BY lastAt DESC LIMIT 200")
    fun observeHistory(): Flow<List<WatchLogEntity>>

    @Query(
        "SELECT stableKey, type, title, icon, SUM(watchedMs) AS total, MAX(lastAt) AS lastAt FROM watch_log " +
            "GROUP BY stableKey ORDER BY total DESC LIMIT :limit"
    )
    fun observeTop(limit: Int): Flow<List<TitleTotal>>
}

@Database(
    entities = [
        FolderEntity::class, FolderItemEntity::class, ProgressEntity::class, EpisodeProgressEntity::class,
        TrackedSeriesEntity::class, UsageDayEntity::class, WatchLogEntity::class, TasteEntity::class, PlayLogEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class UserDb : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun progress(): ProgressDao
    abstract fun tracked(): TrackedDao
    abstract fun stats(): StatsDao
    abstract fun taste(): TasteDao
    abstract fun plays(): PlayLogDao

    companion object {
        fun fileName(profileId: Long) = "user_$profileId.db"

        fun open(context: Context, profileId: Long): UserDb =
            Room.databaseBuilder(context, UserDb::class.java, fileName(profileId))
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `taste` (`stableKey` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                                "`icon` TEXT, `score` INTEGER NOT NULL, `genre` TEXT, `tmdbId` INTEGER, `at` INTEGER NOT NULL, PRIMARY KEY(`stableKey`))"
                        )
                    }
                }, object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `play_log` (`session` INTEGER NOT NULL, `stableKey` TEXT NOT NULL, `part` TEXT NOT NULL, " +
                                "`type` TEXT NOT NULL, `title` TEXT NOT NULL, `icon` TEXT, `startedAt` INTEGER NOT NULL, `lastAt` INTEGER NOT NULL, " +
                                "`watchedMs` INTEGER NOT NULL, PRIMARY KEY(`session`, `stableKey`, `part`))"
                        )
                    }
                })
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /**
         * Paso a la v3: lo que ya tenías (carpetas, seguir viendo, vistas, series seguidas)
         * pasa al perfil principal. Se hace una sola vez.
         */
        fun moveLegacyData(context: Context, main: ButacaDb) {
            val u = open(context, 1L)
            u.openHelper.writableDatabase   // crea las tablas
            u.close()
            val m = main.openHelper.writableDatabase
            val path = context.getDatabasePath(fileName(1L)).absolutePath.replace("'", "''")
            m.execSQL("ATTACH DATABASE '$path' AS u")
            try {
                for (t in listOf("folder", "folder_item", "progress", "episode_progress", "tracked_series")) {
                    try { m.execSQL("INSERT OR IGNORE INTO u.$t SELECT * FROM main.$t") } catch (_: Exception) { }
                }
            } finally {
                m.execSQL("DETACH DATABASE u")
            }
        }
    }
}
