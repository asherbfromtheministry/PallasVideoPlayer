package com.vizvag.shieldvideo.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TmdbImages(
    val posterUrl: String?,
    val fanartUrl: String?,
    /** From the same TMDB details response used for art (no extra round-trip). */
    val overview: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    /** e.g. "Drama · Crime" */
    val genresLabel: String? = null,
    /** e.g. "3 seasons" / "Ended" */
    val statusLabel: String? = null,
)

data class TmdbEpisode(
    val title: String?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val airDate: String?,
    val rating: Double?,
)

class TmdbRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val imageCache = object : LinkedHashMap<String, TmdbImages?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TmdbImages?>?): Boolean =
            size > 256
    }

    data class TmdbSearchHit(
        val mediaType: String,
        val tmdbId: Int,
        val title: String,
        val year: Int?,
        val overview: String?,
    )

    /**
     * Resolve a title to a TMDB id when Trakt is unavailable / rate-limited.
     * Prefers TV for episode-ish queries, otherwise best movie-or-TV hit.
     */
    suspend fun searchTitle(
        apiKey: String,
        readToken: String,
        query: String,
        preferTv: Boolean,
        year: Int? = null,
    ): TmdbSearchHit? = withContext(Dispatchers.IO) {
        searchCandidates(apiKey, readToken, query, preferTv = preferTv, year = year, limit = 1)
            .firstOrNull()
    }

    /** Multiple TV/movie candidates for manual folder assignment when Trakt is empty/429. */
    suspend fun searchCandidates(
        apiKey: String,
        readToken: String,
        query: String,
        preferTv: Boolean = true,
        year: Int? = null,
        limit: Int = 12,
    ): List<TmdbSearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank() || (apiKey.isBlank() && readToken.isBlank())) return@withContext emptyList()
        val types = if (preferTv) listOf("tv", "movie") else listOf("movie", "tv")
        val out = linkedMapOf<String, TmdbSearchHit>()
        for (type in types) {
            for (hit in searchTypeList(apiKey, readToken, type, query, year, limit = limit)) {
                val key = "${hit.mediaType}:${hit.tmdbId}"
                if (!out.containsKey(key)) out[key] = hit
                if (out.size >= limit) return@withContext out.values.toList()
            }
        }
        out.values.toList()
    }

    private fun searchTypeList(
        apiKey: String,
        readToken: String,
        type: String,
        query: String,
        year: Int?,
        limit: Int,
    ): List<TmdbSearchHit> {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val yearParam = when {
            year == null -> ""
            type == "movie" -> "&year=$year"
            else -> "&first_air_date_year=$year"
        }
        val url = if (readToken.isNotBlank()) {
            "https://api.themoviedb.org/3/search/$type?query=$encoded&language=en-US&page=1$yearParam"
        } else {
            "https://api.themoviedb.org/3/search/$type?query=$encoded&language=en-US&page=1&api_key=$apiKey$yearParam"
        }
        val requestBuilder = Request.Builder().url(url).get()
        if (readToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $readToken")
        }
        return client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use emptyList()
            val results = JSONObject(body).optJSONArray("results") ?: return@use emptyList()
            if (results.length() == 0) return@use emptyList()
            val qNorm = query.lowercase().replace(Regex("[^a-z0-9\\s]+"), " ").replace(Regex("\\s+"), " ").trim()
            val qTokens = qNorm.split(' ').filter { it.length > 2 }
            val ranked = mutableListOf<Pair<Int, TmdbSearchHit>>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optInt("id")
                if (id <= 0) continue
                val title = when (type) {
                    "tv" -> item.optString("name")
                    else -> item.optString("title")
                }
                if (title.isBlank()) continue
                val tNorm = title.lowercase().replace(Regex("[^a-z0-9\\s]+"), " ").replace(Regex("\\s+"), " ").trim()
                val score = when {
                    tNorm == qNorm -> 0
                    qTokens.isNotEmpty() && qTokens.all { it in tNorm } -> 1
                    else -> 2
                }
                val date = when (type) {
                    "tv" -> item.optString("first_air_date")
                    else -> item.optString("release_date")
                }
                ranked += score to TmdbSearchHit(
                    mediaType = if (type == "tv") "show" else "movie",
                    tmdbId = id,
                    title = title,
                    year = date.take(4).toIntOrNull(),
                    overview = item.optString("overview").takeIf { it.isNotBlank() },
                )
            }
            ranked.sortedBy { it.first }.map { it.second }.distinctBy { it.tmdbId }.take(limit)
        }
    }

    suspend fun images(
        apiKey: String,
        readToken: String,
        mediaType: String,
        tmdbId: Int?
    ): TmdbImages? = withContext(Dispatchers.IO) {
        if (tmdbId == null) return@withContext null
        val pathType = when (mediaType) {
            "show", "tv" -> "tv"
            else -> "movie"
        }
        val cacheKey = "$pathType:$tmdbId"
        synchronized(imageCache) {
            if (imageCache.containsKey(cacheKey)) return@withContext imageCache[cacheKey]
        }

        val url = if (readToken.isNotBlank()) {
            "https://api.themoviedb.org/3/$pathType/$tmdbId?language=en-US"
        } else if (apiKey.isNotBlank()) {
            "https://api.themoviedb.org/3/$pathType/$tmdbId?language=en-US&api_key=$apiKey"
        } else {
            return@withContext null
        }

        val requestBuilder = Request.Builder().url(url).get()
        if (readToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $readToken")
        }

        val images = client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use null
            val json = JSONObject(body)
            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }
            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val date = when (pathType) {
                "tv" -> json.optString("first_air_date")
                else -> json.optString("release_date")
            }
            val year = date.take(4).toIntOrNull()
            val rating = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
            val genres = buildList {
                val arr = json.optJSONArray("genres") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.isNotBlank()) add(name)
                }
            }
            val statusLabel = when (pathType) {
                "tv" -> {
                    val seasons = json.optInt("number_of_seasons").takeIf { it > 0 }
                    val status = json.optString("status").takeIf { it.isNotBlank() }
                    when {
                        seasons != null && status != null ->
                            "$seasons ${if (seasons == 1) "season" else "seasons"} · $status"
                        seasons != null ->
                            "$seasons ${if (seasons == 1) "season" else "seasons"}"
                        else -> status
                    }
                }
                else -> json.optString("status").takeIf { it.isNotBlank() }
            }
            TmdbImages(
                posterUrl = poster?.let { "https://image.tmdb.org/t/p/w500$it" },
                fanartUrl = backdrop?.let { "https://image.tmdb.org/t/p/w1280$it" }
                    ?: poster?.let { "https://image.tmdb.org/t/p/w500$it" },
                overview = overview,
                year = year,
                rating = rating,
                genresLabel = genres.take(3).joinToString(" · ").ifBlank { null },
                statusLabel = statusLabel,
            )
        }
        synchronized(imageCache) { imageCache[cacheKey] = images }
        images
    }

    suspend fun episode(
        apiKey: String,
        readToken: String,
        tmdbShowId: Int?,
        season: Int,
        episode: Int,
    ): TmdbEpisode? = withContext(Dispatchers.IO) {
        if (tmdbShowId == null || tmdbShowId <= 0) return@withContext null
        val url = if (readToken.isNotBlank()) {
            "https://api.themoviedb.org/3/tv/$tmdbShowId/season/$season/episode/$episode?language=en-US"
        } else if (apiKey.isNotBlank()) {
            "https://api.themoviedb.org/3/tv/$tmdbShowId/season/$season/episode/$episode?language=en-US&api_key=$apiKey"
        } else {
            return@withContext null
        }
        val requestBuilder = Request.Builder().url(url).get()
        if (readToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $readToken")
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@withContext null
            val json = JSONObject(body)
            TmdbEpisode(
                title = json.optString("name").takeIf { it.isNotBlank() },
                overview = json.optString("overview").takeIf { it.isNotBlank() },
                runtimeMinutes = json.optInt("runtime").takeIf { it > 0 },
                airDate = json.optString("air_date").takeIf { it.isNotBlank() },
                rating = json.optDouble("vote_average").takeIf { it > 0.0 },
            )
        }
    }
}
