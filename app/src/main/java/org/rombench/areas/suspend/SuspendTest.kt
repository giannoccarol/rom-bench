package org.rombench.areas.suspend

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import org.rombench.R
import org.rombench.core.AlarmBus
import org.rombench.core.AlarmReceiver
import org.rombench.core.Detail
import org.rombench.core.Kind
import org.rombench.core.ResultStore
import org.rombench.core.Status
import org.rombench.core.d
import org.rombench.core.result
import org.rombench.core.txt

/**
 * Guided deep-sleep test. Its state lives in SharedPreferences so that it survives the process
 * being killed while the screen is off: results are computed the next time the screen opens.
 */
internal object SuspendTest {
    private const val PREFS = "suspend_test"
    private const val REQUEST = 7301
    /** How long after the due time we wait before declaring the alarm lost. */
    const val GRACE_MS = 120_000L

    data class Run(
        val tag: String,
        val startElapsed: Long,
        val startUptime: Long,
        val durationMs: Long,
        val pluggedStart: Int,
        val wakeupCountStart: Long?,
        val suspendSuccessStart: Long?,
    ) {
        val due get() = startElapsed + durationMs
    }

    data class Fired(val elapsed: Long, val uptime: Long)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): Run? {
        val p = prefs(ctx)
        if (!p.getBoolean("running", false)) return null
        val run = Run(
            p.getString("tag", "") ?: "",
            p.getLong("start_elapsed", 0), p.getLong("start_uptime", 0), p.getLong("duration", 0),
            p.getInt("plugged", 0),
            p.getLong("wakeup_count", -1).takeIf { it >= 0 },
            p.getLong("suspend_success", -1).takeIf { it >= 0 },
        )
        // A start point in the future of elapsedRealtime means the phone rebooted meanwhile.
        if (run.startElapsed <= 0 || run.startElapsed > SleepInfo.clocks().elapsed) {
            p.edit { clear() }
            return null
        }
        return run
    }

    /** The core AlarmReceiver records the last alarm in prefs "alarm"; only ours counts. */
    fun fired(ctx: Context, run: Run): Fired? {
        val p = ctx.applicationContext.getSharedPreferences("alarm", Context.MODE_PRIVATE)
        if (p.getString("tag", null) != run.tag) return null
        val e = p.getLong("elapsed", 0)
        if (e < run.startElapsed) return null
        return Fired(e, p.getLong("uptime", 0))
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private fun pending(ctx: Context, tag: String?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        if (tag != null) i.putExtra(AlarmBus.EXTRA_TAG, tag)
        return PendingIntent.getBroadcast(ctx.applicationContext, REQUEST, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Schedules the wake-up alarm and stores the start point; returns an error text or null. */
    fun start(ctx: Context, durationMs: Long): String? {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return "no AlarmManager"
        val c = SleepInfo.clocks()
        val tag = "suspend_test_${c.elapsed}"
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, c.elapsed + durationMs, pending(ctx, tag))
        } catch (e: SecurityException) {
            return e.message ?: e.toString()
        }
        val plugged = SleepInfo.plugged(ctx)
        val wakeups = SleepInfo.wakeupCount() ?: -1
        val success = SleepInfo.suspendStats()["success"]?.toLongOrNull() ?: -1
        prefs(ctx).edit {
            putBoolean("running", true)
            putString("tag", tag)
            putLong("start_elapsed", c.elapsed)
            putLong("start_uptime", c.uptime)
            putLong("duration", durationMs)
            putInt("plugged", plugged)
            putLong("wakeup_count", wakeups)
            putLong("suspend_success", success)
        }
        return null
    }

    fun cancel(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, null))
        } catch (e: Exception) {
        }
        prefs(ctx).edit { clear() }
    }

    /** True when the run can be evaluated without the tester stopping it. */
    fun ready(ctx: Context, run: Run): Boolean =
        fired(ctx, run) != null || SleepInfo.clocks().elapsed > run.due + GRACE_MS

    /**
     * Computes both results from the stored start point, the alarm record and the clocks now,
     * stores them and ends the run. [early] means the tester stopped before the alarm was due.
     */
    fun finish(ctx: Context, run: Run, early: Boolean) {
        val now = SleepInfo.clocks()
        val fired = fired(ctx, run)
        // Window: until the alarm woke the phone, or until now if it did not fire.
        val endElapsed = fired?.elapsed ?: now.elapsed
        val endUptime = fired?.uptime ?: now.uptime
        val total = (endElapsed - run.startElapsed).coerceAtLeast(1)
        val awake = (endUptime - run.startUptime).coerceIn(0, total)
        val asleep = total - awake
        val frac = asleep.toDouble() / total
        val pluggedEnd = SleepInfo.plugged(ctx)
        val plugged = run.pluggedStart != 0
        val wakeups = SleepInfo.wakeupCount()?.let { w -> run.wakeupCountStart?.let { w - it } }
        val suspends = SleepInfo.suspendStats()["success"]?.toLongOrNull()?.let { s -> run.suspendSuccessStart?.let { s - it } }
        val det = mutableListOf<Detail>(
            d("planned", SleepInfo.fmtDur(run.durationMs)),
            d("window", SleepInfo.fmtDur(total)),
            d("asleep", SleepInfo.fmtDur(asleep)),
            d("awake", SleepInfo.fmtDur(awake)),
            d("deep sleep", SleepInfo.pct(frac)),
            d("power at start", SleepInfo.pluggedName(run.pluggedStart)),
            d("power at end", SleepInfo.pluggedName(pluggedEnd)),
        )
        wakeups?.let { det += d("wakeup_count delta", it) }
        suspends?.let { det += d("suspend_stats success delta", it) }
        SleepInfo.lastResumeReason()?.let { det += d("last_resume_reason", it) }
        SleepInfo.lastSuspendTime()?.let { det += d("last_suspend_time", it) }

        val a = SuspendArea
        val tSleep = txt(R.string.suspend_t_sleep_test)
        val minutes = SleepInfo.fmtDur(total)
        val sleepRes = when {
            total < 60_000 -> a.result("deep_sleep_test", tSleep, Status.INFO, txt(R.string.suspend_test_too_short, minutes), null, det, Kind.GUIDED)
            frac >= 0.7 -> a.result("deep_sleep_test", tSleep, Status.PASS, txt(R.string.suspend_test_slept, SleepInfo.pct(frac), minutes), null, det, Kind.GUIDED)
            plugged -> a.result(
                "deep_sleep_test", tSleep, Status.WARN, txt(R.string.suspend_test_slept, SleepInfo.pct(frac), minutes),
                txt(R.string.suspend_test_plugged), det, Kind.GUIDED,
            )
            frac >= 0.1 -> a.result(
                "deep_sleep_test", tSleep, Status.WARN, txt(R.string.suspend_test_slept, SleepInfo.pct(frac), minutes),
                txt(R.string.suspend_test_partial), det, Kind.GUIDED,
            )
            else -> a.result(
                "deep_sleep_test", tSleep, Status.FAIL, txt(R.string.suspend_test_never, minutes),
                txt(R.string.suspend_test_never_reason), det, Kind.GUIDED,
            )
        }
        ResultStore.put(sleepRes)

        val tAlarm = txt(R.string.suspend_t_alarm)
        val alarmDet = listOf(
            d("due after", SleepInfo.fmtDur(run.durationMs)),
            d("fired", fired?.let { "${SleepInfo.fmtDur(it.elapsed - run.startElapsed)} after start" } ?: "no"),
            d("lateness", fired?.let { "${it.elapsed - run.due} ms" }),
            d("deep sleep before alarm", SleepInfo.pct(frac)),
        )
        val alarmRes = when {
            fired == null && early -> a.result("alarm_wakeup", tAlarm, Status.SKIP, txt(R.string.suspend_alarm_early), null, alarmDet, Kind.GUIDED)
            fired == null -> a.result(
                "alarm_wakeup", tAlarm, Status.FAIL, txt(R.string.suspend_alarm_missing),
                txt(R.string.suspend_alarm_missing_reason), alarmDet, Kind.GUIDED,
            )
            else -> {
                val late = fired.elapsed - run.due
                val lateS = String.format(java.util.Locale.US, "%.1f", late / 1000.0)
                when {
                    frac < 0.1 -> a.result(
                        "alarm_wakeup", tAlarm, Status.WARN, txt(R.string.suspend_alarm_on_time, lateS),
                        txt(R.string.suspend_alarm_not_proven), alarmDet, Kind.GUIDED,
                    )
                    late <= 5_000 -> a.result("alarm_wakeup", tAlarm, Status.PASS, txt(R.string.suspend_alarm_on_time, lateS), null, alarmDet, Kind.GUIDED)
                    late <= 60_000 -> a.result(
                        "alarm_wakeup", tAlarm, Status.WARN, txt(R.string.suspend_alarm_late, lateS),
                        txt(R.string.suspend_alarm_late_reason), alarmDet, Kind.GUIDED,
                    )
                    else -> a.result(
                        "alarm_wakeup", tAlarm, Status.FAIL, txt(R.string.suspend_alarm_late, lateS),
                        txt(R.string.suspend_alarm_very_late_reason), alarmDet, Kind.GUIDED,
                    )
                }
            }
        }
        ResultStore.put(alarmRes)
        cancel(ctx)
    }
}
