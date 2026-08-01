package com.theveloper.pixelplay.data.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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

/**
 * Owns all ReplayGain volume-normalization state and logic.
 *
 * Design note (rewritten to fix cold-start / hit-and-miss reliability): every
 * caller — apply(), onMediaMetadataChanged(), prepareForTransition(), the
 * shuffle/rebuild hook, the enable/disable toggle — funnels through the single
 * [apply] decision path below. There is exactly one place that decides "do I
 * already know this track's volume", "is a file path available yet", and
 * "should I kick off an IO read". Earlier versions duplicated that decision in
 * three places and they could disagree, which is what caused RG to silently
 * and permanently give up on a track whose MediaItem didn't have its file path
 * populated yet (a transient condition, not a permanent one) until some
 * unrelated trigger happened to retry it later.
 */
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

    // Volume the user last chose by hand; restored whenever RG is disabled.
    private var userSelectedVolume = 1f
    // Volume we just wrote programmatically — used to ignore the echoed onVolumeChanged.
    private var expectedVolume: Float? = null
    // RG volume computed mid-crossfade, applied once the transition finishes.
    private var pendingVolume: Float? = null
    // Last successfully applied RG volume, and which track it belongs to.
    private var lastAppliedVolume: Float? = null
    private var lastAppliedMediaId: String? = null

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setUseAlbumGain(value: Boolean) {
        useAlbumGain = value
    }

    /** Seeds the user-selected volume from the player's current volume on startup. */
    fun captureUserVolume(volume: Float) {
        userSelectedVolume = volume.coerceIn(0f, 1f)
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Mirrors [Player.Listener.onVolumeChanged]: distinguishes a programmatic RG
     * volume change (which we ignore) from a genuine user gesture (which updates
     * [userSelectedVolume]).
     */
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

    /** Re-applies the last computed RG volume immediately (no IO), only if it still
     * belongs to the current track — otherwise a stale gain could bleed into a
     * different song while a fresh read is in flight. */
    fun reapplyLastAppliedVolume(player: Player) {
        if (!enabled || engine.isTransitionRunning()) return
        val currentMediaId = currentSessionMediaItem()?.mediaId
        if (currentMediaId != null && currentMediaId == lastAppliedMediaId) {
            lastAppliedVolume?.let { setPlayerVolume(player, it) }
        }
    }

    /**
     * Pre-computes ReplayGain for the incoming crossfade track. Seeds
     * [DualPlayerEngine.incomingTrackReplayGainVolume] from cache when available so
     * the fade loop ends at the correct volume, then kicks off [apply] which (since
     * the transition is running) stores the result as a pending volume.
     */
    fun prepareForTransition(player: Player) {
        if (!enabled) return
        val incomingItem = player.currentMediaItem
        cachedVolumeFor(incomingItem)?.let { engine.incomingTrackReplayGainVolume = it }
        apply(incomingItem)
    }

    /**
     * Forces a fresh ReplayGain re-read for [mediaItem], even if we already have an
     * applied volume cached for this exact track. Use this when the underlying
     * file's tags just changed (a metadata edit) — apply()'s normal fast path would
     * otherwise just reassert the now-stale cached volume instead of re-reading.
     */
    fun forceRefresh(mediaItem: MediaItem?) {
        if (mediaItem == null) return
        if (mediaItem.mediaId == lastAppliedMediaId) {
            lastAppliedVolume = null
            lastAppliedMediaId = null
        }
        apply(mediaItem)
    }
    
    /**
     * Single entry point for applying ReplayGain to [mediaItem]. Safe to call
     * repeatedly and from anywhere (track transitions, metadata updates, shuffle,
     * enable/disable toggles, player rebuilds) — every call re-evaluates from
     * scratch what's actually known right now, so a call that can't do anything
     * yet (e.g. file path not populated) is a harmless no-op, not a dead end.
     */
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

        // Fast path 1: we already applied RG for this exact track — just reassert it.
        if (mediaId == lastAppliedMediaId && lastAppliedVolume != null) {
            if (!engine.isTransitionRunning()) {
                setPlayerVolume(engine.masterPlayer, lastAppliedVolume!!)
            }
            return
        }

        // Fast path 2: ReplayGainManager already has the tags cached (from a
        // prefetch or an earlier read) — apply instantly, no IO needed.
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
            // Can't compute yet. Deliberately not touching lastAppliedMediaId/volume
            // or the player volume here — this is a "not ready yet", not a failure.
            // The next natural trigger (onMediaMetadataChanged once the item's full
            // metadata lands, a player rebuild, a track change) will call apply()
            // again and this time the file path will likely be populated.
            Timber.tag(TAG).d("ReplayGain: file path not yet available for %s, will retry on next trigger", mediaId)
            return
        }

        val resolvedUseAlbumGain = useAlbumGain

        // Read ReplayGain tags on an IO thread. The 500ms delay both avoids
        // competing with the decoder for disk access right as a track starts
        // (TagLib seeking through large ID3 tags on the same file the decoder is
        // opening), and self-debounces: apply() cancels `job` on every call, so a
        // burst of calls in quick succession only ever performs the last one's read.
        job = scope.launch {
            delay(500)
            if (myToken != requestToken) return@launch

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
            } else {
                lastAppliedVolume = volume
                lastAppliedMediaId = mediaId
                setPlayerVolume(engine.masterPlayer, volume)
                Timber.tag(TAG).d("ReplayGain: applied volume=%.2f for %s", volume, mediaId)
            }
        }
    }

    /**
     * Returns the cached ReplayGain volume for a media item if already computed, or null.
     * Does NOT trigger an IO read — only reads from the in-memory cache.
     */
    private fun cachedVolumeFor(mediaItem: MediaItem?): Float? {
        if (!enabled || mediaItem == null) return null
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return null
        if (filePath.isBlank()) return null
        val cached = replayGainManager.getCachedReplayGain(filePath) ?: return null
        return replayGainManager.getVolumeMultiplier(cached, useAlbumGain = useAlbumGain)
    }

    /**
     * Pre-fetches ReplayGain tags for a media item into the cache without applying the volume.
     * Called on queue changes and track transitions so the cache is warm by the time
     * [apply] runs, avoiding the read delay on playback start.
     */
    fun prefetch(mediaItem: MediaItem?) {
        if (!enabled || mediaItem == null) return
        val filePath = mediaItem.mediaMetadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH) ?: return
        if (filePath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            replayGainManager.readReplayGain(filePath)
        }
    }

    /**
     * Applies the volume that was held back while a crossfade was running, or
     * triggers a fresh computation if none was pending.
     */
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

    /**
     * Notifies the processor that the current track's metadata changed — covers
     * both real track transitions and cases where a MediaItem is replaced/rebuilt
     * in place (queue edits, shuffle, player rebuilds) with metadata that may now
     * include a file path it didn't have before. Just forwards to [apply], which
     * already knows how to no-op safely if there's nothing new to do.
     */
    fun onMediaMetadataChanged(currentItem: MediaItem?) {
        if (!enabled) return
        apply(currentItem)
    }
}
