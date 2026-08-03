@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sin

/**
 * Tape/vinyl-style pitch wobble via a modulated fractional delay line.
 * "Wow" = slow ~0.8Hz drift, "flutter" = faster ~6.5Hz wobble.
 */
@UnstableApi
class WowFlutterAudioProcessor(
    private val state: AudioFxStateHolder
) : AudioProcessor {

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var channelCount = 0
    private var sampleRate = 44100

    private val ringSize = 8192
    private var ringBuffers: Array<FloatArray> = arrayOf()
    private var writePos = 0
    private var phase = 0.0

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (isSupported) {
            inputFormat = inputAudioFormat
            channelCount = inputAudioFormat.channelCount
            sampleRate = inputAudioFormat.sampleRate
            ringBuffers = Array(channelCount) { FloatArray(ringSize) }
            writePos = 0
            phase = 0.0
            inputAudioFormat
        } else {
            inputFormat = AudioFormat.NOT_SET
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive()) return
        val enabled = state.wowFlutterEnabled
        val intensity = state.wowFlutterIntensity.coerceIn(0, 100)

        if (!enabled || intensity == 0) {
            outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val t = intensity / 100f
        val depthSamples = t * 0.004f * sampleRate
        val baseDelaySamples = 20f + depthSamples
        val wowRate = 0.8
        val flutterRate = 6.5

        if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val frameCount = inputBuffer.remaining() / Float.SIZE_BYTES
            outputBuffer = ensureOutputBuffer(frameCount * Float.SIZE_BYTES)
            val floatIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
            var ch = 0
            repeat(frameCount) {
                outputBuffer.putFloat(processSample(floatIn.get(), ch, baseDelaySamples, depthSamples, wowRate, flutterRate))
                if (ch == channelCount - 1) advanceFrame()
                ch = (ch + 1) % channelCount
            }
            inputBuffer.position(inputBuffer.limit())
        } else {
            val frameCount = inputBuffer.remaining() / Short.SIZE_BYTES
            outputBuffer = ensureOutputBuffer(frameCount * Short.SIZE_BYTES)
            val shortIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            var ch = 0
            repeat(frameCount) {
                val y = processSample(shortIn.get() / 32768f, ch, baseDelaySamples, depthSamples, wowRate, flutterRate)
                val out = (y.coerceIn(-1f, 1f) * 32767f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outputBuffer.putShort(out.toShort())
                if (ch == channelCount - 1) advanceFrame()
                ch = (ch + 1) % channelCount
            }
            inputBuffer.position(inputBuffer.limit())
        }
        outputBuffer.flip()
    }

    private fun advanceFrame() {
        phase += 1.0 / sampleRate
        writePos = (writePos + 1) % ringSize
    }

    private fun processSample(
        x: Float, ch: Int, baseDelaySamples: Float, depthSamples: Float,
        wowRate: Double, flutterRate: Double
    ): Float {
        val ring = ringBuffers[ch]
        ring[writePos] = x

        val lfo = (sin(2.0 * Math.PI * wowRate * phase) * 0.7 +
            sin(2.0 * Math.PI * flutterRate * phase) * 0.3).toFloat()
        val delaySamples = baseDelaySamples + lfo * depthSamples

        var readPos = writePos - delaySamples
        while (readPos < 0f) readPos += ringSize
        val idx0 = floor(readPos).toInt() % ringSize
        val idx1 = (idx0 + 1) % ringSize
        val frac = readPos - floor(readPos)
        return ring[idx0] * (1f - frac) + ring[idx1] * frac
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
