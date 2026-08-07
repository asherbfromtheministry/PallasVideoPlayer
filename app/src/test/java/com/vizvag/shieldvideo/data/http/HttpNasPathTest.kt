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

    @Test
    fun stripSharePrefixKeepsFolderNamedLikeShareDifferentCase() {
        // Share `docs` + folder `Docs` must not collapse to share root.
        assertEquals("Docs", HttpNasRepository.stripSharePrefix("Docs", "docs"))
        assertEquals("Docs", HttpNasRepository.stripSharePrefix("docs/Docs", "docs"))
        assertEquals("Docs/Reports", HttpNasRepository.stripSharePrefix("docs/Docs/Reports", "Docs"))
    }

    @Test
    fun absoluteSharePathKeepsDocsFolderUnderDocsShare() {
        // Playback used to strip Docs/ case-insensitively → /docs/file.mkv → FS 408.
        assertEquals(
            "/docs/Docs/clip.mkv",
            HttpNasRepository.absoluteSharePath("docs", "Docs/clip.mkv"),
        )
        assertEquals(
            "/docs/Docs",
            HttpNasRepository.absoluteSharePath("docs", "Docs"),
        )
        assertEquals(
            "/download/Show/S01/ep.mkv",
            HttpNasRepository.absoluteSharePath("download", "Show/S01/ep.mkv"),
        )
        // Exact duplicated share prefix still peeled.
        assertEquals(
            "/download/Show",
            HttpNasRepository.absoluteSharePath("download", "download/Show"),
        )
    }
}
