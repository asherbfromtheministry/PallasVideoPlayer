package com.vizvag.shieldvideo.music.data.metadata

import com.vizvag.shieldvideo.music.data.local.AlbumEntity
import com.vizvag.shieldvideo.music.data.local.ArtistEntity
import com.vizvag.shieldvideo.music.data.local.TrackEntity
import com.vizvag.shieldvideo.music.data.synology.FileEntry
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.security.MessageDigest

data class ParsedTrack(
    val track: TrackEntity,
    val artist: ArtistEntity,
    val album: AlbumEntity,
)

/** Full tag set shown on the now-playing player (indexed + live-read). */
data class TrackTagInfo(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val composer: String? = null,
    val lyricist: String? = null,
    val conductor: String? = null,
    val publisher: String? = null,
    val comment: String? = null,
    val grouping: String? = null,
    val originalArtist: String? = null,
    val remixer: String? = null,
    val bpm: Int? = null,
    val isrc: String? = null,
    val encoder: String? = null,
    val mood: String? = null,
    val media: String? = null,
    val language: String? = null,
    val copyright: String? = null,
    val url: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val codec: String? = null,
    val bitsPerSample: Int? = null,
    val durationMs: Long = 0,
    val artistId: String? = null,
    val albumId: String? = null,
) {
    companion object {
        fun fromTrack(track: TrackEntity): TrackTagInfo = TrackTagInfo(
            title = track.title,
            artist = track.artistName,
            albumArtist = track.albumArtist,
            album = track.albumTitle,
            genre = track.genre,
            year = track.year,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            composer = track.composer,
            lyricist = track.lyricist,
            conductor = track.conductor,
            publisher = track.publisher,
            comment = track.comment,
            grouping = track.grouping,
            originalArtist = track.originalArtist,
            remixer = track.remixer,
            bpm = track.bpm,
            isrc = track.isrc,
            encoder = track.encoder,
            mood = track.mood,
            media = track.media,
            language = track.language,
            copyright = track.copyright,
            bitrateKbps = track.bitrateKbps,
            sampleRateHz = track.sampleRateHz,
            channels = track.channels,
            codec = track.codec,
            durationMs = track.durationMs,
            artistId = track.artistId,
            albumId = track.albumId,
        )
    }

    fun mergePreferringRich(rich: TrackTagInfo): TrackTagInfo = TrackTagInfo(
        title = rich.title?.takeIf { it.isNotBlank() } ?: title,
        artist = rich.artist?.takeIf {
            it.isNotBlank() && !MetadataResolver.isPlaceholderArtist(it)
        } ?: artist?.takeUnless { MetadataResolver.isPlaceholderArtist(it) } ?: rich.artist,
        albumArtist = rich.albumArtist?.takeIf {
            it.isNotBlank() && !MetadataResolver.isPlaceholderArtist(it)
        } ?: albumArtist,
        album = rich.album?.takeIf { it.isNotBlank() } ?: album,
        genre = rich.genre?.takeIf { it.isNotBlank() } ?: genre,
        year = rich.year ?: year,
        trackNumber = rich.trackNumber ?: trackNumber,
        discNumber = rich.discNumber ?: discNumber,
        composer = rich.composer?.takeIf { it.isNotBlank() } ?: composer,
        lyricist = rich.lyricist?.takeIf { it.isNotBlank() } ?: lyricist,
        conductor = rich.conductor?.takeIf { it.isNotBlank() } ?: conductor,
        publisher = rich.publisher?.takeIf { it.isNotBlank() } ?: publisher,
        comment = rich.comment?.takeIf { it.isNotBlank() } ?: comment,
        grouping = rich.grouping?.takeIf { it.isNotBlank() } ?: grouping,
        originalArtist = rich.originalArtist?.takeIf { it.isNotBlank() } ?: originalArtist,
        remixer = rich.remixer?.takeIf { it.isNotBlank() } ?: remixer,
        bpm = rich.bpm ?: bpm,
        isrc = rich.isrc?.takeIf { it.isNotBlank() } ?: isrc,
        encoder = rich.encoder?.takeIf { it.isNotBlank() } ?: encoder,
        mood = rich.mood?.takeIf { it.isNotBlank() } ?: mood,
        media = rich.media?.takeIf { it.isNotBlank() } ?: media,
        language = rich.language?.takeIf { it.isNotBlank() } ?: language,
        copyright = rich.copyright?.takeIf { it.isNotBlank() } ?: copyright,
        url = rich.url?.takeIf { it.isNotBlank() } ?: url,
        bitrateKbps = rich.bitrateKbps ?: bitrateKbps,
        sampleRateHz = rich.sampleRateHz ?: sampleRateHz,
        channels = rich.channels ?: channels,
        codec = rich.codec?.takeIf { it.isNotBlank() } ?: codec,
        bitsPerSample = rich.bitsPerSample ?: bitsPerSample,
        durationMs = rich.durationMs.takeIf { it > 0 } ?: durationMs,
        artistId = artistId ?: rich.artistId,
        albumId = albumId ?: rich.albumId,
    )
}

object MetadataResolver {
    private val audioExtensions = setOf("mp3", "flac", "m4a", "ogg", "aac", "wav", "wma")

    /** `01-olivia_rodrigo-drop_dead-6a84aa` */
    private val SCENE_TRACK_STEM = Regex(
        """^(\d{1,3})-(.+)-([a-fA-F0-9]{6})$""",
    )

    /** `Olivia_Rodrigo-album_title-WEB-2026-MyMom` */
    private val SCENE_ALBUM_FOLDER = Regex(
        """^(.+?)-(.+)-(WEB(?:-?DL)?|CD|FLAC|V0|V2|320|BFAT|LP|TAPES?)-(\d{4})-(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private val COMMON_GENRE_FOLDER_NAMES = setOf(
        "pop", "rock", "jazz", "blues", "classical", "hip-hop", "hip hop", "hiphop",
        "rap", "r&b", "rnb", "soul", "funk", "metal", "punk", "indie", "electronic",
        "dance", "house", "techno", "trance", "ambient", "folk", "country", "reggae",
        "latin", "world", "soundtrack", "soundtracks", "compilations", "various",
        "spoken", "comedy", "christmas", "kids", "children",
    )

    fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    fun lyricsPathFor(audioPath: String): String {
        val base = audioPath.substringBeforeLast('.')
        return "$base.lrc"
    }

    /**
     * Path layouts under music root (file is always last):
     * - Artist/Album/track
     * - Genre/Artist/Album/track
     * - Artist/track
     */
    fun parseFromBytes(
        entry: FileEntry,
        musicRoot: String,
        bytes: ByteArray,
        lyricsExists: Boolean,
        cacheDir: File? = null,
    ): ParsedTrack {
        val tags = if (bytes.isNotEmpty() && cacheDir != null) {
            runCatching { readTags(bytes, cacheDir, entry.name) }.getOrNull()
        } else null

        val parts = relativePathParts(entry.path, musicRoot)
        val dirs = if (parts.size > 1) parts.dropLast(1) else emptyList()
        val fileName = entry.name.substringBeforeLast('.')

        val pathAlbum = dirs.lastOrNull() ?: "Unknown Album"
        val pathArtistFallback = when {
            dirs.size >= 3 -> dirs[dirs.size - 2] // Genre / Artist / Album
            dirs.size == 2 -> dirs[0] // Artist/Album — or Genre/Album (comps)
            dirs.size == 1 -> dirs[0]
            else -> "Unknown Artist"
        }
        val pathGenre = if (dirs.size >= 3) dirs.first() else null
        val fileParsed = parseCompilationFileName(fileName)

        // ID3 tags win. Filename / path only fill blanks.
        val displayArtist = tags?.artist?.takeIf { it.isNotBlank() }
            ?: fileParsed?.second
            ?: pathArtistFallback
        val albumArtist = tags?.albumArtist?.takeIf { it.isNotBlank() } ?: displayArtist
        val albumTitle = tags?.album?.takeIf { it.isNotBlank() } ?: pathAlbum
        val title = tags?.title?.takeIf { it.isNotBlank() }
            ?: fileParsed?.third
            ?: fileName
        val year = tags?.year
        val genre = tags?.genre?.takeIf { it.isNotBlank() } ?: pathGenre
        val trackNumber = tags?.trackNumber ?: fileParsed?.first

        val artistId = hashId("artist", albumArtist)
        val albumId = hashId("album", albumArtist, albumTitle)
        val trackId = hashId("track", entry.path)

        val folderPath = entry.path.replace('\\', '/').substringBeforeLast('/')
        val coverPath = "$folderPath/folder.jpg"

        val artist = ArtistEntity(
            id = artistId,
            name = albumArtist,
            sortKey = albumArtist.lowercase(),
        )
        val album = AlbumEntity(
            id = albumId,
            artistId = artistId,
            title = albumTitle,
            year = year,
            genre = genre,
            coverPath = coverPath,
            folderPath = folderPath,
        )
        val track = TrackEntity(
            id = trackId,
            albumId = albumId,
            artistId = artistId,
            title = title,
            artistName = displayArtist,
            albumTitle = albumTitle,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = tags?.discNumber,
            year = year,
            genre = genre,
            composer = tags?.composer,
            lyricist = tags?.lyricist,
            conductor = tags?.conductor,
            publisher = tags?.publisher,
            comment = tags?.comment,
            grouping = tags?.grouping,
            originalArtist = tags?.originalArtist,
            remixer = tags?.remixer,
            bpm = tags?.bpm,
            isrc = tags?.isrc,
            encoder = tags?.encoder,
            mood = tags?.mood,
            media = tags?.media,
            language = tags?.language,
            copyright = tags?.copyright,
            bitrateKbps = tags?.bitrateKbps,
            sampleRateHz = tags?.sampleRateHz,
            channels = tags?.channels,
            codec = tags?.codec,
            durationMs = tags?.durationMs ?: 0,
            nasPath = entry.path,
            lyricsPath = if (lyricsExists) lyricsPathFor(entry.path) else null,
            mimeType = entry.additional?.type,
            fileSize = entry.additional?.size ?: 0,
            modifiedTime = entry.additional?.time?.mtime ?: 0,
        )
        return ParsedTrack(track, artist, album)
    }

    fun readTagsFromBytes(bytes: ByteArray, cacheDir: File, fileName: String): TrackTagInfo? {
        if (bytes.isEmpty()) return null
        return runCatching { readTags(bytes, cacheDir, fileName).toTagInfo() }.getOrNull()
    }

    fun relativePathParts(path: String, musicRoot: String): List<String> {
        var p = path.replace('\\', '/').trim('/')
        val root = musicRoot.replace('\\', '/').trim('/')
        if (root.isNotEmpty() && p.lowercase().startsWith(root.lowercase())) {
            p = p.drop(root.length).trim('/')
        }
        return p.split('/').filter { it.isNotBlank() }
    }

    /**
     * Build a track from Synology Audio Station's already-indexed tags (no file download).
     */
    fun parseFromAudioStation(
        song: com.vizvag.shieldvideo.music.data.synology.AudioStationSong,
        musicRoot: String,
    ): ParsedTrack {
        val path = song.path.replace('\\', '/')
        val tag = song.additional?.songTag
        val audio = song.additional?.songAudio
        val parts = relativePathParts(path, musicRoot)
        val dirs = if (parts.size > 1) parts.dropLast(1) else emptyList()
        val fileName = path.substringAfterLast('/').substringBeforeLast('.')

        val pathAlbum = dirs.lastOrNull() ?: "Unknown Album"
        val pathArtistFallback = when {
            dirs.size >= 3 -> dirs[dirs.size - 2]
            dirs.size == 2 -> dirs[0]
            dirs.size == 1 -> dirs[0]
            else -> "Unknown Artist"
        }
        val pathGenre = if (dirs.size >= 3) dirs.first() else null
        val fileParsed = parseCompilationFileName(fileName)

        // ID3 / Audio Station tags first; filename split only for blanks.
        val tagTitle = tag?.title?.takeIf { it.isNotBlank() }
            ?: song.title?.takeIf { it.isNotBlank() && !titleLooksLikeFileName(it, path) }
        val tagArtist = tag?.artist?.takeIf { it.isNotBlank() }
        val (title, trackArtist, trackNumber) = fillMissingFromCompilationFileName(
            title = tagTitle,
            artist = tagArtist,
            nasPath = path,
            trackNumber = tag?.track?.takeIf { it > 0 },
        ).let { (t, a, n) ->
            Triple(
                fixTagText(t.ifBlank { fileParsed?.third ?: fileName }),
                fixTagText(a.ifBlank { fileParsed?.second ?: pathArtistFallback }),
                n ?: fileParsed?.first,
            )
        }
        val albumArtist = fixTagText(
            tag?.albumArtist?.takeIf { it.isNotBlank() } ?: trackArtist,
        )
        val albumTitle = fixTagText(tag?.album?.takeIf { it.isNotBlank() } ?: pathAlbum)
        val year = tag?.year?.takeIf { it > 0 }
        val genre = tag?.genre?.takeIf { it.isNotBlank() } ?: pathGenre
        val discNumber = tag?.disc?.takeIf { it > 0 }

        val artistId = hashId("artist", albumArtist)
        val albumId = hashId("album", albumArtist, albumTitle)
        val trackId = hashId("track", path)

        val folderPath = path.substringBeforeLast('/')
        val coverPath = "$folderPath/folder.jpg"
        val bitrateKbps = audio?.bitrate?.takeIf { it > 0 }?.let { bps ->
            if (bps >= 1000) bps / 1000 else bps
        }
        val durationMs = (audio?.duration?.takeIf { it > 0 }?.toLong() ?: 0L) * 1000L
        val fileSize = audio?.filesize?.takeIf { it > 0 } ?: 0L

        val artist = ArtistEntity(
            id = artistId,
            name = albumArtist,
            sortKey = albumArtist.lowercase(),
        )
        val album = AlbumEntity(
            id = albumId,
            artistId = artistId,
            title = albumTitle,
            year = year,
            genre = genre,
            coverPath = coverPath,
            folderPath = folderPath,
        )
        val track = TrackEntity(
            id = trackId,
            albumId = albumId,
            artistId = artistId,
            title = title,
            artistName = trackArtist,
            albumTitle = albumTitle,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genre,
            composer = tag?.composer?.takeIf { it.isNotBlank() },
            comment = tag?.comment?.takeIf { it.isNotBlank() },
            bitrateKbps = bitrateKbps,
            sampleRateHz = audio?.frequency?.takeIf { it > 0 },
            channels = audio?.channel?.takeIf { it > 0 },
            codec = audio?.codec?.takeIf { it.isNotBlank() }
                ?: audio?.container?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
            nasPath = path,
            lyricsPath = null,
            mimeType = audio?.container,
            fileSize = fileSize,
            modifiedTime = 0L,
        )
        return ParsedTrack(track, artist, album)
    }

    fun trackFromFileEntry(entry: FileEntry): TrackEntity {
        val path = entry.path.replace('\\', '/')
        val stem = entry.name.substringBeforeLast('.')
        val parent = path.substringBeforeLast('/')
        val albumGuess = parent.substringAfterLast('/').ifBlank { "Unknown Album" }
        val artistFolder = parent.substringBeforeLast('/').substringAfterLast('/').ifBlank { "Unknown Artist" }
        val compiled = parseCompilationFileName(stem)
        val artistGuess = compiled?.second ?: artistFolder
        val titleGuess = compiled?.third ?: stem
        val artistId = "path-artist:${artistGuess.lowercase()}"
        val albumId = "path-album:${artistGuess.lowercase()}|${albumGuess.lowercase()}"
        return TrackEntity(
            id = "path-track:$path",
            albumId = albumId,
            artistId = artistId,
            title = titleGuess,
            artistName = artistGuess,
            albumTitle = albumGuess,
            albumArtist = artistGuess,
            trackNumber = compiled?.first,
            nasPath = path,
            mimeType = entry.additional?.type,
            fileSize = entry.additional?.size ?: 0,
            modifiedTime = entry.additional?.time?.mtime ?: 0,
        )
    }

    /** True when title is just the file stem — not real ID3 / Audio Station metadata. */
    fun titleLooksLikeFileName(title: String, nasPath: String): Boolean {
        val stem = nasPath.substringAfterLast('/').substringBeforeLast('.')
        val file = nasPath.substringAfterLast('/')
        val t = title.trim()
        if (t.isBlank()) return true
        if (t.equals(stem, ignoreCase = true) || t.equals(file, ignoreCase = true)) return true
        if (t.contains('_')) return true
        // Compilation filenames: "01. Artist - Title"
        if (parseCompilationFileName(t) != null || parseCompilationFileName(stem) != null) {
            if (t.contains(" - ") && (t.equals(stem, ignoreCase = true) ||
                    normKey(t) == normKey(stem))
            ) {
                return true
            }
        }
        val norm = ::normKey
        if (norm(t) == norm(stem)) return true
        // "oasis-importance of being idle" style slugs
        if (t == t.lowercase() && (t.contains('-') || stem.contains('-') || stem.contains('_'))) {
            if (norm(t).startsWith(norm(stem).take(12)) || norm(stem).startsWith(norm(t).take(12))) {
                return true
            }
        }
        return false
    }

    private fun normKey(s: String): String =
        s.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Compilation / soundtrack filenames like:
     * `01. The Future Sound of London - We Have Explosive`
     * Used only when ID3 title/artist are missing — never overrides real tags.
     */
    fun parseCompilationFileName(raw: String): Triple<Int?, String, String>? {
        var s = fixTagText(raw).trim()
        if (s.isBlank()) return null
        val numbered = Regex(
            """^(?:track\s*)?(\d{1,3})\s*[.):\-_]\s+(.+)$""",
            RegexOption.IGNORE_CASE,
        )
        val nm = numbered.matchEntire(s) ?: return null
        val trackNo = nm.groupValues[1].toIntOrNull()
        s = nm.groupValues[2].trim()
        val dash = s.indexOf(" - ")
        if (dash <= 0) return null
        val artist = s.substring(0, dash).trim()
        val title = s.substring(dash + 3).trim()
        if (artist.length < 2 || title.isBlank()) return null
        return Triple(trackNo, artist, title)
    }

    /**
     * Scene / WEB release track stems:
     * `01-olivia_rodrigo-drop_dead-6a84aa` → track 1, Olivia Rodrigo, drop dead
     */
    data class SceneTrackName(
        val trackNumber: Int?,
        val artist: String,
        val title: String,
    )

    /**
     * Scene / WEB release album folders:
     * `Olivia_Rodrigo-you_seem_pretty_sad_for_a_girl_so_in_love-WEB-2026-MyMom`
     */
    data class SceneAlbumName(
        val artist: String,
        val album: String,
        val year: Int?,
        val group: String?,
    )

    fun parseSceneTrackFileName(raw: String): SceneTrackName? {
        val stem = fixTagText(raw).trim().substringBeforeLast('.')
        if (stem.isBlank()) return null
        val m = SCENE_TRACK_STEM.matchEntire(stem) ?: return null
        val trackNo = m.groupValues[1].toIntOrNull()
        val middle = m.groupValues[2]
        val dash = middle.indexOf('-')
        if (dash <= 0) return null
        val artistSlug = middle.substring(0, dash).trim()
        val titleSlug = middle.substring(dash + 1).trim()
        if (artistSlug.length < 2 || titleSlug.isBlank()) return null
        return SceneTrackName(
            trackNumber = trackNo,
            artist = humanizeFileStem(artistSlug, null),
            title = humanizeFileStem(titleSlug, null),
        )
    }

    fun parseSceneAlbumFolder(raw: String): SceneAlbumName? {
        val name = fixTagText(raw).trim()
        if (name.isBlank() || !name.contains('_') && !name.contains('-')) return null
        val m = SCENE_ALBUM_FOLDER.matchEntire(name) ?: return null
        val artistSlug = m.groupValues[1].trim()
        val albumSlug = m.groupValues[2].trim()
        val year = m.groupValues[4].toIntOrNull()
        val group = m.groupValues[5].trim().takeIf { it.isNotBlank() }
        if (artistSlug.length < 2 || albumSlug.isBlank()) return null
        return SceneAlbumName(
            artist = humanizeFileStem(artistSlug, null),
            album = humanizeFileStem(albumSlug, null),
            year = year?.takeIf { it in 1900..2100 },
            group = group,
        )
    }

    /**
     * Apply scene-release filename/folder parse onto a path-stub track
     * (never overrides titles that already look like real tags).
     */
    fun applySceneNameFallback(track: TrackEntity): TrackEntity {
        val artistIsGenre = track.artistName.trim().lowercase() in COMMON_GENRE_FOLDER_NAMES
        if (!titleLooksLikeFileName(track.title, track.nasPath) &&
            !isPlaceholderArtist(track.artistName) &&
            !artistIsGenre
        ) {
            return track
        }
        val path = track.nasPath.replace('\\', '/')
        val stem = path.substringAfterLast('/').substringBeforeLast('.')
        val folder = path.substringBeforeLast('/').substringAfterLast('/')
        val sceneTrack = parseSceneTrackFileName(stem)
        val sceneAlbum = parseSceneAlbumFolder(folder)
        if (sceneTrack == null && sceneAlbum == null) return track
        val titleMissing = titleLooksLikeFileName(track.title, track.nasPath)
        val artistMissing = isPlaceholderArtist(track.artistName) || artistIsGenre
        return track.copy(
            title = if (titleMissing) {
                sceneTrack?.title ?: track.title
            } else {
                track.title
            },
            artistName = when {
                !artistMissing -> track.artistName
                sceneTrack != null -> sceneTrack.artist
                sceneAlbum != null -> sceneAlbum.artist
                else -> track.artistName
            },
            albumTitle = sceneAlbum?.album?.takeIf { it.isNotBlank() }
                ?: track.albumTitle.takeUnless {
                    it.contains('_') || normKey(it) == normKey(folder)
                }
                ?: track.albumTitle,
            albumArtist = sceneAlbum?.artist
                ?: sceneTrack?.artist
                ?: track.albumArtist,
            trackNumber = sceneTrack?.trackNumber ?: track.trackNumber,
            year = sceneAlbum?.year ?: track.year,
        )
    }

    /**
     * Fill blank / filename-only title or artist from disk naming conventions.
     * Real ID3 values are never replaced.
     */
    fun fillMissingFromCompilationFileName(
        title: String?,
        artist: String?,
        nasPath: String?,
        trackNumber: Int? = null,
    ): Triple<String, String, Int?> {
        var t = title?.trim().orEmpty()
        var a = artist?.trim().orEmpty()
        var n = trackNumber?.takeIf { it > 0 }
        val path = nasPath?.replace('\\', '/')?.takeIf { it.isNotBlank() }
        val stem = path
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }

        val titleMissing = t.isBlank()
        val artistMissing = isPlaceholderArtist(a)
        if (!titleMissing && !artistMissing) {
            return Triple(t, a, n)
        }

        val compiled = when {
            titleMissing && t.isNotBlank() -> parseCompilationFileName(t)
            else -> null
        } ?: stem?.let { parseCompilationFileName(it) }

        val parsed = compiled

        if (parsed != null) {
            val (pn, pa, pt) = parsed
            if (titleMissing) {
                t = pt
                a = pa
            } else if (artistMissing) {
                a = pa
            }
            if (n == null) n = pn
        }
        return Triple(t, a, n)
    }

    fun isPlaceholderArtist(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.isEmpty() ||
            v.equals("Unknown artist", ignoreCase = true) ||
            v.equals("unknown", ignoreCase = true) ||
            v.matches(Regex("""\d{1,3}"""))
    }

    /** Genre bins like /music/Pop/... must not become the performer. */
    private fun isLikelyGenreFolderArtist(artist: String?, @Suppress("UNUSED_PARAMETER") albumFolder: String?): Boolean {
        val a = artist?.trim().orEmpty()
        if (a.isEmpty()) return false
        return a.lowercase() in COMMON_GENRE_FOLDER_NAMES
    }

    /** Artist folder above the album folder, when tags are missing. */
    fun guessArtistFromPath(nasPath: String, albumTitle: String? = null): String? {
        val parent = nasPath.replace('\\', '/').trimEnd('/').substringBeforeLast('/')
        if (parent.isBlank()) return null
        val albumFolder = parent.substringAfterLast('/')
        parseSceneAlbumFolder(albumFolder)?.artist?.let { return it }
        val artistFolder = parent.substringBeforeLast('/').substringAfterLast('/')
        if (artistFolder.isBlank()) return null
        if (artistFolder.equals(albumFolder, ignoreCase = true)) return null
        if (albumTitle != null && artistFolder.equals(albumTitle.trim(), ignoreCase = true)) return null
        if (artistFolder.lowercase() in COMMON_GENRE_FOLDER_NAMES) return null
        val human = humanizeFileStem(artistFolder, null)
        return human.takeUnless { isPlaceholderArtist(it) }
    }

    /** Prefer track artist, then album artist, then NAS folder. */
    fun resolveDisplayArtist(
        artist: String?,
        albumArtist: String? = null,
        nasPath: String? = null,
        albumTitle: String? = null,
    ): String? {
        sequenceOf(artist, albumArtist).forEach { candidate ->
            val v = candidate?.trim().orEmpty()
            if (!isPlaceholderArtist(v)) {
                return if (v.contains('_') || (v == v.lowercase() && v.contains('-') && v.length > 8)) {
                    humanizeFileStem(v, null)
                } else {
                    v
                }
            }
        }
        return nasPath?.takeIf { it.isNotBlank() }?.let { guessArtistFromPath(it, albumTitle) }
    }

    /** Turn a file stem / slug into a readable title. */
    fun humanizeFileStem(stem: String, artistHint: String?): String {
        var s = stem.trim()
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        val hint = artistHint?.trim()?.takeIf { it.isNotBlank() }
        if (hint != null) {
            val h = hint.replace('_', ' ').trim()
            if (s.startsWith(h, ignoreCase = true)) {
                s = s.drop(h.length).trimStart(' ', '-', '_', '.')
            } else {
                val slug = h.lowercase().replace(' ', '-')
                if (s.lowercase().startsWith("$slug-") || s.lowercase().startsWith("${slug}_")) {
                    s = s.drop(slug.length).trimStart(' ', '-', '_', '.')
                }
            }
        }
        // Strip leading track numbers: "01 - Title" / "01.Title"
        s = s.replace(Regex("""^(?:track\s*)?\d{1,3}\s*[.):\-_]\s+""", RegexOption.IGNORE_CASE), "")
        // Hyphens between words → spaces (keep apostrophes)
        s = s.replace(Regex("""(?<=\w)-(?=\w)"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.isBlank()) return stem.trim()
        return s.split(' ').joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word.replaceFirstChar { ch: Char -> if (ch.isLowerCase()) ch.titlecaseChar() else ch }
        }
    }

    /** Rebuild artist/album wrappers from a previously persisted track (no NAS download). */
    fun parsedFromExisting(track: TrackEntity): ParsedTrack {
        val artistName = track.albumArtist?.takeIf { it.isNotBlank() } ?: track.artistName
        val folderPath = track.nasPath.replace('\\', '/').substringBeforeLast('/')
        return ParsedTrack(
            track = track,
            artist = ArtistEntity(
                id = track.artistId,
                name = artistName,
                sortKey = artistName.lowercase(),
            ),
            album = AlbumEntity(
                id = track.albumId,
                artistId = track.artistId,
                title = track.albumTitle,
                year = track.year,
                genre = track.genre,
                coverPath = "$folderPath/folder.jpg",
                folderPath = folderPath,
            ),
        )
    }

    /**
     * True when the on-disk index entry still matches the NAS file fingerprint.
     * Skips re-downloading tags for unchanged files on subsequent syncs.
     */
    fun isUnchanged(existing: TrackEntity, entry: FileEntry): Boolean {
        val size = entry.additional?.size ?: 0L
        val mtime = entry.additional?.time?.mtime ?: 0L
        if (size > 0L && existing.fileSize > 0L && size != existing.fileSize) return false
        if (mtime > 0L && existing.modifiedTime > 0L && mtime != existing.modifiedTime) return false
        // Prefer keeping a known index row when the API omits size/mtime.
        return true
    }

    private data class TagData(
        val title: String?,
        val artist: String?,
        val albumArtist: String?,
        val album: String?,
        val genre: String?,
        val trackNumber: Int?,
        val discNumber: Int?,
        val year: Int?,
        val composer: String?,
        val lyricist: String?,
        val conductor: String?,
        val publisher: String?,
        val comment: String?,
        val grouping: String?,
        val originalArtist: String?,
        val remixer: String?,
        val bpm: Int?,
        val isrc: String?,
        val encoder: String?,
        val mood: String?,
        val media: String?,
        val language: String?,
        val copyright: String?,
        val url: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        val codec: String?,
        val bitsPerSample: Int?,
        val durationMs: Long,
    ) {
        fun toTagInfo() = TrackTagInfo(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            composer = composer,
            lyricist = lyricist,
            conductor = conductor,
            publisher = publisher,
            comment = comment,
            grouping = grouping,
            originalArtist = originalArtist,
            remixer = remixer,
            bpm = bpm,
            isrc = isrc,
            encoder = encoder,
            mood = mood,
            media = media,
            language = language,
            copyright = copyright,
            url = url,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRateHz,
            channels = channels,
            codec = codec,
            bitsPerSample = bitsPerSample,
            durationMs = durationMs,
        )
    }

    private fun readTags(bytes: ByteArray, cacheDir: File, fileName: String): TagData {
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val temp = File.createTempFile("tag_", "_$safeName", cacheDir)
        try {
            temp.writeBytes(bytes)
            val file = AudioFileIO.read(temp)
            val tag = file.tagOrCreateDefault
            val header = file.audioHeader
            val trackStr = tag.firstOrNull(FieldKey.TRACK)
            val discStr = tag.firstOrNull(FieldKey.DISC_NO)
            val yearStr = tag.firstOrNull(FieldKey.YEAR)
            val bpmStr = tag.firstOrNull(FieldKey.BPM)
            val bitrate = runCatching { header.bitRateAsNumber.toInt() }.getOrNull()
                ?.takeIf { it > 0 }
            val sampleRate = runCatching { header.sampleRateAsNumber }.getOrNull()
                ?.takeIf { it > 0 }
            val channels = runCatching {
                header.channels?.trim()?.toIntOrNull()
                    ?: when (header.channels?.lowercase()) {
                        "mono" -> 1
                        "stereo" -> 2
                        else -> null
                    }
            }.getOrNull()?.takeIf { it > 0 }
            val bits = runCatching { header.bitsPerSample }.getOrNull()
                ?.takeIf { it > 0 }
            val codec = header.format?.takeIf { it.isNotBlank() }
                ?: header.encodingType?.takeIf { it.isNotBlank() }
            return TagData(
                title = tag.firstOrNull(FieldKey.TITLE),
                artist = tag.firstOrNull(FieldKey.ARTIST),
                albumArtist = tag.firstOrNull(FieldKey.ALBUM_ARTIST),
                album = tag.firstOrNull(FieldKey.ALBUM),
                genre = tag.firstOrNull(FieldKey.GENRE),
                trackNumber = trackStr?.substringBefore('/')?.trim()?.toIntOrNull(),
                discNumber = discStr?.substringBefore('/')?.trim()?.toIntOrNull(),
                year = yearStr?.take(4)?.toIntOrNull(),
                composer = tag.firstOrNull(FieldKey.COMPOSER),
                lyricist = tag.firstOrNull(FieldKey.LYRICIST),
                conductor = tag.firstOrNull(FieldKey.CONDUCTOR),
                publisher = tag.firstOrNull(FieldKey.RECORD_LABEL),
                comment = tag.firstOrNull(FieldKey.COMMENT),
                grouping = tag.firstOrNull(FieldKey.GROUPING),
                originalArtist = tag.firstOrNull(FieldKey.ORIGINAL_ARTIST),
                remixer = tag.firstOrNull(FieldKey.REMIXER),
                bpm = bpmStr?.trim()?.toFloatOrNull()?.toInt(),
                isrc = tag.firstOrNull(FieldKey.ISRC),
                encoder = tag.firstOrNull(FieldKey.ENCODER),
                mood = tag.firstOrNull(FieldKey.MOOD),
                media = tag.firstOrNull(FieldKey.MEDIA),
                language = tag.firstOrNull(FieldKey.LANGUAGE),
                copyright = tag.firstOrNull(FieldKey.COPYRIGHT),
                url = tag.firstOrNull(FieldKey.URL_OFFICIAL_RELEASE_SITE)
                    ?: tag.firstOrNull(FieldKey.URL_DISCOGS_RELEASE_SITE),
                bitrateKbps = bitrate,
                sampleRateHz = sampleRate,
                channels = channels,
                codec = codec,
                bitsPerSample = bits,
                durationMs = (header.trackLength * 1000L).coerceAtLeast(0),
            )
        } finally {
            temp.delete()
        }
    }

    private fun org.jaudiotagger.tag.Tag.firstOrNull(key: FieldKey): String? =
        runCatching { getFirst(key) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { fixTagText(it) }

    private fun hashId(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(parts.joinToString("|").toByteArray())
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Repair common ID3 mojibake (e.g. DonÂ´t → Don't) and normalize apostrophes.
     */
    fun fixTagText(value: String): String {
        var s = value
        if (s.indexOf('Â') >= 0 || s.indexOf('Ã') >= 0 || s.contains("â€")) {
            val repaired = runCatching {
                String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            }.getOrNull()
            if (repaired != null &&
                repaired.isNotBlank() &&
                !repaired.contains('\uFFFD') &&
                repaired.length <= s.length + 8
            ) {
                s = repaired
            }
        }
        return s
            .replace('\u00B4', '\'') // ´
            .replace('\u2018', '\'') // ‘
            .replace('\u2019', '\'') // ’
            .replace('\u201B', '\'') // ‛
            .replace('\u2032', '\'') // ′
            .replace('\u0060', '\'') // `
            .replace("â€™", "'")
            .replace("â€˜", "'")
            .replace("Â´", "'")
            .replace("Â'", "'")
    }
}
