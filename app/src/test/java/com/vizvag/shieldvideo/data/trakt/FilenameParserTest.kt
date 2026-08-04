package com.vizvag.shieldvideo.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameParserTest {
    @Test
    fun episodeUsesShowFolderNotSeasonFolder() {
        val parsed = FilenameParser.parse(
            "S01E01.mkv",
            "The Musketeers/Season 01/S01E01.mkv",
        )
        assertEquals(MediaKind.EPISODE, parsed.kind)
        assertEquals("The Musketeers", parsed.searchQuery)
        assertEquals("The Musketeers", parsed.showTitle)
        assertEquals(1, parsed.season)
        assertEquals(1, parsed.episode)
    }

    @Test
    fun episodeUsesShowFolderAboveSxxFolder() {
        val parsed = FilenameParser.parse(
            "Show.Name.S02E05.1080p.mkv",
            "Show.Name/S02/Show.Name.S02E05.1080p.mkv",
        )
        assertEquals(MediaKind.EPISODE, parsed.kind)
        assertEquals("Show Name", parsed.showTitle)
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun seasonOrJunkFolderNames() {
        assertTrue(FilenameParser.isSeasonOrJunkFolderName("S01"))
        assertTrue(FilenameParser.isSeasonOrJunkFolderName("Season 01"))
        assertTrue(FilenameParser.isSeasonOrJunkFolderName("Season.1"))
        assertTrue(FilenameParser.isSeasonOrJunkFolderName("Disc 2"))
        assertTrue(FilenameParser.isSeasonOrJunkFolderName("Extras"))
        assertFalse(FilenameParser.isSeasonOrJunkFolderName("The Musketeers"))
        assertFalse(FilenameParser.isSeasonOrJunkFolderName("The.Musketeers.S01.COMPLETE"))
    }

    @Test
    fun seasonPackFolderStillParsesShowTitle() {
        val parsed = FilenameParser.parseFolder(
            "The.Musketeers.S01.COMPLETE.1080p.WEB-DL",
            "The.Musketeers.S01.COMPLETE.1080p.WEB-DL",
        )
        assertEquals(MediaKind.EPISODE, parsed.kind)
        assertEquals("The Musketeers", parsed.searchQuery)
        assertEquals(1, parsed.season)
    }
}
