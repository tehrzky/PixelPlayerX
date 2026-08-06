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
import kotlin.math.pow

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
            "bitcrusher" -> BitcrusherNode()
            "delay" -> DelayNode()
            "compressor" -> CompressorNode()
            "pitchshift" -> PitchShiftNode()
            else -> DistortionNode()
        }
        nodeDef to node
    }

    // (nodeIndex, paramKey) -> (macroId, weight)
    private val macroBindingsByNodeParam: Map<Pair<Int, String>, Pair<String, Float>> = buildMap {
        definition.macros.forEach { macro ->
            macro.bindings.forEach { binding ->
                put(binding.nodeIndex to binding.param, macro.id to binding.weight)
            }
        }
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

            val dryWetMix = state.masterValue(definition.id, "dryWetMix", definition.master.dryWetMix).coerceIn(0f, 100f) / 100f
            val outputGainDb = state.masterValue(definition.id, "outputGainDb", definition.master.outputGainDb)
            val gainLinear = 10f.pow(outputGainDb / 20f)

            if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
                val frameCount = inputBuffer.remaining() / Float.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Float.SIZE_BYTES)
                val floatIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                var ch = 0
                repeat(frameCount) {
                    val dry = floatIn.get()
                    val wet = runChain(dry, ch, ch == 0)
                    val mixed = (dry * (1f - dryWetMix) + wet * dryWetMix) * gainLinear
                    outputBuffer.putFloat(mixed.coerceIn(-1f, 1f))
                    ch = (ch + 1) % channelCount
                }
                inputBuffer.position(inputBuffer.limit())
            } else {
                val frameCount = inputBuffer.remaining() / Short.SIZE_BYTES
                outputBuffer = ensureOutputBuffer(frameCount * Short.SIZE_BYTES)
                val shortIn = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                var ch = 0
                repeat(frameCount) {
                    val dry = shortIn.get() / 32768f
                    val wet = runChain(dry, ch, ch == 0)
                    val mixed = (dry * (1f - dryWetMix) + wet * dryWetMix) * gainLinear
                    val out = (mixed.coerceIn(-1f, 1f) * 32767f).toInt()
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

    private fun runChain(inputSample: Float, ch: Int, frameStart: Boolean): Float {
        var sample = inputSample
        nodes.forEachIndexed { nodeIndex, (nodeDef, node) ->
            val nodeId = nodeDef.effectiveId(nodeIndex)
            if (!state.isNodeEnabled(definition.id, nodeId)) return@forEachIndexed
            sample = node.process(sample, ch, frameStart) { key, fallback ->
                val paramDef = nodeDef.params[key]
                val rawOverride = state.paramValues["${definition.id}:$key"]
                val macroBinding = macroBindingsByNodeParam[nodeIndex to key]
                when {
                    rawOverride != null -> rawOverride
                    macroBinding != null && paramDef != null -> {
                        val (macroId, weight) = macroBinding
                        val macroVal = state.macroValue(definition.id, macroId, 50f).coerceIn(0f, 100f)
                        (paramDef.min + (paramDef.max - paramDef.min) * (macroVal / 100f) * weight)
                            .coerceIn(paramDef.min, paramDef.max)
                    }
                    else -> paramDef?.default ?: fallback
                }
            }
        }
        return sample
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
