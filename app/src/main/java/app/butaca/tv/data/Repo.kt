package app.butaca.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import app.butaca.tv.ButacaApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Lo mínimo para mostrar y guardar un título en cualquier lista. */
data class ContentRef(
    val stableKey: String,
    val type: String,
    val title: String,
    val icon: String?,
    val year: Int?,
    val rating: Double?,
    val itemKey: String?,          // null si ya no está en el servidor
    val addedAt: Long = 0L,
) {
    val isSeries get() = type == ContentType.SERIES
    val isLive get() = type == ContentType.LIVE
}

fun ItemEntity.toRef() = ContentRef(stableKey, type, name, icon, year, rating, key)
fun FolderItemEntity.toRef(itemKey: String?) = ContentRef(stableKey, type, title, icon, year, rating, itemKey, addedAt)

data class SyncState(val running: Boolean = false, val message: String = "", val error: String? = null)

data class PlayItem(
    val urls: List<String>,         // de mejor a peor calidad
    val title: String,
    val subtitle: String?,
    val stableKey: String,
    val type: String,
    val icon: String?,
    val episodeId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class PlayRequest(
    val items: List<PlayItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val seriesKey: String? = null,
    val seriesLastEpisodeId: String? = null,
)

/** Una fila de títulos relacionados en la ficha (saga, director, reparto…). */
data class RelatedRow(
    val title: String,
    val items: List<ItemEntity>,
    /** Títulos de la saga que no están en tu servidor (se ven en gris). */
    val missing: List<TmdbTitle> = emptyList(),
)

/** Lo que TMDB aporta a una ficha. */
data class Related(
    val rows: List<RelatedRow>,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val people: List<TmdbPerson> = emptyList(),
    val logo: String? = null,
    val trailer: String? = null,
    val runtime: Int? = null,
    val cert: Int? = null,
    val tv: TvStatus? = null,
    val tmdbId: Int? = null,
    /** Etiquetas pulsables: géneros, temas y estudio. */
    val tagObjs: List<Tag> = emptyList(),
)

class Repo(
    val db: ButacaDb,
    val client: XtreamClient,
    val prefs: Prefs,
    val tmdb: TmdbClient,
    private val userDbFor: (Long) -> UserDb,
) {
    // ----- Perfiles -----
    private val userDbs = HashMap<Long, UserDb>()
    /** Datos del perfil que está viendo (carpetas, progreso, historial…). */
    @Volatile var user: UserDb = userDb(prefs.value.lastProfile)
        private set
    private val _profile = MutableStateFlow<ProfileEntity?>(null)
    val profile: StateFlow<ProfileEntity?> = _profile.asStateFlow()

    private fun userDb(id: Long): UserDb = synchronized(userDbs) { userDbs.getOrPut(id) { userDbFor(id) } }

    /** Edad máxima del contenido para el perfil actual (0 = sin límite). */
    val kidsLimit: Int get() = _profile.value?.let { if (it.kid) it.kidsLimit.coerceAtLeast(3) else 0 } ?: 0
    val isKid: Boolean get() = _profile.value?.kid == true

    fun profiles(): Flow<List<ProfileEntity>> = db.profiles().observe()

    suspend fun selectProfile(p: ProfileEntity) {
        usagePause()
        user = userDb(p.id)
        _adultUnlocked.value = false
        _profile.value = p
        prefs.update { it.copy(lastProfile = p.id) }
        resetProfileCaches()
        Taste.clear(); Taste.rebuild(this, 400)
        usageResume()
        if (p.kid) startKidsPrep()
    }

    fun leaveProfile() { usagePause(); Taste.clear(); _profile.value = null }

    suspend fun saveProfile(p: ProfileEntity): Long {
        val id = if (p.id == 0L) db.profiles().insert(p.copy(position = db.profiles().all().size, createdAt = System.currentTimeMillis()))
        else { db.profiles().update(p); p.id }
        if (_profile.value?.id == id) _profile.value = db.profiles().get(id)
        if (p.kid) startKidsPrep()
        return id
    }

    suspend fun deleteProfile(id: Long) {
        if (id == 1L) return   // el principal no se borra
        db.profiles().delete(id)
        synchronized(userDbs) { userDbs.remove(id)?.close() }
        ButacaApp.instance.deleteDatabase(UserDb.fileName(id))
    }

    // ----- Tiempo de uso y temporizador infantil -----
    private var usageFrom = 0L
    private val _timeUp = MutableStateFlow(false)
    /** Se ha agotado el tiempo del perfil infantil (la app lo echa al selector). */
    val timeUp: StateFlow<Boolean> = _timeUp.asStateFlow()
    private var ticker: Job? = null

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun usageResume() {
        if (_profile.value == null) return
        usageFrom = System.currentTimeMillis()
        if (ticker?.isActive != true) ticker = scope.launch {
            while (true) { delay(15_000); flushUsage() }
        }
    }

    fun usagePause() {
        ticker?.cancel(); ticker = null
        val from = usageFrom
        usageFrom = 0L
        val p = _profile.value ?: return
        if (from > 0) { val ms = System.currentTimeMillis() - from; scope.launch { addUsage(p, ms) } }
    }

    private suspend fun flushUsage() {
        val p = _profile.value ?: return
        val from = usageFrom
        if (from <= 0) return
        val now = System.currentTimeMillis()
        usageFrom = now
        addUsage(p, now - from)
    }

    private suspend fun addUsage(p: ProfileEntity, ms: Long) {
        if (ms <= 0 || ms > 6 * 3600_000L) return
        val st = userDb(p.id).stats()
        val day = today()
        val u = st.usage(day) ?: UsageDayEntity(day, 0, 0)
        st.upsertUsage(u.copy(appMs = u.appMs + ms))
        // Temporizador de los perfiles infantiles
        if (p.kid && p.timerMin > 0 && !p.timerExpired) {
            val cur = db.profiles().get(p.id) ?: return
            val used = cur.timerUsedMs + ms
            val expired = used >= cur.timerMin * 60_000L
            db.profiles().setTimer(p.id, used, expired)
            val fresh = db.profiles().get(p.id)
            if (_profile.value?.id == p.id) _profile.value = fresh
            if (expired && _profile.value?.id == p.id) _timeUp.value = true
        }
    }

    /** Minutos que le quedan al perfil infantil (null si no tiene temporizador). */
    fun minutesLeft(p: ProfileEntity? = _profile.value): Int? {
        if (p == null || !p.kid || p.timerMin <= 0) return null
        val live = if (usageFrom > 0 && p.id == _profile.value?.id) System.currentTimeMillis() - usageFrom else 0L
        return (((p.timerMin * 60_000L) - p.timerUsedMs - live) / 60_000L).toInt().coerceAtLeast(0)
    }

    fun clearTimeUp() { _timeUp.value = false }

    /** Un adulto (con PIN) da más tiempo: el contador vuelve a empezar. */
    suspend fun resetTimer(id: Long, minutes: Int) {
        val p = db.profiles().get(id) ?: return
        db.profiles().update(p.copy(timerMin = minutes, timerUsedMs = 0, timerExpired = false))
        if (_profile.value?.id == id) _profile.value = db.profiles().get(id)
        _timeUp.value = false
    }

    /** Historial: suma lo que se ha visto de un título hoy. */
    suspend fun logWatch(stableKey: String, type: String, title: String, icon: String?, subtitle: String?, ms: Long, session: Long = 0L) {
        if (ms <= 0 || _profile.value == null) return
        // Historial de reproducciones: una línea por cada vez que se ve algo
        if (session > 0) {
            val part = subtitle.orEmpty()
            val pl = user.plays().get(session, stableKey, part)
            val now0 = System.currentTimeMillis()
            user.plays().upsert(
                (pl ?: PlayLogEntity(session, stableKey, part, type, title, icon, now0 - ms, now0, 0))
                    .copy(lastAt = now0, watchedMs = (pl?.watchedMs ?: 0) + ms)
            )
        }
        val st = user.stats()
        val day = today()
        val now = System.currentTimeMillis()
        val l = st.log(day, stableKey)
        st.upsertLog(
            (l ?: WatchLogEntity(day, stableKey, type, title, icon, subtitle, 0, now))
                .copy(watchedMs = (l?.watchedMs ?: 0) + ms, lastAt = now, subtitle = subtitle ?: l?.subtitle, title = title, icon = icon ?: l?.icon)
        )
        val u = st.usage(day) ?: UsageDayEntity(day, 0, 0)
        st.upsertUsage(u.copy(watchMs = u.watchMs + ms))
    }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    // ----- Control parental: desbloqueo por sesión -----
    private val _adultUnlocked = MutableStateFlow(false)
    val adultUnlocked: StateFlow<Boolean> = _adultUnlocked.asStateFlow()
    private var unlockedAt = 0L

    /** Mostrar adultos ahora mismo (ajuste desactivado o desbloqueado con PIN). */
    val showAdult: Flow<Boolean> = combine(prefs.flow, adultUnlocked, profile) { s, u, p -> p?.kid != true && (!s.adultHide || u) }

    fun showAdultNow(): Boolean = !isKid && (!prefs.value.adultHide || _adultUnlocked.value)

    fun unlockAdult(pin: String): Boolean {
        if (prefs.value.pin.isNotEmpty() && pin == prefs.value.pin) {
            _adultUnlocked.value = true
            unlockedAt = System.currentTimeMillis()
            return true
        }
        return false
    }

    fun lockAdult() { _adultUnlocked.value = false }

    fun onAppStopped() { if (prefs.value.relock) lockAdult() }

    fun checkIdleLock() {
        if (_adultUnlocked.value && prefs.value.relock &&
            System.currentTimeMillis() - unlockedAt > 30 * 60 * 1000L
        ) lockAdult()
    }

    fun touchUnlock() { if (_adultUnlocked.value) unlockedAt = System.currentTimeMillis() }

    // ----- Cuenta -----
    fun account(): Account? {
        val s = prefs.value
        return if (s.loggedIn) Account(s.server, s.user, s.pass) else null
    }

    suspend fun login(serverRaw: String, user: String, pass: String): String? {
        val server = XtreamClient.normalizeServer(serverRaw)
        val acc = Account(server, user.trim(), pass)
        return try {
            val r = client.authenticate(acc)
            if (!r.ok || r.message != null) return r.message ?: "No se ha podido conectar"
            prefs.update {
                it.copy(
                    server = server, user = acc.user, pass = acc.pass,
                    accountStatus = r.status, expDate = r.expDate, maxConnections = r.maxConnections,
                )
            }
            ensureSystemFolders()
            null
        } catch (e: Exception) {
            "No se ha podido conectar: ${e.message ?: "error de red"}"
        }
    }

    fun logout() {
        prefs.update { it.copy(server = "", user = "", pass = "", lastSync = 0L, accountStatus = "", expDate = 0L) }
    }

    suspend fun refreshAccountInfo() {
        val a = account() ?: return
        try {
            val r = client.authenticate(a)
            prefs.update { it.copy(accountStatus = r.status, expDate = r.expDate, maxConnections = r.maxConnections) }
        } catch (_: Exception) { }
    }

    // ----- Sincronización del catálogo -----
    fun syncIfNeeded() {
        val s = prefs.value
        if (!s.loggedIn) return
        val dayMs = 24 * 60 * 60 * 1000L
        scope.launch {
            val empty = db.items().count() == 0
            if (empty || System.currentTimeMillis() - s.lastSync > dayMs) syncNow()
            else refreshTrackedSeries()
            // Copia en la nube cada 6 horas si tienes cuenta de TMDB conectada
            if (prefs.value.tmdbSession.isNotBlank() && System.currentTimeMillis() - prefs.value.lastCloudSync > 6 * 3600_000L) cloudSync()
            startKidsPrep()   // lee géneros y edades de todo el catálogo en TMDB (una vez)
        }
    }

    fun syncInBackground(): Job = scope.launch { syncNow() }

    suspend fun syncNow() = syncMutex.withLock {
        val a = account() ?: return@withLock
        _sync.value = SyncState(true, "Conectando con el servidor…")
        try {
            ensureSystemFolders()
            refreshAccountInfo()
            val stamp = System.currentTimeMillis()
            val firstSync = db.items().count() == 0
            syncType(a, ContentType.LIVE, "get_live_categories", "get_live_streams", stamp, firstSync, "canales")
            syncType(a, ContentType.MOVIE, "get_vod_categories", "get_vod_streams", stamp, firstSync, "películas")
            syncType(a, ContentType.SERIES, "get_series_categories", "get_series", stamp, firstSync, "series")
            prefs.update { it.copy(lastSync = System.currentTimeMillis()) }
            _sync.value = SyncState(true, "Buscando episodios nuevos…")
            refreshTrackedSeries()
            try { checkAlerts() } catch (_: Exception) { }
            startKidsPrep()   // lee géneros y edades de todo el catálogo en TMDB (una vez)
            _sync.value = SyncState(false, "")
        } catch (e: Exception) {
            _sync.value = SyncState(false, "", "No se pudo actualizar el catálogo: ${e.message ?: "error de red"}")
        }
    }

    private suspend fun syncType(
        a: Account, type: String, catAction: String, listAction: String,
        stamp: Long, firstSync: Boolean, label: String,
    ) {
        _sync.value = SyncState(true, "Descargando $label…")
        val now = System.currentTimeMillis()
        val existingCats = db.categories().all(type).associateBy { it.key }
        val cats = client.categories(a, catAction)
        val catEntities = cats.mapIndexed { i, (id, name) ->
            val key = "$type:$id"
            val old = existingCats[key]
            val auto = TextUtil.looksAdult(name)
            CategoryEntity(
                key = key, type = type, catId = id, name = TextUtil.cleanName(name), position = i,
                autoAdult = auto,
                userAdult = old?.userAdult,
                hidden = old?.hidden ?: false,
                firstSeen = old?.firstSeen ?: now,
                reviewed = old?.reviewed ?: firstSync,
            )
        }
        db.categories().upsert(catEntities)
        if (catEntities.isNotEmpty()) db.categories().deleteMissing(type, catEntities.map { it.key })
        val catByKey = catEntities.associateBy { it.key }
        val newCatsHidden = prefs.value.newCatsHidden

        // Lo que ya habíamos completado (género, sinopsis…) no se pierde al actualizar
        val enriched = db.items().enrichments(type).associateBy { it.key }
        var position = 0
        var total = 0
        client.streamList(a, listAction) { batch ->
            val items = batch.mapNotNull { m ->
                val it = mapItem(type, m, catByKey, newCatsHidden, stamp, position++) ?: return@mapNotNull null
                val e = enriched[it.key]
                if (e == null) it else it.copy(
                    genre = if (e.enriched >= 2) e.genre ?: it.genre else it.genre ?: e.genre,
                    plot = it.plot ?: e.plot, backdrop = it.backdrop ?: e.backdrop, enriched = e.enriched,
                    tmdbId = e.tmdbId, cert = e.cert, pop = e.pop, country = e.country, lang = e.lang,
                )
            }
            db.items().upsert(items)
            total += items.size
            _sync.value = SyncState(true, "Descargando $label… $total")
        }
        db.items().deleteStale(type, stamp)

        // Categorías en las que el servidor marca casi todo como adulto
        val counts = db.categories().counts(type)
        counts.forEach { c ->
            val cat = catByKey[c.categoryKey] ?: return@forEach
            if (cat.userAdult == null && !cat.autoAdult && c.total > 0 && c.adult * 2 >= c.total) {
                val updated = cat.copy(autoAdult = true)
                db.categories().upsert(listOf(updated))
                db.items().applyCategoryAdult(cat.key, true)
            }
        }
    }

    private fun mapItem(
        type: String, m: Map<String, String>, cats: Map<String, CategoryEntity>,
        newCatsHidden: Boolean, stamp: Long, position: Int,
    ): ItemEntity? {
        val id = when (type) {
            ContentType.SERIES -> m["series_id"]
            else -> m["stream_id"]
        } ?: return null
        val name = TextUtil.cleanName(m["name"] ?: m["title"] ?: return null)
        val catKey = "$type:${m["category_id"] ?: ""}"
        val cat = cats[catKey]
        val adultFlag = m["is_adult"] == "1" || m["is_adult"].equals("true", true)
        val catAdult = cat?.isAdult ?: false
        // Una categoría nueva que parece de adultos se queda oculta hasta que la revises
        val suspicious = newCatsHidden && cat != null && !cat.reviewed && cat.userAdult == null && TextUtil.looksAdult(name)
        val isAdult = adultFlag || catAdult || suspicious
        val added = when (type) {
            ContentType.SERIES -> (m["last_modified"] ?: m["added"])?.toLongOrNull() ?: 0L
            else -> m["added"]?.toLongOrNull() ?: 0L
        }
        val year = m["year"]?.toIntOrNull()
            ?: TextUtil.parseYear(m["releaseDate"] ?: m["release_date"])
            ?: TextUtil.extractYear(name)
        val rating = (m["rating"] ?: "").replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
            ?: m["rating_5based"]?.toDoubleOrNull()?.takeIf { it > 0 }?.times(2)
        val tmdb = (m["tmdb"] ?: m["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" }
        val stableKey = when (type) {
            ContentType.LIVE -> "l:" + TextUtil.baseName(name)
            ContentType.MOVIE -> "m:" + (tmdb?.let { "t$it" } ?: (TextUtil.titleKey(name) + "|" + (year ?: "")))
            else -> "s:" + (tmdb?.let { "t$it" } ?: TextUtil.titleKey(name))
        }
        return ItemEntity(
            key = "$type:$id",
            type = type,
            streamId = id,
            name = name,
            normName = TextUtil.norm(name),
            categoryKey = catKey,
            icon = (m["stream_icon"] ?: m["cover"])?.takeIf { it.isNotBlank() },
            backdrop = m["backdrop_path"]?.takeIf { it.isNotBlank() },
            rating = rating,
            added = added,
            year = year,
            ext = m["container_extension"],
            genre = m["genre"]?.takeIf { it.isNotBlank() },
            plot = m["plot"]?.takeIf { it.isNotBlank() },
            adultFlag = adultFlag,
            isAdult = isAdult,
            stableKey = stableKey,
            baseKey = TextUtil.baseName(name),
            quality = TextUtil.qualityRank(name),
            position = position,
            syncStamp = stamp,
        )
    }

    // ----- Series seguidas: avisar de episodios nuevos -----
    suspend fun refreshTrackedSeries() {
        val a = account() ?: return
        val keys = (user.folders().seriesInFolders() + user.progress().seriesKeys()).distinct()
        for (k in keys) {
            val item = db.items().byStableKey(k).firstOrNull() ?: continue
            val tracked = user.tracked().tracked(k)
            if (tracked != null && tracked.lastModified == item.added && tracked.seriesId == item.streamId) continue
            val info = try { client.seriesInfo(a, item.streamId) } catch (e: Exception) { null } ?: continue
            val eps = info.episodes.size
            val seasons = info.seasons.size
            if (tracked == null) {
                user.tracked().upsertTracked(
                    TrackedSeriesEntity(k, item.streamId, eps, seasons, 0, false, item.added, item.name, item.icon)
                )
            } else {
                val newEps = (eps - tracked.knownEpisodes).coerceAtLeast(0)
                user.tracked().upsertTracked(
                    tracked.copy(
                        seriesId = item.streamId,
                        newEpisodes = tracked.newEpisodes + newEps,
                        newSeason = tracked.newSeason || seasons > tracked.knownSeasons,
                        knownEpisodes = maxOf(eps, tracked.knownEpisodes),
                        knownSeasons = maxOf(seasons, tracked.knownSeasons),
                        lastModified = item.added,
                        title = item.name,
                        icon = item.icon,
                    )
                )
            }
        }
    }

    suspend fun markSeriesSeen(stableKey: String, info: SeriesInfo, item: ItemEntity) {
        val t = user.tracked().tracked(stableKey)
        if (t == null) {
            user.tracked().upsertTracked(
                TrackedSeriesEntity(stableKey, item.streamId, info.episodes.size, info.seasons.size, 0, false, item.added, item.name, item.icon)
            )
        } else user.tracked().markSeen(stableKey, info.episodes.size, info.seasons.size)
    }

    // ----- Perfil infantil -----
    private val kidsLiveRx = Regex("(?i)(infantil|kids|niñ|nin[oa]s|junior|disney|cartoon|clan|boing|nick|baby|dibujos|animaci)")

    /** ¿Se puede ver con el perfil infantil activo? Lo que no tiene edad conocida queda fuera. */
    fun kidsOk(item: ItemEntity): Boolean {
        val limit = kidsLimit
        if (limit <= 0) return true
        if (item.isAdult) return false
        if (item.type == ContentType.LIVE) return true   // los canales se filtran por categoría
        val c = item.cert ?: return false
        return c <= limit
    }

    // ----- Consultas -----
    @OptIn(ExperimentalCoroutinesApi::class)
    fun categories(type: String): Flow<List<CategoryEntity>> =
        profile.map { kidsLimit }.distinctUntilChanged().flatMapLatestCompat { limit ->
            if (limit <= 0) combine(db.categories().observe(type), showAdult) { list, adult ->
                list.filter { !it.hidden && (adult || !it.isAdult) }
            } else if (type == ContentType.LIVE) db.categories().observe(type).map { list ->
                list.filter { !it.isAdult && kidsLiveRx.containsMatchIn(it.name) }
            } else combine(db.categories().observe(type), db.items().observeKidsCategories(type, limit)) { list, ok ->
                val set = ok.toSet()
                list.filter { !it.isAdult && it.key in set }
            }
        }

    fun allCategories(): Flow<List<CategoryEntity>> = db.categories().observeAll()

    /** Elementos de una categoría, sin repetir el mismo canal en varias calidades. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun categoryItems(categoryKey: String, type: String): Flow<List<ItemEntity>> =
        combine(db.items().observeCategory(categoryKey), showAdult) { list, adult ->
            val visible = list.filter { (adult || !it.isAdult) && kidsOk(it) }
            collapseVariants(visible, type)
        }

    fun collapseVariants(list: List<ItemEntity>, type: String): List<ItemEntity> {
        val max = prefs.value.maxQuality
        val groupKey: (ItemEntity) -> String =
            if (type == ContentType.LIVE) { e: ItemEntity -> e.baseKey } else { e: ItemEntity -> e.stableKey }
        val groups = LinkedHashMap<String, MutableList<ItemEntity>>()
        list.forEach { groups.getOrPut(groupKey(it)) { mutableListOf() }.add(it) }
        if (type == ContentType.LIVE) return groups.values.map { g -> pickBest(g, max) }
        // La misma película con distinto nombre en el servidor: si TMDB dice que es la misma, una sola carátula
        val byTmdb = LinkedHashMap<String, MutableList<ItemEntity>>()
        groups.values.forEach { g ->
            val id = g.firstNotNullOfOrNull { it.tmdbId }
            byTmdb.getOrPut(if (id != null) "t$id" else "k" + g.first().stableKey) { mutableListOf() }.addAll(g)
        }
        return byTmdb.values.map { g -> pickBest(g, max) }
    }

    private fun pickBest(group: List<ItemEntity>, max: Int): ItemEntity =
        group.filter { it.quality <= max }.maxByOrNull { it.quality } ?: group.minBy { it.quality }

    fun recent(type: String, limit: Int = 60): Flow<List<ItemEntity>> =
        showAdult.flatMapLatestCompat { adult -> db.items().observeRecent(type, adult, if (kidsLimit > 0) limit * 6 else limit) }
            .map { l -> collapseVariants(l.filter { kidsOk(it) }, type).take(limit) }

    /**
     * Busca la frase tal cual, su traducción (títulos conocidos) y por palabras sueltas
     * con sus traducciones, para que «bola de dragon» encuentre «Dragon Ball».
     */
    suspend fun search(query: String): List<ItemEntity> {
        val q = TextUtil.norm(query)
        if (q.length < 2) return emptyList()
        val adult = showAdultNow()
        val res = LinkedHashMap<String, ItemEntity>()
        db.items().search(q, adult, 300).forEach { res.putIfAbsent(it.key, it) }
        for (v in TextUtil.phraseVariants(q)) db.items().search(v, adult, 150).forEach { res.putIfAbsent(it.key, it) }
        // Títulos en español y en inglés según TMDB («Bola de Dragón» ↔ «Dragon Ball»)
        if (tmdb.enabled) {
            val variants = kotlinx.coroutines.withTimeoutOrNull(3000) { tmdb.titleVariants(query) }.orEmpty()
            for (v in variants) {
                val nv = TextUtil.titleKey(v)
                if (nv.length >= 3 && nv != q) db.items().search(nv, adult, 60).forEach { res.putIfAbsent(it.key, it) }
            }
        }
        val tokens = TextUtil.searchTokens(q).take(5)
        if (tokens.isNotEmpty()) {
            val args = ArrayList<Any>()
            val where = tokens.joinToString(" AND ") { alts ->
                "(" + alts.joinToString(" OR ") { alt -> args.add("% $alt%"); "(' ' || normName || ' ') LIKE ?" } + ")"
            }
            val sql = "SELECT * FROM item WHERE $where" + (if (adult) "" else " AND isAdult = 0") + " ORDER BY added DESC LIMIT 300"
            db.items().raw(androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())).forEach { res.putIfAbsent(it.key, it) }
        }
        val out = ArrayList<ItemEntity>()
        val byType = res.values.filter { kidsOk(it) }.groupBy { it.type }
        for (t in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE)) {
            out += collapseVariants(byType[t].orEmpty(), t)
        }
        return out
    }

    /** Novedades para el carrusel: sin adultos y sin lo que ya has visto. */
    fun novedades(): Flow<List<ItemEntity>> {
        val since = System.currentTimeMillis() / 1000 - 14 * 24 * 3600
        return combine(db.items().observeNew(since), user.progress().observeWatchedKeys(), user.tracked().observeNewEpisodes()) { list, watched, tracked ->
            val watchedSet = watched.toSet()
            val trackedKeys = tracked.map { it.stableKey }.toSet()
            val seen = HashSet<String>()
            list.filter { it.stableKey !in watchedSet && kidsOk(it) && seen.add(it.stableKey) }
                .sortedWith(compareByDescending<ItemEntity> { it.stableKey in trackedKeys }.thenByDescending { it.added })
                .take(10)
        }
    }

    suspend fun bestItem(stableKey: String): ItemEntity? {
        val list = db.items().byStableKey(stableKey)
        if (list.isEmpty()) return null
        return pickBest(list, prefs.value.maxQuality)
    }

    suspend fun itemByKey(key: String) = db.items().byKey(key)

    /** Todas las versiones (de mejor a peor dentro del límite) para poder cambiar si una falla. */
    suspend fun variantsFor(item: ItemEntity): List<ItemEntity> {
        val max = prefs.value.maxQuality
        val list = if (item.type == ContentType.LIVE) db.items().variants(item.type, item.baseKey)
        else db.items().byStableKey(item.stableKey)
        val within = list.filter { it.quality <= max }.sortedByDescending { it.quality }
        val rest = list.filter { it.quality > max }.sortedBy { it.quality }
        val ordered = (listOf(item) + within + rest).distinctBy { it.key }
        return ordered
    }

    // ----- Completar géneros (bajo la carátula) -----
    // Con TMDB: géneros de TMDB para películas y series. Sin TMDB: los del servidor (solo películas).
    private val enrichQueue = kotlinx.coroutines.channels.Channel<ItemEntity>(capacity = 400)
    private val enrichRequested = java.util.Collections.synchronizedSet(HashSet<String>())

    init {
        repeat(3) {
            scope.launch {
                for (item in enrichQueue) {
                    try {
                        if (tmdb.enabled) {
                            enrichWithTmdb(item)
                        } else if (item.type == ContentType.MOVIE) {
                            val a = account() ?: continue
                            val info = client.vodInfo(a, item.streamId)
                            db.items().enrich(item.streamId, info?.genre, info?.plot, info?.backdrop)
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }

    /** Pide el género de un título que se está mostrando, una sola vez. */
    fun requestGenre(item: ItemEntity) {
        if (item.type == ContentType.LIVE) return
        val needed = if (tmdb.enabled) item.enriched < 2 else (item.type == ContentType.MOVIE && item.enriched == 0 && item.genre == null)
        if (!needed) return
        if (enrichRequested.add(item.stableKey)) enrichQueue.trySend(item)
    }

    /** Id de TMDB del título (lo guarda para la próxima vez). */
    suspend fun tmdbIdFor(item: ItemEntity, hint: String? = null): Int? {
        item.tmdbId?.let { return it }
        val isTv = item.type == ContentType.SERIES
        val fromKey = item.stableKey.substringAfter(":").takeIf { it.startsWith("t") }?.drop(1)?.toIntOrNull()
        return hint?.toIntOrNull() ?: fromKey ?: tmdb.findId(isTv, item.name, item.year)
    }

    /** Géneros, edad, sinopsis y fondo desde TMDB. */
    suspend fun enrichWithTmdb(item: ItemEntity) {
        val isTv = item.type == ContentType.SERIES
        val id = tmdbIdFor(item)
        if (id == null) { db.items().markTried(item.stableKey); return }
        val d = tmdb.light(isTv, id)
        if (d == null) { if (tmdb.lastError == null) db.items().markTried(item.stableKey); return }
        db.items().enrichTmdb(
            item.stableKey,
            tmdb.genres(d).joinToString(", ") { it.second }.ifBlank { null },
            d.optString("overview").ifBlank { null },
            TmdbClient.img("w1280", d.optString("backdrop_path")),
            id, tmdb.certification(d, isTv), tmdb.popularity(d), tmdb.country(d), tmdb.language(d),
        )
    }

    suspend fun saveVodInfo(item: ItemEntity, info: VodInfo) {
        db.items().enrich(item.streamId, info.genre, info.plot, info.backdrop)
    }

    // ----- Títulos similares -----
    /** Misma saga primero, luego mismo género y, si falta, de la misma categoría. */
    suspend fun similar(item: ItemEntity, genre: String?): List<ItemEntity> {
        val out = LinkedHashMap<String, ItemEntity>()
        val words = TextUtil.titleKey(item.name).split(' ').filter { it.length > 2 && it !in setOf("the", "los", "las", "del") }
        val prefix = words.take(2).joinToString(" ")
        if (prefix.length >= 4) db.items().saga(item.type, prefix, item.stableKey).forEach { out.putIfAbsent(it.stableKey, it) }
        val genres = genre?.split(',', '/', '|')?.map { it.trim() }?.filter { it.length > 2 }.orEmpty()
        val sameCat = db.items().byCategory(item.categoryKey, item.stableKey)
        if (genres.isNotEmpty()) {
            val byGenre = db.items().byGenre(item.type, genres.first(), item.stableKey)
            // Preferimos los que comparten más géneros
            byGenre.sortedByDescending { g -> genres.count { gen -> g.genre?.contains(gen, true) == true } }
                .forEach { out.putIfAbsent(it.stableKey, it) }
        }
        sameCat.forEach { out.putIfAbsent(it.stableKey, it) }
        return collapseVariants(out.values.toList(), item.type).take(12)
    }

    // ----- Relacionados con TMDB: saga, director, reparto y recomendaciones -----
    /** Busca en tu servidor los títulos que da TMDB (solo enseñamos lo que puedes ver). */
    internal suspend fun matchTitles(titles: List<TmdbTitle>, exclude: String?): List<ItemEntity> {
        if (titles.isEmpty()) return emptyList()
        // Primero por id de TMDB (exacto), luego por título y año
        val byId = HashMap<Pair<Int, Boolean>, List<ItemEntity>>()
        titles.map { it.id }.distinct().chunked(400).forEach { ids ->
            db.items().byTmdbIds(ids).groupBy { it.tmdbId!! to (it.type == ContentType.SERIES) }.forEach { (k, v) -> byId[k] = v }
        }
        val candidates = titles.map { t ->
            val keys = ArrayList<String>()
            val names = listOfNotNull(t.title, t.originalTitle).map { TextUtil.titleKey(it) }.filter { it.isNotBlank() }.distinct()
            if (t.isTv) {
                keys += "s:t${t.id}"
                names.forEach { keys += "s:$it" }
            } else {
                keys += "m:t${t.id}"
                names.forEach { n ->
                    val y = t.year
                    if (y != null) { keys += "m:$n|$y"; keys += "m:$n|${y - 1}"; keys += "m:$n|${y + 1}" }
                    keys += "m:$n|"
                }
            }
            keys
        }
        val all = candidates.flatten().distinct()
        val found = all.chunked(500).flatMap { db.items().byStableKeys(it) }
            .filter { !it.isAdult && it.stableKey != exclude }
            .groupBy { it.stableKey }
        val max = prefs.value.maxQuality
        val out = LinkedHashMap<String, ItemEntity>()
        candidates.forEachIndexed { i, keys ->
            val t = titles[i]
            val hit = byId[t.id to t.isTv]?.filter { !it.isAdult && it.stableKey != exclude }?.takeIf { it.isNotEmpty() }
                ?: keys.firstNotNullOfOrNull { found[it] } ?: return@forEachIndexed
            val best = hit.filter { it.quality <= max }.maxByOrNull { it.quality } ?: hit.first()
            if (kidsOk(best)) out.putIfAbsent(best.stableKey, best)
        }
        // Sin repetidos: la misma película de TMDB solo una vez
        val seen = HashSet<Int>()
        return out.values.filter { it.tmdbId == null || seen.add(it.tmdbId) }
    }

    suspend fun related(item: ItemEntity, serverTmdbId: String?): Related {
        if (!tmdb.enabled || item.type == ContentType.LIVE) return Related(emptyList())
        val isTv = item.type == ContentType.SERIES
        return try {
            val id = tmdbIdFor(item, serverTmdbId) ?: return Related(emptyList())
            val d = tmdb.details(isTv, id) ?: return Related(emptyList())
            // Todo sale de TMDB; tu servidor solo se usa para saber qué tienes disponible.
            val genres = tmdb.genres(d)
            val genreIds = genres.map { it.first }

            var sagaMissing: List<TmdbTitle> = emptyList()
            val saga = d.optJSONObject("belongs_to_collection")?.let { c ->
                val name = c.optString("name").removeSuffix(" - Colección").removeSuffix(" (Colección)").removePrefix("Colección ")
                val parts = tmdb.collection(c.optInt("id")).filter { it.id != id }
                val have = matchTitles(parts, item.stableKey)
                // Las que no tienes, para verlas en gris
                sagaMissing = parts.filter { p -> matchTitles(listOf(p), null).isEmpty() }
                "Saga: $name" to have
            }
            val director = tmdb.directors(d, isTv).firstOrNull()?.let { p ->
                ((if (isTv) "De los creadores: " else "Más de ") + p.name) to matchTitles(tmdb.personWork(p.id, asDirector = true), item.stableKey)
            }
            val cast = tmdb.mainCast(d, isTv, 2).map { p ->
                ("Con " + p.name) to matchTitles(tmdb.personWork(p.id, asDirector = false).take(80), item.stableKey)
            }

            // Guardamos los géneros de TMDB en el título para verlos bajo la carátula
            val cert = tmdb.certification(d, isTv)
            db.items().enrichTmdb(
                item.stableKey, genres.joinToString(", ") { it.second }.ifBlank { null }, d.optString("overview").ifBlank { null },
                TmdbClient.img("w1280", d.optString("backdrop_path")), id, cert,
                tmdb.popularity(d), tmdb.country(d), tmdb.language(d),
            )

            // ---- Mismo estilo, coherente ----
            // Géneros «fuertes»: si el título no los tiene, no sugerimos nada que los tenga
            // (así una comedia no trae películas de animación infantiles).
            val strong = setOf(16, 10751, 99, 27, 10402, 37, 10752, 878, 14, 10762, 10763, 10764, 10766, 10767, 10765, 10768)
            val lang = d.optString("original_language").ifBlank { null }
            val keywords = tmdb.keywords(d, isTv)
            val kwIds = keywords.map { it.first }.take(5)
            val byKeyword = if (kwIds.isEmpty()) emptyList() else tmdb.discover(isTv, emptyList(), 2, mapOf("with_keywords" to kwIds.joinToString("|")))
            val kwSet = byKeyword.map { it.id }.toSet()
            val sameLang = if (lang != null && lang != "en" && genreIds.isNotEmpty())
                tmdb.discover(isTv, genreIds.take(1), 3, mapOf("with_original_language" to lang)) else emptyList()
            // Lo que TMDB recomienda para este título manda: cuanto más arriba en su lista, más peso.
            val recs = tmdb.recommendationPages(isTv, id, d, 3)
            val sims = tmdb.similarPages(isTv, id, d, 2)
            val recRank = recs.withIndex().associate { it.value.id to it.index }
            val simRank = sims.withIndex().associate { it.value.id to it.index }
            fun coherent(t: TmdbTitle) = t.genreIds.none { it in strong && it !in genreIds }
            val pool = (recs + sims + byKeyword + sameLang + tmdb.discover(isTv, genreIds.take(2), 4))
                .distinctBy { it.id to it.isTv }
                .filter { t ->
                    coherent(t) && (genreIds.isEmpty() || t.genreIds.isEmpty() || t.genreIds.any { it in genreIds })
                }
                .sortedByDescending { t ->
                    val shared = t.genreIds.count { it in genreIds }
                    val extra = t.genreIds.count { it !in genreIds }
                    val rec = recRank[t.id]?.let { 60.0 - it } ?: 0.0          // recomendaciones de TMDB
                    val sim = simRank[t.id]?.let { 25.0 - it * 0.6 } ?: 0.0    // parecidos de TMDB
                    rec + sim + (if (rec > 0 && sim > 0) 15.0 else 0.0) +
                        shared * 6.0 - extra * 2.0 +
                        (if (lang != null && t.language == lang) 10.0 else 0.0) +
                        (if (t.id in kwSet) 8.0 else 0.0) +
                        t.popularity / 1000.0
                }
            val styleItems = matchTitles(pool, item.stableKey)

            // Estudio o cadena: probamos con los principales hasta encontrar alguno con títulos en tu servidor
            var studio: Pair<String, List<ItemEntity>>? = null
            for (st in tmdb.studios(d, isTv).take(3)) {
                val found = matchTitles(tmdb.byStudio(isTv, st), item.stableKey)
                if (found.size >= 2) { studio = ("Más de " + st.name) to found; break }
            }

            // Orden de prioridad: director, saga, estudio, mismo estilo y reparto.
            // Un título solo aparece en la primera fila que le toca.
            val claimed = HashSet<String>()
            val rows = ArrayList<RelatedRow>()
            fun add(title: String, list: List<ItemEntity>, max: Int = 15, missing: List<TmdbTitle> = emptyList()) {
                val fresh = list.filter { it.stableKey !in claimed }.take(max)
                if (fresh.isNotEmpty() || missing.isNotEmpty()) { rows += RelatedRow(title, fresh, missing); fresh.forEach { claimed += it.stableKey } }
            }
            // La saga va primero si existe: es lo más relacionado; luego el director
            saga?.let { add(it.first, it.second, 20, sagaMissing) }
            director?.let { add(it.first, it.second) }
            studio?.let { add(it.first, it.second) }
            val label = genres.take(3).joinToString(", ") { it.second }
            add(if (label.isBlank()) "Te puede gustar" else "Te puede gustar · $label", styleItems, 20)
            cast.forEach { add(it.first, it.second) }
            Related(
                rows, genres.map { it.second }, keywords.map { it.second }.take(10),
                people = tmdb.people(d, isTv, 8),
                logo = tmdb.logo(d), trailer = tmdb.trailer(d), runtime = tmdb.runtimeMinutes(d, isTv),
                cert = cert, tv = if (isTv) tmdb.tvStatus(d) else null, tmdbId = id,
                tagObjs = genres.map { (gid, name) -> if (isTv) Tag(TagKind.GENRE, name, null, gid) else Tag(TagKind.GENRE, name, gid, null) } +
                    tmdb.studios(d, isTv).take(2).map { st -> Tag(if (st.isNetwork) TagKind.NETWORK else TagKind.COMPANY, st.name, st.id, st.id) } +
                    keywords.take(10).map { (kid, name) -> Tag(TagKind.KEYWORD, name, kid, kid) },
            )
        } catch (e: Exception) {
            tmdb.lastError = e.message ?: e.javaClass.simpleName
            Related(emptyList())
        }
    }

    // ----- Ir a una carpeta desde otra pantalla -----
    val requestedFolder = MutableStateFlow<Long?>(null)
    @Volatile private var systemColorsDone = false

    // ----- Carpetas -----
    suspend fun ensureSystemFolders() {
        val dao = user.folders()
        if (dao.systemFolder("later") == null) dao.insertFolder(FolderEntity(name = "Ver más adelante", color = 0xFFE8344B, position = -2, system = "later"))
        if (dao.systemFolder("fav") == null) dao.insertFolder(FolderEntity(name = "Favoritos", color = 0xFFE8344B, position = -1, system = "fav"))
        // Con la nueva imagen, las dos listas fijas van en el rojo de la app
        if (!systemColorsDone) { dao.setSystemColor(0xFFE8344B); systemColorsDone = true }
    }

    fun folders(): Flow<List<FolderEntity>> = user.folders().observeFolders()
    fun folderCounts(): Flow<Map<Long, Int>> = user.folders().observeCounts().map { l -> l.associate { it.folderId to it.total } }
    fun foldersOf(stableKey: String): Flow<Set<Long>> = user.folders().observeFoldersOf(stableKey).map { it.toSet() }

    suspend fun systemFolderId(system: String): Long {
        ensureSystemFolders()
        return user.folders().systemFolder(system)!!.id
    }

    /** Contenido de una carpeta, resolviendo si sigue existiendo en el servidor. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun folderContent(folderId: Long): Flow<List<ContentRef>> =
        combine(user.folders().observeItems(folderId), showAdult) { items, adult -> items to adult }
            .mapLatestCompat { (items, adult) ->
                val found = items.map { it.stableKey }.chunked(500).flatMap { db.items().byStableKeys(it) }
                val byKey = found.groupBy { it.stableKey }
                items.mapNotNull { fi ->
                    val best = byKey[fi.stableKey]?.let { pickBest(it, prefs.value.maxQuality) }
                    if (best != null && ((best.isAdult && !adult) || !kidsOk(best))) null
                    else if (best == null && kidsLimit > 0) null
                    else fi.toRef(best?.key).copy(icon = best?.icon ?: fi.icon)
                }
            }

    suspend fun addToFolder(folderId: Long, ref: ContentRef) {
        val min = user.folders().minPosition(folderId)
        user.folders().insertItem(
            FolderItemEntity(folderId, ref.stableKey, ref.type, ref.title, ref.icon, ref.year, ref.rating, min - 1, System.currentTimeMillis())
        )
    }

    suspend fun removeFromFolder(folderId: Long, stableKey: String) = user.folders().removeItem(folderId, stableKey)

    suspend fun toggleFolder(folderId: Long, ref: ContentRef, inFolder: Boolean) {
        if (inFolder) removeFromFolder(folderId, ref.stableKey) else addToFolder(folderId, ref)
        Taste.rebuild(this)
    }

    suspend fun toggleSystem(system: String, ref: ContentRef, inFolder: Boolean) =
        toggleFolder(systemFolderId(system), ref, inFolder)

    suspend fun createFolder(name: String, color: Long): Long {
        val pos = user.folders().maxFolderPosition() + 1
        return user.folders().insertFolder(FolderEntity(name = name.trim().ifBlank { "Carpeta" }, color = color, position = pos))
    }

    suspend fun updateFolder(id: Long, name: String, color: Long) = user.folders().updateFolder(id, name, color)

    suspend fun deleteFolder(id: Long) {
        user.folders().clearFolder(id)
        user.folders().deleteFolder(id)
    }

    /** Mueve un título dentro de la carpeta (delta negativo = hacia delante). */
    suspend fun moveInFolder(folderId: Long, stableKey: String, delta: Int) {
        val items = user.folders().items(folderId).toMutableList()
        val idx = items.indexOfFirst { it.stableKey == stableKey }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, items.size - 1)
        if (target == idx) return
        val it = items.removeAt(idx)
        items.add(target, it)
        user.folders().upsertItems(items.mapIndexed { i, f -> f.copy(position = i) })
    }

    // ----- Progreso y vistas -----
    fun continueRefs(): Flow<List<ContentRef>> = user.progress().observeContinue().mapLatestCompat { resolveProgress(it) }
    fun watchedRefs(): Flow<List<ContentRef>> = user.progress().observeWatched().mapLatestCompat { resolveProgress(it) }

    private suspend fun resolveProgress(list: List<ProgressEntity>): List<ContentRef> {
        val found = list.map { it.stableKey }.chunked(500).flatMap { db.items().byStableKeys(it) }.groupBy { it.stableKey }
        val adult = showAdultNow()
        return list.mapNotNull { p ->
            val best = found[p.stableKey]?.let { pickBest(it, prefs.value.maxQuality) }
            if (best != null && ((best.isAdult && !adult) || !kidsOk(best))) null
            else if (best == null && kidsLimit > 0) null
            else ContentRef(p.stableKey, p.type, p.title, best?.icon ?: p.icon, best?.year, best?.rating, best?.key, p.updatedAt)
        }
    }

    fun continueWatching(): Flow<List<ProgressEntity>> = user.progress().observeContinue()
    fun watched(): Flow<List<ProgressEntity>> = user.progress().observeWatched()
    /** Títulos en Favoritos y en Ver más adelante, para marcarlos en las carátulas. */
    fun marks(): Flow<Marks> = user.folders().observeMarks().map { l ->
        Marks(l.filter { it.system == "fav" }.map { it.stableKey }.toSet(), l.filter { it.system == "later" }.map { it.stableKey }.toSet())
    }

    /** Todo lo visto o empezado por este perfil. */
    fun activity(): Flow<List<ActivityRow>> = user.progress().observeActivity()

    fun watchedKeys(): Flow<Set<String>> = user.progress().observeWatchedKeys().map { it.toSet() }
    fun progressOf(stableKey: String): Flow<ProgressEntity?> = user.progress().observe(stableKey)
    fun episodeProgress(seriesKey: String): Flow<List<EpisodeProgressEntity>> = user.progress().observeEpisodes(seriesKey)
    fun newEpisodes(): Flow<List<TrackedSeriesEntity>> = user.tracked().observeNewEpisodes()

    suspend fun saveMovieProgress(item: PlayItem, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val finished = positionMs >= durationMs * 0.92
        user.progress().upsert(
            ProgressEntity(item.stableKey, item.type, item.title, item.icon, positionMs, durationMs, System.currentTimeMillis(), finished)
        )
        if (finished) onFinished(ContentRef(item.stableKey, item.type, item.title, item.icon, null, null, null))
    }

    suspend fun saveEpisodeProgress(
        item: PlayItem, seriesTitle: String, seriesIcon: String?, positionMs: Long, durationMs: Long, lastEpisodeId: String?,
    ) {
        val epId = item.episodeId ?: return
        if (durationMs <= 0) return
        val finished = positionMs >= durationMs * 0.92
        user.progress().upsertEpisode(EpisodeProgressEntity(epId, item.stableKey, positionMs, durationMs, finished, System.currentTimeMillis()))
        val seriesDone = finished && epId == lastEpisodeId
        user.progress().upsert(
            ProgressEntity(
                stableKey = item.stableKey, type = ContentType.SERIES, title = seriesTitle, icon = seriesIcon,
                positionMs = if (finished) 1 else positionMs, durationMs = durationMs,
                updatedAt = System.currentTimeMillis(), finished = seriesDone,
                episodeId = epId, season = item.season, episode = item.episode, episodeTitle = item.subtitle,
            )
        )
        if (seriesDone) onFinished(ContentRef(item.stableKey, ContentType.SERIES, seriesTitle, seriesIcon, null, null, null))
    }

    /** Al terminar: sale de «Ver más adelante», pasa a «Vistas» y te pregunta qué te ha parecido. */
    private suspend fun onFinished(ref: ContentRef, askRating: Boolean = true) {
        user.folders().systemFolder("later")?.let { user.folders().removeItem(it.id, ref.stableKey) }
        if (askRating && user.taste().get(ref.stableKey) == null) RatePrompt.ask(ref)
        Taste.rebuild(this)
    }

    suspend fun markWatched(ref: ContentRef, watched: Boolean) {
        if (watched) {
            user.progress().upsert(ProgressEntity(ref.stableKey, ref.type, ref.title, ref.icon, 1, 1, System.currentTimeMillis(), true))
            onFinished(ref, askRating = false)   // desde la ficha se pregunta directamente
        } else {
            user.progress().delete(ref.stableKey)
            if (ref.isSeries) user.progress().clearEpisodes(ref.stableKey)
        }
    }

    suspend fun dismissContinue(stableKey: String) = user.progress().dismiss(stableKey)

    suspend fun audioPref(seriesKey: String) = db.misc().audio(seriesKey)
    suspend fun saveAudioPref(p: AudioPrefEntity) = db.misc().upsertAudio(p)

    // ----- Categorías -----
    suspend fun setCategoryAdult(cat: CategoryEntity, adult: Boolean?) {
        db.categories().setAdult(cat.key, adult)
        db.items().applyCategoryAdult(cat.key, adult ?: cat.autoAdult)
    }

    suspend fun setCategoryHidden(key: String, hidden: Boolean) = db.categories().setHidden(key, hidden)

    // ----- Reproducción -----
    var pendingPlay: PlayRequest? = null

    suspend fun playRequestFor(item: ItemEntity, resumeMs: Long = 0L): PlayRequest? {
        val a = account() ?: return null
        val variants = variantsFor(item)
        val urls = variants.map {
            when (it.type) {
                ContentType.LIVE -> client.liveUrl(a, it.streamId)
                else -> client.movieUrl(a, it.streamId, it.ext)
            }
        }
        return PlayRequest(
            items = listOf(PlayItem(urls, item.name, null, item.stableKey, item.type, item.icon)),
            startIndex = 0,
            startPositionMs = resumeMs,
        )
    }

    fun episodesRequest(series: ItemEntity, info: SeriesInfo, startEpisodeId: String, resumeMs: Long): PlayRequest? {
        val a = account() ?: return null
        val items = info.episodes.map { ep ->
            PlayItem(
                urls = listOf(client.episodeUrl(a, ep.id, ep.ext)),
                title = series.name,
                subtitle = "T${ep.season} · E${ep.number} · ${ep.title}",
                stableKey = series.stableKey,
                type = ContentType.SERIES,
                icon = series.icon,
                episodeId = ep.id,
                season = ep.season,
                episode = ep.number,
            )
        }
        val idx = info.episodes.indexOfFirst { it.id == startEpisodeId }.coerceAtLeast(0)
        return PlayRequest(items, idx, resumeMs, series.stableKey, info.episodes.lastOrNull()?.id)
    }
}

// Pequeños ayudantes para no depender de APIs experimentales en todo el código
@OptIn(ExperimentalCoroutinesApi::class)
private fun <T, R> Flow<T>.flatMapLatestCompat(transform: (T) -> Flow<R>): Flow<R> = flatMapLatest { transform(it) }

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T, R> Flow<T>.mapLatestCompat(transform: suspend (T) -> R): Flow<R> =
    flatMapLatest { v -> kotlinx.coroutines.flow.flow { emit(transform(v)) } }

@Suppress("unused")
private fun <T> emptyFlow(): Flow<List<T>> = flowOf(emptyList())

data class Marks(val fav: Set<String> = emptySet(), val later: Set<String> = emptySet())
