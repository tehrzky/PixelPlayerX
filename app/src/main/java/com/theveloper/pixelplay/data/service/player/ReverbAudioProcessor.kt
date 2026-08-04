@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Short Schroeder-style reverb: 4 parallel combs + 2 series allpasses per
 * channel, mixed with the dry signal by intensity. Tuned short/small-room,
 * not a cathedral.
 */
@UnstableApi
class ReverbAudioProcessor(
    private val state: AudioFxStateHolder
) : AudioProcessor {

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var channelCount = 0

    private val combDelaysMs = floatArrayOf(9.7f, 11.3f, 13.9f, 17.2f)
    private val combFeedback = 0.55f
    private val allpassDelaysMs = floatArrayOf(3.1f, 1.9f)
    private val allpassFeedback = 0.5f

    private var combBuffers: Array<Array<FloatArray>> = arrayOf()
    private var combPos: Array<IntArray> = arrayOf()
    private var allpassBuffers: Array<Array<FloatArray>> = arrayOf()
    private var allpassPos: Array<IntArray> = arrayOf()

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (isSupported) {
            inputFormat = inputAudioFormat
            channelCount = inputAudioFormat.channelCount
            val sr = inputAudioFormat.sampleRate
            combBuffers = Array(channelCount) {
                Array(combDelaysMs.size) { i -> FloatArray((combDelaysMs[i] * sr / 1000f).toInt().coerceAtLeast(1)) }
            }
            combPos = Array(channelCount) { IntArray(combDelaysMs.size) }
            allpassBuffers = Array(channelCount) {
                Array(allpassDelaysMs.size) { i -> FloatArray((allpassDelaysMs[i] * sr / 1000f).toInt().coerceAtLeast(1)) }
            }
            allpassPos = Array(channelCount) { IntArray(allpassDelaysMs.size) }
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
            val enabled = state.reverbEnabled
            val intensity = state.reverbIntensity.coerceIn(0, 100)

            if (!enabled || intensity == 0) {
                outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
                return
            }

            val wet = (intensity / 100f) * 0.5f

            if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
                val frameCount = inputBuffer.remaining() / Float.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Float.SIZE_BYTES)
                val floatIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                var ch = 0
                repeat(frameCount) {
                    outputBuffer.putFloat(processSample(floatIn.get(), ch, wet))
                    ch = (ch + 1) % channelCount
                }
                inputBuffer.position(inputBuffer.limit())
            } else {
                val frameCount = inputBuffer.remaining() / Short.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Short.SIZE_BYTES)
                val shortIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                var ch = 0
                repeat(frameCount) {
                    val y = processSample(shortIn.get() / 32768f, ch, wet)
                    val out = (y.coerceIn(-1f, 1f) * 32767f).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outputBuffer.putShort(out.toShort())
                    ch = (ch + 1) % channelCount
                }
                inputBuffer.position(inputBuffer.limit())
            }
            outputBuffer.flip()
        } catch (t: Throwable) {
            Timber.tag("AudioFxProcessor").e(t, "Reverb DSP failed, falling back to pass-through")
            if (inputBuffer.hasRemaining()) {
                outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
            }
        }
    }

    private fun processSample(x: Float, ch: Int, wet: Float): Float {
        var combSum = 0f
        for (i in combDelaysMs.indices) {
            val buf = combBuffers[ch][i]
            val pos = combPos[ch][i]
            val delayed = buf[pos]
            buf[pos] = x + delayed * combFeedback
            combPos[ch][i] = (pos + 1) % buf.size
            combSum += delayed
        }
        var signal = combSum / combDelaysMs.size

        for (i in allpassDelaysMs.indices) {
            val buf = allpassBuffers[ch][i]
            val pos = allpassPos[ch][i]
            val delayed = buf[pos]
            val out = -signal * allpassFeedback + delayed
            buf[pos] = signal + delayed * allpassFeedback
            allpassPos[ch][i] = (pos + 1) % buf.size
            signal = out
        }

        return x * (1f - wet) + signal * wet
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
