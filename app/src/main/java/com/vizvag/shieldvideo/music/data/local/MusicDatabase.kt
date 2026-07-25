package com.vizvag.shieldvideo.music.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        LibraryIndexStateEntity::class,
        PlayHistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun libraryIndexDao(): LibraryIndexDao
    abstract fun playHistoryDao(): PlayHistoryDao
}
