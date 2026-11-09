package org.rombench.areas.mic

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MicrophoneInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.areas.audio.AudioDevices
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Detail
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmt2
import org.rombench.core.result
import org.rombench.core.skippedForPermission
import org.rombench.core.txt

object MicArea : Area {
    override val id = "mic"
    override val title = R.string.mic_title
    override val subtitle = R.string.mic_subtitle
    override val icon: ImageVector = Icons.Outlined.Mic
    override val group = AreaGroup.MEDIA
    override val permissions = listOf(Perms.MIC)
    override val autoSeconds = 14

    override suspend fun autoCheck(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        val am = ctx.getSystemService(AudioManager::class.java)
            ?: return@withContext listOf(result("inputs", txt(R.string.mic_t_inputs), Status.FAIL, txt(R.string.mic_s_no_service), txt(R.string.mic_r_no_service)))
        val out = mutableListOf<TestResult>()
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        out += inputsResult(ctx, am, inputs)
        out += microphonesResult(am, inputs)
        out += sourcesResult(am)
        out += effectsResult()

        val missing = Perms.missing(ctx, permissions)
        if (missing.isNotEmpty()) {
            out += skippedForPermission("record", txt(R.string.mic_t_record), missing)
            return@withContext out
        }
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        for (s in SOURCES) {
            val title = txt(R.string.mic_t_source, s.name)
            if (s.key == "unprocessed" && !unprocessed) {
                out += result("src.${s.key}", title, Status.SKIP, txt(R.string.mic_s_unprocessed_unsupported))
                continue
            }
            out += recResult("src.${s.key}", title, probeRecord(s, null), s, null)
        }
        val mic = SOURCES.first()
        devicesToProbe(inputs).forEach { (slug, dev) ->
            out += recResult("dev.$slug", txt(R.string.mic_t_device, AudioDevices.labelTxt(dev)), probeRecord(mic, dev), mic, dev)
        }
        out
    }

    @Composable
    override fun Content() = MicContent()

    // ---------------------------------------------------------------------------------------

    internal fun join(items: List<Txt>): Txt? = items.reduceOrNull { a, b -> txt(R.string.mic_join2, a, b) }

    /** Stable slug of an input device for result keys: builtin_<address>, wired_headset, usb_headset. */
    internal fun slug(dev: AudioDeviceInfo, index: Int): String = when (dev.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_" + (dev.address.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { index.toString() })
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        else -> AudioDevices.typeName(dev.type).lowercase()
    }

    internal fun devicesToProbe(inputs: List<AudioDeviceInfo>): List<Pair<String, AudioDeviceInfo>> {
        val builtin = inputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val ext = inputs.filter {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        return (builtin.mapIndexed { i, d -> slug(d, i) to d } + ext.map { slug(it, 0) to it }).distinctBy { it.first }
    }

    private fun inputsResult(ctx: Context, am: AudioManager, inputs: List<AudioDeviceInfo>): TestResult {
        val details = inputs.map { dev ->
            d(
                "#${dev.id} ${AudioDevices.typeName(dev.type)}",
                listOfNotNull(dev.address.takeIf { it.isNotBlank() }?.let { "addr $it" }, AudioDevices.formats(dev)).joinToString(" · "),
                sensitive = AudioDevices.addressSensitive(dev.type) && dev.address.isNotBlank(),
            )
        } + listOf(d("isMicrophoneMute", am.isMicrophoneMute))
        val phone = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val builtin = inputs.count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val title = txt(R.string.mic_t_inputs)
        return when {
            inputs.isEmpty() -> result("inputs", title, Status.FAIL, txt(R.string.mic_s_no_inputs), txt(R.string.mic_r_no_inputs), details)
            builtin == 0 -> result(
                "inputs", title, if (phone) Status.WARN else Status.INFO, txt(R.string.mic_s_inputs, inputs.size, 0),
                txt(R.string.mic_r_no_builtin), details,
            )
            am.isMicrophoneMute -> result(
                "inputs", title, Status.WARN, txt(R.string.mic_s_inputs, inputs.size, builtin), txt(R.string.mic_r_muted), details,
            )
            else -> result("inputs", title, Status.PASS, txt(R.string.mic_s_inputs, inputs.size, builtin), details = details)
        }
    }

    private fun microphonesResult(am: AudioManager, inputs: List<AudioDeviceInfo>): TestResult {
        val title = txt(R.string.mic_t_microphones)
        val list = try {
            am.microphones
        } catch (e: Exception) {
            return result("microphones", title, Status.INFO, txt(R.string.mic_s_mics_error), txt(R.string.mic_r_mics_error), listOf(d("Error", e.toString())))
        }
        if (list.isEmpty()) return result(
            "microphones", title, Status.INFO, txt(R.string.mic_s_mics_none),
            if (inputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }) txt(R.string.mic_r_mics_none) else null,
        )
        val details = list.flatMap { m -> micDetails(m) }
        return result("microphones", title, Status.INFO, txt(R.string.mic_s_mics, list.size), details = details)
    }

    private fun micDetails(m: MicrophoneInfo): List<Detail> {
        val loc = when (m.location) {
            MicrophoneInfo.LOCATION_MAINBODY -> "MAINBODY"
            MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "MAINBODY_MOVABLE"
            MicrophoneInfo.LOCATION_PERIPHERAL -> "PERIPHERAL"
            else -> "UNKNOWN"
        }
        val dir = when (m.directionality) {
            MicrophoneInfo.DIRECTIONALITY_OMNI -> "OMNI"
            MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "BI_DIRECTIONAL"
            MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "CARDIOID"
            MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "HYPER_CARDIOID"
            MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "SUPER_CARDIOID"
            else -> "UNKNOWN"
        }
        val pos = m.position.takeIf { it != MicrophoneInfo.POSITION_UNKNOWN }?.let { "(${fmt2(it.x.toDouble())}, ${fmt2(it.y.toDouble())}, ${fmt2(it.z.toDouble())}) m" }
        val sens = m.sensitivity.takeIf { it != MicrophoneInfo.SENSITIVITY_UNKNOWN }?.let { "${fmt1(it.toDouble())} dBFS" }
        val spl = m.maxSpl.takeIf { it != MicrophoneInfo.SPL_UNKNOWN }?.let { "${fmt1(it.toDouble())} dB SPL" }
        return listOf(
            d("Mic ${m.id}", "${m.description} · ${AudioDevices.typeName(m.type)} · addr ${m.address}"),
            d("  location / group", "$loc · group ${m.group} #${m.indexInTheGroup}"),
            d("  position", pos),
            d("  directionality", dir),
            d("  sensitivity / max SPL", listOfNotNull(sens, spl).joinToString(" · ")),
        )
    }

    private fun sourcesResult(am: AudioManager): TestResult {
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        val mic = am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND)
        val details = listOf(
            d("PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED", unprocessed ?: "not set"),
            d("PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND", mic ?: "not set"),
            d("Sources tested", SOURCES.joinToString(", ") { it.name }),
        )
        return result(
            "sources", txt(R.string.mic_t_sources), Status.INFO,
            txt(if (unprocessed == "true") R.string.mic_s_sources_unproc else R.string.mic_s_sources), details = details,
        )
    }

    private fun effectsResult(): TestResult {
        val fx = effectsAvailable()
        val desc = effectDescriptors()
        val details = listOf(
            d("NoiseSuppressor", fx.ns),
            d("AutomaticGainControl", fx.agc),
            d("AcousticEchoCanceler", fx.aec),
        ) + desc.take(30).map { d(it.name, "${it.implementor} · ${it.connectMode}") }
        val names = listOfNotNull("NS".takeIf { fx.ns }, "AGC".takeIf { fx.agc }, "AEC".takeIf { fx.aec })
        return result(
            "effects", txt(R.string.mic_t_effects), Status.INFO,
            if (names.isEmpty()) txt(R.string.mic_s_effects_none, desc.size) else txt(R.string.mic_s_effects, names.joinToString(", "), desc.size),
            if (!fx.aec) txt(R.string.mic_r_no_aec) else null, details,
        )
    }

    private fun recResult(name: String, title: Txt, p: RecProbe, s: Source, wanted: AudioDeviceInfo?): TestResult {
        val failStatus = if (s.optional) Status.INFO else Status.FAIL
        val base = buildList {
            add(d("Source", s.name))
            add(d("Requested device", wanted?.let { "#${it.id} ${AudioDevices.typeName(it.type)} ${it.address}" } ?: "default"))
            add(d("Format", if (p.rate > 0) "${p.rate} Hz · ${p.channels} ch · PCM_16" else null))
            p.error?.let { add(d("Error", it)) }
        }
        when (p.stage) {
            RecStage.INIT -> return result(name, title, failStatus, txt(R.string.mic_s_init_failed), txt(R.string.mic_r_init_failed), base)
            RecStage.START -> return result(name, title, failStatus, txt(R.string.mic_s_start_failed), txt(R.string.mic_r_start_failed), base)
            RecStage.NO_DATA -> return result(
                name, title, failStatus, txt(R.string.mic_s_no_data, p.elapsedMs), txt(R.string.mic_r_no_data), base + d("Routed device", p.routed?.let { AudioDevices.typeName(it.type) }),
            )
            RecStage.OK -> Unit
        }
        val st = p.stats ?: return result(name, title, failStatus, txt(R.string.mic_s_no_data, p.elapsedMs), txt(R.string.mic_r_no_data), base)
        val routed = p.routed
        val details = base + buildList {
            add(d("Routed device", routed?.let { "#${it.id} ${AudioDevices.typeName(it.type)} ${it.address}" }))
            add(d("First data", p.firstDataMs?.let { "$it ms" }))
            add(d("Data rate", if (p.dataMs > 0) "${(p.frames * 1000L / p.dataMs)} frames/s" else null))
            add(d("Leading zeros", "${p.leadingZeroMs} ms"))
            add(d("RMS", "${fmt1(st.rmsDb)} dBFS"))
            add(d("Peak", "${fmt1(st.peakDb)} dBFS"))
            add(d("DC offset", "${fmt2(st.dc * 100)} %"))
            add(d("Exact zeros", "${fmt1(st.zeroFrac * 100)} %"))
            add(d("Clipped", "${fmt2(st.clipFrac * 100)} %"))
            if (st.channels == 2) {
                add(d("RMS L / R", "${fmt1(st.rmsDbL ?: -120.0)} / ${fmt1(st.rmsDbR ?: -120.0)} dBFS"))
                add(d("L/R correlation", st.lrCorr?.let { fmt2(it) }))
            }
            add(d("Client silenced", p.silenced))
        }
        val routedLabel = routed?.let { AudioDevices.labelTxt(it) } ?: txt(R.string.mic_default)
        val levels = txt(R.string.mic_s_levels, fmt1(st.rmsDb), fmt1(st.peakDb), routedLabel)
        if (st.allZero) {
            return if (p.silenced == true) result(name, title, Status.WARN, txt(R.string.mic_s_silenced), txt(R.string.mic_r_silenced), details)
            else result(name, title, failStatus, txt(R.string.mic_s_zero), txt(R.string.mic_r_zero), details)
        }
        if (st.stuck) return result(name, title, failStatus, txt(R.string.mic_s_stuck), txt(R.string.mic_r_stuck), details)

        val warn = mutableListOf<Txt>()
        if (st.rmsDb < -90) warn += txt(R.string.mic_r_near_silence, fmt1(st.rmsDb))
        if (st.clipFrac > 0.01) warn += txt(R.string.mic_r_clipping, fmt1(st.clipFrac * 100))
        if (kotlin.math.abs(st.dc) > 0.1) warn += txt(R.string.mic_r_dc, fmt1(st.dc * 100))
        if (st.rmsDb > -6) warn += txt(R.string.mic_r_loud, fmt1(st.rmsDb))
        if (p.dataMs >= 300 && p.frames * 1000.0 / p.dataMs < p.rate * 0.7) warn += txt(R.string.mic_r_slow, p.frames * 1000L / p.dataMs, p.rate)
        if (p.firstDataMs != null && p.firstDataMs > 1000) warn += txt(R.string.mic_r_slow_start, p.firstDataMs)
        if (wanted != null && routed != null && (routed.type != wanted.type || routed.address != wanted.address)) {
            warn += txt(R.string.mic_r_routed_elsewhere, routedLabel, AudioDevices.labelTxt(wanted))
        }
        if (s.stereo && st.channels == 1) warn += txt(R.string.mic_r_stereo_refused)
        if (warn.isNotEmpty()) return result(name, title, Status.WARN, levels, join(warn), details)
        if (st.lrIdentical == true) return result(name, title, Status.INFO, levels, txt(R.string.mic_r_duplicated), details)
        return result(name, title, Status.PASS, levels, details = details)
    }
}
