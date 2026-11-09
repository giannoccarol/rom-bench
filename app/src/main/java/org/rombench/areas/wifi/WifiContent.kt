@file:Suppress("MissingPermission")

package org.rombench.areas.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings.ACTION_WIFI_SETTINGS
import android.provider.Settings.Panel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.rombench.R
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Settings
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmtBytes
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.LevelBar
import org.rombench.ui.components.LineChart
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.SeriesBuffer
import org.rombench.ui.components.rememberPermissions
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono
import java.net.HttpURLConnection
import java.net.URL

private const val SPEED_MAX_MS = 15_000L
private const val SPEED_MAX_BYTES = 100L * 1024 * 1024

@Composable
internal fun WifiContent(area: WifiArea) {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
    if (wm == null) {
        SectionCard(stringResource(R.string.wifi_live_title), icon = Icons.AutoMirrored.Outlined.ShowChart) {
            Hint(stringResource(R.string.wifi_ui_no_wifi))
        }
        return
    }
    LiveLink(wm)
    ScanSection(area, wm)
    SpeedSection(area)
    SectionCard(stringResource(R.string.wifi_guided_title), icon = Icons.Outlined.Router) {
        Hint(stringResource(R.string.wifi_g_browse_hint))
        GuidedCheck(area, "browse", txt(R.string.wifi_g_browse), stringResource(R.string.wifi_g_browse_q))
    }
}

private fun openWifiSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        runCatching { ctx.startActivity(Intent(ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun LiveLink(wm: WifiManager) {
    val ctx = LocalContext.current
    val sc = LocalStatusColors.current
    val rssiBuf = remember { SeriesBuffer(120, 1) }
    val speedBuf = remember { SeriesBuffer(120, 2) }
    val cm = remember { ctx.applicationContext.getSystemService(ConnectivityManager::class.java) }
    val link by rememberPolled(1000) {
        readLink(wm, cm).also { l ->
            if (l.connected) {
                rssiBuf.add(l.rssi.toFloat())
                speedBuf.add(l.tx.toFloat(), l.rx.toFloat())
            } else {
                rssiBuf.add(Float.NaN); speedBuf.add(Float.NaN, Float.NaN)
            }
        }
    }
    SectionCard(
        stringResource(R.string.wifi_live_title),
        subtitle = stringResource(R.string.wifi_live_sub),
        icon = Icons.AutoMirrored.Outlined.ShowChart,
        actions = {
            androidx.compose.material3.IconButton(onClick = { openWifiSettings(ctx) }) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.wifi_ui_settings))
            }
        },
    ) {
        val l = link
        when {
            l == null -> Hint(stringResource(R.string.loading))
            l.state != WifiManager.WIFI_STATE_ENABLED -> {
                Hint(stringResource(R.string.wifi_ui_off))
                FilledTonalButton(onClick = { openWifiSettings(ctx) }) { Text(stringResource(R.string.wifi_ui_turn_on)) }
            }
            !l.connected -> Hint(stringResource(R.string.wifi_ui_not_connected, l.supplicant?.name ?: "—"))
            else -> {
                Text(
                    l.ssid ?: stringResource(R.string.wifi_ui_hidden_ssid),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric("${l.rssi}", stringResource(R.string.wifi_ui_rssi), Modifier.weight(1f), signalStatus(l.rssi))
                    Metric("${l.linkSpeed.coerceAtLeast(0)}", stringResource(R.string.wifi_ui_link), Modifier.weight(1f))
                    Metric(
                        WifiUtil.band(l.freq),
                        WifiUtil.channel(l.freq)?.let { stringResource(R.string.wifi_ui_channel, it) } ?: "${l.freq} MHz",
                        Modifier.weight(1f),
                    )
                }
                Text(
                    "${WifiUtil.standard(l.standard)} · ${l.freq} MHz" + (l.security?.let { " · " + WifiUtil.security(it) } ?: ""),
                    style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LevelBar(((l.rssi + 100) / 70f), color = sc.of(signalStatus(l.rssi)))
                SubHeader(stringResource(R.string.wifi_ui_rssi_chart))
                LineChart(rssiBuf, range = -100f..-20f, labels = listOf("RSSI"), unit = " dBm", height = 110.dp)
                SubHeader(stringResource(R.string.wifi_ui_speed_chart))
                LineChart(speedBuf, labels = listOf("TX", "RX"), unit = " Mb/s", height = 110.dp)
            }
        }
    }
}

@Composable
private fun ScanSection(area: WifiArea, wm: WifiManager) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val perms = rememberPermissions(area.permissions)
    var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableIntStateOf(-1) }

    DisposableEffect(perms.allGranted) {
        if (!perms.allGranted) return@DisposableEffect onDispose { }
        fun refresh() {
            results = runCatching { wm.scanResults ?: emptyList() }.getOrDefault(emptyList())
        }
        refresh()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh()
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }

    SectionCard(
        stringResource(R.string.wifi_scan_title),
        subtitle = stringResource(R.string.wifi_scan_sub),
        icon = Icons.Outlined.TravelExplore,
    ) {
        if (!perms.allGranted) {
            Hint(stringResource(R.string.wifi_ui_scan_perm))
            return@SectionCard
        }
        if (!WifiUtil.locationEnabled(ctx)) Hint(stringResource(R.string.wifi_ui_location_off))
        ButtonRow {
            FilledTonalButton(
                enabled = !scanning,
                onClick = {
                    scanning = true
                    scope.launch {
                        val s = scanWifi(ctx, wm)
                        results = s.results
                        note = when {
                            s.fresh -> res.getString(R.string.wifi_ui_scan_fresh, s.results.size)
                            else -> res.getString(R.string.wifi_ui_scan_cached, s.ageSec ?: 0)
                        }
                        scanning = false
                    }
                },
            ) {
                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (scanning) R.string.wifi_ui_scanning else R.string.wifi_ui_scan))
            }
        }
        note?.let { Hint(it) }
        val counts = IntArray(4)
        results.forEach { r -> WifiUtil.bandIndex(r.frequency).takeIf { it >= 0 }?.let { counts[it]++ } }
        ButtonRow {
            FilterChip(filter == -1, { filter = -1 }, { Text(stringResource(R.string.wifi_ui_all, results.size)) })
            FilterChip(filter == 0, { filter = 0 }, { Text("2.4 GHz (${counts[0]})") })
            FilterChip(filter == 1, { filter = 1 }, { Text("5 GHz (${counts[1]})") })
            if (counts[2] > 0 || runCatching { wm.is6GHzBandSupported }.getOrDefault(false)) {
                FilterChip(filter == 2, { filter = 2 }, { Text("6 GHz (${counts[2]})") })
            }
        }
        val shown = results.filter { filter == -1 || WifiUtil.bandIndex(it.frequency) == filter }.sortedByDescending { it.level }
        if (shown.isEmpty()) Hint(stringResource(R.string.wifi_ui_scan_empty))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            shown.take(40).forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ScanRow(r)
            }
        }
        if (shown.size > 40) Hint(stringResource(R.string.wifi_ui_more, shown.size - 40))
    }
}

@Composable
private fun ScanRow(r: ScanResult) {
    val sc = LocalStatusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    WifiUtil.scanSsid(r) ?: stringResource(R.string.wifi_ui_hidden_ssid),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        WifiUtil.bandChannel(r.frequency), WifiUtil.width(r.channelWidth),
                        WifiUtil.standardShort(r.wifiStandard), WifiUtil.scanSecurity(r.capabilities),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${r.level} dBm", style = Mono, color = sc.of(signalStatus(r.level)), modifier = Modifier.padding(start = 8.dp))
        }
        LevelBar((r.level + 100) / 70f, color = sc.of(signalStatus(r.level)), height = 6.dp)
    }
}

@Composable
private fun SpeedSection(area: WifiArea) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val cm = remember { ctx.applicationContext.getSystemService(ConnectivityManager::class.java) }
    var job by remember { mutableStateOf<Job?>(null) }
    var conn by remember { mutableStateOf<HttpURLConnection?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var mbps by remember { mutableStateOf<Double?>(null) }
    var bytes by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val url = remember(Settings.version.intValue) { Settings.speedUrl(ctx) }

    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            conn?.let { c -> Thread { runCatching { c.disconnect() } }.start() }
        }
    }

    fun record(m: Double?, got: Long, ms: Long, err: String?) {
        val details = listOf(d("URL", url), d("Bytes", fmtBytes(got)), d("Time", "$ms ms"), d("Error", err))
        val title = txt(R.string.wifi_t_speed)
        val r = when {
            err != null && got < 256 * 1024 -> area.result("speed", title, Status.WARN, txt(R.string.wifi_s_speed_fail), txt(R.string.wifi_r_speed_fail, err), details, Kind.GUIDED)
            m == null -> area.result("speed", title, Status.WARN, txt(R.string.wifi_s_speed_fail), txt(R.string.wifi_r_speed_fail, "—"), details, Kind.GUIDED)
            m < 1.0 -> area.result("speed", title, Status.WARN, txt(R.string.wifi_s_speed, fmt1(m)), txt(R.string.wifi_r_speed_slow), details, Kind.GUIDED)
            else -> area.result("speed", title, Status.PASS, txt(R.string.wifi_s_speed, fmt1(m)), null, details, Kind.GUIDED)
        }
        ResultStore.put(r)
    }

    fun start() {
        error = null; mbps = null; bytes = 0; progress = 0f
        running = true
        job = scope.launch(Dispatchers.IO) {
            val net = WifiUtil.wifiNetwork(cm)
            if (net == null) {
                error = res.getString(R.string.wifi_ui_speed_no_wifi)
                running = false
                return@launch
            }
            var got = 0L
            val t0 = SystemClock.elapsedRealtime()
            var err: String? = null
            try {
                val c = net.openConnection(URL(url)) as HttpURLConnection
                conn = c
                c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
                val total = c.contentLengthLong.takeIf { it > 0 } ?: SPEED_MAX_BYTES
                val cap = minOf(total, SPEED_MAX_BYTES)
                c.inputStream.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    var lastUi = 0L
                    while (isActive) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        got += n
                        val el = SystemClock.elapsedRealtime() - t0
                        if (el - lastUi > 200) {
                            lastUi = el
                            bytes = got
                            mbps = got * 8.0 / 1e6 / (el / 1000.0).coerceAtLeast(0.001)
                            progress = maxOf(got.toFloat() / cap, el.toFloat() / SPEED_MAX_MS).coerceIn(0f, 1f)
                        }
                        if (el >= SPEED_MAX_MS || got >= cap) break
                    }
                }
            } catch (e: Exception) {
                err = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            } finally {
                conn?.disconnect(); conn = null
            }
            val el = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
            val m = if (got > 0) got * 8.0 / 1e6 / (el / 1000.0) else null
            bytes = got; mbps = m; progress = 1f; error = err
            if (isActive) record(m, got, el, err)
            running = false
        }
    }

    SectionCard(
        stringResource(R.string.wifi_speed_title),
        subtitle = stringResource(R.string.wifi_speed_sub, runCatching { URL(url).host }.getOrDefault(url)),
        icon = Icons.Outlined.Speed,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(mbps?.let { fmt1(it) } ?: "—", stringResource(R.string.wifi_ui_mbps), Modifier.weight(1f))
            Metric(fmtBytes(bytes), stringResource(R.string.wifi_ui_downloaded), Modifier.weight(1f))
        }
        if (running || progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        error?.let { Hint(stringResource(R.string.wifi_ui_speed_error, it)) }
        ButtonRow {
            if (!running) FilledTonalButton(onClick = { start() }) {
                Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_ui_speed_start))
            } else OutlinedButton(onClick = {
                job?.cancel()
                conn?.let { c -> Thread { runCatching { c.disconnect() } }.start() }
                job = null
                running = false
            }) {
                Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.stop))
            }
        }
        Hint(stringResource(R.string.wifi_speed_hint))
    }
}
