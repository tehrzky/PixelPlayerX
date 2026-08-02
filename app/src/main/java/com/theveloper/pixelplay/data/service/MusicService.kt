package com.theveloper.pixelplay.data.service

import android.app.AlarmManager
import android.app.BackgroundServiceStartNotAllowedException
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.theveloper.pixelplay.MainActivity
import com.theveloper.pixelplay.PixelPlayApplication
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics
import com.theveloper.pixelplay.data.equalizer.EqualizerManager
import com.theveloper.pixelplay.data.media.ReplayGainManager
import com.theveloper.pixelplay.data.model.PlaybackQueueItemSnapshot
import com.theveloper.pixelplay.data.model.PlaybackQueueSnapshot
import com.theveloper.pixelplay.data.model.PlayerInfo
import com.theveloper.pixelplay.data.model.QueueItem
import com.theveloper.pixelplay.data.model.WidgetThemeColors
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.AlbumArtColorAccuracy
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import com.theveloper.pixelplay.data.preferences.EqualizerPreferencesRepository
import com.theveloper.pixelplay.data.preferences.ThemePreference
import com.theveloper.pixelplay.data.preferences.ThemePreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.auto.AutoMediaBrowseTree
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.service.player.TransitionController
import com.theveloper.pixelplay.data.service.wear.WearStatePublisher
import com.theveloper.pixelplay.data.service.wear.buildWearThemePalette
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemeProcessor
import com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker
import com.theveloper.pixelplay.shared.WearIntents
import com.theveloper.pixelplay.ui.glancewidget.PlayerActions
import com.theveloper.pixelplay.utils.AlbumArtUtils
import com.theveloper.pixelplay.utils.ArtworkTransportSanitizer
import com.theveloper.pixelplay.utils.LocalArtworkUri
import com.theveloper.pixelplay.utils.MediaItemBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

suspend fun loadArtworkBytesViaCoil(context: Context, uri: Uri): ByteArray? {
    val appContext = context.applicationContext
    val request = ImageRequest.Builder(appContext)
        .data(uri)
        .size(
            ArtworkTransportSanitizer.WIDGET_CONFIG.maxDimensionPx,
            ArtworkTransportSanitizer.WIDGET_CONFIG.maxDimensionPx,
        )
        .precision(Precision.INEXACT)
        .allowHardware(false)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .build()

    return runCatching {
        val drawable = appContext.imageLoader.execute(request).drawable ?: return@runCatching null
        val fallbackSizePx = ArtworkTransportSanitizer.WIDGET_CONFIG.maxDimensionPx
        val bitmap = drawable.toBitmap(
            width = drawable.intrinsicWidth.takeIf { it > 0 } ?: fallbackSizePx,
            height = drawable.intrinsicHeight.takeIf { it > 0 } ?: fallbackSizePx,
            config = Bitmap.Config.ARGB_8888,
        )
        val encodedBytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            output.toByteArray()
        }
        ArtworkTransportSanitizer.sanitizeEncodedBytes(
            data = encodedBytes,
            config = ArtworkTransportSanitizer.WIDGET_CONFIG,
        )
    }.getOrElse { error ->
        Timber.tag("MusicService_PixelPlay").w(error, "Artwork read failed via Coil for uri=%s", uri)
        null
    }
}

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService() {

    @Inject
    lateinit var engine: DualPlayerEngine
    @Inject
    lateinit var controller: TransitionController
    @Inject
    lateinit var musicRepository: MusicRepository
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository
    @Inject
    lateinit var equalizerPreferencesRepository: EqualizerPreferencesRepository
    @Inject
    lateinit var themePreferencesRepository: ThemePreferencesRepository
    @Inject
    lateinit var equalizerManager: EqualizerManager
    @Inject
    lateinit var colorSchemeProcessor: ColorSchemeProcessor
    @Inject
    lateinit var autoMediaBrowseTree: AutoMediaBrowseTree
    @Inject
    lateinit var wearStatePublisher: WearStatePublisher
    @Inject
    lateinit var replayGainManager: ReplayGainManager
    @Inject
    lateinit var navidromeRepository: NavidromeRepository
    @Inject
    lateinit var listeningStatsTracker: ListeningStatsTracker
    @Inject
    @AppScope
    lateinit var appScope: CoroutineScope

    private val replayGainProcessor by lazy {
        ReplayGainProcessor(
            engine = engine,
            replayGainManager = replayGainManager,
            scope = serviceScope,
            currentSessionMediaItem = { mediaSession?.player?.currentMediaItem },
        )
    }

    private var favoriteSongIds = emptySet<String>()
    private var mediaSession: MediaLibrarySession? = null
    private val controllerLastBrowsedParent = mutableMapOf<String, String>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var keepPlayingInBackground = true
    private var isManualShuffleEnabled = false
    private var persistentShuffleEnabled = false
    private var previousMainThreadExceptionHandler: Thread.UncaughtExceptionHandler? = null

    private var countedPlayActive = false
    private var countedPlayTarget = 0
    private var countedPlayCount = 0
    private var countedOriginalId: String? = null
    private var countedPlayListener: Player.Listener? = null
    private val alarmManager by lazy {
        getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    private var endOfTrackTimerSongId: String? = null

    private val castSyncCoordinator by lazy {
        CastSyncCoordinator(
            context = this,
            listeningStatsTracker = listeningStatsTracker,
            requestWidgetUpdate = { force -> widgetUpdateManager.requestFullUpdate(force) },
        )
    }

    private val widgetUpdateManager by lazy {
        WidgetUpdateManager(
            context = applicationContext,
            scope = serviceScope,
            wearStatePublisher = wearStatePublisher,
            buildPlayerInfo = { buildPlayerInfo() },
            resolveCurrentMediaIdForWear = { resolveCurrentMediaIdForWear() },
        )
    }
    private var playbackSnapshotPersistJob: Job? = null
    private var playbackSnapshotUnloadWriteJob: Job? = null
    private var isRestoringPlaybackSnapshot = false
    private var isPlaybackUnloadInProgress = false
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var headsetReconnectCallback: AudioDeviceCallback? = null
    private var shouldResumeAfterHeadsetReconnect = false
    private var lastNoisyPauseRealtimeMs = 0L
    private var resumeOnHeadsetReconnectEnabled = false
    private var pauseOnVolumeZeroEnabled = false
    private var temporaryForegroundStartedInOnCreate = false

    private val systemVolumeObserver by lazy {
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!pauseOnVolumeZeroEnabled) return
                val streamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (streamVolume == 0) {
                    val player = mediaSession?.player ?: engine.masterPlayer
                    if (player.isPlaying) {
                        player.pause()
                        Timber.tag(TAG).d("pauseOnVolumeZero: paused because system media volume reached 0")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MusicService_PixelPlay"
        const val NOTIFICATION_ID = 101
        const val ACTION_SLEEP_TIMER_EXPIRED = "com.theveloper.pixelplay.ACTION_SLEEP_TIMER_EXPIRED"
        const val EXTRA_FORCE_FOREGROUND_ON_START =
            "com.theveloper.pixelplay.extra.FORCE_FOREGROUND_ON_START"

        private const val PLAYBACK_SNAPSHOT_DEBOUNCE_MS = 1500L
        private const val MEDIA_SESSION_BUTTON_DEBOUNCE_MS = 250L
        private const val DEFERRED_SERVICE_STARTUP_WORK_DELAY_MS = 1_000L
        private const val PAUSED_RESTORE_PREPARE_QUEUE_LIMIT = 50
        private val pendingMediaButtonForegroundStarts = AtomicInteger(0)

        private const val APP_PACKAGE_PREFIX = "com.theveloper.pixelplay"
        private val BLOCKED_WEAR_CONTROLLER_PREFIXES = listOf(
            "com.google.android.wearable",
            "com.google.android.clockwork",
            "com.google.android.apps.wearable",
            "com.google.android.apps.wear.companion",
            "com.samsung.android.app.watchmanager",
            "com.mobvoi.wear",
        )
        private val WEAR_HINT_KEY_MARKERS = listOf(
            "wear",
            "clockwork",
            "companion",
            "node",
            "remote_device",
        )
        private const val AUTO_CONTEXT_RECENT = "recent"
        private const val AUTO_CONTEXT_FAVORITES = "favorites"
        private const val AUTO_CONTEXT_ALL_SONGS = "all_songs"
        private const val AUTO_CONTEXT_ALBUM = "album"
        private const val AUTO_CONTEXT_ARTIST = "artist"
        private const val AUTO_CONTEXT_PLAYLIST = "playlist"
        private const val DEFAULT_STREAM_BUFFER_SIZE = 8 * 1024
        private const val WIDGET_ART_FAILURE_RETRY_MS = 30_000L
        private const val HEADSET_RECONNECT_RESUME_WINDOW_MS = 15_000L

        fun markPendingMediaButtonForegroundStart() {
            pendingMediaButtonForegroundStarts.incrementAndGet()
        }

        fun unmarkPendingMediaButtonForegroundStart() {
            while (true) {
                val currentCount = pendingMediaButtonForegroundStarts.get()
                if (currentCount <= 0) return
                if (pendingMediaButtonForegroundStarts.compareAndSet(currentCount, currentCount - 1)) {
                    return
                }
            }
        }

        private fun consumePendingMediaButtonForegroundStart(): Boolean {
            while (true) {
                val currentCount = pendingMediaButtonForegroundStarts.get()
                if (currentCount <= 0) return false
                if (pendingMediaButtonForegroundStarts.compareAndSet(currentCount, currentCount - 1)) {
                    return true
                }
            }
        }
    }

    private val playerSwapListener: (Player) -> Unit = { newPlayer ->
        publishMediaSessionPlayer(newPlayer, "Swapped MediaSession player to new instance.")
        replayGainProcessor.prepareForTransition(newPlayer)
    }

    private val transitionDisplayPlayerListener: (Player) -> Unit = { displayPlayer ->
        publishMediaSessionPlayer(
            displayPlayer,
            "Published incoming crossfade player to MediaSession."
        )
        replayGainProcessor.prepareForTransition(displayPlayer)
        val incomingSessionId = displayPlayer.audioSessionId
        if (incomingSessionId != 0) {
            serviceScope.launch {
                equalizerManager.attachToAudioSessionIfNeeded(incomingSessionId, source = "pre_transition")
            }
        }
    }

    private val transitionFinishedListener: () -> Unit = {
        replayGainProcessor.onTransitionFinished()
    }

    private fun publishMediaSessionPlayer(player: Player, logMessage: String) {
        val session = mediaSession ?: return
        val oldPlayer = session.player
        if (oldPlayer !== player) {
            oldPlayer.removeListener(playerListener)
            session.player = player
            player.addListener(playerListener)
        }

        Timber.tag("MusicService").d(logMessage)
        syncLocalListeningStatsFromPlayer(player)
        widgetUpdateManager.requestFullUpdate(true)
        refreshMediaSessionUi(session)
    }

    private fun syncLocalListeningStatsFromPlayer(
        player: Player = engine.masterPlayer,
        forceNewSession: Boolean = false
    ) {
        val mediaItem = player.currentMediaItem
        val songId = mediaItem?.mediaId?.takeIf { it.isNotBlank() }
        if (songId == null) {
            if (
                player.mediaItemCount == 0 ||
                player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_ENDED
            ) {
                listeningStatsTracker.onPlaybackStopped()
            }
            return
        }

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration
        val fallbackDurationMs = mediaItem.mediaMetadata.extras
            ?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, 0L)
            ?: 0L

        if (forceNewSession) {
            listeningStatsTracker.onTrackChanged(
                songId = songId,
                positionMs = positionMs,
                durationMs = durationMs,
                fallbackDurationMs = fallbackDurationMs,
                isPlaying = player.isPlaying
            )
        } else {
            listeningStatsTracker.ensureSession(
                songId = songId,
                positionMs = positionMs,
                durationMs = durationMs,
                fallbackDurationMs = fallbackDurationMs,
                isPlaying = player.isPlaying
            )
        }
    }

    override fun onCreate() {
        val existingHandler = Thread.currentThread().uncaughtExceptionHandler
        previousMainThreadExceptionHandler = existingHandler
        Thread.currentThread().setUncaughtExceptionHandler { thread, throwable ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                throwable is ForegroundServiceStartNotAllowedException
            ) {
                Timber.tag(TAG).w(throwable, "Suppressed ForegroundServiceStartNotAllowedException from Media3/Cast internal path")
            } else {
                existingHandler?.uncaughtException(thread, throwable)
            }
        }

        temporaryForegroundStartedInOnCreate =
            consumePendingMediaButtonForegroundStart() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        if (temporaryForegroundStartedInOnCreate) {
            startTemporaryForegroundForCommand()
        }

        super.onCreate()
        listeningStatsTracker.initialize(appScope)

        engine.initialize()
        syncLocalListeningStatsFromPlayer(engine.masterPlayer)

        engine.masterPlayer.addListener(playerListener)
        registerSystemVolumeObserver()

        engine.setOnPlayerAboutToBeReleasedListener { oldPlayer ->
            oldPlayer.removeListener(playerListener)
        }
        engine.addPlayerSwapListener(playerSwapListener)
        engine.addTransitionDisplayPlayerListener(transitionDisplayPlayerListener)
        engine.addTransitionFinishedListener(transitionFinishedListener)

        controller.initialize()
        serviceScope.launch {
            delay(DEFERRED_SERVICE_STARTUP_WORK_DELAY_MS)
            if (!isPlaybackUnloadInProgress && mediaSession != null) {
                castSyncCoordinator.start()
            }
        }
        registerHeadsetReconnectMonitor()

        serviceScope.launch {
            musicRepository.telegramRepository.downloadCompleted.collect {
                if (isCurrentWidgetArtworkBackedByTelegram()) {
                    invalidateCachedWidgetArtwork()
                    widgetUpdateManager.requestWithFollowUp()
                }
            }
        }

        serviceScope.launch {
            val eqEnabled = equalizerPreferencesRepository.equalizerEnabledFlow.first()
            val presetName = equalizerPreferencesRepository.equalizerPresetFlow.first()
            val customBands = equalizerPreferencesRepository.equalizerCustomBandsFlow.first()
            val bassBoostEnabled = equalizerPreferencesRepository.bassBoostEnabledFlow.first()
            val bassBoostStrength = equalizerPreferencesRepository.bassBoostStrengthFlow.first()
            val virtualizerEnabled = equalizerPreferencesRepository.virtualizerEnabledFlow.first()
            val virtualizerStrength = equalizerPreferencesRepository.virtualizerStrengthFlow.first()
            val loudnessEnabled = equalizerPreferencesRepository.loudnessEnhancerEnabledFlow.first()
            val loudnessStrength = equalizerPreferencesRepository.loudnessEnhancerStrengthFlow.first()

            equalizerManager.restoreState(
                eqEnabled, presetName, customBands,
                bassBoostEnabled, bassBoostStrength,
                virtualizerEnabled, virtualizerStrength,
                loudnessEnabled, loudnessStrength
            )

            val sessionId = engine.getAudioSessionId()
            if (sessionId != 0) {
                equalizerManager.attachToAudioSessionIfNeeded(sessionId, source = "service_restore")
            }

            engine.activeAudioSessionId.collect { newSessionId ->
                if (newSessionId != 0) {
                    equalizerManager.attachToAudioSessionIfNeeded(newSessionId, source = "session_changed")
                }
            }
        }
        serviceScope.launch {
            engine.activeAudioSessionId.collect { newSessionId ->
                if (newSessionId != 0) {
                    replayGainProcessor.apply(mediaSession?.player?.currentMediaItem)
                }
            }
        }
        serviceScope.launch {
            userPreferencesRepository.keepPlayingInBackgroundFlow.collect { enabled ->
                keepPlayingInBackground = enabled
            }
        }

        serviceScope.launch {
            userPreferencesRepository.hiFiModeEnabledFlow.collect { enabled ->
                engine.setHiFiMode(enabled)
            }
        }

        serviceScope.launch {
            userPreferencesRepository.resumeOnHeadsetReconnectFlow.collect { enabled ->
                resumeOnHeadsetReconnectEnabled = enabled
                if (!enabled) {
                    clearHeadsetReconnectResume()
                }
            }
        }

        serviceScope.launch {
            userPreferencesRepository.pauseOnVolumeZeroFlow.collect { enabled ->
                pauseOnVolumeZeroEnabled = enabled
            }
        }

        serviceScope.launch {
            userPreferencesRepository.persistentShuffleEnabledFlow.collect { enabled ->
                persistentShuffleEnabled = enabled
            }
        }

        serviceScope.launch {
            userPreferencesRepository.replayGainEnabledFlow.collect { enabled ->
                replayGainProcessor.setEnabled(enabled)
                replayGainProcessor.apply(mediaSession?.player?.currentMediaItem)
            }
        }
        serviceScope.launch {
            userPreferencesRepository.replayGainUseAlbumGainFlow.collect { useAlbum ->
                replayGainProcessor.setUseAlbumGain(useAlbum)
                replayGainProcessor.apply(mediaSession?.player?.currentMediaItem)
            }
        }

        serviceScope.launch {
            val persistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            if (persistent) {
                isManualShuffleEnabled = userPreferencesRepository.isShuffleOnFlow.first()
                mediaSession?.let { refreshMediaSessionUi(it) }
            }
        }

        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val controllerPackage = controller.packageName
                val hintKeys = controller.connectionHints.keySet().joinToString(",")
                Timber.tag(TAG).d(
                    "onConnect from package=%s uid=%s trusted=%s version=%s hints=[%s]",
                    controllerPackage,
                    controller.uid,
                    controller.isTrusted,
                    controller.controllerVersion,
                    hintKeys
                )
                if (shouldRejectWearController(controller)) {
                    Timber.tag(TAG).i(
                        "Rejecting Wear system controller connection from package=%s",
                        controllerPackage
                    )
                    return MediaSession.ConnectionResult.reject()
                }

                val defaultResult = super.onConnect(session, controller)
                val customCommands = listOf(
                    MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER,
                    MusicNotificationProvider.CUSTOM_COMMAND_LIKE,
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_FAVORITE_STATE,
                    MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE,
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON,
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF,
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE,
                    MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE,
                    MusicNotificationProvider.CUSTOM_COMMAND_COUNTED_PLAY,
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_DURATION,
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK,
                    MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_SLEEP_TIMER,
                    MusicNotificationProvider.CUSTOM_COMMAND_REFRESH_REPLAYGAIN,
                ).map { SessionCommand(it, Bundle.EMPTY) }

                val sessionCommandsBuilder = SessionCommands.Builder()
                    .addSessionCommands(defaultResult.availableSessionCommands.commands)
                customCommands.forEach { sessionCommandsBuilder.add(it) }
                grantArtworkUriPermissions(
                    controller.packageName,
                    listOfNotNull(session.player.currentMediaItem)
                )

                if (!controllerPackage.startsWith(APP_PACKAGE_PREFIX)) {
                    val isAuto = controllerPackage.startsWith("com.google.android.projection.gearhead") ||
                        controllerPackage.startsWith("com.google.android.gms.car") ||
                        controllerPackage.startsWith("com.google.android.apps.automotive") ||
                        controller.connectionHints.keySet().any { it.contains("automotive", ignoreCase = true) }
                    val isWear = BLOCKED_WEAR_CONTROLLER_PREFIXES.any { controllerPackage.startsWith(it) } ||
                        controller.connectionHints.keySet().any { key ->
                            WEAR_HINT_KEY_MARKERS.any { key.contains(it, ignoreCase = true) }
                        }
                    PerformanceMetrics.recordControllerConnected(
                        packageName = controllerPackage,
                        isAndroidAuto = isAuto,
                        isWear = isWear,
                        elapsedRealtimeMs = SystemClock.elapsedRealtime()
                    )
                }

                return MediaSession.ConnectionResult.accept(
                    sessionCommandsBuilder.build(),
                    defaultResult.availablePlayerCommands
                )
            }

            override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
                clearLastBrowsedParent(controller)
                super.onDisconnected(session, controller)
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                Timber.tag("MusicService")
                    .d("onCustomCommand received: ${customCommand.customAction}")
                when (customCommand.customAction) {
                    MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER -> {
                        closeNotificationPlayer()
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_COUNTED_PLAY -> {
                        val count = args.getInt("count", 1)
                        startCountedPlay(count)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_COUNTED_PLAY -> {
                        stopCountedPlay()
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_DURATION -> {
                        val minutes = args.getInt(
                            MusicNotificationProvider.EXTRA_SLEEP_TIMER_MINUTES,
                            0
                        )
                        setDurationSleepTimer(minutes)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SLEEP_TIMER_END_OF_TRACK -> {
                        val enabled = args.getBoolean(
                            MusicNotificationProvider.EXTRA_END_OF_TRACK_ENABLED,
                            true
                        )
                        setEndOfTrackSleepTimer(enabled)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_CANCEL_SLEEP_TIMER -> {
                        cancelSleepTimers()
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_TOGGLE_SHUFFLE -> {
                        val enabled = !isManualShuffleEnabled
                        updateManualShuffleState(session, enabled = enabled, broadcast = true)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON -> {
                        Timber.tag("MusicService")
                            .d("Executing SHUFFLE_ON. Current shuffleMode: ${session.player.shuffleModeEnabled}")
                        updateManualShuffleState(session, enabled = true, broadcast = true)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF -> {
                        Timber.tag("MusicService")
                            .d("Executing SHUFFLE_OFF. Current shuffleMode: ${session.player.shuffleModeEnabled}")
                        updateManualShuffleState(session, enabled = false, broadcast = true)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE -> {
                        val enabled = args.getBoolean(
                            MusicNotificationProvider.EXTRA_SHUFFLE_ENABLED,
                            false
                        )
                        updateManualShuffleState(session, enabled = enabled, broadcast = false)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE -> {
                        val currentMode = session.player.repeatMode
                        val newMode = when (currentMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                        session.player.repeatMode = newMode
                        refreshMediaSessionUi(session)
                        widgetUpdateManager.requestFullUpdate(true)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_LIKE -> {
                        val songId = session.player.currentMediaItem?.mediaId
                            ?: return@onCustomCommand Futures.immediateFuture(
                                SessionResult(SessionError.ERROR_UNKNOWN)
                            )
                        val targetFavoriteState = !favoriteSongIds.contains(songId)
                        return setCurrentSongFavoriteState(
                            session = session,
                            targetFavoriteState = targetFavoriteState
                        )
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SET_FAVORITE_STATE -> {
                        val enabled = args.getBoolean(
                            MusicNotificationProvider.EXTRA_FAVORITE_ENABLED,
                            false
                        )
                        return setCurrentSongFavoriteState(
                            session = session,
                            targetFavoriteState = enabled
                        )
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_REFRESH_REPLAYGAIN -> {
                        val editedPath = args.getString(MusicNotificationProvider.EXTRA_REPLAYGAIN_FILE_PATH)
                        val currentItem = session.player.currentMediaItem
                        val currentPath = currentItem?.mediaMetadata?.extras
                            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)
                        if (!editedPath.isNullOrBlank() && editedPath == currentPath) {
                            replayGainProcessor.forceRefresh(currentItem)
                        }
                    }
                }

                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId(AutoMediaBrowseTree.ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("PixelPlay")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return serviceScope.future {
                    try {
                        rememberLastBrowsedParent(browser, parentId)
                        val children = autoMediaBrowseTree.getChildren(parentId, page, pageSize)
                        grantArtworkUriPermissions(browser.packageName, children)
                        LibraryResult.ofItemList(children, params)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "onGetChildren failed for parentId=$parentId")
                        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }
                }
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                return serviceScope.future {
                    try {
                        val item = autoMediaBrowseTree.getItem(mediaId)
                        if (item != null) {
                            grantArtworkUriPermissions(browser.packageName, listOf(item))
                            LibraryResult.ofItem(item, null)
                        } else {
                            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "onGetItem failed for mediaId=$mediaId")
                        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }
                }
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> {
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: MediaLibraryService.LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                return serviceScope.future {
                    try {
                        val allResults = autoMediaBrowseTree.search(query)
                        val effectivePage = page.coerceAtLeast(0)
                        val effectivePageSize = if (pageSize > 0) pageSize else Int.MAX_VALUE
                        val offset = (effectivePage.toLong() * effectivePageSize.toLong())
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                        val pagedResults = allResults
                            .drop(offset)
                            .take(effectivePageSize)

                        grantArtworkUriPermissions(browser.packageName, pagedResults)
                        LibraryResult.ofItemList(pagedResults, params)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "onGetSearchResult failed for query=$query")
                        LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
                    }
                }
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                return serviceScope.future {
                    if (mediaItems.size == 1 && !controller.packageName.startsWith(APP_PACKAGE_PREFIX)) {
                        resolveContextQueueForRequestedItem(mediaItems.first(), controller)?.let { queue ->
                            grantArtworkUriPermissions(controller.packageName, queue.mediaItems)
                            return@future queue.mediaItems
                        }
                    }
                    resolveMediaItemsByIds(mediaItems).also { resolvedItems ->
                        grantArtworkUriPermissions(
                            controller.packageName,
                            resolvedItems.trustedArtworkGrantItems
                        )
                    }.mediaItems
                }
            }

            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                return serviceScope.future {
                    val requestedIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
                    val requestedItem = mediaItems.getOrNull(requestedIndex)

                    val contextQueue = if (requestedItem != null && !controller.packageName.startsWith(APP_PACKAGE_PREFIX)) {
                        resolveContextQueueForRequestedItem(requestedItem, controller)
                    } else {
                        null
                    }
                    if (contextQueue != null) {
                        grantArtworkUriPermissions(controller.packageName, contextQueue.mediaItems)
                        return@future MediaSession.MediaItemsWithStartPosition(
                            contextQueue.mediaItems,
                            contextQueue.startIndex,
                            startPositionMs
                        )
                    }

                    val resolvedItems = resolveMediaItemsByIds(mediaItems)
                    grantArtworkUriPermissions(
                        controller.packageName,
                        resolvedItems.trustedArtworkGrantItems
                    )
                    val safeStartIndex = requestedIndex.coerceIn(
                        0,
                        (resolvedItems.mediaItems.size - 1).coerceAtLeast(0)
                    )
                    MediaSession.MediaItemsWithStartPosition(
                        resolvedItems.mediaItems,
                        safeStartIndex,
                        startPositionMs
                    )
                }
            }
        }

        mediaSession = MediaLibrarySession.Builder(this, engine.masterPlayer, callback)
            .setSessionActivity(getOpenAppPendingIntent())
            .setBitmapLoader(CoilBitmapLoader(this, serviceScope))
            .build()

        val localOnlyProvider = LocalOnlyMediaNotificationProvider(this).also {
            it.setSmallIcon(R.drawable.monochrome_player)
        }
        setMediaNotificationProvider(localOnlyProvider)
        if (temporaryForegroundStartedInOnCreate) {
            serviceScope.launch {
                delay(2_000L)
                if (mediaSession?.player?.hasForegroundPlaybackIntent() != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        serviceScope.launch {
            replayGainProcessor.setEnabled(userPreferencesRepository.replayGainEnabledFlow.first())
            replayGainProcessor.setUseAlbumGain(userPreferencesRepository.replayGainUseAlbumGainFlow.first())
            restorePlaybackQueueSnapshotIfNeeded()
            replayGainProcessor.apply(mediaSession?.player?.currentMediaItem)
            mediaSession?.let { refreshMediaSessionUi(it) }
            widgetUpdateManager.requestFullUpdate(true)
        }

        serviceScope.launch {
            musicRepository.getFavoriteSongIdsFlow().collect { ids ->
                Timber.tag("MusicService")
                    .d("favoriteSongIdsFlow(Room) collected. New ids size: ${ids.size}")
                val oldIds = favoriteSongIds
                favoriteSongIds = ids
                val currentSongId = mediaSession?.player?.currentMediaItem?.mediaId
                if (currentSongId != null) {
                    val wasFavorite = oldIds.contains(currentSongId)
                    val isFavorite = ids.contains(currentSongId)
                    if (wasFavorite != isFavorite) {
                        Timber.tag("MusicService")
                            .d("Favorite status changed for current song. Updating notification.")
                        mediaSession?.let { refreshMediaSessionUi(it) }
                        widgetUpdateManager.requestFullUpdate(true)
                    }
                }
            }
        }
    }

    private fun shouldRejectWearController(controller: MediaSession.ControllerInfo): Boolean {
        val controllerPackage = controller.packageName
        if (controllerPackage.startsWith(APP_PACKAGE_PREFIX)) {
            return false
        }
        val blockedByPackage = BLOCKED_WEAR_CONTROLLER_PREFIXES.any { prefix ->
            controllerPackage.startsWith(prefix)
        }
        if (blockedByPackage) {
            return true
        }

        val hasWearHints = controller.connectionHints.keySet().any { key ->
            WEAR_HINT_KEY_MARKERS.any { marker ->
                key.contains(marker, ignoreCase = true)
            }
        }
        return hasWearHints
    }

    private fun createSleepTimerPendingIntent(): PendingIntent {
        val intent = Intent(this, SleepTimerReceiver::class.java).apply {
            action = ACTION_SLEEP_TIMER_EXPIRED
            setPackage(packageName)
        }
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelDurationSleepTimerInternal() {
        alarmManager.cancel(createSleepTimerPendingIntent())
    }

    private fun setDurationSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimers()
            return
        }
        endOfTrackTimerSongId = null
        val triggerAtMillis = System.currentTimeMillis() + (minutes * 60_000L)
        val pendingIntent = createSleepTimerPendingIntent()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }
            Timber.tag(TAG).d("Sleep timer set from Wear for %d minutes", minutes)
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Exact alarm denied; using inexact sleep timer")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun setEndOfTrackSleepTimer(enabled: Boolean) {
        if (!enabled) {
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("End-of-track timer disabled from Wear")
            return
        }
        cancelDurationSleepTimerInternal()
        val currentSongId = mediaSession?.player?.currentMediaItem?.mediaId
        if (currentSongId.isNullOrBlank()) {
            endOfTrackTimerSongId = null
            Timber.tag(TAG).d("End-of-track timer ignored: no active song")
            return
        }
        endOfTrackTimerSongId = currentSongId
        Timber.tag(TAG).d("End-of-track timer set from Wear for mediaId=%s", currentSongId)
    }

    private fun cancelSleepTimers() {
        cancelDurationSleepTimerInternal()
        endOfTrackTimerSongId = null
        Timber.tag(TAG).d("Sleep timers cancelled from Wear")
    }

    private fun startTemporaryForegroundForCommand() {
        val notification = NotificationCompat.Builder(
            this,
            PixelPlayApplication.NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_processing_action))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(getOpenAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to promote service to foreground for external command")
        }
    }

    private fun isServiceAlreadyForeground(): Boolean {
        val player = mediaSession?.player ?: return false
        return player.hasForegroundPlaybackIntent()
    }

    private fun Player.hasForegroundPlaybackIntent(): Boolean {
        return playWhenReady &&
            mediaItemCount > 0 &&
            playbackState != Player.STATE_IDLE &&
            playbackState != Player.STATE_ENDED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedTemporaryForegroundInOnCreate = temporaryForegroundStartedInOnCreate
        temporaryForegroundStartedInOnCreate = false
        val pendingMediaButtonForegroundStart = consumePendingMediaButtonForegroundStart()
        val forcedForegroundStart =
            intent?.getBooleanExtra(EXTRA_FORCE_FOREGROUND_ON_START, false) == true
        val isMediaButtonIntent = intent?.action == Intent.ACTION_MEDIA_BUTTON
        val needsTemporaryForeground = forcedForegroundStart ||
            pendingMediaButtonForegroundStart ||
            (isMediaButtonIntent &&
                !startedTemporaryForegroundInOnCreate &&
                !isServiceAlreadyForeground()) ||
            when (intent?.action) {
                PlayerActions.PLAY_PAUSE,
                PlayerActions.NEXT,
                PlayerActions.PREVIOUS,
                PlayerActions.FAVORITE,
                PlayerActions.PLAY_FROM_QUEUE,
                PlayerActions.SHUFFLE,
                PlayerActions.REPEAT -> true
                else -> false
            }
        if (needsTemporaryForeground && !startedTemporaryForegroundInOnCreate) {
            startTemporaryForegroundForCommand()
        }

        intent?.action?.let { action ->
            Timber.tag(TAG).d("onStartCommand widget action: %s", action)
            val player = mediaSession?.player ?: engine.masterPlayer
            when (action) {
                PlayerActions.PLAY_PAUSE -> {
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.playWhenReady = !player.playWhenReady
                    widgetUpdateManager.requestFullUpdate(true)
                }
                PlayerActions.NEXT -> {
                    player.seekToNext()
                    widgetUpdateManager.requestFullUpdate(true)
                }
                PlayerActions.PREVIOUS -> {
                    player.seekToPrevious()
                    widgetUpdateManager.requestFullUpdate(true)
                }
                PlayerActions.FAVORITE -> {
                    val songId = player.currentMediaItem?.mediaId
                    if (!songId.isNullOrBlank()) {
                        serviceScope.launch {
                            val updatedFavorite = musicRepository.toggleFavoriteStatus(songId)
                            favoriteSongIds = if (updatedFavorite) {
                                favoriteSongIds + songId
                            } else {
                                favoriteSongIds - songId
                            }
                            mediaSession?.let { refreshMediaSessionUi(it) }
                            widgetUpdateManager.requestFullUpdate(true)
                        }
                    }
                }
                PlayerActions.PLAY_FROM_QUEUE -> {
                    val songId = intent.getLongExtra("song_id", -1L)
                    if (songId != -1L) {
                        val timeline = player.currentTimeline
                        if (!timeline.isEmpty) {
                            val window = Timeline.Window()
                            for (i in 0 until timeline.windowCount) {
                                timeline.getWindow(i, window)
                                if (window.mediaItem.mediaId.toLongOrNull() == songId) {
                                    player.seekTo(i, C.TIME_UNSET)
                                    player.prepare()
                                    player.play()
                                    break
                                }
                            }
                        }
                    }
                }
                PlayerActions.SHUFFLE -> {
                    val newState = !isManualShuffleEnabled
                    mediaSession?.let { session ->
                        updateManualShuffleState(session, enabled = newState, broadcast = true)
                    } ?: run {
                        isManualShuffleEnabled = newState
                        widgetUpdateManager.requestFullUpdate(true)
                    }
                }
                PlayerActions.REPEAT -> {
                    val newMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.repeatMode = newMode
                    widgetUpdateManager.requestFullUpdate(true)
                }
                ACTION_SLEEP_TIMER_EXPIRED -> {
                    Timber.tag(TAG).d("Sleep timer expired action received. Pausing player.")
                    cancelDurationSleepTimerInternal()
                    player.pause()
                }
            }
        }
        val startCommandResult = super.onStartCommand(intent, flags, startId)
        if (needsTemporaryForeground || startedTemporaryForegroundInOnCreate) {
            if (mediaSession?.player?.hasForegroundPlaybackIntent() != true) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (needsTemporaryForeground) {
                    stopSelfResult(startId)
                }
            }
        }
        return startCommandResult
    }

    private fun getNavidromeId(mediaItem: MediaItem?): String? {
        if (mediaItem == null) return null
        return mediaItem.mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_NAVIDROME_ID)
            ?: mediaItem.mediaId.let { if (it.startsWith("navidrome_")) it.substringAfter("navidrome_") else null }
            ?: mediaItem.mediaMetadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)?.let {
                if (it.startsWith("navidrome://")) it.substringAfter("navidrome://") else null
            }
    }

    private fun isNavidromeMediaItem(mediaItem: MediaItem?): Boolean {
        return getNavidromeId(mediaItem) != null
    }

    private fun reportNavidromePlayback(state: String, mediaItem: MediaItem? = engine.masterPlayer.currentMediaItem) {
        val player = engine.masterPlayer
        val targetItem = mediaItem ?: return
        val navidromeId = getNavidromeId(targetItem) ?: return

        val positionMs = if (targetItem === player.currentMediaItem) {
            player.currentPosition
        } else {
            targetItem.mediaMetadata.extras?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION) ?: 0L
        }
        val playbackRate = player.playbackParameters.speed

        appScope.launch(Dispatchers.IO) {
            navidromeRepository.reportPlayback(
                navidromeId = navidromeId,
                positionMs = positionMs,
                state = state,
                playbackRate = playbackRate
            )
        }
    }

    private var navidromePlaybackReportJob: Job? = null

    private fun startNavidromePlaybackReporting() {
        navidromePlaybackReportJob?.cancel()
        navidromePlaybackReportJob = serviceScope.launch {
            while (true) {
                delay(30_000)
                val player = engine.masterPlayer
                if (player.isPlaying && isNavidromeMediaItem(player.currentMediaItem)) {
                    reportNavidromePlayback("playing")
                }
            }
        }
    }

    private fun stopNavidromePlaybackReporting() {
        navidromePlaybackReportJob?.cancel()
        navidromePlaybackReportJob = null
    }

    private val playerListener = object : Player.Listener {
        override fun onVolumeChanged(volume: Float) {
            replayGainProcessor.onPlayerVolumeChanged(volume)
            if (pauseOnVolumeZeroEnabled && volume == 0f) {
                val player = mediaSession?.player ?: engine.masterPlayer
                if (player.isPlaying) {
                    player.pause()
                    Timber.tag(TAG).d("pauseOnVolumeZero: paused playback because volume reached 0")
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = mediaSession?.player ?: engine.masterPlayer
            Timber.tag(TAG).d("onIsPlayingChanged: $isPlaying. Duration: ${player.duration}, Seekable: ${player.isCurrentMediaItemSeekable}")
            PlaybackActivityTracker.setPlaybackActive(isPlaying)
            syncLocalListeningStatsFromPlayer(player)

            if (isPlaying) {
                reportNavidromePlayback("playing")
                startNavidromePlaybackReporting()
            } else {
                val state = if (player.playbackState == Player.STATE_ENDED) "stopped" else "paused"
                reportNavidromePlayback(state)
                stopNavidromePlaybackReporting()
            }

            if (isPlaying) {
                replayGainProcessor.reapplyLastAppliedVolume(player)
            }
            widgetUpdateManager.requestFullUpdate(true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            when {
                playWhenReady -> clearHeadsetReconnectResume()
                !resumeOnHeadsetReconnectEnabled -> clearHeadsetReconnectResume()
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                    shouldResumeAfterHeadsetReconnect = true
                    lastNoisyPauseRealtimeMs = SystemClock.elapsedRealtime()
                    Timber.tag(TAG).d("Marked playback for headset reconnect resume")
                }
                else -> clearHeadsetReconnectResume()
            }
            widgetUpdateManager.requestFullUpdate(true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            val canSeek = availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            val player = engine.masterPlayer
            Timber.tag(TAG).w("onAvailableCommandsChanged. Can Seek Command? $canSeek. IsSeekable? ${player.isCurrentMediaItemSeekable}. Duration: ${player.duration}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Timber.tag(TAG).d("Playback state changed: $playbackState")
            if (playbackState == Player.STATE_ENDED) {
                listeningStatsTracker.finalizeCurrentSession()
                val mediaItem = (mediaSession?.player ?: engine.masterPlayer).currentMediaItem
                getNavidromeId(mediaItem)?.let { navidromeId ->
                    appScope.launch(Dispatchers.IO) {
                        navidromeRepository.scrobble(navidromeId, submission = true)
                    }
                }

                endOfTrackTimerSongId = null
                reportNavidromePlayback("stopped")
                stopNavidromePlaybackReporting()
            } else {
                syncLocalListeningStatsFromPlayer(mediaSession?.player ?: engine.masterPlayer)
            }
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist(immediate = playbackState == Player.STATE_IDLE)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            widgetUpdateManager.requestFullUpdate(true)
            schedulePlaybackSnapshotPersist(immediate = timeline.isEmpty)
            val player = engine.masterPlayer
            val currentItem = player.currentMediaItem
            if (currentItem != null) {
                runCatching { replayGainProcessor.prefetch(currentItem) }
            }
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex != C.INDEX_UNSET) {
                runCatching { replayGainProcessor.prefetch(player.getMediaItemAt(nextIndex)) }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val state = if (engine.masterPlayer.isPlaying) "playing" else "paused"
                reportNavidromePlayback(state)
            }

            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                val finishedItem = oldPosition.mediaItem
                if (isNavidromeMediaItem(finishedItem)) {
                    val prevId = getNavidromeId(finishedItem)
                    reportNavidromePlayback("stopped", finishedItem)
                    if (prevId != null) {
                        appScope.launch(Dispatchers.IO) {
                            navidromeRepository.scrobble(prevId, submission = true)
                        }
                    }
                }
            }

            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                reason == Player.DISCONTINUITY_REASON_SEEK
            ) {
                val currentItem = mediaSession?.player?.currentMediaItem
                val oldMediaId = oldPosition.mediaItem?.mediaId
                val newMediaId = newPosition.mediaItem?.mediaId
                if (oldMediaId != null && oldMediaId == newMediaId) {
                    replayGainProcessor.reapplyLastAppliedVolume(engine.masterPlayer)
                } else {
                    replayGainProcessor.apply(currentItem)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncLocalListeningStatsFromPlayer(mediaSession?.player ?: engine.masterPlayer, forceNewSession = true)
            if (isNavidromeMediaItem(mediaItem)) {
                reportNavidromePlayback("starting")
                if (engine.masterPlayer.isPlaying) {
                    startNavidromePlaybackReporting()
                }
            } else {
                stopNavidromePlaybackReporting()
            }

            val eotTargetSongId = endOfTrackTimerSongId
            if (!eotTargetSongId.isNullOrBlank()) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    val previousSongId = engine.masterPlayer.run {
                        if (previousMediaItemIndex != C.INDEX_UNSET) {
                            runCatching { getMediaItemAt(previousMediaItemIndex).mediaId }.getOrNull()
                        } else {
                            null
                        }
                    }
                    if (previousSongId == eotTargetSongId) {
                        endOfTrackTimerSongId = null
                        engine.masterPlayer.seekTo(0L)
                        engine.masterPlayer.pause()
                        Timber.tag(TAG).d("Paused playback at end of track from Wear timer")
                    }
                } else if (mediaItem?.mediaId != eotTargetSongId) {
                    endOfTrackTimerSongId = null
                    Timber.tag(TAG).d("Cleared end-of-track timer after manual track change")
                }
            }
            mediaItem?.let { replayGainProcessor.prefetch(it) }
            replayGainProcessor.apply(mediaItem)
            val player = engine.masterPlayer
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex != C.INDEX_UNSET) {
                runCatching { replayGainProcessor.prefetch(player.getMediaItemAt(nextIndex)) }
            }
            widgetUpdateManager.requestFullUpdate(false)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            widgetUpdateManager.requestFullUpdate(true)
            mediaSession?.let { refreshMediaSessionUiWithFollowUp(it) }
            replayGainProcessor.onMediaMetadataChanged(mediaSession?.player?.currentMediaItem)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            Timber.tag("MusicService")
                .d("playerListener.onShuffleModeEnabledChanged: $shuffleModeEnabled")
            widgetUpdateManager.requestFullUpdate(true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            widgetUpdateManager.requestFullUpdate(true)
            mediaSession?.let { refreshMediaSessionUi(it) }
            schedulePlaybackSnapshotPersist()
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.tag(TAG).e(error, "Error en el reproductor: ")
            serviceScope.launch {
                val currentMediaItem = mediaSession?.player?.currentMediaItem
                val trackTitle = currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: currentMediaItem?.mediaId
                    ?: getString(R.string.common_unknown_track)
                val errorMessage = error.localizedMessage ?: error.message ?: "Unknown error"
                val toastMessage = getString(R.string.player_playback_error, "$trackTitle ($errorMessage)")
                android.widget.Toast.makeText(this@MusicService, toastMessage, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val allowBackground = keepPlayingInBackground

        if (!allowBackground) {
            stopPlaybackAndUnload(
                reason = "task_removed_background_disabled"
            )
            return
        }

        if (player?.hasForegroundPlaybackIntent() != true) {
            stopPlaybackAndUnload(
                reason = "task_removed_not_playing"
            )
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        PlaybackActivityTracker.setPlaybackActive(false)
        listeningStatsTracker.finalizeCurrentSession(forceSynchronousPersistence = true)
        reportNavidromePlayback("stopped")
        stopNavidromePlaybackReporting()
        playbackSnapshotPersistJob?.cancel()
        mediaSessionButtonRefreshJob?.cancel()
        followUpMediaSessionUiRefreshJob?.cancel()
        widgetUpdateManager.cancel()
        castSyncCoordinator.stop()
        unregisterHeadsetReconnectMonitor()
        unregisterSystemVolumeObserver()
        wearStatePublisher.clearState()
        replayGainProcessor.cancel()

        engine.removePlayerSwapListener(playerSwapListener)
        engine.removeTransitionDisplayPlayerListener(transitionDisplayPlayerListener)
        engine.removeTransitionFinishedListener(transitionFinishedListener)
        engine.setOnPlayerAboutToBeReleasedListener {}
        mediaSession?.player?.removeListener(playerListener)
        engine.masterPlayer.removeListener(playerListener)

        mediaSession?.run {
            release()
            mediaSession = null
        }
        engine.release()
        controller.release()
        serviceScope.cancel()
        Thread.currentThread().setUncaughtExceptionHandler(previousMainThreadExceptionHandler)
        previousMainThreadExceptionHandler = null
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= 10) {
            Timber.tag(TAG).d("onTrimMemory(level=%d): releasing widget bitmap caches", level)
            invalidateCachedWidgetArtwork()
            widgetUpdateManager.clearCachedState()
        }
    }

    private fun registerHeadsetReconnectMonitor() {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (!addedDevices.any(::isReconnectableHeadsetOutput)) return
                maybeResumeAfterHeadsetReconnect()
            }
        }

        audioManager.registerAudioDeviceCallback(callback, null)
        headsetReconnectCallback = callback
    }

    private fun unregisterHeadsetReconnectMonitor() {
        headsetReconnectCallback?.let { callback ->
            runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
        }
        headsetReconnectCallback = null
        clearHeadsetReconnectResume()
    }

    private fun registerSystemVolumeObserver() {
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            systemVolumeObserver
        )
    }

    private fun unregisterSystemVolumeObserver() {
        runCatching { contentResolver.unregisterContentObserver(systemVolumeObserver) }
    }

    private fun maybeResumeAfterHeadsetReconnect() {
        if (!resumeOnHeadsetReconnectEnabled || !shouldResumeAfterHeadsetReconnect) return

        val elapsedSinceNoisyPause = SystemClock.elapsedRealtime() - lastNoisyPauseRealtimeMs
        if (elapsedSinceNoisyPause > HEADSET_RECONNECT_RESUME_WINDOW_MS) {
            clearHeadsetReconnectResume()
            return
        }

        if (!hasReconnectableHeadsetOutput()) {
            return
        }

        val player = engine.masterPlayer
        if (
            player.currentMediaItem == null ||
            player.playWhenReady ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            clearHeadsetReconnectResume()
            return
        }

        Timber.tag(TAG).d("Resuming playback after headset reconnect")
        clearHeadsetReconnectResume()
        player.play()
    }

    private fun hasReconnectableHeadsetOutput(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any(::isReconnectableHeadsetOutput)
    }

    private fun isReconnectableHeadsetOutput(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
            else -> false
        }
    }

    private fun clearHeadsetReconnectResume() {
        shouldResumeAfterHeadsetReconnect = false
        lastNoisyPauseRealtimeMs = 0L
    }

    private fun schedulePlaybackSnapshotPersist(immediate: Boolean = false) {
        if (isPlaybackUnloadInProgress) {
            return
        }
        playbackSnapshotPersistJob?.cancel()
        playbackSnapshotPersistJob = serviceScope.launch {
            if (!immediate) {
                delay(PLAYBACK_SNAPSHOT_DEBOUNCE_MS)
            }
            persistPlaybackSnapshot()
        }
    }

    private suspend fun persistPlaybackSnapshot(playWhenReadyOverride: Boolean? = null) {
        if (isRestoringPlaybackSnapshot) return
        val snapshot = capturePlaybackSnapshot(playWhenReadyOverride)
        runCatching {
            userPreferencesRepository.setPlaybackQueueSnapshot(snapshot)
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Failed to persist playback snapshot")
        }
    }

    private suspend fun capturePlaybackSnapshot(playWhenReadyOverride: Boolean? = null): PlaybackQueueSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            capturePlaybackSnapshotFromPlayer(playWhenReadyOverride)
        }

    private fun capturePlaybackSnapshotFromPlayer(
        playWhenReadyOverride: Boolean? = null
    ): PlaybackQueueSnapshot? {
        val player = engine.masterPlayer
        val mediaItemCount = player.mediaItemCount
        if (mediaItemCount <= 0) {
            return null
        }

        val snapshotItems = ArrayList<PlaybackQueueItemSnapshot>(mediaItemCount)
        for (index in 0 until mediaItemCount) {
            val mediaItem = player.getMediaItemAt(index)
            val metadata = mediaItem.mediaMetadata
            val uri = mediaItem.localConfiguration?.uri?.toString()
                ?: metadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)

            if (mediaItem.mediaId.isBlank() || uri.isNullOrBlank()) {
                continue
            }

            val durationMs = metadata.extras
                ?.getLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION)
                ?.takeIf { it > 0L }

            val filePath = metadata.extras
                ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)
                ?.takeIf { it.isNotBlank() }
            snapshotItems.add(
                PlaybackQueueItemSnapshot(
                    mediaId = mediaItem.mediaId,
                    uri = uri,
                    title = metadata.title?.toString(),
                    artist = metadata.artist?.toString(),
                    albumTitle = metadata.albumTitle?.toString(),
                    artworkUri = resolveStoredArtworkUriString(metadata),
                    durationMs = durationMs,
                    filePath = filePath,
                )
            )
        }

        if (snapshotItems.isEmpty()) {
            return null
        }

        val currentMediaId = player.currentMediaItem?.mediaId
        val indexFromMediaId = currentMediaId
            ?.let { id -> snapshotItems.indexOfFirst { it.mediaId == id } }
            ?.takeIf { it >= 0 }

        val safeCurrentIndex = when {
            indexFromMediaId != null -> indexFromMediaId
            player.currentMediaItemIndex in snapshotItems.indices -> player.currentMediaItemIndex
            else -> 0
        }

        val safeRepeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF,
            Player.REPEAT_MODE_ONE,
            Player.REPEAT_MODE_ALL -> player.repeatMode
            else -> Player.REPEAT_MODE_OFF
        }

        return PlaybackQueueSnapshot(
            items = snapshotItems,
            currentMediaId = currentMediaId,
            currentIndex = safeCurrentIndex,
            currentPositionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = playWhenReadyOverride ?: player.playWhenReady,
            repeatMode = safeRepeatMode,
            shuffleEnabled = isManualShuffleEnabled,
        )
    }

    private suspend fun restorePlaybackQueueSnapshotIfNeeded() {
        val alreadyHasQueue = withContext(Dispatchers.Main.immediate) {
            engine.masterPlayer.mediaItemCount > 0
        }
        if (alreadyHasQueue) return

        val snapshot = runCatching {
            userPreferencesRepository.getPlaybackQueueSnapshotOnce()
        }.getOrNull() ?: return

        if (snapshot.items.isEmpty()) {
            return
        }

        val allowBackgroundPlayback = runCatching {
            userPreferencesRepository.keepPlayingInBackgroundFlow.first()
        }.getOrDefault(keepPlayingInBackground)
        val shouldRestorePlaying = snapshot.playWhenReady && allowBackgroundPlayback

        val restoredItems = snapshot.items.mapNotNull(::buildMediaItemFromSnapshot)
        if (restoredItems.isEmpty()) {
            userPreferencesRepository.setPlaybackQueueSnapshot(null)
            return
        }

        val resolvedIndex = when {
            snapshot.currentIndex in restoredItems.indices -> snapshot.currentIndex
            !snapshot.currentMediaId.isNullOrBlank() -> {
                restoredItems.indexOfFirst { it.mediaId == snapshot.currentMediaId }
                    .takeIf { it >= 0 } ?: 0
            }
            else -> 0
        }

        val preparedItems = restoredItems.toMutableList()
        preparedItems.getOrNull(resolvedIndex)?.let { currentItem ->
            val resolvedCurrentItem = runCatching { engine.resolveMediaItem(currentItem) }.getOrNull()
            if (resolvedCurrentItem != null && resolvedCurrentItem != currentItem) {
                val originalPath = currentItem.mediaMetadata.extras
                    ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)
                val resolvedPath = resolvedCurrentItem.mediaMetadata.extras
                    ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH)
                val itemToUse = if (!originalPath.isNullOrBlank() && resolvedPath.isNullOrBlank()) {
                    val newExtras = Bundle(resolvedCurrentItem.mediaMetadata.extras ?: Bundle())
                    newExtras.putString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH, originalPath)
                    resolvedCurrentItem.buildUpon()
                        .setMediaMetadata(
                            resolvedCurrentItem.mediaMetadata.buildUpon()
                                .setExtras(newExtras)
                                .build()
                        )
                        .build()
                } else {
                    resolvedCurrentItem
                }
                preparedItems[resolvedIndex] = itemToUse
            }
        }

        withContext(Dispatchers.Main.immediate) {
            val player = engine.masterPlayer
            if (player.mediaItemCount > 0) {
                return@withContext
            }

            val safeRepeatMode = when (snapshot.repeatMode) {
                Player.REPEAT_MODE_OFF,
                Player.REPEAT_MODE_ONE,
                Player.REPEAT_MODE_ALL -> snapshot.repeatMode
                else -> Player.REPEAT_MODE_OFF
            }

            isRestoringPlaybackSnapshot = true
            try {
                player.setMediaItems(
                    preparedItems,
                    resolvedIndex,
                    snapshot.currentPositionMs.coerceAtLeast(0L)
                )
                if (shouldRestorePlaying || preparedItems.size <= PAUSED_RESTORE_PREPARE_QUEUE_LIMIT) {
                    player.prepare()
                }
                player.repeatMode = safeRepeatMode
                player.shuffleModeEnabled = false
                isManualShuffleEnabled = snapshot.shuffleEnabled
                if (shouldRestorePlaying) {
                    player.playWhenReady = true
                } else {
                    player.playWhenReady = false
                }
            } finally {
                isRestoringPlaybackSnapshot = false
                engine.masterPlayer.currentMediaItem?.let {
                    replayGainProcessor.prefetch(it)
                }
            }
        }

        Timber.tag(TAG).i(
            "Restored playback snapshot: items=%d index=%d playWhenReady=%s",
            restoredItems.size,
            snapshot.currentIndex,
            shouldRestorePlaying
        )
        schedulePlaybackSnapshotPersist(immediate = true)
    }

    private fun buildMediaItemFromSnapshot(snapshotItem: PlaybackQueueItemSnapshot): MediaItem? {
        if (snapshotItem.mediaId.isBlank() || snapshotItem.uri.isBlank()) {
            return null
        }

        val metadataBuilder = MediaMetadata.Builder()
        snapshotItem.title?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setTitle(it) }
        snapshotItem.artist?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setArtist(it) }
        snapshotItem.albumTitle?.takeIf { it.isNotBlank() }?.let { metadataBuilder.setAlbumTitle(it) }
        MediaItemBuilder.externalControllerArtworkUri(this, snapshotItem.artworkUri)
            ?.let { metadataBuilder.setArtworkUri(it) }

        val extras = Bundle().apply {
            putBoolean(
                MediaItemBuilder.EXTERNAL_EXTRA_FLAG,
                snapshotItem.mediaId.startsWith("external:")
            )
            putString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI, snapshotItem.uri)
            snapshotItem.albumTitle?.takeIf { it.isNotBlank() }?.let {
                putString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM, it)
            }
            snapshotItem.artworkUri?.takeIf { it.isNotBlank() }?.let {
                putString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART, it)
            }
            snapshotItem.durationMs?.takeIf { it > 0L }?.let {
                putLong(MediaItemBuilder.EXTERNAL_EXTRA_DURATION, it)
            }
            snapshotItem.filePath?.takeIf { it.isNotBlank() }?.let {
                putString(MediaItemBuilder.EXTERNAL_EXTRA_FILE_PATH, it)
            }
        }
        metadataBuilder.setExtras(extras)

        return MediaItem.Builder()
            .setMediaId(snapshotItem.mediaId)
            .setUri(MediaItemBuilder.playbackUri(snapshotItem.uri))
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun getOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            setPackage(packageName)
            action = WearIntents.ACTION_OPEN_PLAYER
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ACTION_SHOW_PLAYER", true)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private var followUpMediaSessionUiRefreshJob: Job? = null
    private var mediaSessionButtonRefreshJob: Job? = null
    private var lastAppliedMediaButtonSignature: String? = null

    private suspend fun resolveCurrentMediaIdForWear(): String? {
        val remoteSongId = castSyncCoordinator.resolveRemoteSnapshot()?.songId
        if (!remoteSongId.isNullOrBlank()) {
            return remoteSongId
        }
        val player = engine.masterPlayer
        return withContext(Dispatchers.Main) { player.currentMediaItem?.mediaId }
    }

    private fun buildWearQueueRevision(
        timeline: Timeline,
        currentIndex: Int,
        currentMediaId: String?,
    ): String {
        val remoteClient = castSyncCoordinator.currentRemoteMediaClient()
        val remoteStatus = remoteClient?.mediaStatus
        val remoteQueueItems = remoteStatus?.queueItems.orEmpty()
        if (remoteQueueItems.isNotEmpty()) {
            val remoteCurrentIndex = remoteQueueItems.indexOfFirst {
                it.itemId == remoteStatus?.currentItemId
            }.takeIf { it >= 0 } ?: 0
            val remoteTokens = remoteQueueItems.map { item ->
                item.customData
                    ?.optString("songId")
                    ?.takeIf { it.isNotBlank() }
                    ?: item.media?.contentId
                    ?: item.itemId.toString()
            }
            return encodeWearQueueRevision(remoteTokens, remoteStatus?.currentItemId ?: 0)
        }

        if (timeline.isEmpty) {
            return currentMediaId.orEmpty()
        }

        val window = Timeline.Window()
        val tokens = buildList(timeline.windowCount) {
            for (index in 0 until timeline.windowCount) {
                timeline.getWindow(index, window)
                val mediaItem = window.mediaItem
                add(
                    mediaItem.mediaId.ifBlank {
                        mediaItem.localConfiguration?.uri?.toString()
                            ?: mediaItem.mediaMetadata.title?.toString()
                            ?: index.toString()
                    }
                )
            }
        }
        val safeCurrentIndex = currentIndex.coerceIn(0, (timeline.windowCount - 1).coerceAtLeast(0))
        return encodeWearQueueRevision(tokens, safeCurrentIndex)
    }

    private fun encodeWearQueueRevision(queueTokens: List<String>, currentIndex: Int): String {
        if (queueTokens.isEmpty()) return ""
        return buildString {
            append(currentIndex)
            append('|')
            queueTokens.forEachIndexed { index, token ->
                if (index > 0) append(',')
                append(token)
            }
        }.hashCode().toString()
    }

    private suspend fun buildPlayerInfo(): PlayerInfo {
        val player = engine.masterPlayer
        var currentItem: MediaItem? = null
        var isPlaying = false
        var repeatMode = Player.REPEAT_MODE_OFF
        var currentPosition = 0L
        var totalDuration = 0L
        var snapshotWindowIndex = 0
        var snapshotTimeline: Timeline = Timeline.EMPTY

        withContext(Dispatchers.Main) {
            currentItem = player.currentMediaItem
            isPlaying = player.isPlaying
            repeatMode = player.repeatMode
            currentPosition = player.currentPosition
            totalDuration = player.duration.coerceAtLeast(0)
            snapshotWindowIndex = player.currentMediaItemIndex
            snapshotTimeline = player.currentTimeline
        }

        var shuffleEnabled = isManualShuffleEnabled

        var title = currentItem?.mediaMetadata?.title?.toString().orEmpty()
        var artist = currentItem?.mediaMetadata?.artist?.toString().orEmpty()
        var mediaId = currentItem?.mediaId
        var artworkUri = resolveWidgetArtworkUriCandidates(currentItem?.mediaMetadata).firstOrNull()
        var artworkData = currentItem?.mediaMetadata?.artworkData

        castSyncCoordinator.resolveRemoteSnapshot()?.let { remote ->
            if (remote.title.isNotBlank()) {
                title = remote.title
            }
            if (remote.artist.isNotBlank()) {
                artist = remote.artist
            }
            if (!remote.songId.isNullOrBlank()) {
                mediaId = remote.songId
            }
            if (remote.artworkUri != null) {
                artworkUri = remote.artworkUri
            }
            isPlaying = remote.isPlaying
            currentPosition = remote.currentPositionMs
            if (remote.totalDurationMs > 0L) {
                totalDuration = remote.totalDurationMs
            }
            repeatMode = remote.repeatMode
            shuffleEnabled = remote.isShuffleEnabled
        }

        val artworkCandidates = resolveWidgetArtworkUriCandidates(
            metadata = currentItem?.mediaMetadata,
            preferredArtworkUri = artworkUri,
        )
        val (artBytes, artUriString) = getAlbumArtForWidget(
            mediaId = mediaId,
            embeddedArt = artworkData,
            artUris = artworkCandidates,
        )

        val (playerTheme, paletteStyle, colorAccuracyLevel) = withContext(Dispatchers.IO) {
            Triple(
                themePreferencesRepository.playerThemePreferenceFlow.first(),
                AlbumArtPaletteStyle.fromStorageKey(themePreferencesRepository.albumArtPaletteStyleFlow.first().storageKey),
                AlbumArtColorAccuracy.clamp(themePreferencesRepository.albumArtColorAccuracyFlow.first())
            )
        }

        val schemePair: ColorSchemePair? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && playerTheme == ThemePreference.DYNAMIC ->
                ColorSchemePair(
                    light = dynamicLightColorScheme(applicationContext),
                    dark = dynamicDarkColorScheme(applicationContext)
                )
            artUriString != null ->
                if (
                    artUriString == cachedSchemeArtUri &&
                    paletteStyle == cachedSchemePaletteStyle &&
                    colorAccuracyLevel == cachedSchemeColorAccuracy
                ) {
                    cachedColorSchemePair
                } else {
                    colorSchemeProcessor.getOrGenerateColorScheme(
                        albumArtUri = artUriString,
                        paletteStyle = paletteStyle,
                        colorAccuracyLevel = colorAccuracyLevel
                    ).also {
                        cachedSchemeArtUri = artUriString
                        cachedSchemePaletteStyle = paletteStyle
                        cachedSchemeColorAccuracy = colorAccuracyLevel
                        cachedColorSchemePair = it
                    }
                }
            else -> null
        }

        val widgetColors = schemePair?.let {
            WidgetThemeColors(
                lightSurfaceContainer = it.light.surfaceContainer.toArgb(),
                lightSurfaceContainerLowest = it.light.surfaceContainerLowest.toArgb(),
                lightSurfaceContainerLow = it.light.surfaceContainerLow.toArgb(),
                lightSurfaceContainerHigh = it.light.surfaceContainerHigh.toArgb(),
                lightSurfaceContainerHighest = it.light.surfaceContainerHighest.toArgb(),
                lightTitle = it.light.onSurface.toArgb(),
                lightArtist = it.light.onSurfaceVariant.toArgb(),
                lightPlayPauseBackground = it.light.primary.toArgb(),
                lightPlayPauseIcon = it.light.onPrimary.toArgb(),
                lightPrevNextBackground = it.light.onPrimary.toArgb(),
                lightPrevNextIcon = it.light.primary.toArgb(),
                
                darkSurfaceContainer = it.dark.surfaceContainer.toArgb(),
                darkSurfaceContainerLowest = it.dark.surfaceContainerLowest.toArgb(),
                darkSurfaceContainerLow = it.dark.surfaceContainerLow.toArgb(),
                darkSurfaceContainerHigh = it.dark.surfaceContainerHigh.toArgb(),
                darkSurfaceContainerHighest = it.dark.surfaceContainerHighest.toArgb(),
                darkTitle = it.dark.onSurface.toArgb(),
                darkArtist = it.dark.onSurfaceVariant.toArgb(),
                darkPlayPauseBackground = it.dark.primary.toArgb(),
                darkPlayPauseIcon = it.dark.onPrimary.toArgb(),
                darkPrevNextBackground = it.dark.onPrimary.toArgb(),
                darkPrevNextIcon = it.dark.primary.toArgb()
            )
        }
        val wearThemePalette = schemePair?.let { buildWearThemePalette(it.dark) }

        val isFavorite = isSongFavorite(mediaId)
        val lyrics = resolveWearLyrics(mediaId)
        val wearQueueRevision = buildWearQueueRevision(
            timeline = snapshotTimeline,
            currentIndex = snapshotWindowIndex,
            currentMediaId = mediaId,
        )

        val queueItems = mutableListOf<QueueItem>()
        if (!snapshotTimeline.isEmpty) {
            val window = Timeline.Window()

            val startIndex = if (snapshotWindowIndex + 1 < snapshotTimeline.windowCount) snapshotWindowIndex + 1 else 0

            val endIndex = (startIndex + 4).coerceAtMost(snapshotTimeline.windowCount)
            for (i in startIndex until endIndex) {
                snapshotTimeline.getWindow(i, window)
                val mediaItem = window.mediaItem
                val songId = mediaItem.mediaId.toLongOrNull()
                if (songId != null) {
                    val initialQueueArtworkUri = resolveWidgetArtworkUriCandidates(mediaItem.mediaMetadata)
                        .firstOrNull()
                    val queueArtworkUri = when {
                        initialQueueArtworkUri == null -> resolveRepositoryArtworkUri(mediaItem.mediaId)
                        initialQueueArtworkUri.scheme?.lowercase() == "content" &&
                            initialQueueArtworkUri.authority == "$packageName.provider" ->
                            resolveRepositoryArtworkUri(mediaItem.mediaId) ?: initialQueueArtworkUri
                        else -> initialQueueArtworkUri
                    }
                    queueItems.add(
                        QueueItem(
                            id = songId,
                            albumArtUri = queueArtworkUri?.toString()
                        )
                    )
                }
            }
        }

        return PlayerInfo(
            songTitle = title,
            artistName = artist,
            isPlaying = isPlaying,
            albumArtUri = artUriString,
            albumArtBitmapData = artBytes,
            currentPositionMs = currentPosition,
            totalDurationMs = totalDuration,
            isFavorite = isFavorite,
            lyrics = lyrics,
            queue = queueItems,
            themeColors = widgetColors,
            isShuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            wearThemePalette = wearThemePalette,
            wearQueueRevision = wearQueueRevision,
        )
    }

    private suspend fun resolveWearLyrics(mediaId: String?): com.theveloper.pixelplay.data.model.Lyrics? {
        val songId = mediaId?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            withContext(Dispatchers.IO) {
                val song = musicRepository.getSong(songId).first() ?: return@withContext null
                musicRepository.getStoredLyrics(song)?.first
            }
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Unable to resolve Wear lyrics for mediaId=%s", songId)
            null
        }
    }

    private var cachedSchemeArtUri: String? = null
    private var cachedSchemePaletteStyle: AlbumArtPaletteStyle? = null
    private var cachedSchemeColorAccuracy: Int = AlbumArtColorAccuracy.DEFAULT
    private var cachedColorSchemePair: ColorSchemePair? = null
    private var cachedWidgetArtSourceKey: String? = null
    private var cachedWidgetArtResolvedUri: String? = null
    private var cachedWidgetArtBytes: ByteArray? = null
    private var cachedWidgetArtLoadFailureKey: String? = null
    private var cachedWidgetArtLoadFailureAtMs: Long = 0L

    private fun invalidateCachedWidgetArtwork() {
        cachedWidgetArtSourceKey = null
        cachedWidgetArtResolvedUri = null
        cachedWidgetArtBytes = null
        cachedWidgetArtLoadFailureKey = null
        cachedWidgetArtLoadFailureAtMs = 0L
    }

    private fun isCurrentWidgetArtworkBackedByTelegram(): Boolean {
        val currentItem = engine.masterPlayer.currentMediaItem ?: return false
        val metadata = currentItem.mediaMetadata
        val contentUriString = currentItem.localConfiguration?.uri?.toString()
            ?: metadata.extras?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)
        val artworkUriString = resolveStoredArtworkUriString(metadata)
        return contentUriString?.startsWith("telegram://") == true ||
            artworkUriString?.startsWith("telegram_art://") == true
    }

    private suspend fun getAlbumArtForWidget(
        mediaId: String?,
        embeddedArt: ByteArray?,
        artUris: List<Uri>,
    ): Pair<ByteArray?, String?> = withContext(Dispatchers.IO) {
        val sanitizedFromEmbedded = embeddedArt?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching {
                ArtworkTransportSanitizer.sanitizeEncodedBytes(
                    data = bytes,
                    config = ArtworkTransportSanitizer.WIDGET_CONFIG,
                )
            }.getOrNull()
        }
        val candidateUriStrings = LinkedHashSet<String>().apply {
            artUris.forEach { candidate ->
                candidate.toString()
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }.toList()
        val preferredUriString = candidateUriStrings.firstOrNull()
        val sourceKey = buildWidgetArtworkSourceKey(
            mediaId = mediaId,
            candidateUriStrings = candidateUriStrings,
        )

        if (sanitizedFromEmbedded != null) {
            cachedWidgetArtSourceKey = sourceKey
            cachedWidgetArtResolvedUri = preferredUriString
            cachedWidgetArtBytes = sanitizedFromEmbedded
            cachedWidgetArtLoadFailureKey = null
            cachedWidgetArtLoadFailureAtMs = 0L
            return@withContext sanitizedFromEmbedded to preferredUriString
        }

        if (sourceKey != null && sourceKey == cachedWidgetArtSourceKey && cachedWidgetArtBytes != null) {
            return@withContext cachedWidgetArtBytes to (cachedWidgetArtResolvedUri ?: preferredUriString)
        }
        if (sourceKey != null && sourceKey == cachedWidgetArtLoadFailureKey) {
            val failureAgeMs = SystemClock.elapsedRealtime() - cachedWidgetArtLoadFailureAtMs
            if (failureAgeMs < WIDGET_ART_FAILURE_RETRY_MS) {
                return@withContext null to preferredUriString
            }
        }

        val repositoryArtUriString = if (mediaId.isNullOrBlank()) {
            null
        } else {
            resolveRepositoryArtworkUri(mediaId)?.toString()
        }
        val resolvedUriStrings = LinkedHashSet<String>().apply {
            addAll(candidateUriStrings)
            repositoryArtUriString
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }

        for (candidateUriString in resolvedUriStrings) {
            val candidateUri = parseArtworkUriString(candidateUriString) ?: continue
            val loadedBytes = loadArtworkBytesForWidget(candidateUri)
            if (loadedBytes != null) {
                cachedWidgetArtSourceKey = sourceKey
                cachedWidgetArtResolvedUri = candidateUriString
                cachedWidgetArtBytes = loadedBytes
                cachedWidgetArtLoadFailureKey = null
                cachedWidgetArtLoadFailureAtMs = 0L
                return@withContext loadedBytes to candidateUriString
            }
        }

        cachedWidgetArtLoadFailureKey = sourceKey
        cachedWidgetArtLoadFailureAtMs = SystemClock.elapsedRealtime()
        return@withContext null to (repositoryArtUriString ?: preferredUriString)
    }

    private fun resolveStoredArtworkUriString(metadata: MediaMetadata?): String? {
        metadata ?: return null
        return metadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
            ?.takeIf { it.isNotBlank() }
            ?: metadata.artworkUri
                ?.toString()
                ?.takeIf { it.isNotBlank() }
    }

    private fun resolveWidgetArtworkUriCandidates(
        metadata: MediaMetadata?,
        preferredArtworkUri: Uri? = null,
    ): List<Uri> {
        val candidates = LinkedHashSet<String>()
        preferredArtworkUri
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let(candidates::add)
        resolveStoredArtworkUriString(metadata)?.let(candidates::add)
        metadata?.artworkUri
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let(candidates::add)
        return candidates.mapNotNull(::parseArtworkUriString)
    }

    private fun parseArtworkUriString(rawArtworkUri: String?): Uri? {
        if (rawArtworkUri.isNullOrBlank()) {
            return null
        }

        return MediaItemBuilder.artworkUri(rawArtworkUri)
            ?: if (rawArtworkUri.startsWith("/")) {
                Uri.fromFile(File(rawArtworkUri))
            } else {
                runCatching { rawArtworkUri.toUri() }.getOrNull()
            }
    }

    private fun buildWidgetArtworkSourceKey(
        mediaId: String?,
        candidateUriStrings: List<String>,
    ): String? {
        val normalizedMediaId = mediaId?.takeIf { it.isNotBlank() }
        if (normalizedMediaId == null && candidateUriStrings.isEmpty()) {
            return null
        }
        return buildString {
            normalizedMediaId?.let {
                append("mediaId=")
                append(it)
            }
            if (candidateUriStrings.isNotEmpty()) {
                if (isNotEmpty()) append('|')
                append(candidateUriStrings.joinToString(separator = ","))
            }
        }
    }

    private fun resolveArtworkUri(metadata: MediaMetadata?): Uri? {
        metadata ?: return null
        metadata.artworkUri?.let { return it }
        val extrasUri = metadata.extras
            ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_ALBUM_ART)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return parseArtworkUriString(extrasUri)
    }

    private suspend fun resolveRepositoryArtworkUri(mediaId: String?): Uri? {
        val songId = mediaId?.takeIf { it.isNotBlank() } ?: return null
        val song = withContext(Dispatchers.IO) {
            musicRepository.getSong(songId).first()
        } ?: return null

        return MediaItemBuilder.artworkUri(song.albumArtUriString)
            ?: song.albumArtUriString
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    if (raw.startsWith("/")) Uri.fromFile(File(raw))
                    else runCatching { Uri.parse(raw) }.getOrNull()
                }
    }

    suspend fun loadArtworkBytesForWidget(uri: Uri): ByteArray? {
        val uriString = uri.toString()
        val scheme = uri.scheme?.lowercase()
        val isLocalArtworkUri = LocalArtworkUri.isLocalArtworkUri(uriString)
        return when {
            isLocalArtworkUri || scheme == "content" || scheme == "file" || scheme == "android.resource" -> {
                runCatching {
                    AlbumArtUtils.openArtworkInputStream(applicationContext, uri)?.use { input ->
                        readBytesCapped(input, ArtworkTransportSanitizer.WIDGET_CONFIG.sourceBytesLimit)
                            ?.let { bytes ->
                                ArtworkTransportSanitizer.sanitizeEncodedBytes(
                                    data = bytes,
                                    config = ArtworkTransportSanitizer.WIDGET_CONFIG,
                                )
                            }
                    }
                }.getOrElse { error ->
                    Timber.tag(TAG).w(error, "Widget artwork read failed for local uri=%s", uri)
                    null
                }
            }
            scheme == "http" || scheme == "https" -> {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(uriString).openConnection() as? HttpURLConnection)
                        ?: return null
                    connection.connectTimeout = 4_000
                    connection.readTimeout = 6_000
                    connection.instanceFollowRedirects = true
                    connection.doInput = true
                    connection.inputStream.use { input ->
                        readBytesCapped(input, ArtworkTransportSanitizer.WIDGET_CONFIG.sourceBytesLimit)
                            ?.let { bytes ->
                                ArtworkTransportSanitizer.sanitizeEncodedBytes(
                                    data = bytes,
                                    config = ArtworkTransportSanitizer.WIDGET_CONFIG,
                                )
                            }
                    }
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Widget artwork read failed for remote uri=%s", uri)
                    null
                } finally {
                    connection?.disconnect()
                }
            }
            else -> loadArtworkBytesViaCoil(applicationContext, uri)
        }
    }

    private fun readBytesCapped(input: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(DEFAULT_STREAM_BUFFER_SIZE * 4)
        val buffer = ByteArray(DEFAULT_STREAM_BUFFER_SIZE)
        var totalRead = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            totalRead += read
            if (totalRead > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    fun isSongFavorite(songId: String?): Boolean {
        return songId != null && favoriteSongIds.contains(songId)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val hasPlaybackIntent = session.player.hasForegroundPlaybackIntent()

        val shouldStartInForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startInForegroundRequired || hasPlaybackIntent
        } else {
            startInForegroundRequired
        }

        try {
            super.onUpdateNotification(session, shouldStartInForeground)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "onUpdateNotification suppressed: ${e.message}")
        }
    }

    override fun startForegroundService(serviceIntent: Intent?): ComponentName? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                super.startForegroundService(serviceIntent)
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Timber.tag(TAG).w(
                    e,
                    "startForegroundService not allowed; ignoring redundant self-start request"
                )
                serviceIntent?.component ?: ComponentName(this, javaClass)
            } catch (e: BackgroundServiceStartNotAllowedException) {
                Timber.tag(TAG).w(
                    e,
                    "startForegroundService blocked (app in background); ignoring self-start request"
                )
                serviceIntent?.component ?: ComponentName(this, javaClass)
            }
        }
        return super.startForegroundService(serviceIntent)
    }

    private fun refreshMediaSessionUi(session: MediaSession, force: Boolean = false) {
        val pendingSignature = buildMediaButtonPreferencesSignature(session)
        if (!force && pendingSignature == lastAppliedMediaButtonSignature) {
            return
        }

        mediaSessionButtonRefreshJob?.cancel()
        mediaSessionButtonRefreshJob = serviceScope.launch {
            if (!force) {
                delay(MEDIA_SESSION_BUTTON_DEBOUNCE_MS)
            }
            if (mediaSession !== session) {
                return@launch
            }

            val latestSignature = buildMediaButtonPreferencesSignature(session)
            if (latestSignature == lastAppliedMediaButtonSignature) {
                return@launch
            }

            val buttons = buildMediaButtonPreferences(session)
            session.setMediaButtonPreferences(buttons)
            lastAppliedMediaButtonSignature = latestSignature
        }
    }

    private fun closeNotificationPlayer() {
        stopPlaybackAndUnload(
            reason = "notification_close_button",
            preservePlaybackSnapshot = false
        )
    }

    private fun stopPlaybackAndUnload(
        reason: String,
        preservePlaybackSnapshot: Boolean = true,
    ) {
        Timber.tag(TAG).d(
            "Stopping playback and unloading service. reason=%s",
            reason
        )
        isPlaybackUnloadInProgress = true
        followUpMediaSessionUiRefreshJob?.cancel()
        mediaSessionButtonRefreshJob?.cancel()
        widgetUpdateManager.cancel()
        playbackSnapshotPersistJob?.cancel()

        val sessionToRelease = mediaSession
        val player = sessionToRelease?.player ?: engine.masterPlayer

        clearHeadsetReconnectResume()
        cancelDurationSleepTimerInternal()
        endOfTrackTimerSongId = null

        if (preservePlaybackSnapshot) {
            persistPlaybackSnapshotOnUnload()
        } else {
            clearPlaybackSnapshotOnUnload()
        }

        listeningStatsTracker.finalizeCurrentSession(forceSynchronousPersistence = true)

        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()

        widgetUpdateManager.requestFullUpdate(true)
        stopForeground(STOP_FOREGROUND_REMOVE)

        stopSelf()
    }

    private fun persistPlaybackSnapshotOnUnload() {
        val snapshot = capturePlaybackSnapshotFromPlayer(playWhenReadyOverride = false)
        writePlaybackSnapshotOnUnload(snapshot)
    }

    private fun clearPlaybackSnapshotOnUnload() {
        writePlaybackSnapshotOnUnload(null)
    }

    private fun writePlaybackSnapshotOnUnload(snapshot: PlaybackQueueSnapshot?) {
        playbackSnapshotUnloadWriteJob?.cancel()
        playbackSnapshotUnloadWriteJob = appScope.launch {
            runCatching {
                userPreferencesRepository.setPlaybackQueueSnapshot(snapshot)
            }.onFailure { e ->
                Timber.tag(TAG).w(e, "Failed to persist playback snapshot during unload")
            }
        }
    }

    private fun refreshMediaSessionUiWithFollowUp(
        session: MediaSession,
        delayMs: Long = 250L
    ) {
        refreshMediaSessionUi(session, force = true)
        followUpMediaSessionUiRefreshJob?.cancel()
        followUpMediaSessionUiRefreshJob = serviceScope.launch {
            delay(delayMs)
            if (mediaSession === session) {
                refreshMediaSessionUi(session)
            }
        }
    }

    private fun updateManualShuffleState(
        session: MediaSession,
        enabled: Boolean,
        broadcast: Boolean
    ) {
        val changed = isManualShuffleEnabled != enabled
        isManualShuffleEnabled = enabled
        session.player.shuffleModeEnabled = enabled
        
        if (persistentShuffleEnabled) {
            serviceScope.launch {
                userPreferencesRepository.setShuffleOn(enabled)
            }
        }

        if (broadcast && changed) {
            val args = Bundle().apply {
                putBoolean(MusicNotificationProvider.EXTRA_SHUFFLE_ENABLED, enabled)
            }
            session.broadcastCustomCommand(
                SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_SET_SHUFFLE_STATE, Bundle.EMPTY),
                args
            )
        }
        refreshMediaSessionUi(session)
        widgetUpdateManager.requestFullUpdate(true)
        replayGainProcessor.apply(session.player.currentMediaItem)
    }

    private fun setCurrentSongFavoriteState(
        session: MediaSession,
        targetFavoriteState: Boolean
    ): ListenableFuture<SessionResult> {
        val songId = session.player.currentMediaItem?.mediaId
            ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))

        val isCurrentlyFavorite = favoriteSongIds.contains(songId)
        if (isCurrentlyFavorite == targetFavoriteState) {
            refreshMediaSessionUi(session)
            widgetUpdateManager.requestFullUpdate(true)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        favoriteSongIds = if (targetFavoriteState) {
            favoriteSongIds + songId
        } else {
            favoriteSongIds - songId
        }

        refreshMediaSessionUi(session)
        widgetUpdateManager.requestFullUpdate(true)

        serviceScope.launch {
            Timber.tag("MusicService")
                .d("Applying favorite=$targetFavoriteState for songId: $songId")
            musicRepository.setFavoriteStatus(songId, targetFavoriteState)
            refreshMediaSessionUi(session)
            widgetUpdateManager.requestFullUpdate(true)
        }

        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private data class ContextQueueResolution(
        val mediaItems: MutableList<MediaItem>,
        val startIndex: Int
    )

    private fun controllerKey(controller: MediaSession.ControllerInfo): String {
        return "${controller.packageName}:${controller.uid}"
    }

    private fun rememberLastBrowsedParent(controller: MediaSession.ControllerInfo, parentId: String) {
        synchronized(controllerLastBrowsedParent) {
            controllerLastBrowsedParent[controllerKey(controller)] = parentId
        }
    }

    private fun getLastBrowsedParent(controller: MediaSession.ControllerInfo): String? {
        return synchronized(controllerLastBrowsedParent) {
            controllerLastBrowsedParent[controllerKey(controller)]
        }
    }

    private fun clearLastBrowsedParent(controller: MediaSession.ControllerInfo) {
        synchronized(controllerLastBrowsedParent) {
            controllerLastBrowsedParent.remove(controllerKey(controller))
        }
    }

    private suspend fun resolveContextQueueForRequestedItem(
        requestedItem: MediaItem,
        controller: MediaSession.ControllerInfo
    ): ContextQueueResolution? {
        var contextType = requestedItem.mediaMetadata.extras
            ?.getString(AutoMediaBrowseTree.CONTEXT_TYPE_EXTRA)
        var contextId = requestedItem.mediaMetadata.extras
            ?.getString(AutoMediaBrowseTree.CONTEXT_ID_EXTRA)

        if (contextType.isNullOrBlank()) {
            val parentId = requestedItem.mediaMetadata.extras
                ?.getString(AutoMediaBrowseTree.CONTEXT_PARENT_ID_EXTRA)
                ?: getLastBrowsedParent(controller)
            val parentContext = parentId?.let { resolveAutoContextFromParentId(it) }
            contextType = parentContext?.first
            contextId = parentContext?.second
        }

        if (contextType.isNullOrBlank()) {
            return null
        }

        val queueSongs = autoMediaBrowseTree.getSongsForContext(contextType, contextId)
        if (queueSongs.isEmpty()) {
            return null
        }

        val startIndex = queueSongs.indexOfFirst { it.id == requestedItem.mediaId }
        if (startIndex < 0) {
            return null
        }

        val queueMediaItems = queueSongs.map { song ->
            MediaItemBuilder.buildForExternalController(this, song)
        }.toMutableList()

        return ContextQueueResolution(
            mediaItems = queueMediaItems,
            startIndex = startIndex
        )
    }

    private suspend fun resolveMediaItemsByIds(
        requestedItems: List<MediaItem>
    ): TrustedMediaItemsResolution {
        val songIds = requestedItems.map { it.mediaId }
        val songs = musicRepository.getSongsByIds(songIds).first()
        val songMap = songs.associateBy { it.id }

        return resolveMediaItemsWithTrustedArtworkGrants(requestedItems) { mediaId ->
            songMap[mediaId]?.let { song ->
                MediaItemBuilder.buildForExternalController(this, song)
            }
        }
    }

    private fun grantArtworkUriPermissions(
        targetPackage: String,
        mediaItems: List<MediaItem>
    ) {
        if (targetPackage.isBlank()) return

        val providerAuthority = "$packageName.provider"
        val artworkAuthority = "$packageName.artwork"
        mediaItems.forEach { mediaItem ->
            val artworkUri = resolveArtworkUri(mediaItem.mediaMetadata) ?: return@forEach
            val authority = artworkUri.authority
            if (artworkUri.scheme?.lowercase() != "content" ||
                (authority != providerAuthority && authority != artworkAuthority)
            ) {
                return@forEach
            }

            runCatching {
                grantUriPermission(targetPackage, artworkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { error ->
                Timber.tag(TAG).w(
                    error,
                    "Failed to grant artwork URI permission to package=%s uri=%s",
                    targetPackage,
                    artworkUri
                )
            }
        }
    }

    private fun resolveAutoContextFromParentId(parentId: String): Pair<String, String?>? {
        return when {
            parentId == AutoMediaBrowseTree.RECENT_ID -> AUTO_CONTEXT_RECENT to null
            parentId == AutoMediaBrowseTree.FAVORITES_ID -> AUTO_CONTEXT_FAVORITES to null
            parentId == AutoMediaBrowseTree.SONGS_ID -> AUTO_CONTEXT_ALL_SONGS to null
            parentId.startsWith(AutoMediaBrowseTree.ALBUM_PREFIX) -> {
                AUTO_CONTEXT_ALBUM to parentId.removePrefix(AutoMediaBrowseTree.ALBUM_PREFIX)
            }
            parentId.startsWith(AutoMediaBrowseTree.ARTIST_PREFIX) -> {
                AUTO_CONTEXT_ARTIST to parentId.removePrefix(AutoMediaBrowseTree.ARTIST_PREFIX)
            }
            parentId.startsWith(AutoMediaBrowseTree.PLAYLIST_PREFIX) -> {
                AUTO_CONTEXT_PLAYLIST to parentId.removePrefix(AutoMediaBrowseTree.PLAYLIST_PREFIX)
            }
            else -> null
        }
    }

    private fun buildMediaButtonPreferencesSignature(session: MediaSession): String {
        val player = session.player
        return buildString {
            append(player.currentMediaItem?.mediaId.orEmpty())
            append('|')
            append(isSongFavorite(player.currentMediaItem?.mediaId))
            append('|')
            append(isManualShuffleEnabled)
            append('|')
            append(player.repeatMode)
        }
    }

    private fun buildMediaButtonPreferences(session: MediaSession): List<CommandButton> {
        val player = session.player
        val songId = player.currentMediaItem?.mediaId
        val isFavorite = isSongFavorite(songId)
        val likeButton = CommandButton.Builder(
            if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName("Like")
            .setSessionCommand(SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_LIKE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val shuffleOn = isManualShuffleEnabled
        val shuffleCommandAction = if (shuffleOn) {
            MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF
        } else {
            MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON
        }
        val shuffleButton = CommandButton.Builder(
            if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName("Shuffle")
            .setSessionCommand(SessionCommand(shuffleCommandAction, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val repeatButton = CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            }
        )
            .setDisplayName("Repeat")
            .setSessionCommand(SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val closeButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.rounded_close_24)
            .setDisplayName(getString(R.string.close_notification_player))
            .setSessionCommand(SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_CLOSE_PLAYER, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        return listOf(likeButton, closeButton, shuffleButton, repeatButton)
    }

    fun startCountedPlay(count: Int) {
        val player = engine.masterPlayer
        val currentItem = player.currentMediaItem ?: return

        stopCountedPlay()

        countedPlayTarget = count
        countedPlayCount = 1
        countedOriginalId = currentItem.mediaId
        countedPlayActive = true

        player.repeatMode = Player.REPEAT_MODE_ONE

        val listener = object : Player.Listener {

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (!countedPlayActive) return

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    countedPlayCount++

                    if (countedPlayCount > countedPlayTarget) {
                        player.pause()
                        stopCountedPlay()
                        return
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!countedPlayActive) return

                if (mediaItem?.mediaId != countedOriginalId) {
                    stopCountedPlay()
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (countedPlayActive && repeatMode != Player.REPEAT_MODE_ONE) {
                    stopCountedPlay(restoreRepeatMode = false)
                }
            }
        }

        countedPlayListener = listener
        player.addListener(listener)
    }

    fun stopCountedPlay(restoreRepeatMode: Boolean = true) {
        if (!countedPlayActive) return

        countedPlayActive = false
        countedPlayTarget = 0
        countedPlayCount = 0
        countedOriginalId = null

        countedPlayListener?.let {
            engine.masterPlayer.removeListener(it)
        }
        countedPlayListener = null

        if (restoreRepeatMode) {
            engine.masterPlayer.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    private fun <T> CoroutineScope.future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        launch(Dispatchers.IO) {
            try {
                future.set(block())
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }
}
