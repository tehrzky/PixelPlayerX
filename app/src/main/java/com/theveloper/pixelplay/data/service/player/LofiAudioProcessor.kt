@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh


/**
 * Bitcrush + soft-clip "lo-fi" effect. Reads live enable/intensity state from
 * [AudioFxStateHolder] every buffer, so toggling from the UI takes effect on the
 * very next audio buffer with no sink flush/reset (no blip, unlike a naive
 * reconfigure would cause — see the cancelNext() volume-reset lesson).
 *
 * isActive() always returns true for supported formats. When disabled or at
 * intensity 0, queueInput() does a fast unmodified copy — so this processor
 * never needs to be added/removed from the chain at runtime.
 */
@UnstableApi
class LofiAudioProcessor(
    private val state: AudioFxStateHolder
) : AudioProcessor {

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (isSupported) {
            inputFormat = inputAudioFormat
            inputAudioFormat // same format out — this is a 1:1 in-place style transform
        } else {
            inputFormat = AudioFormat.NOT_SET
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive()) return

        try {
            val enabled = state.lofiEnabled
            val intensity = state.lofiIntensity.coerceIn(0, 100)

            if (!enabled || intensity == 0) {
                // Bypass: pass the audio through completely unmodified.
                outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
                return
            }

            // Map intensity 0..100 to:
            //  - quantization depth: 16 bits (no crush) down to ~4 bits (heavy crush)
            //  - drive: 1x (clean) up to 4x (heavily saturated) into tanh soft clipper
            val depthBits = 16f - (intensity / 100f) * 12f
            val levels = 2f.pow(depthBits)
            val drive = 1f + (intensity / 100f) * 3f

            if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
                val frameCount = inputBuffer.remaining() / Float.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Float.SIZE_BYTES)
                val floatIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                repeat(frameCount) {
                    val sample = floatIn.get()
                    val quantized = (sample * levels).roundToInt() / levels
                    val driven = tanh(quantized * drive) / tanh(drive)
                    outputBuffer.putFloat(driven.coerceIn(-1f, 1f))
                }
                inputBuffer.position(inputBuffer.limit())
            } else {
                val frameCount = inputBuffer.remaining() / Short.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Short.SIZE_BYTES)
                val shortIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                repeat(frameCount) {
                    val sample = shortIn.get() / 32768f
                    val quantized = (sample * levels).roundToInt() / levels
                    val driven = tanh(quantized * drive) / tanh(drive)
                    val out = (driven.coerceIn(-1f, 1f) * 32767f).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outputBuffer.putShort(out.toShort())
                }
                inputBuffer.position(inputBuffer.limit())
            }
            outputBuffer.flip()
        } catch (t: Throwable) {
            Timber.tag("AudioFxProcessor").e(t, "Lofi DSP failed, falling back to pass-through")
            if (inputBuffer.hasRemaining()) {
                outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
            }
        }
    }

    private fun ensureOutputBuffer(requiredCapacity: Int): ByteBuffer {
        return if (outputBuffer.capacity() < requiredCapacity) {
            ByteBuffer.allocateDirect(requiredCapacity).order(ByteOrder.nativeOrder()).also {
                outputBuffer = it
            }
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
