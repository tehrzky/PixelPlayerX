package com.theveloper.pixelplay.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.data.diagnostics.AdvancedPerformanceDiagnostics
import com.theveloper.pixelplay.data.media.ReplayGainManager
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.utils.MediaItemBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import timber.log.Timber
import kotlin.math.abs

class ReplayGainProcessor(
    private val engine: DualPlayerEngine,
    private val replayGainManager: ReplayGainManager,
    private val scope: CoroutineScope,
    private val currentSessionMediaItem: () -> MediaItem?,
) {
    private companion object {
        private const val TAG = "MusicService_PixelPlay"
    }

    private var enabled = false
    private var useAlbumGain = false
    private var job: Job? = null
    private var requestToken = 0L

    private var userSelectedVolume = 1f
    private var expectedVolume: Float? = null
    private var pendingVolume: Float? = null
    private var lastAppliedVolume: Float? = null
    private var lastAppliedMediaId: String? = null

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setUseAlbumGain(value: Boolean) {
        useAlbumGain = value
    }

    fun captureUserVolume(volume: Float) {
        userSelectedVolume = volume.coerceIn(0f, 1f)
    }

    fun cancel() {
        job?.cancel()
    }

    fun onPlayerVolumeChanged(volume: Float) {
        if (engine.isTransitionRunning()) return
        val expected = expectedVolume
        if (expected != null && abs(expected - volume) < 0.001f) {
            expectedVolume = null
            return
        }
        expectedVolume = null
        userSelectedVolume = volume.coerceIn(0f, 1f)
    }

    private fun setPlayerVolume(player: Player, volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        expectedVolume = clampedVolume
        player.volume = clampedVolume
    }

    fun reapplyLastAppliedVolume(player: Player) {
        if (!enabled || engine.isTransitionRunning()) return
        val currentMediaId = currentSessionMediaItem()?.mediaId
        if (currentMediaId != null && currentMediaId == lastAppliedMediaId) {
            lastAppliedVolume?.let { setPlayerVolume(player, it) }
        }
    }

    fun prepareForTransition(player: Player) {
        if (!enabled) return
        val incomingItem = player.currentMediaItem
        cachedVolumeFor(incomingItem)?.let { engine.incomingTrackReplayGainVolume = it }
        apply(incomingItem)
    }

    fun forceRefresh(mediaItem: MediaItem?) {
        if (mediaItem == null) return
        if (mediaItem.mediaId == lastAppliedMediaId) {
            lastAppliedVolume = null
            lastAppliedMediaId = null
        }
        apply(mediaItem)
    }

    fun apply(mediaItem: MediaItem?) {
        job?.cancel()
        requestToken += 1
        val myToken = requestToken

        if (mediaItem == null) return

        if (!enabled) {
            pendingVolume = null
            lastAppliedVolume = null
            lastAppliedMediaId = null
            if (!engine.isTransitionRunning()) {
                setPlayerVolume(engine.masterPlayer, userSelectedVolume)
            }
            return
        }

        val mediaId = mediaItem.mediaId

        if (mediaId == lastAppliedMediaId && lastAppliedVolume != null) {
            if (!engine.isTransitionRunning()) {
                setPlayerVolume(engine.masterPlayer, lastAppliedVolume!!)
            }
            return
        }

        val cachedVolume = cachedVolumeFor(mediaItem)
        if (cachedVolume != null) {
            if (!engine.isTransitionRunning()) {
                setPlayerVolume(engine.masterPlayer, cachedVolume)
            }
            lastAppliedVolume = cachedVolume
            lastAppliedMediaId = mediaId
            return
        }

        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)
        if (filePath.isNullOrBlank()) {
            Timber.tag(TAG).d("ReplayGain: file path not yet available for %s, will retry on next trigger", mediaId)
            AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                type = AdvancedPerformanceDiagnostics.EventTypes.REPLAYGAIN,
                name = "replaygain_apply_skipped_no_path"
            ) { mapOf("mediaId" to mediaId) }
            return
        }

        val resolvedUseAlbumGain = useAlbumGain

        AdvancedPerformanceDiagnostics.recordEventIfEnabled(
            type = AdvancedPerformanceDiagnostics.EventTypes.REPLAYGAIN,
            name = "replaygain_read_start"
        ) { mapOf("mediaId" to mediaId) }
        job = scope.launch {
            delay(500)
            if (myToken != requestToken) {
                AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                    type = AdvancedPerformanceDiagnostics.EventTypes.REPLAYGAIN,
                    name = "replaygain_read_superseded"
                ) { mapOf("mediaId" to mediaId) }
                return@launch
            }

            val rgValues = try {
                withTimeout(2000) {
                    withContext(Dispatchers.IO) {
                        replayGainManager.readReplayGain(filePath)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                null
            }

            if (myToken != requestToken) return@launch
            if (currentSessionMediaItem()?.mediaId != mediaId) {
                Timber.tag(TAG).d("ReplayGain: ignoring stale result for %s (track changed)", mediaId)
                return@launch
            }

            val volume = replayGainManager.getVolumeMultiplier(rgValues, useAlbumGain = resolvedUseAlbumGain)

            if (engine.isTransitionRunning()) {
                pendingVolume = volume
                engine.incomingTrackReplayGainVolume = volume
                Timber.tag(TAG).d("ReplayGain: stored pending volume=%.2f for %s (transition running)", volume, mediaId)
                AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                    type = AdvancedPerformanceDiagnostics.EventTypes.REPLAYGAIN,
                    name = "replaygain_read_result"
                ) { mapOf("mediaId" to mediaId, "volume" to volume.toString(), "outcome" to "pending_transition") }
            } else {
                lastAppliedVolume = volume
                lastAppliedMediaId = mediaId
                setPlayerVolume(engine.masterPlayer, volume)
                Timber.tag(TAG).d("ReplayGain: applied volume=%.2f for %s", volume, mediaId)
                AdvancedPerformanceDiagnostics.recordEventIfEnabled(
                    type = AdvancedPerformanceDiagnostics.EventTypes.REPLAYGAIN,
                    name = "replaygain_read_result"
                ) { mapOf("mediaId" to mediaId, "volume" to volume.toString(), "outcome" to "applied") }
            }
        }
    }

    private fun cachedVolumeFor(mediaItem: MediaItem?): Float? {
        if (!enabled || mediaItem == null) return null
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return null
        if (filePath.isBlank()) return null
        val cached = replayGainManager.getCachedReplayGain(filePath) ?: return null
        return replayGainManager.getVolumeMultiplier(cached, useAlbumGain = useAlbumGain)
    }

    fun prefetch(mediaItem: MediaItem?) {
        if (!enabled || mediaItem == null) return
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return
        if (filePath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            replayGainManager.readReplayGain(filePath)
        }
    }

    fun onTransitionFinished() {
        val player = engine.masterPlayer
        val pending = pendingVolume
        pendingVolume = null

        if (!enabled) {
            Timber.tag(TAG).d("ReplayGain: Transition finished, RG disabled — no volume change")
            return
        }

        if (pending != null) {
            lastAppliedVolume = pending
            lastAppliedMediaId = currentSessionMediaItem()?.mediaId
            setPlayerVolume(player, pending)
            Timber.tag(TAG).d("ReplayGain: Transition finished, applied pending volume=%.2f", pending)
        } else {
            apply(currentSessionMediaItem())
            Timber.tag(TAG).d("ReplayGain: Transition finished, no pending volume — triggering full recomputation")
        }
    }

    fun onMediaMetadataChanged(currentItem: MediaItem?) {
        if (!enabled) return
        apply(currentItem)
    }
}
