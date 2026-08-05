package com.theveloper.pixelplay.data.service.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
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

class BitcrusherNode : PluginDspNode {
    private var phase = FloatArray(0)
    private var held = FloatArray(0)
    private var sampleRate = 44100

    override fun configure(channelCount: Int, sampleRate: Int) {
        phase = FloatArray(channelCount)
        held = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val bitDepth = param("bitDepth", 16f).coerceIn(1f, 16f)
        val targetRate = param("sampleRateHz", sampleRate.toFloat()).coerceIn(1000f, sampleRate.toFloat())
        val drive = param("drive", 0f).coerceIn(0f, 100f)

        val step = targetRate / sampleRate
        phase[channel] += step
        if (phase[channel] >= 1f) {
            phase[channel] -= 1f
            held[channel] = sample
        }
        val levels = 2f.pow(bitDepth)
        val quantized = (held[channel] * levels).roundToInt() / levels
        val gain = 1f + drive / 100f * 2f
        return (tanh(quantized * gain) / tanh(gain)).coerceIn(-1f, 1f)
    }
}

class DelayNode : PluginDspNode {
    private val maxDelayMs = 2000f
    private var buffers: Array<FloatArray> = arrayOf()
    private var writePos = IntArray(0)
    private var lpState = FloatArray(0)
    private var sampleRate = 44100

    override fun configure(channelCount: Int, sampleRate: Int) {
        this.sampleRate = sampleRate
        val maxSamples = (maxDelayMs * sampleRate / 1000f).toInt()
        buffers = Array(channelCount) { FloatArray(maxSamples) }
        writePos = IntArray(channelCount)
        lpState = FloatArray(channelCount)
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val delayMs = param("delayTimeMs", 250f).coerceIn(1f, maxDelayMs)
        val feedback = param("feedback", 30f).coerceIn(0f, 95f) / 100f
        val highCut = param("highCutHz", 8000f).coerceIn(200f, 20000f)
        val pingPong = param("pingPongPan", 0f).coerceIn(0f, 1f)

        val buf = buffers[channel]
        val delaySamples = (delayMs * sampleRate / 1000f).toInt().coerceIn(1, buf.size - 1)
        val readPos = ((writePos[channel] - delaySamples) % buf.size + buf.size) % buf.size
        var delayed = buf[readPos]

        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * highCut)
        val a = dt / (rc + dt)
        lpState[channel] = lpState[channel] + a * (delayed - lpState[channel])
        delayed = lpState[channel]

        buf[writePos[channel]] = sample + delayed * feedback
        writePos[channel] = (writePos[channel] + 1) % buf.size

        // Approximate stereo widening rather than true cross-channel bounce (which
        // would need write-position coordination across channels).
        val channelWeight = if (channel % 2 == 0) (1f - pingPong * 0.5f) else (0.5f + pingPong * 0.5f)
        return (sample + delayed * 0.5f * channelWeight).coerceIn(-1f, 1f)
    }
}

class CompressorNode : PluginDspNode {
    private var envelope = FloatArray(0)
    private var sampleRate = 44100

    override fun configure(channelCount: Int, sampleRate: Int) {
        envelope = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val thresholdDb = param("thresholdDb", -18f).coerceIn(-60f, 0f)
        val ratio = param("ratio", 4f).coerceIn(1f, 20f)
        val attackMs = param("attackMs", 10f).coerceIn(0.1f, 200f)
        val releaseMs = param("releaseMs", 100f).coerceIn(1f, 1000f)

        val inputLevel = abs(sample)
        val attackCoeff = exp(-1f / (attackMs / 1000f * sampleRate))
        val releaseCoeff = exp(-1f / (releaseMs / 1000f * sampleRate))
        val coeff = if (inputLevel > envelope[channel]) attackCoeff else releaseCoeff
        envelope[channel] = coeff * envelope[channel] + (1f - coeff) * inputLevel

        val envDb = 20f * log10(envelope[channel].coerceAtLeast(1e-6f))
        val gainDb = if (envDb > thresholdDb) (thresholdDb + (envDb - thresholdDb) / ratio) - envDb else 0f
        val gain = 10f.pow(gainDb / 20f)
        return (sample * gain).coerceIn(-1f, 1f)
    }
}

/** Simple two-tap granular pitch shifter. This is the most experimental of the
 * four new nodes — true pitch-shifting is inherently hard to do artifact-free
 * without FFT-based processing, which this deliberately avoids to stay in
 * pure-Kotlin, sample-rate-safe territory. Expect some grain-boundary warble on
 * larger shifts; formantShift is a coarse spectral-tilt approximation, not true
 * formant preservation. */
class PitchShiftNode : PluginDspNode {
    private val ringSize = 8192
    private val grainSize = 2048f
    private var ring: Array<FloatArray> = arrayOf()
    private var writePos = 0
    private var readPos1 = 0f
    private var channelCount = 1
    private var tiltState = FloatArray(0)

    override fun configure(channelCount: Int, sampleRate: Int) {
        this.channelCount = channelCount
        ring = Array(channelCount) { FloatArray(ringSize) }
        tiltState = FloatArray(channelCount)
        writePos = 0
        readPos1 = 0f
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val semitones = param("pitchSemitones", 0f).coerceIn(-12f, 12f)
        val formantShift = param("formantShift", 0f).coerceIn(-1f, 1f)

        val buf = ring[channel]
        buf[writePos] = sample

        if (semitones == 0f) {
            if (frameStart) writePos = (writePos + 1) % ringSize
            return sample
        }

        val pitchRatio = 2f.pow(semitones / 12f)
        val readPos2 = readPos1 + grainSize / 2f

        fun tap(pos: Float): Float {
            val wrapped = ((pos % ringSize) + ringSize) % ringSize
            val i0 = floor(wrapped).toInt() % ringSize
            val i1 = (i0 + 1) % ringSize
            val frac = wrapped - floor(wrapped)
            return buf[i0] * (1f - frac) + buf[i1] * frac
        }
        fun window(pos: Float): Float {
            val dist = ((writePos - pos) % grainSize + grainSize) % grainSize
            return 1f - abs(dist / (grainSize / 2f) - 1f)
        }

        var out = tap(readPos1) * window(readPos1) + tap(readPos2) * window(readPos2)

        // Coarse formant approximation: a one-pole tilt filter, not true formant shift.
        if (formantShift != 0f) {
            val a = 0.3f + formantShift * 0.2f
            tiltState[channel] = tiltState[channel] + a * (out - tiltState[channel])
            out = if (formantShift > 0f) tiltState[channel] else out - tiltState[channel] * 0.5f
        }

        if (frameStart) {
            writePos = (writePos + 1) % ringSize
            readPos1 += pitchRatio
            if (readPos1 >= writePos + ringSize) readPos1 -= ringSize
        }
        return out.coerceIn(-1f, 1f)
    }
}
