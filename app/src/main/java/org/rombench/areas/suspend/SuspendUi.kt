package org.rombench.areas.suspend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.rombench.R
import org.rombench.core.AlarmBus
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.text
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.StatusChip
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.Mono

@Composable
internal fun SuspendContent() {
    CountersCard()
    GuidedSuspendCard()
    WakelockCard()
    SourcesCard()
}

@Composable
private fun CountersCard() {
    val ctx = LocalContext.current
    data class Snap(val c: SleepInfo.Clocks, val wakeups: Long?, val resume: String?, val plugged: Int)
    val s by rememberPolled(1000) { Snap(SleepInfo.clocks(), SleepInfo.wakeupCount(), SleepInfo.lastResumeReason(), SleepInfo.plugged(ctx)) }
    SectionCard(
        stringResource(R.string.suspend_counters_title), icon = Icons.Outlined.Bedtime,
        subtitle = stringResource(R.string.suspend_counters_sub),
    ) {
        val snap = s ?: return@SectionCard
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(SleepInfo.pct(snap.c.fraction), stringResource(R.string.suspend_ui_deep_sleep), Modifier.weight(1f))
            Metric(SleepInfo.fmtDur(snap.c.asleep), stringResource(R.string.suspend_ui_asleep), Modifier.weight(1f))
            Metric(SleepInfo.fmtDur(snap.c.elapsed), stringResource(R.string.suspend_ui_since_boot), Modifier.weight(1f))
        }
        KeyValue("uptimeMillis", SleepInfo.fmtDur(snap.c.uptime))
        KeyValue("wakeup_count", snap.wakeups?.toString() ?: "unreadable")
        KeyValue("last_resume_reason", snap.resume ?: "—")
        KeyValue(stringResource(R.string.suspend_ui_power), SleepInfo.pluggedName(snap.plugged))
        if (snap.plugged != 0) Hint(stringResource(R.string.suspend_ui_usb_hint))
    }
}

// ------------------------------------------------------------------------------ guided test

@Composable
private fun GuidedSuspendCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val fired by AlarmBus.last.collectAsState()
    val run = remember(tick, fired) { SuspendTest.current(ctx) }
    // Evaluate as soon as the alarm fired or was clearly lost, also after a process death.
    LaunchedEffect(run, fired) {
        while (run != null) {
            if (SuspendTest.ready(ctx, run)) {
                SuspendTest.finish(ctx, run, early = false)
                tick++
                break
            }
            delay(5000)
        }
    }
    val now by rememberPolled(1000) { SleepInfo.clocks().elapsed }
    var minutes by rememberSaveable { mutableIntStateOf(5) }
    var error by remember { mutableStateOf<String?>(null) }
    val canExact = remember(tick) { SuspendTest.canScheduleExact(ctx) }
    val results by ResultStore.results.collectAsState()

    SectionCard(
        stringResource(R.string.suspend_test_title), icon = Icons.Outlined.Alarm,
        subtitle = stringResource(R.string.suspend_test_sub),
    ) {
        if (run == null) {
            Text(stringResource(R.string.suspend_test_duration), style = MaterialTheme.typography.labelLarge)
            ButtonRow {
                listOf(2, 5, 10).forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(stringResource(R.string.suspend_test_minutes, m)) })
                }
            }
            if (!canExact) {
                Hint(stringResource(R.string.suspend_exact_denied_reason))
                if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${ctx.packageName}".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                    }
                }) { Text(stringResource(R.string.suspend_test_grant_exact)) }
            }
            Button(onClick = {
                error = SuspendTest.start(ctx, minutes * 60_000L)
                tick++
            }, enabled = canExact) {
                Icon(Icons.Outlined.Timer, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.suspend_test_start))
            }
            error?.let { Hint(it) }
            listOf("suspend.deep_sleep_test", "suspend.alarm_wakeup").mapNotNull { results[it] }.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(r.status)
                    Spacer(Modifier.width(8.dp))
                    Text(r.summary.text(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        } else {
            val remaining = ((run.due - (now ?: run.startElapsed)) / 1000).coerceAtLeast(0)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.suspend_test_step1), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.suspend_test_step2), style = MaterialTheme.typography.bodyMedium)
                    Text(pluralStringResource(R.plurals.suspend_test_step3, (run.durationMs / 60_000).toInt(), (run.durationMs / 60_000).toInt()), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.suspend_test_step4), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Metric(
                if (remaining > 0) String.format(java.util.Locale.US, "%d:%02d", remaining / 60, remaining % 60) else stringResource(R.string.suspend_test_due),
                stringResource(R.string.suspend_test_remaining),
            )
            LinearProgressIndicator(
                progress = { (1f - remaining * 1000f / run.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (remaining == 0L) Hint(stringResource(R.string.suspend_test_waiting_alarm))
            ButtonRow {
                Button(onClick = {
                    SuspendTest.finish(ctx, run, early = (now ?: 0) < run.due)
                    tick++
                }) { Text(stringResource(R.string.suspend_test_evaluate)) }
                TextButton(onClick = { SuspendTest.cancel(ctx); tick++ }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

// ------------------------------------------------------------------------------ wakelock demo

private class WakelockDemo(ctx: Context) {
    private val app = ctx.applicationContext
    private val pm = app.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var wl: PowerManager.WakeLock? = null
    var held by mutableStateOf(false)
        private set
    var start by mutableStateOf(SleepInfo.Clocks(0, 0))
        private set
    private var screenOffSince = 0L
    var screenOffMs by mutableLongStateOf(0L)
        private set

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SleepInfo.clocks().elapsed
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (screenOffSince == 0L) screenOffSince = now
                Intent.ACTION_SCREEN_ON -> if (screenOffSince != 0L) { screenOffMs += now - screenOffSince; screenOffSince = 0 }
            }
        }
    }
    private val autoRelease = Runnable { release() }

    fun hold() {
        if (held || pm == null) return
        val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RomBench:wakelock_demo")
        w.acquire(LIMIT_MS + 60_000)
        wl = w
        start = SleepInfo.clocks()
        screenOffMs = 0
        screenOffSince = 0
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(app, screen, f, ContextCompat.RECEIVER_EXPORTED)
        main.postDelayed(autoRelease, LIMIT_MS)
        held = true
    }

    /** Releases the wakelock and stores the result (also called on dispose). */
    fun release() {
        if (!held) return
        main.removeCallbacks(autoRelease)
        val end = SleepInfo.clocks()
        try { app.unregisterReceiver(screen) } catch (e: Exception) { }
        if (screenOffSince != 0L) { screenOffMs += end.elapsed - screenOffSince; screenOffSince = 0 }
        try { wl?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        wl = null
        held = false
        record(end)
    }

    private fun record(end: SleepInfo.Clocks) {
        val el = end.elapsed - start.elapsed
        val up = end.uptime - start.uptime
        val ratio = if (el > 0) up.toDouble() / el else 1.0
        val det = listOf(
            d("held", SleepInfo.fmtDur(el)), d("uptime advanced", SleepInfo.fmtDur(up)),
            d("suspended while held", SleepInfo.fmtDur((el - up).coerceAtLeast(0))),
            d("screen off while held", SleepInfo.fmtDur(screenOffMs)),
            d("uptime/elapsed", SleepInfo.pct(ratio)),
        )
        val a = SuspendArea
        val t = txt(R.string.suspend_t_wakelock)
        val r = when {
            screenOffMs < 20_000 -> a.result("wakelock", t, Status.INFO, txt(R.string.suspend_wl_screen_on, SleepInfo.fmtDur(el)), txt(R.string.suspend_wl_screen_on_reason), det, Kind.GUIDED)
            ratio >= 0.95 -> a.result("wakelock", t, Status.PASS, txt(R.string.suspend_wl_ok, SleepInfo.fmtDur(screenOffMs)), null, det, Kind.GUIDED)
            else -> a.result("wakelock", t, Status.FAIL, txt(R.string.suspend_wl_fail, SleepInfo.fmtDur((el - up).coerceAtLeast(0))), txt(R.string.suspend_wl_fail_reason), det, Kind.GUIDED)
        }
        ResultStore.put(r)
    }

    companion object {
        const val LIMIT_MS = 10 * 60_000L
    }
}

@Composable
private fun WakelockCard() {
    val ctx = LocalContext.current
    val demo = remember { WakelockDemo(ctx) }
    DisposableEffect(demo) { onDispose { demo.release() } }
    val now by rememberPolled(1000) { SleepInfo.clocks() }
    SectionCard(
        stringResource(R.string.suspend_wl_title), icon = Icons.Outlined.Bolt,
        subtitle = stringResource(R.string.suspend_wl_sub),
    ) {
        Hint(pluralStringResource(R.plurals.suspend_wl_hint, (WakelockDemo.LIMIT_MS / 60_000).toInt(), (WakelockDemo.LIMIT_MS / 60_000).toInt()))
        val n = now
        if (demo.held && n != null) {
            val el = n.elapsed - demo.start.elapsed
            val up = n.uptime - demo.start.uptime
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(SleepInfo.fmtDur(el), stringResource(R.string.suspend_wl_held), Modifier.weight(1f))
                Metric(SleepInfo.fmtDur(up), stringResource(R.string.suspend_wl_uptime), Modifier.weight(1f))
                Metric(SleepInfo.fmtDur((el - up).coerceAtLeast(0)), stringResource(R.string.suspend_ui_asleep), Modifier.weight(1f))
            }
            KeyValue(stringResource(R.string.suspend_wl_screen_off), SleepInfo.fmtDur(demo.screenOffMs))
        }
        ButtonRow {
            if (!demo.held) Button(onClick = { demo.hold() }) {
                Icon(Icons.Outlined.Lock, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.suspend_wl_hold))
            } else Button(onClick = { demo.release() }) {
                Icon(Icons.Outlined.LockOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.suspend_wl_release))
            }
        }
    }
}

// ------------------------------------------------------------------------------ wakeup sources

@Composable
private fun SourcesCard() {
    val src by rememberPolled(5000) { SleepInfo.sources() ?: SleepInfo.Sources("", emptyList()) }
    SectionCard(
        stringResource(R.string.suspend_t_sources), icon = Icons.Outlined.Timer,
        subtitle = stringResource(R.string.suspend_sources_sub),
    ) {
        val s = src ?: return@SectionCard
        if (s.list.isEmpty()) {
            Hint(stringResource(R.string.suspend_sources_unreadable_reason))
            return@SectionCard
        }
        Hint(s.path)
        val active = s.list.filter { it.active }.sortedByDescending { it.activeMs }
        SubHeader(stringResource(R.string.suspend_sources_active, active.size))
        if (active.isEmpty()) Hint(stringResource(R.string.suspend_sources_none_active))
        active.take(8).forEach { a ->
            Row {
                Text(a.name, style = Mono, modifier = Modifier.weight(1f))
                Text(SleepInfo.fmtDur(a.activeMs), style = Mono)
            }
        }
        SubHeader(stringResource(R.string.suspend_sources_top))
        s.list.sortedByDescending { it.totalMs }.take(8).forEach { a ->
            Row {
                Text(a.name, style = Mono, modifier = Modifier.weight(1f))
                Text("${SleepInfo.fmtDur(a.totalMs)} · ${a.activeCount}×", style = Mono)
            }
        }
    }
}
