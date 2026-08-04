package com.vizvag.shieldvideo.data.iptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

data class IptvCatalog(
    val playlistId: String = "",
    val channels: List<IptvChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val loadedAtMs: Long = 0L,
    val epgLoadedAtMs: Long = 0L,
    val epgChannelCount: Int = 0,
    val epgFromDisk: Boolean = false,
    val channelsFromDisk: Boolean = false,
    val channelsStale: Boolean = false,
    val epgLoading: Boolean = false,
    val error: String? = null
)

class IptvRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Channel catalog only — never share with EPG so Live TV preview is not blocked. */
    private val channelsMutex = Mutex()
    /** EPG parse/index only — downloads happen outside this lock. */
    private val epgMutex = Mutex()
    private val epgByTvgId = java.util.concurrent.ConcurrentHashMap<String, List<IptvProgramme>>()
    private val epgByLower = java.util.concurrent.ConcurrentHashMap<String, List<IptvProgramme>>()
    @Volatile
    private var epgChannelIndex: List<EpgChannelEntry> = emptyList()
    /** Extra XMLTV ids (from manual channel→EPG mapping) always included in programme parse. */
    @Volatile
    private var extraWantedEpgIds: Set<String> = emptySet()

    private val _catalog = MutableStateFlow(IptvCatalog())
    val catalog: StateFlow<IptvCatalog> = _catalog.asStateFlow()

    private val cacheDir: File
        get() = File(context.filesDir, "iptv_cache").also { it.mkdirs() }

    /**
     * Instant hydrate for Live TV: memory → compact snapshot → groups text → (async) M3U/network.
     * Does **not** wait for EPG (separate lock; memory hit returns without locking).
     */
    suspend fun ensureChannelsLoaded(
        playlist: IptvPlaylistConfig,
        forceRefresh: Boolean = false
    ): IptvCatalog = withContext(Dispatchers.IO) {
        // Memory hit must not wait on an in-flight EPG download/parse.
        if (!forceRefresh) {
            val current = _catalog.value
            if (current.playlistId == playlist.id && current.channels.isNotEmpty()) {
                return@withContext current
            }
        }

        channelsMutex.withLock {
            val current = _catalog.value
            if (!forceRefresh &&
                current.playlistId == playlist.id &&
                current.channels.isNotEmpty()
            ) {
                return@withLock current
            }

            // Fastest disk path: pre-parsed channel snapshot (not raw M3U).
            if (!forceRefresh && loadSnapshotLocked(playlist.id)) {
                return@withLock _catalog.value
            }

            val m3uFile = m3uCacheFile(playlist.id)
            if (!forceRefresh && m3uFile.exists() && m3uFile.length() > 0L) {
                applyM3uText(
                    playlistId = playlist.id,
                    text = m3uFile.readText(),
                    loadedAtMs = m3uFile.lastModified(),
                    fromDisk = true,
                    stale = isStale(m3uFile.lastModified(), M3U_TTL_MS)
                )
                return@withLock _catalog.value
            }

            try {
                val text = downloadText(playlist.m3uUrl).also { m3uFile.writeText(it) }
                applyM3uText(
                    playlistId = playlist.id,
                    text = text,
                    loadedAtMs = m3uFile.lastModified().takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                    fromDisk = false,
                    stale = false
                )
            } catch (e: Exception) {
                if (m3uFile.exists() && m3uFile.length() > 0L) {
                    applyM3uText(
                        playlistId = playlist.id,
                        text = m3uFile.readText(),
                        loadedAtMs = m3uFile.lastModified(),
                        fromDisk = true,
                        stale = true,
                        error = "Playlist network failed — using cache (${e.message})"
                    )
                } else if (loadSnapshotLocked(playlist.id)) {
                    _catalog.value = _catalog.value.copy(
                        error = "Playlist network failed — using snapshot (${e.message})"
                    )
                } else {
                    _catalog.value = IptvCatalog(
                        playlistId = playlist.id,
                        error = e.message ?: "Unable to download playlist"
                    )
                }
            }
            _catalog.value
        }
    }

    /** Peek groups from the tiny groups snapshot without parsing channels (UI first paint). */
    fun peekGroupsFromDisk(playlistId: String): List<String> {
        val mem = _catalog.value
        if (mem.playlistId == playlistId && mem.groups.isNotEmpty()) return mem.groups
        return ChannelSnapshot.readGroups(groupsSnapshotFile(playlistId)).orEmpty()
    }

    /** Background refresh when disk M3U is older than TTL. */
    suspend fun refreshChannelsIfStale(playlist: IptvPlaylistConfig): IptvCatalog =
        withContext(Dispatchers.IO) {
            channelsMutex.withLock {
                val m3uFile = m3uCacheFile(playlist.id)
                val need = !m3uFile.exists() ||
                    m3uFile.length() == 0L ||
                    isStale(m3uFile.lastModified(), M3U_TTL_MS) ||
                    _catalog.value.channelsStale
                if (!need) return@withLock _catalog.value
                try {
                    val text = downloadText(playlist.m3uUrl).also { m3uFile.writeText(it) }
                    applyM3uText(
                        playlistId = playlist.id,
                        text = text,
                        loadedAtMs = System.currentTimeMillis(),
                        fromDisk = false,
                        stale = false
                    )
                } catch (_: Exception) {
                    // Keep existing catalog; UI already has disk data.
                }
                _catalog.value
            }
        }

    private fun loadSnapshotLocked(playlistId: String): Boolean {
        val snap = ChannelSnapshot.read(ChannelSnapshot.file(cacheDir, playlistId)) ?: return false
        if (snap.channels.isEmpty()) return false
        val keepEpg = _catalog.value.playlistId == playlistId && epgByTvgId.isNotEmpty()
        _catalog.value = _catalog.value.copy(
            playlistId = playlistId,
            channels = snap.channels,
            groups = snap.groups,
            loadedAtMs = snap.loadedAtMs,
            channelsFromDisk = true,
            channelsStale = isStale(snap.loadedAtMs, M3U_TTL_MS),
            epgLoadedAtMs = if (keepEpg) _catalog.value.epgLoadedAtMs else 0L,
            epgChannelCount = if (keepEpg) _catalog.value.epgChannelCount else 0,
            epgFromDisk = if (keepEpg) _catalog.value.epgFromDisk else false,
            error = null
        )
        if (!keepEpg) {
            clearEpgMaps()
        }
        return true
    }

    /**
     * Load EPG from memory → disk → network. Safe to call after channels are shown.
     * Network download never holds [epgMutex] / never blocks channel load.
     */
    suspend fun ensureEpgLoaded(
        playlist: IptvPlaylistConfig,
        forceRefresh: Boolean = false
    ): IptvCatalog = withContext(Dispatchers.IO) {
        if (playlist.epgUrl.isBlank()) return@withContext _catalog.value

        epgMutex.withLock {
            _catalog.value = _catalog.value.copy(epgLoading = true)
        }
        try {
            loadEpg(playlist, forceRefresh = forceRefresh)
        } finally {
            epgMutex.withLock {
                _catalog.value = _catalog.value.copy(epgLoading = false)
            }
        }
        _catalog.value
    }

    /** @deprecated Prefer [ensureChannelsLoaded] + [ensureEpgLoaded]. */
    suspend fun ensureLoaded(playlist: IptvPlaylistConfig, forceRefresh: Boolean = false): IptvCatalog {
        ensureChannelsLoaded(playlist, forceRefresh)
        return ensureEpgLoaded(playlist, forceRefresh)
    }

    suspend fun refreshEpg(playlist: IptvPlaylistConfig): IptvCatalog =
        ensureEpgLoaded(playlist, forceRefresh = true)

    fun setExtraWantedEpgIds(ids: Set<String>) {
        extraWantedEpgIds = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun epgChannels(): List<EpgChannelEntry> = epgChannelIndex

    fun searchEpgChannels(query: String, limit: Int = 80): List<EpgChannelEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return epgChannelIndex.take(limit)
        return epgChannelIndex.asSequence()
            .filter {
                it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
            }
            .take(limit)
            .toList()
    }

    fun programmesForTvgId(tvgId: String?): List<IptvProgramme> {
        val tvg = tvgId?.trim().orEmpty()
        if (tvg.isEmpty()) return emptyList()
        epgByTvgId[tvg]?.let { return it }
        epgByLower[tvg.lowercase()]?.let { return it }
        return emptyList()
    }

    fun programmesFor(channel: IptvChannel): List<IptvProgramme> =
        programmesForTvgId(channel.tvgId)

    /** Parse programmes for ids missing from memory (e.g. after assigning a new EPG channel). */
    suspend fun ensureProgrammesForIds(
        playlist: IptvPlaylistConfig,
        ids: Set<String>
    ): Boolean = withContext(Dispatchers.IO) {
        epgMutex.withLock {
            val missing = ids.map { it.trim() }.filter { id ->
                id.isNotEmpty() &&
                    epgByTvgId[id] == null &&
                    epgByLower[id.lowercase()] == null
            }.toSet()
            if (missing.isEmpty()) return@withLock true
            val epgFile = epgCacheFile(playlist.id)
            if (!epgFile.exists() || epgFile.length() == 0L) return@withLock false
            val err = parseEpgFile(epgFile, missing, merge = true)
            err == null
        }
    }

    fun nowNext(channel: IptvChannel): IptvNowNext =
        XmltvParser.nowNext(programmesFor(channel))

    fun searchChannels(
        query: String,
        channels: List<IptvChannel>,
        limit: Int = 60
    ): List<IptvChannel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<IptvChannel>(minOf(limit, 64))
        for (ch in channels) {
            if (ch.name.lowercase().contains(q) ||
                ch.group.lowercase().contains(q) ||
                (ch.tvgId?.lowercase()?.contains(q) == true)
            ) {
                out += ch
                if (out.size >= limit) break
            }
        }
        return out
    }

    fun searchProgrammes(query: String, limit: Int = 40): List<Pair<IptvChannel, IptvProgramme>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val channels = _catalog.value.channels
        val byTvg = channels.associateBy { it.tvgId?.lowercase().orEmpty() }
        val results = ArrayList<Pair<IptvChannel, IptvProgramme>>(limit)
        // Titles first — description scans over a full XMLTV cache are too expensive for typing.
        for ((key, programmes) in epgByLower) {
            val channel = byTvg[key] ?: continue
            for (p in programmes) {
                if (p.title.lowercase().contains(q)) {
                    results += channel to p
                    if (results.size >= limit) return results
                }
            }
        }
        if (results.size >= limit) return results
        for ((key, programmes) in epgByLower) {
            val channel = byTvg[key] ?: continue
            for (p in programmes) {
                val desc = p.description ?: continue
                if (desc.lowercase().contains(q) &&
                    results.none { it.first.id == channel.id && it.second.startMs == p.startMs }
                ) {
                    results += channel to p
                    if (results.size >= limit) return results
                }
            }
        }
        return results
    }

    private fun applyM3uText(
        playlistId: String,
        text: String,
        loadedAtMs: Long,
        fromDisk: Boolean,
        stale: Boolean,
        error: String? = null
    ) {
        val channels = M3uParser.parse(text)
        val groups = channels.map { it.group }.distinct().sortedBy { it.lowercase() }
        val keepEpg = _catalog.value.playlistId == playlistId && epgByTvgId.isNotEmpty()
        _catalog.value = _catalog.value.copy(
            playlistId = playlistId,
            channels = channels,
            groups = groups,
            loadedAtMs = loadedAtMs,
            channelsFromDisk = fromDisk,
            channelsStale = stale,
            epgLoadedAtMs = if (keepEpg) _catalog.value.epgLoadedAtMs else 0L,
            epgChannelCount = if (keepEpg) _catalog.value.epgChannelCount else 0,
            epgFromDisk = if (keepEpg) _catalog.value.epgFromDisk else false,
            error = error
        )
        if (!keepEpg) {
            clearEpgMaps()
        }
        // Persist fast snapshot for next cold start.
        try {
            ChannelSnapshot.write(
                ChannelSnapshot.file(cacheDir, playlistId),
                loadedAtMs,
                groups,
                channels
            )
            ChannelSnapshot.writeGroups(groupsSnapshotFile(playlistId), groups)
        } catch (_: Exception) {
            // Non-fatal — next open can still parse M3U.
        }
    }

    private fun groupsSnapshotFile(playlistId: String) =
        ChannelSnapshot.groupsFile(cacheDir, playlistId)

    /**
     * Prefer any on-disk EPG for instant fill-in; download only when missing/forced/stale
     * (stale disk still used first, then upgraded from network).
     * Downloads run outside [epgMutex] so channel open / preview never waits on the network.
     */
    private suspend fun loadEpg(playlist: IptvPlaylistConfig, forceRefresh: Boolean) {
        if (playlist.epgUrl.isBlank()) return
        val channels = _catalog.value.channels
        val wanted = buildWantedEpgIds(channels)
        if (wanted.isEmpty() && extraWantedEpgIds.isEmpty()) {
            epgMutex.withLock {
                val epgFile = epgCacheFile(playlist.id)
                if (epgFile.exists()) loadEpgChannelIndex(epgFile)
                _catalog.value = _catalog.value.copy(
                    epgLoadedAtMs = System.currentTimeMillis(),
                    epgChannelCount = 0,
                    epgFromDisk = false
                )
            }
            return
        }

        val needNetwork = epgMutex.withLock {
            val memAge = System.currentTimeMillis() - _catalog.value.epgLoadedAtMs
            if (!forceRefresh &&
                _catalog.value.playlistId == playlist.id &&
                _catalog.value.epgLoadedAtMs > 0L &&
                memAge <= EPG_TTL_MS &&
                epgByTvgId.isNotEmpty()
            ) {
                if (epgChannelIndex.isEmpty()) {
                    val epgFile = epgCacheFile(playlist.id)
                    if (epgFile.exists()) loadEpgChannelIndex(epgFile)
                }
                return@withLock false
            }

            val epgFile = epgCacheFile(playlist.id)
            val diskExists = epgFile.exists() && epgFile.length() > 0L

            // Prefer on-disk EPG (even if older than TTL) so Live TV never waits on the network.
            if (!forceRefresh && diskExists) {
                loadEpgChannelIndex(epgFile)
                val parseError = parseEpgFile(epgFile, wanted)
                if (parseError == null) {
                    applyEpgCatalog(playlist.id, epgFile.lastModified(), fromDisk = true)
                    return@withLock false
                }
            }
            true
        }
        if (!needNetwork) return

        // Network I/O outside the lock — channel catalog / preview stay usable.
        val epgFile = epgCacheFile(playlist.id)
        val diskExists = epgFile.exists() && epgFile.length() > 0L
        try {
            downloadToFile(playlist.epgUrl, epgFile)
            epgMutex.withLock {
                loadEpgChannelIndex(epgFile)
                val parseError = parseEpgFile(epgFile, wanted)
                if (parseError != null) throw IllegalStateException(parseError)
                applyEpgCatalog(playlist.id, epgFile.lastModified(), fromDisk = false)
            }
        } catch (e: Exception) {
            epgMutex.withLock {
                if (epgByTvgId.isNotEmpty()) {
                    _catalog.value = _catalog.value.copy(
                        error = "EPG network failed — keeping cache (${e.message})"
                    )
                    return
                }
                if (diskExists) {
                    loadEpgChannelIndex(epgFile)
                    val fallbackErr = parseEpgFile(epgFile, wanted)
                    if (fallbackErr == null) {
                        applyEpgCatalog(
                            playlist.id,
                            epgFile.lastModified(),
                            fromDisk = true,
                            error = "EPG network failed — using cached file (${e.message})"
                        )
                        return
                    }
                }
                _catalog.value = _catalog.value.copy(
                    error = e.message ?: "EPG load failed"
                )
            }
        }
    }

    private fun buildWantedEpgIds(channels: List<IptvChannel>): Set<String> {
        val fromM3u = channels.mapNotNull { it.tvgId?.trim()?.takeIf { id -> id.isNotEmpty() } }
        return (fromM3u + extraWantedEpgIds).toSet()
    }

    /** Upgrade stale on-disk EPG from the network after the UI is already usable. */
    suspend fun refreshEpgIfStale(playlist: IptvPlaylistConfig): IptvCatalog =
        withContext(Dispatchers.IO) {
            if (playlist.epgUrl.isBlank()) return@withContext _catalog.value
            val epgFile = epgCacheFile(playlist.id)
            val stale = !epgFile.exists() ||
                epgFile.length() == 0L ||
                isStale(epgFile.lastModified(), EPG_TTL_MS)
            if (!stale) return@withContext _catalog.value
            try {
                downloadToFile(playlist.epgUrl, epgFile)
                epgMutex.withLock {
                    val wanted = buildWantedEpgIds(_catalog.value.channels)
                    loadEpgChannelIndex(epgFile)
                    val parseError = parseEpgFile(epgFile, wanted)
                    if (parseError == null) {
                        applyEpgCatalog(playlist.id, epgFile.lastModified(), fromDisk = false)
                    }
                }
            } catch (_: Exception) {
                // Keep whatever EPG we already parsed from disk.
            }
            _catalog.value
        }

    private fun clearEpgMaps() {
        epgByTvgId.clear()
        epgByLower.clear()
    }

    private fun applyEpgCatalog(
        playlistId: String,
        loadedAtMs: Long,
        fromDisk: Boolean,
        error: String? = null
    ) {
        _catalog.value = _catalog.value.copy(
            playlistId = playlistId,
            epgLoadedAtMs = loadedAtMs,
            epgChannelCount = epgByTvgId.size,
            epgFromDisk = fromDisk,
            error = error
        )
    }

    private fun parseEpgFile(epgFile: File, wanted: Set<String>, merge: Boolean = false): String? {
        if (wanted.isEmpty()) return null
        val now = System.currentTimeMillis()
        val windowStart = now - TimeUnit.HOURS.toMillis(12)
        val windowEnd = now + TimeUnit.HOURS.toMillis(36)
        return try {
            FileInputStream(epgFile).use { stream ->
                val parsed = XmltvParser.parse(
                    input = stream,
                    wantedChannelIds = wanted,
                    windowStartMs = windowStart,
                    windowEndMs = windowEnd
                )
                if (!merge) {
                    epgByTvgId.clear()
                    epgByLower.clear()
                }
                parsed.forEach { (id, list) ->
                    epgByTvgId[id] = list
                    epgByLower[id.lowercase()] = list
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: "EPG parse failed"
        }
    }

    private fun loadEpgChannelIndex(epgFile: File) {
        if (!epgFile.exists() || epgFile.length() == 0L) return
        val cached = epgChannelIndexFile(epgFile)
        if (cached.exists() && cached.lastModified() >= epgFile.lastModified()) {
            // XMLTV files can declare the same channel id twice — keep ids unique.
            val fromDisk = readEpgChannelIndexCache(cached).distinctBy { it.id.lowercase() }
            if (fromDisk.isNotEmpty()) {
                epgChannelIndex = fromDisk
                return
            }
        }
        runCatching {
            FileInputStream(epgFile).use { stream ->
                epgChannelIndex = XmltvParser.parseChannelList(stream)
                    .distinctBy { it.id.lowercase() }
            }
            writeEpgChannelIndexCache(cached, epgChannelIndex)
        }
        if (epgChannelIndex.isEmpty() && epgByTvgId.isNotEmpty()) {
            epgChannelIndex = epgByTvgId.keys
                .map { EpgChannelEntry(id = it, name = it) }
                .sortedBy { it.name.lowercase() }
        }
    }

    private fun epgChannelIndexFile(epgFile: File): File =
        File(epgFile.parentFile, epgFile.nameWithoutExtension + "_channels.json")

    private fun readEpgChannelIndexCache(file: File): List<EpgChannelEntry> =
        runCatching {
            val arr = org.json.JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val name = o.optString("name").ifBlank { id }
                    if (id.isNotBlank()) add(EpgChannelEntry(id = id, name = name))
                }
            }
        }.getOrDefault(emptyList())

    private fun writeEpgChannelIndexCache(file: File, entries: List<EpgChannelEntry>) {
        runCatching {
            val arr = org.json.JSONArray()
            entries.forEach { e ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                )
            }
            file.writeText(arr.toString())
        }
    }

    private fun downloadToFile(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PallasVideoPlayer/1.6")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("EPG HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty EPG body")
            tmp.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PallasVideoPlayer/1.6")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Playlist HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IllegalStateException("Empty playlist")
        }
    }

    private fun isStale(lastModifiedMs: Long, ttlMs: Long): Boolean =
        System.currentTimeMillis() - lastModifiedMs > ttlMs

    private fun m3uCacheFile(playlistId: String) = File(cacheDir, "playlist_$playlistId.m3u")
    private fun epgCacheFile(playlistId: String) = File(cacheDir, "epg_$playlistId.xml")

    companion object {
        private val M3U_TTL_MS = TimeUnit.HOURS.toMillis(6)
        private val EPG_TTL_MS = TimeUnit.HOURS.toMillis(6)
    }
}
