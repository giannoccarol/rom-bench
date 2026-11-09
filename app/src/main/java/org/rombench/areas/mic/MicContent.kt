package org.rombench.areas.mic

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.rombench.R
import org.rombench.areas.audio.AudioDevices
import org.rombench.areas.audio.CommMode
import org.rombench.areas.audio.PcmPlayer
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmt2
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.LevelBar
import org.rombench.ui.components.LineChart
import org.rombench.ui.components.Metric
import org.rombench.ui.components.PermissionGate
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors

@Composable
internal fun MicContent() {
    PermissionGate(listOf(Perms.MIC)) { MicScreen() }
    EffectsSection()
}

@Composable
private fun MicScreen() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) } ?: return
    val engine = remember { MicEngine() }
    val player = remember { PcmPlayer() }
    val comm = remember { CommMode(am) }
    var inputs by remember { mutableStateOf(am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()) }
    var source by remember { mutableStateOf(SOURCES.first()) }
    var deviceId by remember { mutableStateOf<Int?>(null) }
    var stereo by remember { mutableStateOf(false) }
    val fxAvail = remember { effectsAvailable() }
    var ns by remember { mutableStateOf(false) }
    var agc by remember { mutableStateOf(false) }
    var aec by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(5) }
    var playing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(am) {
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        onDispose {
            am.unregisterAudioDeviceCallback(cb)
            engine.stop()
            player.stop()
            comm.exit()
        }
    }

    val selectable = inputs.filter { it.isSource && it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX && it.type != AudioDevices.TYPE_ECHO_REFERENCE && it.type != AudioDeviceInfo.TYPE_FM_TUNER }
    val device = selectable.firstOrNull { it.id == deviceId }
    LaunchedEffect(device == null, deviceId) { if (device == null && deviceId != null) deviceId = null }
    val fx = Effects(ns && fxAvail.ns, agc && fxAvail.agc, aec && fxAvail.aec)

    fun startLive(rec: Int?) {
        player.stop()
        engine.recorded = if (rec != null) null else engine.recorded
        engine.start(source, device, stereo, fx, rec)
    }
    // Restart the live meter when the selection changes.
    LaunchedEffect(source, deviceId, stereo, ns, agc, aec) {
        if (engine.running && engine.recProgress < 0) startLive(null)
    }

    fun play(earpiece: Boolean) {
        val r = engine.recorded ?: return
        engine.stop()
        playError = null
        val out = if (earpiece) AudioDevices.commEarpiece(am) else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (earpiece) comm.enter(out) else comm.exit()
        playing = true
        player.play(r.pcm, r.rate, r.channels, out, earpiece) { err ->
            playError = err
            playing = false
            comm.exit()
        }
    }

    fun stopPlay() {
        player.stop()
        comm.exit()
        playing = false
    }

    SectionCard(stringResource(R.string.mic_select_title), subtitle = stringResource(R.string.mic_select_sub), icon = Icons.Outlined.Tune) {
        SubHeader(stringResource(R.string.mic_source))
        ButtonRow {
            SOURCES.forEach { s ->
                FilterChip(selected = source == s, onClick = { source = s; if (!s.stereo) stereo = false }, label = { Text(s.name) })
            }
        }
        SubHeader(stringResource(R.string.mic_device))
        ButtonRow {
            FilterChip(selected = deviceId == null, onClick = { deviceId = null }, label = { Text(stringResource(R.string.mic_default)) })
            selectable.forEach { dev ->
                FilterChip(selected = deviceId == dev.id, onClick = { deviceId = dev.id }, label = { Text(AudioDevices.label(ctx, dev)) })
            }
        }
        SwitchRow(stringResource(R.string.mic_stereo), stereo, enabled = true) { stereo = it }
        SwitchRow(stringResource(R.string.mic_fx_ns), ns, enabled = fxAvail.ns) { ns = it }
        SwitchRow(stringResource(R.string.mic_fx_agc), agc, enabled = fxAvail.agc) { agc = it }
        SwitchRow(stringResource(R.string.mic_fx_aec), aec, enabled = fxAvail.aec) { aec = it }
    }

    SectionCard(stringResource(R.string.mic_live_title), subtitle = stringResource(R.string.mic_live_sub), icon = Icons.Outlined.GraphicEq) {
        val sc = LocalStatusColors.current
        val live = engine.running
        Button(onClick = { if (live) engine.stop() else startLive(null) }, enabled = !playing) {
            Icon(if (live) Icons.Outlined.Stop else Icons.Outlined.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (live) R.string.mic_stop else R.string.mic_listen))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(if (live) fmt1(engine.rmsDb.toDouble()) else "—", stringResource(R.string.mic_rms), Modifier.weight(1f))
            Metric(if (live) fmt1(engine.peakDb.toDouble()) else "—", stringResource(R.string.mic_peak), Modifier.weight(1f))
        }
        val rmsFrac = ((engine.rmsDb + 90f) / 90f).coerceIn(0f, 1f)
        val peakFrac = ((engine.peakDb + 90f) / 90f).coerceIn(0f, 1f)
        LevelBar(if (live) rmsFrac else 0f, color = sc.of(Status.PASS))
        LevelBar(if (live) peakFrac else 0f, color = if (engine.peakDb > -1f) sc.of(Status.FAIL) else sc.of(Status.WARN), height = 6.dp)
        LineChart(engine.levels, range = -100f..0f, labels = listOf("RMS", "Peak"), unit = " dB", height = 110.dp)
        LineChart(engine.wave, range = -1f..1f, height = 90.dp)
        KeyValue(stringResource(R.string.mic_routed), engine.routed?.let { "${AudioDevices.label(ctx, it)} (${AudioDevices.typeName(it.type)})" } ?: "—")
        engine.applied?.let { KeyValue(stringResource(R.string.mic_fx_applied), it) }
        if (live && engine.silentMs >= 1000) {
            Text(stringResource(R.string.mic_live_zero, engine.silentMs / 1000), color = sc.of(Status.FAIL), style = MaterialTheme.typography.bodySmall)
        }
        engine.failure?.let { Text(stringResource(R.string.mic_error, it), color = sc.of(Status.FAIL), style = MaterialTheme.typography.bodySmall) }
    }

    SectionCard(stringResource(R.string.mic_rec_title), subtitle = stringResource(R.string.mic_rec_sub), icon = Icons.Outlined.Hearing) {
        val sc = LocalStatusColors.current
        ButtonRow {
            listOf(3, 5, 10).forEach { s ->
                FilterChip(selected = seconds == s, onClick = { seconds = s }, label = { Text(stringResource(R.string.mic_seconds, s)) })
            }
        }
        val recording = engine.running && engine.recProgress >= 0f
        ButtonRow {
            Button(onClick = { if (recording) engine.stop() else startLive(seconds) }, enabled = !playing) {
                Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.FiberManualRecord, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (recording) R.string.mic_stop else R.string.mic_record))
            }
        }
        if (recording) LinearProgressIndicator(progress = { engine.recProgress }, modifier = Modifier.fillMaxWidth())
        val rec = engine.recorded
        if (rec != null) {
            KeyValue(stringResource(R.string.mic_rec_of), "${rec.source} · ${rec.device?.let { AudioDevices.label(ctx, it) } ?: stringResource(R.string.mic_default)}")
            KeyValue("RMS / Peak", "${fmt1(rec.stats.rmsDb)} / ${fmt1(rec.stats.peakDb)} dBFS")
            KeyValue("DC / Clipped", "${fmt2(rec.stats.dc * 100)} % / ${fmt2(rec.stats.clipFrac * 100)} %")
            KeyValue("Format", "${rec.rate} Hz · ${rec.channels} ch")
            if (rec.stats.allZero) Text(stringResource(R.string.mic_s_zero), color = sc.of(Status.FAIL), style = MaterialTheme.typography.bodySmall)
            if (rec.stats.lrIdentical == true) Hint(stringResource(R.string.mic_r_duplicated))
            ButtonRow {
                FilledTonalButton(onClick = { play(false) }, enabled = !playing) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mic_play_speaker))
                }
                FilledTonalButton(onClick = { play(true) }, enabled = !playing) {
                    Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.mic_play_earpiece))
                }
                if (playing) OutlinedButton(onClick = { stopPlay() }) { Text(stringResource(R.string.mic_stop)) }
            }
            val progress by rememberPolled(100, playing) { player.progress }
            if (playing) LinearProgressIndicator(progress = { (progress ?: 0f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            playError?.let { Text(stringResource(R.string.mic_error, it), color = sc.of(Status.FAIL), style = MaterialTheme.typography.bodySmall) }

            val slug = rec.device?.let { MicArea.slug(it, 0) } ?: "default"
            val label = rec.device?.let { AudioDevices.labelTxt(it) } ?: txt(R.string.mic_default)
            GuidedCheck(
                MicArea, "guided_$slug", txt(R.string.mic_g_title, label),
                stringResource(R.string.mic_g_question, label.resolve(ctx)),
                details = {
                    listOf(
                        d("Source", rec.source), d("Format", "${rec.rate} Hz · ${rec.channels} ch"),
                        d("RMS", "${fmt1(rec.stats.rmsDb)} dBFS"), d("Peak", "${fmt1(rec.stats.peakDb)} dBFS"),
                    )
                },
            )
        } else if (!recording) {
            Hint(stringResource(R.string.mic_rec_hint))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun EffectsSection() {
    val fx = remember { effectsAvailable() }
    val list = remember { effectDescriptors() }
    SectionCard(stringResource(R.string.mic_fx_title), subtitle = stringResource(R.string.mic_fx_sub, list.size), icon = Icons.Outlined.Tune) {
        KeyValue("NoiseSuppressor", stringResource(if (fx.ns) R.string.mic_available else R.string.mic_unavailable))
        KeyValue("AutomaticGainControl", stringResource(if (fx.agc) R.string.mic_available else R.string.mic_unavailable))
        KeyValue("AcousticEchoCanceler", stringResource(if (fx.aec) R.string.mic_available else R.string.mic_unavailable))
        list.take(24).forEach { KeyValue(it.name, it.implementor) }
    }
}
