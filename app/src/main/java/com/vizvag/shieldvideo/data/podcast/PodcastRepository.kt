package com.vizvag.shieldvideo.data.podcast

import android.content.Context
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.nas.NasRepository
import com.vizvag.shieldvideo.data.settings.AppSettings
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class PodcastRepository(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val nasRepository: NasRepository,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheDir = File(appContext.filesDir, "podcast_cache").also { it.mkdirs() }
    private val mutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _subscriptions = MutableStateFlow(loadSubscriptions())
    val subscriptions: StateFlow<List<PodcastShow>> = _subscriptions.asStateFlow()

    private val _progress = MutableStateFlow(loadProgress())
    val progress: StateFlow<Map<String, PodcastEpisodeProgress>> = _progress.asStateFlow()

    fun subscriptionCount(): Int = _subscriptions.value.size

    fun lastImportAtMs(): Long = prefs.getLong(KEY_LAST_IMPORT_MS, 0L)

    var showSort: PodcastShowSort
        get() = PodcastShowSort.entries
            .firstOrNull { it.name == prefs.getString(KEY_SHOW_SORT, null) }
            ?: PodcastShowSort.TITLE
        set(value) {
            prefs.edit().putString(KEY_SHOW_SORT, value.name).apply()
        }

    var episodeSort: PodcastEpisodeSort
        get() = PodcastEpisodeSort.entries
            .firstOrNull { it.name == prefs.getString(KEY_EPISODE_SORT, null) }
            ?: PodcastEpisodeSort.NEWEST
        set(value) {
            prefs.edit().putString(KEY_EPISODE_SORT, value.name).apply()
        }

    fun replaceSubscriptions(shows: List<PodcastShow>) {
        val distinct = shows
            .filter { it.feedUrl.isNotBlank() }
            .distinctBy { it.feedUrl.trim().lowercase() }
            .sortedBy { it.title.lowercase() }
        saveSubscriptions(distinct)
        _subscriptions.value = distinct
        prefs.edit().putLong(KEY_LAST_IMPORT_MS, System.currentTimeMillis()).apply()
        clearCatalogSnapshot()
    }

    fun clearSubscriptions() {
        saveSubscriptions(emptyList())
        _subscriptions.value = emptyList()
        prefs.edit().putLong(KEY_LAST_IMPORT_MS, 0L).apply()
        cacheDir.listFiles()?.forEach { it.delete() }
        clearCatalogSnapshot()
    }

    /** Instant reopen: last painted shows + recent episodes (no network). */
    fun loadCatalogSnapshot(): PodcastCatalogSnapshot? {
        val file = catalogSnapshotFile()
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val showsArr = root.optJSONArray("shows") ?: return null
            val epsArr = root.optJSONArray("episodes") ?: return null
            val shows = parseShowsJson(showsArr)
            val episodes = buildList {
                for (i in 0 until epsArr.length()) {
                    val o = epsArr.optJSONObject(i) ?: continue
                    val guid = o.optString("guid").trim()
                    val audio = o.optString("audioUrl").trim()
                    if (guid.isBlank() || audio.isBlank()) continue
                    add(
                        PodcastEpisode(
                            guid = guid,
                            showId = o.optString("showId", ""),
                            title = o.optString("title", "Episode"),
                            description = o.optString("description", ""),
                            audioUrl = audio,
                            publishEpochMs = o.optLong("publishEpochMs", 0L),
                            durationSec = o.optLong("durationSec", 0L),
                            imageUrl = o.optString("imageUrl", ""),
                        ),
                    )
                }
            }
            if (shows.isEmpty() || episodes.isEmpty()) return null
            PodcastCatalogSnapshot(
                shows = shows,
                episodes = episodes,
                savedAtMs = root.optLong("savedAtMs", file.lastModified()),
            )
        }.getOrNull()
    }

    fun saveCatalogSnapshot(shows: List<PodcastShow>, episodes: List<PodcastEpisode>) {
        if (shows.isEmpty() || episodes.isEmpty()) return
        val showsArr = JSONArray()
        shows.forEach { s ->
            showsArr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("feedUrl", s.feedUrl)
                    .put("siteUrl", s.siteUrl)
                    .put("imageUrl", s.imageUrl)
                    .put("genres", JSONArray(s.genres))
                    .put("latestEpisodeEpochMs", s.latestEpisodeEpochMs),
            )
        }
        val epsArr = JSONArray()
        episodes.forEach { ep ->
            epsArr.put(
                JSONObject()
                    .put("guid", ep.guid)
                    .put("showId", ep.showId)
                    .put("title", ep.title)
                    .put("description", ep.description.take(500))
                    .put("audioUrl", ep.audioUrl)
                    .put("publishEpochMs", ep.publishEpochMs)
                    .put("durationSec", ep.durationSec)
                    .put("imageUrl", ep.imageUrl),
            )
        }
        val root = JSONObject()
            .put("savedAtMs", System.currentTimeMillis())
            .put("shows", showsArr)
            .put("episodes", epsArr)
        runCatching {
            catalogSnapshotFile().writeText(root.toString(), Charsets.UTF_8)
        }
    }

    fun clearCatalogSnapshot() {
        runCatching { catalogSnapshotFile().delete() }
    }

    fun exportSubscriptionsJson(): JSONArray {
        val arr = JSONArray()
        _subscriptions.value.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("feedUrl", s.feedUrl)
                    .put("siteUrl", s.siteUrl)
                    .put("imageUrl", s.imageUrl)
                    .put("genres", JSONArray(s.genres))
                    .put("latestEpisodeEpochMs", s.latestEpisodeEpochMs),
            )
        }
        return arr
    }

    fun importSubscriptionsJson(arr: JSONArray?) {
        if (arr == null) return
        val shows = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val feed = o.optString("feedUrl").trim()
                if (feed.isBlank()) continue
                val genresArr = o.optJSONArray("genres")
                val genres = buildList {
                    if (genresArr != null) {
                        for (g in 0 until genresArr.length()) {
                            genresArr.optString(g).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
                add(
                    PodcastShow(
                        id = o.optString("id").ifBlank {
                            java.util.UUID.nameUUIDFromBytes(feed.lowercase().toByteArray()).toString()
                        },
                        title = o.optString("title", feed),
                        feedUrl = feed,
                        siteUrl = o.optString("siteUrl", ""),
                        imageUrl = o.optString("imageUrl", ""),
                        genres = genres,
                        latestEpisodeEpochMs = o.optLong("latestEpisodeEpochMs", 0L),
                    ),
                )
            }
        }
        if (shows.isNotEmpty()) replaceSubscriptions(shows)
    }

    suspend fun importOpmlFromNasPath(fullPath: String): Result<Int> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        val path = fullPath.trim()
        if (path.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No OPML path"))
        }
        val text = readOpmlFromNas(settings, path).getOrElse {
            return@withContext Result.failure(it)
        }
        val shows = OpmlParser.parse(text)
        if (shows.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No podcast feeds found in OPML"))
        }
        replaceSubscriptions(shows)
        Result.success(shows.size)
    }

    suspend fun importOpmlFromLocalFile(absolutePath: String): Result<Int> = withContext(Dispatchers.IO) {
        val file = File(absolutePath)
        if (!file.isFile) {
            return@withContext Result.failure(IllegalStateException("File not found"))
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return@withContext Result.failure(it)
        }
        val shows = OpmlParser.parse(text)
        if (shows.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No podcast feeds found in OPML"))
        }
        replaceSubscriptions(shows)
        Result.success(shows.size)
    }

    suspend fun importOpmlPreferNas(): Result<Int> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        val nasPath = settings.podcastOpmlNasPath.trim()
        if (nasPath.isNotBlank()) {
            val nasResult = importOpmlFromNasPath(nasPath)
            if (nasResult.isSuccess) return@withContext nasResult
        }
        importOpmlFromDownloads()
    }

    suspend fun importOpmlFromNas(): Result<Int> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        val nasPath = settings.podcastOpmlNasPath.trim()
        if (nasPath.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Set a NAS OPML path first"))
        }
        importOpmlFromNasPath(nasPath)
    }

    suspend fun importOpmlFromDownloads(): Result<Int> = withContext(Dispatchers.IO) {
        val file = findDownloadsOpml()
            ?: return@withContext Result.failure(
                IllegalStateException("No .opml found in Downloads"),
            )
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return@withContext Result.failure(it)
        }
        val shows = OpmlParser.parse(text)
        if (shows.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No podcast feeds found in OPML"))
        }
        replaceSubscriptions(shows)
        Result.success(shows.size)
    }

    suspend fun episodesForShow(show: PodcastShow, forceRefresh: Boolean = false): List<PodcastEpisode> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val cacheFile = feedCacheFile(show.id)
                val stale = !cacheFile.exists() ||
                    System.currentTimeMillis() - cacheFile.lastModified() > STALE_MS
                if (!forceRefresh && !stale && cacheFile.exists()) {
                    val cached = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull()
                    if (!cached.isNullOrBlank()) {
                        val parsed = RssPodcastParser.parse(cached, show.id, show.imageUrl)
                        maybeUpdateShowMeta(show, parsed)
                        return@withContext parsed.episodes
                    }
                }
                val xml = fetchFeedXml(show.feedUrl).getOrElse {
                    if (cacheFile.exists()) {
                        val cached = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull()
                        if (!cached.isNullOrBlank()) {
                            val parsed = RssPodcastParser.parse(
                                cached,
                                show.id,
                                show.imageUrl,
                            ).episodes
                            return@withContext parsed
                        }
                    }
                    return@withContext emptyList()
                }
                runCatching { cacheFile.writeText(xml, Charsets.UTF_8) }
                val parsed = RssPodcastParser.parse(xml, show.id, show.imageUrl)
                maybeUpdateShowMeta(show, parsed)
                parsed.episodes
            }
        }

    /** Fast path for remotes — disk cache only, no network, no meta writes. */
    fun readCachedEpisodes(show: PodcastShow): List<PodcastEpisode> {
        val cacheFile = feedCacheFile(show.id)
        if (!cacheFile.exists()) return emptyList()
        val cached = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return RssPodcastParser.parse(cached, show.id, show.imageUrl).episodes
    }

    /**
     * Recent episodes across all subscriptions (disk cache only) for HA / remotes.
     * Labels are unique display strings (`Show · Episode`) for input_select options.
     */
    fun recentEpisodesForHa(limit: Int = HA_EPISODE_LIMIT): List<HaPodcastEpisodeRef> {
        val shows = _subscriptions.value.associateBy { it.id }
        val merged = shows.values.flatMap { show ->
            readCachedEpisodes(show).map { ep -> show to ep }
        }.sortedByDescending { it.second.publishEpochMs }
        val usedLabels = mutableSetOf<String>()
        return merged.take(limit.coerceAtLeast(1)).map { (show, ep) ->
            var label = episodeHaLabel(show.title, ep.title)
            if (label in usedLabels) {
                val suffix = ep.guid.takeLast(6).ifBlank { ep.hashCode().toString(16).takeLast(4) }
                label = "$label · $suffix".take(LABEL_MAX)
            }
            usedLabels.add(label)
            HaPodcastEpisodeRef(
                guid = ep.guid,
                showId = show.id,
                showTitle = show.title,
                title = ep.title,
                label = label,
                audioUrl = ep.audioUrl,
                imageUrl = ep.imageUrl.ifBlank { show.imageUrl },
                durationSec = ep.durationSec,
                publishEpochMs = ep.publishEpochMs,
            )
        }
    }

    fun findCachedEpisodeByGuid(guid: String, showId: String = ""): Pair<PodcastShow, PodcastEpisode>? {
        val g = guid.trim()
        if (g.isBlank()) return null
        val shows = _subscriptions.value
        val candidates = if (showId.isNotBlank()) {
            shows.filter { it.id == showId }
        } else {
            shows
        }
        for (show in candidates) {
            val ep = readCachedEpisodes(show).firstOrNull { it.guid == g }
            if (ep != null) return show to ep
        }
        if (showId.isNotBlank()) {
            for (show in shows) {
                val ep = readCachedEpisodes(show).firstOrNull { it.guid == g }
                if (ep != null) return show to ep
            }
        }
        return null
    }

    fun findCachedEpisodeByLabel(label: String): Pair<PodcastShow, PodcastEpisode>? {
        val want = label.trim()
        if (want.isBlank()) return null
        return recentEpisodesForHa(limit = HA_EPISODE_LIMIT * 2)
            .firstOrNull { it.label.equals(want, ignoreCase = true) }
            ?.let { ref -> findCachedEpisodeByGuid(ref.guid, ref.showId) }
    }

    /**
     * Match a spoken / deep-link show name to a subscription, then return that show's
     * newest cached episode. Used by `pallas://podcast?show=…` / voice "latest … podcast".
     */
    fun findLatestEpisodeByShowName(spoken: String): Pair<PodcastShow, PodcastEpisode>? {
        val show = findShowBySpokenName(spoken) ?: return null
        val latest = readCachedEpisodes(show)
            .filter { it.audioUrl.isNotBlank() }
            .maxByOrNull { it.publishEpochMs }
            ?: return null
        return show to latest
    }

    fun findShowBySpokenName(spoken: String): PodcastShow? {
        val raw = normalizeSpokenShowName(spoken)
        if (raw.isBlank()) return null
        val shows = _subscriptions.value
        if (shows.isEmpty()) return null
        shows.firstOrNull { normalizeSpokenShowName(it.title) == raw }?.let { return it }
        val scored = shows.map { show ->
            val title = normalizeSpokenShowName(show.title)
            val score = when {
                title == raw -> 100
                title.startsWith(raw) || raw.startsWith(title) -> 80 + minOf(title.length, raw.length)
                title.contains(raw) || raw.contains(title) -> 50 + minOf(title.length, raw.length)
                else -> {
                    val words = raw.split(' ').filter { it.isNotBlank() }
                    if (words.isNotEmpty() && words.all { it in title }) 40 + words.size * 5 else -1
                }
            }
            show to score
        }.filter { it.second >= 0 }
        return scored.maxByOrNull { it.second }?.first
    }

    private fun normalizeSpokenShowName(value: String): String {
        var s = value.lowercase().trim()
        s = s.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("^(the|my)\\s+"), "")
        s = s.replace(Regex("\\s+podcasts?$"), "")
        s = s.replace(Regex("\\s+podcasts?\\s+"), " ")
        return s.trim()
    }

    fun progressFor(guid: String): PodcastEpisodeProgress? = _progress.value[guid]

    fun saveProgress(
        guid: String,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean = false,
        showId: String = "",
    ) {
        if (guid.isBlank()) return
        val done = completed || (durationMs > 0L && positionMs >= durationMs - 5_000L)
        val existing = _progress.value[guid]
        val entry = PodcastEpisodeProgress(
            guid = guid,
            showId = showId.ifBlank { existing?.showId.orEmpty() },
            positionMs = if (done) 0L else positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            completed = done,
            updatedAtMs = System.currentTimeMillis(),
        )
        val next = _progress.value.toMutableMap()
        next[guid] = entry
        // Cap map size
        if (next.size > MAX_PROGRESS) {
            val oldest = next.values.sortedBy { it.updatedAtMs }.take(next.size - MAX_PROGRESS)
            oldest.forEach { next.remove(it.guid) }
        }
        _progress.value = next
        persistProgress(next)
    }

    fun markPlayed(guid: String, durationMs: Long = 0L) {
        saveProgress(guid, positionMs = 0L, durationMs = durationMs, completed = true)
    }

    private fun maybeUpdateShowMeta(show: PodcastShow, parsed: RssPodcastParser.ParsedFeed) {
        val title = parsed.title.takeIf { it.isNotBlank() } ?: show.title
        val image = parsed.imageUrl.takeIf { it.isNotBlank() } ?: show.imageUrl
        val site = parsed.siteUrl.takeIf { it.isNotBlank() } ?: show.siteUrl
        val genres = parsed.genres.ifEmpty { show.genres }
        val latest = parsed.episodes.maxOfOrNull { it.publishEpochMs }?.takeIf { it > 0L }
            ?: show.latestEpisodeEpochMs
        if (title == show.title &&
            image == show.imageUrl &&
            site == show.siteUrl &&
            genres == show.genres &&
            latest == show.latestEpisodeEpochMs
        ) {
            return
        }
        val updated = _subscriptions.value.map {
            if (it.id == show.id) {
                it.copy(
                    title = title,
                    imageUrl = image,
                    siteUrl = site,
                    genres = genres,
                    latestEpisodeEpochMs = latest,
                )
            } else {
                it
            }
        }
        saveSubscriptions(updated)
        _subscriptions.value = updated
    }

    private fun fetchFeedXml(url: String): Result<String> = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PallasVideoPlayer/2.5 (Podcasts)")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: error("Empty feed")
        }
    }

    private suspend fun readOpmlFromNas(settings: AppSettings, fullPath: String): Result<String> {
        val normalized = fullPath.trim().replace('\\', '/')
        val (share, rel) = NasPaths.parseFolderPath(normalized)
            ?: return Result.failure(IllegalArgumentException("Invalid NAS path"))
        if (rel.isBlank()) {
            return Result.failure(IllegalArgumentException("OPML path must include a file name"))
        }
        return nasRepository.readTextAt(settings, share, rel)
    }

    private fun findDownloadsOpml(): File? {
        val dirs = listOf(
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download"),
            appContext.getExternalFilesDir(null),
            appContext.filesDir,
        )
        val candidates = dirs.filterNotNull().flatMap { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".opml", ignoreCase = true) }
                .orEmpty()
        }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.lastModified() }
    }

    private fun feedCacheFile(showId: String): File =
        File(cacheDir, "${showId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}.xml")

    private fun catalogSnapshotFile(): File = File(cacheDir, CATALOG_SNAPSHOT_FILE)

    private fun loadSubscriptions(): List<PodcastShow> {
        val raw = prefs.getString(KEY_SUBS, null) ?: return emptyList()
        return runCatching { parseShowsJson(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun parseShowsJson(arr: JSONArray): List<PodcastShow> =
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val feed = o.optString("feedUrl").trim()
                if (feed.isBlank()) continue
                val genresArr = o.optJSONArray("genres")
                val genres = buildList {
                    if (genresArr != null) {
                        for (g in 0 until genresArr.length()) {
                            genresArr.optString(g).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                }
                add(
                    PodcastShow(
                        id = o.optString("id").ifBlank {
                            java.util.UUID.nameUUIDFromBytes(feed.lowercase().toByteArray()).toString()
                        },
                        title = o.optString("title", feed),
                        feedUrl = feed,
                        siteUrl = o.optString("siteUrl", ""),
                        imageUrl = o.optString("imageUrl", ""),
                        genres = genres,
                        latestEpisodeEpochMs = o.optLong("latestEpisodeEpochMs", 0L),
                    ),
                )
            }
        }

    private fun saveSubscriptions(shows: List<PodcastShow>) {
        val arr = JSONArray()
        shows.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("feedUrl", s.feedUrl)
                    .put("siteUrl", s.siteUrl)
                    .put("imageUrl", s.imageUrl)
                    .put("genres", JSONArray(s.genres))
                    .put("latestEpisodeEpochMs", s.latestEpisodeEpochMs),
            )
        }
        prefs.edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    private fun loadProgress(): Map<String, PodcastEpisodeProgress> {
        val raw = prefs.getString(KEY_PROGRESS, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val guid = o.optString("guid").trim()
                    if (guid.isBlank()) continue
                    put(
                        guid,
                        PodcastEpisodeProgress(
                            guid = guid,
                            showId = o.optString("showId", ""),
                            positionMs = o.optLong("positionMs").coerceAtLeast(0L),
                            durationMs = o.optLong("durationMs").coerceAtLeast(0L),
                            completed = o.optBoolean("completed", false),
                            updatedAtMs = o.optLong("updatedAtMs").coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistProgress(map: Map<String, PodcastEpisodeProgress>) {
        val arr = JSONArray()
        map.values.forEach { p ->
            arr.put(
                JSONObject()
                    .put("guid", p.guid)
                    .put("showId", p.showId)
                    .put("positionMs", p.positionMs)
                    .put("durationMs", p.durationMs)
                    .put("completed", p.completed)
                    .put("updatedAtMs", p.updatedAtMs),
            )
        }
        prefs.edit().putString(KEY_PROGRESS, arr.toString()).apply()
    }

    companion object {
        fun episodeHaLabel(showTitle: String, episodeTitle: String): String {
            val show = showTitle.trim().ifBlank { "Podcast" }
            val ep = episodeTitle.trim().ifBlank { "Episode" }
            return "$show · $ep".take(LABEL_MAX)
        }

        private const val PREFS = "podcasts"
        private const val KEY_SUBS = "subscriptions"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_LAST_IMPORT_MS = "last_import_ms"
        private const val KEY_SHOW_SORT = "show_sort"
        private const val KEY_EPISODE_SORT = "episode_sort"
        private const val CATALOG_SNAPSHOT_FILE = "catalog_snapshot.json"
        private const val STALE_MS = 6L * 60L * 60L * 1000L
        private const val MAX_PROGRESS = 500
        private const val LABEL_MAX = 120
        private const val HA_EPISODE_LIMIT = 40
    }
}
