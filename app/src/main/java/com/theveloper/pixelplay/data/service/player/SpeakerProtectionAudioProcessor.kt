@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Always-on safety stage at the very end of the chain, after all built-in
 * effects and user plugins. Not user-configurable — hardware safety, not a
 * creative effect. Two jobs: a steep ~90Hz highpass to protect small phone
 * speakers from sub-bass excursion, and a fast peak limiter clamping to
 * 0dBFS so cumulative gain from stacked plugins can never clip.
 */
@UnstableApi
class SpeakerProtectionAudioProcessor : AudioProcessor {

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var channelCount = 0
    private var sampleRate = 44100

    private var hp1In = FloatArray(0); private var hp1Out = FloatArray(0)
    private var hp2In = FloatArray(0); private var hp2Out = FloatArray(0)
    private var limiterGain = FloatArray(0)

    private val hpfCutoffHz = 90f
    private val limiterCeiling = 0.98f
    private val limiterAttack = 0.9f
    private val limiterRelease = 0.9995f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (isSupported) {
            inputFormat = inputAudioFormat
            channelCount = inputAudioFormat.channelCount
            sampleRate = inputAudioFormat.sampleRate
            hp1In = FloatArray(channelCount); hp1Out = FloatArray(channelCount)
            hp2In = FloatArray(channelCount); hp2Out = FloatArray(channelCount)
            limiterGain = FloatArray(channelCount) { 1f }
            inputAudioFormat
        } else {
            inputFormat = AudioFormat.NOT_SET
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive()) return
        try {
            if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
                val frameCount = inputBuffer.remaining() / Float.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Float.SIZE_BYTES)
                val floatIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                var ch = 0
                repeat(frameCount) {
                    outputBuffer.putFloat(process(floatIn.get(), ch))
                    ch = (ch + 1) % channelCount
                }
                inputBuffer.position(inputBuffer.limit())
            } else {
                val frameCount = inputBuffer.remaining() / Short.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Short.SIZE_BYTES)
                val shortIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                var ch = 0
                repeat(frameCount) {
                    val y = process(shortIn.get() / 32768f, ch)
                    val out = (y * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outputBuffer.putShort(out.toShort())
                    ch = (ch + 1) % channelCount
                }
                inputBuffer.position(inputBuffer.limit())
            }
            outputBuffer.flip()
        } catch (t: Throwable) {
            Timber.tag("AudioFxProcessor").e(t, "SpeakerProtection failed, falling back to pass-through")
            if (inputBuffer.hasRemaining()) {
                outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
            }
        }
    }

    private fun process(sampleIn: Float, ch: Int): Float {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * hpfCutoffHz)
        val a = rc / (rc + dt)

        val hp1 = a * (hp1Out[ch] + sampleIn - hp1In[ch])
        hp1In[ch] = sampleIn; hp1Out[ch] = hp1
        val hp2 = a * (hp2Out[ch] + hp1 - hp2In[ch])
        hp2In[ch] = hp1; hp2Out[ch] = hp2

        val absVal = abs(hp2)
        val targetGain = if (absVal > limiterCeiling) limiterCeiling / absVal else 1f
        limiterGain[ch] = if (targetGain < limiterGain[ch]) {
            limiterGain[ch] * (1f - limiterAttack) + targetGain * limiterAttack
        } else {
            limiterGain[ch] * limiterRelease + targetGain * (1f - limiterRelease)
        }
        return (hp2 * limiterGain[ch]).coerceIn(-1f, 1f)
    }

    private fun ensureOutputBuffer(requiredCapacity: Int): ByteBuffer {
        return if (outputBuffer.capacity() < requiredCapacity) {
            ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder()).also { outputBuffer = it }
        } else {
            outputBuffer.clear()
            outputBuffer
        }
    }

    override fun getOutput(): ByteBuffer {
        val pending = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return pending
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER
    override fun queueEndOfStream() { inputEnded = true }

    @Deprecated("Media3 AudioProcessor now prefers flush(StreamMetadata); kept for interface compatibility")
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
    }
}
