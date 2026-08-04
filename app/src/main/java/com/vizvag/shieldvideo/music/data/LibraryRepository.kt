package com.vizvag.shieldvideo.music.data

import com.vizvag.shieldvideo.music.data.local.AlbumDao
import com.vizvag.shieldvideo.music.data.local.AlbumEntity
import com.vizvag.shieldvideo.music.data.local.AlbumWithArtist
import com.vizvag.shieldvideo.music.data.local.ArtistDao
import com.vizvag.shieldvideo.music.data.local.ArtistEntity
import com.vizvag.shieldvideo.music.data.local.LibraryIndexDao
import com.vizvag.shieldvideo.music.data.local.LibraryIndexStateEntity
import com.vizvag.shieldvideo.music.data.local.PlayHistoryDao
import com.vizvag.shieldvideo.music.data.local.PlayHistoryEntity
import com.vizvag.shieldvideo.music.data.local.TrackDao
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import android.content.Context
import android.util.Log
import com.vizvag.shieldvideo.data.nas.NasPaths
import com.vizvag.shieldvideo.data.settings.ConnectionMode
import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.data.smb.SmbRepository
import com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
import com.vizvag.shieldvideo.music.data.settings.MusicSettingsBridge
import com.vizvag.shieldvideo.music.data.settings.NasSettings
import com.vizvag.shieldvideo.music.data.synology.FileEntry
import com.vizvag.shieldvideo.music.data.synology.SynologyApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository constructor(
    private val context: Context,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val libraryIndexDao: LibraryIndexDao,
    private val playHistoryDao: PlayHistoryDao,
    private val synologyApiClient: SynologyApiClient,
    private val musicSettings: MusicSettingsBridge,
    private val settingsRepository: SettingsRepository,
    private val smbRepository: SmbRepository = SmbRepository(),
) {
    fun observeArtists(): Flow<List<ArtistEntity>> = artistDao.observeAll()
    fun observeAlbums(): Flow<List<AlbumWithArtist>> =
        albumDao.observeAllWithArtist().map { collapseSplitAlbums(it) }
    fun observeAlbumsByArtist(artistId: String): Flow<List<AlbumWithArtist>> =
        albumDao.observeByArtist(artistId)

    fun observeAlbumsByYear(year: Int): Flow<List<AlbumWithArtist>> =
        albumDao.observeByYear(year)

    fun observeAlbumsByGenre(genre: String): Flow<List<AlbumWithArtist>> =
        albumDao.observeByGenre(genre)

    fun observeAlbumsByComposer(composer: String): Flow<List<AlbumWithArtist>> =
        albumDao.observeByComposer(composer)

    fun observeAlbumsByAlbumArtist(albumArtist: String): Flow<List<AlbumWithArtist>> =
        albumDao.observeByAlbumArtist(albumArtist)

    fun observeAlbumsByMood(mood: String): Flow<List<AlbumWithArtist>> =
        albumDao.observeByMood(mood)

    fun observeAlbumsByGrouping(grouping: String): Flow<List<AlbumWithArtist>> =
        albumDao.observeByGrouping(grouping)

    fun observeTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()
    suspend fun getAllTracks(): List<TrackEntity> = trackDao.getAll()
    fun observeTracksByAlbum(albumId: String): Flow<List<TrackEntity>> =
        trackDao.observeByAlbum(albumId)

    suspend fun getTracksByAlbum(albumId: String): List<TrackEntity> =
        sortAlbumPlaybackOrder(resolveFullAlbumTracks(albumId))

    /**
     * Full album playback — every track for this release under the album folder
     * (parent of CD1/CD2/…), or the same album/artist credit. Never expands to
     * unrelated albums that only share a title (e.g. every "Greatest Hits").
     */
    private suspend fun resolveFullAlbumTracks(albumId: String): List<TrackEntity> {
        val byId = trackDao.getByAlbum(albumId)
        val album = albumDao.getById(albumId)
        val title = album?.title?.takeIf { it.isNotBlank() }
            ?: byId.firstOrNull()?.albumTitle?.takeIf { it.isNotBlank() }
            ?: return sortAlbumPlaybackOrder(byId)

        val year = album?.year ?: byId.firstOrNull()?.year
        val allWithTitle = trackDao.getByAlbumTitle(title).let { rows ->
            if (year == null) rows
            else {
                val sameYear = rows.filter { it.year == null || it.year == year }
                // VA comps often omit year on some tracks — don't drop them if
                // year-filtered set would leave only the seed albumId's rows.
                if (sameYear.size >= byId.size.coerceAtLeast(1)) sameYear else rows
            }
        }

        val seedFolders = buildSet {
            album?.folderPath
                ?.replace('\\', '/')
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it) }
            byId.forEach { track ->
                track.nasPath.replace('\\', '/')
                    .substringBeforeLast('/')
                    .trimEnd('/')
                    .takeIf { it.isNotBlank() }
                    ?.let { add(it) }
            }
        }
        val albumRoot = inferAlbumRoot(seedFolders)

        val albumArtistFromTable = album?.artistId?.let { artistDao.getById(it)?.name }
        val seedAlbumArtist = dominantCredit(
            byId.mapNotNull { it.albumArtist?.trim()?.takeIf { s -> s.isNotEmpty() } },
        ) ?: albumArtistFromTable?.trim()?.takeIf { it.isNotEmpty() }
        val seedArtist = dominantCredit(
            byId.map { it.artistName.trim() }.filter { it.isNotEmpty() },
        ) ?: albumArtistFromTable?.trim()?.takeIf { it.isNotEmpty() }
        val seedIsVa = isVariousArtistsName(seedAlbumArtist.orEmpty()) ||
            isVariousArtistsName(seedArtist.orEmpty()) ||
            byId.any { isVariousArtistsName(it.albumArtist.orEmpty()) }

        fun sameRelease(track: TrackEntity): Boolean {
            if (seedIsVa) return true
            val aa = track.albumArtist?.trim().orEmpty()
            val an = track.artistName.trim()
            if (seedAlbumArtist != null) {
                if (aa.equals(seedAlbumArtist, ignoreCase = true) ||
                    an.equals(seedAlbumArtist, ignoreCase = true)
                ) {
                    return true
                }
            }
            if (seedArtist != null) {
                if (an.equals(seedArtist, ignoreCase = true) ||
                    aa.equals(seedArtist, ignoreCase = true)
                ) {
                    return true
                }
            }
            val seedArtistId = album?.artistId ?: byId.firstOrNull()?.artistId
            return seedArtistId != null && track.artistId == seedArtistId
        }

        val artistScoped = allWithTitle.filter(::sameRelease)

        // Prefer folder scope (multi-disc). Fall back to same artist/album-artist
        // — never to every library track that merely shares the album title.
        val expanded = when {
            albumRoot != null -> {
                val underRoot = allWithTitle.filter { trackUnderAlbumRoot(it.nasPath, albumRoot) }
                when {
                    underRoot.isNotEmpty() -> underRoot
                    artistScoped.size > byId.size -> artistScoped
                    else -> byId
                }
            }
            artistScoped.size > byId.size -> artistScoped
            else -> byId
        }

        android.util.Log.i(
            "PallasMusic",
            "resolveFullAlbum '$title' id=$albumId root=$albumRoot " +
                "byId=${byId.size} expanded=${expanded.size} va=$seedIsVa",
        )

        val merged = (byId + expanded).distinctBy { it.id }
        return sortAlbumPlaybackOrder(merged.ifEmpty { byId })
    }

    private fun dominantCredit(values: List<String>): String? =
        values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key


    /** Disc (tag or CD/Disc folder) first, then track number. */
    private fun sortAlbumPlaybackOrder(tracks: List<TrackEntity>): List<TrackEntity> =
        tracks.sortedWith(
            compareBy(
                { trackDiscNumber(it) },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.title.lowercase() },
            ),
        )

    /**
     * All indexed tracks under [folderPath], plus a live NAS scan fallback so
     * Play / Add folder still works when the index is empty or paths don't match.
     */
    suspend fun getTracksUnderFolder(folderPath: String): List<TrackEntity> {
        val normalized = folderPath.replace('\\', '/').trimEnd('/')
        // Single audio file path (not a directory)
        if (MetadataResolver.isAudioFile(normalized.substringAfterLast('/'))) {
            return listOf(resolveTrack(normalized))
        }

        val indexed = matchTracksUnderFolder(trackDao.getAll(), normalized)
        if (indexed.isNotEmpty()) {
            // Indexed rows can still be path stubs — re-read ID3 when title looks like a filename.
            return sortAlbumPlaybackOrder(
                indexed.map { track ->
                    if (MetadataResolver.titleLooksLikeFileName(track.title, track.nasPath) ||
                        MetadataResolver.isPlaceholderArtist(track.artistName)
                    ) {
                        resolveTrack(track.nasPath)
                    } else {
                        track
                    }
                },
            )
        }

        var files = runCatching {
            synologyApiClient.searchAudioFiles(normalized)
        }.getOrDefault(emptyList())
        if (files.isEmpty()) {
            files = listAudioRecursive(normalized)
        }
        if (files.isEmpty()) return emptyList()

        // Always resolve via ID3 (index → live tag read → path last).
        return files.map { entry ->
            resolveTrack(entry.path)
        }.sortedWith(
            compareBy(
                { it.albumTitle },
                { it.discNumber ?: 1 },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.title },
            ),
        )
    }

    private suspend fun listAudioRecursive(folderPath: String, depth: Int = 0): List<FileEntry> {
        if (depth > 12) return emptyList()
        val listed = runCatching { synologyApiClient.listFolder(folderPath) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<FileEntry>()
        for (entry in listed.files) {
            when {
                entry.isdir && NasPaths.isIgnoredDirectoryName(entry.name) -> Unit
                entry.isdir -> out += listAudioRecursive(entry.path, depth + 1)
                MetadataResolver.isAudioFile(entry.name) -> out += entry
            }
        }
        return out
    }

    /**
     * Resolve a NAS path to a TrackEntity with real metadata when possible:
     * live ID3 read first → indexed row → path fallback.
     */
    suspend fun resolveTrack(path: String): TrackEntity {
        val normalized = path.replace('\\', '/')
        val indexed = findIndexedByPath(normalized)
        val entry = FileEntry(
            path = normalized,
            name = normalized.substringAfterLast('/'),
            isdir = false,
        )
        // Always prefer a live ID3 read when the file is reachable.
        enrichFromFileTags(entry, indexed)?.let { return it }
        if (indexed != null) return indexed
        return MetadataResolver.trackFromFileEntry(entry)
    }

    suspend fun resolveTrack(entry: FileEntry): TrackEntity = resolveTrack(entry.path)

    private suspend fun findIndexedByPath(path: String): TrackEntity? {
        val normalized = path.replace('\\', '/')
        return trackDao.getByPath(normalized)
            ?: trackDao.getByPath(normalized.trimStart('/'))
            ?: trackDao.getByPath("/${normalized.trimStart('/')}")
    }

    private suspend fun enrichFromFileTags(
        entry: FileEntry,
        existing: TrackEntity?,
    ): TrackEntity? {
        val musicRoot = NasSettings.rootForPath(
            entry.path,
            musicSettings.currentSettings().musicPaths,
        )
        val bytes = runCatching {
            synologyApiClient.downloadBytes(entry.path, maxBytes = 2_097_152)
        }.getOrElse { ByteArray(0) }.let { raw ->
            if (raw.isNotEmpty() && !looksLikeSynologyJsonError(raw) && looksLikeAudioHeader(raw)) {
                raw
            } else {
                readTagBytesViaSmb(entry.path, maxBytes = 2_097_152)
            }
        }
        if (bytes.isEmpty()) return null
        val parsed = runCatching {
            MetadataResolver.parseFromBytes(
                entry = entry,
                musicRoot = musicRoot,
                bytes = bytes,
                lyricsExists = false,
                cacheDir = context.cacheDir,
            )
        }.getOrNull() ?: return null
        val track = if (existing != null) {
            // Keep stable ids from the index when we already had a row
            parsed.track.copy(
                id = existing.id,
                albumId = existing.albumId,
                artistId = existing.artistId,
            )
        } else {
            parsed.track
        }
        runCatching {
            artistDao.insertAll(listOf(parsed.artist.copy(id = track.artistId)))
            albumDao.insertAll(listOf(parsed.album.copy(id = track.albumId, artistId = track.artistId)))
            trackDao.insertAll(listOf(track))
        }
        return track
    }

    /** Download the start of the file and read full ID3 / audio header tags. */
    suspend fun readTagsForPath(nasPath: String): com.vizvag.shieldvideo.music.data.metadata.TrackTagInfo? {
        val bytes = readTagBytes(nasPath, maxBytes = 2_097_152)
        if (bytes.isEmpty()) return null
        val name = nasPath.substringAfterLast('/')
        // Partial reads skew jaudiotagger track length — never trust duration here.
        return MetadataResolver.readTagsFromBytes(bytes, context.cacheDir, name)
            ?.copy(durationMs = 0)
    }

    private suspend fun readTagBytes(nasPath: String, maxBytes: Long): ByteArray {
        for (candidate in pathVariants(nasPath)) {
            val raw = runCatching {
                synologyApiClient.downloadBytes(candidate, maxBytes = maxBytes)
            }.onFailure {
                Log.w(TAG, "FS tag download failed for $candidate: ${it.message}")
            }.getOrNull() ?: continue
            if (raw.isNotEmpty() && !looksLikeSynologyJsonError(raw) && looksLikeAudioHeader(raw)) {
                return raw
            }
            Log.w(TAG, "FS tag download for $candidate looked like error/non-audio (${raw.size} bytes)")
        }
        return readTagBytesViaSmb(nasPath, maxBytes)
    }

    private suspend fun readTagBytesViaSmb(nasPath: String, maxBytes: Long): ByteArray {
        val settings = runCatching { settingsRepository.load() }.getOrNull() ?: return ByteArray(0)
        if (settings.host.isBlank() || settings.password.isBlank()) return ByteArray(0)
        val parsed = NasPaths.parseFolderPath(nasPath) ?: return ByteArray(0)
        val (share, rel) = parsed
        val smbSettings = settings.copy(
            connectionMode = ConnectionMode.SMB3,
            port = ConnectionMode.SMB3.defaultPort,
        )
        val bytes = smbRepository.readBytes(
            smbSettings,
            share,
            rel,
            maxBytes = maxBytes.toInt().coerceAtLeast(1),
        ).getOrElse {
            Log.w(TAG, "SMB tag read failed for $nasPath: ${it.message}")
            return ByteArray(0)
        }
        if (bytes.isEmpty() || looksLikeSynologyJsonError(bytes) || !looksLikeAudioHeader(bytes)) {
            return ByteArray(0)
        }
        return bytes
    }

    private fun looksLikeSynologyJsonError(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0] != '{'.code.toByte()) return false
        val head = String(bytes, 0, minOf(bytes.size, 96), Charsets.UTF_8)
        return head.contains("\"success\"") && head.contains("false")
    }

    private fun looksLikeAudioHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        // ID3v2
        if (bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) {
            return true
        }
        // FLAC
        if (bytes.size >= 4 &&
            bytes[0] == 'f'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() &&
            bytes[3] == 'C'.code.toByte()
        ) {
            return true
        }
        // Ogg
        if (bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() &&
            bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()
        ) {
            return true
        }
        // MPEG frame sync
        if (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return true
        // MP4 / M4A ftyp
        if (bytes.size >= 8) {
            val ftyp = String(bytes, 4, 4, Charsets.US_ASCII)
            if (ftyp == "ftyp") return true
        }
        return false
    }

    private fun pathVariants(path: String): List<String> {
        val n = path.replace('\\', '/').trim()
        if (n.isEmpty()) return emptyList()
        return linkedSetOf(
            n,
            n.trimStart('/'),
            "/${n.trimStart('/')}",
        ).toList()
    }

    /**
     * Apply live tags onto a TrackEntity for display / queue / now-playing.
     */
    fun mergeTagsIntoTrack(
        track: TrackEntity,
        rich: com.vizvag.shieldvideo.music.data.metadata.TrackTagInfo,
    ): TrackEntity {
        val mr = com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
        val resolvedArtist = mr.resolveDisplayArtist(
            artist = rich.artist,
            albumArtist = rich.albumArtist ?: track.albumArtist,
            nasPath = track.nasPath,
            albumTitle = rich.album ?: track.albumTitle,
        )
        return track.copy(
            title = rich.title?.takeIf { it.isNotBlank() } ?: track.title,
            artistName = resolvedArtist
                ?: track.artistName.takeUnless { mr.isPlaceholderArtist(it) }
                ?: track.artistName,
            albumTitle = rich.album?.takeIf { it.isNotBlank() } ?: track.albumTitle,
            albumArtist = rich.albumArtist?.takeIf {
                it.isNotBlank() && !mr.isPlaceholderArtist(it)
            } ?: track.albumArtist,
            genre = rich.genre?.takeIf { it.isNotBlank() } ?: track.genre,
            year = rich.year ?: track.year,
            trackNumber = rich.trackNumber ?: track.trackNumber,
            discNumber = rich.discNumber ?: track.discNumber,
            composer = rich.composer?.takeIf { it.isNotBlank() } ?: track.composer,
            durationMs = track.durationMs,
            bitrateKbps = rich.bitrateKbps ?: track.bitrateKbps,
            sampleRateHz = rich.sampleRateHz ?: track.sampleRateHz,
            channels = rich.channels ?: track.channels,
            codec = rich.codec?.takeIf { it.isNotBlank() } ?: track.codec,
        )
    }

    private fun matchTracksUnderFolder(tracks: List<TrackEntity>, folderPath: String): List<TrackEntity> {
        val variants = linkedSetOf(
            folderPath,
            folderPath.trimStart('/'),
            "/${folderPath.trimStart('/')}",
        ).map { it.trimEnd('/') }
        return tracks.filter { track ->
            val p = track.nasPath.replace('\\', '/').trimEnd('/')
            variants.any { root -> p == root || p.startsWith("$root/") }
        }.sortedWith(
            compareBy(
                { it.albumTitle },
                { it.discNumber ?: 1 },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.title },
            ),
        )
    }

    fun trackFromFileEntry(entry: FileEntry): TrackEntity =
        MetadataResolver.trackFromFileEntry(entry)

    suspend fun upsertTrack(track: TrackEntity) {
        trackDao.insertAll(listOf(track))
    }

    /** Genre folder when path is music/Genre/Artist/Album; null for Artist/Album. */
    fun genreLabel(folderPath: String?, musicRoot: String): String? {
        if (folderPath.isNullOrBlank()) return null
        val parts = MetadataResolver.relativePathParts(folderPath, musicRoot)
        return if (parts.size >= 3) parts.first() else null
    }

    fun observeIndexState(): Flow<LibraryIndexStateEntity?> = libraryIndexDao.observeState()

    suspend fun getIndexState(): LibraryIndexStateEntity? = libraryIndexDao.getState()

    /** Clears a leftover isIndexing flag after process death. */
    suspend fun clearStaleIndexingFlag() {
        val state = libraryIndexDao.getState() ?: return
        if (state.isIndexing) {
            libraryIndexDao.upsert(
                state.copy(
                    isIndexing = false,
                    statusMessage = state.statusMessage
                        .takeIf { it.isNotBlank() && !it.startsWith("Searching") && !it.startsWith("Found") && !it.startsWith("Indexing") }
                        ?: if (state.trackCount > 0) "Indexed ${state.trackCount} tracks" else "",
                ),
            )
        }
    }
    fun observeRecent(): Flow<List<TrackEntity>> = playHistoryDao.observeRecent()

    suspend fun getArtist(id: String): ArtistEntity? = artistDao.getById(id)
    suspend fun getAlbum(id: String): AlbumEntity? = albumDao.getById(id)
    suspend fun getTrack(id: String): TrackEntity? = trackDao.getById(id)
    suspend fun getTrackByPath(path: String): TrackEntity? = trackDao.getByPath(path)
    suspend fun getTracksByArtist(artistId: String): List<TrackEntity> =
        trackDao.getByArtist(artistId)

    /**
     * All tracks credited to this artist — by library artistId **and** by track
     * artist / album-artist name. Room artistId alone under-counts when albums
     * were indexed under a different id (common for album-artist credits).
     */
    suspend fun getTracksForArtistBrowse(artist: ArtistEntity): List<TrackEntity> {
        val byName = getTracksByArtistCredit(artist.name)
        if (artist.id.startsWith("performer:") || artist.id.startsWith("albumartist:")) {
            return byName
        }
        val byId = trackDao.getByArtist(artist.id)
        if (byId.isEmpty()) return byName
        if (byName.isEmpty()) return byId
        val merged = LinkedHashMap<String, TrackEntity>(byId.size + byName.size)
        for (track in byId + byName) {
            val key = track.nasPath.replace('\\', '/').lowercase().ifBlank { track.id }
            merged.putIfAbsent(key, track)
        }
        return merged.values.sortedWith(
            compareBy(
                { it.albumTitle.lowercase() },
                { it.discNumber ?: 1 },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.title.lowercase() },
            ),
        )
    }

    /** Tracks where artist or album artist matches [name]. */
    suspend fun getTracksByPerformerName(name: String): List<TrackEntity> =
        getTracksByArtistCredit(name)

    private suspend fun getTracksByArtistCredit(name: String): List<TrackEntity> {
        val wanted = name.trim()
        if (wanted.isEmpty()) return emptyList()
        return trackDao.searchByArtistName(wanted).filter { track ->
            val a = track.artistName.trim()
            val aa = track.albumArtist?.trim().orEmpty()
            a.equals(wanted, ignoreCase = true) ||
                aa.equals(wanted, ignoreCase = true) ||
                a.startsWith("$wanted ", ignoreCase = true) ||
                a.startsWith("$wanted &", ignoreCase = true) ||
                a.startsWith("$wanted/", ignoreCase = true) ||
                aa.startsWith("$wanted ", ignoreCase = true) ||
                aa.startsWith("$wanted &", ignoreCase = true)
        }.sortedWith(
            compareBy(
                { it.albumTitle.lowercase() },
                { it.discNumber ?: 1 },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.title.lowercase() },
            ),
        )
    }

    fun observeTracksByArtist(artistId: String): Flow<List<TrackEntity>> =
        trackDao.observeByArtist(artistId)

    suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val q = query.trim()
        val artists = artistDao.search(q).toMutableList()
        val tracks = trackDao.search(q)
        val seen = artists.map { it.name.trim().lowercase() }.toMutableSet()
        val artistMatchedTracks = trackDao.searchByArtistName(q)

        // Track performers (compilations) — album-artist table may not list them.
        for ((key, group) in artistMatchedTracks.groupBy { it.artistName.trim().lowercase() }) {
            if (key.isEmpty() || key in seen) continue
            val sample = group.first().artistName.trim()
            if (!isSearchableArtistName(sample) || !sample.contains(q, ignoreCase = true)) continue
            seen.add(key)
            artists += ArtistEntity(
                id = "performer:$key",
                name = sample,
                sortKey = key,
            )
        }
        // Album-artist-only names (e.g. Jaydee credited as album artist).
        for ((key, group) in artistMatchedTracks
            .mapNotNull { t ->
                t.albumArtist?.trim()?.takeIf { it.isNotBlank() }?.let { it to t }
            }
            .groupBy({ it.first.lowercase() }, { it.second })
        ) {
            if (key.isEmpty() || key in seen) continue
            val sample = group.first().albumArtist!!.trim()
            if (!isSearchableArtistName(sample) || !sample.contains(q, ignoreCase = true)) continue
            seen.add(key)
            artists += ArtistEntity(
                id = "albumartist:$key",
                name = sample,
                sortKey = key,
            )
        }

        // Albums: title/artist table hits + albums from matching track credits.
        // Collapse VA comps indexed as one albumId per track artist (often also
        // one folder per artist) — same title (+ year) = one physical release.
        val albumHits = LinkedHashMap<String, AlbumWithArtist>()
        val albumIdsByKey = HashMap<String, MutableSet<String>>()
        fun putAlbum(album: AlbumWithArtist) {
            val key = physicalAlbumKey(
                album.title,
                album.year,
                album.folderPath,
                album.albumId,
                album.artistName,
            )
            val seenIds = albumIdsByKey.getOrPut(key) { mutableSetOf() }
            val prior = albumHits[key]
            if (prior == null) {
                albumHits[key] = album
                seenIds += album.albumId
                return
            }
            val newId = album.albumId.isNotBlank() && album.albumId !in seenIds
            if (newId) seenIds += album.albumId
            val mergedCount = if (newId) {
                prior.trackCount + album.trackCount
            } else {
                maxOf(prior.trackCount, album.trackCount)
            }
            val artistName = when {
                prior.artistName.equals(album.artistName, ignoreCase = true) -> prior.artistName
                isVariousArtistsName(prior.artistName) -> prior.artistName
                isVariousArtistsName(album.artistName) -> album.artistName
                else -> "Various Artists"
            }
            albumHits[key] = prior.copy(
                trackCount = mergedCount,
                artistName = artistName,
                year = prior.year ?: album.year,
                folderPath = prior.folderPath ?: album.folderPath,
                coverPath = prior.coverPath ?: album.coverPath,
            )
        }
        albumDao.search(q).forEach(::putAlbum)
        // Artist-name queries: albums that contain tracks by that artist.
        if (artistMatchedTracks.isNotEmpty()) {
            val albumIds = artistMatchedTracks.map { it.albumId }.distinct()
            for (id in albumIds) {
                val sample = artistMatchedTracks.first { it.albumId == id }
                val folder = sample.nasPath.replace('\\', '/').substringBeforeLast('/')
                putAlbum(
                    AlbumWithArtist(
                        albumId = id,
                        title = sample.albumTitle,
                        artistId = sample.artistId,
                        artistName = sample.albumArtist?.takeIf { it.isNotBlank() }
                            ?: sample.artistName,
                        year = sample.year,
                        genre = sample.genre,
                        coverPath = null,
                        trackCount = artistMatchedTracks.count { it.albumId == id },
                        folderPath = folder,
                    ),
                )
            }
        }
        val albums = albumHits.values
            .sortedWith(compareBy({ it.title.lowercase() }, { it.artistName.lowercase() }))

        // Room albumCount is only albums with that artistId — often wrong for
        // album-artist / performer credits. Recount from matching tracks.
        val recounted = artists.map { artist ->
            val (albumN, trackN) = artistAlbumTrackCounts(artist.name, artistMatchedTracks, albums)
            artist.copy(
                albumCount = albumN.takeIf { it > 0 } ?: artist.albumCount,
                trackCount = trackN.takeIf { it > 0 } ?: artist.trackCount,
            )
        }.sortedBy { it.sortKey }

        val albumArtists = if (albums.isEmpty()) {
            emptyMap()
        } else {
            trackDao.albumArtistCounts(albums.map { it.albumId })
                .groupBy { it.albumId }
                .mapValues { (_, votes) ->
                    votes.maxBy { it.trackCount }.albumArtist
                }
        }
        return SearchResults(recounted, albums, tracks, albumArtists)
    }

    /**
     * Same physical release across split albumIds.
     * VA comps are often one albumId (and folder) per track artist — key by
     * title + year when credited as Various Artists. Otherwise include artist
     * so "Greatest Hits" for Michael Jackson stays separate from Queen.
     */
    private fun physicalAlbumKey(
        title: String,
        year: Int?,
        folderPath: String?,
        albumId: String,
        artistName: String = "",
    ): String {
        val t = title.trim().lowercase()
        if (t.isBlank()) return "id:${albumId.lowercase()}"
        val y = year?.takeIf { it > 0 }?.toString().orEmpty()
        val root = folderPath
            ?.replace('\\', '/')
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { albumRootFolder(it).lowercase() }
            ?.takeIf { it.isNotBlank() }
        if (root != null) {
            return buildString {
                append("f:$root|t:$t")
                if (y.isNotEmpty()) append("|y:$y")
            }
        }
        val artist = artistName.trim()
        if (artist.isNotEmpty() && !isVariousArtistsName(artist)) {
            return buildString {
                append("a:${artist.lowercase()}|t:$t")
                if (y.isNotEmpty()) append("|y:$y")
            }
        }
        return buildString {
            append("t:$t")
            if (y.isNotEmpty()) append("|y:$y")
        }
    }

    private fun isVariousArtistsName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n in setOf("various artists", "various artist", "various", "va", "v.a.", "v.a") ||
            n.startsWith("various artist")
    }

    /** Merge VA comps that were indexed as one albumId per track artist. */
    private fun collapseSplitAlbums(albums: List<AlbumWithArtist>): List<AlbumWithArtist> {
        if (albums.size <= 1) return albums
        return albums
            .groupBy { album ->
                physicalAlbumKey(
                    album.title,
                    album.year,
                    album.folderPath,
                    album.albumId,
                    album.artistName,
                )
            }
            .map { (_, group) ->
                val best = group.maxBy { it.trackCount }
                val artists = group.map { it.artistName.trim() }.filter { it.isNotEmpty() }.distinct()
                best.copy(
                    trackCount = group.sumOf { it.trackCount.coerceAtLeast(1) }
                        .coerceAtLeast(best.trackCount),
                    artistName = when {
                        artists.size <= 1 -> best.artistName
                        artists.any { isVariousArtistsName(it) } ->
                            artists.first { isVariousArtistsName(it) }
                        else -> "Various Artists"
                    },
                    year = group.mapNotNull { it.year }.maxOrNull() ?: best.year,
                )
            }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.artistName.lowercase() }))
    }

    /**
     * Distinct physical albums + tracks for an artist name (track artist or album artist).
     */
    private fun artistAlbumTrackCounts(
        artistName: String,
        matchedTracks: List<TrackEntity>,
        matchedAlbums: List<AlbumWithArtist>,
    ): Pair<Int, Int> {
        val wanted = artistName.trim()
        if (wanted.isEmpty()) return 0 to 0

        fun trackMatches(t: TrackEntity): Boolean {
            val a = t.artistName.trim()
            val aa = t.albumArtist?.trim().orEmpty()
            return a.equals(wanted, ignoreCase = true) ||
                aa.equals(wanted, ignoreCase = true)
        }

        val group = matchedTracks.filter(::trackMatches)
        val fromTracks = group.map {
            val folder = it.nasPath.replace('\\', '/').substringBeforeLast('/').trimEnd('/')
            albumRootFolder(folder).lowercase() to it.albumTitle.trim().lowercase()
        }.filter { (folder, title) -> folder.isNotBlank() && title.isNotBlank() }
            .toMutableSet()

        matchedAlbums.forEach { album ->
            if (album.artistName.equals(wanted, ignoreCase = true)) {
                val folder = album.folderPath
                    ?.replace('\\', '/')
                    ?.trimEnd('/')
                    ?.let { albumRootFolder(it) }
                    ?.lowercase()
                    .orEmpty()
                val title = album.title.trim().lowercase()
                if (title.isNotBlank()) {
                    fromTracks += (folder.ifBlank { album.albumId.lowercase() } to title)
                }
            }
        }

        val trackCount = group.size.takeIf { it > 0 }
            ?: matchedAlbums
                .filter { it.artistName.equals(wanted, ignoreCase = true) }
                .sumOf { it.trackCount.coerceAtLeast(0) }
        return fromTracks.size to trackCount
    }

    private fun isSearchableArtistName(name: String): Boolean {
        if (MetadataResolver.isPlaceholderArtist(name)) return false
        val n = name.trim().lowercase()
        return n !in setOf("various artists", "various artist", "various", "va", "v.a.", "v.a") &&
            !n.startsWith("various artist")
    }

    suspend fun recordPlay(trackId: String) {
        val existing = playHistoryDao.get(trackId)
        playHistoryDao.upsert(
            PlayHistoryEntity(
                trackId = trackId,
                playedAt = System.currentTimeMillis(),
                playCount = (existing?.playCount ?: 0) + 1,
            ),
        )
    }

    suspend fun indexLibrary(
        forceFull: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ) {
        val settings = musicSettings.currentSettings()
        val prior = libraryIndexDao.getState()
        val priorCount = prior?.trackCount ?: 0
        val priorBuiltAt = prior?.lastIndexedAt ?: 0L
        libraryIndexDao.upsert(
            LibraryIndexStateEntity(
                lastIndexedAt = priorBuiltAt,
                trackCount = priorCount,
                isIndexing = true,
                progress = 0f,
                statusMessage = "Reading NAS music index…",
            ),
        )
        try {
            val musicRoots = NasSettings.normalizePaths(settings.musicPaths)
            // Prefer Synology Audio Station's media index (tags already on the NAS).
            val nasSongs = runCatching {
                synologyApiClient.listAudioStationSongs(musicRoots) { fetched, total ->
                    val progress = if (total > 0) {
                        (fetched.toFloat() / total).coerceIn(0f, 1f) * 0.85f
                    } else {
                        0.2f
                    }
                    onProgress(progress, "NAS index: $fetched${if (total > 0) "/$total" else ""}…")
                    libraryIndexDao.upsert(
                        LibraryIndexStateEntity(
                            lastIndexedAt = priorBuiltAt,
                            trackCount = priorCount.coerceAtLeast(fetched),
                            isIndexing = true,
                            progress = progress,
                            statusMessage = "NAS index: $fetched${if (total > 0) "/$total" else ""} songs…",
                        ),
                    )
                }
            }.getOrNull()

            if (!nasSongs.isNullOrEmpty()) {
                indexFromAudioStation(nasSongs, musicRoots, priorBuiltAt, onProgress)
                return
            }

            // Fallback: File Station search + local tag parse (slower).
            indexFromFileStation(musicRoots, forceFull, prior, priorCount, priorBuiltAt, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            libraryIndexDao.upsert(
                LibraryIndexStateEntity(
                    lastIndexedAt = priorBuiltAt,
                    trackCount = trackDao.getAll().size.coerceAtLeast(priorCount),
                    isIndexing = false,
                    progress = 0f,
                    statusMessage = prior?.statusMessage
                        ?.takeIf { !it.startsWith("Error:", ignoreCase = true) && it.isNotBlank() }
                        ?: if (priorCount > 0) "Indexed $priorCount tracks" else "",
                ),
            )
            throw e
        } catch (e: Exception) {
            libraryIndexDao.upsert(
                LibraryIndexStateEntity(
                    lastIndexedAt = priorBuiltAt,
                    trackCount = trackDao.getAll().size.coerceAtLeast(priorCount),
                    isIndexing = false,
                    progress = 0f,
                    statusMessage = "Error: ${e.message}",
                ),
            )
            throw e
        }
    }

    private suspend fun indexFromAudioStation(
        songs: List<com.vizvag.shieldvideo.music.data.synology.AudioStationSong>,
        musicRoots: List<String>,
        priorBuiltAt: Long,
        onProgress: (Float, String) -> Unit,
    ) {
        onProgress(0.88f, "Writing ${songs.size} tracks to disk…")
        libraryIndexDao.upsert(
            LibraryIndexStateEntity(
                lastIndexedAt = priorBuiltAt,
                trackCount = songs.size,
                isIndexing = true,
                progress = 0.88f,
                statusMessage = "Writing ${songs.size} tracks to disk…",
            ),
        )
        val parsedTracks = songs.map { song ->
            val root = NasSettings.rootForPath(song.path, musicRoots)
            MetadataResolver.parseFromAudioStation(song, root)
        }
        val artists = buildArtists(parsedTracks)
        val albums = buildAlbums(parsedTracks)
        replaceLibrary(artists, albums, parsedTracks.map { it.track })
        val doneMessage = "Synced ${parsedTracks.size} tracks from NAS index"
        libraryIndexDao.upsert(
            LibraryIndexStateEntity(
                lastIndexedAt = System.currentTimeMillis(),
                trackCount = parsedTracks.size,
                isIndexing = false,
                progress = 1f,
                statusMessage = doneMessage,
            ),
        )
        onProgress(1f, doneMessage)
    }

    private suspend fun indexFromFileStation(
        musicPaths: List<String>,
        forceFull: Boolean,
        prior: LibraryIndexStateEntity?,
        priorCount: Int,
        priorBuiltAt: Long,
        onProgress: (Float, String) -> Unit,
    ) {
        libraryIndexDao.upsert(
            LibraryIndexStateEntity(
                lastIndexedAt = priorBuiltAt,
                trackCount = priorCount,
                isIndexing = true,
                progress = 0.05f,
                statusMessage = "Audio Station unavailable — scanning files…",
            ),
        )
        val files = mutableListOf<FileEntry>()
        musicPaths.forEachIndexed { rootIndex, musicPath ->
            val found = synologyApiClient.searchAudioFiles(musicPath) { total, finished ->
                val base = rootIndex.toFloat() / musicPaths.size.coerceAtLeast(1)
                val span = 1f / musicPaths.size.coerceAtLeast(1)
                val progress = 0.05f + (base + if (finished) span * 0.25f else span * 0.12f) * 0.25f
                onProgress(progress, "Found $total in ${musicPath.substringAfterLast('/')}…")
                libraryIndexDao.upsert(
                    LibraryIndexStateEntity(
                        lastIndexedAt = priorBuiltAt,
                        trackCount = priorCount,
                        isIndexing = true,
                        progress = progress,
                        statusMessage = "Found $total audio files in $musicPath…",
                    ),
                )
            }
            files += found
        }
        val distinctFiles = files.distinctBy { it.path.replace('\\', '/').lowercase() }
        if (distinctFiles.isEmpty()) {
            val keepMessage = if (priorCount > 0) {
                "No audio files found — kept $priorCount tracks on disk"
            } else {
                "No audio files found"
            }
            libraryIndexDao.upsert(
                LibraryIndexStateEntity(
                    lastIndexedAt = priorBuiltAt,
                    trackCount = priorCount,
                    isIndexing = false,
                    progress = 1f,
                    statusMessage = keepMessage,
                ),
            )
            onProgress(1f, keepMessage)
            return
        }

        val existingByPath = if (forceFull) {
            emptyMap()
        } else {
            trackDao.getAll().associateBy { it.nasPath.replace('\\', '/') }
        }

        val parsedTracks = mutableListOf<com.vizvag.shieldvideo.music.data.metadata.ParsedTrack>()
        var reused = 0
        var scanned = 0
        var sinceCheckpoint = 0

        distinctFiles.forEachIndexed { index, entry ->
            val pathKey = entry.path.replace('\\', '/')
            val existing = existingByPath[pathKey]
                ?: existingByPath[pathKey.trimStart('/')]
                ?: existingByPath["/${pathKey.trimStart('/')}"]
            val musicRoot = NasSettings.rootForPath(entry.path, musicPaths)

            val progress = 0.3f + (index.toFloat() / distinctFiles.size.coerceAtLeast(1)) * 0.65f
            val parsed = if (existing != null && MetadataResolver.isUnchanged(existing, entry)) {
                reused++
                onProgress(progress, "Reusing $reused · scanning ${index + 1}/${distinctFiles.size}…")
                MetadataResolver.parsedFromExisting(existing)
            } else {
                scanned++
                onProgress(progress, "Indexing ${entry.name} ($scanned new)…")
                libraryIndexDao.upsert(
                    LibraryIndexStateEntity(
                        lastIndexedAt = priorBuiltAt,
                        trackCount = priorCount.coerceAtLeast(parsedTracks.size + reused),
                        isIndexing = true,
                        progress = progress,
                        statusMessage = "Indexing ${entry.name} ($scanned new, $reused reused)…",
                    ),
                )
                val bytes = runCatching {
                    synologyApiClient.downloadBytes(entry.path, maxBytes = 524_288)
                }.getOrElse { ByteArray(0) }
                val lyricsPath = MetadataResolver.lyricsPathFor(entry.path)
                val lyricsExists = runCatching {
                    synologyApiClient.fileExists(lyricsPath)
                }.getOrDefault(false)
                MetadataResolver.parseFromBytes(
                    entry = entry,
                    musicRoot = musicRoot,
                    bytes = bytes,
                    lyricsExists = lyricsExists,
                    cacheDir = context.cacheDir,
                )
            }
            parsedTracks.add(parsed)
            sinceCheckpoint++

            if (sinceCheckpoint >= CHECKPOINT_EVERY || index == distinctFiles.lastIndex) {
                checkpointTracks(parsedTracks.takeLast(sinceCheckpoint))
                sinceCheckpoint = 0
                libraryIndexDao.upsert(
                    LibraryIndexStateEntity(
                        lastIndexedAt = priorBuiltAt,
                        trackCount = parsedTracks.size,
                        isIndexing = true,
                        progress = progress,
                        statusMessage = "Saved ${parsedTracks.size}/${distinctFiles.size} " +
                            "($reused reused, $scanned new)…",
                    ),
                )
            }
        }

        val artists = buildArtists(parsedTracks)
        val albums = buildAlbums(parsedTracks)
        replaceLibrary(artists, albums, parsedTracks.map { it.track })

        val doneMessage = if (scanned == 0 && reused > 0) {
            "Up to date · $reused tracks on disk"
        } else {
            "Indexed ${parsedTracks.size} tracks ($reused reused, $scanned new)"
        }
        libraryIndexDao.upsert(
            LibraryIndexStateEntity(
                lastIndexedAt = System.currentTimeMillis(),
                trackCount = parsedTracks.size,
                isIndexing = false,
                progress = 1f,
                statusMessage = doneMessage,
            ),
        )
        onProgress(1f, "Done")
    }

    private suspend fun checkpointTracks(
        batch: List<com.vizvag.shieldvideo.music.data.metadata.ParsedTrack>,
    ) {
        if (batch.isEmpty()) return
        val artists = batch.map { it.artist }.distinctBy { it.id }
        val albums = batch.map { it.album }.distinctBy { it.id }
        artistDao.insertAll(artists)
        albumDao.insertAll(albums)
        trackDao.insertAll(batch.map { it.track })
    }

    private fun buildArtists(
        parsedTracks: List<com.vizvag.shieldvideo.music.data.metadata.ParsedTrack>,
    ): List<ArtistEntity> =
        parsedTracks.map { it.artist }.distinctBy { it.id }.map { artist ->
            val artistTracks = parsedTracks.filter { it.artist.id == artist.id }
            val albumIds = artistTracks.map { it.album.id }.distinct()
            artist.copy(
                albumCount = albumIds.size,
                trackCount = artistTracks.size,
            )
        }

    private fun buildAlbums(
        parsedTracks: List<com.vizvag.shieldvideo.music.data.metadata.ParsedTrack>,
    ): List<AlbumEntity> =
        parsedTracks.groupBy { it.album.id }.map { (_, group) ->
            val base = group.first().album
            base.copy(
                trackCount = group.size,
                year = group.mapNotNull { it.album.year }.maxOrNull() ?: base.year,
                genre = group.mapNotNull { it.album.genre?.takeIf { g -> g.isNotBlank() } }
                    .firstOrNull() ?: base.genre,
            )
        }

    private suspend fun replaceLibrary(
        artists: List<ArtistEntity>,
        albums: List<AlbumEntity>,
        tracks: List<TrackEntity>,
    ) {
        // Delete tracks first so CASCADE from artist/album wipe cannot race with inserts.
        trackDao.deleteAll()
        albumDao.deleteAll()
        artistDao.deleteAll()
        artistDao.insertAll(artists)
        albumDao.insertAll(albums)
        trackDao.insertAll(tracks)
    }

    companion object {
        private const val TAG = "PallasMusic"
        private const val CHECKPOINT_EVERY = 40
    }

    suspend fun listNasFolder(path: String) = synologyApiClient.listFolder(path)
}

/** Folder names like CD1, Disc 2, Disk 3 — lift to parent as album root. */
private val DiscFolderName =
    Regex("""(?i)^(cd|disc|disk|dvd)\s*[-_.]?\s*\d+\b.*""")

private val DiscFolderNumber =
    Regex("""(?i)(?:cd|disc|disk|dvd)\s*[-_.]?\s*(\d+)\b""")

internal fun isDiscFolderName(name: String): Boolean =
    DiscFolderName.matches(name.trim())

internal fun albumRootFolder(folderPath: String): String {
    val folder = folderPath.replace('\\', '/').trimEnd('/')
    if (folder.isBlank()) return folder
    val name = folder.substringAfterLast('/')
    return if (isDiscFolderName(name)) {
        folder.substringBeforeLast('/').trimEnd('/').ifBlank { folder }
    } else {
        folder
    }
}

/** ID3 disc tag, else CD1 / Disc 2 folder name, else 1. */
internal fun trackDiscNumber(track: TrackEntity): Int {
    track.discNumber?.takeIf { it > 0 }?.let { return it }
    val folderName = track.nasPath.replace('\\', '/')
        .substringBeforeLast('/')
        .trimEnd('/')
        .substringAfterLast('/')
    return DiscFolderNumber.find(folderName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
}

private fun inferAlbumRoot(folders: Set<String>): String? {
    val roots = folders
        .map { albumRootFolder(it) }
        .filter { it.isNotBlank() }
        .distinct()
    if (roots.isEmpty()) return null
    if (roots.size == 1) return roots.first()
    return longestCommonDirPrefix(roots) ?: roots.minByOrNull { it.length }
}

private fun trackUnderAlbumRoot(nasPath: String, root: String): Boolean {
    val folder = nasPath.replace('\\', '/').substringBeforeLast('/').trimEnd('/')
    val r = root.trimEnd('/')
    return folder == r || folder.startsWith("$r/")
}

private fun longestCommonDirPrefix(paths: List<String>): String? {
    if (paths.isEmpty()) return null
    val split = paths.map { it.trimEnd('/').split('/').filter { p -> p.isNotEmpty() } }
    val minLen = split.minOf { it.size }
    if (minLen == 0) return null
    val common = mutableListOf<String>()
    for (i in 0 until minLen) {
        val part = split[0][i]
        if (split.all { it[i].equals(part, ignoreCase = true) }) {
            common += part
        } else {
            break
        }
    }
    if (common.isEmpty()) return null
    val prefix = "/" + common.joinToString("/")
    return if (paths.first().startsWith("/")) prefix else common.joinToString("/")
}

data class SearchResults(
    val artists: List<ArtistEntity> = emptyList(),
    val albums: List<AlbumWithArtist> = emptyList(),
    val tracks: List<TrackEntity> = emptyList(),
    /** albumId → dominant album-artist from track tags */
    val albumArtists: Map<String, String> = emptyMap(),
)
