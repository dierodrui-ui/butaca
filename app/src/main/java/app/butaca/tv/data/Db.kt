package app.butaca.tv.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.RawQuery
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

object ContentType {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"
}

@Entity(tableName = "category", indices = [Index("type")])
data class CategoryEntity(
    @PrimaryKey val key: String,      // tipo:id
    val type: String,
    val catId: String,
    val name: String,
    val position: Int,
    val autoAdult: Boolean,
    val userAdult: Boolean? = null,   // decisión manual del usuario
    val hidden: Boolean = false,
    val firstSeen: Long = 0L,
    val reviewed: Boolean = false,
) {
    val isAdult: Boolean get() = userAdult ?: autoAdult
}

@Entity(
    tableName = "item",
    indices = [Index("type"), Index("categoryKey"), Index("stableKey"), Index("baseKey"), Index("added")],
)
data class ItemEntity(
    @PrimaryKey val key: String,      // tipo:streamId
    val type: String,
    val streamId: String,
    val name: String,
    val normName: String,
    val categoryKey: String,
    val icon: String?,
    val backdrop: String?,
    val rating: Double?,
    val added: Long,                  // segundos (fecha en que el servidor lo añadió / actualizó)
    val year: Int?,
    val ext: String?,
    val genre: String?,
    val plot: String?,
    val adultFlag: Boolean,
    val isAdult: Boolean,
    val stableKey: String,            // misma película aunque esté repetida
    val baseKey: String,              // mismo canal en distintas calidades
    val quality: Int,
    val position: Int,
    val syncStamp: Long,
    /** 0 = sin completar, 1 = datos del servidor, 2 = datos de TMDB */
    @ColumnInfo(defaultValue = "0") val enriched: Int = 0,
    val tmdbId: Int? = null,
    /** Edad recomendada (0, 7, 12, 16, 18) según TMDB; null si aún no se sabe. */
    val cert: Int? = null,
    /** Popularidad en TMDB (para ordenar como las plataformas). */
    val pop: Double? = null,
    /** País de origen (ES, US…) e idioma original según TMDB. */
    val country: String? = null,
    val lang: String? = null,
)

data class Enrichment(
    val key: String, val genre: String?, val plot: String?, val backdrop: String?, val enriched: Int,
    val tmdbId: Int?, val cert: Int?, val pop: Double?, val country: String?, val lang: String?,
)

/** Estreno que esperas: te avisamos cuando llegue a tu servidor. */
@Entity(tableName = "alert")
data class AlertEntity(
    @PrimaryKey val key: String,     // m123 / t123
    val tmdbId: Int,
    val isTv: Boolean,
    val title: String,
    val poster: String?,
    val year: Int?,
    val createdAt: Long,
    val available: Boolean,
)

@Entity(tableName = "folder")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,
    val position: Int,
    val system: String? = null,       // "later" | "fav" | null
)

@Entity(tableName = "folder_item", primaryKeys = ["folderId", "stableKey"], indices = [Index("stableKey")])
data class FolderItemEntity(
    val folderId: Long,
    val stableKey: String,
    val type: String,
    val title: String,
    val icon: String?,
    val year: Int?,
    val rating: Double?,
    val position: Int,
    val addedAt: Long,
)

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val stableKey: String,  // película o serie
    val type: String,
    val title: String,
    val icon: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val finished: Boolean,
    val dismissed: Boolean = false,
    val episodeId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
)

@Entity(tableName = "episode_progress", indices = [Index("seriesKey")])
data class EpisodeProgressEntity(
    @PrimaryKey val episodeId: String,
    val seriesKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "tracked_series")
data class TrackedSeriesEntity(
    @PrimaryKey val stableKey: String,
    val seriesId: String,
    val knownEpisodes: Int,
    val knownSeasons: Int,
    val newEpisodes: Int,
    val newSeason: Boolean,
    val lastModified: Long,
    val title: String,
    val icon: String?,
)

@Entity(tableName = "audio_pref")
data class AudioPrefEntity(
    @PrimaryKey val seriesKey: String,
    val language: String?,
    val label: String?,
    val groupIndex: Int,
)

data class CategoryCount(val categoryKey: String, val total: Int, val adult: Int)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE type = :type ORDER BY position")
    fun observe(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY type, position")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE type = :type")
    suspend fun all(type: String): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: List<CategoryEntity>)

    @Query("DELETE FROM category WHERE type = :type AND `key` NOT IN (:keep)")
    suspend fun deleteMissing(type: String, keep: List<String>)

    @Query("UPDATE category SET userAdult = :adult, reviewed = 1 WHERE `key` = :key")
    suspend fun setAdult(key: String, adult: Boolean?)

    @Query("UPDATE category SET hidden = :hidden WHERE `key` = :key")
    suspend fun setHidden(key: String, hidden: Boolean)

    @Query("SELECT categoryKey, COUNT(*) AS total, SUM(adultFlag) AS adult FROM item WHERE type = :type GROUP BY categoryKey")
    suspend fun counts(type: String): List<CategoryCount>
}

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: List<ItemEntity>)

    @Query("DELETE FROM item WHERE type = :type AND syncStamp != :stamp")
    suspend fun deleteStale(type: String, stamp: Long)

    @Query("SELECT `key`, added FROM item WHERE type = :type")
    suspend fun addedMap(type: String): List<KeyAdded>

    @Query("UPDATE item SET isAdult = (adultFlag OR :catAdult) WHERE categoryKey = :categoryKey")
    suspend fun applyCategoryAdult(categoryKey: String, catAdult: Boolean)

    @Query("SELECT * FROM item WHERE categoryKey = :categoryKey ORDER BY position")
    fun observeCategory(categoryKey: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item WHERE type = :type AND (:adult OR isAdult = 0) ORDER BY added DESC LIMIT :limit")
    fun observeRecent(type: String, adult: Boolean, limit: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): ItemEntity?

    @Query("SELECT * FROM item WHERE stableKey = :stableKey ORDER BY quality DESC")
    suspend fun byStableKey(stableKey: String): List<ItemEntity>

    @Query("SELECT * FROM item WHERE stableKey IN (:keys)")
    suspend fun byStableKeys(keys: List<String>): List<ItemEntity>

    @Query("SELECT * FROM item WHERE type = :type AND baseKey = :baseKey ORDER BY quality DESC")
    suspend fun variants(type: String, baseKey: String): List<ItemEntity>

    @Query(
        "SELECT * FROM item WHERE normName LIKE '%' || :q || '%' AND (:adult OR isAdult = 0) " +
            "ORDER BY CASE WHEN normName LIKE :q || '%' THEN 0 ELSE 1 END, added DESC LIMIT :limit"
    )
    suspend fun search(q: String, adult: Boolean, limit: Int): List<ItemEntity>

    @Query(
        "SELECT * FROM item WHERE type IN ('movie','series') AND isAdult = 0 AND added >= :since " +
            "ORDER BY added DESC LIMIT 80"
    )
    fun observeNew(since: Long): Flow<List<ItemEntity>>

    @Query("SELECT COUNT(*) FROM item")
    suspend fun count(): Int

    @RawQuery
    suspend fun raw(q: SupportSQLiteQuery): List<ItemEntity>

    @Query("SELECT `key`, genre, plot, backdrop, enriched, tmdbId, cert, pop, country, lang FROM item WHERE type = :type AND enriched >= 1")
    suspend fun enrichments(type: String): List<Enrichment>

    @Query("UPDATE item SET genre = CASE WHEN enriched >= 2 THEN genre ELSE COALESCE(:genre, genre) END, plot = COALESCE(:plot, plot), backdrop = COALESCE(backdrop, :backdrop), enriched = MAX(enriched, 1) WHERE type = 'movie' AND streamId = :streamId")
    suspend fun enrich(streamId: String, genre: String?, plot: String?, backdrop: String?)

    /** Géneros de TMDB (mandan sobre los del servidor). */
    @Query(
        "UPDATE item SET genre = COALESCE(:genre, genre), plot = COALESCE(plot, :plot), backdrop = COALESCE(backdrop, :backdrop), " +
            "tmdbId = COALESCE(:tmdbId, tmdbId), cert = COALESCE(:cert, cert), pop = COALESCE(:pop, pop), country = COALESCE(:country, country), " +
            "lang = COALESCE(:lang, lang), enriched = 2 WHERE stableKey = :stableKey"
    )
    suspend fun enrichTmdb(
        stableKey: String, genre: String?, plot: String?, backdrop: String?, tmdbId: Int?, cert: Int?,
        pop: Double?, country: String?, lang: String?,
    )

    /** Películas o series ya identificadas en TMDB (para las filas por categorías). */
    @Query("SELECT * FROM item WHERE type = :type AND isAdult = 0 AND tmdbId IS NOT NULL")
    suspend fun identified(type: String): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM item WHERE type = :type AND isAdult = 0 AND tmdbId IS NULL AND enriched >= 2")
    suspend fun unidentifiedCount(type: String): Int

    /** Marca como revisado aunque TMDB no lo conozca (para no preguntar en bucle). */
    @Query("UPDATE item SET enriched = 2 WHERE stableKey = :stableKey AND enriched < 2")
    suspend fun markTried(stableKey: String)

    @Query("SELECT * FROM item WHERE type IN ('movie','series') AND enriched < 2 ORDER BY added DESC LIMIT :limit")
    suspend fun pendingEnrich(limit: Int): List<ItemEntity>

    @Query("SELECT COUNT(*) FROM item WHERE type IN ('movie','series') AND enriched < 2")
    suspend fun pendingEnrichCount(): Int

    @Query("SELECT DISTINCT categoryKey FROM item WHERE type = :type AND cert IS NOT NULL AND cert <= :limit")
    fun observeKidsCategories(type: String, limit: Int): Flow<List<String>>

    @Query("SELECT * FROM item WHERE tmdbId = :tmdbId AND type = :type")
    suspend fun byTmdbId(tmdbId: Int, type: String): List<ItemEntity>

    @Query("SELECT genre FROM item WHERE genre IS NOT NULL AND isAdult = 0 AND type IN ('movie','series')")
    suspend fun allGenres(): List<String>

    @Query("SELECT * FROM item WHERE type IN ('movie','series') AND isAdult = 0 AND genre LIKE '%' || :genre || '%' ORDER BY rating DESC LIMIT :limit")
    suspend fun withGenre(genre: String, limit: Int): List<ItemEntity>

    /** Títulos cuyo género viene de TMDB (con id de TMDB), sin límite. */
    @Query("SELECT * FROM item WHERE type IN ('movie','series') AND isAdult = 0 AND tmdbId IS NOT NULL AND genre LIKE '%' || :genre || '%'")
    suspend fun withTmdbGenre(genre: String): List<ItemEntity>

    @Query("SELECT * FROM item WHERE tmdbId IN (:ids)")
    suspend fun byTmdbIds(ids: List<Int>): List<ItemEntity>

    @Query("SELECT * FROM item WHERE stableKey IN (:keys) AND genre IS NOT NULL")
    suspend fun genresOf(keys: List<String>): List<ItemEntity>

    @Query(
        "SELECT * FROM item WHERE type = :type AND isAdult = 0 AND stableKey != :exclude AND (' ' || normName || ' ') LIKE '% ' || :prefix || '%' " +
            "ORDER BY year, normName LIMIT 20"
    )
    suspend fun saga(type: String, prefix: String, exclude: String): List<ItemEntity>

    @Query("SELECT * FROM item WHERE type = :type AND isAdult = 0 AND stableKey != :exclude AND genre LIKE '%' || :genre || '%' ORDER BY rating DESC LIMIT 40")
    suspend fun byGenre(type: String, genre: String, exclude: String): List<ItemEntity>

    @Query("SELECT * FROM item WHERE categoryKey = :categoryKey AND isAdult = 0 AND stableKey != :exclude ORDER BY rating DESC LIMIT 40")
    suspend fun byCategory(categoryKey: String, exclude: String): List<ItemEntity>
}

data class KeyAdded(val key: String, val added: Long)

data class KeyMark(val stableKey: String, val system: String)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder ORDER BY position, id")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folder WHERE system = :system LIMIT 1")
    suspend fun systemFolder(system: String): FolderEntity?

    @Insert
    suspend fun insertFolder(f: FolderEntity): Long

    @Query("UPDATE folder SET name = :name, color = :color WHERE id = :id")
    suspend fun updateFolder(id: Long, name: String, color: Long)

    @Query("UPDATE folder SET color = :color WHERE system IN ('fav','later')")
    suspend fun setSystemColor(color: Long)

    @Query("DELETE FROM folder WHERE id = :id AND system IS NULL")
    suspend fun deleteFolder(id: Long)

    @Query("DELETE FROM folder_item WHERE folderId = :id")
    suspend fun clearFolder(id: Long)

    @Query("SELECT COALESCE(MAX(position), 0) FROM folder")
    suspend fun maxFolderPosition(): Int

    @Query("SELECT * FROM folder_item WHERE folderId = :folderId ORDER BY position")
    fun observeItems(folderId: Long): Flow<List<FolderItemEntity>>

    @Query("SELECT * FROM folder_item WHERE folderId = :folderId ORDER BY position")
    suspend fun items(folderId: Long): List<FolderItemEntity>

    @Query("SELECT fi.stableKey AS stableKey, f.system AS system FROM folder_item fi JOIN folder f ON f.id = fi.folderId WHERE f.system IN ('fav','later')")
    fun observeMarks(): Flow<List<KeyMark>>

    @Query("SELECT folderId FROM folder_item WHERE stableKey = :stableKey")
    fun observeFoldersOf(stableKey: String): Flow<List<Long>>

    @Query("SELECT folderId, COUNT(*) AS total FROM folder_item GROUP BY folderId")
    fun observeCounts(): Flow<List<FolderCount>>

    @Query("SELECT COALESCE(MIN(position), 0) FROM folder_item WHERE folderId = :folderId")
    suspend fun minPosition(folderId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: FolderItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<FolderItemEntity>)

    @Query("DELETE FROM folder_item WHERE folderId = :folderId AND stableKey = :stableKey")
    suspend fun removeItem(folderId: Long, stableKey: String)

    @Query("SELECT DISTINCT stableKey FROM folder_item WHERE type = 'series'")
    suspend fun seriesInFolders(): List<String>
}

data class FolderCount(val folderId: Long, val total: Int)

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProgressEntity)

    @Query("SELECT * FROM progress WHERE stableKey = :key")
    suspend fun get(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE stableKey = :key")
    fun observe(key: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE finished = 0 AND dismissed = 0 AND positionMs > 0 ORDER BY updatedAt DESC LIMIT 30")
    fun observeContinue(): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE finished = 1 ORDER BY updatedAt DESC")
    fun observeWatched(): Flow<List<ProgressEntity>>

    @Query("SELECT stableKey FROM progress WHERE finished = 1")
    fun observeWatchedKeys(): Flow<List<String>>

    @Query("UPDATE progress SET dismissed = 1 WHERE stableKey = :key")
    suspend fun dismiss(key: String)

    @Query("DELETE FROM progress WHERE stableKey = :key")
    suspend fun delete(key: String)

    @Query("SELECT DISTINCT stableKey FROM progress WHERE type = 'series'")
    suspend fun seriesKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(p: EpisodeProgressEntity)

    @Query("SELECT * FROM episode_progress WHERE seriesKey = :seriesKey")
    fun observeEpisodes(seriesKey: String): Flow<List<EpisodeProgressEntity>>

    @Query("SELECT * FROM episode_progress WHERE episodeId = :id")
    suspend fun episode(id: String): EpisodeProgressEntity?

    @Query("DELETE FROM episode_progress WHERE seriesKey = :seriesKey")
    suspend fun clearEpisodes(seriesKey: String)
}

@Dao
interface MiscDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracked(t: TrackedSeriesEntity)

    @Query("SELECT * FROM tracked_series WHERE stableKey = :key")
    suspend fun tracked(key: String): TrackedSeriesEntity?

    @Query("SELECT * FROM tracked_series WHERE newEpisodes > 0 OR newSeason = 1 ORDER BY lastModified DESC")
    fun observeNewEpisodes(): Flow<List<TrackedSeriesEntity>>

    @Query("UPDATE tracked_series SET newEpisodes = 0, newSeason = 0, knownEpisodes = :episodes, knownSeasons = :seasons WHERE stableKey = :key")
    suspend fun markSeen(key: String, episodes: Int, seasons: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAudio(p: AudioPrefEntity)

    @Query("SELECT * FROM audio_pref WHERE seriesKey = :key")
    suspend fun audio(key: String): AudioPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlert(a: AlertEntity)

    @Query("DELETE FROM alert WHERE `key` = :key")
    suspend fun deleteAlert(key: String)

    @Query("SELECT * FROM alert ORDER BY createdAt DESC")
    fun observeAlerts(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alert")
    suspend fun alerts(): List<AlertEntity>
}

@Database(
    entities = [
        CategoryEntity::class, ItemEntity::class, FolderEntity::class, FolderItemEntity::class,
        ProgressEntity::class, EpisodeProgressEntity::class, TrackedSeriesEntity::class, AudioPrefEntity::class,
        AlertEntity::class, ProfileEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ButacaDb : RoomDatabase() {
    abstract fun categories(): CategoryDao
    abstract fun items(): ItemDao
    abstract fun misc(): MiscDao
    abstract fun profiles(): ProfileDao

    companion object {
        fun create(context: Context): ButacaDb =
            Room.databaseBuilder(context, ButacaDb::class.java, "butaca.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { insertMainProfile(db) }
                })
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        private const val PROFILE_TABLE =
            "CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`color` INTEGER NOT NULL, `kid` INTEGER NOT NULL, `kidsLimit` INTEGER NOT NULL, `timerMin` INTEGER NOT NULL, " +
                "`timerUsedMs` INTEGER NOT NULL, `timerExpired` INTEGER NOT NULL, `adsEvery` INTEGER NOT NULL, `tips` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"

        /** El perfil principal (id 1) siempre existe. */
        private fun insertMainProfile(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO profile (id, name, color, kid, kidsLimit, timerMin, timerUsedMs, timerExpired, adsEvery, tips, position, createdAt) " +
                    "VALUES (1, 'Principal', ${0xFFE8344BL}, 0, 0, 0, 0, 0, 0, 0, 0, ${System.currentTimeMillis()})"
            )
        }

        /** v5: perfiles (quién está viendo). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PROFILE_TABLE)
                insertMainProfile(db)
            }
        }

        /** v4: popularidad, país e idioma de TMDB (para ordenar el catálogo por categorías). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE item ADD COLUMN pop REAL")
                db.execSQL("ALTER TABLE item ADD COLUMN country TEXT")
                db.execSQL("ALTER TABLE item ADD COLUMN lang TEXT")
                // Releer de TMDB lo ya identificado (va casi todo desde la caché, es rápido)
                db.execSQL("UPDATE item SET enriched = 1 WHERE enriched = 2 AND tmdbId IS NOT NULL")
            }
        }

        /** v3: id de TMDB, edad recomendada y avisos de estrenos. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE item ADD COLUMN tmdbId INTEGER")
                db.execSQL("ALTER TABLE item ADD COLUMN cert INTEGER")
                // Volver a completar con TMDB (ahora también guardamos la edad)
                db.execSQL("UPDATE item SET enriched = 1 WHERE enriched = 2")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `alert` (`key` TEXT NOT NULL, `tmdbId` INTEGER NOT NULL, `isTv` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, `poster` TEXT, `year` INTEGER, `createdAt` INTEGER NOT NULL, `available` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        /** v2: datos ampliados de películas y nombres sin adornos (⭐) en tus listas. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE item ADD COLUMN enriched INTEGER NOT NULL DEFAULT 0")
                for (table in listOf("folder_item", "progress", "tracked_series")) {
                    db.execSQL("UPDATE OR IGNORE $table SET stableKey = REPLACE(REPLACE(stableKey, '⭐ ', ''), '⭐', '')")
                }
                db.execSQL("UPDATE OR IGNORE episode_progress SET seriesKey = REPLACE(REPLACE(seriesKey, '⭐ ', ''), '⭐', '')")
                db.execSQL("UPDATE OR IGNORE audio_pref SET seriesKey = REPLACE(REPLACE(seriesKey, '⭐ ', ''), '⭐', '')")
            }
        }
    }
}
