package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

@Singleton
class RadioEffectAudioProcessor @Inject constructor() : AudioProcessor {

    // --- Parameters (UI thread writes, audio thread reads at buffer boundary) ---
    @Volatile var enabled = false
    @Volatile var noiseLevel = 0.15f
    @Volatile var distortionAmount = 0.25f
    @Volatile var radioBand = true
    @Volatile var crackleEnabled = true
    @Volatile var tapeWowEnabled = false
    @Volatile var tapeWowDepth = 0.3f
    @Volatile var phaserEnabled = false
    @Volatile var phaserDepth = 0.5f
    @Volatile var phaserRate = 0.3f
    @Volatile var bathroomReverbEnabled = false
    @Volatile var bathroomReverbAmount = 0.3f

    @Volatile private var pendingEnabled = false
    @Volatile private var pendingNoiseLevel = 0.15f
    @Volatile private var pendingDistortionAmount = 0.25f
    @Volatile private var pendingRadioBand = true
    @Volatile private var pendingCrackleEnabled = true
    @Volatile private var pendingTapeWowEnabled = false
    @Volatile private var pendingTapeWowDepth = 0.3f
    @Volatile private var pendingPhaserEnabled = false
    @Volatile private var pendingPhaserDepth = 0.5f
    @Volatile private var pendingPhaserRate = 0.3f
    @Volatile private var pendingBathroomReverbEnabled = false
    @Volatile private var pendingBathroomReverbAmount = 0.3f

    // --- AudioProcessor state ---
    private var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var inputBuffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var scratchBuffer: ByteBuffer = EMPTY_BUFFER
    private var isFloatMode = false
    private var sampleRateF = 44100f

    // --- Radio DSP state ---
    private var hpState = FloatArray(2) { 0f }
    private var lpState = FloatArray(2) { 0f }
    private var noiseSeed = 123456789L
    private var brownNoiseAcc = 0f

    // --- Tape wow / flutter ---
    private var tapeDelayLineL = FloatArray(0)
    private var tapeDelayLineR = FloatArray(0)
    private var tapeWriteIndexL = 0
    private var tapeWriteIndexR = 0
    private var tapeLfoPhase = 0f

    // --- Phaser ---
    private var phaserAllPassState: Array<FloatArray> = Array(4) { FloatArray(2) { 0f } }
    private var phaserLfoPhase = 0f

    // --- Chorus ---
    private var chorusDelayLineL = FloatArray(0)
    private var chorusDelayLineR = FloatArray(0)
    private var chorusWriteIndexL = 0
    private var chorusWriteIndexR = 0
    private var chorusLfoPhase = 0f

    // --- Schroeder reverb ---
    private var combBuffers: Array<FloatArray> = Array(4) { FloatArray(0) }
    private var combIndices = IntArray(4) { 0 }
    private var combDelayLengths = IntArray(4) { 0 }
    private var allPassBuffers: Array<FloatArray> = Array(2) { FloatArray(0) }
    private var allPassIndices = IntArray(2) { 0 }
    private var allPassDelayLengths = IntArray(2) { 0 }
    private var inputEnded = false
    fun setParameters(
        enabled: Boolean,
        noiseLevel: Float,
        distortionAmount: Float,
        radioBand: Boolean = true,
        crackleEnabled: Boolean = true,
        tapeWowEnabled: Boolean = false,
        tapeWowDepth: Float = 0.3f,
        phaserEnabled: Boolean = false,
        phaserDepth: Float = 0.5f,
        phaserRate: Float = 0.3f,
        bathroomReverbEnabled: Boolean = false,
        bathroomReverbAmount: Float = 0.3f
    ) {
        pendingEnabled = enabled
        pendingNoiseLevel = noiseLevel.coerceIn(0f, 1f)
        pendingDistortionAmount = distortionAmount.coerceIn(0f, 1f)
        pendingRadioBand = radioBand
        pendingCrackleEnabled = crackleEnabled
        pendingTapeWowEnabled = tapeWowEnabled
        pendingTapeWowDepth = tapeWowDepth.coerceIn(0f, 1f)
        pendingPhaserEnabled = phaserEnabled
        pendingPhaserDepth = phaserDepth.coerceIn(0f, 1f)
        pendingPhaserRate = phaserRate.coerceIn(0f, 1f)
        pendingBathroomReverbEnabled = bathroomReverbEnabled
        pendingBathroomReverbAmount = bathroomReverbAmount.coerceIn(0f, 1f)
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        this.isFloatMode = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        this.sampleRateF = inputAudioFormat.sampleRate.toFloat()
        if (sampleRateF > 0) initDelayLines(sampleRateF.toInt())
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        return inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            this.inputBuffer = inputBuffer
            outputBuffer = EMPTY_BUFFER
            return
        }

        enabled = pendingEnabled
        noiseLevel = pendingNoiseLevel
        distortionAmount = pendingDistortionAmount
        radioBand = pendingRadioBand
        crackleEnabled = pendingCrackleEnabled
        tapeWowEnabled = pendingTapeWowEnabled
        tapeWowDepth = pendingTapeWowDepth
        phaserEnabled = pendingPhaserEnabled
        phaserDepth = pendingPhaserDepth
        phaserRate = pendingPhaserRate
        bathroomReverbEnabled = pendingBathroomReverbEnabled
        bathroomReverbAmount = pendingBathroomReverbAmount

        if (!enabled) {
            // Pass-through: wrap the input buffer directly as output.
            this.inputBuffer = EMPTY_BUFFER
            outputBuffer = inputBuffer
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val bytesPerFrame = if (isFloatMode) 4 * channelCount else 2 * channelCount
        val frameCount = inputBuffer.remaining() / bytesPerFrame

        if (scratchBuffer.capacity() < inputBuffer.remaining()) {
            scratchBuffer = ByteBuffer.allocateDirect(inputBuffer.remaining())
                .order(ByteOrder.nativeOrder())
        } else {
            scratchBuffer.clear()
        }

        val samples = FloatArray(channelCount)

        for (frame in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                samples[ch] = if (isFloatMode) inputBuffer.float else inputBuffer.short / 32768f
            }

            for (ch in 0 until channelCount) {
                var s = samples[ch]
                if (radioBand) s = applyBandpass(s, ch)
                s = applyDistortion(s)
                s = applyNoise(s)
                if (crackleEnabled) s = applyCrackle(s)
                if (tapeWowEnabled) s = applyTapeWow(s, ch)
                if (phaserEnabled) s = applyPhaserChorus(s, ch)
                if (bathroomReverbEnabled) s = applyBathroomReverb(s, ch)
                samples[ch] = s
            }

            advanceLfos()

            for (ch in 0 until channelCount) {
                val clamped = samples[ch].coerceIn(-1f, 1f)
                if (isFloatMode) {
                    scratchBuffer.putFloat(clamped)
                } else {
                    scratchBuffer.putShort((clamped * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        this.inputBuffer = EMPTY_BUFFER
        scratchBuffer.flip()
        outputBuffer = scratchBuffer
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return buffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    override fun flush() {
        inputBuffer = EMPTY_BUFFER
        outputBuffer = EMPTY_BUFFER
        hpState.fill(0f)
        lpState.fill(0f)
        brownNoiseAcc = 0f
        noiseSeed = 123456789L

        tapeDelayLineL.fill(0f)
        tapeDelayLineR.fill(0f)
        tapeWriteIndexL = 0
        tapeWriteIndexR = 0
        tapeLfoPhase = 0f

        phaserAllPassState.forEach { it.fill(0f) }
        phaserLfoPhase = 0f

        chorusDelayLineL.fill(0f)
        chorusDelayLineR.fill(0f)
        chorusWriteIndexL = 0
        chorusWriteIndexR = 0
        chorusLfoPhase = 0f

        combBuffers.forEach { it.fill(0f) }
        combIndices.fill(0)
        allPassBuffers.forEach { it.fill(0f) }
        allPassIndices.fill(0)
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    private fun initDelayLines(sampleRate: Int) {
        val maxTapeDelayMs = 50f
        val maxTapeSamples = (sampleRate * maxTapeDelayMs / 1000f).toInt() + 100
        if (tapeDelayLineL.size < maxTapeSamples) {
            tapeDelayLineL = FloatArray(maxTapeSamples)
            tapeDelayLineR = FloatArray(maxTapeSamples)
        }
        if (chorusDelayLineL.size < maxTapeSamples) {
            chorusDelayLineL = FloatArray(maxTapeSamples)
            chorusDelayLineR = FloatArray(maxTapeSamples)
        }

        combDelayLengths[0] = (sampleRate * 0.0297f).toInt()
        combDelayLengths[1] = (sampleRate * 0.0371f).toInt()
        combDelayLengths[2] = (sampleRate * 0.0411f).toInt()
        combDelayLengths[3] = (sampleRate * 0.0437f).toInt()
        for (i in 0 until 4) {
            val len = combDelayLengths[i]
            if (combBuffers[i].size < len) combBuffers[i] = FloatArray(len)
        }

        allPassDelayLengths[0] = (sampleRate * 0.005f).toInt()
        allPassDelayLengths[1] = (sampleRate * 0.0017f).toInt()
        for (i in 0 until 2) {
            val len = allPassDelayLengths[i]
            if (allPassBuffers[i].size < len) allPassBuffers[i] = FloatArray(len)
        }
    }

    private fun advanceLfos() {
        val twopi = 2f * PI.toFloat()
        tapeLfoPhase += twopi * (0.5f + tapeWowDepth * 4.5f) / sampleRateF
        if (tapeLfoPhase > twopi) tapeLfoPhase -= twopi

        phaserLfoPhase += twopi * (0.1f + phaserRate * 1.9f) / sampleRateF
        if (phaserLfoPhase > twopi) phaserLfoPhase -= twopi

        chorusLfoPhase += twopi * (0.2f + phaserRate * 1.0f) / sampleRateF
        if (chorusLfoPhase > twopi) chorusLfoPhase -= twopi
    }

    private fun applyBandpass(sample: Float, channel: Int): Float {
        val hpOut = sample - hpState[channel]
        hpState[channel] = sample * 0.04f + hpState[channel] * 0.96f
        val lpOut = hpOut * 0.35f + lpState[channel] * 0.65f
        lpState[channel] = lpOut
        return lpOut
    }

    private fun applyDistortion(sample: Float): Float {
        if (distortionAmount < 0.001f) return sample
        val drive = 1f + distortionAmount * 12f
        val x = sample * drive
        val wet = x / (1f + abs(x))
        return sample * (1f - distortionAmount) + wet * distortionAmount
    }

    private fun applyNoise(sample: Float): Float {
        if (noiseLevel < 0.001f) return sample
        noiseSeed = (noiseSeed * 1103515245L + 12345L) and 0x7fffffffL
        val white = (noiseSeed.toFloat() / 0x3fffffff) - 1f
        brownNoiseAcc = (brownNoiseAcc + white * 0.02f) * 0.98f
        val mixed = brownNoiseAcc * 0.6f + white * 0.4f
        return sample + mixed * noiseLevel * 0.08f
    }

    private fun applyCrackle(sample: Float): Float {
        if (noiseLevel < 0.05f) return sample
        noiseSeed = (noiseSeed * 1103515245L + 12345L) and 0x7fffffffL
        if ((noiseSeed % 8000) < (noiseLevel * 30).toLong()) {
            val pop = ((noiseSeed % 1000) / 500f - 1f) * noiseLevel * 0.25f
            return sample + pop
        }
        return sample
    }

    private fun applyTapeWow(sample: Float, channel: Int): Float {
        val delayLine = if (channel == 0) tapeDelayLineL else tapeDelayLineR
        val writeIndex = if (channel == 0) tapeWriteIndexL else tapeWriteIndexR
        val maxDelay = delayLine.size - 1

        delayLine[writeIndex] = sample

        val wowDepthSamples = (tapeWowDepth * 10f * sampleRateF / 1000f).coerceIn(1f, maxDelay.toFloat())
        val wowMod = sin(tapeLfoPhase) * wowDepthSamples
        val flutterDepthSamples = (tapeWowDepth * 2f * sampleRateF / 1000f).coerceIn(0.5f, maxDelay.toFloat() * 0.3f)
        val flutterMod = sin(tapeLfoPhase * 3.7f + 1.2f) * flutterDepthSamples

        val totalDelay = wowMod + flutterMod + 5f
        val readIndexF = writeIndex - totalDelay
        val readIndex = readIndexF.toInt()
        val frac = readIndexF - readIndex

        val i0 = ((readIndex % delayLine.size) + delayLine.size) % delayLine.size
        val i1 = (i0 + 1) % delayLine.size
        val output = delayLine[i0] * (1f - frac) + delayLine[i1] * frac

        if (channel == 0) tapeWriteIndexL = (writeIndex + 1) % delayLine.size
        else tapeWriteIndexR = (writeIndex + 1) % delayLine.size

        return output
    }

    private fun applyPhaserChorus(sample: Float, channel: Int): Float {
        var phaserOut = sample
        val minFreq = 200f
        val maxFreq = 4000f
        val lfo = (sin(phaserLfoPhase) + 1f) * 0.5f
        val freq = minFreq + lfo * (maxFreq - minFreq)
        val tanw = tan(PI.toFloat() * freq / sampleRateF)
        val a1 = (tanw - 1f) / (tanw + 1f)

        for (stage in 0 until 4) {
            val state = phaserAllPassState[stage]
            val allPassOut = a1 * phaserOut + state[0] - a1 * state[1]
            state[0] = phaserOut
            state[1] = allPassOut
            phaserOut = allPassOut
        }

        val delayLine = if (channel == 0) chorusDelayLineL else chorusDelayLineR
        val writeIndex = if (channel == 0) chorusWriteIndexL else chorusWriteIndexR
        delayLine[writeIndex] = sample
        val chorusDelayMs = 15f + sin(chorusLfoPhase) * 5f
        val chorusDelaySamples = chorusDelayMs * sampleRateF / 1000f
        val readIndexF = writeIndex - chorusDelaySamples
        val readIndex = readIndexF.toInt()
        val frac = readIndexF - readIndex
        val c0 = ((readIndex % delayLine.size) + delayLine.size) % delayLine.size
        val c1 = (c0 + 1) % delayLine.size
        val chorusOut = delayLine[c0] * (1f - frac) + delayLine[c1] * frac

        if (channel == 0) chorusWriteIndexL = (writeIndex + 1) % delayLine.size
        else chorusWriteIndexR = (writeIndex + 1) % delayLine.size

        val mix = phaserDepth * 0.5f
        return sample * (1f - mix) + phaserOut * mix * 0.6f + chorusOut * mix * 0.4f
    }

    private fun applyBathroomReverb(sample: Float, channel: Int): Float {
        var combOut = 0f
        val feedback = 0.3f + bathroomReverbAmount * 0.5f

        for (i in 0 until 4) {
            val buf = combBuffers[i]
            val idx = combIndices[i]
            val len = combDelayLengths[i]
            if (len <= 0) continue
            val delayed = buf[idx]
            val newVal = sample + delayed * feedback
            buf[idx] = newVal
            combIndices[i] = (idx + 1) % len
            combOut += delayed
        }
        combOut *= 0.25f

        var apOut = combOut
        for (i in 0 until 2) {
            val buf = allPassBuffers[i]
            val idx = allPassIndices[i]
            val len = allPassDelayLengths[i]
            if (len <= 0) continue
            val delayed = buf[idx]
            val newVal = apOut + delayed * 0.7f
            buf[idx] = newVal
            allPassIndices[i] = (idx + 1) % len
            apOut = delayed - newVal * 0.7f
        }

        val mix = bathroomReverbAmount * 0.4f
        return sample * (1f - mix) + apOut * mix
    }
}
