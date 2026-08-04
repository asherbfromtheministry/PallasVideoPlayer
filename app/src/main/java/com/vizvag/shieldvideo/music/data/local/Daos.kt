package com.vizvag.shieldvideo.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class AlbumWithArtist(
    val albumId: String,
    val title: String,
    val artistId: String,
    val artistName: String,
    val year: Int?,
    val genre: String?,
    val coverPath: String?,
    val trackCount: Int,
    val folderPath: String?,
)

data class SearchResult(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String,
)

/** Per-album album-artist vote from track tags (search subtitles). */
data class AlbumArtistCount(
    val albumId: String,
    val albumArtist: String,
    val trackCount: Int,
)

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY sortKey ASC")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getById(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%' ORDER BY sortKey LIMIT 50")
    suspend fun search(query: String): List<ArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artists: List<ArtistEntity>)

    @Query("DELETE FROM artists")
    suspend fun deleteAll()
}

@Dao
interface AlbumDao {
    @Query(
        """
        SELECT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        ORDER BY a.title, ar.sortKey
        """,
    )
    fun observeAllWithArtist(): Flow<List<AlbumWithArtist>>

    @Query(
        """
        SELECT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        WHERE a.artistId = :artistId
        ORDER BY a.title
        """,
    )
    fun observeByArtist(artistId: String): Flow<List<AlbumWithArtist>>

    @Query(
        """
        SELECT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        WHERE a.year = :year
        ORDER BY ar.sortKey, a.title
        """,
    )
    fun observeByYear(year: Int): Flow<List<AlbumWithArtist>>

    @Query(
        """
        SELECT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        WHERE a.genre = :genre
        ORDER BY ar.sortKey, a.title
        """,
    )
    fun observeByGenre(genre: String): Flow<List<AlbumWithArtist>>

    @Query(
        """
        SELECT DISTINCT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        JOIN tracks t ON t.albumId = a.id
        WHERE IFNULL(t.composer, '') = :composer
        ORDER BY ar.sortKey, a.title
        """,
    )
    fun observeByComposer(composer: String): Flow<List<AlbumWithArtist>>

    @Query(
        """
        SELECT DISTINCT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        JOIN tracks t ON t.albumId = a.id
        WHERE LOWER(IFNULL(NULLIF(TRIM(t.albumArtist), ''), ar.name)) = LOWER(:albumArtist)
        ORDER BY ar.sortKey, a.title
        """,
    )
    fun observeByAlbumArtist(albumArtist: String): Flow<List<AlbumWithArtist>>

    @Query(
        """
        SELECT DISTINCT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        JOIN tracks t ON t.albumId = a.id
        WHERE IFNULL(t.mood, '') = :mood
        ORDER BY ar.sortKey, a.title
        """,
    )
    fun observeByMood(mood: String): Flow<List<AlbumWithArtist>>

    @Query(
        """
        SELECT DISTINCT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        JOIN tracks t ON t.albumId = a.id
        WHERE IFNULL(t.grouping, '') = :grouping
        ORDER BY ar.sortKey, a.title
        """,
    )
    fun observeByGrouping(grouping: String): Flow<List<AlbumWithArtist>>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: String): AlbumEntity?

    @Query(
        """
        SELECT a.id AS albumId, a.title, a.artistId, ar.name AS artistName,
               a.year, a.genre, a.coverPath, a.trackCount, a.folderPath
        FROM albums a
        JOIN artists ar ON a.artistId = ar.id
        WHERE a.title LIKE '%' || :query || '%'
           OR ar.name LIKE '%' || :query || '%'
        ORDER BY a.title, ar.sortKey
        LIMIT 100
        """,
    )
    suspend fun search(query: String): List<AlbumWithArtist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums")
    suspend fun deleteAll()
}

@Dao
interface TrackDao {
    @Query(
        """
        SELECT * FROM tracks
        ORDER BY artistName, albumTitle, IFNULL(discNumber, 1), trackNumber, title
        """,
    )
    fun observeAll(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        ORDER BY artistName, albumTitle, IFNULL(discNumber, 1), trackNumber, title
        """,
    )
    suspend fun getAll(): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE albumId = :albumId
        ORDER BY IFNULL(discNumber, 1), trackNumber, title
        """,
    )
    fun observeByAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE albumId = :albumId
        ORDER BY IFNULL(discNumber, 1), trackNumber, title
        """,
    )
    suspend fun getByAlbum(albumId: String): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE albumTitle = :title COLLATE NOCASE
        ORDER BY IFNULL(discNumber, 1), trackNumber, title
        """,
    )
    suspend fun getByAlbumTitle(title: String): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE artistId = :artistId
        ORDER BY albumTitle, IFNULL(discNumber, 1), trackNumber, title
        """,
    )
    fun observeByArtist(artistId: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE artistId = :artistId
        ORDER BY albumTitle, IFNULL(discNumber, 1), trackNumber, title
        """,
    )
    suspend fun getByArtist(artistId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE nasPath = :path LIMIT 1")
    suspend fun getByPath(path: String): TrackEntity?

    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :query || '%'
        ORDER BY title, artistName, albumTitle
        LIMIT 100
        """,
    )
    suspend fun search(query: String): List<TrackEntity>

    @Query(
        """
        SELECT albumId,
               IFNULL(NULLIF(TRIM(albumArtist), ''), artistName) AS albumArtist,
               COUNT(*) AS trackCount
        FROM tracks
        WHERE albumId IN (:albumIds)
        GROUP BY albumId, IFNULL(NULLIF(TRIM(albumArtist), ''), artistName)
        """,
    )
    suspend fun albumArtistCounts(albumIds: List<String>): List<AlbumArtistCount>

    @Query(
        """
        SELECT * FROM tracks
        WHERE artistName LIKE '%' || :query || '%'
           OR IFNULL(albumArtist, '') LIKE '%' || :query || '%'
        ORDER BY albumTitle, IFNULL(discNumber, 1), trackNumber, title
        LIMIT 500
        """,
    )
    suspend fun searchByArtistName(query: String): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

@Dao
interface LibraryIndexDao {
    @Query("SELECT * FROM library_index_state WHERE id = 1")
    fun observeState(): Flow<LibraryIndexStateEntity?>

    @Query("SELECT * FROM library_index_state WHERE id = 1")
    suspend fun getState(): LibraryIndexStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LibraryIndexStateEntity)
}

@Dao
interface PlayHistoryDao {
    @Query(
        """
        SELECT t.* FROM tracks t
        JOIN play_history h ON t.id = h.trackId
        ORDER BY h.playedAt DESC LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int = 20): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlayHistoryEntity)

    @Query("SELECT * FROM play_history WHERE trackId = :trackId")
    suspend fun get(trackId: String): PlayHistoryEntity?
}

@Dao
interface LibraryDao {
    @Transaction
    suspend fun replaceLibrary(
        artists: List<ArtistEntity>,
        albums: List<AlbumEntity>,
        tracks: List<TrackEntity>,
    ) {
        // Handled by individual DAOs in repository
    }
}
