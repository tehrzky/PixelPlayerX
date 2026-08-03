package com.theveloper.pixelplay.data.service.player

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live, audio-thread-readable state for Audio FX processors.
 *
 * This is intentionally separate from AudioFxPreferencesRepository (DataStore):
 * DataStore reads are suspend/async and too slow to read per-audio-buffer.
 * The ViewModel writes here immediately on every toggle/slider change so the
 * DSP chain reacts instantly, while also persisting to DataStore for next launch.
 *
 * @Volatile ensures the audio thread always sees the latest value written by
 * the main thread, without needing a Mutex (single writer, simple reads, no
 * check-then-act — plain visibility is enough here).
 */
@Singleton
class AudioFxStateHolder @Inject constructor() {
    @Volatile var lofiEnabled: Boolean = false
    @Volatile var lofiIntensity: Int = 40
}
