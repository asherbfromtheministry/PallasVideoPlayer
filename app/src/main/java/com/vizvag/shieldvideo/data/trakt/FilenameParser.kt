package com.vizvag.shieldvideo.data.trakt

enum class MediaKind { MOVIE, EPISODE, UNKNOWN }

data class ParsedMediaQuery(
    val searchQuery: String,
    val kind: MediaKind,
    val year: Int? = null,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null
)

data class QualityTags(
    val resolutionLabel: String? = null,
    val isHdr: Boolean = false,
    /** e.g. "60fps" when the filename declares a frame rate. */
    val fpsLabel: String? = null
)

object FilenameParser {
    private val episodeRegex = Regex(
        """^(?:(?<title>.+?)[.\s_-]+)?[Ss](?<season>\d{1,2})[Ee](?<episode>\d{1,3})(?:[.\s_-]+(?<epname>.+))?$""",
        RegexOption.IGNORE_CASE
    )
    /** Season pack folders: Title.S01.COMPLETE.1080p… (no episode number). */
    private val seasonPackRegex = Regex(
        """^(?:(?<title>.+?)[.\s_-]+)[Ss](?<season>\d{1,2})(?![Ee\d])(?:[.\s_-].*)?$""",
        RegexOption.IGNORE_CASE
    )
    /** Title.Season.1 / Title Season 01 Complete… */
    private val seasonWordPackRegex = Regex(
        """^(?:(?<title>.+?)[.\s_-]+)Season[.\s_-]*(?<season>\d{1,2})(?:[.\s_-].*)?$""",
        RegexOption.IGNORE_CASE
    )
    /** Title.S01-S03… multi-season packs */
    private val seasonRangePackRegex = Regex(
        """^(?:(?<title>.+?)[.\s_-]+)[Ss](?<season>\d{1,2})\s*[-–]\s*[Ss]?\d{1,2}(?:[.\s_-].*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val movieRegex = Regex(
        """^(?<title>.+?)[.\s_(\[-]+(?<year>19\d{2}|20\d{2})(?:[.\s_)\]-].*)?$"""
    )
    private val yearToken = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val junk = Regex(
        """\b(1080p|720p|2160p|4k|uhd|bluray|webrip|web-dl|webdl|web|hdr10\+?|hdr|dolby[\s._-]?vision|dovi|hlg|x264|x265|hevc|h\.?264|h\.?265|avc|av1|aac|dts(?:[- ]?hd(?:[- ]?ma)?)?|truehd|atmos|eac3|ac3|ddp\d*|flac|opus|proper|repack|extended|directors\.cut|complete|pack|multi|remux|internal|amzn|nf|hulu|dsnp|atvp|hmax|monolith|rarbg|yify|yts|sparks|ntb|flux|cmrg|mkv|mp4)\b""",
        RegexOption.IGNORE_CASE
    )
    private val sceneGroupSuffix = Regex("""\s*-\s*[A-Za-z0-9]{2,15}\s*$""")
    private val knownVideoExt = Regex("""(?i)\.(mkv|mp4|avi|m4v|mov|wmv|ts|m2ts|mpg|mpeg|iso|img)$""")
    private val uhdRegex = Regex(
        """\b(2160p|3840\s*[x×]\s*2160|4k|uhd|ultra[\s._-]?hd)\b""",
        RegexOption.IGNORE_CASE
    )
    private val p1080Regex = Regex(
        """\b(1080p|1920\s*[x×]\s*1080|full[\s._-]?hd|fhd)\b""",
        RegexOption.IGNORE_CASE
    )
    private val p720Regex = Regex(
        """\b(720p|1280\s*[x×]\s*720|hdready)\b""",
        RegexOption.IGNORE_CASE
    )
    private val hdrRegex = Regex(
        """\b(hdr10\+?|hdr|hlg|dolby[\s._-]?vision|dovi)\b|(?<=[.\s_\(\[-])dv(?=[.\s_\)\]-])""",
        RegexOption.IGNORE_CASE
    )
    private val fpsRegex = Regex(
        """(?<![0-9])(\d{2,3})\s*fps\b""",
        RegexOption.IGNORE_CASE
    )

    fun qualityTags(vararg sources: String): QualityTags {
        val haystack = sources.joinToString(" ")
        val resolution = when {
            uhdRegex.containsMatchIn(haystack) -> "4K"
            p1080Regex.containsMatchIn(haystack) -> "1080p"
            p720Regex.containsMatchIn(haystack) -> "720p"
            else -> null
        }
        return QualityTags(
            resolutionLabel = resolution,
            isHdr = hdrRegex.containsMatchIn(haystack),
            fpsLabel = fpsRegex.find(haystack)?.let { "${it.groupValues[1]}fps" }
        )
    }

    /**
     * @param fullPath relative path within the share (e.g. `Flight of the Conchords/S01E01.mkv`)
     *                 so parent folder can supply the show title when the filename omits it.
     */
    fun parse(fileName: String, fullPath: String = fileName): ParsedMediaQuery {
        val base = stripFileExtension(fileName)
            .replace('_', ' ')
            .trim()
        return parseBase(base, fullPath)
    }

    /**
     * Folder / season-pack names keep every dotted segment (no "extension" strip).
     * e.g. `Show.Name.S01.COMPLETE.1080p.WEB-DL…`
     */
    fun parseFolder(folderName: String, fullPath: String = folderName): ParsedMediaQuery {
        val base = folderName.replace('_', ' ').trim()
        return parseBase(base, fullPath)
    }

    private fun parseBase(base: String, fullPath: String): ParsedMediaQuery {
        val parentFolder = parentFolderName(fullPath)

        episodeRegex.find(base)?.let { match ->
            var title = cleanTitle(match.groups["title"]?.value.orEmpty())
            title = stripYears(title)
            if (title.isBlank() && !parentFolder.isNullOrBlank()) {
                title = cleanTitle(parentFolder)
            }
            val season = match.groups["season"]?.value?.toIntOrNull()
            val episode = match.groups["episode"]?.value?.toIntOrNull()
            val epName = match.groups["epname"]?.value?.let { cleanEpisodeTitle(it) }
            return ParsedMediaQuery(
                searchQuery = title.ifBlank { cleanTitle(base) },
                kind = MediaKind.EPISODE,
                showTitle = title.ifBlank { null },
                season = season,
                episode = episode,
                episodeTitle = epName
            )
        }

        seasonPackRegex.find(base)?.let { match ->
            seasonPackResult(match, parentFolder)?.let { return it }
        }
        seasonRangePackRegex.find(base)?.let { match ->
            seasonPackResult(match, parentFolder)?.let { return it }
        }
        seasonWordPackRegex.find(base)?.let { match ->
            seasonPackResult(match, parentFolder)?.let { return it }
        }

        movieRegex.find(base)?.let { match ->
            val title = cleanTitle(match.groups["title"]?.value.orEmpty())
            val year = match.groups["year"]?.value?.toIntOrNull()
            return ParsedMediaQuery(
                searchQuery = title,
                kind = MediaKind.MOVIE,
                year = year
            )
        }

        return ParsedMediaQuery(
            searchQuery = cleanTitle(base).ifBlank { parentFolder?.let { cleanTitle(it) }.orEmpty() },
            kind = MediaKind.UNKNOWN
        )
    }

    private fun seasonPackResult(
        match: MatchResult,
        parentFolder: String?,
    ): ParsedMediaQuery? {
        var title = cleanTitle(match.groups["title"]?.value.orEmpty())
        title = stripYears(title)
        if (title.isBlank() && !parentFolder.isNullOrBlank()) {
            title = cleanTitle(parentFolder)
        }
        if (title.isBlank()) return null
        val season = match.groups["season"]?.value?.toIntOrNull()
        return ParsedMediaQuery(
            searchQuery = title,
            kind = MediaKind.EPISODE,
            showTitle = title,
            season = season,
        )
    }

    /** Only strip real video extensions — never dotted scene tags like `.H.264-CMRG`. */
    private fun stripFileExtension(fileName: String): String {
        val trimmed = fileName.trim()
        return if (knownVideoExt.containsMatchIn(trimmed)) {
            trimmed.substringBeforeLast('.')
        } else {
            trimmed
        }
    }

    private fun parentFolderName(fullPath: String): String? {
        val parts = fullPath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        return if (parts.size >= 2) parts[parts.lastIndex - 1] else null
    }

    private fun stripYears(raw: String): String =
        raw.replace(yearToken, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Episode name after SxxExx — strip release tags / scene groups, keep the real title. */
    private fun cleanEpisodeTitle(raw: String): String? {
        var t = raw.trim()
        // Drop trailing release brackets: (1080p AMZN …), [WEB-DL], etc.
        t = t.replace(Regex("""[\(\[][^\)\]]*(1080p|720p|2160p|4k|web|bluray|amzn|nf|x26|hevc|hdr)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(sceneGroupSuffix, " ")
        t = cleanTitle(stripYears(t))
        // Leftover empty parens from junk stripping
        t = t.replace(Regex("""\(\s*\)|\[\s*]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', ' ', '.')
        return t.takeIf { it.isNotBlank() && it.length >= 2 }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace('.', ' ').replace('_', ' ')
        t = t.replace(sceneGroupSuffix, " ")
        // Channel layouts left after DDP5.1 → "DDP5 1"
        t = t.replace(Regex("""(?i)\bddp\d+(?:\s+\d)?\b"""), " ")
        t = t.replace(junk, " ")
        return t.replace(Regex("""\s+"""), " ").trim()
    }
}
