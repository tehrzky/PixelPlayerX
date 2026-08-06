package com.theveloper.pixelplay.data.service.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** One DSP building block a plugin's chain can use. `params` is a plain map
 * resolved once per audio buffer (not per sample) by PluginAudioProcessor —
 * reading from it here is just a HashMap lookup, no allocation. */
interface PluginDspNode {
    fun configure(channelCount: Int, sampleRate: Int)
    fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float
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

    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val lowCut = params["lowCutHz"] ?: 20f
        val highCut = params["highCutHz"] ?: 18000f
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
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val drive = (params["drive"] ?: 0f).coerceIn(0f, 100f)
        if (drive == 0f) return sample
        val depthBits = 16f - (drive / 100f) * 12f
        val levels = 2f.pow(depthBits)
        val gain = 1f + (drive / 100f) * 3f
        val quantized = (sample * levels).roundToInt() / levels
        return (tanh(quantized * gain) / tanh(gain)).coerceIn(-1f, 1f)
    }
}

/** Vinyl surface noise: lowpassed hiss + a genuine Poisson-process crackle
 * (exponential inter-arrival gaps, each pop a short decaying transient). */
class NoiseNode : PluginDspNode {
    private val random = Random(0)
    private var hissLpState = FloatArray(0)
    private var popCountdown = FloatArray(0)
    private var popEnvelope = FloatArray(0)
    private var popDecay = FloatArray(0)

    override fun configure(channelCount: Int, sampleRate: Int) {
        hissLpState = FloatArray(channelCount)
        popCountdown = FloatArray(channelCount) { nextInterval(0.3f) }
        popEnvelope = FloatArray(channelCount)
        popDecay = FloatArray(channelCount)
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val hiss = (params["hiss"] ?: 0f).coerceIn(0f, 100f) / 100f
        val crackleDensity = (params["crackleDensity"] ?: 0f).coerceIn(0f, 100f) / 100f
        var out = sample

        if (hiss > 0f) {
            val raw = random.nextFloat() * 2f - 1f
            hissLpState[channel] = hissLpState[channel] + 0.3f * (raw - hissLpState[channel])
            out += hissLpState[channel] * hiss * 0.05f
        }

        if (crackleDensity > 0f) {
            popCountdown[channel] -= 1f
            if (popCountdown[channel] <= 0f) {
                popEnvelope[channel] = 0.3f + random.nextFloat() * 0.7f
                popDecay[channel] = 0.6f + random.nextFloat() * 0.3f
                popCountdown[channel] = nextInterval(crackleDensity)
            }
            if (popEnvelope[channel] > 0.001f) {
                val s = if (random.nextBoolean()) 1f else -1f
                out += s * popEnvelope[channel] * (random.nextFloat() * 0.5f + 0.5f)
                popEnvelope[channel] *= popDecay[channel]
            }
        }
        return out.coerceIn(-1f, 1f)
    }

    private fun nextInterval(density: Float): Float {
        val rate = (density * 0.008f).coerceAtLeast(0.00005f)
        val u = random.nextFloat().coerceAtLeast(1e-6f)
        return (-kotlin.math.ln(u) / rate).coerceIn(50f, 200000f)
    }
}

class WobbleNode : PluginDspNode {
    private val ringSize = 8192
    private var ring: Array<FloatArray> = arrayOf()
    private var writePos = 0
    private var phase = 0.0
    private var sampleRate = 44100
    private val random = Random(1)
    private var randomWalk = FloatArray(0)

    override fun configure(channelCount: Int, sampleRate: Int) {
        ring = Array(channelCount) { FloatArray(ringSize) }
        writePos = 0; phase = 0.0
        this.sampleRate = sampleRate
        randomWalk = FloatArray(channelCount)
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val rateHz = (params["rateHz"] ?: 0.8f).coerceIn(0.05f, 20f)
        val depth = (params["depth"] ?: 0f).coerceIn(0f, 100f) / 100f
        val randomness = (params["randomness"] ?: 0f).coerceIn(0f, 100f) / 100f
        val depthSamples = depth * 0.004f * sampleRate
        val baseDelay = 20f + depthSamples

        val buf = ring[channel]
        buf[writePos] = sample

        randomWalk[channel] = (randomWalk[channel] + (random.nextFloat() * 2f - 1f) * 0.01f) * 0.995f
        val periodicLfo = (sin(2.0 * Math.PI * rateHz * phase) * 0.7 + sin(2.0 * Math.PI * rateHz * 8.1 * phase) * 0.3).toFloat()
        val lfo = periodicLfo * (1f - randomness) + randomWalk[channel].coerceIn(-1f, 1f) * randomness
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
        combBuf = Array(channelCount) { Array(baseCombMs.size) { i -> FloatArray((baseCombMs[i] * 4f * sampleRate / 1000f).toInt().coerceAtLeast(1)) } }
        combPos = Array(channelCount) { IntArray(baseCombMs.size) }
        apBuf = Array(channelCount) { Array(baseAllpassMs.size) { i -> FloatArray((baseAllpassMs[i] * 4f * sampleRate / 1000f).toInt().coerceAtLeast(1)) } }
        apPos = Array(channelCount) { IntArray(baseAllpassMs.size) }
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val roomSize = (params["roomSize"] ?: 30f).coerceIn(0f, 100f) / 100f
        val decay = (params["decayTime"] ?: 40f).coerceIn(0f, 95f) / 100f
        val wet = (params["mix"] ?: 30f).coerceIn(0f, 100f) / 100f * 0.6f
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
        phase = FloatArray(channelCount); held = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val bitDepth = (params["bitDepth"] ?: 16f).coerceIn(1f, 16f)
        val targetRate = (params["sampleRateHz"] ?: sampleRate.toFloat()).coerceIn(1000f, sampleRate.toFloat())
        val drive = (params["drive"] ?: 0f).coerceIn(0f, 100f)

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
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val delayMs = (params["delayTimeMs"] ?: 250f).coerceIn(1f, maxDelayMs)
        val feedback = (params["feedback"] ?: 30f).coerceIn(0f, 95f) / 100f
        val highCut = (params["highCutHz"] ?: 8000f).coerceIn(200f, 20000f)
        val pingPong = (params["pingPongPan"] ?: 0f).coerceIn(0f, 1f)

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

        val channelWeight = if (channel % 2 == 0) (1f - pingPong * 0.5f) else (0.5f + pingPong * 0.5f)
        return (sample + delayed * 0.5f * channelWeight).coerceIn(-1f, 1f)
    }
}

class CompressorNode : PluginDspNode {
    private var envelope = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        envelope = FloatArray(channelCount); this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val thresholdDb = (params["thresholdDb"] ?: -18f).coerceIn(-60f, 0f)
        val ratio = (params["ratio"] ?: 4f).coerceIn(1f, 20f)
        val attackMs = (params["attackMs"] ?: 10f).coerceIn(0.1f, 200f)
        val releaseMs = (params["releaseMs"] ?: 100f).coerceIn(1f, 1000f)

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

/** Two-tap granular pitch shifter. Deliberately avoids FFT to stay pure-Kotlin
 * and sample-rate-safe; expect some grain-boundary warble on larger shifts. */
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
        writePos = 0; readPos1 = 0f
    }

    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val semitones = (params["pitchSemitones"] ?: 0f).coerceIn(-12f, 12f)
        val formantShift = (params["formantShift"] ?: 0f).coerceIn(-1f, 1f)

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

class GainNode : PluginDspNode {
    override fun configure(channelCount: Int, sampleRate: Int) {}
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val gainDb = (params["gainDb"] ?: 0f).coerceIn(-24f, 24f)
        return (sample * 10f.pow(gainDb / 20f)).coerceIn(-1f, 1f)
    }
}

class MonoUtilityNode : PluginDspNode {
    private var lastChannelSamples = FloatArray(0)
    private var channelCount = 1
    override fun configure(channelCount: Int, sampleRate: Int) {
        this.channelCount = channelCount
        lastChannelSamples = FloatArray(channelCount)
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val monoAmount = (params["monoAmount"] ?: 100f).coerceIn(0f, 100f) / 100f
        if (channelCount < 2) return sample
        val prevAvg = lastChannelSamples.average().toFloat()
        lastChannelSamples[channel] = sample
        return sample * (1f - monoAmount) + prevAvg * monoAmount
    }
}

class StereoWidenerNode : PluginDspNode {
    private var lastChannelSamples = FloatArray(0)
    private var channelCount = 1
    override fun configure(channelCount: Int, sampleRate: Int) {
        this.channelCount = channelCount
        lastChannelSamples = FloatArray(channelCount)
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val width = (params["width"] ?: 50f).coerceIn(0f, 200f) / 100f
        if (channelCount < 2) return sample
        val other = lastChannelSamples[(channel + 1) % channelCount]
        lastChannelSamples[channel] = sample
        val mid = (sample + other) / 2f
        val side = (sample - other) / 2f
        val widened = if (channel == 0) mid + side * width else mid - side * width
        return widened.coerceIn(-1f, 1f)
    }
}

class ParametricEqNode : PluginDspNode {
    private var x1 = FloatArray(0); private var x2 = FloatArray(0)
    private var y1 = FloatArray(0); private var y2 = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        x1 = FloatArray(channelCount); x2 = FloatArray(channelCount)
        y1 = FloatArray(channelCount); y2 = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val freq = (params["freqHz"] ?: 1000f).coerceIn(20f, 20000f)
        val gainDb = (params["gainDb"] ?: 0f).coerceIn(-24f, 24f)
        val q = (params["q"] ?: 1f).coerceIn(0.1f, 10f)

        val a = 10f.pow(gainDb / 40f)
        val w0 = (2.0 * Math.PI * freq / sampleRate).toFloat()
        val cosw0 = kotlin.math.cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2f * q)

        val b0 = 1f + alpha * a
        val b1 = -2f * cosw0
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cosw0
        val a2 = 1f - alpha / a

        val out = (b0 / a0) * sample + (b1 / a0) * x1[channel] + (b2 / a0) * x2[channel] -
            (a1 / a0) * y1[channel] - (a2 / a0) * y2[channel]

        x2[channel] = x1[channel]; x1[channel] = sample
        y2[channel] = y1[channel]; y1[channel] = out
        return out.coerceIn(-1f, 1f)
    }
}

class ShelvingEqNode : PluginDspNode {
    private var lowLpState = FloatArray(0)
    private var hpIn = FloatArray(0); private var hpOut = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        lowLpState = FloatArray(channelCount)
        hpIn = FloatArray(channelCount); hpOut = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val lowFreq = (params["lowShelfHz"] ?: 200f).coerceIn(20f, 2000f)
        val lowGainDb = (params["lowShelfGainDb"] ?: 0f).coerceIn(-24f, 24f)
        val highFreq = (params["highShelfHz"] ?: 4000f).coerceIn(1000f, 20000f)
        val highGainDb = (params["highShelfGainDb"] ?: 0f).coerceIn(-24f, 24f)

        val dtL = 1f / sampleRate
        val rcL = 1f / (2f * Math.PI.toFloat() * lowFreq)
        val aL = dtL / (rcL + dtL)
        lowLpState[channel] = lowLpState[channel] + aL * (sample - lowLpState[channel])
        val lowGainLin = 10f.pow(lowGainDb / 20f) - 1f
        val afterLow = sample + lowLpState[channel] * lowGainLin

        val dtH = 1f / sampleRate
        val rcH = 1f / (2f * Math.PI.toFloat() * highFreq)
        val aH = rcH / (rcH + dtH)
        val hp = aH * (hpOut[channel] + afterLow - hpIn[channel])
        hpIn[channel] = afterLow
        hpOut[channel] = hp
        val highGainLin = 10f.pow(highGainDb / 20f) - 1f
        return (afterLow + hp * highGainLin).coerceIn(-1f, 1f)
    }
}

class LimiterNode : PluginDspNode {
    private var gainState = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        gainState = FloatArray(channelCount) { 1f }; this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val ceilingDb = (params["ceilingDb"] ?: params["thresholdDb"] ?: -1f).coerceIn(-24f, 0f)
        val releaseMs = (params["releaseMs"] ?: 50f).coerceIn(5f, 500f)
        val ceiling = 10f.pow(ceilingDb / 20f)
        val absVal = abs(sample)
        val target = if (absVal > ceiling) ceiling / absVal else 1f
        val releaseCoeff = exp(-1f / (releaseMs / 1000f * sampleRate))
        gainState[channel] = if (target < gainState[channel]) target else gainState[channel] * releaseCoeff + target * (1f - releaseCoeff)
        return (sample * gainState[channel]).coerceIn(-1f, 1f)
    }
}

class GateNode : PluginDspNode {
    private var envelope = FloatArray(0)
    private var gainState = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        envelope = FloatArray(channelCount)
        gainState = FloatArray(channelCount) { 1f }
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val thresholdDb = (params["thresholdDb"] ?: -40f).coerceIn(-80f, 0f)
        val attackMs = (params["attackMs"] ?: 5f).coerceIn(0.1f, 100f)
        val releaseMs = (params["releaseMs"] ?: 150f).coerceIn(5f, 1000f)

        val absVal = abs(sample)
        val attackCoeff = exp(-1f / (attackMs / 1000f * sampleRate))
        val releaseCoeff = exp(-1f / (releaseMs / 1000f * sampleRate))
        val coeff = if (absVal > envelope[channel]) attackCoeff else releaseCoeff
        envelope[channel] = coeff * envelope[channel] + (1f - coeff) * absVal

        val envDb = 20f * log10(envelope[channel].coerceAtLeast(1e-6f))
        val target = if (envDb > thresholdDb) 1f else 0f
        val gCoeff = if (target < gainState[channel]) attackCoeff else releaseCoeff
        gainState[channel] = gCoeff * gainState[channel] + (1f - gCoeff) * target
        return sample * gainState[channel]
    }
}

class ChorusNode : PluginDspNode {
    private val ringSize = 8192
    private var ring: Array<FloatArray> = arrayOf()
    private var writePos = 0
    private var phase = 0.0
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        ring = Array(channelCount) { FloatArray(ringSize) }
        writePos = 0; phase = 0.0
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val rateHz = (params["rateHz"] ?: 1.5f).coerceIn(0.1f, 8f)
        val depth = (params["depth"] ?: 40f).coerceIn(0f, 100f) / 100f
        val mix = (params["mix"] ?: 50f).coerceIn(0f, 100f) / 100f

        val buf = ring[channel]
        buf[writePos] = sample

        val depthSamples = depth * 0.015f * sampleRate
        val baseDelay = 15f + depthSamples
        val lfo = sin(2.0 * Math.PI * rateHz * phase).toFloat()
        val delaySamples = baseDelay + lfo * depthSamples
        var readPos = writePos - delaySamples
        while (readPos < 0f) readPos += ringSize
        val i0 = floor(readPos).toInt() % ringSize
        val i1 = (i0 + 1) % ringSize
        val frac = readPos - floor(readPos)
        val delayed = buf[i0] * (1f - frac) + buf[i1] * frac

        if (frameStart) {
            phase += 1.0 / sampleRate
            writePos = (writePos + 1) % ringSize
        }
        return (sample * (1f - mix) + delayed * mix).coerceIn(-1f, 1f)
    }
}

class TapeSaturatorNode : PluginDspNode {
    override fun configure(channelCount: Int, sampleRate: Int) {}
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val drive = (params["drive"] ?: 20f).coerceIn(0f, 100f)
        val warmth = (params["warmth"] ?: 30f).coerceIn(0f, 100f)
        if (drive == 0f) return sample
        val g = 1f + drive / 100f * 5f
        val asym = warmth / 100f * 0.3f
        val biased = sample + asym
        val shaped = tanh(biased * g) - tanh(asym * g)
        return (shaped / tanh(g)).coerceIn(-1f, 1f)
    }
}

class DcBlockerNode : PluginDspNode {
    private var xPrev = FloatArray(0)
    private var yPrev = FloatArray(0)
    override fun configure(channelCount: Int, sampleRate: Int) {
        xPrev = FloatArray(channelCount); yPrev = FloatArray(channelCount)
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val r = 0.995f
        val y = sample - xPrev[channel] + r * yPrev[channel]
        xPrev[channel] = sample
        yPrev[channel] = y
        return y.coerceIn(-1f, 1f)
    }
}

class VinylDropoutNode : PluginDspNode {
    private var sampleRate = 44100
    private var phaseSamples = FloatArray(0)
    private val random = Random(2)
    override fun configure(channelCount: Int, sampleRate: Int) {
        this.sampleRate = sampleRate
        phaseSamples = FloatArray(channelCount)
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val rpm = (params["rpm"] ?: 33f).coerceIn(1f, 100f)
        val dropoutAmount = (params["dropoutAmount"] ?: 30f).coerceIn(0f, 100f) / 100f
        val periodSamples = (60f / rpm) * sampleRate

        phaseSamples[channel] += 1f
        if (phaseSamples[channel] >= periodSamples) phaseSamples[channel] -= periodSamples

        val bumpWindow = periodSamples * 0.002f
        val jitter = (random.nextFloat() - 0.5f) * periodSamples * 0.01f
        val distToBump = abs(phaseSamples[channel] - periodSamples / 2f - jitter)
        return if (dropoutAmount > 0f && distToBump < bumpWindow) {
            sample * (1f - dropoutAmount) + (random.nextFloat() * 2f - 1f) * dropoutAmount * 0.4f
        } else sample
    }
}

class PhaserNode : PluginDspNode {
    private val stageCount = 4
    private var apState: Array<FloatArray> = arrayOf()
    private var phase = 0.0
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        apState = Array(channelCount) { FloatArray(stageCount) }
        phase = 0.0
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val rateHz = (params["rateHz"] ?: 0.5f).coerceIn(0.05f, 5f)
        val depth = (params["depth"] ?: 60f).coerceIn(0f, 100f) / 100f
        val feedback = (params["feedback"] ?: 30f).coerceIn(0f, 90f) / 100f
        val mix = (params["mix"] ?: 50f).coerceIn(0f, 100f) / 100f

        val lfo = (sin(2.0 * Math.PI * rateHz * phase).toFloat() * 0.5f + 0.5f) * depth
        val centerFreq = 400f + lfo * 2000f
        val w = (2.0 * Math.PI * centerFreq / sampleRate).toFloat().coerceIn(0.01f, 3f)
        val a = (1f - w) / (1f + w)

        var x = sample + apState[channel][stageCount - 1] * feedback
        for (s in 0 until stageCount) {
            val prevState = apState[channel][s]
            val y = -a * x + prevState
            apState[channel][s] = x + a * y
            x = y
        }
        if (frameStart) phase += 1.0 / sampleRate
        return (sample * (1f - mix) + x * mix).coerceIn(-1f, 1f)
    }
}

class ExciterNode : PluginDspNode {
    private var hpIn = FloatArray(0); private var hpOut = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        hpIn = FloatArray(channelCount); hpOut = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val freq = (params["thresholdHz"] ?: params["freqHz"] ?: 3000f).coerceIn(500f, 12000f)
        val amount = (params["amount"] ?: params["drive"] ?: 30f).coerceIn(0f, 100f) / 100f
        val mix = (params["mix"] ?: 50f).coerceIn(0f, 100f) / 100f

        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * freq)
        val a = rc / (rc + dt)
        val hp = a * (hpOut[channel] + sample - hpIn[channel])
        hpIn[channel] = sample
        hpOut[channel] = hp

        val driven = hp * (1f + amount * 4f)
        val harmonics = sign(driven) * (abs(driven).pow(0.7f)) - hp
        return (sample + harmonics * mix).coerceIn(-1f, 1f)
    }
}

class EnvelopeFollowerNode : PluginDspNode {
    private var envelope = FloatArray(0)
    private var lpState = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        envelope = FloatArray(channelCount); lpState = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val sensitivity = (params["sensitivity"] ?: 50f).coerceIn(0f, 100f) / 100f
        val minFreq = (params["minFreqHz"] ?: 300f).coerceIn(50f, 5000f)
        val maxFreq = (params["maxFreqHz"] ?: 4000f).coerceIn(500f, 18000f)
        val attackMs = (params["attackMs"] ?: 10f).coerceIn(0.5f, 100f)
        val releaseMs = (params["releaseMs"] ?: 150f).coerceIn(5f, 1000f)

        val absVal = abs(sample)
        val attackCoeff = exp(-1f / (attackMs / 1000f * sampleRate))
        val releaseCoeff = exp(-1f / (releaseMs / 1000f * sampleRate))
        val coeff = if (absVal > envelope[channel]) attackCoeff else releaseCoeff
        envelope[channel] = coeff * envelope[channel] + (1f - coeff) * absVal

        val cutoff = minFreq + (maxFreq - minFreq) * (envelope[channel] * sensitivity * 3f).coerceIn(0f, 1f)
        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * cutoff)
        val a = dt / (rc + dt)
        lpState[channel] = lpState[channel] + a * (sample - lpState[channel])
        return lpState[channel]
    }
}

class DeEsserNode : PluginDspNode {
    private var hpIn = FloatArray(0); private var hpOut = FloatArray(0)
    private var envelope = FloatArray(0)
    private var gainState = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        hpIn = FloatArray(channelCount); hpOut = FloatArray(channelCount)
        envelope = FloatArray(channelCount)
        gainState = FloatArray(channelCount) { 1f }
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, params: Map<String, Float>): Float {
        val freq = (params["sibilanceHz"] ?: 6000f).coerceIn(2000f, 12000f)
        val thresholdDb = (params["thresholdDb"] ?: -24f).coerceIn(-60f, 0f)
        val ratio = (params["ratio"] ?: 4f).coerceIn(1f, 20f)

        val dt = 1f / sampleRate
        val rc = 1f / (2f * Math.PI.toFloat() * freq)
        val a = rc / (rc + dt)
        val hp = a * (hpOut[channel] + sample - hpIn[channel])
        hpIn[channel] = sample
        hpOut[channel] = hp

        val attackCoeff = exp(-1f / (0.003f * sampleRate))
        val releaseCoeff = exp(-1f / (0.08f * sampleRate))
        val absVal = abs(hp)
        val envCoeff = if (absVal > envelope[channel]) attackCoeff else releaseCoeff
        envelope[channel] = envCoeff * envelope[channel] + (1f - envCoeff) * absVal

        val envDb = 20f * log10(envelope[channel].coerceAtLeast(1e-6f))
        val targetGainDb = if (envDb > thresholdDb) (thresholdDb + (envDb - thresholdDb) / ratio) - envDb else 0f
        val targetGain = 10f.pow(targetGainDb / 20f)
        val gCoeff = if (targetGain < gainState[channel]) attackCoeff else releaseCoeff
        gainState[channel] = gCoeff * gainState[channel] + (1f - gCoeff) * targetGain

        val reducedHigh = hp * gainState[channel]
        return (sample - hp + reducedHigh).coerceIn(-1f, 1f)
    }
}
