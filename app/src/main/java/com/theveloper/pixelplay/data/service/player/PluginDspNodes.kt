package com.theveloper.pixelplay.data.service.player

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** One DSP building block a plugin's chain can use. Same math as our built-in
 * Lo-Fi/Radio/Wow-Flutter/Reverb processors, generalized to read named params
 * live instead of fixed fields. */
interface PluginDspNode {
    fun configure(channelCount: Int, sampleRate: Int)
    fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float
}

class BandpassNode : PluginDspNode {
    private var lpState = FloatArray(0)
    private var hpStateIn = FloatArray(0)
    private var hpStateOut = FloatArray(0)
    private var sampleRate = 44100

    override fun configure(channelCount: Int, sampleRate: Int) {
        lpState = FloatArray(channelCount)
        hpStateIn = FloatArray(channelCount)
        hpStateOut = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val lowCut = param("lowCutHz", 20f)
        val highCut = param("highCutHz", 18000f)
        val lpA = alpha(highCut, true)
        val hpA = alpha(lowCut, false)
        val lp = lpState[channel] + lpA * (sample - lpState[channel])
        lpState[channel] = lp
        val hp = hpA * (hpStateOut[channel] + lp - hpStateIn[channel])
        hpStateIn[channel] = lp
        hpStateOut[channel] = hp
        return hp
    }

    private fun alpha(cutoffHz: Float, isLowpass: Boolean): Float {
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz.coerceAtLeast(1f))
        return if (isLowpass) dt / (rc + dt) else rc / (rc + dt)
    }
}

class DistortionNode : PluginDspNode {
    override fun configure(channelCount: Int, sampleRate: Int) {}

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val drive = param("drive", 0f).coerceIn(0f, 100f)
        if (drive == 0f) return sample
        val depthBits = 16f - (drive / 100f) * 12f
        val levels = 2f.pow(depthBits)
        val gain = 1f + (drive / 100f) * 3f
        val quantized = (sample * levels).roundToInt() / levels
        return (tanh(quantized * gain) / tanh(gain)).coerceIn(-1f, 1f)
    }
}

class NoiseNode : PluginDspNode {
    private val random = Random(0)
    override fun configure(channelCount: Int, sampleRate: Int) {}

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val hiss = param("hiss", 0f).coerceIn(0f, 100f) / 100f * 0.04f
        val crackleDensity = param("crackleDensity", 0f).coerceIn(0f, 100f) / 100f
        var out = sample + (random.nextFloat() * 2f - 1f) * hiss
        if (crackleDensity > 0f && random.nextFloat() < crackleDensity * 0.0006f) {
            out += (random.nextFloat() * 2f - 1f) * 0.6f
        }
        return out.coerceIn(-1f, 1f)
    }
}

class WobbleNode : PluginDspNode {
    private val ringSize = 8192
    private var ring: Array<FloatArray> = arrayOf()
    private var writePos = 0
    private var phase = 0.0
    private var sampleRate = 44100

    override fun configure(channelCount: Int, sampleRate: Int) {
        ring = Array(channelCount) { FloatArray(ringSize) }
        writePos = 0
        phase = 0.0
        this.sampleRate = sampleRate
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val rateHz = param("rateHz", 0.8f).coerceIn(0.05f, 20f)
        val depth = param("depth", 0f).coerceIn(0f, 100f) / 100f
        val depthSamples = depth * 0.004f * sampleRate
        val baseDelay = 20f + depthSamples

        val buf = ring[channel]
        buf[writePos] = sample

        val lfo = sin(2.0 * Math.PI * rateHz * phase).toFloat()
        val delaySamples = baseDelay + lfo * depthSamples
        var readPos = writePos - delaySamples
        while (readPos < 0f) readPos += ringSize
        val i0 = floor(readPos).toInt() % ringSize
        val i1 = (i0 + 1) % ringSize
        val frac = readPos - floor(readPos)
        val result = buf[i0] * (1f - frac) + buf[i1] * frac

        if (frameStart) {
            phase += 1.0 / sampleRate
            writePos = (writePos + 1) % ringSize
        }
        return result
    }
}

class ReverbNode : PluginDspNode {
    private val baseCombMs = floatArrayOf(9.7f, 11.3f, 13.9f, 17.2f)
    private val baseAllpassMs = floatArrayOf(3.1f, 1.9f)
    private var combBuf: Array<Array<FloatArray>> = arrayOf()
    private var combPos: Array<IntArray> = arrayOf()
    private var apBuf: Array<Array<FloatArray>> = arrayOf()
    private var apPos: Array<IntArray> = arrayOf()
    private var sampleRate = 44100

    override fun configure(channelCount: Int, sampleRate: Int) {
        this.sampleRate = sampleRate
        combBuf = Array(channelCount) { Array(baseCombMs.size) { i -> FloatArray(((baseCombMs[i] * 4f) * sampleRate / 1000f).toInt().coerceAtLeast(1)) } }
        combPos = Array(channelCount) { IntArray(baseCombMs.size) }
        apBuf = Array(channelCount) { Array(baseAllpassMs.size) { i -> FloatArray(((baseAllpassMs[i] * 4f) * sampleRate / 1000f).toInt().coerceAtLeast(1)) } }
        apPos = Array(channelCount) { IntArray(baseAllpassMs.size) }
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val roomSize = param("roomSize", 30f).coerceIn(0f, 100f) / 100f
        val decay = param("decayTime", 40f).coerceIn(0f, 95f) / 100f
        val wet = param("mix", 30f).coerceIn(0f, 100f) / 100f * 0.6f
        val sizeMultiplier = 1f + roomSize * 3f

        var combSum = 0f
        for (i in baseCombMs.indices) {
            val maxLen = combBuf[channel][i].size
            val activeLen = ((baseCombMs[i] * sizeMultiplier) * sampleRate / 1000f).toInt().coerceIn(1, maxLen)
            val pos = combPos[channel][i] % activeLen
            val buf = combBuf[channel][i]
            val delayed = buf[pos]
            buf[pos] = sample + delayed * decay
            combPos[channel][i] = (pos + 1) % activeLen
            combSum += delayed
        }
        var signal = combSum / baseCombMs.size

        for (i in baseAllpassMs.indices) {
            val maxLen = apBuf[channel][i].size
            val activeLen = ((baseAllpassMs[i] * sizeMultiplier) * sampleRate / 1000f).toInt().coerceIn(1, maxLen)
            val pos = apPos[channel][i] % activeLen
            val buf = apBuf[channel][i]
            val delayed = buf[pos]
            val out = -signal * 0.5f + delayed
            buf[pos] = signal + delayed * 0.5f
            apPos[channel][i] = (pos + 1) % activeLen
            signal = out
        }

        return sample * (1f - wet) + signal * wet
    }
}
