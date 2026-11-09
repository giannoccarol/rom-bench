package org.rombench.areas.mic

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.rombench.ui.components.SeriesBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** An AudioRecord source; [key] is used in result keys and must stay stable. */
internal data class Source(val key: String, val value: Int, val name: String, val stereo: Boolean = false, val optional: Boolean = false)

internal val SOURCES = listOf(
    Source("mic", MediaRecorder.AudioSource.MIC, "MIC"),
    Source("camcorder", MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER", stereo = true),
    Source("voice_recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
    Source("voice_communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
    Source("unprocessed", MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"),
    Source("voice_performance", MediaRecorder.AudioSource.VOICE_PERFORMANCE, "VOICE_PERFORMANCE", optional = true),
)

internal fun dbfs(v: Double): Double = if (v <= 0) -120.0 else max(-120.0, 20 * log10(v))

/** Statistics of interleaved 16-bit PCM. Levels in dBFS, [dc] and fractions in 0..1. */
internal data class PcmStats(
    val frames: Int,
    val channels: Int,
    val zeroFrac: Double,
    val rmsDb: Double,
    val peakDb: Double,
    val dc: Double,
    val clipFrac: Double,
    val distinct: Int,
    val lrIdentical: Boolean? = null,
    val lrCorr: Double? = null,
    val rmsDbL: Double? = null,
    val rmsDbR: Double? = null,
) {
    val allZero get() = zeroFrac >= 0.9999
    val stuck get() = !allZero && distinct <= 1
}

internal fun analyse(buf: ShortArray, from: Int, to: Int, channels: Int): PcmStats {
    val n = (to - from).coerceAtLeast(0)
    if (n == 0) return PcmStats(0, channels, 1.0, -120.0, -120.0, 0.0, 0.0, 0)
    var zeros = 0
    var clips = 0
    var sum = 0.0
    var sq = 0.0
    var peak = 0
    val seen = HashSet<Short>()
    for (i in from until to) {
        val s = buf[i].toInt()
        if (s == 0) zeros++
        if (s >= 32767 || s <= -32768) clips++
        sum += s
        sq += s.toDouble() * s
        if (abs(s) > peak) peak = abs(s)
        if (seen.size < 4) seen += buf[i]
    }
    var ident: Boolean? = null
    var corr: Double? = null
    var rl: Double? = null
    var rr: Double? = null
    if (channels == 2 && n >= 4) {
        var same = true
        var sl = 0.0; var sr = 0.0; var sll = 0.0; var srr = 0.0; var slr = 0.0
        var k = 0
        var i = from
        while (i + 1 < to) {
            val l = buf[i].toDouble(); val r = buf[i + 1].toDouble()
            if (buf[i] != buf[i + 1]) same = false
            sl += l; sr += r; sll += l * l; srr += r * r; slr += l * r
            k++; i += 2
        }
        ident = same
        val cov = slr / k - (sl / k) * (sr / k)
        val vl = sll / k - (sl / k) * (sl / k)
        val vr = srr / k - (sr / k) * (sr / k)
        corr = if (vl > 0 && vr > 0) cov / sqrt(vl * vr) else null
        rl = dbfs(sqrt(sll / k) / 32768.0)
        rr = dbfs(sqrt(srr / k) / 32768.0)
    }
    val mean = sum / n
    val rms = sqrt(max(0.0, sq / n - mean * mean))
    return PcmStats(
        frames = n / channels, channels = channels, zeroFrac = zeros.toDouble() / n,
        rmsDb = dbfs(rms / 32768.0), peakDb = dbfs(peak / 32768.0), dc = mean / 32768.0,
        clipFrac = clips.toDouble() / n, distinct = seen.size,
        lrIdentical = ident, lrCorr = corr, rmsDbL = rl, rmsDbR = rr,
    )
}

/**
 * Creates an AudioRecord for [source], trying 48, 44.1 and 16 kHz. Throws a readable message
 * when the audio policy has no input profile for the request.
 */
@SuppressLint("MissingPermission")
internal fun buildRecord(source: Int, stereo: Boolean, minMs: Int = 400): AudioRecord {
    val mask = if (stereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    val ch = if (stereo) 2 else 1
    var last = "no sample rate accepted"
    for (rate in intArrayOf(48000, 44100, 16000)) {
        val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { last = "getMinBufferSize($rate Hz, $ch ch) = $min"; continue }
        try {
            val r = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(max(min * 2, rate * ch * 2 * minMs / 1000))
                .build()
            if (r.state == AudioRecord.STATE_INITIALIZED) return r
            last = "AudioRecord state ${r.state} at $rate Hz"
            r.release()
        } catch (e: Exception) {
            last = "${e.javaClass.simpleName}: ${e.message} at $rate Hz"
        }
    }
    error(last)
}

internal enum class RecStage { INIT, START, NO_DATA, OK }

/** Outcome of [probeRecord]. */
internal data class RecProbe(
    val stage: RecStage,
    val error: String? = null,
    val rate: Int = 0,
    val channels: Int = 1,
    val firstDataMs: Long? = null,
    val frames: Int = 0,
    val elapsedMs: Long = 0,
    val dataMs: Long = 0,
    val leadingZeroMs: Long = 0,
    val stats: PcmStats? = null,
    val routed: AudioDeviceInfo? = null,
    val silenced: Boolean? = null,
)

/**
 * Records [ms] milliseconds with non-blocking reads (a dead input HAL must not hang the check),
 * discards the first 150 ms (mic power-up, ramp) and analyses the rest.
 */
internal suspend fun probeRecord(source: Source, device: AudioDeviceInfo?, ms: Int = 1000, deadlineMs: Long = 3000): RecProbe =
    withContext(Dispatchers.IO) {
        var stereo = source.stereo
        val rec = try {
            buildRecord(source.value, stereo)
        } catch (e: Exception) {
            if (!stereo) return@withContext RecProbe(RecStage.INIT, e.message)
            stereo = false
            try {
                buildRecord(source.value, false)
            } catch (e2: Exception) {
                return@withContext RecProbe(RecStage.INIT, e2.message)
            }
        }
        val ch = rec.channelCount
        val rate = rec.sampleRate
        val skip = rate * ch * 150 / 1000
        val want = rate * ch * ms / 1000 + skip
        val buf = ShortArray(want)
        var got = 0
        var firstAt: Long? = null
        var silenced: Boolean? = null
        val t0 = SystemClock.elapsedRealtime()
        try {
            device?.let { rec.setPreferredDevice(it) }
            try {
                rec.startRecording()
            } catch (e: Exception) {
                return@withContext RecProbe(RecStage.START, e.message ?: e.toString(), rate, ch)
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext RecProbe(RecStage.START, "recordingState ${rec.recordingState}", rate, ch)
            }
            while (got < want && SystemClock.elapsedRealtime() - t0 < deadlineMs) {
                ensureActive()
                val n = rec.read(buf, got, want - got, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) return@withContext RecProbe(RecStage.NO_DATA, "AudioRecord.read() = $n", rate, ch, firstAt?.minus(t0))
                if (n > 0) {
                    if (firstAt == null) firstAt = SystemClock.elapsedRealtime()
                    got += n
                } else {
                    delay(10)
                }
                if (silenced == null && got > 0) silenced = rec.activeRecordingConfiguration?.isClientSilenced
            }
            val end = SystemClock.elapsedRealtime()
            val routed = rec.routedDevice
            if (silenced == null) silenced = rec.activeRecordingConfiguration?.isClientSilenced
            val fa = firstAt ?: return@withContext RecProbe(RecStage.NO_DATA, null, rate, ch, null, 0, end - t0, routed = routed, silenced = silenced)
            var lead = 0
            while (lead < got && buf[lead].toInt() == 0) lead++
            RecProbe(
                RecStage.OK, null, rate, ch, fa - t0, got / ch, end - t0, end - fa,
                leadingZeroMs = lead.toLong() / ch * 1000 / rate,
                stats = analyse(buf, minOf(skip, got), got, ch),
                routed = routed, silenced = silenced,
            )
        } finally {
            withContext(NonCancellable) {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
            }
        }
    }

/** Effects the HAL offers for capture. */
internal data class Effects(val ns: Boolean, val agc: Boolean, val aec: Boolean)

internal fun effectsAvailable() = Effects(
    runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
    runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false),
    runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
)

internal fun effectDescriptors(): List<AudioEffect.Descriptor> = runCatching { AudioEffect.queryEffects()?.toList() }.getOrNull() ?: emptyList()

/**
 * Live capture for the interactive screen: level meter, waveform, optional recording of N
 * seconds. Runs one AudioRecord on its own thread; [stop] unblocks the read.
 */
internal class MicEngine {
    val levels = SeriesBuffer(150, 2)
    val wave = SeriesBuffer(240, 1)
    var rmsDb by mutableFloatStateOf(-120f)
    var peakDb by mutableFloatStateOf(-120f)
    var running by mutableStateOf(false)
        private set
    var routed by mutableStateOf<AudioDeviceInfo?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
    var silentMs by mutableStateOf(0L)
        private set
    var recProgress by mutableFloatStateOf(-1f)
        private set
    var recorded by mutableStateOf<Recording?>(null)
    var applied by mutableStateOf<String?>(null)
        private set

    class Recording(val pcm: ShortArray, val rate: Int, val channels: Int, val stats: PcmStats, val source: String, val device: AudioDeviceInfo?)

    @Volatile private var active = false
    @Volatile private var rec: AudioRecord? = null
    private var thread: Thread? = null

    fun start(source: Source, device: AudioDeviceInfo?, stereo: Boolean, fx: Effects, recordSeconds: Int?) {
        stop()
        failure = null
        active = true
        running = true
        levels.clear()
        wave.clear()
        silentMs = 0
        recProgress = if (recordSeconds != null) 0f else -1f
        thread = Thread({
            var r: AudioRecord? = null
            val effects = mutableListOf<AudioEffect>()
            try {
                r = buildRecord(source.value, stereo, minMs = 200)
                device?.let { r.setPreferredDevice(it) }
                val names = mutableListOf<String>()
                if (fx.ns) NoiseSuppressor.create(r.audioSessionId)?.let { it.enabled = true; effects += it; names += "NS" }
                if (fx.agc) AutomaticGainControl.create(r.audioSessionId)?.let { it.enabled = true; effects += it; names += "AGC" }
                if (fx.aec) AcousticEchoCanceler.create(r.audioSessionId)?.let { it.enabled = true; effects += it; names += "AEC" }
                applied = names.joinToString("+").ifEmpty { null }
                rec = r
                val ch = r.channelCount
                val rate = r.sampleRate
                val block = rate * ch / 50
                val buf = ShortArray(block)
                val total = recordSeconds?.let { rate * ch * it }
                val store = total?.let { ShortArray(it) }
                var stored = 0
                var zeroRun = 0L
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("recordingState ${r.recordingState}")
                var routedRead = false
                while (active) {
                    val n = r.read(buf, 0, block)
                    if (n < 0) throw IllegalStateException("AudioRecord.read() = $n")
                    if (n == 0) continue
                    if (!routedRead) { routed = r.routedDevice; routedRead = true }
                    val st = analyse(buf, 0, n - n % ch, ch)
                    rmsDb = st.rmsDb.toFloat()
                    peakDb = st.peakDb.toFloat()
                    levels.add(rmsDb, peakDb)
                    zeroRun = if (st.allZero) zeroRun + n * 1000L / (rate * ch) else 0
                    silentMs = zeroRun
                    // Waveform: 12 points per 20 ms block, signed sample of largest magnitude.
                    val step = max(1, n / ch / 12)
                    var f = 0
                    while (f < n / ch) {
                        var best = 0
                        for (j in f until minOf(f + step, n / ch)) {
                            val s = buf[j * ch].toInt()
                            if (abs(s) > abs(best)) best = s
                        }
                        wave.add(best / 32768f)
                        f += step
                    }
                    if (store != null) {
                        val c = minOf(n, store.size - stored)
                        System.arraycopy(buf, 0, store, stored, c)
                        stored += c
                        recProgress = stored.toFloat() / store.size
                        if (stored >= store.size) {
                            val skip = minOf(rate * ch * 150 / 1000, stored)
                            recorded = Recording(store, rate, ch, analyse(store, skip, stored, ch), source.name, device)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (active) failure = e.message ?: e.toString()
            } finally {
                active = false
                rec = null
                effects.forEach { runCatching { it.release() } }
                try {
                    r?.stop()
                } catch (_: Exception) {
                }
                r?.release()
                running = false
                recProgress = -1f
            }
        }, "rb-mic-live").also { it.start() }
    }

    fun stop() {
        active = false
        try {
            rec?.stop()
        } catch (_: Exception) {
        }
        thread?.join(1500)
        thread = null
        running = false
    }
}
