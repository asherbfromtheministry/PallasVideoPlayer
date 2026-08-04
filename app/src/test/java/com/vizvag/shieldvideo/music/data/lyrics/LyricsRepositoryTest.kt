package com.vizvag.shieldvideo.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {
    @Test
    fun spacingVariants_includeDoubleSpaceAfterTrackNumber() {
        val variants = LyricsRepository.filenameSpacingVariants("02 Adele - Best For Last.lrc")
        assertTrue(variants.contains("02  Adele - Best For Last.lrc"))
        assertTrue(variants.contains("02 Adele - Best For Last.lrc"))
    }

    @Test
    fun pathVariants_includeDoubleSpaceLrcNextToAudio() {
        val audio = "/music/#Pop/Adele/Adele - 19/02 Adele - Best For Last.mp3"
        val lrc = audio.substringBeforeLast('.') + ".lrc"
        val variants = LyricsRepository.pathVariants(lrc)
        assertTrue(
            "expected double-space lrc path in $variants",
            variants.any { it.endsWith("02  Adele - Best For Last.lrc") },
        )
        assertTrue(variants.any { it.contains("#Pop") })
    }

    @Test
    fun findBestLrc_matchesDoubleSpaceSibling() {
        val files = listOf(
            "01  Adele - Daydreamer.lrc" to "/music/#Pop/Adele/Adele - 19/01  Adele - Daydreamer.lrc",
            "02  Adele - Best For Last.lrc" to "/music/#Pop/Adele/Adele - 19/02  Adele - Best For Last.lrc",
        )
        val match = LyricsRepository.findBestLrc(
            files = files,
            audioStem = "02 Adele - Best For Last",
            trackTitle = "Best For Last",
        )
        assertEquals(
            "/music/#Pop/Adele/Adele - 19/02  Adele - Best For Last.lrc",
            match,
        )
    }

    @Test
    fun lrcParser_readsTimedLines() {
        val content = """
            [00:12.79] Wait, do you see my heart on my sleeve?
            [00:17.46] It's been there for days on end and
            [ti:Best For Last]
        """.trimIndent()
        val lines = LrcParser.parse(content)
        assertEquals(2, lines.size)
        assertEquals("Wait, do you see my heart on my sleeve?", lines[0].text)
        assertEquals(12_790L, lines[0].timeMs)
        assertEquals(-1, LrcParser.currentLineIndex(lines, 12_000L))
        assertEquals(0, LrcParser.currentLineIndex(lines, 12_790L))
        assertEquals(0, LrcParser.currentLineIndex(lines, 15_000L))
        assertEquals(1, LrcParser.currentLineIndex(lines, 17_460L))
    }

    @Test
    fun plainToTimedLines_spreadsAcrossDuration() {
        val lines = OnlineLyricsClient.plainToTimedLines("One\nTwo\nThree", 9_000L)
        assertEquals(3, lines.size)
        assertEquals("One", lines[0].text)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(3_000L, lines[1].timeMs)
        assertEquals(6_000L, lines[2].timeMs)
    }
}
