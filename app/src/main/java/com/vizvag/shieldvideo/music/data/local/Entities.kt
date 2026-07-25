package com.vizvag.shieldvideo.music.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortKey: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId"), Index("year"), Index("genre")],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val artistId: String,
    val title: String,
    val year: Int? = null,
    val genre: String? = null,
    val coverPath: String? = null,
    val trackCount: Int = 0,
    val folderPath: String? = null,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("albumId"),
        Index("artistId"),
        Index("year"),
        Index("genre"),
        Index("composer"),
        Index("albumArtist"),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val artistId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
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
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val codec: String? = null,
    val durationMs: Long = 0,
    val nasPath: String,
    val lyricsPath: String? = null,
    val mimeType: String? = null,
    val fileSize: Long = 0,
    val modifiedTime: Long = 0,
)

@Entity(tableName = "library_index_state")
data class LibraryIndexStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastIndexedAt: Long = 0,
    val trackCount: Int = 0,
    val isIndexing: Boolean = false,
    val progress: Float = 0f,
    val statusMessage: String = "",
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey val trackId: String,
    val playedAt: Long,
    val playCount: Int = 1,
)
