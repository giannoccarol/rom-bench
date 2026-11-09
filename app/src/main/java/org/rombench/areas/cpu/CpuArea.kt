package org.rombench.areas.cpu

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Area
import org.rombench.core.AreaGroup
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.fmt1
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.ButtonRow
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.LevelBar
import org.rombench.ui.components.LineChart
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.SeriesBuffer
import org.rombench.ui.components.rememberPolled
import org.rombench.ui.theme.LocalStatusColors
import org.rombench.ui.theme.Mono

object CpuArea : Area {
    override val id = "cpu"
    override val title = R.string.cpu_title
    override val subtitle = R.string.cpu_subtitle
    override val icon: ImageVector = Icons.Outlined.DeveloperBoard
    override val group = AreaGroup.SYSTEM
    override val autoSeconds = 8

    private fun coreLabel(c: Int, parts: Map<Int, String>) = "cpu$c" + (CpuInfo.partName(parts[c])?.let { " ($it)" } ?: "")

    override suspend fun autoCheck(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TestResult>()
        val (info, parts) = CpuInfo.cpuinfo()
        val possible = CpuInfo.possible()
        val idleCores = CpuInfo.cores()
        val policies = CpuInfo.policies()

        // SoC
        val partNames = possible.mapNotNull { CpuInfo.partName(parts[it]) }.groupingBy { it }.eachCount()
        out += result(
            "soc", txt(R.string.cpu_r_soc), Status.INFO,
            raw(listOfNotNull(info["Hardware"], partNames.entries.joinToString(" + ") { "${it.value}× ${it.key}" }.ifEmpty { null }, "${possible.size} cores").joinToString(" · ")),
            details = listOf(d("Hardware", info["Hardware"]), d("Processor", info["Processor"] ?: info["model name"]), d("ro.board.platform", org.rombench.core.Sys.prop("ro.board.platform")), d("possible", possible.joinToString(",")), d("present", CpuInfo.present().joinToString(",")), d("Features", info["Features"])),
        )

        // Load all cores, sampling frequencies and online state.
        val maxSeen = mutableMapOf<Int, Long>()
        val onlineUnderLoad = mutableSetOf<Int>()
        val load = CpuLoad()
        try {
            repeat(12) {
                delay(250)
                CpuInfo.cores().forEach { c ->
                    if (c.online) onlineUnderLoad += c.cpu
                    c.cur?.let { f -> if (f > (maxSeen[c.cpu] ?: 0)) maxSeen[c.cpu] = f }
                }
            }
        } finally {
            load.stop()
        }

        // Cores online
        val offlineIdle = idleCores.filter { !it.online }.map { it.cpu }
        val offlineLoad = possible.filter { it !in onlineUnderLoad }
        val coreDetails = idleCores.map { c -> d(coreLabel(c.cpu, parts), if (c.online) "online" else if (c.cpu in onlineUnderLoad) "offline at idle, online under load" else "offline") }
        out += when {
            offlineLoad.isNotEmpty() -> result("cores", txt(R.string.cpu_r_cores), Status.WARN, txt(R.string.cpu_cores_offline, offlineLoad.size, possible.size, offlineLoad.joinToString(",")), txt(R.string.cpu_cores_offline_why), coreDetails)
            offlineIdle.isNotEmpty() -> result("cores", txt(R.string.cpu_r_cores), Status.PASS, txt(R.string.cpu_cores_hotplug, possible.size, offlineIdle.size), txt(R.string.cpu_cores_hotplug_why), coreDetails)
            else -> result("cores", txt(R.string.cpu_r_cores), Status.PASS, txt(R.string.cpu_cores_all, possible.size), details = coreDetails)
        }

        // Frequency limits and governor per cluster
        policies.forEach { p ->
            val name = p.name
            val det = listOf(
                d("CPUs", p.cpus.joinToString(",")), d("cpuinfo", "${mhz(p.hwMin)} – ${mhz(p.hwMax)}"),
                d("scaling", "${mhz(p.scalingMin)} – ${mhz(p.scalingMax)}"), d("governor", p.governor),
                d("available governors", p.governors), d("frequencies", p.frequencies),
                d("max seen under load", mhz(p.cpus.mapNotNull { maxSeen[it] }.maxOrNull())),
            )
            val peak = p.cpus.mapNotNull { maxSeen[it] }.maxOrNull()
            val label = "${name} (${p.cpus.firstOrNull()?.let { CpuInfo.partName(parts[it]) } ?: "cpu${p.cpus.joinToString(",")}"})"
            out += when {
                p.hwMax == null -> result("freq_$name", txt(R.string.cpu_r_freq, label), Status.WARN, txt(R.string.cpu_freq_unreadable), txt(R.string.cpu_freq_unreadable_why), det)
                p.scalingMax != null && p.scalingMax < p.hwMax * 0.98 ->
                    result("freq_$name", txt(R.string.cpu_r_freq, label), Status.WARN, txt(R.string.cpu_freq_capped, mhz(p.scalingMax), mhz(p.hwMax)), txt(R.string.cpu_freq_capped_why), det)
                peak != null && p.scalingMin != null && peak <= p.scalingMin && (p.scalingMax ?: 0) > p.scalingMin ->
                    result("freq_$name", txt(R.string.cpu_r_freq, label), Status.WARN, txt(R.string.cpu_freq_stuck, mhz(peak)), txt(R.string.cpu_freq_stuck_why, p.governor ?: "?"), det)
                p.governor in listOf("performance", "powersave", "userspace") ->
                    result("freq_$name", txt(R.string.cpu_r_freq, label), Status.INFO, txt(R.string.cpu_freq_fixed_gov, p.governor ?: "", mhz(peak)), txt(R.string.cpu_freq_fixed_gov_why), det)
                else -> result("freq_$name", txt(R.string.cpu_r_freq, label), Status.PASS, txt(R.string.cpu_freq_ok, mhz(peak), mhz(p.hwMax), p.governor ?: "?"), details = det)
            }
        }
        if (policies.isEmpty()) out += result("freq", txt(R.string.cpu_r_freq, "cpufreq"), Status.WARN, txt(R.string.cpu_freq_unreadable), txt(R.string.cpu_freq_unreadable_why))

        // Thermal
        val zones = Thermal.zones()
        val readable = zones.filter { it.tempC != null }
        val pm = ctx.getSystemService(PowerManager::class.java)
        val status = runCatching { pm.currentThermalStatus }.getOrNull()
        val headroom = runCatching { pm.getThermalHeadroom(10) }.getOrNull()?.takeIf { !it.isNaN() }
        val tDetails = listOf(d("PowerManager thermal status", status), d("Thermal headroom (10 s)", headroom?.let { fmt1(it.toDouble()) })) +
            readable.sortedByDescending { it.tempC }.take(25).map { d("${it.name} ${it.type}", "${fmt1(it.tempC!!)} °C") }
        val hottest = readable.maxByOrNull { it.tempC!! }
        val bogus = readable.filter { it.tempC!! <= -30 || it.tempC > 150 }
        out += when {
            zones.isEmpty() -> result("thermal", txt(R.string.cpu_r_thermal), Status.WARN, txt(R.string.cpu_thermal_none), txt(R.string.cpu_thermal_none_why), tDetails)
            readable.isEmpty() -> result("thermal", txt(R.string.cpu_r_thermal), Status.WARN, txt(R.string.cpu_thermal_unreadable, zones.size), txt(R.string.cpu_thermal_unreadable_why), tDetails)
            hottest != null && hottest.tempC!! > 80 && hottest.tempC < 150 -> result("thermal", txt(R.string.cpu_r_thermal), Status.WARN, txt(R.string.cpu_thermal_hot, hottest.type, fmt1(hottest.tempC)), txt(R.string.cpu_thermal_hot_why), tDetails)
            bogus.isNotEmpty() -> result("thermal", txt(R.string.cpu_r_thermal), Status.INFO, txt(R.string.cpu_thermal_ok, readable.size, hottest?.type ?: "", hottest?.tempC?.let { fmt1(it) } ?: ""), txt(R.string.cpu_thermal_bogus_why, bogus.joinToString { it.type }), tDetails)
            else -> result("thermal", txt(R.string.cpu_r_thermal), Status.PASS, txt(R.string.cpu_thermal_ok, readable.size, hottest?.type ?: "", hottest?.tempC?.let { fmt1(it) } ?: ""), details = tDetails)
        }
        val hal = org.rombench.core.Sys.prop("init.svc.vendor.thermal-hal-2-0").ifBlank { org.rombench.core.Sys.prop("init.svc.thermal-hal") }
        if (status == null || status == PowerManager.THERMAL_STATUS_NONE && headroom == null) {
            out += result("thermal_hal", txt(R.string.cpu_r_thermal_hal), Status.INFO, txt(R.string.cpu_thermal_hal_none), txt(R.string.cpu_thermal_hal_none_why), listOf(d("thermal HAL service", hal.ifBlank { "—" })))
        } else {
            out += result("thermal_hal", txt(R.string.cpu_r_thermal_hal), Status.PASS, txt(R.string.cpu_thermal_hal_ok, status, headroom?.let { fmt1(it.toDouble()) } ?: "—"), details = listOf(d("thermal HAL service", hal.ifBlank { "—" })))
        }

        // GPU
        val gl = runCatching { Gpu.gl() }.getOrNull()
        val pmg = ctx.packageManager
        val vk = pmg.systemAvailableFeatures.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
        val gDetails = listOf(
            d("GL_VENDOR", gl?.vendor), d("GL_RENDERER", gl?.renderer), d("GL_VERSION", gl?.version), d("GL extensions", gl?.extensions),
            d("Vulkan", vk?.let { "${it shr 22}.${(it shr 12) and 0x3ff}.${it and 0xfff}" } ?: "not declared"),
            d("ro.hardware.egl", org.rombench.core.Sys.prop("ro.hardware.egl")), d("ro.hardware.vulkan", org.rombench.core.Sys.prop("ro.hardware.vulkan")),
        ) + Gpu.sysfs().map { (k, v) -> d(k, v) }
        val emulator = org.rombench.core.Sys.prop("ro.kernel.qemu") == "1" || org.rombench.core.Sys.prop("ro.boot.qemu") == "1"
        out += when {
            gl == null -> result("gpu", txt(R.string.cpu_r_gpu), Status.FAIL, txt(R.string.cpu_gpu_egl_fail), txt(R.string.cpu_gpu_egl_fail_why), gDetails)
            Gpu.isSoftware(gl.renderer) && !emulator -> result("gpu", txt(R.string.cpu_r_gpu), Status.FAIL, raw(gl.renderer), txt(R.string.cpu_gpu_soft_why), gDetails)
            else -> result("gpu", txt(R.string.cpu_r_gpu), Status.PASS, raw("${gl.renderer} · ${gl.version.take(40)}"), details = gDetails)
        }
        out
    }

    @Composable
    override fun Content() {
        CoresCard()
        StressCard()
        ThermalCard()
        GpuCard()
    }

    @Composable
    private fun CoresCard() {
        val parts = remember { CpuInfo.cpuinfo().second }
        val cores = rememberPolled(1000) { CpuInfo.cores() }.value
        val policies = rememberPolled(5000) { CpuInfo.policies() }.value
        val sc = LocalStatusColors.current
        SectionCard(stringResource(R.string.cpu_sec_cores), icon = Icons.Outlined.DeveloperBoard) {
            cores?.forEach { c ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(coreLabel(c.cpu, parts), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(if (c.online) mhz(c.cur) else stringResource(R.string.cpu_offline), style = Mono, color = if (c.online) MaterialTheme.colorScheme.onSurface else sc.warn)
                    }
                    val max = (c.scalingMax ?: c.hwMax ?: 1).coerceAtLeast(1)
                    LevelBar(if (c.online) (c.cur ?: 0).toFloat() / max else 0f, color = if (c.online) MaterialTheme.colorScheme.primary else sc.skip)
                }
            } ?: Hint(stringResource(R.string.loading))
            policies?.forEach { p -> Hint("${p.name}: ${p.governor ?: "?"} · ${mhz(p.scalingMin)}–${mhz(p.scalingMax)} (hw ${mhz(p.hwMax)})") }
        }
    }

    @Composable
    private fun ThermalCard() {
        var all by rememberSaveable { mutableStateOf(false) }
        val zones = rememberPolled(2000) { Thermal.zones() }.value
        val cooling = rememberPolled(3000) { Thermal.cooling() }.value
        val sc = LocalStatusColors.current
        SectionCard(stringResource(R.string.cpu_sec_thermal), icon = Icons.Outlined.LocalFireDepartment, subtitle = zones?.let { stringResource(R.string.cpu_zones, it.size) }) {
            val list = zones?.filter { it.tempC != null }?.sortedByDescending { it.tempC }.orEmpty()
            (if (all) list else list.take(8)).forEach { z ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${z.type}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${fmt1(z.tempC!!)} °C", style = Mono, color = when { z.tempC > 80 -> sc.fail; z.tempC > 60 -> sc.warn; else -> MaterialTheme.colorScheme.onSurface })
                }
            }
            if (list.size > 8) OutlinedButton(onClick = { all = !all }) { Text(stringResource(if (all) R.string.cpu_show_less else R.string.cpu_show_all)) }
            val active = cooling?.filter { (it.cur ?: 0) > 0 }.orEmpty()
            if (!cooling.isNullOrEmpty()) Hint(stringResource(R.string.cpu_cooling, cooling.size, active.size) + active.joinToString(prefix = if (active.isEmpty()) "" else ": ") { "${it.type} ${it.cur}/${it.max}" })
        }
    }

    @Composable
    private fun GpuCard() {
        val gl by produceState<GlInfo?>(null) { value = withContext(Dispatchers.IO) { runCatching { Gpu.gl() }.getOrNull() } }
        val freq = rememberPolled(1000) { Gpu.curFreqHz() to Gpu.busyPercent() }.value
        SectionCard(stringResource(R.string.cpu_sec_gpu), icon = Icons.Outlined.ViewInAr) {
            KeyValue("GL_RENDERER", gl?.renderer ?: "—")
            KeyValue("GL_VERSION", gl?.version ?: "—")
            KeyValue("GL_VENDOR", gl?.vendor ?: "—")
            freq?.first?.let { KeyValue("Frequency", "${it / 1_000_000} MHz") }
            freq?.second?.let { KeyValue("Busy", "$it %") }
        }
    }

    @Composable
    private fun StressCard() {
        var seconds by rememberSaveable { mutableIntStateOf(60) }
        var running by remember { mutableStateOf(false) }
        var elapsed by remember { mutableIntStateOf(0) }
        val chart = remember { SeriesBuffer(180, 2) }
        val sc = LocalStatusColors.current
        DisposableEffect(Unit) { onDispose { running = false } }
        LaunchedEffect(running) {
            if (!running) return@LaunchedEffect
            chart.clear(); elapsed = 0
            val load = withContext(Dispatchers.Default) { CpuLoad() }
            val maxHw = CpuInfo.cores().mapNotNull { it.hwMax }.maxOrNull()?.coerceAtLeast(1) ?: 1
            var peakTemp = Double.NEGATIVE_INFINITY
            val freqs = mutableListOf<Double>()
            val offline = mutableSetOf<Int>()
            var completed = false
            try {
                while (isActive && running && elapsed < seconds) {
                    delay(1000); elapsed++
                    val (cores, hot) = withContext(Dispatchers.IO) { CpuInfo.cores() to Thermal.zones().mapNotNull { it.tempC }.filter { it < 150 }.maxOrNull() }
                    cores.filter { !it.online }.forEach { offline += it.cpu }
                    val avg = cores.filter { it.online }.mapNotNull { it.cur }.average().takeIf { !it.isNaN() } ?: 0.0
                    val pct = avg * 100.0 / maxHw
                    freqs += pct
                    hot?.let { peakTemp = maxOf(peakTemp, it) }
                    chart.add(pct.toFloat(), (hot ?: Double.NaN).toFloat())
                }
                completed = elapsed >= seconds
            } finally {
                withContext(Dispatchers.Default) { load.stop() }
                running = false
            }
            if (freqs.size >= 10) {
                val first = freqs.take(5).average()
                val last = freqs.takeLast(5).average()
                val throttled = last < first * 0.7
                val det = listOf(
                    d("Duration", "$elapsed s"), d("Average frequency first 5 s", "${fmt1(first)} % of max"),
                    d("Average frequency last 5 s", "${fmt1(last)} % of max"), d("Peak temperature", if (peakTemp.isFinite()) "${fmt1(peakTemp)} °C" else "—"),
                    d("Cores offline during the run", offline.sorted().joinToString(",").ifEmpty { "none" }),
                )
                ResultStore.put(
                    result(
                        "stress", txt(R.string.cpu_r_stress), when { !completed -> Status.INFO; throttled -> Status.WARN; else -> Status.PASS },
                        txt(R.string.cpu_stress_summary, elapsed, fmt1(last), if (peakTemp.isFinite()) fmt1(peakTemp) else "—"),
                        if (throttled) txt(R.string.cpu_stress_throttled_why) else if (!completed) txt(R.string.cpu_stress_stopped) else null,
                        det, org.rombench.core.Kind.GUIDED,
                    ),
                )
            }
        }
        SectionCard(stringResource(R.string.cpu_sec_stress), icon = Icons.Outlined.Speed, subtitle = stringResource(R.string.cpu_stress_help)) {
            ButtonRow {
                listOf(30, 60, 180).forEach { s -> FilterChip(seconds == s, { seconds = s }, enabled = !running, label = { Text("$s s") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) OutlinedButton(onClick = { running = false }) { Text(stringResource(R.string.stop)) }
                else Button(onClick = { running = true }) { Text(stringResource(R.string.cpu_stress_start)) }
                Spacer(Modifier.width(12.dp))
                if (running || elapsed > 0) Metric("$elapsed / $seconds s", stringResource(R.string.cpu_stress_elapsed))
            }
            if (chart.size > 0) {
                SubHeader(stringResource(R.string.cpu_stress_chart))
                LineChart(chart, labels = listOf("freq %", "°C"), colors = listOf(MaterialTheme.colorScheme.primary, sc.fail))
            }
        }
    }
}
