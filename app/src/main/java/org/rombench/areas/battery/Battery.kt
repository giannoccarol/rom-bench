package org.rombench.areas.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import org.rombench.core.Sys
import kotlin.math.abs

/** One reading of the battery state from the sticky broadcast and BatteryManager. */
data class BatterySnapshot(
    val present: Boolean,
    val level: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val voltageMv: Int,
    val tempTenths: Int,
    val technology: String,
    val currentNowUa: Int?,
    val currentAvgUa: Int?,
    val chargeCounterUah: Int?,
    val energyNwh: Long?,
    val chargeTimeRemainingMs: Long?,
) {
    val tempC get() = tempTenths / 10.0
    val charging get() = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val isPlugged get() = plugged != 0

    /** Current in mA with the AOSP sign convention (positive = into the battery), if reported. */
    val currentMa: Double? get() = currentNowUa?.let { it / 1000.0 }

    /** Battery power in W from voltage and current. */
    val powerW: Double? get() = currentNowUa?.let { voltageMv / 1000.0 * it / 1_000_000.0 }

    companion object {
        private fun BatteryManager.prop(id: Int): Int? = getIntProperty(id).takeIf { it != Int.MIN_VALUE && it != 0 }

        fun read(ctx: Context): BatterySnapshot {
            val i: Intent? = ContextCompat.registerReceiver(ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val level = i?.let { val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0 && s > 0) l * 100 / s else -1 } ?: -1
            return BatterySnapshot(
                present = i?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) ?: false,
                level = level,
                status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN,
                plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
                health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN,
                voltageMv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
                tempTenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
                technology = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "",
                currentNowUa = bm?.prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                currentAvgUa = bm?.prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
                chargeCounterUah = bm?.prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                energyNwh = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.takeIf { it != Long.MIN_VALUE && it != 0L },
                chargeTimeRemainingMs = bm?.computeChargeTimeRemaining()?.takeIf { it > 0 },
            )
        }
    }
}

fun statusName(s: Int) = when (s) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
    BatteryManager.BATTERY_STATUS_FULL -> "full"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
    else -> "unknown"
}

fun pluggedName(p: Int) = when {
    p == 0 -> "unplugged"
    else -> listOfNotNull(
        "AC".takeIf { p and BatteryManager.BATTERY_PLUGGED_AC != 0 },
        "USB".takeIf { p and BatteryManager.BATTERY_PLUGGED_USB != 0 },
        "wireless".takeIf { p and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 },
        "dock".takeIf { p and 8 != 0 },
    ).joinToString("+").ifEmpty { "other ($p)" }
}

fun healthName(h: Int) = when (h) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
    else -> "unknown"
}

/** A /sys/class/power_supply node with its POWER_SUPPLY_* values (prefix removed, lower case). */
data class PowerSupply(val name: String, val values: Map<String, String>) {
    val type get() = values["type"] ?: ""
    fun long(key: String) = values[key]?.toLongOrNull()
}

object PowerSupplies {
    private const val ROOT = "/sys/class/power_supply"

    fun read(): List<PowerSupply> = Sys.list(ROOT).map { name ->
        val kv = Sys.keyValues("$ROOT/$name/uevent").mapKeys { it.key.removePrefix("POWER_SUPPLY_").lowercase() }
        val values = if (kv.isNotEmpty()) kv else {
            // Some kernels deny uevent but allow the single attributes.
            listOf("type", "online", "status", "capacity", "voltage_now", "current_now", "current_max", "temp", "charge_full", "charge_full_design", "cycle_count", "health", "charge_type", "input_current_limit", "usb_type", "real_type")
                .mapNotNull { a -> Sys.line("$ROOT/$name/$a")?.let { a to it } }.toMap()
        }
        PowerSupply(name, values)
    }.filter { it.values.isNotEmpty() || Sys.exists("$ROOT/${it.name}") }

    fun battery(list: List<PowerSupply>) = list.firstOrNull { it.type.equals("Battery", true) } ?: list.firstOrNull { it.name == "battery" }
}

/** True when a current value looks like mA reported where µA is expected (screen-on phones draw >50 mA). */
fun looksLikeMilliamps(ua: Int) = abs(ua) in 1..20_000
