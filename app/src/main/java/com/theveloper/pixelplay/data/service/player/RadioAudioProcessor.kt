@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * AM-radio style bandpass filter (one-pole lowpass + one-pole highpass) plus
 * static noise. Same always-active/internal-bypass pattern as LofiAudioProcessor
 * so toggling never touches the sink's flush/reconfigure cycle.
 */
@UnstableApi
class RadioAudioProcessor(
    private val state: AudioFxStateHolder
) : AudioProcessor {

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var channelCount = 0

    private var lpState = FloatArray(0)
    private var hpStateIn = FloatArray(0)
    private var hpStateOut = FloatArray(0)
    private val random = Random(0)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (isSupported) {
            inputFormat = inputAudioFormat
            channelCount = inputAudioFormat.channelCount
            lpState = FloatArray(channelCount)
            hpStateIn = FloatArray(channelCount)
            hpStateOut = FloatArray(channelCount)
            inputAudioFormat
        } else {
            inputFormat = AudioFormat.NOT_SET
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive()) return
        val enabled = state.radioEnabled
        val intensity = state.radioIntensity.coerceIn(0, 100)

        if (!enabled || intensity == 0) {
            outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val t = intensity / 100f
        val sampleRate = inputFormat.sampleRate.toFloat()
        val lpCutoff = 12000f - t * 9800f
        val hpCutoff = 20f + t * 380f
        val lpAlpha = lpAlpha(lpCutoff, sampleRate)
        val hpAlpha = hpAlpha(hpCutoff, sampleRate)
        val noiseAmp = t * 0.035f

        if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val frameCount = inputBuffer.remaining() / Float.SIZE_BYTES
            outputBuffer = ensureOutputBuffer(frameCount * Float.SIZE_BYTES)
            val floatIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            var ch = 0
            repeat(frameCount) {
                outputBuffer.putFloat(processSample(floatIn.get(), ch, lpAlpha, hpAlpha, noiseAmp))
                ch = (ch + 1) % channelCount
            }
            inputBuffer.position(inputBuffer.limit())
        } else {
            val frameCount = inputBuffer.remaining() / Short.SIZE_BYTES
            outputBuffer = ensureOutputBuffer(frameCount * Short.SIZE_BYTES)
            val shortIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            var ch = 0
            repeat(frameCount) {
                val y = processSample(shortIn.get() / 32768f, ch, lpAlpha, hpAlpha, noiseAmp)
                val out = (y.coerceIn(-1f, 1f) * 32767f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outputBuffer.putShort(out.toShort())
                ch = (ch + 1) % channelCount
            }
            inputBuffer.position(inputBuffer.limit())
        }
        outputBuffer.flip()
    }

    private fun processSample(x: Float, ch: Int, lpAlpha: Float, hpAlpha: Float, noiseAmp: Float): Float {
        val lp = lpState[ch] + lpAlpha * (x - lpState[ch])
        lpState[ch] = lp
        val hp = hpAlpha * (hpStateOut[ch] + lp - hpStateIn[ch])
        hpStateIn[ch] = lp
        hpStateOut[ch] = hp
        val noise = (random.nextFloat() * 2f - 1f) * noiseAmp
        return (hp + noise).coerceIn(-1f, 1f)
    }

    private fun lpAlpha(cutoffHz: Float, sampleRate: Float): Float {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
        return dt / (rc + dt)
    }

    private fun hpAlpha(cutoffHz: Float, sampleRate: Float): Float {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
        return rc / (rc + dt)
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
        lpState.fill(0f); hpStateIn.fill(0f); hpStateOut.fill(0f)
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
    }
}
