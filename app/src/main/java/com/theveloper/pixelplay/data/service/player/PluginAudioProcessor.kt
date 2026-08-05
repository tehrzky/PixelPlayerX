@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.plugin.PluginDefinition
import com.theveloper.pixelplay.data.plugin.PluginNodeDef
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Executes one imported plugin's DSP chain. Built fresh per ExoPlayer instance
 * (never shared between the two crossfading players — a shared-singleton
 * processor caused a real race condition we found in a comparison branch, so
 * this deliberately avoids that). Wrapped in try/catch so a bad plugin file
 * can't kill playback, same as the built-in effects.
 */
@UnstableApi
class PluginAudioProcessor(
    private val definition: PluginDefinition,
    private val state: PluginStateHolder
) : AudioProcessor {

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var channelCount = 0

    private val nodes: List<Pair<PluginNodeDef, PluginDspNode>> = definition.chain.map { nodeDef ->
        val node: PluginDspNode = when (nodeDef.type) {
            "bandpass" -> BandpassNode()
            "distortion" -> DistortionNode()
            "noise" -> NoiseNode()
            "wobble" -> WobbleNode()
            "reverb" -> ReverbNode()
            else -> DistortionNode()
        }
        nodeDef to node
    }

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        return if (isSupported) {
            inputFormat = inputAudioFormat
            channelCount = inputAudioFormat.channelCount
            nodes.forEach { (_, node) -> node.configure(channelCount, inputAudioFormat.sampleRate) }
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
            if (!state.isEnabled(definition.id)) {
                outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
                return
            }

            if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
                val frameCount = inputBuffer.remaining() / Float.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Float.SIZE_BYTES)
                val floatIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                var ch = 0
                repeat(frameCount) {
                    var sample = floatIn.get()
                    val frameStart = ch == 0
                    for ((nodeDef, node) in nodes) {
                        sample = node.process(sample, ch, frameStart) { key, fallback ->
                            state.paramValue(definition.id, key, nodeDef.params[key]?.default ?: fallback)
                        }
                    }
                    outputBuffer.putFloat(sample.coerceIn(-1f, 1f))
                    ch = (ch + 1) % channelCount
                }
                inputBuffer.position(inputBuffer.limit())
            } else {
                val frameCount = inputBuffer.remaining() / Short.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Short.SIZE_BYTES)
                val shortIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                var ch = 0
                repeat(frameCount) {
                    var sample = shortIn.get() / 32768f
                    val frameStart = ch == 0
                    for ((nodeDef, node) in nodes) {
                        sample = node.process(sample, ch, frameStart) { key, fallback ->
                            state.paramValue(definition.id, key, nodeDef.params[key]?.default ?: fallback)
                        }
                    }
                    val out = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outputBuffer.putShort(out.toShort())
                    ch = (ch + 1) % channelCount
                }
                inputBuffer.position(inputBuffer.limit())
            }
            outputBuffer.flip()
        } catch (t: Throwable) {
            Timber.tag("AudioFxProcessor").e(t, "Plugin '${definition.id}' DSP failed, falling back to pass-through")
            if (inputBuffer.hasRemaining()) {
                outputBuffer = ensureOutputBuffer(inputBuffer.remaining())
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
            }
        }
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
