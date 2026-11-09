package org.rombench.areas.buttons

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.rombench.R
import org.rombench.core.KeyBus
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.Hint
import org.rombench.ui.components.SectionCard
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono

@Composable
internal fun ButtonsContent() {
    KeyTesterCard()
    VibrationCard()
    LedCard()
}

/** One key event as shown in the log. [scan] is -1 for keys seen indirectly (screen off, back gesture). */
private data class KeyLog(val code: Int, val scan: Int, val device: String, val down: Boolean, val repeat: Int, val long: Boolean, val via: String? = null) {
    fun line(): String = buildString {
        append(if (down) "DOWN " else "UP   ")
        append(keyName(code))
        if (scan >= 0) append("  scan $scan")
        if (repeat > 0) append("  repeat $repeat")
        if (long) append("  LONG")
        append("  · ").append(via ?: device)
    }

    companion object {
        fun of(e: KeyEvent) = KeyLog(
            e.keyCode, e.scanCode,
            runCatching { InputDevice.getDevice(e.deviceId)?.name }.getOrNull() ?: "device ${e.deviceId}",
            e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.isLongPress,
        )
    }
}

private fun recordKey(k: KeyLog) {
    val label = keyName(k.code)
    val summary = when {
        k.code == KeyEvent.KEYCODE_POWER && k.scan < 0 -> txt(R.string.buttons_s_key_power_screen)
        k.scan < 0 -> txt(R.string.buttons_s_key_gesture)
        else -> txt(R.string.buttons_s_key_seen, k.scan, k.device)
    }
    ResultStore.put(
        ButtonsArea.result(
            keyResultName(k.code), txt(R.string.buttons_t_key, label), Status.PASS, summary, null,
            listOf(d("Key code", "${k.code} ($label)"), d("Scan code", if (k.scan >= 0) k.scan else null), d("Device", k.via ?: k.device)),
            Kind.GUIDED,
        ),
    )
}

@Composable
private fun KeyTesterCard() {
    val ctx = LocalContext.current
    val expected = remember { checklistKeys() }
    var active by remember { mutableStateOf(false) }
    val seen = remember { mutableStateMapOf<Int, KeyLog>() }
    val log = remember { mutableStateListOf<KeyLog>() }
    val screenOff = stringResource(R.string.buttons_via_screen_off)
    val backGesture = stringResource(R.string.buttons_via_back_gesture)

    fun onKey(k: KeyLog) {
        log.add(0, k)
        while (log.size > 14) log.removeAt(log.lastIndex)
        if (k.code !in seen) {
            seen[k.code] = k
            recordKey(k)
        }
    }
    val onKeyState by rememberUpdatedState(::onKey)

    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        KeyBus.capture = true
        // POWER never reaches apps: the screen turning off is the proof that it works.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) {
                    onKeyState(KeyLog(KeyEvent.KEYCODE_POWER, -1, "", true, 0, false, screenOff))
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            KeyBus.capture = false
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }
    LaunchedEffect(active) {
        if (active) KeyBus.events.collect { e -> onKeyState(KeyLog.of(e)) }
    }
    // While capturing, BACK is logged instead of leaving the screen (gesture navigation sends no key).
    BackHandler(enabled = active) {
        onKeyState(KeyLog(KeyEvent.KEYCODE_BACK, -1, "", false, 0, false, backGesture))
    }

    SectionCard(
        stringResource(R.string.buttons_keys_title),
        subtitle = stringResource(R.string.buttons_keys_sub),
        icon = Icons.Outlined.Keyboard,
    ) {
        Hint(stringResource(if (active) R.string.buttons_keys_active else R.string.buttons_keys_how))
        FilledTonalButton(onClick = { active = !active }) {
            Text(stringResource(if (active) R.string.buttons_btn_stop_keys else R.string.buttons_btn_start_keys))
        }
        ButtonRow {
            expected.forEach { KeyPill(it, seen.containsKey(it)) }
            seen.keys.filter { it !in expected }.sorted().forEach { KeyPill(it, true) }
        }
        if (KeyEvent.KEYCODE_CAMERA in expected || KeyEvent.KEYCODE_FOCUS in expected) {
            Hint(stringResource(R.string.buttons_camera_hint))
        }
        if (log.isNotEmpty()) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                log.forEach { Text(it.line(), style = Mono.copy(fontSize = 11.sp), softWrap = false) }
            }
        }
    }
}

@Composable
private fun KeyPill(code: Int, ok: Boolean) {
    val sc = LocalStatusColors.current
    Surface(shape = RoundedCornerShape(50), color = if (ok) sc.passContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                tint = if (ok) sc.pass else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(keyLabel(code)?.let { stringResource(it) } ?: keyName(code), style = MaterialTheme.typography.labelLarge)
        }
    }
}
