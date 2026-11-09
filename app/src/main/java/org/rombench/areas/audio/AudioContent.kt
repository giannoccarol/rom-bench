package org.rombench.areas.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SettingsInputComponent
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.rombench.R
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.StatusChip
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors
import kotlin.math.roundToInt

private data class JackState(val plugged: Boolean, val name: String?, val mic: Boolean)

@Composable
internal fun AudioContent() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) } ?: return
    var outputs by remember { mutableStateOf(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()) }
    var jack by remember { mutableStateOf<JackState?>(null) }
    var jackEvents by remember { mutableStateOf(listOf<String>()) }

    DisposableEffect(am) {
        var initial = true
        fun jackEvent(plugged: Boolean, what: String) {
            val ev = (if (plugged) "plug " else "unplug ") + what
            jackEvents = (listOf(ev) + jackEvents).take(6)
            val plug = jackEvents.any { it.startsWith("plug") }
            val unplug = jackEvents.any { it.startsWith("unplug") }
            val summary = when {
                plug && unplug -> txt(R.string.audio_s_jack_both)
                plug -> txt(R.string.audio_s_jack_plug)
                else -> txt(R.string.audio_s_jack_unplug)
            }
            ResultStore.put(
                AudioArea.result(
                    "jack_detect", txt(R.string.audio_t_jack), Status.PASS, summary,
                    details = jackEvents.mapIndexed { i, e -> d("Event ${jackEvents.size - i}", e) },
                ),
            )
        }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
                if (!initial) added.filter { it.isSink && AudioDevices.isJack(it.type) }
                    .forEach { jackEvent(true, AudioDevices.typeName(it.type)) }
                initial = false
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
                removed.filter { it.isSink && AudioDevices.isJack(it.type) }.forEach { jackEvent(false, AudioDevices.typeName(it.type)) }
            }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val state = i.getIntExtra("state", -1)
                jack = JackState(state == 1, i.getStringExtra("name"), i.getIntExtra("microphone", 0) == 1)
                if (!isInitialStickyBroadcast) jackEvent(state == 1, "ACTION_HEADSET_PLUG")
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(AudioManager.ACTION_HEADSET_PLUG), ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            am.unregisterAudioDeviceCallback(cb)
            try {
                ctx.unregisterReceiver(rx)
            } catch (_: Exception) {
            }
        }
    }

    PlayerSection(am, outputs)
    JackSection(jack, jackEvents)
    OutputsSection(outputs)
    GuidedSection()
}

@Composable
private fun PlayerSection(am: AudioManager, outputs: List<AudioDeviceInfo>) {
    val ctx = LocalContext.current
    val player = remember { SignalPlayer() }
    val comm = remember { CommMode(am) }
    var targetId by remember { mutableStateOf<Int?>(null) }
    var signal by remember { mutableStateOf(Signal.TONE_440) }
    var channel by remember { mutableStateOf(Channel.BOTH) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val playable = outputs.filter { it.isSink && AudioDevices.isPlayable(it.type) }.distinctBy { it.type to it.address }
    val target = playable.firstOrNull { it.id == targetId }
    val voice = target?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            comm.exit()
        }
    }

    fun start() {
        error = null
        if (voice) comm.enter(AudioDevices.commEarpiece(am) ?: target) else comm.exit()
        player.signal = signal
        player.channel = channel
        player.start(target, voice) { msg ->
            error = msg
            playing = false
            comm.exit()
        }
        playing = true
    }

    fun stop() {
        player.stop()
        comm.exit()
        playing = false
    }

    LaunchedEffect(targetId) { if (playing) start() }
    LaunchedEffect(signal, channel) {
        player.signal = signal
        player.channel = channel
    }
    LaunchedEffect(target == null, targetId) { if (target == null && targetId != null) targetId = null }

    SectionCard(
        stringResource(R.string.audio_player_title), subtitle = stringResource(R.string.audio_player_sub),
        icon = Icons.Outlined.GraphicEq,
    ) {
        SubHeader(stringResource(R.string.audio_output))
        ButtonRow {
            FilterChip(selected = targetId == null, onClick = { targetId = null }, label = { Text(stringResource(R.string.audio_default_route)) })
            playable.forEach { dev ->
                FilterChip(selected = targetId == dev.id, onClick = { targetId = dev.id }, label = { Text(AudioDevices.label(ctx, dev)) })
            }
        }
        SubHeader(stringResource(R.string.audio_signal))
        ButtonRow {
            Signal.entries.forEach { s ->
                FilterChip(selected = signal == s, onClick = { signal = s }, label = { Text(stringResource(s.label)) })
            }
        }
        SubHeader(stringResource(R.string.audio_channel))
        ButtonRow {
            Channel.entries.forEach { c ->
                FilterChip(selected = channel == c, onClick = { channel = c }, label = { Text(stringResource(c.label)) })
            }
        }
        Button(onClick = { if (playing) stop() else start() }) {
            Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (playing) R.string.audio_stop else R.string.audio_play))
        }
        if (voice) Hint(stringResource(R.string.audio_earpiece_hint))
        error?.let {
            Text(stringResource(R.string.audio_play_error, it), color = LocalStatusColors.current.of(Status.FAIL), style = MaterialTheme.typography.bodySmall)
        }

        val live by rememberPolled(300, playing) {
            player.track?.let { t -> Triple(t.routedDevice, t.head(), t.underrunCount) }
        }
        val l = live
        KeyValue(
            stringResource(R.string.audio_routed_to),
            if (!playing) "—" else l?.first?.let { "${AudioDevices.label(ctx, it)} (${AudioDevices.typeName(it.type)})" } ?: "…",
        )
        if (playing && l != null) {
            KeyValue(stringResource(R.string.audio_played), "${l.second * 1000 / PLAY_RATE} ms")
            KeyValue(stringResource(R.string.audio_underruns), l.third.toString())
            if (target != null && l.first != null && l.first?.type != target.type) {
                Text(
                    stringResource(R.string.audio_routed_warn),
                    color = LocalStatusColors.current.of(Status.WARN), style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        VolumeSlider(am, if (voice) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC)
    }
}

@Composable
private fun VolumeSlider(am: AudioManager, stream: Int) {
    val min = remember(stream) { am.getStreamMinVolume(stream) }
    val max = remember(stream) { am.getStreamMaxVolume(stream) }
    var vol by remember(stream) { mutableFloatStateOf(am.getStreamVolume(stream).toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val polled by rememberPolled(800, stream) { am.getStreamVolume(stream) }
    LaunchedEffect(polled) { polled?.let { if (!dragging) vol = it.toFloat() } }
    var failed by remember { mutableStateOf(false) }
    if (max <= min) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (stream == AudioManager.STREAM_VOICE_CALL) R.string.audio_volume_call else R.string.audio_volume_music,
                    vol.roundToInt(), max,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = vol.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = {
                dragging = true
                vol = it
                failed = try {
                    am.setStreamVolume(stream, it.roundToInt(), 0); false
                } catch (e: Exception) {
                    true
                }
            },
            onValueChangeFinished = { dragging = false },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
        )
        if (failed) Hint(stringResource(R.string.audio_volume_failed))
    }
}

@Composable
private fun JackSection(jack: JackState?, events: List<String>) {
    SectionCard(
        stringResource(R.string.audio_jack_title), subtitle = stringResource(R.string.audio_jack_sub),
        icon = Icons.Outlined.Headphones,
        actions = { if (events.isNotEmpty()) StatusChip(Status.PASS) },
    ) {
        KeyValue(
            stringResource(R.string.audio_jack_state),
            when {
                jack == null -> stringResource(R.string.audio_jack_unknown)
                jack.plugged -> stringResource(if (jack.mic) R.string.audio_jack_plugged_mic else R.string.audio_jack_plugged)
                else -> stringResource(R.string.audio_jack_unplugged)
            },
        )
        jack?.name?.takeIf { it.isNotBlank() }?.let { KeyValue("name", it) }
        if (events.isEmpty()) Hint(stringResource(R.string.audio_jack_hint))
        events.forEach { KeyValue(stringResource(R.string.audio_jack_event), it) }
    }
}

@Composable
private fun OutputsSection(outputs: List<AudioDeviceInfo>) {
    val ctx = LocalContext.current
    SectionCard(
        stringResource(R.string.audio_outputs_title),
        subtitle = stringResource(R.string.audio_outputs_sub, outputs.size),
        icon = Icons.Outlined.SettingsInputComponent,
    ) {
        if (outputs.isEmpty()) Hint(stringResource(R.string.audio_s_no_outputs))
        outputs.forEach { dev ->
            Column(Modifier.fillMaxWidth()) {
                Text(AudioDevices.label(ctx, dev), style = MaterialTheme.typography.titleSmall)
                KeyValue("#${dev.id}", AudioDevices.typeName(dev.type))
                KeyValue("formats", AudioDevices.formats(dev))
            }
        }
    }
}

@Composable
private fun GuidedSection() {
    SectionCard(
        stringResource(R.string.audio_guided_title), subtitle = stringResource(R.string.audio_guided_sub),
        icon = Icons.Outlined.TouchApp,
    ) {
        GuidedCheck(AudioArea, "guided_speaker", txt(R.string.audio_g_speaker_t), stringResource(R.string.audio_g_speaker))
        GuidedCheck(AudioArea, "guided_earpiece", txt(R.string.audio_g_earpiece_t), stringResource(R.string.audio_g_earpiece))
        GuidedCheck(AudioArea, "guided_headset_left", txt(R.string.audio_g_left_t), stringResource(R.string.audio_g_left))
        GuidedCheck(AudioArea, "guided_headset_right", txt(R.string.audio_g_right_t), stringResource(R.string.audio_g_right))
        GuidedCheck(AudioArea, "guided_bluetooth", txt(R.string.audio_g_bt_t), stringResource(R.string.audio_g_bt))
        GuidedCheck(AudioArea, "guided_max_volume", txt(R.string.audio_g_max_t), stringResource(R.string.audio_g_max))
    }
}
