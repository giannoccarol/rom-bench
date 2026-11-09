package org.rombench.areas.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

internal const val PLAY_RATE = 48000

internal fun attributes(voice: Boolean): AudioAttributes = AudioAttributes.Builder()
    .setUsage(if (voice) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
    .setContentType(if (voice) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/** Builds a 16-bit PCM stream track; throws with a readable message when AudioFlinger refuses it. */
internal fun buildTrack(rate: Int, channels: Int, voice: Boolean, minMs: Int = 400): AudioTrack {
    val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
    require(min > 0) { "getMinBufferSize($rate Hz, $channels ch) = $min" }
    val track = AudioTrack.Builder()
        .setAudioAttributes(attributes(voice))
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
        .setBufferSizeInBytes(max(min, rate * channels * 2 * minMs / 1000))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    if (track.state != AudioTrack.STATE_INITIALIZED) {
        val s = track.state
        track.release()
        error("AudioTrack state $s (not initialised)")
    }
    return track
}

/** Unsigned 32-bit playback head position. */
internal fun AudioTrack.head(): Long = playbackHeadPosition.toLong() and 0xffffffffL

/**
 * Interactive player of [SignalGen] signals on its own thread. A blocking write on a dead HAL is
 * interrupted by [stop], which calls AudioTrack.stop() from the caller's thread.
 */
internal class SignalPlayer {
    @Volatile var signal = Signal.TONE_440
    @Volatile var channel = Channel.BOTH
    @Volatile var amplitude = 0.5f
    @Volatile private var running = false
    @Volatile var track: AudioTrack? = null
        private set
    private var thread: Thread? = null

    val isRunning get() = running

    fun start(device: AudioDeviceInfo?, voice: Boolean, onError: (String) -> Unit) {
        stop()
        running = true
        thread = Thread({
            var t: AudioTrack? = null
            try {
                t = buildTrack(PLAY_RATE, 2, voice)
                device?.let { t.setPreferredDevice(it) }
                track = t
                val gen = SignalGen(PLAY_RATE)
                val frames = PLAY_RATE / 50
                val buf = ShortArray(frames * 2)
                t.play()
                while (running) {
                    gen.signal = signal
                    gen.fillStereo(buf, frames, channel, amplitude)
                    val w = t.write(buf, 0, buf.size)
                    if (w < 0) {
                        if (running) onError("AudioTrack.write() = $w")
                        break
                    }
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: e.toString())
            } finally {
                running = false
                track = null
                try {
                    t?.pause(); t?.flush(); t?.stop()
                } catch (_: Exception) {
                }
                t?.release()
            }
        }, "rb-audio-signal").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        thread?.join(1500)
        thread = null
    }
}

/** Plays a mono or stereo PCM buffer once on its own thread (microphone playback). */
internal class PcmPlayer {
    @Volatile private var running = false
    @Volatile private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile var progress = -1f
        private set

    val isRunning get() = running

    fun play(pcm: ShortArray, rate: Int, channels: Int, device: AudioDeviceInfo?, voice: Boolean, onDone: (String?) -> Unit) {
        stop()
        running = true
        progress = 0f
        thread = Thread({
            var t: AudioTrack? = null
            var err: String? = null
            try {
                t = buildTrack(rate, channels, voice)
                device?.let { t.setPreferredDevice(it) }
                track = t
                t.play()
                var off = 0
                val chunk = rate * channels / 50
                while (running && off < pcm.size) {
                    val n = minOf(chunk, pcm.size - off)
                    val w = t.write(pcm, off, n)
                    if (w < 0) { err = "AudioTrack.write() = $w"; break }
                    off += w
                    progress = off.toFloat() / pcm.size
                }
                // Let the tail drain before stopping.
                val total = pcm.size / channels
                val until = SystemClock.elapsedRealtime() + 1500
                while (running && t.head() < total && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            } catch (e: Exception) {
                if (running) err = e.message ?: e.toString()
            } finally {
                running = false
                track = null
                progress = -1f
                try {
                    t?.stop()
                } catch (_: Exception) {
                }
                t?.release()
                onDone(err)
            }
        }, "rb-audio-pcm").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        thread?.join(1500)
        thread = null
    }
}

/** Outcome of [probePlayback]. */
internal data class PlaybackProbe(
    val error: String? = null,
    val rate: Int = PLAY_RATE,
    val target: Long = 0,
    val written: Long = 0,
    val head: Long = 0,
    val firstAdvanceMs: Long? = null,
    val reachedMs: Long? = null,
    /** Frames consumed per second between the first advance and the target. */
    val consumeRate: Double? = null,
    val tsValid: Boolean = false,
    val tsFirst: Long? = null,
    val tsLast: Long? = null,
    val routed: AudioDeviceInfo? = null,
    val underruns: Int? = null,
    val elapsedMs: Long = 0,
) {
    val reached get() = head >= target && target > 0
}

/**
 * Plays [ms] of a quiet 1 kHz tone with non-blocking writes and watches the playback head and
 * AudioTimestamp: a HAL whose write blocks (dead ADSP / ALSA path) stops the mixer thread and
 * the head freezes after at most one period.
 */
internal suspend fun probePlayback(device: AudioDeviceInfo?, voice: Boolean, ms: Int = 500, deadlineMs: Long = 3000): PlaybackProbe =
    withContext(Dispatchers.IO) {
        val track = try {
            buildTrack(PLAY_RATE, 2, voice, minMs = 200)
        } catch (e: Exception) {
            return@withContext PlaybackProbe(error = e.message ?: e.toString())
        }
        val target = PLAY_RATE.toLong() * ms / 1000
        val gen = SignalGen(PLAY_RATE).apply { signal = Signal.TONE_1K }
        val frames = PLAY_RATE / 50
        val buf = ShortArray(frames * 2)
        var pendingOff = buf.size
        var written = 0L
        var firstAt: Long? = null
        var firstHead = 0L
        var reachedAt: Long? = null
        var head = 0L
        val ts = AudioTimestamp()
        var tsFirst: Long? = null
        var tsLast: Long? = null
        val t0 = SystemClock.elapsedRealtime()
        try {
            device?.let { track.setPreferredDevice(it) }
            track.play()
            var now = t0
            while (now - t0 < deadlineMs) {
                ensureActive()
                // Keep the buffer topped up without ever blocking.
                repeat(4) {
                    if (pendingOff >= buf.size) {
                        gen.fillStereo(buf, frames, Channel.BOTH, 0.04f)
                        pendingOff = 0
                    }
                    val w = track.write(buf, pendingOff, buf.size - pendingOff, AudioTrack.WRITE_NON_BLOCKING)
                    if (w > 0) {
                        pendingOff += w
                        written += w / 2
                    }
                    if (w <= 0 || pendingOff < buf.size) return@repeat
                }
                now = SystemClock.elapsedRealtime()
                head = track.head()
                if (head > 0 && firstAt == null) {
                    firstAt = now; firstHead = head
                }
                if (track.getTimestamp(ts)) {
                    if (tsFirst == null) tsFirst = ts.framePosition
                    tsLast = ts.framePosition
                }
                if (head >= target) {
                    reachedAt = now
                    break
                }
                delay(10)
            }
            // Some HALs only report a presentation position after a few periods.
            if (reachedAt != null && (tsFirst == null || tsFirst == tsLast)) {
                val until = SystemClock.elapsedRealtime() + 300
                while (SystemClock.elapsedRealtime() < until) {
                    track.write(buf, 0, buf.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (track.getTimestamp(ts)) {
                        if (tsFirst == null) tsFirst = ts.framePosition
                        tsLast = ts.framePosition
                        if (tsLast != tsFirst) break
                    }
                    delay(20)
                }
            }
            val fa = firstAt
            val rate = if (fa != null && reachedAt != null && reachedAt - fa >= 100) (head - firstHead) * 1000.0 / (reachedAt - fa) else null
            PlaybackProbe(
                rate = PLAY_RATE, target = target, written = written, head = head,
                firstAdvanceMs = fa?.minus(t0), reachedMs = reachedAt?.minus(t0), consumeRate = rate,
                tsValid = tsFirst != null, tsFirst = tsFirst, tsLast = tsLast,
                routed = track.routedDevice, underruns = track.underrunCount,
                elapsedMs = SystemClock.elapsedRealtime() - t0,
            )
        } finally {
            withContext(NonCancellable) {
                try {
                    track.setVolume(0f)
                    delay(30)
                    track.pause(); track.flush(); track.stop()
                } catch (_: Exception) {
                }
                track.release()
            }
        }
    }
