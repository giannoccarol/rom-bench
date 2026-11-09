package org.rombench.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.rombench.R
import org.rombench.report.Report
import org.rombench.report.ReportRepo

/** Runs the automatic checks of several areas in sequence, outside any screen's lifecycle. */
object AutoRunner {
    data class State(
        val running: Boolean = false,
        val current: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val finishedAt: Long? = null,
        val singleArea: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun isRunning(area: String? = null): Boolean {
        val s = _state.value
        return s.running && (area == null || s.current == area)
    }

    /** Runs every area (dashboard, adb autorun). The report is saved at the end. */
    fun runAll(ctx: Context, save: Boolean = true) = start(ctx, Areas.all, save, single = null)

    /** Runs a subset of areas and saves the report (adb automation). */
    fun runAreas(ctx: Context, areas: List<Area>) = start(ctx, areas, save = true, single = null)

    /** Runs one area from its screen; no report is saved. */
    fun runArea(ctx: Context, area: Area) = start(ctx, listOf(area), save = false, single = area.id)

    private fun start(ctx: Context, areas: List<Area>, save: Boolean, single: String?) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            _state.value = State(running = true, total = areas.size, singleArea = single)
            try {
                areas.forEachIndexed { i, area ->
                    _state.update { it.copy(current = area.id, done = i) }
                    ResultStore.putAll(runOne(app, area))
                }
                if (save) ReportRepo.save(app, Report.fromSession(app))
            } finally {
                _state.value = State(finishedAt = System.currentTimeMillis(), singleArea = single, done = areas.size, total = areas.size)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun runOne(ctx: Context, area: Area): List<TestResult> {
        val timeout = (area.autoSeconds * 3L + 20L) * 1000L
        return try {
            withTimeoutOrNull(timeout) { area.autoCheck(ctx) } ?: listOf(
                area.result(
                    "timeout", txt(area.title), Status.WARN,
                    txt(R.string.auto_timeout, timeout / 1000), txt(R.string.auto_timeout_reason),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("RomBench", "auto check ${area.id} crashed", e)
            listOf(
                area.result(
                    "crash", txt(area.title), Status.FAIL,
                    txt(R.string.auto_crash), raw(e.toString()),
                    listOf(d("Stack", e.stackTrace.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })),
                ),
            )
        }
    }
}
