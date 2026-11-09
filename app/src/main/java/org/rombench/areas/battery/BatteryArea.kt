package org.rombench.areas.battery

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import org.rombench.core.fmt2
import org.rombench.core.raw
import org.rombench.core.result
import org.rombench.core.txt
import org.rombench.ui.SubHeader
import org.rombench.ui.components.GuidedCheck
import org.rombench.ui.components.Hint
import org.rombench.ui.components.KeyValue
import org.rombench.ui.components.LineChart
import org.rombench.ui.components.Metric
import org.rombench.ui.components.SectionCard
import org.rombench.ui.components.SeriesBuffer
import kotlin.math.abs

object BatteryArea : Area {
    override val id = "battery"
    override val title = R.string.battery_title
    override val subtitle = R.string.battery_subtitle
    override val icon: ImageVector = Icons.Outlined.BatteryChargingFull
    override val group = AreaGroup.SYSTEM
    override val autoSeconds = 4

    private fun snapshotDetails(s: BatterySnapshot) = listOf(
        d("Level", "${s.level} %"), d("Status", statusName(s.status)), d("Plugged", pluggedName(s.plugged)),
        d("Health", healthName(s.health)), d("Voltage", "${s.voltageMv} mV"), d("Temperature", "${fmt1(s.tempC)} °C"),
        d("Technology", s.technology), d("CURRENT_NOW", s.currentNowUa?.let { "$it µA" }),
        d("CURRENT_AVERAGE", s.currentAvgUa?.let { "$it µA" }), d("CHARGE_COUNTER", s.chargeCounterUah?.let { "$it µAh" }),
        d("ENERGY_COUNTER", s.energyNwh?.let { "$it nWh" }), d("Charge time remaining", s.chargeTimeRemainingMs?.let { "${it / 60000} min" }),
    )

    override suspend fun autoCheck(ctx: Context): List<TestResult> {
        val out = mutableListOf<TestResult>()
        val samples = mutableListOf<BatterySnapshot>()
        repeat(7) {
            samples += BatterySnapshot.read(ctx)
            if (it < 6) delay(500)
        }
        val s = samples.last()
        val supplies = withContext(Dispatchers.IO) { PowerSupplies.read() }
        val bat = PowerSupplies.battery(supplies)

        // Presence and level
        out += if (!s.present || s.level < 0) {
            result("present", txt(R.string.battery_r_present), Status.FAIL, txt(R.string.battery_absent), txt(R.string.battery_absent_why), snapshotDetails(s))
        } else {
            result("present", txt(R.string.battery_r_present), Status.PASS, txt(R.string.battery_level, s.level, statusName(s.status)), details = snapshotDetails(s))
        }

        // Voltage: EXTRA_VOLTAGE is mV; some HALs pass µV or V.
        val v = s.voltageMv
        out += when {
            v <= 0 -> result("voltage", txt(R.string.battery_r_voltage), Status.WARN, txt(R.string.battery_not_reported), txt(R.string.battery_voltage_missing_why))
            v > 100_000 -> result("voltage", txt(R.string.battery_r_voltage), Status.WARN, raw("$v"), txt(R.string.battery_voltage_uv_why))
            v < 100 -> result("voltage", txt(R.string.battery_r_voltage), Status.WARN, raw("$v"), txt(R.string.battery_voltage_v_why))
            v !in 3000..4600 -> result("voltage", txt(R.string.battery_r_voltage), Status.WARN, raw("${fmt2(v / 1000.0)} V"), txt(R.string.battery_voltage_range_why))
            else -> result("voltage", txt(R.string.battery_r_voltage), Status.PASS, raw("${fmt2(v / 1000.0)} V"))
        }

        // Temperature: tenths of °C.
        val t = s.tempC
        out += when {
            s.tempTenths == 0 -> result("temperature", txt(R.string.battery_r_temp), Status.WARN, txt(R.string.battery_not_reported), txt(R.string.battery_temp_missing_why))
            t < -20 || t > 80 -> result("temperature", txt(R.string.battery_r_temp), Status.WARN, raw("${fmt1(t)} °C"), txt(R.string.battery_temp_odd_why))
            t > 45 -> result("temperature", txt(R.string.battery_r_temp), Status.WARN, raw("${fmt1(t)} °C"), txt(R.string.battery_temp_hot_why))
            else -> result("temperature", txt(R.string.battery_r_temp), Status.PASS, raw("${fmt1(t)} °C"))
        }

        // Current: unit and sign heuristics.
        val currents = samples.mapNotNull { it.currentNowUa }
        val cur = s.currentNowUa
        val curDetails = listOf(d("Samples (µA)", currents.joinToString()), d("Status", statusName(s.status)), d("Plugged", pluggedName(s.plugged)), d("sysfs current_now", bat?.values?.get("current_now")))
        out += when {
            cur == null -> result("current", txt(R.string.battery_r_current), Status.WARN, txt(R.string.battery_not_reported), txt(R.string.battery_current_missing_why), curDetails)
            looksLikeMilliamps(cur) -> result("current", txt(R.string.battery_r_current), Status.WARN, raw("$cur"), txt(R.string.battery_current_ma_why), curDetails)
            s.status == BatteryManager.BATTERY_STATUS_CHARGING && cur < 0 ->
                result("current", txt(R.string.battery_r_current), Status.INFO, raw("${fmt1(cur / 1000.0)} mA"), txt(R.string.battery_current_sign_why), curDetails)
            s.status == BatteryManager.BATTERY_STATUS_DISCHARGING && cur > 0 ->
                result("current", txt(R.string.battery_r_current), Status.INFO, raw("${fmt1(cur / 1000.0)} mA"), txt(R.string.battery_current_sign_why), curDetails)
            currents.size > 3 && currents.distinct().size == 1 ->
                result("current", txt(R.string.battery_r_current), Status.INFO, raw("${fmt1(cur / 1000.0)} mA"), txt(R.string.battery_current_static_why), curDetails)
            else -> result("current", txt(R.string.battery_r_current), Status.PASS, raw("${fmt1(cur / 1000.0)} mA · ${s.powerW?.let { fmt2(abs(it)) } ?: "—"} W"), details = curDetails)
        }

        // Charger
        val usb = supplies.filter { it.type.contains("USB", true) || it.name.startsWith("usb") || it.type.equals("Mains", true) }
        val chDetails = usb.flatMap { p -> p.values.filterKeys { it in CHARGER_KEYS }.map { (k, vv) -> d("${p.name}.$k", vv) } }
        out += when {
            !s.isPlugged -> result("charger", txt(R.string.battery_r_charger), Status.INFO, txt(R.string.battery_unplugged), txt(R.string.battery_unplugged_hint), chDetails)
            s.charging -> result("charger", txt(R.string.battery_r_charger), Status.PASS, txt(R.string.battery_charging_via, pluggedName(s.plugged), cur?.let { fmt1(abs(it) / 1000.0) } ?: "—"), details = chDetails)
            s.level < 95 -> result("charger", txt(R.string.battery_r_charger), Status.WARN, txt(R.string.battery_plugged_not_charging, pluggedName(s.plugged), statusName(s.status)), txt(R.string.battery_plugged_not_charging_why), chDetails)
            else -> result("charger", txt(R.string.battery_r_charger), Status.PASS, txt(R.string.battery_plugged_full, pluggedName(s.plugged)), details = chDetails)
        }

        // Health and capacity from the fuel gauge
        val full = bat?.long("charge_full")
        val design = bat?.long("charge_full_design")
        val cycles = bat?.long("cycle_count")
        val ratio = if (full != null && design != null && design > 0) full * 100.0 / design else null
        val hDetails = listOf(d("Health", healthName(s.health)), d("charge_full", full?.let { "$it µAh" }), d("charge_full_design", design?.let { "$it µAh" }), d("cycle_count", cycles), d("CHARGE_COUNTER", s.chargeCounterUah?.let { "$it µAh" }))
        out += when {
            s.health != BatteryManager.BATTERY_HEALTH_GOOD && s.health != BatteryManager.BATTERY_HEALTH_UNKNOWN ->
                result("health", txt(R.string.battery_r_health), Status.WARN, raw(healthName(s.health)), txt(R.string.battery_health_bad_why), hDetails)
            ratio != null && ratio < 70 -> result("health", txt(R.string.battery_r_health), Status.WARN, txt(R.string.battery_capacity_pct, fmt1(ratio)), txt(R.string.battery_capacity_low_why), hDetails)
            ratio != null -> result("health", txt(R.string.battery_r_health), Status.PASS, txt(R.string.battery_capacity_pct, fmt1(ratio)), details = hDetails)
            else -> result("health", txt(R.string.battery_r_health), Status.INFO, raw(healthName(s.health)), txt(R.string.battery_capacity_unknown_why), hDetails)
        }

        // power_supply nodes
        out += result(
            "supplies", txt(R.string.battery_r_supplies), if (supplies.isEmpty()) Status.WARN else Status.INFO,
            txt(R.string.battery_supplies_count, supplies.size, supplies.joinToString { it.name }),
            if (supplies.isEmpty()) txt(R.string.battery_supplies_none_why) else null,
            supplies.map { p -> d(p.name, listOfNotNull(p.type, p.values["online"]?.let { "online=$it" }, p.values["status"]).joinToString(" ")) },
        )
        return out
    }

    private val CHARGER_KEYS = setOf("online", "type", "usb_type", "real_type", "current_max", "voltage_max", "input_current_limit", "voltage_now", "current_now", "charge_type")

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val current = remember { SeriesBuffer(120, 1) }
        val voltage = remember { SeriesBuffer(120, 1) }
        var snap by remember { mutableStateOf<BatterySnapshot?>(null) }
        var maxCharge by remember { mutableStateOf(0.0) }
        LaunchedEffect(Unit) {
            while (isActive) {
                val s = withContext(Dispatchers.IO) { BatterySnapshot.read(ctx) }
                snap = s
                s.currentMa?.let { current.add(it.toFloat()) }
                if (s.voltageMv in 1..100_000) voltage.add(s.voltageMv / 1000f)
                // Charging test: passes once charging with a real current is observed.
                val ma = s.currentMa
                if (s.isPlugged && s.charging && ma != null && abs(ma) > 50 && abs(ma) > maxCharge + 5) {
                    maxCharge = abs(ma)
                    ResultStore.put(
                        result(
                            "charge_test", txt(R.string.battery_r_charge_test), Status.PASS,
                            txt(R.string.battery_charge_test_ok, pluggedName(s.plugged), fmt1(maxCharge)),
                            details = listOf(d("Max current", "${fmt1(maxCharge)} mA"), d("Voltage", "${s.voltageMv} mV"), d("Level", "${s.level} %")),
                            kind = org.rombench.core.Kind.GUIDED,
                        ),
                    )
                }
                delay(1000)
            }
        }
        val s = snap
        SectionCard(stringResource(R.string.battery_sec_live), icon = Icons.Outlined.ShowChart) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("${s?.level ?: "—"} %", stringResource(R.string.battery_m_level))
                Metric(s?.currentMa?.let { fmt1(it) } ?: "—", "mA")
                Metric(s?.let { fmt2(it.voltageMv / 1000.0) } ?: "—", "V")
                Metric(s?.let { fmt1(it.tempC) } ?: "—", "°C")
            }
            Hint(s?.let { stringResource(R.string.battery_live_line, statusName(it.status), pluggedName(it.plugged), it.powerW?.let { p -> fmt2(abs(p)) } ?: "—") } ?: "")
            SubHeader(stringResource(R.string.battery_chart_current))
            LineChart(current, unit = " mA")
            SubHeader(stringResource(R.string.battery_chart_voltage))
            LineChart(voltage, height = 100.dp, unit = " V")
        }
        SectionCard(stringResource(R.string.battery_sec_charge), icon = Icons.Outlined.ElectricalServices, subtitle = stringResource(R.string.battery_charge_help)) {
            GuidedCheck(this, "charge_speed", txt(R.string.battery_r_charge_speed), stringResource(R.string.battery_q_charge_speed))
            GuidedCheck(this, "charge_pc", txt(R.string.battery_r_charge_pc), stringResource(R.string.battery_q_charge_pc))
        }
        SupplyTable()
    }

    @Composable
    private fun SupplyTable() {
        var open by rememberSaveable { mutableStateOf<String?>(null) }
        val list = org.rombench.ui.components.rememberPolled(2000) { PowerSupplies.read() }.value
        SectionCard(stringResource(R.string.battery_sec_sysfs), subtitle = "/sys/class/power_supply") {
            if (list.isNullOrEmpty()) Hint(stringResource(R.string.battery_supplies_none_why))
            list?.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { open = if (open == p.name) null else p.name }) {
                        Text("${p.name} · ${p.type.ifEmpty { "?" }}${p.values["online"]?.let { " · online=$it" } ?: ""}")
                    }
                    if (open == p.name) p.values.forEach { (k, v) -> KeyValue(k, v) }
                }
            }
        }
    }
}
