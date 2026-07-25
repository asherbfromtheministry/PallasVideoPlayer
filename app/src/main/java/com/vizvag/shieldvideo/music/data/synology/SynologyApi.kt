package com.vizvag.shieldvideo.music.data.synology

import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface SynologyApi {
    @GET("/webapi/query.cgi")
    suspend fun queryApiInfo(
        @Query("api") api: String = "SYNO.API.Info",
        @Query("version") version: Int = 1,
        @Query("method") method: String = "query",
        @Query("query") query: String,
    ): SynologyResponse<JsonObject>

    @GET("/webapi/{path}")
    suspend fun login(
        @retrofit2.http.Path("path") path: String = "auth.cgi",
        @Query("api") api: String = "SYNO.API.Auth",
        @Query("version") version: Int = 3,
        @Query("method") method: String = "login",
        @Query("account") account: String,
        @Query("passwd") password: String,
        @Query("session") session: String = "FileStation",
        @Query("format") format: String = "sid",
    ): SynologyResponse<LoginData>

    @GET("/webapi/{path}")
    suspend fun logout(
        @retrofit2.http.Path("path") path: String = "auth.cgi",
        @Query("api") api: String = "SYNO.API.Auth",
        @Query("version") version: Int = 1,
        @Query("method") method: String = "logout",
        @Query("session") session: String = "FileStation",
        @Query("_sid") sid: String,
    ): SynologyResponse<Unit>

    @GET("/webapi/entry.cgi")
    suspend fun fileStationList(
        @QueryMap params: Map<String, String>,
    ): SynologyResponse<FileListData>

    @GET("/webapi/entry.cgi")
    suspend fun fileStationSearch(
        @QueryMap params: Map<String, String>,
    ): SynologyResponse<Any>

    @GET("/webapi/{path}")
    suspend fun audioStation(
        @retrofit2.http.Path(value = "path", encoded = true) path: String,
        @QueryMap params: Map<String, String>,
    ): SynologyResponse<AudioStationSongListData>

    @GET("/webapi/{path}")
    suspend fun videoStation(
        @retrofit2.http.Path(value = "path", encoded = true) path: String,
        @QueryMap params: Map<String, String>,
    ): SynologyResponse<VideoStationListData>
}

object SynologyApiParser {
    val gson = Gson()

    fun <T> parse(data: JsonObject?, clazz: Class<T>): T? {
        if (data == null) return null
        return gson.fromJson(data, clazz)
    }

    fun parseApiInfo(data: JsonObject?): ApiInfoData? {
        if (data == null) return null
        return gson.fromJson(data, ApiInfoData::class.java)
    }

    fun parseSearchList(response: SynologyResponse<Any>): SearchListData? {
        val json = gson.toJsonTree(response.data)
        return gson.fromJson(json, SearchListData::class.java)
    }

    fun parseSearchStart(response: SynologyResponse<Any>): SearchStartData? {
        val json = gson.toJsonTree(response.data)
        return gson.fromJson(json, SearchStartData::class.java)
    }
}
