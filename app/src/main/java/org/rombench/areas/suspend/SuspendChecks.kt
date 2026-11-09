package org.rombench.areas.suspend

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rombench.R
import org.rombench.core.Detail
import org.rombench.core.Perms
import org.rombench.core.Status
import org.rombench.core.Sys
import org.rombench.core.TestResult
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt

/** Automatic checks of [SuspendArea]. */
internal object SuspendChecks {
    private val A = SuspendArea

    suspend fun run(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        val dump = Perms.hasDump(ctx)
        listOf(
            bootSleep(ctx),
            kernelPower(),
            suspendStats(),
            wakeupSources(ctx),
            powerManager(ctx),
            doze(dump),
            powerDump(dump),
            exactAlarm(ctx),
        )
    }

    fun thermalName(s: Int) = when (s) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "status $s"
    }

    private fun bootSleep(ctx: Context): TestResult {
        val t = txt(R.string.suspend_t_boot)
        val c = SleepInfo.clocks()
        val plugged = SleepInfo.plugged(ctx)
        val det = mutableListOf(
            d("elapsedRealtime", SleepInfo.fmtDur(c.elapsed)),
            d("uptimeMillis", SleepInfo.fmtDur(c.uptime)),
            d("asleep since boot", SleepInfo.fmtDur(c.asleep)),
            d("deep sleep", SleepInfo.pct(c.fraction)),
            d("power source", SleepInfo.pluggedName(plugged)),
        )
        SleepInfo.lastResumeReason()?.let { det += d("last_resume_reason", it) }
        SleepInfo.lastSuspendTime()?.let { det += d("last_suspend_time", it) }
        val summary = txt(R.string.suspend_boot_summary, SleepInfo.pct(c.fraction), SleepInfo.fmtDur(c.elapsed))
        return when {
            c.elapsed < 30 * 60_000L -> A.result("deep_sleep_boot", t, Status.INFO, summary, txt(R.string.suspend_boot_recent), det)
            c.fraction < 0.01 && plugged != 0 -> A.result("deep_sleep_boot", t, Status.WARN, txt(R.string.suspend_boot_never), txt(R.string.suspend_boot_never_usb), det)
            c.fraction < 0.01 -> A.result("deep_sleep_boot", t, Status.WARN, txt(R.string.suspend_boot_never), txt(R.string.suspend_boot_never_reason), det)
            else -> A.result("deep_sleep_boot", t, Status.PASS, summary, null, det)
        }
    }

    private fun kernelPower(): TestResult {
        val t = txt(R.string.suspend_t_kernel)
        val values = SleepInfo.powerFiles.associateWith { Sys.line(it) }
        val det = values.map { (k, v) -> d(k, v ?: "unreadable") }
        val state = values["/sys/power/state"]
        val memSleep = values["/sys/power/mem_sleep"]
        val autosleep = values["/sys/power/autosleep"]
        return when {
            state == null -> A.result("kernel_power", t, Status.INFO, txt(R.string.suspend_kernel_unreadable), txt(R.string.suspend_kernel_unreadable_reason), det)
            !state.split(' ').any { it == "mem" || it == "freeze" } -> A.result(
                "kernel_power", t, Status.FAIL, txt(R.string.suspend_kernel_no_state, state), txt(R.string.suspend_kernel_no_state_reason), det,
            )
            memSleep != null && memSleep.contains("[s2idle]") && !memSleep.contains("deep") -> A.result(
                "kernel_power", t, Status.WARN, txt(R.string.suspend_kernel_s2idle, memSleep), txt(R.string.suspend_kernel_s2idle_reason), det,
            )
            autosleep != null && autosleep != "off" -> A.result(
                "kernel_power", t, Status.INFO, txt(R.string.suspend_kernel_ok, state), txt(R.string.suspend_kernel_autosleep, autosleep), det,
            )
            else -> A.result("kernel_power", t, Status.PASS, txt(R.string.suspend_kernel_ok, state), null, det)
        }
    }

    private fun suspendStats(): TestResult {
        val t = txt(R.string.suspend_t_stats)
        val st = SleepInfo.suspendStats()
        val det = mutableListOf<Detail>()
        st.forEach { (k, v) -> det += d(k, v) }
        SleepInfo.lastResumeReason()?.let { det += d("last_resume_reason", it) }
        SleepInfo.lastSuspendTime()?.let { det += d("last_suspend_time", it) }
        if (st.isEmpty()) return A.result(
            "suspend_stats", t, Status.INFO, txt(R.string.suspend_stats_missing), txt(R.string.suspend_stats_missing_reason), det,
        )
        val ok = st["success"]?.toLongOrNull() ?: 0
        val fail = st["fail"]?.toLongOrNull() ?: 0
        val dev = st["last_failed_dev"]?.takeIf { it.isNotBlank() }
        val summary = txt(R.string.suspend_stats_summary, ok, fail)
        return when {
            fail > 0 && fail * 4 > ok -> A.result(
                "suspend_stats", t, Status.WARN, summary,
                txt(R.string.suspend_stats_failing, dev ?: "?", st["last_failed_errno"] ?: "?", st["last_failed_step"] ?: "?"), det,
            )
            ok == 0L -> A.result("suspend_stats", t, Status.WARN, summary, txt(R.string.suspend_stats_none), det)
            else -> A.result("suspend_stats", t, Status.PASS, summary, null, det)
        }
    }

    private fun wakeupSources(ctx: Context): TestResult {
        val t = txt(R.string.suspend_t_sources)
        val src = SleepInfo.sources() ?: return A.result(
            "wakeup_sources", t, Status.INFO, txt(R.string.suspend_sources_unreadable), txt(R.string.suspend_sources_unreadable_reason),
            listOf(d("/sys/class/wakeup", if (Sys.exists("/sys/class/wakeup")) "not readable" else "absent"), d("/proc/wakelocks", if (Sys.exists("/proc/wakelocks")) "not readable" else "absent")),
        )
        val det = mutableListOf(d("source", src.path), d("count", src.list.size))
        src.list.sortedByDescending { it.totalMs }.take(8).forEachIndexed { i, s ->
            det += d("top ${i + 1}", "${s.name}: ${SleepInfo.fmtDur(s.totalMs)} total, ${s.activeCount}×, max ${SleepInfo.fmtDur(s.maxMs)}${if (s.active) ", ACTIVE" else ""}")
        }
        src.list.sortedByDescending { it.wakeupCount }.take(3).filter { it.wakeupCount > 0 }.forEach { s ->
            det += d("wakeups ${s.name}", s.wakeupCount)
        }
        val activeKernel = src.list.filter { it.active && !SleepInfo.isFrameworkLock(it.name) }
        activeKernel.sortedByDescending { it.activeMs }.take(6).forEach { s -> det += d("active ${s.name}", SleepInfo.fmtDur(s.activeMs)) }
        val stuck = activeKernel.filter { it.activeMs > 10 * 60_000L }.sortedByDescending { it.activeMs }
        val plugged = SleepInfo.plugged(ctx) != 0
        val summary = txt(R.string.suspend_sources_summary, src.list.size, activeKernel.size)
        return when {
            stuck.isNotEmpty() -> A.result(
                "wakeup_sources", t, Status.WARN, summary,
                txt(
                    if (plugged) R.string.suspend_sources_stuck_usb else R.string.suspend_sources_stuck,
                    stuck.take(3).joinToString { "${it.name} (${SleepInfo.fmtDur(it.activeMs)})" },
                ),
                det,
            )
            else -> A.result("wakeup_sources", t, Status.PASS, summary, null, det)
        }
    }

    private fun powerManager(ctx: Context): TestResult {
        val t = txt(R.string.suspend_t_pm)
        val pm = ctx.getSystemService(PowerManager::class.java)
            ?: return A.result("power_manager", t, Status.FAIL, txt(R.string.suspend_pm_missing), null)
        val det = mutableListOf(
            d("isInteractive", pm.isInteractive),
            d("isPowerSaveMode", pm.isPowerSaveMode),
            d("isDeviceIdleMode", pm.isDeviceIdleMode),
            d("isIgnoringBatteryOptimizations", pm.isIgnoringBatteryOptimizations(ctx.packageName)),
            d("currentThermalStatus", thermalName(pm.currentThermalStatus)),
            d("locationPowerSaveMode", pm.locationPowerSaveMode),
            d("isSustainedPerformanceModeSupported", pm.isSustainedPerformanceModeSupported),
            d("PARTIAL_WAKE_LOCK supported", pm.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK)),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            det.add(3, d("isDeviceLightIdleMode", pm.isDeviceLightIdleMode))
            det += d("isLowPowerStandbyEnabled", pm.isLowPowerStandbyEnabled)
        }
        val thermal = pm.currentThermalStatus
        return if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) A.result(
            "power_manager", t, Status.WARN, txt(R.string.suspend_pm_summary, thermalName(thermal)), txt(R.string.suspend_pm_hot), det,
        ) else A.result("power_manager", t, Status.INFO, txt(R.string.suspend_pm_summary, thermalName(thermal)), null, det)
    }

    private fun doze(dump: Boolean): TestResult {
        val t = txt(R.string.suspend_t_doze)
        val cfg = SleepInfo.sysBool("config_enableAutoPowerModes")
        val det = mutableListOf(d("config_enableAutoPowerModes", cfg))
        var deep: Boolean? = null
        var light: Boolean? = null
        if (dump) {
            val out = Sys.exec("dumpsys", "deviceidle", timeoutMs = 6000).out
            fun grab(k: String) = Regex("""\b$k=(\S+)""").find(out)?.groupValues?.get(1)
            deep = grab("mDeepEnabled")?.toBooleanStrictOrNull()
            light = grab("mLightEnabled")?.toBooleanStrictOrNull()
            listOf("mDeepEnabled", "mLightEnabled", "mState", "mLightState", "mForceIdle", "mScreenOn", "mCharging", "mMotionActive")
                .forEach { k -> grab(k)?.let { det += d(k, it) } }
        }
        val deepOn = deep ?: cfg
        return when {
            deepOn == false -> A.result("doze", t, Status.WARN, txt(R.string.suspend_doze_off), txt(R.string.suspend_doze_off_reason), det)
            deepOn == true && light == false -> A.result("doze", t, Status.INFO, txt(R.string.suspend_doze_deep_only), null, det)
            deepOn == true -> A.result("doze", t, Status.PASS, txt(R.string.suspend_doze_on), null, det)
            else -> A.result("doze", t, Status.INFO, txt(R.string.suspend_doze_unknown), txt(R.string.suspend_need_dump), det)
        }
    }

    private fun powerDump(dump: Boolean): TestResult {
        val t = txt(R.string.suspend_t_dump)
        if (!dump) return A.result("power_dump", t, Status.SKIP, txt(R.string.suspend_dump_skip), txt(R.string.suspend_need_dump))
        val out = Sys.exec("dumpsys", "power", timeoutMs = 6000).out
        val det = mutableListOf<Detail>()
        fun grab(k: String) = Regex("""\b$k=(\S+)""").find(out)?.groupValues?.get(1)
        listOf("mWakefulness", "mHoldingWakeLockSuspendBlocker", "mHoldingDisplaySuspendBlocker", "mStayOn", "mIsPowered", "mPlugType")
            .forEach { k -> grab(k)?.let { det += d(k, it) } }
        val lines = out.lines()
        val wl = lines.indexOfFirst { it.trim().startsWith("Wake Locks:") }
        val locks = if (wl >= 0) lines.drop(wl + 1).takeWhile { it.isNotBlank() && it.startsWith("  ") }.map { it.trim() } else emptyList()
        locks.take(10).forEachIndexed { i, l -> det += d("wake lock ${i + 1}", l.take(160)) }
        val sb = lines.indexOfFirst { it.trim().startsWith("Suspend Blockers:") }
        val blockers = if (sb >= 0) lines.drop(sb + 1).takeWhile { it.isNotBlank() && it.startsWith("  ") }.map { it.trim() } else emptyList()
        blockers.filter { !it.endsWith("ref count=0") }.take(6).forEach { det += d("suspend blocker", it) }
        val sc = Sys.exec("dumpsys", "suspend_control_internal", timeoutMs = 5000)
        if (sc.ok && !sc.out.contains("Can't find service")) {
            sc.out.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(12).forEachIndexed { i, l -> det += d("suspend_control ${i + 1}", l.take(160)) }
        }
        val partial = locks.filter { it.startsWith("PARTIAL_WAKE_LOCK") }
        return if (out.isBlank()) A.result("power_dump", t, Status.INFO, txt(R.string.suspend_dump_empty), null, det)
        else A.result("power_dump", t, Status.INFO, txt(R.string.suspend_dump_summary, locks.size, partial.size, grab("mWakefulness") ?: "?"), null, det)
    }

    private fun exactAlarm(ctx: Context): TestResult {
        val t = txt(R.string.suspend_t_exact)
        val ok = SuspendTest.canScheduleExact(ctx)
        val det = listOf(d("SDK", Build.VERSION.SDK_INT), d("canScheduleExactAlarms", if (Build.VERSION.SDK_INT >= 31) ok else "n/a (API 30)"))
        return if (ok) A.result("exact_alarm", t, Status.PASS, txt(R.string.suspend_exact_ok), null, det)
        else A.result("exact_alarm", t, Status.FAIL, txt(R.string.suspend_exact_denied), txt(R.string.suspend_exact_denied_reason), det)
    }
}
