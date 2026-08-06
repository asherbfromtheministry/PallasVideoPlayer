package com.vizvag.shieldvideo.playback.radio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.vizvag.shieldvideo.music.player.MusicEnergyProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RadioPlaybackState(
    val stationId: String = "",
    val stationName: String = "",
    val streamUrl: String = "",
    val isPlaying: Boolean = false,
    val title: String = "",
)

/**
 * App-scoped Radio ExoPlayer so LAN remote control can drive stations without Compose.
 */
class RadioPlaybackController(context: Context) {
    private val appContext = context.applicationContext
    val energyProbe: MusicEnergyProbe = MusicEnergyProbe()

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setRenderersFactory(
            object : DefaultRenderersFactory(appContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink {
                    return DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(energyProbe.asProcessor()))
                        .build()
                }
            },
        )
        .build()
        .apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                }
            })
        }

    private val _state = MutableStateFlow(RadioPlaybackState())
    val state: StateFlow<RadioPlaybackState> = _state.asStateFlow()

    fun playStation(stationId: String, name: String, streamUrl: String) {
        if (streamUrl.isBlank()) return
        _state.value = RadioPlaybackState(
            stationId = stationId,
            stationName = name,
            streamUrl = streamUrl,
            isPlaying = true,
            title = name,
        )
        // Keep RadioScreen dial / cold-start selection in sync with deep links & remotes.
        appContext.getSharedPreferences(PREFS_RADIO, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_STATION, stationId)
            .apply()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.play()
        publishHaNowPlaying()
    }

    companion object {
        const val PREFS_RADIO = "radio_player"
        const val KEY_LAST_STATION = "last_station_id"
    }

    /** UI already loaded media onto [player] — keep remote status in sync. */
    fun notePlaying(stationId: String, name: String, streamUrl: String) {
        _state.value = RadioPlaybackState(
            stationId = stationId,
            stationName = name,
            streamUrl = streamUrl,
            isPlaying = player.isPlaying || player.playWhenReady,
            title = name,
        )
        publishHaNowPlaying()
    }

    fun play() {
        if (player.mediaItemCount > 0) {
            player.play()
        } else {
            val s = _state.value
            if (s.streamUrl.isNotBlank()) {
                playStation(s.stationId, s.stationName, s.streamUrl)
            }
        }
        publishHaNowPlaying()
    }

    fun pause() {
        player.pause()
        publishHaNowPlaying()
    }

    fun toggle() {
        if (player.isPlaying) pause() else play()
    }

    fun stop() {
        runCatching {
            player.volume = 0f
            player.pause()
            player.stop()
            player.clearMediaItems()
            player.volume = 1f
        }
        energyProbe.resetLevels()
        _state.value = RadioPlaybackState()
        clearHaNowPlaying()
    }

    fun setNowPlayingTitle(title: String) {
        _state.update { it.copy(title = title.ifBlank { it.stationName }) }
        publishHaNowPlaying()
    }

    fun isActive(): Boolean = _state.value.stationId.isNotBlank() || player.mediaItemCount > 0

    private fun publishHaNowPlaying() {
        runCatching {
            (appContext as? com.vizvag.shieldvideo.ShieldVideoApp)
                ?: com.vizvag.shieldvideo.ShieldVideoApp.instance
        }.getOrNull()?.publishRadioNowPlayingToHa()
    }

    private fun clearHaNowPlaying() {
        runCatching {
            (appContext as? com.vizvag.shieldvideo.ShieldVideoApp)
                ?: com.vizvag.shieldvideo.ShieldVideoApp.instance
        }.getOrNull()?.clearRadioNowPlayingToHa()
    }
}
