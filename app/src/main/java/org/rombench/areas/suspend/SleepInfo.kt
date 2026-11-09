package org.rombench.areas.suspend

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.rombench.core.Sys

/** Readers for suspend related sysfs/procfs nodes. Never touches debugfs (Sys refuses it). */
internal object SleepInfo {
    data class Clocks(val elapsed: Long, val uptime: Long) {
        val asleep get() = (elapsed - uptime).coerceAtLeast(0)
        val fraction get() = if (elapsed > 0) asleep.toDouble() / elapsed else 0.0
    }

    fun clocks() = Clocks(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())

    data class Source(
        val name: String,
        val activeCount: Long,
        val eventCount: Long,
        val wakeupCount: Long,
        /** Time the source has been active in its current activation, 0 when inactive. */
        val activeMs: Long,
        val totalMs: Long,
        val maxMs: Long,
        val preventSuspendMs: Long,
    ) {
        val active get() = activeMs > 0
    }

    data class Sources(val path: String, val list: List<Source>)

    /** Kernel wakeup sources from /sys/class/wakeup (4.19+ / 5.4+) or the old /proc/wakelocks. */
    fun sources(): Sources? {
        val dirs = Sys.list("/sys/class/wakeup")
        if (dirs.isNotEmpty()) {
            val list = dirs.mapNotNull { dname ->
                val b = "/sys/class/wakeup/$dname"
                val name = Sys.line("$b/name") ?: return@mapNotNull null
                Source(
                    name,
                    Sys.long("$b/active_count") ?: 0, Sys.long("$b/event_count") ?: 0, Sys.long("$b/wakeup_count") ?: 0,
                    Sys.long("$b/active_time_ms") ?: 0, Sys.long("$b/total_time_ms") ?: 0, Sys.long("$b/max_time_ms") ?: 0,
                    Sys.long("$b/prevent_suspend_time_ms") ?: 0,
                )
            }
            if (list.isNotEmpty()) return Sources("/sys/class/wakeup", list)
        }
        val proc = Sys.read("/proc/wakelocks") ?: return null
        // name count expire_count wake_count active_since total_time sleep_time max_time last_change (ns)
        val list = proc.lineSequence().drop(1).mapNotNull { l ->
            val f = l.split('\t').map { it.trim() }
            if (f.size < 8) return@mapNotNull null
            fun n(i: Int) = f.getOrNull(i)?.toLongOrNull() ?: 0L
            Source(f[0].trim('"'), n(1), n(1), n(3), n(4) / 1_000_000, n(5) / 1_000_000, n(7) / 1_000_000, n(6) / 1_000_000)
        }.toList()
        return if (list.isEmpty()) null else Sources("/proc/wakelocks", list)
    }

    /** Userspace wakelocks that the framework itself holds while the screen is on. */
    fun isFrameworkLock(name: String) =
        name.startsWith("PowerManager") || name.startsWith("RomBench") || name == "SensorService_wakelock" ||
            name.startsWith("*") || name.contains("suspend_backoff")

    val powerFiles = listOf("/sys/power/state", "/sys/power/mem_sleep", "/sys/power/wakeup_count", "/sys/power/autosleep", "/sys/power/pm_freeze_timeout")

    fun suspendStats(): Map<String, String> {
        val dir = "/sys/power/suspend_stats"
        return Sys.list(dir).mapNotNull { f -> Sys.line("$dir/$f")?.let { f to it } }.toMap()
    }

    fun lastResumeReason(): String? = Sys.read("/sys/kernel/wakeup_reasons/last_resume_reason", 4096)
        ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(4)?.joinToString(" | ")?.takeIf { it.isNotEmpty() }

    fun lastSuspendTime(): String? = Sys.line("/sys/kernel/wakeup_reasons/last_suspend_time")

    fun wakeupCount(): Long? = Sys.long("/sys/power/wakeup_count")

    /** BatteryManager.EXTRA_PLUGGED from the sticky battery broadcast (0 = on battery). */
    fun plugged(ctx: Context): Int = try {
        ContextCompat.registerReceiver(ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    } catch (e: Exception) {
        0
    }

    fun pluggedName(p: Int): String = when (p) {
        0 -> "battery"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        8 -> "dock"
        else -> "plugged $p"
    }

    @SuppressLint("DiscouragedApi")
    fun sysBool(name: String): Boolean? = try {
        val id = Resources.getSystem().getIdentifier(name, "bool", "android")
        if (id == 0) null else Resources.getSystem().getBoolean(id)
    } catch (e: Exception) {
        null
    }

    fun fmtDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    fun pct(f: Double) = String.format(java.util.Locale.US, "%.1f%%", f * 100)
}
