package org.rombench.areas.buttons

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Perms
import org.rombench.core.d
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.PermissionCard
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.rememberPermissions
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors

private class LedColor(val key: String, @StringRes val label: Int, val argb: Int)

private val LED_COLORS = listOf(
    LedColor("red", R.string.buttons_led_red, 0xFFFF0000.toInt()),
    LedColor("green", R.string.buttons_led_green, 0xFF00FF00.toInt()),
    LedColor("blue", R.string.buttons_led_blue, 0xFF0000FF.toInt()),
    LedColor("white", R.string.buttons_led_white, 0xFFFFFFFF.toInt()),
)

private const val LED_NOTIFICATION_ID = 0x1ed
private const val LED_DELAY_S = 5
private const val WATCH_MS = 40_000L

/** Channel settings are fixed once created, so every colour has its own silent channel. */
private fun ensureChannel(ctx: Context, c: LedColor): String {
    val id = "buttons_led_${c.key}"
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return id
    if (nm.getNotificationChannel(id) == null) {
        nm.createNotificationChannel(
            NotificationChannel(id, ctx.getString(R.string.buttons_led_channel, ctx.getString(c.label)), NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableLights(true)
                lightColor = c.argb
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }
    return id
}

private fun postLed(ctx: Context, c: LedColor): Boolean {
    if (!Perms.granted(ctx, Perms.NOTIFICATIONS)) return false
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
    val n = NotificationCompat.Builder(ctx, ensureChannel(ctx, c))
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(ctx.getString(R.string.buttons_led_notif_title))
        .setContentText(ctx.getString(R.string.buttons_led_notif_text, ctx.getString(c.label)))
        .setLights(c.argb, 1000, 1000)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .build()
    return runCatching { nm.notify(LED_NOTIFICATION_ID, n) }.isSuccess
}

private fun cancelLed(ctx: Context) {
    runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(LED_NOTIFICATION_ID) }
}

@Composable
internal fun LedCard() {
    val ctx = LocalContext.current
    val leds by rememberPolled(1000) { readLeds() }
    val perm = rememberPermissions(listOf(Perms.NOTIFICATIONS))
    var colorIdx by rememberSaveable { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var posted by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf(false) }
    val changed = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { cancelLed(ctx) } }

    fun start() {
        job?.cancel()
        cancelLed(ctx)
        changed.clear()
        posted = false
        val color = LED_COLORS[colorIdx]
        job = scope.launch {
            for (s in LED_DELAY_S downTo 1) {
                countdown = s
                delay(1000)
            }
            countdown = 0
            val base = withContext(Dispatchers.IO) { readLeds() }.associateBy { it.name }
            posted = postLed(ctx, color)
            if (!posted) return@launch
            // The lights HAL writes brightness/trigger in sysfs: note which LED it drives.
            watching = true
            val until = System.currentTimeMillis() + WATCH_MS
            while (System.currentTimeMillis() < until) {
                val now = withContext(Dispatchers.IO) { readLeds() }
                now.forEach { l ->
                    val b = base[l.name]
                    val brighter = (l.brightness ?: 0) > (b?.brightness ?: 0)
                    val trigger = l.trigger != b?.trigger
                    if (brighter || trigger) changed[l.name] = "brightness ${l.brightness ?: "?"}/${l.max ?: "?"}" + (l.trigger?.let { " · trigger $it" } ?: "")
                }
                delay(250)
            }
            watching = false
        }
    }

    SectionCard(
        stringResource(R.string.buttons_led_title),
        subtitle = stringResource(R.string.buttons_led_sub),
        icon = Icons.Outlined.Lightbulb,
    ) {
        SubHeader(stringResource(R.string.buttons_led_sysfs))
        val list = leds.orEmpty()
        if (list.isEmpty()) Hint(stringResource(R.string.buttons_led_sysfs_none))
        else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.forEach { l ->
                KeyValue("${l.name} · ${l.kind}", "${l.brightness ?: "?"}/${l.max ?: "?"}" + (l.trigger?.let { " · $it" } ?: ""))
            }
        }

        SubHeader(stringResource(R.string.buttons_led_test))
        if (!perm.allGranted) {
            PermissionCard(perm)
            return@SectionCard
        }
        Hint(stringResource(R.string.buttons_led_how, LED_DELAY_S))
        ButtonRow {
            LED_COLORS.forEachIndexed { i, c ->
                FilterChip(
                    selected = colorIdx == i,
                    onClick = { colorIdx = i },
                    label = { Text(stringResource(c.label)) },
                    leadingIcon = { Canvas(Modifier.size(12.dp)) { drawCircle(Color(c.argb)); drawCircle(Color.Gray, style = androidx.compose.ui.graphics.drawscope.Stroke(1f)) } },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { start() }, enabled = countdown == 0) {
                Text(stringResource(R.string.buttons_led_post, LED_DELAY_S))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { job?.cancel(); countdown = 0; watching = false; posted = false; cancelLed(ctx) }) {
                Text(stringResource(R.string.buttons_led_remove))
            }
        }
        when {
            countdown > 0 -> Text(
                stringResource(R.string.buttons_led_countdown, countdown), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            posted -> Hint(stringResource(R.string.buttons_led_posted))
        }
        if (changed.isNotEmpty()) {
            Text(stringResource(R.string.buttons_led_changed), style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.pass)
            changed.forEach { (n, v) -> KeyValue(n, v) }
        } else if (posted && !watching) {
            Text(stringResource(R.string.buttons_led_unchanged), style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn)
        }
        GuidedCheck(
            ButtonsArea, "led", txt(R.string.buttons_t_led_guided), stringResource(R.string.buttons_led_question),
            details = {
                listOf(
                    d("Colour", LED_COLORS[colorIdx].key),
                    d("Kernel LEDs changed", changed.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "none" }),
                    d("Indicator LEDs", leds.orEmpty().filter { it.kind == "indicator" }.joinToString { it.name }.ifEmpty { "none" }),
                )
            },
        )
    }
}
