package org.rombench.areas.gnss

import android.content.Intent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.rombench.R
import org.rombench.core.Kind
import org.rombench.core.Perms
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.rememberPermissions
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Fixed colours per constellation, readable on light and dark surfaces. */
internal fun constellationColor(c: Int): Color = when (c) {
    GnssStatus.CONSTELLATION_GPS -> Color(0xFF3E63DD)
    GnssStatus.CONSTELLATION_GLONASS -> Color(0xFFE5484D)
    GnssStatus.CONSTELLATION_GALILEO -> Color(0xFF30A46C)
    GnssStatus.CONSTELLATION_BEIDOU -> Color(0xFFF5A524)
    GnssStatus.CONSTELLATION_QZSS -> Color(0xFF8E4EC6)
    GnssStatus.CONSTELLATION_IRNSS -> Color(0xFF12A594)
    GnssStatus.CONSTELLATION_SBAS -> Color(0xFF8B8D98)
    else -> Color(0xFFAD7F58)
}

@Composable
internal fun GnssContent() {
    val perms = rememberPermissions(listOf(Perms.LOCATION))
    if (!perms.allGranted) {
        SectionCard(stringResource(R.string.gnss_live_title), icon = Icons.Outlined.SatelliteAlt) {
            Hint(stringResource(R.string.gnss_need_permission))
        }
        return
    }
    GnssLive()
}

private class Cmd(val command: String, @StringRes val label: Int, @StringRes val why: Int)

@Composable
private fun GnssLive() {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(LocationManager::class.java) } ?: return
    val enabled by rememberPolled(2000) {
        lm.isLocationEnabled && runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }
    val hasGps = remember { runCatching { LocationManager.GPS_PROVIDER in lm.allProviders }.getOrDefault(false) }
    if (!hasGps) {
        SectionCard(stringResource(R.string.gnss_live_title), icon = Icons.Outlined.SatelliteAlt) {
            Hint(stringResource(R.string.gnss_r_no_gps))
        }
        return
    }
    if (enabled == false) {
        SectionCard(stringResource(R.string.gnss_s_location_off), icon = Icons.Outlined.LocationOff) {
            Hint(stringResource(R.string.gnss_location_off_hint))
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.gnss_open_location_settings)) }
        }
        return
    }

    var running by rememberSaveable { mutableStateOf(true) }
    var generation by remember { mutableIntStateOf(0) }
    var coldStart by remember { mutableStateOf(false) }
    val session = remember(generation) { GnssSession(lm, ContextCompat.getMainExecutor(ctx)) }
    val on = running && enabled == true
    DisposableEffect(session, on) {
        if (on) session.start()
        onDispose { session.stop() }
    }
    val tick by produceState(0L, on, session) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val version = session.version
    val snap = remember(version, tick, session) { session.snapshot() }

    // A fix seen live (e.g. outdoors, after a cold start) is kept as a guided result.
    val hasFix = snap.location != null
    LaunchedEffect(session, hasFix) {
        if (!hasFix) return@LaunchedEffect
        val s = session.snapshot()
        val loc = s.location ?: return@LaunchedEffect
        val ttff = s.hwTtffMs?.toLong() ?: s.firstFixMs ?: 0L
        ResultStore.put(
            GnssArea.result(
                "fix_live", txt(R.string.gnss_t_fix_live), Status.PASS,
                txt(
                    if (coldStart) R.string.gnss_s_fix_live_cold else R.string.gnss_s_fix_live,
                    fmt1(ttff / 1000.0), if (loc.hasAccuracy()) fmt1(loc.accuracy.toDouble()) else "?", s.sats.count { it.used },
                ),
                null,
                listOf(
                    d("TTFF (HAL)", s.hwTtffMs?.let { "$it ms" }),
                    d("TTFF (app)", s.firstFixMs?.let { "$it ms" }),
                    d("Cold start", coldStart),
                    d("Accuracy", if (loc.hasAccuracy()) "${fmt1(loc.accuracy.toDouble())} m" else null),
                    d("Used in fix", s.sats.count { it.used }),
                    d("Max C/N0", "${fmt1((s.sats.maxOfOrNull { it.cn0 } ?: 0f).toDouble())} dB-Hz"),
                    d("Latitude", "%.6f".format(Locale.US, loc.latitude), sensitive = true),
                    d("Longitude", "%.6f".format(Locale.US, loc.longitude), sensitive = true),
                ),
                Kind.GUIDED,
            ),
        )
    }

    ReceiverCard(snap, on, onToggle = { running = !running }, onRestart = { coldStart = false; generation++ })
    SkyCard(snap)
    BarsCard(snap)
    FixCard(snap)
    NmeaCard(snap)
    AssistCard(lm, onCommand = { cmd ->
        if (cmd == "delete_aiding_data") {
            coldStart = true
            generation++
        }
    })
}

@Composable
private fun ReceiverCard(s: GnssSnapshot, on: Boolean, onToggle: () -> Unit, onRestart: () -> Unit) {
    val status = when {
        !on -> stringResource(R.string.gnss_state_stopped)
        s.statusUpdates == 0 -> stringResource(R.string.gnss_state_starting)
        else -> stringResource(R.string.gnss_state_running, s.elapsedMs / 1000)
    }
    val used = s.sats.count { it.used }
    val maxNow = s.sats.maxOfOrNull { it.cn0 } ?: 0f
    val ttff = s.hwTtffMs?.toLong() ?: s.firstFixMs
    SectionCard(stringResource(R.string.gnss_live_title), subtitle = status, icon = Icons.Outlined.SatelliteAlt) {
        ButtonRow {
            Metric(s.sats.size.toString(), stringResource(R.string.gnss_in_view), Modifier.widthIn(min = 64.dp))
            Metric(used.toString(), stringResource(R.string.gnss_used), Modifier.widthIn(min = 64.dp), status = if (used > 0) Status.PASS else null)
            Metric(
                if (s.sats.isEmpty()) "—" else fmt1(maxNow.toDouble()), stringResource(R.string.gnss_max_cn0), Modifier.widthIn(min = 64.dp),
                status = when {
                    s.sats.isEmpty() -> null
                    maxNow <= 0f -> Status.FAIL
                    maxNow < 20f -> Status.WARN
                    else -> Status.PASS
                },
            )
            Metric(
                when {
                    ttff != null -> "${fmt1(ttff / 1000.0)} s"
                    on -> "${s.elapsedMs / 1000} s…"
                    else -> "—"
                },
                stringResource(R.string.gnss_ttff), Modifier.widthIn(min = 64.dp),
                status = if (ttff != null) Status.PASS else null,
            )
        }
        val byC = s.sats.groupBy { it.constellation }.toSortedMap()
            .map { (c, l) -> "${constellationName(c)} ${l.count { it.used }}/${l.size}" }
        if (byC.isNotEmpty()) Hint(stringResource(R.string.gnss_by_constellation, byC.joinToString("  ·  ")))
        if (s.sats.isNotEmpty() && maxNow <= 0f && s.elapsedMs > 10_000) Text(
            stringResource(R.string.gnss_live_no_rf), style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.fail,
        )
        if (on && s.sats.isNotEmpty() && maxNow > 0f && ttff == null && s.elapsedMs > 60_000) Hint(stringResource(R.string.gnss_live_slow))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onToggle) { Text(stringResource(if (on) R.string.gnss_btn_stop else R.string.gnss_btn_start)) }
            OutlinedButton(onClick = onRestart) { Text(stringResource(R.string.gnss_btn_restart)) }
        }
    }
}

@Composable
private fun SkyCard(s: GnssSnapshot) {
    SectionCard(stringResource(R.string.gnss_sky_title), subtitle = stringResource(R.string.gnss_sky_sub), icon = Icons.Outlined.Radar) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SkyPlot(s.sats, Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f))
        }
        Legend(s.sats)
    }
}

@Composable
private fun Legend(sats: List<Sat>) {
    val present = sats.map { it.constellation }.distinct().sorted()
    if (present.isEmpty()) {
        Hint(stringResource(R.string.gnss_no_sats_yet))
        return
    }
    ButtonRow {
        present.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) { drawCircle(constellationColor(c)) }
                Spacer(Modifier.width(4.dp))
                Text(constellationName(c), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SkyPlot(sats: List<Sat>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val c = center
        val pad = 18.dp.toPx()
        val r = size.minDimension / 2f - pad
        listOf(0f, 30f, 60f).forEach { e -> drawCircle(grid, r * (90f - e) / 90f, c, style = Stroke(width = 1.5f)) }
        drawCircle(grid, 2f, c)
        drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1f)
        drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1f)
        val st = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = label)
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (l, a0) ->
            val m = measurer.measure(l, st)
            val a = Math.toRadians(a0)
            val rr = r + pad / 2f + 2f
            drawText(m, topLeft = Offset(c.x + rr * sin(a).toFloat() - m.size.width / 2f, c.y - rr * cos(a).toFloat() - m.size.height / 2f))
        }
        val dot = 7.dp.toPx()
        val small = TextStyle(fontSize = 9.sp, color = label)
        sats.sortedBy { it.used }.forEach { s ->
            if (s.elevation < 0f) return@forEach
            val rr = r * (90f - s.elevation.coerceIn(0f, 90f)) / 90f
            val a = Math.toRadians(s.azimuth.toDouble())
            val p = Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
            val col = constellationColor(s.constellation).copy(alpha = if (s.cn0 > 0f) 1f else 0.4f)
            if (s.used) drawCircle(col, dot, p) else drawCircle(col, dot - 1.5f, p, style = Stroke(width = 3f))
            val m = measurer.measure(s.svid.toString(), small)
            drawText(m, topLeft = Offset(p.x + dot + 1f, p.y - m.size.height / 2f))
        }
    }
}

@Composable
private fun BarsCard(s: GnssSnapshot) {
    SectionCard(stringResource(R.string.gnss_bars_title), subtitle = stringResource(R.string.gnss_bars_sub), icon = Icons.Outlined.SignalCellularAlt) {
        if (s.sats.isEmpty()) {
            Hint(stringResource(R.string.gnss_no_sats_yet))
            return@SectionCard
        }
        Cn0Bars(s.sats)
    }
}

@Composable
private fun Cn0Bars(sats: List<Sat>) {
    val sorted = sats.sortedWith(compareBy({ it.constellation }, { it.svid }, { it.carrierHz ?: 0f }))
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val zero = LocalStatusColors.current.fail
    val barW = 18.dp
    val axisW = 26.dp
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(max(axisW + barW * sorted.size, 300.dp)).height(170.dp)) {
            val bottom = size.height - 16.dp.toPx()
            val top = 6.dp.toPx()
            val ax = axisW.toPx()
            val small = TextStyle(fontSize = 9.sp, color = label)
            for (v in 0..50 step 10) {
                val y = bottom - (bottom - top) * v / 50f
                drawLine(grid, Offset(ax, y), Offset(size.width, y), strokeWidth = 1f, pathEffect = if (v == 0) null else PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
                val m = measurer.measure(v.toString(), small)
                drawText(m, topLeft = Offset(ax - m.size.width - 4f, y - m.size.height / 2f))
            }
            val bw = barW.toPx()
            sorted.forEachIndexed { i, s ->
                val x = ax + i * bw + 3f
                val h = (bottom - top) * (s.cn0.coerceIn(0f, 50f) / 50f)
                val col = constellationColor(s.constellation).copy(alpha = if (s.used) 1f else 0.5f)
                if (s.cn0 > 0f) drawRect(col, Offset(x, bottom - h), Size(bw - 6f, h))
                else drawRect(zero, Offset(x, bottom - 3f), Size(bw - 6f, 3f))
                val m = measurer.measure(s.svid.toString(), small)
                drawText(m, topLeft = Offset(x + (bw - 6f - m.size.width) / 2f, bottom + 2f))
            }
        }
    }
    Hint(stringResource(R.string.gnss_bars_legend))
}

@Composable
private fun FixCard(s: GnssSnapshot) {
    var show by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        stringResource(R.string.gnss_fix_title), icon = Icons.Outlined.MyLocation,
        actions = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(R.string.gnss_show_coords))
            }
        },
    ) {
        val loc = s.location
        if (loc == null) {
            Hint(stringResource(R.string.gnss_no_fix_yet))
            return@SectionCard
        }
        val hidden = "••••••"
        KeyValue(stringResource(R.string.gnss_lat), if (show) "%.6f".format(Locale.US, loc.latitude) else hidden)
        KeyValue(stringResource(R.string.gnss_lon), if (show) "%.6f".format(Locale.US, loc.longitude) else hidden)
        KeyValue(stringResource(R.string.gnss_accuracy), if (loc.hasAccuracy()) "${fmt1(loc.accuracy.toDouble())} m" else "—")
        KeyValue(stringResource(R.string.gnss_altitude), if (loc.hasAltitude()) "${fmt1(loc.altitude)} m" else "—")
        KeyValue(stringResource(R.string.gnss_speed), if (loc.hasSpeed()) "${fmt1(loc.speed * 3.6)} km/h" else "—")
        KeyValue(stringResource(R.string.gnss_bearing), if (loc.hasBearing()) "${loc.bearing.toInt()}°" else "—")
        KeyValue(stringResource(R.string.gnss_used), s.sats.count { it.used }.toString())
        val age = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L
        KeyValue(stringResource(R.string.gnss_age), "$age s")
        KeyValue(stringResource(R.string.gnss_time_diff), "${(loc.time - s.wallAtFix) / 1000} s")
        KeyValue(stringResource(R.string.gnss_fixes), s.fixes.toString())
    }
}

@Composable
private fun NmeaCard(s: GnssSnapshot) {
    val types = s.nmeaTypes.entries.sortedByDescending { it.value }.take(8).joinToString { it.key }
    SectionCard(
        stringResource(R.string.gnss_nmea_title),
        subtitle = stringResource(R.string.gnss_nmea_sub, s.nmeaCount, s.nmeaBad, types.ifEmpty { "—" }),
        icon = Icons.Outlined.Terminal,
    ) {
        if (s.nmeaTail.isEmpty()) {
            Hint(stringResource(R.string.gnss_nmea_none_yet))
            return@SectionCard
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                s.nmeaTail.forEach { Text(it, style = Mono.copy(fontSize = 10.sp, lineHeight = 13.sp), softWrap = false) }
            }
        }
    }
}

@Composable
private fun AssistCard(lm: LocationManager, onCommand: (String) -> Unit) {
    val cmds = remember {
        listOf(
            Cmd("delete_aiding_data", R.string.gnss_cmd_cold, R.string.gnss_cmd_cold_why),
            Cmd("force_time_injection", R.string.gnss_cmd_time, R.string.gnss_cmd_time_why),
            Cmd("force_psds_injection", R.string.gnss_cmd_psds, R.string.gnss_cmd_psds_why),
        )
    }
    val outcome = remember { mutableStateMapOf<String, Boolean>() }
    SectionCard(stringResource(R.string.gnss_assist_title), subtitle = stringResource(R.string.gnss_assist_sub), icon = Icons.Outlined.Tune) {
        cmds.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        var ok = runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, c.command, null) }.getOrDefault(false)
                        // Older Qualcomm HALs and Android 10 still use the XTRA name.
                        if (c.command == "force_psds_injection") {
                            ok = runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null) }.getOrDefault(false) || ok
                        }
                        outcome[c.command] = ok
                        onCommand(c.command)
                    }) { Text(stringResource(c.label)) }
                    Spacer(Modifier.width(10.dp))
                    outcome[c.command]?.let { ok ->
                        Text(
                            stringResource(if (ok) R.string.gnss_cmd_sent else R.string.gnss_cmd_refused),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ok) LocalStatusColors.current.pass else LocalStatusColors.current.warn,
                        )
                    }
                }
                Hint(stringResource(c.why))
                Text(c.command, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
