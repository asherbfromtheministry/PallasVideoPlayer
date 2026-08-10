package com.vizvag.shieldvideo.data.youtube

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

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
            livestream = livestream,
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
        livestream: Boolean,
        videoOnly: List<VideoStream>,
        audios: List<AudioStream>,
        muxed: List<VideoStream>,
    ): List<YoutubePlayback> {
        val options = mutableListOf<YoutubePlayback>()

        val bestAudio = audios.maxWithOrNull(
            compareBy<AudioStream> { if (isAac(it)) 1 else 0 }.thenBy { streamBitrate(it) },
        )
        val bestVideo = videoOnly
            .filter { !isAv1(it) }
            .maxWithOrNull(
                compareBy<VideoStream> { it.height }
                    .thenBy { when { isVp9(it) -> 2; isAvc(it) -> 1; else -> 0 } }
                    .thenBy { streamBitrate(it) },
            )

        // SeparateTracks with pot= first — high-res and quality switchable.
        if (bestVideo != null && bestAudio != null) {
            options += YoutubePlayback.SeparateTracks(
                videoUrl = bestVideo.content,
                audioUrl = bestAudio.content,
                videoMime = bestVideo.format?.mimeType,
                audioMime = bestAudio.format?.mimeType,
            )
        }

        // HLS often works without pot= (iOS/safari manifests).
        if (hlsUrl != null) {
            options += YoutubePlayback.Hls(hlsUrl)
        }

        // Progressive muxed — lower res, still useful fallback.
        muxed
            .filter { !isAv1(it) }
            .maxWithOrNull(compareBy<VideoStream> { it.height }.thenBy { streamBitrate(it) })
            ?.let {
                options += YoutubePlayback.Progressive(it.content, it.format?.mimeType)
            }

        if (dashUrl != null) {
            options += YoutubePlayback.Dash(dashUrl)
        }

        return options.distinct()
    }

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
        return audios.maxWithOrNull(
            compareBy<AudioStream> {
                when {
                    preferAac && isAac(it) -> 2
                    !preferAac && !isAac(it) -> 2
                    else -> 1
                }
            }.thenBy { streamBitrate(it) },
        )
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
