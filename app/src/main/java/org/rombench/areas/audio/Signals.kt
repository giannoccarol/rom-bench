package org.rombench.areas.audio

import androidx.annotation.StringRes
import org.rombench.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal enum class Signal(@StringRes val label: Int) {
    TONE_440(R.string.audio_sig_440),
    TONE_1K(R.string.audio_sig_1k),
    SWEEP(R.string.audio_sig_sweep),
    MUSIC(R.string.audio_sig_music),
    NOISE(R.string.audio_sig_noise),
}

internal enum class Channel(@StringRes val label: Int) {
    BOTH(R.string.audio_ch_both),
    LEFT(R.string.audio_ch_left),
    RIGHT(R.string.audio_ch_right),
}

/** Test signals generated in code (no assets): tones, a log sweep, a music-like arpeggio, pink noise. */
internal class SignalGen(private val rate: Int) {
    var signal = Signal.TONE_440
    private var n = 0L
    private var phase = 0.0
    private var phaseBass = 0.0
    private val rnd = java.util.Random(7)
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0

    /** Next sample in -1..1. The first 20 ms fade in to avoid a click. */
    fun sample(): Double {
        val v = when (signal) {
            Signal.TONE_440 -> sine(440.0)
            Signal.TONE_1K -> sine(1000.0)
            Signal.SWEEP -> {
                val period = 4L * rate
                val pos = (n % period).toDouble() / period
                sine(100.0 * 100.0.pow(pos)) * 0.9
            }
            Signal.MUSIC -> music()
            Signal.NOISE -> pink()
        }
        n++
        val ramp = rate / 50
        return if (n < ramp) v * n / ramp else v
    }

    private fun sine(f: Double): Double {
        phase += 2 * PI * f / rate
        if (phase > 2 * PI) phase -= 2 * PI
        return sin(phase)
    }

    private fun music(): Double {
        val noteLen = rate / 4
        val i = ((n / noteLen) % NOTES.size).toInt()
        val t = (n % noteLen).toDouble() / rate
        val env = exp(-5.0 * t) * min(1.0, t * 300)
        phase += 2 * PI * NOTES[i] / rate
        if (phase > 2 * PI) phase -= 2 * PI
        val lead = sin(phase) + 0.4 * sin(2 * phase) + 0.2 * sin(3 * phase)
        val bar = ((n / (noteLen * 4L)) % BASS.size).toInt()
        phaseBass += 2 * PI * BASS[bar] / rate
        if (phaseBass > 2 * PI) phaseBass -= 2 * PI
        val bass = sin(phaseBass) + 0.3 * sin(2 * phaseBass)
        return 0.5 * env * lead / 1.6 + 0.3 * bass / 1.3
    }

    /** Paul Kellet's pink noise filter over gaussian white noise. */
    private fun pink(): Double {
        val white = (rnd.nextGaussian() * 0.25).coerceIn(-1.0, 1.0)
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.96900 * b2 + white * 0.1538520
        b3 = 0.86650 * b3 + white * 0.3104856
        b4 = 0.55000 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.0168980
        val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return (p * 0.3).coerceIn(-1.0, 1.0)
    }

    /** Fills [frames] interleaved stereo frames of 16-bit PCM. */
    fun fillStereo(buf: ShortArray, frames: Int, channel: Channel, amplitude: Float) {
        for (i in 0 until frames) {
            val s = (sample() * amplitude * 32767).toInt().coerceIn(-32768, 32767).toShort()
            buf[2 * i] = if (channel != Channel.RIGHT) s else 0
            buf[2 * i + 1] = if (channel != Channel.LEFT) s else 0
        }
    }

    private companion object {
        val NOTES = doubleArrayOf(261.63, 329.63, 392.0, 523.25, 392.0, 329.63, 293.66, 349.23, 440.0, 587.33, 440.0, 349.23)
        val BASS = doubleArrayOf(130.81, 146.83, 110.0)
    }
}
