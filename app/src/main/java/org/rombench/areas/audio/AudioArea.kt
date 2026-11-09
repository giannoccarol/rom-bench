package org.rombench.areas.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Detail
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.result
import org.rombench.core.txt

object AudioArea : Area {
    override val id = "audio"
    override val title = R.string.audio_title
    override val subtitle = R.string.audio_subtitle
    override val icon: ImageVector = Icons.AutoMirrored.Outlined.VolumeUp
    override val group = AreaGroup.MEDIA
    override val autoSeconds = 10

    override suspend fun autoCheck(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        val am = ctx.getSystemService(AudioManager::class.java)
            ?: return@withContext listOf(
                result("outputs", txt(R.string.audio_t_outputs), Status.FAIL, txt(R.string.audio_s_no_service), txt(R.string.audio_r_no_service)),
            )
        val out = mutableListOf<TestResult>()
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        out += outputsResult(outputs)
        out += builtinResult(ctx, outputs)
        out += propertiesResult(ctx, am)
        out += configFilesResult()
        out += volumesResult(am)

        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        out += if (speaker == null) {
            result("speaker_play", txt(R.string.audio_t_speaker_play), Status.SKIP, txt(R.string.audio_s_no_device, txt(R.string.audio_dev_speaker)))
        } else {
            probeResult("speaker_play", txt(R.string.audio_t_speaker_play), probePlayback(speaker, voice = false), speaker, extra = emptyList())
        }

        val earpiece = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        out += earpieceCheck(am, earpiece)

        val headset = outputs.firstOrNull { AudioDevices.isJack(it.type) }
        out += if (headset == null) {
            result("headset_play", txt(R.string.audio_t_headset_play), Status.SKIP, txt(R.string.audio_s_no_headset))
        } else {
            probeResult("headset_play", txt(R.string.audio_t_headset_play), probePlayback(headset, voice = false), headset, extra = emptyList())
        }
        out
    }

    @Composable
    override fun Content() = AudioContent()

    // ---------------------------------------------------------------------------------------

    internal fun join(items: List<Txt>): Txt? = items.reduceOrNull { a, b -> txt(R.string.audio_join2, a, b) }

    private fun deviceDetails(list: List<AudioDeviceInfo>): List<Detail> = list.map { dev ->
        val addr = dev.address.takeIf { it.isNotBlank() }
        d(
            "#${dev.id} ${AudioDevices.typeName(dev.type)}",
            listOfNotNull(dev.productName?.toString(), addr?.let { "addr $it" }, AudioDevices.formats(dev)).joinToString(" · "),
            sensitive = addr != null && AudioDevices.addressSensitive(dev.type),
        )
    }

    private fun outputsResult(outputs: List<AudioDeviceInfo>): TestResult {
        if (outputs.isEmpty()) return result(
            "outputs", txt(R.string.audio_t_outputs), Status.FAIL, txt(R.string.audio_s_no_outputs), txt(R.string.audio_r_no_outputs),
        )
        val names = outputs.map { AudioDevices.typeName(it.type) }.distinct().joinToString(", ")
        return result(
            "outputs", txt(R.string.audio_t_outputs), Status.INFO,
            txt(R.string.audio_s_outputs, outputs.size, names), details = deviceDetails(outputs),
        )
    }

    private fun builtinResult(ctx: Context, outputs: List<AudioDeviceInfo>): TestResult {
        val pm = ctx.packageManager
        val phone = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val speaker = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val earpiece = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val details = listOf(
            d("FEATURE_TELEPHONY", phone),
            d("FEATURE_AUDIO_OUTPUT", pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)),
            d("BUILTIN_SPEAKER", speaker),
            d("BUILTIN_EARPIECE", earpiece),
        )
        val missing = buildList {
            if (!speaker) add(txt(R.string.audio_dev_speaker))
            if (!earpiece && phone) add(txt(R.string.audio_dev_earpiece))
        }
        return when {
            !speaker && !earpiece -> result(
                "builtin", txt(R.string.audio_t_builtin), if (outputs.isEmpty()) Status.FAIL else Status.WARN,
                txt(R.string.audio_s_builtin_none), txt(R.string.audio_r_builtin_missing), details,
            )
            missing.isNotEmpty() -> result(
                "builtin", txt(R.string.audio_t_builtin), Status.WARN,
                txt(R.string.audio_s_builtin_missing, join(missing)), txt(R.string.audio_r_builtin_missing), details,
            )
            else -> result("builtin", txt(R.string.audio_t_builtin), Status.PASS, txt(R.string.audio_s_builtin_ok), details = details)
        }
    }

    private fun propertiesResult(ctx: Context, am: AudioManager): TestResult {
        val pm = ctx.packageManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        val native = try {
            AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            null
        }
        val details = mutableListOf(
            d("PROPERTY_OUTPUT_SAMPLE_RATE", rate),
            d("PROPERTY_OUTPUT_FRAMES_PER_BUFFER", fpb),
            d("Buffer duration", if (rate != null && fpb != null && rate > 0) "${fmt1(fpb * 1000.0 / rate)} ms" else null),
            d("Native rate (STREAM_MUSIC)", native),
            d("FEATURE_AUDIO_LOW_LATENCY", pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)),
            d("FEATURE_AUDIO_PRO", pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)),
            d("PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND", am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)),
        )
        if (Build.VERSION.SDK_INT >= 32) {
            details += d("Spatializer level", am.spatializer.immersiveAudioLevel)
        }
        val props = Sys.allProps().filterKeys { k -> PROP_PREFIXES.any { k.startsWith(it) } || k in PROP_KEYS }
        props.entries.take(48).forEach { (k, v) -> details += d(k, v) }
        if (props.size > 48) details += d("…", "${props.size - 48} more")

        val issues = buildList {
            if (rate == null || rate <= 0) add(txt(R.string.audio_r_no_rate))
            else if (rate !in listOf(44100, 48000, 96000, 192000)) add(txt(R.string.audio_r_odd_rate, rate))
            if (fpb == null || fpb <= 0) add(txt(R.string.audio_r_no_fpb))
            else if (rate != null && rate > 0 && fpb * 1000.0 / rate > 100) add(txt(R.string.audio_r_big_fpb, fmt1(fpb * 1000.0 / rate)))
        }
        return result(
            "properties", txt(R.string.audio_t_properties), if (issues.isEmpty()) Status.PASS else Status.WARN,
            txt(R.string.audio_s_properties, rate ?: "—", fpb ?: "—", props.size), join(issues), details,
        )
    }

    private fun configFilesResult(): TestResult {
        val dirs = listOf("/vendor/etc", "/vendor/etc/audio", "/odm/etc", "/odm/etc/audio", "/system/etc")
        val found = mutableListOf<String>()
        var readable = 0
        for (dir in dirs) {
            val names = Sys.list(dir)
            if (names.isNotEmpty()) readable++
            names.filter { n -> CONFIG_PATTERNS.any { n.startsWith(it) } && (n.endsWith(".xml") || n.endsWith(".conf")) }
                .forEach { found += "$dir/$it" }
        }
        val details = found.take(40).map { d(it.substringAfterLast('/'), it.substringBeforeLast('/')) }
        val policy = found.any { it.substringAfterLast('/').startsWith("audio_policy_configuration") }
        val mixer = found.any { it.substringAfterLast('/').startsWith("mixer_paths") }
        return when {
            readable == 0 -> result(
                "config_files", txt(R.string.audio_t_config), Status.INFO, txt(R.string.audio_s_config_unreadable), txt(R.string.audio_r_config_unreadable),
            )
            !policy -> result(
                "config_files", txt(R.string.audio_t_config), Status.WARN, txt(R.string.audio_s_config, found.size),
                txt(R.string.audio_r_no_policy), details,
            )
            else -> result(
                "config_files", txt(R.string.audio_t_config), Status.INFO, txt(R.string.audio_s_config, found.size),
                if (!mixer) txt(R.string.audio_r_no_mixer) else null, details,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun volumesResult(am: AudioManager): TestResult {
        val streams = listOf(
            "VOICE_CALL" to AudioManager.STREAM_VOICE_CALL,
            "SYSTEM" to AudioManager.STREAM_SYSTEM,
            "RING" to AudioManager.STREAM_RING,
            "MUSIC" to AudioManager.STREAM_MUSIC,
            "ALARM" to AudioManager.STREAM_ALARM,
            "NOTIFICATION" to AudioManager.STREAM_NOTIFICATION,
            "ACCESSIBILITY" to AudioManager.STREAM_ACCESSIBILITY,
        )
        val details = mutableListOf<Detail>()
        var zeroMax = false
        streams.forEach { (name, s) ->
            val cur = am.getStreamVolume(s)
            val min = am.getStreamMinVolume(s)
            val max = am.getStreamMaxVolume(s)
            if (max <= 0 && s != AudioManager.STREAM_ACCESSIBILITY) zeroMax = true
            details += d("STREAM_$name", "$cur ($min..$max)${if (am.isStreamMute(s)) " muted" else ""}")
        }
        val mode = am.mode
        details += d("Mode", AudioDevices.modeName(mode))
        details += d("Ringer mode", when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
        })
        details += d("Music active", am.isMusicActive)
        details += d("Speakerphone", am.isSpeakerphoneOn)
        if (Build.VERSION.SDK_INT >= 31) details += d("Communication device", am.communicationDevice?.let { AudioDevices.typeName(it.type) })
        val musicCur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val issues = buildList {
            if (mode != AudioManager.MODE_NORMAL) add(txt(R.string.audio_r_mode_stuck, AudioDevices.modeName(mode)))
            if (zeroMax) add(txt(R.string.audio_r_zero_max))
        }
        val notes = if (musicCur == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC)) listOf(txt(R.string.audio_r_music_zero)) else emptyList()
        return result(
            "volumes", txt(R.string.audio_t_volumes), if (issues.isEmpty()) Status.PASS else Status.WARN,
            txt(R.string.audio_s_volumes, musicCur, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioDevices.modeName(mode)),
            join(issues + notes), details,
        )
    }

    private suspend fun earpieceCheck(am: AudioManager, earpiece: AudioDeviceInfo?): TestResult {
        val title = txt(R.string.audio_t_earpiece_play)
        if (earpiece == null) return result("earpiece_play", title, Status.SKIP, txt(R.string.audio_s_no_device, txt(R.string.audio_dev_earpiece)))
        val mode = am.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) {
            return result("earpiece_play", title, Status.SKIP, txt(R.string.audio_s_busy_mode, AudioDevices.modeName(mode)))
        }
        val comm = CommMode(am)
        try {
            val accepted = comm.enter(AudioDevices.commEarpiece(am) ?: earpiece)
            val modeNow = am.mode
            val probe = probePlayback(earpiece, voice = true)
            val extra = buildList {
                add(d("Mode during test", AudioDevices.modeName(modeNow)))
                add(d("Communication device accepted", accepted))
            }
            val warn = buildList {
                if (modeNow != AudioManager.MODE_IN_COMMUNICATION) add(txt(R.string.audio_r_mode_refused, AudioDevices.modeName(modeNow)))
                if (!accepted) add(txt(R.string.audio_r_comm_refused))
            }
            return probeResult("earpiece_play", title, probe, earpiece, extra, warn)
        } finally {
            comm.exit()
        }
    }

    /** Turns a [PlaybackProbe] into a result; [extraWarn] are added as WARN reasons when playback worked. */
    private fun probeResult(
        name: String, title: Txt, p: PlaybackProbe, wanted: AudioDeviceInfo, extra: List<Detail>, extraWarn: List<Txt> = emptyList(),
    ): TestResult {
        val wantedLabel = AudioDevices.labelTxt(wanted)
        if (p.error != null) return result(
            name, title, Status.FAIL, txt(R.string.audio_s_track_failed), txt(R.string.audio_r_track_failed),
            listOf(d("Error", p.error)) + extra,
        )
        val details = buildList {
            add(d("Requested device", "#${wanted.id} ${AudioDevices.typeName(wanted.type)}"))
            add(d("Routed device", p.routed?.let { "#${it.id} ${AudioDevices.typeName(it.type)}" }))
            add(d("Sample rate", p.rate))
            add(d("Frames written", p.written))
            add(d("Head position", "${p.head} / ${p.target}"))
            add(d("First advance", p.firstAdvanceMs?.let { "$it ms" }))
            add(d("Target reached", p.reachedMs?.let { "$it ms" }))
            add(d("Consume rate", p.consumeRate?.let { "${it.toInt()} frames/s" }))
            add(d("AudioTimestamp", if (p.tsValid) "${p.tsFirst} → ${p.tsLast}" else "not available"))
            add(d("Underruns", p.underruns))
            addAll(extra)
        }
        if (!p.reached) {
            val summary = if (p.firstAdvanceMs == null) txt(R.string.audio_s_not_consumed, p.elapsedMs)
            else txt(R.string.audio_s_stalled, p.head, p.elapsedMs)
            return result(name, title, Status.FAIL, summary, txt(R.string.audio_r_not_consumed), details)
        }
        val warn = mutableListOf<Txt>()
        val routed = p.routed
        if (routed == null) warn += txt(R.string.audio_r_no_routed)
        else if (routed.type != wanted.type) warn += txt(R.string.audio_r_routed_elsewhere, AudioDevices.labelTxt(routed), wantedLabel)
        p.consumeRate?.let { if (it < p.rate * 0.6) warn += txt(R.string.audio_r_slow, it.toInt(), p.rate) }
        if (!p.tsValid) warn += txt(R.string.audio_r_no_timestamp)
        else if (p.tsFirst == p.tsLast) warn += txt(R.string.audio_r_ts_stuck, p.tsLast)
        warn += extraWarn
        val summary = txt(
            R.string.audio_s_played, p.target * 1000 / p.rate,
            routed?.let { AudioDevices.labelTxt(it) } ?: txt(R.string.audio_unknown), p.firstAdvanceMs ?: 0,
        )
        return result(name, title, if (warn.isEmpty()) Status.PASS else Status.WARN, summary, join(warn), details)
    }

    private val PROP_PREFIXES = listOf(
        "ro.vendor.audio.", "ro.audio.", "vendor.audio.", "persist.vendor.audio.", "persist.audio.", "ro.af.", "af.",
        "audio.", "ro.hardware.audio", "ro.config.media_vol", "ro.config.vc_call_vol", "ro.vendor.audio_",
    )
    private val PROP_KEYS = setOf("ro.board.platform", "init.svc.audioserver", "init.svc.vendor.audio-hal", "init.svc.vendor.audio-hal-2-0")
    private val CONFIG_PATTERNS = listOf(
        "audio_policy", "mixer_paths", "audio_platform_info", "audio_effects", "audio_io_policy", "audio_output_policy",
        "sound_trigger", "audio_tuning", "audio_configs", "a2dp_", "usb_audio_policy", "r_submix_audio_policy",
    )
}
