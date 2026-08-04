package com.vizvag.shieldvideo.data.http

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpNasPathTest {
    @Test
    fun stripSharePrefixIsCaseInsensitive() {
        assertEquals(
            "Show Name/S01",
            HttpNasRepository.stripSharePrefix("Download/Show Name/S01", "download"),
        )
        assertEquals(
            "Show Name",
            HttpNasRepository.stripSharePrefix("/download/Show Name", "Download"),
        )
        assertEquals("", HttpNasRepository.stripSharePrefix("download", "download"))
        assertEquals(
            "already/relative",
            HttpNasRepository.stripSharePrefix("already/relative", "download"),
        )
    }
}
