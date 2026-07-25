package com.vizvag.shieldvideo.data.trakt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TraktMatch(
    val title: String,
    val year: Int?,
    /** Episode air date as yyyy-MM-dd when known */
    val airedDate: String? = null,
    val overview: String?,
    val rating: Double?,
    val mediaType: String,
    val tmdbId: Int?,
    val traktId: Int? = null,
    val episodeTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val runtimeMinutes: Int? = null
)

data class TraktHistory(
    val watchedEpisodeKeys: Set<String> = emptySet(),
    val watchedMovieIds: Set<Int> = emptySet(),
    /** key -> resume position in milliseconds */
    val resumePositionsMs: Map<String, Long> = emptyMap()
) {
    fun isEpisodeWatched(traktShowId: Int?, season: Int?, episode: Int?): Boolean {
        if (traktShowId == null || season == null || episode == null) return false
        return episodeKey(traktShowId, season, episode) in watchedEpisodeKeys
    }

    fun isMovieWatched(traktId: Int?): Boolean =
        traktId != null && traktId in watchedMovieIds

    fun resumeMs(traktShowId: Int?, season: Int?, episode: Int?, movieTraktId: Int?): Long? {
        if (traktShowId != null && season != null && episode != null) {
            resumePositionsMs[episodeKey(traktShowId, season, episode)]?.let { return it }
        }
        if (movieTraktId != null) {
            resumePositionsMs[movieKey(movieTraktId)]?.let { return it }
        }
        return null
    }

    companion object {
        fun episodeKey(showId: Int, season: Int, episode: Int) = "show:$showId:S${season}E$episode"
        fun movieKey(movieId: Int) = "movie:$movieId"
    }
}

class TraktRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(clientId: String, query: ParsedMediaQuery): TraktMatch? =
        withContext(Dispatchers.IO) {
            if (clientId.isBlank() || query.searchQuery.isBlank()) return@withContext null
            when (query.kind) {
                MediaKind.EPISODE -> searchEpisode(clientId, query) ?: searchGeneric(clientId, query)
                MediaKind.MOVIE -> searchMovie(clientId, query) ?: searchGeneric(clientId, query)
                MediaKind.UNKNOWN -> searchGeneric(clientId, query)
            }
        }

    /**
     * Fill episode title / overview / runtime from Trakt when we already know the show
     * (e.g. folder assignment) and the file's season + episode numbers.
     */
    suspend fun withEpisodeDetails(
        clientId: String,
        match: TraktMatch,
        season: Int,
        episode: Int,
    ): TraktMatch = withContext(Dispatchers.IO) {
        if (clientId.isBlank()) return@withContext match.copy(season = season, episode = episode)
        val ep = fetchEpisodeJson(clientId, match.traktId, season, episode) ?: return@withContext match.copy(
            season = season,
            episode = episode,
        )
        match.copy(
            year = null,
            airedDate = formatAiredDate(ep.optStringOrNull("first_aired")) ?: match.airedDate,
            overview = ep.optStringOrNull("overview") ?: match.overview,
            rating = ep.optDoubleOrNull("rating") ?: match.rating,
            episodeTitle = ep.optStringOrNull("title") ?: match.episodeTitle,
            season = season,
            episode = episode,
            runtimeMinutes = ep.optIntOrNull("runtime") ?: match.runtimeMinutes,
        )
    }

    /** Candidate shows/movies for manual folder assignment (looser than auto-match). */
    suspend fun searchCandidates(
        clientId: String,
        query: String,
        limit: Int = 12,
    ): List<TraktMatch> = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || query.isBlank()) return@withContext emptyList()
        search(clientId, "show,movie", query)
            .mapNotNull { item ->
                val type = item.optString("type").ifBlank { return@mapNotNull null }
                if (type != "show" && type != "movie") return@mapNotNull null
                val payload = item.optJSONObject(type) ?: return@mapNotNull null
                val ids = payload.optJSONObject("ids")
                val tmdb = ids?.optIntOrNull("tmdb")
                val trakt = ids?.optIntOrNull("trakt")
                if (tmdb == null && trakt == null) return@mapNotNull null
                TraktMatch(
                    title = payload.optString("title", query),
                    year = payload.optIntOrNull("year"),
                    overview = payload.optStringOrNull("overview"),
                    rating = payload.optDoubleOrNull("rating"),
                    mediaType = type,
                    tmdbId = tmdb,
                    traktId = trakt,
                    runtimeMinutes = payload.optIntOrNull("runtime"),
                )
            }
            .filter { it.tmdbId != null }
            .distinctBy { "${it.mediaType}:${it.tmdbId}" }
            .take(limit)
    }

    suspend fun loadHistory(
        clientId: String,
        accessToken: String?,
        slug: String
    ): TraktHistory = withContext(Dispatchers.IO) {
        val watchedEps = linkedSetOf<String>()
        val watchedMovies = linkedSetOf<Int>()
        val resume = linkedMapOf<String, Long>()

        // Watched shows: prefer authenticated sync, fall back to public user profile
        val watchedShowsBody = when {
            !accessToken.isNullOrBlank() ->
                getBody(clientId, "https://api.trakt.tv/sync/watched/shows", accessToken)
            slug.isNotBlank() ->
                getBody(clientId, "https://api.trakt.tv/users/$slug/watched/shows", null)
            else -> null
        }
        parseWatchedShows(watchedShowsBody, watchedEps)

        val watchedMoviesBody = when {
            !accessToken.isNullOrBlank() ->
                getBody(clientId, "https://api.trakt.tv/sync/watched/movies", accessToken)
            slug.isNotBlank() ->
                getBody(clientId, "https://api.trakt.tv/users/$slug/watched/movies", null)
            else -> null
        }
        parseWatchedMovies(watchedMoviesBody, watchedMovies)

        // In-progress playback requires OAuth
        if (!accessToken.isNullOrBlank()) {
            parsePlayback(
                getBody(clientId, "https://api.trakt.tv/sync/playback/episodes?extended=full", accessToken),
                resume,
                isEpisode = true
            )
            parsePlayback(
                getBody(clientId, "https://api.trakt.tv/sync/playback/movies?extended=full", accessToken),
                resume,
                isEpisode = false
            )
        }

        TraktHistory(
            watchedEpisodeKeys = watchedEps,
            watchedMovieIds = watchedMovies,
            resumePositionsMs = resume
        )
    }

    private fun parseWatchedShows(body: String?, into: MutableSet<String>) {
        if (body.isNullOrBlank()) return
        val array = JSONArray(body)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val showId = item.optJSONObject("show")?.optJSONObject("ids")?.optIntOrNull("trakt") ?: continue
            val seasons = item.optJSONArray("seasons") ?: continue
            for (s in 0 until seasons.length()) {
                val season = seasons.optJSONObject(s) ?: continue
                val seasonNum = season.optInt("number")
                val episodes = season.optJSONArray("episodes") ?: continue
                for (e in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(e) ?: continue
                    into += TraktHistory.episodeKey(showId, seasonNum, ep.optInt("number"))
                }
            }
        }
    }

    private fun parseWatchedMovies(body: String?, into: MutableSet<Int>) {
        if (body.isNullOrBlank()) return
        val array = JSONArray(body)
        for (i in 0 until array.length()) {
            val id = array.optJSONObject(i)
                ?.optJSONObject("movie")
                ?.optJSONObject("ids")
                ?.optIntOrNull("trakt")
            if (id != null) into += id
        }
    }

    private fun parsePlayback(body: String?, into: MutableMap<String, Long>, isEpisode: Boolean) {
        if (body.isNullOrBlank()) return
        val array = JSONArray(body)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val progress = item.optDouble("progress", 0.0)
            if (progress <= 1.0 || progress >= 95.0) continue // ignore tiny / nearly done
            if (isEpisode) {
                val showId = item.optJSONObject("show")?.optJSONObject("ids")?.optIntOrNull("trakt") ?: continue
                val ep = item.optJSONObject("episode") ?: continue
                val season = ep.optInt("season")
                val number = ep.optInt("number")
                val runtime = ep.optIntOrNull("runtime")
                    ?: item.optJSONObject("show")?.optIntOrNull("runtime")
                    ?: continue
                val ms = ((progress / 100.0) * runtime * 60_000.0).toLong()
                if (ms > 5_000) into[TraktHistory.episodeKey(showId, season, number)] = ms
            } else {
                val movie = item.optJSONObject("movie") ?: continue
                val movieId = movie.optJSONObject("ids")?.optIntOrNull("trakt") ?: continue
                val runtime = movie.optIntOrNull("runtime") ?: continue
                val ms = ((progress / 100.0) * runtime * 60_000.0).toLong()
                if (ms > 5_000) into[TraktHistory.movieKey(movieId)] = ms
            }
        }
    }

    private fun searchEpisode(clientId: String, query: ParsedMediaQuery): TraktMatch? {
        val queryTitle = query.showTitle ?: query.searchQuery
        val show = pickBestPayload(
            results = search(clientId, "show", queryTitle),
            queryTitle = queryTitle,
            queryYear = query.year,
            preferredType = "show"
        )?.second ?: return null
        val ids = show.optJSONObject("ids")
        val traktId = ids?.optIntOrNull("trakt")
        val tmdbId = ids?.optIntOrNull("tmdb")
        val title = show.optString("title", query.searchQuery)
        val overview = show.optStringOrNull("overview")
        val rating = show.optDoubleOrNull("rating")
        val season = query.season
        val episode = query.episode

        if (season != null && episode != null) {
            val ep = fetchEpisodeJson(clientId, traktId, season, episode)
            if (ep != null) {
                return TraktMatch(
                    title = title,
                    year = null,
                    airedDate = formatAiredDate(ep.optStringOrNull("first_aired")),
                    overview = ep.optStringOrNull("overview") ?: overview,
                    rating = ep.optDoubleOrNull("rating") ?: rating,
                    mediaType = "show",
                    tmdbId = tmdbId,
                    traktId = traktId,
                    episodeTitle = ep.optStringOrNull("title"),
                    season = season,
                    episode = episode,
                    runtimeMinutes = ep.optIntOrNull("runtime")
                )
            }
        }

        // Matched the show but not a specific episode — never show the series premiere year.
        return TraktMatch(
            title = title,
            year = null,
            airedDate = null,
            overview = overview,
            rating = rating,
            mediaType = "show",
            tmdbId = tmdbId,
            traktId = traktId,
            episodeTitle = null,
            season = season,
            episode = episode
        )
    }

    /** Prefer Trakt id (stable); fall back to slug from a tiny show lookup if needed. */
    private fun fetchEpisodeJson(
        clientId: String,
        traktShowId: Int?,
        season: Int,
        episode: Int,
    ): JSONObject? {
        val idOrSlug = when {
            traktShowId != null && traktShowId > 0 -> traktShowId.toString()
            else -> return null
        }
        val epBody = getBody(
            clientId,
            "https://api.trakt.tv/shows/$idOrSlug/seasons/$season/episodes/$episode?extended=full"
        ) ?: return null
        return runCatching { JSONObject(epBody) }.getOrNull()
    }

    private fun searchMovie(clientId: String, query: ParsedMediaQuery): TraktMatch? {
        val movie = pickBestPayload(
            results = search(clientId, "movie", query.searchQuery),
            queryTitle = query.searchQuery,
            queryYear = query.year,
            preferredType = "movie"
        )?.second ?: return null
        val ids = movie.optJSONObject("ids")
        return TraktMatch(
            title = movie.optString("title", query.searchQuery),
            year = movie.optIntOrNull("year") ?: query.year,
            overview = movie.optStringOrNull("overview"),
            rating = movie.optDoubleOrNull("rating"),
            mediaType = "movie",
            tmdbId = ids?.optIntOrNull("tmdb"),
            traktId = ids?.optIntOrNull("trakt"),
            runtimeMinutes = movie.optIntOrNull("runtime")
        )
    }

    private fun searchGeneric(clientId: String, query: ParsedMediaQuery): TraktMatch? {
        val picked = pickBestPayload(
            results = search(clientId, "movie,show", query.searchQuery),
            queryTitle = query.searchQuery,
            queryYear = query.year,
            preferredType = null
        ) ?: return null
        val (type, payload) = picked
        val ids = payload.optJSONObject("ids")
        val traktId = ids?.optIntOrNull("trakt")
        val tmdbId = ids?.optIntOrNull("tmdb")
        val season = query.season
        val episode = query.episode
        if (type == "show" && season != null && episode != null) {
            val ep = fetchEpisodeJson(clientId, traktId, season, episode)
            if (ep != null) {
                return TraktMatch(
                    title = payload.optString("title", query.searchQuery),
                    year = null,
                    airedDate = formatAiredDate(ep.optStringOrNull("first_aired")),
                    overview = ep.optStringOrNull("overview")
                        ?: payload.optStringOrNull("overview"),
                    rating = ep.optDoubleOrNull("rating")
                        ?: payload.optDoubleOrNull("rating"),
                    mediaType = "show",
                    tmdbId = tmdbId,
                    traktId = traktId,
                    episodeTitle = ep.optStringOrNull("title"),
                    season = season,
                    episode = episode,
                    runtimeMinutes = ep.optIntOrNull("runtime")
                        ?: payload.optIntOrNull("runtime"),
                )
            }
        }
        return TraktMatch(
            title = payload.optString("title", query.searchQuery),
            year = payload.optIntOrNull("year") ?: query.year,
            overview = payload.optStringOrNull("overview"),
            rating = payload.optDoubleOrNull("rating"),
            mediaType = type,
            tmdbId = tmdbId,
            traktId = traktId,
            season = season,
            episode = episode,
            runtimeMinutes = payload.optIntOrNull("runtime")
        )
    }

    /**
     * Accept only candidates whose title covers the query tokens, and whose year
     * matches when the filename supplied a year. Never fall back to a weak first hit.
     */
    private fun pickBestPayload(
        results: List<JSONObject>,
        queryTitle: String,
        queryYear: Int?,
        preferredType: String?
    ): Pair<String, JSONObject>? {
        data class Ranked(
            val type: String,
            val payload: JSONObject,
            val exactTitle: Boolean,
            val yearExact: Boolean,
            val traktScore: Double,
            val titleLen: Int
        )

        val ranked = results.mapNotNull { item ->
            val type = item.optString("type").ifBlank {
                preferredType ?: return@mapNotNull null
            }
            if (preferredType != null && type != preferredType) return@mapNotNull null
            val payload = item.optJSONObject(type) ?: return@mapNotNull null
            val title = payload.optString("title")
            val year = payload.optIntOrNull("year")
            if (!titlesCompatible(queryTitle, title)) return@mapNotNull null
            if (!yearsCompatible(queryYear, year)) return@mapNotNull null
            Ranked(
                type = type,
                payload = payload,
                exactTitle = normalizeTitle(queryTitle) == normalizeTitle(title),
                yearExact = queryYear != null && year == queryYear,
                traktScore = item.optDouble("score", 0.0),
                titleLen = significantTokens(title).size
            )
        }

        return ranked
            .sortedWith(
                compareByDescending<Ranked> { it.exactTitle }
                    .thenByDescending { it.yearExact }
                    .thenByDescending { it.traktScore }
                    .thenBy { it.titleLen }
            )
            .firstOrNull()
            ?.let { it.type to it.payload }
    }

    private fun search(clientId: String, type: String, query: String): List<JSONObject> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val body = getBody(
            clientId,
            "https://api.trakt.tv/search/$type?query=$encoded&extended=full&limit=25"
        ) ?: return emptyList()
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                add(array.getJSONObject(i))
            }
        }
    }

    companion object {
        private val stopWords = setOf("the", "a", "an", "of", "and", "&")
        private val nonWord = Regex("[^a-z0-9\\s]+")
        private val spaces = Regex("\\s+")

        fun normalizeTitle(value: String): String =
            value.lowercase()
                .replace(nonWord, " ")
                .replace(spaces, " ")
                .trim()

        fun significantTokens(value: String): List<String> =
            normalizeTitle(value)
                .split(' ')
                .filter { it.isNotBlank() && it !in stopWords && !yearOnly.matches(it) }

        /** Every significant query word must appear in the candidate title (fuzzy OK). */
        fun titlesCompatible(query: String, candidate: String): Boolean {
            val queryTokens = significantTokens(query)
            if (queryTokens.isEmpty()) return false
            val candidateTokens = significantTokens(candidate)
            if (candidateTokens.isEmpty()) return false
            return queryTokens.all { q -> tokenMatchesAny(q, candidateTokens) }
        }

        private fun tokenMatchesAny(queryToken: String, candidates: List<String>): Boolean {
            if (candidates.any { it == queryToken }) return true
            // Allow small typos: concordes ≈ conchords
            if (queryToken.length < 5) return false
            return candidates.any { cand ->
                cand.length >= 5 && levenshtein(queryToken, cand) <= fuzzyLimit(queryToken.length, cand.length)
            }
        }

        private fun fuzzyLimit(a: Int, b: Int): Int {
            val len = maxOf(a, b)
            return when {
                len >= 8 -> 2
                len >= 6 -> 1
                else -> 0
            }
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(
                        cur[j - 1] + 1,
                        prev[j] + 1,
                        prev[j - 1] + cost
                    )
                }
                for (j in prev.indices) prev[j] = cur[j]
            }
            return prev[b.length]
        }

        private val yearOnly = Regex("""^(19|20)\d{2}$""")

        /** When the filename has a year, the Trakt result must use that same year. */
        fun yearsCompatible(queryYear: Int?, resultYear: Int?): Boolean {
            if (queryYear == null) return true
            return resultYear == queryYear
        }

        fun formatAiredDate(iso: String?): String? {
            if (iso.isNullOrBlank()) return null
            val datePart = iso.take(10)
            return datePart.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
        }
    }

    private fun getBody(clientId: String, url: String, accessToken: String? = null): String? {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
            .get()
        if (!accessToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
