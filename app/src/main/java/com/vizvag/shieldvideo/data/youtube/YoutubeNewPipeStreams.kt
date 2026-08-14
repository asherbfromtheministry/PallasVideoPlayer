package com.vizvag.shieldvideo.data.youtube

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

object YoutubeNewPipeStreams {
    @Throws(YoutubeApiException::class)
    fun fetch(videoId: String): YoutubeStreamInfo {
        val linkHandler = ServiceList.YouTube.getStreamLHFactory().fromId(videoId)
        val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler) as YoutubeStreamExtractor
        try {
            extractor.fetchPage()
        } catch (e: ExtractionException) {
            throw YoutubeApiException(e.message ?: "NewPipe extraction failed")
        }

        val title = extractor.name.orEmpty().ifBlank { "YouTube" }
        val uploader = extractor.uploaderName.orEmpty()
        val thumb = extractor.thumbnails.firstOrNull()?.url.orEmpty()
        val durationSec = extractor.length.coerceAtLeast(0L)
        val description = extractor.description?.content.orEmpty()
        val livestream = extractor.streamType == StreamType.LIVE_STREAM

        val videoOnly = runCatching { extractor.videoOnlyStreams }.getOrDefault(emptyList())
        val audios = runCatching { extractor.audioStreams }.getOrDefault(emptyList())
        val muxed = runCatching { extractor.videoStreams }.getOrDefault(emptyList())
        val dashUrl = runCatching { extractor.dashMpdUrl }.getOrNull()?.takeIf { it.startsWith("http") }
        val hlsUrl = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.startsWith("http") }

        val options = buildPlaybackOptions(
            dashUrl = dashUrl,
            hlsUrl = hlsUrl,
            videoOnly = videoOnly,
            audios = audios,
            muxed = muxed,
        )
        val playback = options.firstOrNull()
            ?: throw YoutubeApiException("No playable formats from NewPipe")

        val qualities = buildQualityOptions(videoOnly, audios)
        val maxH = maxOf(
            qualities.maxOfOrNull { it.height } ?: 0,
            videoOnly.maxOfOrNull { it.height } ?: 0,
            muxed.maxOfOrNull { it.height } ?: 0,
        )

        return YoutubeStreamInfo(
            id = videoId,
            title = title,
            uploader = uploader,
            thumbnailUrl = thumb,
            durationSec = durationSec,
            description = description,
            livestream = livestream,
            related = emptyList(),
            playback = playback,
            playbackFallbacks = options.drop(1),
            channelId = runCatching { extractor.uploaderUrl }.getOrNull()
                ?.let { YoutubeDefaults.channelIdFromUrl(it) }.orEmpty(),
            maxHeight = maxH,
            qualities = qualities,
            playbackUserAgent = NewPipeDownloader.USER_AGENT,
        )
    }

    private fun buildPlaybackOptions(
        dashUrl: String?,
        hlsUrl: String?,
        videoOnly: List<VideoStream>,
        audios: List<AudioStream>,
        muxed: List<VideoStream>,
    ): List<YoutubePlayback> {
        val options = mutableListOf<YoutubePlayback>()

        val bestVideo = videoOnly
            .filter { !isAv1(it) }
            .maxWithOrNull(
                compareBy<VideoStream> { it.height }
                    .thenBy { when { isVp9(it) -> 2; isAvc(it) -> 1; else -> 0 } }
                    .thenBy { streamBitrate(it) },
            )

        val labeledAudios = audios.any { hasAudioLanguageHint(it) }

        // When YouTube omits audioTrack metadata (common), SeparateTracks can't prefer English —
        // put HLS/DASH first so ExoPlayer setPreferredAudioLanguage("en") can choose.
        if (!labeledAudios) {
            if (hlsUrl != null) options += YoutubePlayback.Hls(hlsUrl)
            if (dashUrl != null) options += YoutubePlayback.Dash(dashUrl)
        }

        // SeparateTracks with pot= — high-res and quality switchable.
        if (bestVideo != null) {
            val preferAac = isAvc(bestVideo) ||
                bestVideo.format?.mimeType?.contains("mp4") == true
            val audio = pickPreferredAudio(audios, preferAac = preferAac)
            if (audio != null) {
                android.util.Log.i(
                    "YoutubeStreams",
                    "audio pick lang=${audio.audioLocale?.language} " +
                        "id=${audio.audioTrackId} type=${audio.audioTrackType} " +
                        "name=${audio.audioTrackName} " +
                        "hints=${audioLanguageHints(audio)} " +
                        "labeled=$labeledAudios among=${audios.size} " +
                        "all=[${audios.joinToString { a ->
                            audioLanguageHints(a).ifBlank { "?" }
                        }}]",
                )
                options += YoutubePlayback.SeparateTracks(
                    videoUrl = bestVideo.content,
                    audioUrl = audio.content,
                    videoMime = bestVideo.format?.mimeType,
                    audioMime = audio.format?.mimeType,
                )
            }
        }

        if (labeledAudios) {
            if (hlsUrl != null) options += YoutubePlayback.Hls(hlsUrl)
            if (dashUrl != null) options += YoutubePlayback.Dash(dashUrl)
        }

        // Progressive muxed — lower res, still useful fallback.
        muxed
            .filter { !isAv1(it) }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { streamBitrate(it) })
            ?.let {
                options += YoutubePlayback.Progressive(it.content, it.format?.mimeType)
            }

        return options.distinct()
    }

    private fun hasAudioLanguageHint(stream: AudioStream): Boolean =
        !stream.audioLocale?.language.isNullOrBlank() ||
            !stream.audioTrackId.isNullOrBlank() ||
            audioLanguageHints(stream).isNotBlank()

    private fun buildQualityOptions(
        videoOnly: List<VideoStream>,
        audios: List<AudioStream>,
    ): List<YoutubeQualityOption> {
        if (videoOnly.isEmpty() || audios.isEmpty()) return emptyList()
        val usable = videoOnly.filter { !isAv1(it) }.ifEmpty { videoOnly }
        return usable
            .groupBy { it.height }
            .mapNotNull { (height, cands) ->
                if (height <= 0) return@mapNotNull null
                val video = cands.maxWithOrNull(
                    compareBy<VideoStream> {
                        when {
                            isVp9(it) -> 2
                            isAvc(it) -> 1
                            else -> 0
                        }
                    }.thenBy { streamBitrate(it) },
                ) ?: return@mapNotNull null
                val audio = audioForVideo(video, audios) ?: return@mapNotNull null
                val label = YoutubeResolutionCache.labelForHeight(height) ?: "${height}p"
                YoutubeQualityOption(
                    height = height,
                    label = label,
                    playback = YoutubePlayback.SeparateTracks(
                        videoUrl = video.content,
                        audioUrl = audio.content,
                        videoMime = video.format?.mimeType,
                        audioMime = audio.format?.mimeType,
                    ),
                )
            }
            .sortedByDescending { it.height }
    }

    private fun audioForVideo(video: VideoStream, audios: List<AudioStream>): AudioStream? {
        val preferAac = isAvc(video) || video.format?.mimeType?.contains("mp4") == true
        return pickPreferredAudio(audios, preferAac = preferAac)
    }

    /**
     * Prefer English + original tracks. On Spanish IPs YouTube often lists a Spanish
     * dub first / at equal bitrate — picking max bitrate alone made English videos Spanish.
     */
    private fun pickPreferredAudio(
        audios: List<AudioStream>,
        preferAac: Boolean,
    ): AudioStream? =
        audios.maxWithOrNull(
            compareBy<AudioStream> { audioLanguageScore(it) }
                .thenBy { audioTrackTypeScore(it) }
                .thenBy {
                    when {
                        preferAac && isAac(it) -> 2
                        !preferAac && !isAac(it) -> 2
                        else -> 1
                    }
                }
                .thenBy { streamBitrate(it) },
        )

    private fun audioLanguageScore(stream: AudioStream): Int {
        val lang = stream.audioLocale?.language?.lowercase(Locale.ROOT).orEmpty()
        val trackId = stream.audioTrackId.orEmpty().lowercase(Locale.ROOT)
        val trackName = stream.audioTrackName.orEmpty().lowercase(Locale.ROOT)
        val hints = audioLanguageHints(stream).lowercase(Locale.ROOT)
        val blob = "$lang $trackId $trackName $hints"
        return when {
            lang == "en" || lang.startsWith("en") ||
                trackId.startsWith("en") || blob.contains("english") ||
                Regex("""(?:^|[^\w])en(?:[^\w]|$)""").containsMatchIn(hints) ||
                hints.contains("lang=en") || hints.contains("lang%3den") -> 100
            lang == "es" || lang.startsWith("es") ||
                trackId.startsWith("es") || blob.contains("spanish") ||
                blob.contains("español") || blob.contains("espanol") ||
                hints.contains("lang=es") || hints.contains("lang%3des") -> 0
            // Unlabeled — prefer original (acont=original) over dubbed
            hints.contains("acont=original") || hints.contains("acont%3doriginal") -> 80
            hints.contains("acont=dubbed") || hints.contains("acont%3ddubbed") -> 5
            lang.isBlank() && trackId.isBlank() && hints.isBlank() -> 60
            else -> 20
        }
    }

    private fun audioTrackTypeScore(stream: AudioStream): Int {
        when (stream.audioTrackType) {
            AudioTrackType.ORIGINAL -> return 50
            AudioTrackType.SECONDARY -> return 20
            AudioTrackType.DESCRIPTIVE -> return 10
            AudioTrackType.DUBBED -> return 0
            null -> Unit
        }
        val hints = audioLanguageHints(stream).lowercase(Locale.ROOT)
        return when {
            hints.contains("acont=original") || hints.contains("acont%3doriginal") -> 50
            hints.contains("acont=dubbed") || hints.contains("acont%3ddubbed") -> 0
            hints.contains("acont=descriptive") || hints.contains("acont%3ddescriptive") -> 10
            else -> 30
        }
    }

    /** YouTube often puts lang/acont in googlevideo `xtags` or URL query when audioTrack JSON is absent. */
    private fun audioLanguageHints(stream: AudioStream): String {
        val xtags = stream.itagItem?.xtags.orEmpty()
        val url = stream.content.orEmpty()
        val fromUrl = buildString {
            fun grab(key: String) {
                val re = Regex("""[?&]$key=([^&]+)""", RegexOption.IGNORE_CASE)
                re.find(url)?.groupValues?.getOrNull(1)?.let {
                    append(key).append('=').append(it).append(' ')
                }
            }
            grab("xtags")
            grab("lang")
            grab("acont")
        }
        return "$xtags $fromUrl".trim()
    }

    private fun streamBitrate(stream: VideoStream): Int = stream.bitrate
    private fun streamBitrate(stream: AudioStream): Int = stream.bitrate

    private fun isAvc(stream: VideoStream): Boolean {
        val mime = stream.format?.mimeType.orEmpty()
        return mime.contains("avc", ignoreCase = true)
    }

    private fun isVp9(stream: VideoStream): Boolean {
        val mime = stream.format?.mimeType.orEmpty()
        return mime.contains("vp9", ignoreCase = true) || mime.contains("vp09", ignoreCase = true)
    }

    private fun isAv1(stream: VideoStream): Boolean {
        val mime = stream.format?.mimeType.orEmpty()
        return mime.contains("av01", ignoreCase = true)
    }

    private fun isAac(stream: AudioStream): Boolean {
        val mime = stream.format?.mimeType.orEmpty()
        return mime.contains("mp4a", ignoreCase = true) || mime.startsWith("audio/mp4")
    }
}
