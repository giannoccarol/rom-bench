package org.rombench.areas.display

import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.fmt2
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.FullscreenDialog
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.findActivity
import org.rombench.ui.components.rememberPolled
import kotlin.math.abs

object DisplayArea : Area {
    override val id = "display"
    override val title = R.string.display_title
    override val subtitle = R.string.display_subtitle
    override val icon: ImageVector = Icons.Outlined.TouchApp
    override val group = AreaGroup.MEDIA
    override val autoSeconds = 4

    private fun hdrName(t: Int) = when (t) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "type $t" }

    override suspend fun autoCheck(ctx: Context): List<TestResult> {
        val out = mutableListOf<TestResult>()
        val disp = DisplayInfo.display(ctx)
        if (disp == null) {
            return listOf(result("info", txt(R.string.display_r_info), Status.FAIL, txt(R.string.display_none), txt(R.string.display_none_why)))
        }
        val m = DisplayInfo.metrics(disp)
        val inches = DisplayInfo.diagonalInches(m)
        val modes = disp.supportedModes.toList()
        @Suppress("DEPRECATION") val hdr = disp.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
        val info = listOf(
            d("Resolution", "${m.widthPixels} × ${m.heightPixels}"), d("Density", "${m.densityDpi} dpi (${fmt2(m.density.toDouble())}×)"),
            d("xdpi / ydpi", "${fmt1(m.xdpi.toDouble())} / ${fmt1(m.ydpi.toDouble())}"), d("Diagonal (from dpi)", inches?.let { "${fmt1(it)}\"" }),
            d("Refresh rate", "${fmt1(disp.refreshRate.toDouble())} Hz"), d("Modes", modes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${fmt1(it.refreshRate.toDouble())}" }),
            d("HDR", hdr.joinToString { hdrName(it) }.ifEmpty { "none" }), d("Wide colour gamut", disp.isWideColorGamut),
            d("ro.sf.lcd_density", org.rombench.core.Sys.prop("ro.sf.lcd_density")), d("Name", disp.name),
        )
        // dpi plausibility: a wrong xdpi breaks physical size, touch slop and some games.
        val dpiOdd = inches != null && (inches < 2.5 || inches > 14)
        out += if (dpiOdd) result("info", txt(R.string.display_r_info), Status.WARN, raw("${m.widthPixels}×${m.heightPixels} · ${m.densityDpi} dpi"), txt(R.string.display_dpi_odd_why, fmt1(inches!!)), info)
        else result("info", txt(R.string.display_r_info), Status.PASS, raw("${m.widthPixels}×${m.heightPixels} · ${m.densityDpi} dpi · ${inches?.let { fmt1(it) + "\"" } ?: ""}"), details = info)

        // Real frame rate
        val fps = DisplayInfo.measureFps()
        val hz = disp.refreshRate.toDouble()
        out += when {
            fps == null || fps < 1 -> result("refresh", txt(R.string.display_r_refresh), Status.SKIP, txt(R.string.display_refresh_noframes), txt(R.string.display_refresh_noframes_why))
            abs(fps - hz) / hz <= 0.1 -> result("refresh", txt(R.string.display_r_refresh), Status.PASS, txt(R.string.display_refresh_ok, fmt1(fps), fmt1(hz)))
            else -> result("refresh", txt(R.string.display_r_refresh), Status.WARN, txt(R.string.display_refresh_ok, fmt1(fps), fmt1(hz)), txt(R.string.display_refresh_off_why))
        }

        // Touchscreen
        val touch = DisplayInfo.touchscreens(ctx)
        val pm = ctx.packageManager
        val feat = listOf(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND to "jazzhand (5+)", PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT to "distinct", PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH to "multitouch")
            .firstOrNull { pm.hasSystemFeature(it.first) }?.second ?: "none"
        val tDetails = touch.map { dev ->
            val x = dev.getMotionRange(android.view.MotionEvent.AXIS_X); val y = dev.getMotionRange(android.view.MotionEvent.AXIS_Y)
            d(dev.name, "vendor ${"%04x".format(dev.vendorId)} product ${"%04x".format(dev.productId)}, x 0–${x?.max?.toInt()}, y 0–${y?.max?.toInt()}${if (dev.isExternal) ", external" else ""}")
        } + d("Multitouch feature", feat)
        out += when {
            touch.isEmpty() && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) -> result("touchscreen", txt(R.string.display_r_touchscreen), Status.FAIL, txt(R.string.display_touch_none), txt(R.string.display_touch_none_why), tDetails)
            touch.isEmpty() -> result("touchscreen", txt(R.string.display_r_touchscreen), Status.INFO, txt(R.string.display_touch_none), details = tDetails)
            else -> {
                // Axis range much smaller or larger than the panel means the driver is not scaled (touches land in a corner).
                val dev = touch.first()
                val xr = dev.getMotionRange(android.view.MotionEvent.AXIS_X)?.max ?: 0f
                val yr = dev.getMotionRange(android.view.MotionEvent.AXIS_Y)?.max ?: 0f
                val short = minOf(m.widthPixels, m.heightPixels).toFloat(); val long = maxOf(m.widthPixels, m.heightPixels).toFloat()
                val axisShort = minOf(xr, yr); val axisLong = maxOf(xr, yr)
                val scaled = axisShort > 0 && abs(axisShort + 1 - short) / short < 0.05 && abs(axisLong + 1 - long) / long < 0.05
                if (axisShort > 0 && !scaled) result("touchscreen", txt(R.string.display_r_touchscreen), Status.INFO, raw(dev.name), txt(R.string.display_touch_axis_why, xr.toInt(), yr.toInt()), tDetails)
                else result("touchscreen", txt(R.string.display_r_touchscreen), Status.PASS, raw("${dev.name} · $feat"), details = tDetails)
            }
        }

        // Backlight
        val bl = withContext(Dispatchers.IO) { DisplayInfo.backlight() }
        val (sb, mode) = DisplayInfo.settingsBrightness(ctx)
        val bDetails = bl.map { d(it.node, "brightness ${it.brightness ?: "?"} / max ${it.max ?: "?"}${it.actual?.let { a -> ", actual $a" } ?: ""}") } +
            listOf(d("Settings brightness", sb), d("Auto brightness", mode?.let { if (it == 1) "on" else "off" }))
        out += when {
            bl.isEmpty() -> result("backlight", txt(R.string.display_r_backlight), Status.INFO, txt(R.string.display_bl_none), txt(R.string.display_bl_none_why), bDetails)
            bl.all { it.brightness == null } -> result("backlight", txt(R.string.display_r_backlight), Status.INFO, txt(R.string.display_bl_unreadable, bl.size), txt(R.string.display_bl_none_why), bDetails)
            else -> result("backlight", txt(R.string.display_r_backlight), Status.PASS, raw(bl.first { it.brightness != null }.let { "${it.node.substringAfterLast('/')} ${it.brightness}/${it.max}" }), details = bDetails)
        }
        return out
    }

    @Composable
    override fun Content() {
        TouchCard()
        ColorsCard()
        BrightnessCard()
        RefreshCard()
    }

    @Composable
    private fun TouchCard() {
        var multi by remember { mutableStateOf(false) }
        var grid by remember { mutableStateOf(false) }
        val ctx = LocalContext.current
        val names = remember { DisplayInfo.touchscreens(ctx).joinToString { it.name } }
        SectionCard(stringResource(R.string.display_sec_touch), icon = Icons.Outlined.TouchApp, subtitle = names.ifEmpty { null }) {
            Hint(stringResource(R.string.display_touch_help))
            ButtonRow {
                FilledTonalButton(onClick = { multi = true }) { Text(stringResource(R.string.display_btn_multitouch)) }
                FilledTonalButton(onClick = { grid = true }) { Text(stringResource(R.string.display_btn_grid)) }
            }
            GuidedCheck(this, "touch_accuracy", txt(R.string.display_r_touch_accuracy), stringResource(R.string.display_q_touch_accuracy))
        }
        if (multi) FullscreenDialog(onDismiss = { multi = false }) {
            MultiTouchTest { max ->
                multi = false
                if (max > 0) ResultStore.put(
                    result(
                        "multitouch", txt(R.string.display_r_multitouch), if (max >= 2) Status.PASS else Status.INFO,
                        txt(R.string.display_multitouch_seen, max), if (max < 2) txt(R.string.display_multitouch_one_why) else null,
                        listOf(d("Max simultaneous touches", max)), Kind.GUIDED,
                    ),
                )
            }
        }
        if (grid) FullscreenDialog(onDismiss = { grid = false }) {
            GridTest { covered, total ->
                grid = false
                if (covered > 0) ResultStore.put(
                    result(
                        "touch_grid", txt(R.string.display_r_grid), if (covered == total) Status.PASS else Status.WARN,
                        txt(R.string.display_grid_result, covered, total),
                        if (covered < total) txt(R.string.display_grid_partial_why) else null,
                        listOf(d("Cells", "$covered / $total")), Kind.GUIDED,
                    ),
                )
            }
        }
    }

    @Composable
    private fun ColorsCard() {
        var show by remember { mutableStateOf(false) }
        SectionCard(stringResource(R.string.display_sec_colors), icon = Icons.Outlined.Palette) {
            Hint(stringResource(R.string.display_colors_help))
            FilledTonalButton(onClick = { show = true }) { Text(stringResource(R.string.display_btn_colors)) }
            GuidedCheck(this, "colors", txt(R.string.display_r_colors), stringResource(R.string.display_q_colors))
        }
        if (show) FullscreenDialog(onDismiss = { show = false }) { ColorTest { show = false } }
    }

    @Composable
    private fun BrightnessCard() {
        val ctx = LocalContext.current
        val activity = remember { ctx.findActivity() }
        var level by remember { mutableFloatStateOf(-1f) }
        var minSeen by remember { mutableStateOf<Long?>(null) }
        var maxSeen by remember { mutableStateOf<Long?>(null) }
        var lowTried by remember { mutableStateOf(false) }
        var highTried by remember { mutableStateOf(false) }
        val bl = rememberPolled(300) { DisplayInfo.backlight().firstOrNull { it.brightness != null } }.value
        DisposableEffect(activity) {
            onDispose {
                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
            }
        }
        LaunchedEffect(bl?.brightness) {
            val b = bl?.brightness ?: return@LaunchedEffect
            if (level < 0) return@LaunchedEffect
            minSeen = minOf(minSeen ?: b, b); maxSeen = maxOf(maxSeen ?: b, b)
            val lo = minSeen!!; val hi = maxSeen!!
            if (lowTried && highTried && hi > lo) {
                ResultStore.put(
                    result(
                        "backlight_control", txt(R.string.display_r_backlight_control), Status.PASS,
                        txt(R.string.display_bl_follows, lo, hi, bl.max ?: 0), details = listOf(d("Node", bl.node), d("Range seen", "$lo – $hi")), kind = Kind.GUIDED,
                    ),
                )
            }
        }
        SectionCard(stringResource(R.string.display_sec_brightness), icon = Icons.Outlined.BrightnessMedium) {
            Hint(stringResource(R.string.display_brightness_help))
            Slider(
                value = if (level < 0) 0.5f else level,
                onValueChange = { v ->
                    level = v
                    if (v < 0.1f) lowTried = true
                    if (v > 0.9f) highTried = true
                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) } }
                },
            )
            Row {
                Metric(if (level < 0) "—" else "${(level * 100).toInt()} %", stringResource(R.string.display_m_requested), Modifier.weight(1f))
                Metric(bl?.let { "${it.brightness}/${it.max ?: "?"}" } ?: "—", stringResource(R.string.display_m_sysfs), Modifier.weight(1f))
            }
            if (bl == null) Hint(stringResource(R.string.display_bl_none_why))
            GuidedCheck(this, "brightness", txt(R.string.display_r_brightness), stringResource(R.string.display_q_brightness))
        }
    }

    @Composable
    private fun RefreshCard() {
        val ctx = LocalContext.current
        val activity = remember { ctx.findActivity() }
        val disp = remember { DisplayInfo.display(ctx) }
        val modes = remember { disp?.supportedModes?.toList().orEmpty() }
        var selected by remember { mutableIntStateOf(0) }
        var fps by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            var count = 0
            var t0 = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { now ->
                    count++
                    if (now - t0 >= 1_000_000_000L) { fps = count * 1e9f / (now - t0); count = 0; t0 = now }
                }
            }
        }
        DisposableEffect(activity) {
            onDispose { activity?.window?.let { w -> w.attributes = w.attributes.apply { preferredDisplayModeId = 0 } } }
        }
        SectionCard(stringResource(R.string.display_sec_refresh), icon = Icons.Outlined.Speed) {
            Row {
                Metric(fmt1(fps.toDouble()), stringResource(R.string.display_m_fps), Modifier.weight(1f))
                Metric(disp?.let { fmt1(it.refreshRate.toDouble()) } ?: "—", stringResource(R.string.display_m_hz), Modifier.weight(1f))
            }
            if (modes.size > 1) {
                Hint(stringResource(R.string.display_modes_help))
                ButtonRow {
                    FilterChip(selected == 0, { selected = 0; activity?.window?.let { w -> w.attributes = w.attributes.apply { preferredDisplayModeId = 0 } } }, label = { Text(stringResource(R.string.display_mode_auto)) })
                    modes.forEach { mo ->
                        FilterChip(selected == mo.modeId, {
                            selected = mo.modeId
                            activity?.window?.let { w -> w.attributes = w.attributes.apply { preferredDisplayModeId = mo.modeId } }
                        }, label = { Text("${mo.physicalWidth}×${mo.physicalHeight} ${fmt1(mo.refreshRate.toDouble())} Hz") })
                    }
                }
            } else {
                modes.firstOrNull()?.let { KeyValue("Mode", "${it.physicalWidth}×${it.physicalHeight} @ ${fmt1(it.refreshRate.toDouble())} Hz") }
            }
            Hint(stringResource(R.string.display_fps_help))
            GuidedCheck(this, "smoothness", txt(R.string.display_r_smoothness), stringResource(R.string.display_q_smoothness))
        }
    }
}
