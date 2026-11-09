package org.rombench.report

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved reports. Each is a JSON file in files/reports/. The newest one is also copied, in all
 * formats, to the app's external files dir (Android/data/<package>/files/reports/latest.*) so
 * it can be pulled with adb without root.
 */
object ReportRepo {
    data class Entry(val file: File, val report: Report)

    private fun dir(ctx: Context) = File(ctx.filesDir, "reports").apply { mkdirs() }

    fun save(ctx: Context, report: Report): File {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.generated))
        val dev = (report.deviceValue("Device") ?: "device").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val f = File(dir(ctx), "$name-$dev.json")
        f.writeText(report.toJson().toString(2))
        exportLatest(ctx, report)
        return f
    }

    fun exportLatest(ctx: Context, report: Report) {
        try {
            val ext = ctx.getExternalFilesDir("reports") ?: return
            ReportFormat.entries.filter { it != ReportFormat.AGENT }.forEach { fmt ->
                File(ext, "latest.${fmt.ext}").writeText(Formats.render(report, fmt))
            }
            File(ext, "latest.agent.json").writeText(AgentReport.build(ctx, report).toString(2))
            File(ext, "latest.done").writeText(report.generated.toString())
        } catch (e: Exception) {
            Log.w("RomBench", "external export failed", e)
        }
    }

    fun list(ctx: Context): List<Entry> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }?.mapNotNull { f ->
            try {
                Entry(f, Report.fromJson(JSONObject(f.readText())))
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()

    fun delete(entry: Entry) = entry.file.delete()

    /** Text of a report in a format; the agent format also collects logs (slow, call off the main thread). */
    fun render(ctx: Context, report: Report, fmt: ReportFormat, withDetails: Boolean = true): String =
        if (fmt == ReportFormat.AGENT) AgentReport.build(ctx, report).toString(2) else Formats.render(report, fmt, withDetails)

    /** Writes a rendered report into cache/share for the share sheet (FileProvider). */
    fun shareFile(ctx: Context, report: Report, fmt: ReportFormat, text: String = render(ctx, report, fmt)): File {
        val d = File(ctx.cacheDir, "share").apply { mkdirs() }
        d.listFiles()?.forEach { it.delete() }
        val name = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(report.generated))
        val dev = (report.deviceValue("Device") ?: "device").replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(d, "rombench-$dev-$name.${fmt.ext}").apply { writeText(text) }
    }
}
