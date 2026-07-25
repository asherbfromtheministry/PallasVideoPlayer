package com.vizvag.shieldvideo.music.data.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Album art resolution order:
 * 1. Local NAS cover only when the folder name matches the **album** (not the track artist folder)
 * 2. MusicBrainz + Cover Art Archive (try every candidate until art exists)
 * 3. Deezer / iTunes album search with strict title + volume matching
 *
 * Never accepts a hit that drops a required volume/number (e.g. "32").
 * Never searches by track artist for compilation / NOW albums.
 */
class AlbumArtLookup(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun resolveCoverUrl(
        localUrl: String?,
        artist: String,
        album: String,
        trackTitle: String = "",
        albumArtist: String? = null,
        nasPath: String? = null,
        skipLocalProbe: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val albumKey = normalize(album)
        if (albumKey.isBlank()) return@withContext null
        val key = "v4|$albumKey"
        cache[key]?.let { return@withContext it }

        val compilation = isCompilation(album, albumArtist, artist)
        // Local NAS cover when the folder name matches the album (incl. NOW volume numbers).
        // Compilations used to skip local entirely — that blocked valid album folders.
        val localOk = !localUrl.isNullOrBlank() &&
            folderMatchesAlbum(nasPath, album) &&
            (skipLocalProbe || isLikelyImage(localUrl))
        if (localOk) {
            cache[key] = localUrl!!
            return@withContext localUrl
        }

        // Remote lookup (compilations still prefer Various Artists on MusicBrainz/iTunes).
        val remote = lookupRemote(album = album, compilation = compilation)
        if (remote != null) cache[key] = remote
        remote
    }

    /**
     * Track/single artwork from the internet (artist + title). Used as the sharp
     * foreground tile; album art stays for the blurred background.
     */
    suspend fun resolveTrackArtUrl(
        artist: String,
        trackTitle: String,
    ): String? = withContext(Dispatchers.IO) {
        resolveTrackIdentity(artist, trackTitle)?.artUrl
    }

    data class TrackIdentity(
        val title: String,
        val artist: String,
        val album: String?,
        val artUrl: String?,
    )

    /**
     * Resolve canonical title / artist / album (+ art) from Deezer / iTunes.
     * Used when local tags are missing or are clearly just a file name.
     */
    suspend fun resolveTrackIdentity(
        artist: String,
        trackTitle: String,
    ): TrackIdentity? = withContext(Dispatchers.IO) {
        val a = artist.trim()
        val t = trackTitle.trim()
        if (a.isBlank() || t.isBlank()) return@withContext null
        val key = "ident|v1|${normalize(a)}|${normalize(t)}"
        identityCache[key]?.let { return@withContext it }
        val remote = deezerTrackIdentity(a, t) ?: itunesTrackIdentity(a, t)
        if (remote != null) identityCache[key] = remote
        remote
    }

    private val identityCache = ConcurrentHashMap<String, TrackIdentity>()

    private fun deezerTrackIdentity(artist: String, trackTitle: String): TrackIdentity? {
        val q = URLEncoder.encode("$artist $trackTitle", "UTF-8")
        val url = "https://api.deezer.com/search/track?q=$q&limit=12"
        val results = getJson(url)?.optJSONArray("data") ?: return null
        val wantTitle = normalize(trackTitle)
        val wantArtist = normalize(artist)
        var best: TrackIdentity? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
            val titleScore = albumMatchScore(wantTitle, normalize(title))
            if (titleScore < 55) continue
            val artArtist = item.optJSONObject("artist")?.optString("name").orEmpty()
            val artNorm = normalize(artArtist)
            var score = titleScore
            if (artNorm == wantArtist) score += 25
            else if (artNorm.contains(wantArtist) || wantArtist.contains(artNorm)) score += 12
            else if (wantArtist.isNotBlank()) continue
            val albumObj = item.optJSONObject("album")
            val album = albumObj?.optString("title")?.takeIf { it.isNotBlank() }
            val art = albumObj?.optString("cover_xl")?.takeIf { it.isNotBlank() }
                ?: albumObj?.optString("cover_big")?.takeIf { it.isNotBlank() }
            if (score > bestScore) {
                bestScore = score
                best = TrackIdentity(
                    title = title,
                    artist = artArtist.ifBlank { artist },
                    album = album,
                    artUrl = art,
                )
            }
        }
        return best
    }

    private fun itunesTrackIdentity(artist: String, trackTitle: String): TrackIdentity? {
        val term = URLEncoder.encode("$artist $trackTitle", "UTF-8")
        for (country in listOf("gb", "us")) {
            val url =
                "https://itunes.apple.com/search?term=$term&media=music&entity=song&country=$country&limit=12"
            val results = getJson(url)?.optJSONArray("results") ?: continue
            val wantTitle = normalize(trackTitle)
            val wantArtist = normalize(artist)
            var best: TrackIdentity? = null
            var bestScore = 0
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("trackName").takeIf { it.isNotBlank() } ?: continue
                val titleScore = albumMatchScore(wantTitle, normalize(title))
                if (titleScore < 55) continue
                val artArtist = item.optString("artistName")
                val artNorm = normalize(artArtist)
                var score = titleScore
                if (artNorm == wantArtist) score += 25
                else if (artNorm.contains(wantArtist) || wantArtist.contains(artNorm)) score += 12
                else if (wantArtist.isNotBlank()) continue
                val album = item.optString("collectionName").takeIf { it.isNotBlank() }
                val art = item.optString("artworkUrl100").takeIf { it.isNotBlank() }?.let { hiResItunesArt(it) }
                if (score > bestScore) {
                    bestScore = score
                    best = TrackIdentity(
                        title = title,
                        artist = artArtist.ifBlank { artist },
                        album = album,
                        artUrl = art,
                    )
                }
            }
            if (best != null) return best
        }
        return null
    }

    private fun deezerTrackArt(artist: String, trackTitle: String): String? {
        return deezerTrackIdentity(artist, trackTitle)?.artUrl
    }

    private fun itunesTrackArt(artist: String, trackTitle: String): String? {
        return itunesTrackIdentity(artist, trackTitle)?.artUrl
    }

    private fun folderMatchesAlbum(nasPath: String?, album: String): Boolean {
        if (nasPath.isNullOrBlank()) return false
        val folder = nasPath.substringBeforeLast('/').substringAfterLast('/')
        if (folder.isBlank()) return false
        if (albumMatchScore(normalize(album), normalize(folder)) >= MIN_ALBUM_SCORE) return true
        // Scene release folders: Artist-Album-WEB-Year-Group
        val scene = com.vizvag.shieldvideo.music.data.metadata.MetadataResolver
            .parseSceneAlbumFolder(folder)
            ?: return false
        return albumMatchScore(normalize(album), normalize(scene.album)) >= MIN_ALBUM_SCORE
    }

    /** True when the track's parent folder is named like the album (safe for NAS folder.jpg). */
    fun folderLooksLikeAlbum(nasPath: String?, album: String): Boolean =
        folderMatchesAlbum(nasPath, album)

    private fun isCompilation(album: String, albumArtist: String?, trackArtist: String): Boolean {
        val aa = albumArtist?.trim().orEmpty()
        val ta = trackArtist.trim()
        if (aa.contains("various", ignoreCase = true)) return true
        if (aa.isNotBlank() && ta.isNotBlank() && !aa.equals(ta, ignoreCase = true)) return true
        val n = normalize(album)
        return n.contains("now that s what i call") || n.contains("now thats what i call")
    }

    private fun lookupRemote(album: String, compilation: Boolean): String? {
        musicBrainzCover(album)?.let { return it }
        deezerAlbumCover(album)?.let { return it }
        itunesAlbumCover(album, preferArtist = if (compilation) "Various Artists" else null)?.let { return it }
        return null
    }

    private fun musicBrainzCover(album: String): String? {
        if (album.isBlank()) return null
        val quoted = album.replace("\"", "")
        val candidates = linkedSetOf<Pair<String, String>>() // kind to mbid

        // Prefer releases credited to Various Artists for compilations
        searchMusicBrainz(
            path = "release",
            query = "release:\"$quoted\" AND artist:\"Various Artists\"",
        )?.let { arr ->
            collectMbids(arr, album, titleKey = "title") { id ->
                candidates += "release" to id
            }
        }
        searchMusicBrainz(
            path = "release-group",
            query = "release:\"$quoted\"",
        )?.let { arr ->
            collectMbids(arr, album, titleKey = "title") { id ->
                candidates += "release-group" to id
            }
        }
        searchMusicBrainz(
            path = "release",
            query = "release:\"$quoted\"",
        )?.let { arr ->
            collectMbids(arr, album, titleKey = "title") { id ->
                candidates += "release" to id
            }
        }

        for ((kind, mbid) in candidates) {
            coverArtArchiveUrl(kind, mbid)?.let { return it }
        }
        return null
    }

    private fun searchMusicBrainz(path: String, query: String): JSONArray? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://musicbrainz.org/ws/2/$path/?query=$encoded&fmt=json&limit=10"
        val root = getJson(url) ?: return null
        return root.optJSONArray(if (path == "release") "releases" else "release-groups")
    }

    private fun collectMbids(
        results: JSONArray,
        album: String,
        titleKey: String,
        onHit: (String) -> Unit,
    ) {
        val wanted = normalize(album)
        data class Hit(val score: Int, val id: String)
        val hits = mutableListOf<Hit>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val title = normalize(item.optString(titleKey))
            val score = albumMatchScore(wanted, title)
            if (score < MIN_ALBUM_SCORE) continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            hits += Hit(score, id)
        }
        hits.sortedByDescending { it.score }.forEach { onHit(it.id) }
    }

    private fun coverArtArchiveUrl(kind: String, mbid: String): String? {
        val metaUrl = "https://coverartarchive.org/$kind/$mbid"
        val json = getJson(metaUrl) ?: return null
        val images = json.optJSONArray("images") ?: return null
        fun artFrom(img: JSONObject): String? {
            val thumbs = img.optJSONObject("thumbnails")
            return thumbs?.optString("500")?.takeIf { it.isNotBlank() }
                ?: thumbs?.optString("large")?.takeIf { it.isNotBlank() }
                ?: img.optString("image").takeIf { it.isNotBlank() }
        }
        for (i in 0 until images.length()) {
            val img = images.optJSONObject(i) ?: continue
            if (!img.optBoolean("front", false)) continue
            artFrom(img)?.let { return it.replace("http://", "https://") }
        }
        // No explicit front flag — use first image
        images.optJSONObject(0)?.let { img ->
            artFrom(img)?.let { return it.replace("http://", "https://") }
        }
        val front = "https://coverartarchive.org/$kind/$mbid/front-500"
        return if (isLikelyImage(front)) front else null
    }

    private fun deezerAlbumCover(album: String): String? {
        val encoded = URLEncoder.encode(album, "UTF-8")
        val url = "https://api.deezer.com/search/album?q=$encoded&limit=15"
        val results = getJson(url)?.optJSONArray("data") ?: return null
        val wanted = normalize(album)
        var bestUrl: String? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val score = albumMatchScore(wanted, normalize(item.optString("title")))
            if (score < MIN_ALBUM_SCORE) continue
            val art = item.optString("cover_xl")
                .ifBlank { item.optString("cover_big") }
                .takeIf { it.isNotBlank() }
                ?: continue
            if (score > bestScore) {
                bestScore = score
                bestUrl = art
            }
        }
        return bestUrl
    }

    private fun itunesAlbumCover(album: String, preferArtist: String?): String? {
        val term = listOfNotNull(album, preferArtist?.takeIf { it.isNotBlank() }).joinToString(" ")
        for (country in listOf("gb", "us")) {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val url =
                "https://itunes.apple.com/search?term=$encoded&media=music&entity=album&country=$country&limit=15"
            val results = getJson(url)?.optJSONArray("results") ?: continue
            val wanted = normalize(album)
            var bestUrl: String? = null
            var bestScore = 0
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val score = albumMatchScore(wanted, normalize(item.optString("collectionName")))
                if (score < MIN_ALBUM_SCORE) continue
                val art = item.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: continue
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = hiResItunesArt(art)
                }
            }
            if (bestUrl != null) return bestUrl
        }
        return null
    }

    /**
     * Strict album title match. If the wanted title has a number (volume "32"),
     * the candidate MUST include that same number — otherwise "NOW Music" without
     * 32, or a random artist album, must not win.
     */
    internal fun albumMatchScore(wanted: String, candidate: String): Int {
        if (wanted.isBlank() || candidate.isBlank()) return 0
        if (wanted == candidate) return 100
        val wantedTokens = significantTokens(wanted)
        val candidateTokens = significantTokens(candidate)
        if (wantedTokens.isEmpty() || candidateTokens.isEmpty()) return 0

        val wantedNums = wantedTokens.filter { it.all(Char::isDigit) }
        val candidateNums = candidateTokens.filter { it.all(Char::isDigit) }.toSet()
        if (wantedNums.isNotEmpty()) {
            // Volume / disc numbers in the album title are mandatory.
            if (wantedNums.any { it !in candidateNums }) return 0
        }

        val hit = wantedTokens.count { w ->
            candidateTokens.any { c -> c == w || c.startsWith(w) || w.startsWith(c) }
        }
        val ratio = hit.toFloat() / wantedTokens.size
        return when {
            ratio >= 0.9f -> 90
            ratio >= 0.8f -> 80
            ratio >= 0.7f && wantedTokens.size >= 4 -> 70
            else -> 0
        }
    }

    private fun significantTokens(normalized: String): List<String> =
        normalized.split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it !in STOP_WORDS }

    internal fun normalize(input: String): String =
        input.lowercase()
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun getJson(url: String): JSONObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "PallasVideoPlayer/2.4.11 (Android TV)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use null
            JSONObject(body)
        }
    }.getOrNull()

    private fun isLikelyImage(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-63")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) return false
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.startsWith("image/")) return true
            if (contentType.contains("json") || contentType.contains("text")) return false
            val bytes = response.body?.bytes() ?: return false
            if (bytes.isEmpty()) return false
            if (bytes[0] == '{'.code.toByte() || bytes[0] == '['.code.toByte()) return false
            isImageMagic(bytes)
        }
    }.getOrDefault(false)

    private fun isImageMagic(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) {
            return true
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return true
        }
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        ) {
            return true
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            return true
        }
        return false
    }

    private fun hiResItunesArt(url: String): String =
        url
            .replace("100x100bb", "600x600bb")
            .replace("100x100bb.jpg", "600x600bb.jpg")
            .replace("60x60bb", "600x600bb")

    companion object {
        private const val MIN_ALBUM_SCORE = 70
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "of", "to", "vol", "volume", "disc", "cd",
        )
    }
}
