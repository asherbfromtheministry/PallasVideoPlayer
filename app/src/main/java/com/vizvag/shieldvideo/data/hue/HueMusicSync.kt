package com.vizvag.shieldvideo.data.hue

import com.vizvag.shieldvideo.data.settings.SettingsRepository
import com.vizvag.shieldvideo.music.player.MusicEnergyProbe
import com.vizvag.shieldvideo.music.player.PlayerController
import com.vizvag.shieldvideo.playback.radio.RadioPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Hue light sync for Music and Radio: maps ExoPlayer PCM energy to bridge brightness/color.
 * Idle / pause restores the lights' previous states.
 */
class HueMusicSync(
    private val settingsRepository: SettingsRepository,
    private val playerController: PlayerController,
    private val musicProbe: MusicEnergyProbe,
    private val radioPlayback: RadioPlaybackController,
    private val radioProbe: MusicEnergyProbe,
    private val client: HueBridgeClient = HueBridgeClient(),
    appScope: CoroutineScope,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreMutex = Mutex()
    private var savedStates: Map<String, HueLightState> = emptyMap()
    private var syncJob: Job? = null
    private var huePhase = 0
    @Volatile private var inFlight = false

    private enum class Source { Music, Radio }

    private data class SyncConfig(
        val active: Boolean,
        val source: Source?,
        val bridgeIp: String,
        val username: String,
        val lightIds: List<String>,
    )

    init {
        appScope.launch {
            combine(
                playerController.uiState.map { it.isPlaying }.distinctUntilChanged(),
                radioPlayback.state.map { it.isPlaying }.distinctUntilChanged(),
                settingsRepository.revision,
            ) { musicPlaying, radioPlaying, _ ->
                val settings = settingsRepository.load()
                val source = when {
                    musicPlaying -> Source.Music
                    radioPlaying -> Source.Radio
                    else -> null
                }
                SyncConfig(
                    active = source != null &&
                        settings.hueEnabled &&
                        settings.hueBridgeIp.isNotBlank() &&
                        settings.hueUsername.isNotBlank() &&
                        settings.hueLightIds.isNotEmpty(),
                    source = source,
                    bridgeIp = settings.hueBridgeIp.trim(),
                    username = settings.hueUsername.trim(),
                    lightIds = settings.hueLightIds.map { it.trim() }.filter { it.isNotEmpty() },
                )
            }
                .distinctUntilChanged()
                .collectLatest { config ->
                    if (config.active) {
                        try {
                            startSync(config)
                            while (true) delay(60_000)
                        } finally {
                            stopSync(restore = true)
                        }
                    } else {
                        stopSync(restore = true)
                    }
                }
        }
    }

    private fun probeFor(source: Source?): MusicEnergyProbe = when (source) {
        Source.Radio -> radioProbe
        else -> musicProbe
    }

    private suspend fun startSync(config: SyncConfig) {
        stopSync(restore = false)
        savedStates = restoreMutex.withLock {
            config.lightIds.associateWith { id ->
                client.getLightState(config.bridgeIp, config.username, id).getOrElse {
                    HueLightState(on = true, bri = 120, hue = 0, sat = 180)
                }
            }
        }
        probeFor(config.source).resetLevels()
        huePhase = (0..65_000).random()
        syncJob = ioScope.launch {
            while (isActive) {
                pushFrame(config)
                delay(FRAME_MS)
            }
        }
    }

    private suspend fun stopSync(restore: Boolean) {
        syncJob?.cancel()
        syncJob = null
        musicProbe.resetLevels()
        radioProbe.resetLevels()
        if (restore) restoreLights()
    }

    private suspend fun restoreLights() {
        val snapshot = restoreMutex.withLock {
            val copy = savedStates
            savedStates = emptyMap()
            copy
        }
        if (snapshot.isEmpty()) return
        val settings = settingsRepository.load()
        val ip = settings.hueBridgeIp
        val user = settings.hueUsername
        if (ip.isBlank() || user.isBlank()) return
        snapshot.forEach { (id, state) ->
            client.setLightState(
                bridgeIp = ip,
                username = user,
                lightId = id,
                on = state.on,
                bri = state.bri,
                hue = state.hue,
                sat = state.sat,
                transitionTime = 10,
            )
        }
    }

    private fun pushFrame(config: SyncConfig) {
        if (inFlight) return
        val probe = probeFor(config.source)
        val level = probe.level
        val bass = probe.bass
        val beat = probe.beat

        huePhase = (huePhase + (400 + (level * 1800).roundToInt()) + (beat * 4000).roundToInt()) % 65_536
        val bri = (28 + level * 220f + beat * 40f).roundToInt().coerceIn(1, 254)
        val sat = (140 + bass * 110f).roundToInt().coerceIn(80, 254)
        val hue = huePhase

        inFlight = true
        ioScope.launch {
            try {
                config.lightIds.forEach { id ->
                    client.setLightState(
                        bridgeIp = config.bridgeIp,
                        username = config.username,
                        lightId = id,
                        on = true,
                        bri = bri,
                        hue = hue,
                        sat = sat,
                        transitionTime = 1,
                    )
                }
            } finally {
                inFlight = false
            }
        }
    }

    companion object {
        private const val FRAME_MS = 100L
    }
}
