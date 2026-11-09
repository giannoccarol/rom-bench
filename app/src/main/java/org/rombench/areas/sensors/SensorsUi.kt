package org.rombench.areas.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.Txt
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmt2
import org.rombench.core.result
import org.rombench.core.text
import org.rombench.core.txt
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.DetailsTable
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.LineChart
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.SeriesBuffer
import org.rombench.ui.components.StatusChip
import org.rombench.ui.theme.LocalStatusColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun SensorsContent() {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    val entries = remember { sm?.let { sensorEntries(it) } ?: emptyList() }
    if (sm == null || entries.isEmpty()) {
        SectionCard(stringResource(R.string.sensors_live_title), icon = Icons.Outlined.Sensors) {
            Hint(stringResource(R.string.sensors_s_none))
        }
        return
    }

    SectionCard(
        stringResource(R.string.sensors_guided_title),
        subtitle = stringResource(R.string.sensors_guided_sub),
        icon = Icons.Outlined.TouchApp,
    ) {
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { ProximityTest(sm, it) }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { LightTest(sm, it) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { GyroTest(sm, it) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { FlipTest(sm, it) }
    }

    CompassCard(sm)

    SectionCard(
        stringResource(R.string.sensors_live_title),
        subtitle = stringResource(R.string.sensors_live_sub, entries.size),
        icon = Icons.Outlined.Sensors,
    ) {
        entries.forEach { SensorRow(sm, it) }
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers

/** Registers a listener while [enabled]; events arrive on the main thread. */
@Composable
private fun SensorListener(
    sm: SensorManager,
    sensor: Sensor?,
    period: Int,
    enabled: Boolean,
    onAccuracy: (Int) -> Unit = {},
    onEvent: (SensorEvent) -> Unit,
) {
    val cb by rememberUpdatedState(onEvent)
    val acc by rememberUpdatedState(onAccuracy)
    DisposableEffect(sensor, period, enabled) {
        if (sensor == null || !enabled) return@DisposableEffect onDispose { }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = cb(e)
            override fun onAccuracyChanged(s: Sensor?, a: Int) = acc(a)
        }
        runCatching { sm.registerListener(l, sensor, period) }
        onDispose { sm.unregisterListener(l) }
    }
}

private fun record(name: String, @StringRes title: Int, status: Status, summary: Txt, reason: Txt? = null, details: List<Detail> = emptyList()) =
    ResultStore.put(SensorsArea.result(name, txt(title), status, summary, reason, details, Kind.GUIDED))

/** Frame of a guided sensor test: title, stored result, instruction, start/stop. */
@Composable
private fun GuidedBox(
    title: String,
    instruction: String,
    name: String,
    active: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val results by ResultStore.results.collectAsState()
    val r = results["${SensorsArea.id}.$name"]
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                r?.let { StatusChip(it.status, Modifier.padding(start = 8.dp)) }
            }
            Hint(instruction)
            if (active) content()
            if (r != null && r.status != Status.PASS) r.reason?.let { Hint(it.text()) }
            FilledTonalButton(onClick = onToggle) {
                Text(stringResource(if (active) R.string.sensors_btn_stop else R.string.sensors_btn_start))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Guided tests

@Composable
private fun ProximityTest(sm: SensorManager, s: Sensor) {
    var active by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(Float.NaN) }
    var near by remember { mutableStateOf<Boolean?>(null) }
    var transitions by remember { mutableIntStateOf(0) }
    var events by remember { mutableIntStateOf(0) }
    var passed by remember { mutableStateOf(false) }
    val thr = proximityThreshold(s)
    fun details() = listOf(
        d("Sensor", s.name), d("Max range", "${fmtF(s.maximumRange)} cm"), d("Near below", "${fmtF(thr)} cm"),
        d("Events", events), d("Transitions", transitions), d("Last value", fmtF(value)),
    )
    SensorListener(sm, s, SensorManager.SENSOR_DELAY_NORMAL, active) { e ->
        val v = e.values[0]
        events++
        value = v
        val n = v < thr
        if (near != null && n != near) transitions++
        near = n
        if (!passed && transitions >= 2 && !n) {
            passed = true
            record("proximity_guided", R.string.sensors_g_prox_title, Status.PASS, txt(R.string.sensors_g_prox_pass, transitions), null, details())
        }
    }
    GuidedBox(
        stringResource(R.string.sensors_g_prox_title), stringResource(R.string.sensors_g_prox_how), "proximity_guided", active,
        onToggle = {
            active = !active
            if (active) { value = Float.NaN; near = null; transitions = 0; events = 0; passed = false }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Metric(
                when (near) { true -> stringResource(R.string.sensors_near); false -> stringResource(R.string.sensors_far); null -> "—" },
                if (value.isNaN()) stringResource(R.string.sensors_waiting) else "${fmtF(value)} cm",
                status = if (passed) Status.PASS else null,
            )
            Metric(transitions.toString(), stringResource(R.string.sensors_changes))
        }
        TextButton(onClick = {
            val reason = when {
                events == 0 -> txt(R.string.sensors_g_prox_r_silent)
                near == true -> txt(R.string.sensors_g_prox_r_stuck_near)
                else -> txt(R.string.sensors_g_prox_r_stuck_far)
            }
            record("proximity_guided", R.string.sensors_g_prox_title, Status.FAIL, txt(R.string.sensors_g_no_reaction), reason, details())
            active = false
        }) { Text(stringResource(R.string.sensors_btn_no_reaction)) }
    }
}

@Composable
private fun LightTest(sm: SensorManager, s: Sensor) {
    var active by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(Float.NaN) }
    var lo by remember { mutableFloatStateOf(Float.NaN) }
    var hi by remember { mutableFloatStateOf(Float.NaN) }
    var events by remember { mutableIntStateOf(0) }
    var passed by remember { mutableStateOf(false) }
    fun details() = listOf(d("Sensor", s.name), d("Events", events), d("Min", "${fmtF(lo)} lx"), d("Max", "${fmtF(hi)} lx"), d("Range", "${fmtF(s.maximumRange)} lx"))
    SensorListener(sm, s, SensorManager.SENSOR_DELAY_NORMAL, active) { e ->
        val v = e.values[0]
        events++
        value = v
        if (lo.isNaN() || v < lo) lo = v
        if (hi.isNaN() || v > hi) hi = v
        if (!passed && hi >= 5 * (lo + 1) && hi - lo >= 20) {
            passed = true
            record("light_guided", R.string.sensors_g_light_title, Status.PASS, txt(R.string.sensors_g_light_pass, fmt1(lo.toDouble()), fmt1(hi.toDouble())), null, details())
        }
    }
    GuidedBox(
        stringResource(R.string.sensors_g_light_title), stringResource(R.string.sensors_g_light_how), "light_guided", active,
        onToggle = {
            active = !active
            if (active) { value = Float.NaN; lo = Float.NaN; hi = Float.NaN; events = 0; passed = false }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Metric(if (value.isNaN()) "—" else fmtF(value), stringResource(R.string.sensors_lux_now), status = if (passed) Status.PASS else null)
            Metric(if (lo.isNaN()) "—" else fmtF(lo), stringResource(R.string.sensors_min))
            Metric(if (hi.isNaN()) "—" else fmtF(hi), stringResource(R.string.sensors_max))
        }
        TextButton(onClick = {
            val reason = if (events == 0) txt(R.string.sensors_g_light_r_silent) else txt(R.string.sensors_g_light_r_flat)
            record("light_guided", R.string.sensors_g_light_title, Status.FAIL, txt(R.string.sensors_g_no_reaction), reason, details())
            active = false
        }) { Text(stringResource(R.string.sensors_btn_no_reaction)) }
    }
}

@Composable
private fun GyroTest(sm: SensorManager, s: Sensor) {
    var active by remember { mutableStateOf(false) }
    var now by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var events by remember { mutableIntStateOf(0) }
    var nonZero by remember { mutableIntStateOf(0) }
    var passed by remember { mutableStateOf(false) }
    val buffer = remember { SeriesBuffer(150, 1) }
    fun details() = listOf(d("Sensor", s.name), d("Events", events), d("Non-zero events", nonZero), d("Peak", "${fmt2(peak.toDouble())} rad/s"), d("Range", "${fmtF(s.maximumRange)} rad/s"))
    SensorListener(sm, s, SensorManager.SENSOR_DELAY_GAME, active) { e ->
        val m = Stats.magnitude(e.values).toFloat()
        events++
        if (m != 0f) nonZero++
        now = m
        buffer.add(m)
        if (m > peak) peak = m
        if (!passed && peak >= 2f) {
            passed = true
            record("gyro_motion", R.string.sensors_g_gyro_title, Status.PASS, txt(R.string.sensors_g_gyro_pass, fmt1(peak.toDouble())), null, details())
        }
    }
    GuidedBox(
        stringResource(R.string.sensors_g_gyro_title), stringResource(R.string.sensors_g_gyro_how), "gyro_motion", active,
        onToggle = {
            active = !active
            if (active) { peak = 0f; events = 0; nonZero = 0; passed = false; buffer.clear() }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Metric(fmt2(now.toDouble()), "|ω| rad/s")
            Metric(fmt2(peak.toDouble()), stringResource(R.string.sensors_peak), status = if (passed) Status.PASS else null)
        }
        LineChart(buffer, height = 90.dp, unit = "")
        TextButton(onClick = {
            val reason = if (events == 0) txt(R.string.sensors_g_gyro_r_silent)
            else if (nonZero == 0) txt(R.string.sensors_g_gyro_r_zero) else txt(R.string.sensors_g_gyro_r_weak, fmt2(peak.toDouble()))
            record("gyro_motion", R.string.sensors_g_gyro_title, Status.FAIL, txt(R.string.sensors_g_no_reaction), reason, details())
            active = false
        }) { Text(stringResource(R.string.sensors_btn_no_reaction)) }
    }
}

/** Face up then face down: the Z axis of the accelerometer must change sign. */
@Composable
private fun FlipTest(sm: SensorManager, s: Sensor) {
    var active by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    var z by remember { mutableFloatStateOf(Float.NaN) }
    var stable by remember { mutableIntStateOf(0) }
    var negStable by remember { mutableIntStateOf(0) }
    var inverted by remember { mutableStateOf(false) }
    var upZ by remember { mutableFloatStateOf(Float.NaN) }
    fun details() = listOf(d("Sensor", s.name), d("Face up Z", fmtF(upZ)), d("Last Z", fmtF(z)), d("Step", step))
    SensorListener(sm, s, SensorManager.SENSOR_DELAY_GAME, active) { e ->
        if (e.values.size < 3) return@SensorListener
        val v = e.values[2]
        z = v
        when (step) {
            0 -> {
                if (v > 8f) stable++ else stable = 0
                if (v < -8f) negStable++ else negStable = 0
                if (negStable >= 25) inverted = true
                if (stable >= 25) { upZ = v; step = 1; stable = 0 }
            }
            1 -> {
                if (v < -8f) stable++ else stable = 0
                if (stable >= 25) {
                    step = 2
                    record("accel_flip", R.string.sensors_g_flip_title, Status.PASS, txt(R.string.sensors_g_flip_pass, fmt1(upZ.toDouble()), fmt1(v.toDouble())), null, details())
                }
            }
        }
    }
    GuidedBox(
        stringResource(R.string.sensors_g_flip_title), stringResource(R.string.sensors_g_flip_how), "accel_flip", active,
        onToggle = {
            active = !active
            if (active) { step = 0; stable = 0; negStable = 0; inverted = false; upZ = Float.NaN; z = Float.NaN }
        },
    ) {
        val stepText = when (step) {
            0 -> stringResource(R.string.sensors_g_flip_step_up)
            1 -> stringResource(R.string.sensors_g_flip_step_down)
            else -> stringResource(R.string.sensors_g_flip_done)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Metric(if (z.isNaN()) "—" else fmt1(z.toDouble()), "Z m/s²", status = if (step == 2) Status.PASS else null)
            Text(stepText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (inverted && step == 0) Text(
            stringResource(R.string.sensors_g_flip_inverted), style = MaterialTheme.typography.bodySmall,
            color = LocalStatusColors.current.warn,
        )
        TextButton(onClick = {
            val reason = if (inverted && step == 0) txt(R.string.sensors_g_flip_r_inverted) else txt(R.string.sensors_g_flip_r_none)
            record("accel_flip", R.string.sensors_g_flip_title, Status.FAIL, txt(R.string.sensors_g_no_reaction), reason, details())
            active = false
        }) { Text(stringResource(R.string.sensors_btn_problem)) }
    }
}

// ---------------------------------------------------------------------------------------------
// Compass

@Composable
private fun CompassCard(sm: SensorManager) {
    val rv = remember { sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    val acc = remember { sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val mag = remember { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    if (rv == null && (acc == null || mag == null)) return
    val useRv = rv != null
    var active by remember { mutableStateOf(false) }
    var azimuth by remember { mutableFloatStateOf(Float.NaN) }
    var magAccuracy by remember { mutableIntStateOf(-1) }
    var field by remember { mutableFloatStateOf(Float.NaN) }
    val view = LocalView.current
    val r = remember { FloatArray(9) }
    val out = remember { FloatArray(9) }
    val orient = remember { FloatArray(3) }
    val grav = remember { FloatArray(3) }
    val geo = remember { FloatArray(3) }
    val have = remember { BooleanArray(2) }

    fun update() {
        val (ax, ay) = when (view.display?.rotation ?: android.view.Surface.ROTATION_0) {
            android.view.Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            android.view.Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            android.view.Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(r, ax, ay, out)
        SensorManager.getOrientation(out, orient)
        val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
        azimuth = if (azimuth.isNaN()) deg else {
            val diff = ((deg - azimuth + 540f) % 360f) - 180f
            (azimuth + diff * 0.25f + 360f) % 360f
        }
    }

    SensorListener(sm, rv, SensorManager.SENSOR_DELAY_GAME, active && useRv) { e ->
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        runCatching { SensorManager.getRotationMatrixFromVector(r, v); update() }
    }
    SensorListener(sm, acc, SensorManager.SENSOR_DELAY_GAME, active && !useRv) { e ->
        for (i in 0..2) grav[i] = if (have[0]) grav[i] * 0.8f + e.values[i] * 0.2f else e.values[i]
        have[0] = true
    }
    SensorListener(sm, mag, SensorManager.SENSOR_DELAY_GAME, active && mag != null, onAccuracy = { magAccuracy = it }) { e ->
        for (i in 0..2) geo[i] = if (have[1]) geo[i] * 0.8f + e.values[i] * 0.2f else e.values[i]
        have[1] = true
        field = Stats.magnitude(e.values).toFloat()
        if (!useRv && have[0] && SensorManager.getRotationMatrix(r, null, grav, geo)) update()
    }

    val source = stringResource(if (useRv) R.string.sensors_compass_src_rv else R.string.sensors_compass_src_am)
    SectionCard(
        stringResource(R.string.sensors_compass_title),
        subtitle = stringResource(R.string.sensors_compass_sub),
        icon = Icons.Outlined.Explore,
    ) {
        if (active) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompassDial(azimuth, Modifier.size(150.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Metric(if (azimuth.isNaN()) "—" else "${azimuth.toInt()}° ${cardinal(azimuth)}", stringResource(R.string.sensors_heading))
                    KeyValue(stringResource(R.string.sensors_source), source)
                    if (mag != null) {
                        KeyValue("|B|", if (field.isNaN()) "—" else "${fmt1(field.toDouble())} µT")
                        KeyValue(stringResource(R.string.sensors_accuracy), accuracyName(magAccuracy))
                    }
                }
            }
            Hint(stringResource(R.string.sensors_compass_calibrate))
        }
        FilledTonalButton(onClick = { active = !active; if (active) azimuth = Float.NaN }) {
            Text(stringResource(if (active) R.string.sensors_btn_stop else R.string.sensors_btn_start))
        }
        GuidedCheck(
            SensorsArea, "compass", txt(R.string.sensors_g_compass_title), stringResource(R.string.sensors_g_compass_q),
            enabled = active,
            details = {
                listOf(
                    d("Source", if (useRv) "rotation vector" else "accelerometer + magnetometer"),
                    d("Heading", if (azimuth.isNaN()) null else "${azimuth.toInt()}°"),
                    d("|B|", if (field.isNaN()) null else "${fmt1(field.toDouble())} µT"),
                    d("Accuracy", accuracyName(magAccuracy)),
                )
            },
        )
    }
}

private fun cardinal(deg: Float): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]

/** Compass rose rotated so that its N points north; the fixed mark at the top is where the phone points. */
@Composable
private fun CompassDial(azimuth: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val ring = MaterialTheme.colorScheme.outline
    val text = MaterialTheme.colorScheme.onSurface
    val mark = MaterialTheme.colorScheme.primary
    val north = Color(0xFFE5484D)
    Canvas(modifier) {
        val c = center
        val rad = size.minDimension / 2f - 4f
        drawCircle(ring, rad, c, style = Stroke(width = 3f))
        val tri = Path().apply {
            moveTo(c.x, c.y - rad - 2f); lineTo(c.x - 9f, c.y - rad + 14f); lineTo(c.x + 9f, c.y - rad + 14f); close()
        }
        drawPath(tri, mark)
        if (azimuth.isNaN()) return@Canvas
        rotate(-azimuth, c) {
            for (i in 0 until 36) {
                val a = Math.toRadians(i * 10.0)
                val len = if (i % 9 == 0) 16f else if (i % 3 == 0) 10f else 5f
                val sx = c.x + (rad - len) * sin(a).toFloat()
                val sy = c.y - (rad - len) * cos(a).toFloat()
                val ex = c.x + rad * sin(a).toFloat()
                val ey = c.y - rad * cos(a).toFloat()
                drawLine(ring, Offset(sx, sy), Offset(ex, ey), strokeWidth = if (i % 9 == 0) 3f else 1.5f)
            }
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (l, a0) ->
                val a = Math.toRadians(a0)
                val m = measurer.measure(l, TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (l == "N") north else text))
                val px = c.x + (rad - 30f) * sin(a).toFloat() - m.size.width / 2f
                val py = c.y - (rad - 30f) * cos(a).toFloat() - m.size.height / 2f
                drawText(m, topLeft = Offset(px, py))
            }
            val needle = Path().apply {
                moveTo(c.x, c.y - rad + 44f); lineTo(c.x - 8f, c.y); lineTo(c.x + 8f, c.y); close()
            }
            drawPath(needle, north)
            val tail = Path().apply {
                moveTo(c.x, c.y + rad - 44f); lineTo(c.x - 8f, c.y); lineTo(c.x + 8f, c.y); close()
            }
            drawPath(tail, ring)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Live list

@Composable
private fun SensorRow(sm: SensorManager, e: SensorEntry) {
    var open by rememberSaveable(e.key) { mutableStateOf(false) }
    val s = e.sensor
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (e.info.label != 0) stringResource(e.info.label) else (s.stringType ?: "?"),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(s.name, s.vendor, if (s.isWakeUpSensor) "wake-up" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(e.mode.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp).size(20.dp),
                )
            }
            if (open) {
                if (e.mode == Mode.ONE_SHOT) TriggerSensor(sm, e) else LiveSensor(sm, e)
                DetailsTable(sensorFacts(e))
            }
        }
    }
}

private val RATES = listOf(SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_GAME, SensorManager.SENSOR_DELAY_FASTEST)

@Composable
private fun LiveSensor(sm: SensorManager, e: SensorEntry) {
    var rateChoice by rememberSaveable(e.key) { mutableIntStateOf(1) }
    val buffer = remember(e.key) { SeriesBuffer(200, e.chartChannels) }
    var values by remember { mutableStateOf<FloatArray?>(null) }
    var rate by remember { mutableFloatStateOf(Float.NaN) }
    var accuracy by remember { mutableIntStateOf(-1) }
    var events by remember { mutableIntStateOf(0) }
    val window = remember { LongArray(2) }
    val period = if (e.mode == Mode.CONTINUOUS) RATES[rateChoice] else SensorManager.SENSOR_DELAY_NORMAL

    SensorListener(sm, e.sensor, period, true, onAccuracy = { accuracy = it }) { ev ->
        val v = ev.values
        buffer.add(*FloatArray(e.chartChannels) { v.getOrElse(it) { Float.NaN } })
        values = v.copyOf()
        events++
        if (window[1] == 0L) window[0] = ev.timestamp
        window[1]++
        val dt = ev.timestamp - window[0]
        if (dt >= 1_000_000_000L) {
            rate = ((window[1] - 1) * 1e9 / dt).toFloat()
            window[0] = ev.timestamp
            window[1] = 1
        }
    }

    if (e.mode == Mode.CONTINUOUS) ButtonRow {
        listOf(R.string.sensors_rate_normal, R.string.sensors_rate_game, R.string.sensors_rate_fastest).forEachIndexed { i, label ->
            FilterChip(
                selected = rateChoice == i,
                onClick = { rateChoice = i; rate = Float.NaN; window[1] = 0 },
                label = { Text(stringResource(label)) },
            )
        }
    }
    LineChart(buffer, height = 120.dp, labels = (0 until e.chartChannels).map { e.axisLabel(it) })
    val unit = e.info.unit.let { if (it.isEmpty()) "" else " $it" }
    val v = values
    if (v == null) Hint(stringResource(if (e.mode == Mode.CONTINUOUS) R.string.sensors_waiting else R.string.sensors_on_change_hint))
    else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        v.forEachIndexed { i, x -> KeyValue(e.axisLabel(i), fmtF(x) + if (e.info.axes.getOrNull(i) != null) unit else "") }
        if (e.info.axes.size >= 3 && e.info.unit.isNotEmpty() && v.size >= 3) {
            val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            KeyValue("|${e.axisLabel(0)}${e.axisLabel(1)}${e.axisLabel(2)}|", fmtF(m) + unit)
        }
        if (e.type == Sensor.TYPE_PROXIMITY) KeyValue(
            stringResource(R.string.sensors_state),
            stringResource(if (v[0] < proximityThreshold(e.sensor)) R.string.sensors_near else R.string.sensors_far),
        )
    }
    KeyValue(stringResource(R.string.sensors_events), events.toString())
    if (e.mode == Mode.CONTINUOUS) KeyValue(stringResource(R.string.sensors_rate), if (rate.isNaN()) "—" else "${fmt1(rate.toDouble())} Hz")
    KeyValue(stringResource(R.string.sensors_accuracy), accuracyName(accuracy))
    if (e.mode == Mode.CONTINUOUS && !rate.isNaN() && rateChoice == 2 && e.sensor.minDelay > 0) {
        val claimed = 1e6 / e.sensor.minDelay
        if (rate < claimed * 0.5 && abs(rate) > 0) Text(
            stringResource(R.string.sensors_rate_slow, fmt1(claimed)), style = MaterialTheme.typography.bodySmall,
            color = LocalStatusColors.current.warn,
        )
    }
}

@Composable
private fun TriggerSensor(sm: SensorManager, e: SensorEntry) {
    var armed by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var last by remember { mutableLongStateOf(0L) }
    val listener = remember {
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                count++
                last = System.currentTimeMillis()
                armed = false
            }
        }
    }
    DisposableEffect(e.key) { onDispose { runCatching { sm.cancelTriggerSensor(listener, e.sensor) } } }
    Hint(stringResource(R.string.sensors_trigger_hint))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { armed = runCatching { sm.requestTriggerSensor(listener, e.sensor) }.getOrDefault(false) }, enabled = !armed) {
            Text(stringResource(if (armed) R.string.sensors_trigger_armed else R.string.sensors_trigger_arm))
        }
        Spacer(Modifier.width(16.dp))
        Metric(count.toString(), stringResource(R.string.sensors_triggers))
    }
    if (last > 0) KeyValue(stringResource(R.string.sensors_last), java.text.DateFormat.getTimeInstance().format(java.util.Date(last)))
}
