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

/** Vinyl surface noise, in two parts:
 *  - Hiss: lightly lowpassed white noise (warmer than raw white noise —
 *    real surface hiss isn't full-band static).
 *  - Crackle: a genuine Poisson point process (exponentially distributed
 *    inter-arrival gaps, not a fixed per-sample coin-flip), where each pop
 *    is a short decaying transient with randomized loudness and length —
 *    not a single hard click. This is what actually reads as "vinyl" rather
 *    than "static": real needle pops have a tiny resonant tail. */
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

    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val hiss = param("hiss", 0f).coerceIn(0f, 100f) / 100f
        val crackleDensity = param("crackleDensity", 0f).coerceIn(0f, 100f) / 100f
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
                val sign = if (random.nextBoolean()) 1f else -1f
                out += sign * popEnvelope[channel] * (random.nextFloat() * 0.5f + 0.5f)
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

class GainNode : PluginDspNode {
    override fun configure(channelCount: Int, sampleRate: Int) {}
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val gainDb = param("gainDb", 0f).coerceIn(-24f, 24f)
        return (sample * 10f.pow(gainDb / 20f)).coerceIn(-1f, 1f)
    }
}

/** Stereo-to-mono fold. Note: this streaming per-sample architecture processes
 * one channel at a time with no lookahead into the other channel of the same
 * frame, so this uses the *previous* frame's channel values for blending — a
 * one-sample (≈0.02ms) latency that's inaudible but worth documenting. */
class MonoUtilityNode : PluginDspNode {
    private var lastChannelSamples = FloatArray(0)
    private var channelCount = 1
    override fun configure(channelCount: Int, sampleRate: Int) {
        this.channelCount = channelCount
        lastChannelSamples = FloatArray(channelCount)
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val monoAmount = param("monoAmount", 100f).coerceIn(0f, 100f) / 100f
        if (channelCount < 2) return sample
        val prevAvg = lastChannelSamples.average().toFloat()
        lastChannelSamples[channel] = sample
        return sample * (1f - monoAmount) + prevAvg * monoAmount
    }
}

/** Mid-side stereo widener. Same one-frame-latency caveat as MonoUtilityNode
 * for reading the "other" channel. */
class StereoWidenerNode : PluginDspNode {
    private var lastChannelSamples = FloatArray(0)
    private var channelCount = 1
    override fun configure(channelCount: Int, sampleRate: Int) {
        this.channelCount = channelCount
        lastChannelSamples = FloatArray(channelCount)
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val width = param("width", 50f).coerceIn(0f, 100f) / 100f * 2f
        if (channelCount < 2) return sample
        val other = lastChannelSamples[(channel + 1) % channelCount]
        lastChannelSamples[channel] = sample
        val mid = (sample + other) / 2f
        val side = (sample - other) / 2f
        val widened = if (channel == 0) mid + side * width else mid - side * width
        return widened.coerceIn(-1f, 1f)
    }
}

/** Single-band peaking EQ (RBJ biquad cookbook formula). */
class ParametricEqNode : PluginDspNode {
    private var x1 = FloatArray(0); private var x2 = FloatArray(0)
    private var y1 = FloatArray(0); private var y2 = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        x1 = FloatArray(channelCount); x2 = FloatArray(channelCount)
        y1 = FloatArray(channelCount); y2 = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val freq = param("freqHz", 1000f).coerceIn(20f, 20000f)
        val gainDb = param("gainDb", 0f).coerceIn(-24f, 24f)
        val q = param("q", 1f).coerceIn(0.1f, 10f)

        val a = 10f.pow(gainDb / 40f)
        val w0 = (2.0 * Math.PI * freq / sampleRate).toFloat()
        val cosw0 = kotlin.math.cos(w0)
        val sinw0 = kotlin.math.sin(w0)
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

/** Combined low-shelf + high-shelf via one-pole taps, simpler and cheaper than
 * true RBJ shelving biquads. Good enough for tonal shaping, not mastering-grade. */
class ShelvingEqNode : PluginDspNode {
    private var lowLpState = FloatArray(0)
    private var hpIn = FloatArray(0); private var hpOut = FloatArray(0)
    private var sampleRate = 44100
    override fun configure(channelCount: Int, sampleRate: Int) {
        lowLpState = FloatArray(channelCount)
        hpIn = FloatArray(channelCount); hpOut = FloatArray(channelCount)
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val lowFreq = param("lowShelfHz", 200f).coerceIn(20f, 2000f)
        val lowGainDb = param("lowShelfGainDb", 0f).coerceIn(-24f, 24f)
        val highFreq = param("highShelfHz", 4000f).coerceIn(1000f, 20000f)
        val highGainDb = param("highShelfGainDb", 0f).coerceIn(-24f, 24f)

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
        gainState = FloatArray(channelCount) { 1f }
        this.sampleRate = sampleRate
    }
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val ceilingDb = param("ceilingDb", -1f).coerceIn(-24f, 0f)
        val releaseMs = param("releaseMs", 50f).coerceIn(5f, 500f)
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
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val thresholdDb = param("thresholdDb", -40f).coerceIn(-80f, 0f)
        val attackMs = param("attackMs", 5f).coerceIn(0.1f, 100f)
        val releaseMs = param("releaseMs", 150f).coerceIn(5f, 1000f)

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
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val rateHz = param("rateHz", 1.5f).coerceIn(0.1f, 8f)
        val depth = param("depth", 40f).coerceIn(0f, 100f) / 100f
        val mix = param("mix", 50f).coerceIn(0f, 100f) / 100f

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

/** Asymmetric soft clipping for tube/tape-style warmth — distinct from
 * DistortionNode, which bitcrushes (quantizes) rather than saturating. */
class TapeSaturatorNode : PluginDspNode {
    override fun configure(channelCount: Int, sampleRate: Int) {}
    override fun process(sample: Float, channel: Int, frameStart: Boolean, param: (String, Float) -> Float): Float {
        val drive = param("drive", 20f).coerceIn(0f, 100f)
        val warmth = param("warmth", 30f).coerceIn(0f, 100f)
        if (drive == 0f) return sample
        val g = 1f + drive / 100f * 5f
        val asym = warmth / 100f * 0.3f
        val biased = sample + asym
        val shaped = tanh(biased * g) - tanh(asym * g)
        return (shaped / tanh(g)).coerceIn(-1f, 1f)
    }
}
