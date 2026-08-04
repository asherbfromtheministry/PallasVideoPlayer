package com.vizvag.shieldvideo.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgChannelMatcherTest {

    private val epg = listOf(
        EpgChannelEntry("BBC1.uk", "BBC One"),
        EpgChannelEntry("BBC2.uk", "BBC Two"),
        EpgChannelEntry("BBC4.uk", "BBC Four"),
        EpgChannelEntry("ITV1.uk", "ITV1"),
        EpgChannelEntry("Channel4.uk", "Channel 4"),
        EpgChannelEntry("SkySpMainEvent.uk", "Sky Sports Main Event"),
        EpgChannelEntry("HBO.us", "HBO"),
        EpgChannelEntry("BBC1.yorkshire.uk", "BBC One Yorkshire"),
        EpgChannelEntry("film4.uk", "Film4"),
        EpgChannelEntry("film4.uk.plus1", "Film4 +1"),
        EpgChannelEntry("sky_sports.uk", "Sky Sports"),
        EpgChannelEntry("sky_sports.nz", "Sky Sports"),
        EpgChannelEntry("sky_sports.za", "Sky Sports"),
    )

    private fun channel(name: String, group: String = "UK", tvgId: String? = null) = IptvChannel(
        id = "id-$name",
        name = name,
        logoUrl = null,
        group = group,
        streamUrl = "http://example/x",
        tvgId = tvgId
    )

    @Test
    fun normalizesUkFhdBbcOne() {
        assertEquals("bbc one", EpgChannelMatcher.normalize("UK: FHD BBC ONE"))
        assertEquals("bbc one", EpgChannelMatcher.normalize("UK FHD BBC One HD"))
        assertEquals("bbc 1", EpgChannelMatcher.normalize("BBC1"))
    }

    @Test
    fun normalizesPlus1AsToken() {
        assertEquals("film4 plus1", EpgChannelMatcher.normalize("UK: Film4 +1"))
        assertEquals("film4 uk plus1", EpgChannelMatcher.normalize("film4.uk.plus1"))
        assertTrue(EpgChannelMatcher.isPlus1("UK: Film4 +1"))
        assertTrue(EpgChannelMatcher.isPlus1("film4.uk.plus1"))
        assertFalse(EpgChannelMatcher.isPlus1("UK: Film4"))
    }

    @Test
    fun matchesUkFhdBbcOne() {
        val match = EpgChannelMatcher.bestAutoAssign(channel("UK: FHD BBC ONE"), epg)
        assertNotNull(match)
        assertEquals("BBC1.uk", match!!.epg.id)
        assertTrue(match.score >= EpgChannelMatcher.AUTO_ASSIGN_MIN_SCORE)
    }

    @Test
    fun prefersBbcOneOverRegional() {
        val ranked = EpgChannelMatcher.rank(channel("BBC ONE HD"), epg, limit = 5)
        assertEquals("BBC1.uk", ranked.first().epg.id)
    }

    @Test
    fun matchesChannel4() {
        val match = EpgChannelMatcher.bestAutoAssign(channel("UK: FHD Channel 4"), epg)
        assertNotNull(match)
        assertEquals("Channel4.uk", match!!.epg.id)
    }

    @Test
    fun matchesSkySports() {
        val match = EpgChannelMatcher.bestAutoAssign(
            channel("UK: FHD Sky Sports Main Event"),
            epg
        )
        assertNotNull(match)
        assertEquals("SkySpMainEvent.uk", match!!.epg.id)
    }

    @Test
    fun plus1MatchesPlus1EpgNotMain() {
        val ranked = EpgChannelMatcher.rank(
            channel("UK: Film4 +1", group = "UK Entertainment"),
            epg,
            limit = 5
        )
        assertTrue(ranked.isNotEmpty())
        assertEquals("film4.uk.plus1", ranked.first().epg.id)
        val match = EpgChannelMatcher.bestAutoAssign(
            channel("UK: Film4 +1", group = "UK Entertainment"),
            epg
        )
        assertNotNull(match)
        assertEquals("film4.uk.plus1", match!!.epg.id)
    }

    @Test
    fun mainFilm4DoesNotMatchPlus1() {
        val ranked = EpgChannelMatcher.rank(
            channel("UK: Film4", group = "UK Entertainment"),
            epg,
            limit = 5
        )
        assertEquals("film4.uk", ranked.first().epg.id)
    }

    @Test
    fun newZealandGroupPrefersNzEpgId() {
        val ranked = EpgChannelMatcher.rank(
            channel("Sky Sports", group = "New Zealand"),
            epg,
            limit = 5
        )
        assertEquals("sky_sports.nz", ranked.first().epg.id)
    }

    @Test
    fun ukGroupPrefersUkEpgIdOverNzZa() {
        val ranked = EpgChannelMatcher.rank(
            channel("Sky Sports", group = "UK Entertainment"),
            epg,
            limit = 5
        )
        assertEquals("sky_sports.uk", ranked.first().epg.id)
    }

    @Test
    fun preferredCountriesFromGroup() {
        assertEquals(setOf("nz"), EpgChannelMatcher.preferredCountries("New Zealand", null))
        assertTrue(EpgChannelMatcher.preferredCountries("UK Entertainment", null).contains("uk"))
        assertEquals(setOf("za"), EpgChannelMatcher.preferredCountries("South Africa", null))
        assertEquals(setOf("uk"), EpgChannelMatcher.countriesFromEpgId("film4.uk.plus1"))
        assertEquals(setOf("nz"), EpgChannelMatcher.countriesFromEpgId("sky_sports.nz"))
    }

    @Test
    fun tvgIdExactWins() {
        val match = EpgChannelMatcher.bestAutoAssign(
            channel("Something Weird", tvgId = "BBC2.uk"),
            epg
        )
        assertNotNull(match)
        assertEquals("BBC2.uk", match!!.epg.id)
        assertEquals(100, match.score)
    }

    @Test
    fun ambiguousDoesNotAutoAssign() {
        val match = EpgChannelMatcher.bestAutoAssign(channel("BBC"), epg)
        if (match != null) {
            val ranked = EpgChannelMatcher.rank(channel("BBC"), epg, limit = 3)
            assertTrue(
                ranked.size < 2 ||
                    ranked[0].score - ranked[1].score >= EpgChannelMatcher.AUTO_ASSIGN_MIN_GAP
            )
        }
    }

    @Test
    fun autoMatchAllSkipsAlreadyMapped() {
        val channels = listOf(
            channel("UK: FHD BBC ONE"),
            channel("UK: FHD BBC TWO")
        )
        val mapped = EpgChannelMatcher.autoMatchAll(channels, epg) { it.name.contains("TWO") }
        assertEquals(1, mapped.size)
        assertEquals("BBC1.uk", mapped.values.first().epg.id)
    }

    @Test
    fun doesNotStripUkFromUkGold() {
        assertEquals("uk gold", EpgChannelMatcher.normalize("UK Gold"))
    }

    @Test
    fun noMatchWhenEpgEmpty() {
        assertNull(EpgChannelMatcher.bestAutoAssign(channel("UK: FHD BBC ONE"), emptyList()))
    }
}
