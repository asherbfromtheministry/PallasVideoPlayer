package com.vizvag.shieldvideo.data.iptv

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Smart M3U ↔ XMLTV channel matching for IPTV names that rarely equal EPG display names
 * (e.g. `UK: FHD BBC ONE` → `BBC One` / `BBC1.uk`).
 *
 * Pure heuristics — no network / API key. High-confidence hits are safe to persist;
 * lower scores stay as Assign-EPG suggestions.
 */
object EpgChannelMatcher {

    data class Match(
        val epg: EpgChannelEntry,
        val score: Int,
        val reason: String
    )

    /** Minimum score to auto-assign without asking. */
    const val AUTO_ASSIGN_MIN_SCORE = 86

    /** Best must beat runner-up by at least this many points. */
    const val AUTO_ASSIGN_MIN_GAP = 8

    fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.US).replace('&', ' ')
        // Preserve timeshift before stripping "+": "Film4 +1" / "film4.uk.plus1" → token "plus1"
        s = PLUS_ONE_MARKERS.replace(s) { " plus1 " }
        s = s.replace('+', ' ')
        // Country / region prefix: "UK:", "US -", "DE|", or "UK FHD …"
        s = COUNTRY_PREFIX.replace(s, "")
        s = COUNTRY_QUALITY_PREFIX.replace(s, "")
        // Quality / codec / resolution noise
        s = QUALITY_TOKEN.replace(s, " ")
        // Parenthetical junk: "(backup)", "[FHD]"
        s = PAREN_BLOCK.replace(s, " ")
        s = BRACKET_BLOCK.replace(s, " ")
        // Punctuation → space
        s = NON_ALNUM.replace(s, " ")
        s = WHITESPACE.replace(s, " ").trim()
        // Compact channel-number forms: bbc1 → bbc one, itv2 → itv 2, channel4 → channel 4
        // Skip when the digit is part of plus1 (already a dedicated token).
        s = CHANNEL_NUMBER_COMPACT.replace(s) { m ->
            "${m.groupValues[1]} ${m.groupValues[2]}"
        }
        return WHITESPACE.replace(s, " ").trim()
    }

    /** True when the name/id is a +1 / timeshift feed (one hour behind). */
    fun isPlus1(raw: String): Boolean {
        if (raw.isBlank()) return false
        return PLUS_ONE_DETECT.containsMatchIn(raw.lowercase(Locale.US)) ||
            "plus1" in tokens(normalize(raw))
    }

    /**
     * Preferred ISO-ish country codes from playlist group + channel name
     * (e.g. group "New Zealand" → nz; "UK: Film4" → uk).
     */
    fun preferredCountries(group: String?, channelName: String?): Set<String> {
        val out = LinkedHashSet<String>()
        fun absorb(raw: String?) {
            if (raw.isNullOrBlank()) return
            val lower = raw.lowercase(Locale.US)
            // Multi-word aliases first
            COUNTRY_PHRASES.forEach { (phrase, code) ->
                if (lower.contains(phrase)) out += code
            }
            // Bare tokens / dotted segments
            val bits = lower.split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() }
            bits.forEach { bit ->
                COUNTRY_CODES[bit]?.let { out += it }
            }
            // Leading "UK:" / "NZ -" style prefixes
            COUNTRY_PREFIX_CODE.find(lower)?.groupValues?.getOrNull(1)?.let { code ->
                COUNTRY_CODES[code]?.let { out += it }
            }
        }
        absorb(group)
        absorb(channelName)
        return out
    }

    /** Country codes embedded in an XMLTV id like `sky_sports.nz` or `film4.uk.plus1`. */
    fun countriesFromEpgId(epgId: String): Set<String> {
        if (epgId.isBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        epgId.lowercase(Locale.US).split('.', '_', '-', ' ').forEach { bit ->
            if (bit == "plus1" || bit == "plus") return@forEach
            COUNTRY_CODES[bit]?.let { out += it }
        }
        return out
    }

    fun compact(normalized: String): String =
        normalized.replace(" ", "")

    /**
     * Rank EPG entries for [channel]. Empty when the EPG index is empty.
     * Prefer [EpgRankIndex] when matching many channels — avoids re-normalising the
     * whole XMLTV list on every call (that is what made AI match appear stuck at 0/N).
     */
    fun rank(
        channel: IptvChannel,
        epgChannels: List<EpgChannelEntry>,
        limit: Int = 40
    ): List<Match> {
        if (epgChannels.isEmpty()) return emptyList()
        return EpgRankIndex(epgChannels).rank(channel, limit)
    }

    fun bestAutoAssign(
        channel: IptvChannel,
        epgChannels: List<EpgChannelEntry>
    ): Match? {
        if (epgChannels.isEmpty()) return null
        return EpgRankIndex(epgChannels).bestAutoAssign(channel)
    }

    fun autoMatchAll(
        channels: List<IptvChannel>,
        epgChannels: List<EpgChannelEntry>,
        alreadyMapped: (IptvChannel) -> Boolean,
    ): Map<IptvChannel, Match> = autoMatchAll(channels, epgChannels, alreadyMapped, onProgress = null)

    /**
     * Auto-match every channel that still lacks programme data via tvg-id / existing override.
     * [alreadyMapped] returns true when an override or working tvg-id already exists.
     */
    fun autoMatchAll(
        channels: List<IptvChannel>,
        epgChannels: List<EpgChannelEntry>,
        alreadyMapped: (IptvChannel) -> Boolean,
        onProgress: ((done: Int, total: Int, channel: IptvChannel, match: Match?) -> Unit)?,
    ): Map<IptvChannel, Match> {
        if (epgChannels.isEmpty()) return emptyMap()
        val index = EpgRankIndex(epgChannels)
        val usedEpgIds = HashSet<String>()
        val out = LinkedHashMap<IptvChannel, Match>()
        val work = channels.filterNot(alreadyMapped)
        work.forEachIndexed { i, ch ->
            val match = index.bestAutoAssign(ch)
            val accepted = if (match != null) {
                val idKey = match.epg.id.lowercase(Locale.US)
                if (idKey in usedEpgIds) null
                else {
                    usedEpgIds += idKey
                    out[ch] = match
                    match
                }
            } else null
            onProgress?.invoke(i + 1, work.size, ch, accepted)
        }
        return out
    }

    /**
     * Pre-normalised XMLTV index for fast repeated ranking (AI batches / bulk local match).
     */
    class EpgRankIndex(epgChannels: List<EpgChannelEntry>) {
        private class Row(
            val epg: EpgChannelEntry,
            val norm: String,
            val compact: String,
            val tokens: List<String>,
            val idNorm: String,
            val idCompact: String,
            val plus1: Boolean,
            val countries: Set<String>,
        )

        private val rows: Array<Row>
        private val tokenIndex: Map<String, IntArray>

        init {
            val built = ArrayList<Row>(epgChannels.size)
            val buckets = HashMap<String, ArrayList<Int>>(epgChannels.size.coerceAtLeast(16))
            epgChannels.forEach { epg ->
                val norm = normalize(epg.name)
                val idNorm = normalize(epg.id.replace('.', ' ').replace('_', ' ').replace('-', ' '))
                val row = Row(
                    epg = epg,
                    norm = norm,
                    compact = compact(norm),
                    tokens = tokens(norm),
                    idNorm = idNorm,
                    idCompact = compact(idNorm),
                    plus1 = isPlus1(epg.name) || isPlus1(epg.id),
                    countries = countriesFromEpgId(epg.id),
                )
                val idx = built.size
                built += row
                row.tokens.forEach { t ->
                    buckets.getOrPut(t) { ArrayList(4) }.add(idx)
                }
                tokens(idNorm).forEach { t ->
                    buckets.getOrPut(t) { ArrayList(4) }.add(idx)
                }
            }
            rows = built.toTypedArray()
            tokenIndex = buckets.mapValues { (_, v) -> v.toIntArray() }
        }

        fun rank(channel: IptvChannel, limit: Int = 40): List<Match> {
            if (rows.isEmpty()) return emptyList()
            val queryName = channel.name
            val queryNorm = normalize(queryName)
            val queryCompact = compact(queryNorm)
            val queryTokens = tokens(queryNorm)
            val queryPlus1 = isPlus1(queryName) || "plus1" in queryTokens
            val preferredCountries = preferredCountries(channel.group, channel.name)
            if (queryNorm.isBlank() && channel.tvgId.isNullOrBlank()) return emptyList()

            val candidateIdx = linkedSetOf<Int>()
            // Exact / tvg-id shortcuts still need a broad net when tokens are weak.
            queryTokens.forEach { t ->
                tokenIndex[t]?.forEach { candidateIdx += it }
                NUMBER_WORDS[t]?.let { w -> tokenIndex[w]?.forEach { candidateIdx += it } }
            }
            channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { tvg ->
                val tvgNorm = normalize(tvg)
                rows.forEachIndexed { i, row ->
                    if (row.epg.id.equals(tvg, ignoreCase = true) ||
                        row.norm == tvgNorm ||
                        row.compact == compact(tvgNorm)
                    ) {
                        candidateIdx += i
                    }
                }
            }
            val toScore: IntArray = when {
                candidateIdx.isEmpty() -> IntArray(rows.size) { it }
                candidateIdx.size > MAX_CANDIDATES_SCORED -> {
                    // Too many hits (e.g. token "tv") — keep a capped set, prefer shorter names.
                    candidateIdx
                        .sortedBy { rows[it].norm.length }
                        .take(MAX_CANDIDATES_SCORED)
                        .toIntArray()
                }
                else -> candidateIdx.toIntArray()
            }

            val scored = ArrayList<Match>(min(toScore.size, limit * 4))
            for (i in toScore) {
                val row = rows[i]
                val score = scorePairCached(
                    queryName = queryName,
                    queryNorm = queryNorm,
                    queryCompact = queryCompact,
                    queryTokens = queryTokens,
                    queryTvgId = channel.tvgId,
                    queryPlus1 = queryPlus1,
                    preferredCountries = preferredCountries,
                    row = row,
                )
                if (score.score > 0) scored += score
            }
            scored.sortWith(
                compareByDescending<Match> { it.score }
                    .thenBy { it.epg.name.length }
                    .thenBy { it.epg.name.lowercase(Locale.US) }
            )
            return scored.take(limit)
        }

        fun bestAutoAssign(channel: IptvChannel): Match? {
            val ranked = rank(channel, limit = 3)
            val best = ranked.firstOrNull() ?: return null
            if (best.score < AUTO_ASSIGN_MIN_SCORE) return null
            val second = ranked.getOrNull(1)
            if (second != null && best.score - second.score < AUTO_ASSIGN_MIN_GAP) return null
            // Hard rule: never auto-map +1 ↔ non-+1
            val chPlus = isPlus1(channel.name)
            val epgPlus = isPlus1(best.epg.name) || isPlus1(best.epg.id)
            if (chPlus != epgPlus) return null
            return best
        }

        /**
         * Score for a specific channel↔EPG pair (used to gate AI suggestions).
         * Returns 0 when the pair should be rejected.
         */
        fun scoreFor(channel: IptvChannel, epgId: String): Int {
            val want = epgId.lowercase(Locale.US)
            val row = rows.firstOrNull { it.epg.id.lowercase(Locale.US) == want } ?: return 0
            val queryName = channel.name
            val queryNorm = normalize(queryName)
            val queryTokens = tokens(queryNorm)
            return scorePairCached(
                queryName = queryName,
                queryNorm = queryNorm,
                queryCompact = compact(queryNorm),
                queryTokens = queryTokens,
                queryTvgId = channel.tvgId,
                queryPlus1 = isPlus1(queryName) || "plus1" in queryTokens,
                preferredCountries = preferredCountries(channel.group, channel.name),
                row = row,
            ).score
        }

        private fun scorePairCached(
            queryName: String,
            queryNorm: String,
            queryCompact: String,
            queryTokens: List<String>,
            queryTvgId: String?,
            queryPlus1: Boolean,
            preferredCountries: Set<String>,
            row: Row,
        ): Match {
            val epg = row.epg
            val epgNorm = row.norm
            val epgCompact = row.compact
            val epgTokens = row.tokens
            val epgIdNorm = row.idNorm
            val epgIdCompact = row.idCompact

            val tvg = queryTvgId?.trim().orEmpty()
            if (tvg.isNotEmpty()) {
                if (tvg.equals(epg.id, ignoreCase = true)) {
                    return Match(epg, 100, "tvg-id exact")
                }
                if (normalize(tvg) == epgNorm || compact(normalize(tvg)) == epgCompact) {
                    return Match(epg, 96, "tvg-id name")
                }
            }

            if (queryNorm.isBlank()) return Match(epg, 0, "empty")

            // Hard mismatch: +1 channel must map to +1 EPG and vice versa.
            if (queryPlus1 != row.plus1) {
                return Match(epg, 0, "plus1 mismatch")
            }

            var bonus = 0
            if (queryPlus1 && row.plus1) bonus += 18

            if (preferredCountries.isNotEmpty() && row.countries.isNotEmpty()) {
                if (preferredCountries.any { it in row.countries }) {
                    bonus += 16
                } else {
                    // Same brand in the wrong country — keep as weak candidate for ranking
                    // but never high enough for auto-assign / AI accept.
                    bonus -= 22
                }
            }

            if (queryNorm == epgNorm) {
                return Match(epg, (100 + bonus).coerceIn(0, 100), "exact name")
            }
            if (queryCompact.isNotEmpty() && queryCompact == epgCompact) {
                return Match(epg, (97 + bonus).coerceIn(0, 100), "exact compact")
            }
            if (queryNorm == epgIdNorm || queryCompact == epgIdCompact) {
                return Match(epg, (94 + bonus).coerceIn(0, 100), "exact epg-id")
            }

            if (epgNorm.isNotEmpty() && (queryNorm.contains(epgNorm) || epgNorm.contains(queryNorm))) {
                val shorter = min(queryNorm.length, epgNorm.length)
                val longer = max(queryNorm.length, epgNorm.length)
                val ratio = shorter.toFloat() / longer.toFloat()
                val base = if (ratio >= 0.75f) 90 else if (ratio >= 0.5f) 82 else 70
                return Match(epg, (base + bonus).coerceIn(0, 99), "phrase")
            }

            if (queryTokens.isEmpty() || epgTokens.isEmpty()) {
                val dist = levenshtein(queryCompact, epgCompact)
                val maxLen = max(queryCompact.length, epgCompact.length).coerceAtLeast(1)
                if (dist <= 2 && maxLen >= 5) {
                    return Match(epg, (80 - dist * 3 + bonus).coerceIn(0, 99), "edit distance")
                }
                return Match(epg, 0, "no tokens")
            }

            val matchedQuery = queryTokens.count { q -> tokenMatchesAny(q, epgTokens) }
            val matchedEpg = epgTokens.count { e -> tokenMatchesAny(e, queryTokens) }
            if (matchedQuery == 0) return Match(epg, 0, "no overlap")

            val coverage = matchedQuery.toFloat() / queryTokens.size
            val precision = matchedEpg.toFloat() / epgTokens.size
            var score = (coverage * 55f + precision * 30f).toInt()

            if (matchedQuery == queryTokens.size) {
                score += 18
                val extra = (epgTokens.size - queryTokens.size).coerceAtLeast(0)
                score -= min(12, extra * 3)
            }

            if (queryTokens.any { it in BRAND_TOKENS && it in epgTokens }) {
                score += 6
            }

            val lenDiff = abs(queryCompact.length - epgCompact.length)
            if (lenDiff <= 2) score += 4 else if (lenDiff >= 10) score -= 4

            if (queryCompact.length >= 5 && epgCompact.length >= 5) {
                val dist = levenshtein(queryCompact, epgCompact)
                val limit = if (max(queryCompact.length, epgCompact.length) >= 10) 3 else 2
                if (dist in 1..limit) score += (8 - dist * 2)
            }

            val idTokens = tokens(epgIdNorm)
            if (idTokens.isNotEmpty() && queryTokens.all { q -> tokenMatchesAny(q, idTokens) }) {
                score = max(score, 88)
            }

            score += bonus
            return Match(epg, score.coerceIn(0, 99), "tokens")
        }

        companion object {
            private const val MAX_CANDIDATES_SCORED = 2_500
        }
    }

    private fun tokens(normalized: String): List<String> =
        normalized.split(' ')
            .map { it.trim() }
            .filter { t ->
                t.isNotEmpty() &&
                    t !in STOP_TOKENS &&
                    (t.length > 1 || t.any { c -> c.isDigit() })
            }

    private fun tokenMatchesAny(token: String, candidates: List<String>): Boolean {
        if (candidates.any { it == token }) return true
        // Number synonyms: "1" ↔ "one"
        val expanded = NUMBER_WORDS[token] ?: token
        if (expanded != token && candidates.any { it == expanded }) return true
        val asDigit = NUMBER_WORDS.entries.firstOrNull { it.value == token }?.key
        if (asDigit != null && candidates.any { it == asDigit }) return true
        if (token.length < 5) return false
        return candidates.any { cand ->
            cand.length >= 5 && levenshtein(token, cand) <= fuzzyLimit(token.length, cand.length)
        }
    }

    private fun fuzzyLimit(a: Int, b: Int): Int {
        val len = max(a, b)
        return when {
            len >= 8 -> 2
            len >= 6 -> 1
            else -> 0
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[b.length]
    }

    private val COUNTRY_PREFIX = Regex(
        // "UK:", "US -", "DE|" — not bare "UK Gold" (would steal the brand)
        """^(?:uk|us|usa|gb|de|fr|es|it|nl|be|pt|pl|se|no|dk|fi|ie|au|ca|nz|in|br|mx|ar|ch|at|cz|sk|hu|ro|bg|gr|tr|ae|sa|za|jp|kr|cn|hk|tw|sg|my|th|ph|id|vn|ru|ua|il|eg)\s*[:|\-/]\s*""",
        RegexOption.IGNORE_CASE
    )
    private val COUNTRY_PREFIX_CODE = Regex(
        """^(uk|us|usa|gb|de|fr|es|it|nl|be|pt|pl|se|no|dk|fi|ie|au|ca|nz|in|br|mx|ar|ch|at|cz|sk|hu|ro|bg|gr|tr|ae|sa|za|jp|kr|cn|hk|tw|sg|my|th|ph|id|vn|ru|ua|il|eg)\b""",
        RegexOption.IGNORE_CASE
    )
    /** "UK FHD …" / "US 4K …" — country only when a quality token follows. */
    private val COUNTRY_QUALITY_PREFIX = Regex(
        """^(?:uk|us|usa|gb)\s+(?=(?:fhd|uhd|full\s*hd|4k|8k|hd|sd|hdr)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val QUALITY_TOKEN = Regex(
        """(?<![a-z0-9])(?:fhd|uhd|full\s*hd|4k|8k|2160p?|1080p?|720p?|576p?|480p?|sd|hd|hdr10?\+?|hlg|dolby\s*vision|\bdv\b|hevc|h\.?265|h\.?264|avc|aac|50fps|60fps|25fps|30fps|\d{2,3}\s*(?:fps|hz))(?![a-z0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val PAREN_BLOCK = Regex("""\([^)]*\)""")
    private val BRACKET_BLOCK = Regex("""\[[^\]]*\]""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val CHANNEL_NUMBER_COMPACT = Regex(
        """\b(bbc|itv|s4c|rte|tv|channel|sky)\s*(\d{1,2})\b""",
        RegexOption.IGNORE_CASE
    )
    /** Collapse +1 / plus1 / plus 1 / (+1) into a stable token before other normalisation. */
    private val PLUS_ONE_MARKERS = Regex(
        """(?<![a-z0-9])(?:\+\s*1|plus\s*1|plus1)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val PLUS_ONE_DETECT = Regex(
        """(?<![a-z0-9])(?:\+\s*1|plus\s*1|plus1)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE
    )

    private val STOP_TOKENS = setOf(
        "the", "and", "for", "tv", "ch", "backup", "alt", "feed",
        "live", "stream", "iptv", "raw", "vip"
    )

    private val BRAND_TOKENS = setOf(
        "bbc", "itv", "sky", "dave", "gold", "pick", "quest", "comedy", "universal",
        "paramount", "disney", "national", "geographic", "discovery", "history", "hbo",
        "showtime", "espn", "fox", "cbs", "nbc", "abc", "cw", "mtv", "nickelodeon",
        "cartoon", "network", "e4", "film4", "more4", "4seven", "5star", "5usa",
        "alibi", "yesterday", "drama", "really", "blaze", "tcm", "tnt", "amc"
    )

    private val NUMBER_WORDS = mapOf(
        "1" to "one",
        "2" to "two",
        "3" to "three",
        "4" to "four",
        "5" to "five",
        "6" to "six",
        "7" to "seven",
        "8" to "eight",
        "9" to "nine",
        "10" to "ten",
        "11" to "eleven",
        "12" to "twelve"
    )

    /** Phrase → ISO-ish code used in XMLTV ids (sky_sports.nz). */
    private val COUNTRY_PHRASES = listOf(
        "united kingdom" to "uk",
        "great britain" to "uk",
        "new zealand" to "nz",
        "south africa" to "za",
        "united states" to "us",
        "hong kong" to "hk",
        "saudi arabia" to "sa",
    )

    private val COUNTRY_CODES = mapOf(
        "uk" to "uk", "gb" to "uk", "britain" to "uk",
        "nz" to "nz",
        "za" to "za",
        "au" to "au", "australia" to "au",
        "us" to "us", "usa" to "us",
        "ca" to "ca", "canada" to "ca",
        "ie" to "ie", "ireland" to "ie",
        "de" to "de", "germany" to "de",
        "fr" to "fr", "france" to "fr",
        "es" to "es", "spain" to "es",
        "it" to "it", "italy" to "it",
        "nl" to "nl", "netherlands" to "nl",
        "be" to "be", "belgium" to "be",
        "pt" to "pt", "portugal" to "pt",
        "pl" to "pl", "poland" to "pl",
        "se" to "se", "sweden" to "se",
        "no" to "no", "norway" to "no",
        "dk" to "dk", "denmark" to "dk",
        "fi" to "fi", "finland" to "fi",
        "in" to "in", "india" to "in",
        "br" to "br", "brazil" to "br",
        "mx" to "mx", "mexico" to "mx",
        "ar" to "ar", "argentina" to "ar",
        "ch" to "ch", "switzerland" to "ch",
        "at" to "at", "austria" to "at",
        "cz" to "cz", "sk" to "sk", "hu" to "hu",
        "ro" to "ro", "bg" to "bg", "gr" to "gr",
        "tr" to "tr", "ae" to "ae", "sa" to "sa",
        "jp" to "jp", "kr" to "kr", "cn" to "cn",
        "hk" to "hk", "tw" to "tw", "sg" to "sg",
        "my" to "my", "th" to "th", "ph" to "ph",
        "id" to "id", "vn" to "vn", "ru" to "ru",
        "ua" to "ua", "il" to "il", "eg" to "eg",
    )

    /** Minimum local score for accepting an AI suggestion. */
    const val AI_ACCEPT_MIN_SCORE = 72
}
