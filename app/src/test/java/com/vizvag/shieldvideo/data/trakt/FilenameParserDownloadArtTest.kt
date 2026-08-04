package com.vizvag.shieldvideo.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameParserDownloadArtTest {
    @Test
    fun criminalRecordSeasonPack() {
        val name = "Criminal.Record.S01.COMPLETE.1080p.ATVP.WEB-DL.DDP5.1.Atmos.H.264-CMRG"
        val parsed = FilenameParser.parseFolder(name, name)
        assertEquals(MediaKind.EPISODE, parsed.kind)
        assertEquals("Criminal Record", parsed.searchQuery)
        assertEquals(1, parsed.season)
    }

    @Test
    fun queenOfTheSouth() {
        val name = "Queen of the South S01"
        val parsed = FilenameParser.parseFolder(name, name)
        println("queen => kind=${parsed.kind} q=${parsed.searchQuery} s=${parsed.season}")
        assertTrue(parsed.searchQuery.contains("Queen", ignoreCase = true))
    }

    @Test
    fun lastWeekTonight() {
        val name = "Last Week Tonight with John Oliver"
        val parsed = FilenameParser.parseFolder(name, name)
        println("lwt => kind=${parsed.kind} q=${parsed.searchQuery}")
        assertEquals("Last Week Tonight with John Oliver", parsed.searchQuery)
    }
}
