package com.vizvag.shieldvideo.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TmdbImages(
    val posterUrl: String?,
    val fanartUrl: String?
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

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@withContext null
            val json = JSONObject(body)
            val poster = json.optString("poster_path").takeIf { it.isNotBlank() }
            val backdrop = json.optString("backdrop_path").takeIf { it.isNotBlank() }
            TmdbImages(
                posterUrl = poster?.let { "https://image.tmdb.org/t/p/w500$it" },
                fanartUrl = backdrop?.let { "https://image.tmdb.org/t/p/w500$it" }
                    ?: poster?.let { "https://image.tmdb.org/t/p/w500$it" }
            )
        }
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
