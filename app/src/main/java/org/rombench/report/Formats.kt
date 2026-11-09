package org.rombench.report

import org.rombench.core.Status

enum class ReportFormat(val ext: String, val mime: String, val label: String) {
    TEXT("txt", "text/plain", "Text"),
    MARKDOWN("md", "text/markdown", "Markdown"),
    BBCODE("bbcode.txt", "text/plain", "BBCode (XDA)"),
    JSON("json", "application/json", "JSON"),
    /** Results + analysis context + Android logs for AI agents; built by [AgentReport]. */
    AGENT("agent.json", "application/json", "Agent JSON + logs"),
}

private fun Status.mark() = when (this) {
    Status.PASS -> "[ OK ]"
    Status.WARN -> "[WARN]"
    Status.FAIL -> "[FAIL]"
    Status.INFO -> "[INFO]"
    Status.SKIP -> "[SKIP]"
}

private fun Status.word() = when (this) {
    Status.PASS -> "OK"
    Status.WARN -> "Warning"
    Status.FAIL -> "Fail"
    Status.INFO -> "Info"
    Status.SKIP -> "Skipped"
}

private fun Status.bbColor() = when (this) {
    Status.PASS -> "#2E7D32"
    Status.WARN -> "#B26A00"
    Status.FAIL -> "#C62828"
    Status.INFO -> "#3D5A99"
    Status.SKIP -> "#757575"
}

/** Renders reports. Formats differ only in markup; content and ordering are the same. */
object Formats {

    fun render(r: Report, f: ReportFormat, withDetails: Boolean = true): String = when (f) {
        ReportFormat.TEXT -> text(r, withDetails)
        ReportFormat.MARKDOWN -> markdown(r, withDetails)
        ReportFormat.BBCODE -> bbcode(r, withDetails)
        ReportFormat.JSON, ReportFormat.AGENT -> r.toJson().toString(2)
    }

    private fun countsLine(r: Report) = Status.entries.joinToString("  ") { "${it.word()}: ${r.counts[it] ?: 0}" }

    private fun text(r: Report, withDetails: Boolean) = buildString {
        appendLine("ROM Bench report — ${Report.isoDate(r.generated)} (app ${r.appVersion})")
        appendLine("=".repeat(60))
        val w = r.device.maxOfOrNull { it.label.length } ?: 10
        r.device.forEach { appendLine("${it.label.padEnd(w)} : ${it.value}") }
        appendLine()
        appendLine(countsLine(r))
        r.areas.forEach { a ->
            appendLine()
            appendLine("## ${a.title}${a.status?.let { "  ${it.mark()}" } ?: ""}")
            a.results.forEach { i ->
                appendLine("${i.status.mark()} ${i.title}: ${i.summary}${if (i.kind == "guided") " (guided)" else ""}")
                i.reason?.let { appendLine("       why: $it") }
                if (withDetails) i.details.forEach { appendLine("       ${it.label}: ${it.value}") }
            }
        }
        if (r.properties.isNotEmpty()) {
            appendLine(); appendLine("## Properties")
            r.properties.forEach { (k, v) -> appendLine("[$k]: [$v]") }
        }
    }

    private fun md(s: String) = s.replace("|", "\\|")

    private fun markdown(r: Report, withDetails: Boolean) = buildString {
        appendLine("# ROM Bench report")
        appendLine()
        appendLine("Generated ${Report.isoDate(r.generated)} by ROM Bench ${r.appVersion}.")
        appendLine()
        appendLine("| Property | Value |"); appendLine("|---|---|")
        r.device.forEach { appendLine("| ${md(it.label)} | ${md(it.value)} |") }
        appendLine()
        appendLine("**${countsLine(r)}**")
        r.areas.forEach { a ->
            appendLine(); appendLine("## ${a.title}${a.status?.let { " — ${it.word()}" } ?: ""}"); appendLine()
            appendLine("| | Check | Result | Why |"); appendLine("|---|---|---|---|")
            a.results.forEach { i ->
                appendLine("| ${i.status.word()} | ${md(i.title)} | ${md(i.summary)} | ${md(i.reason ?: "")} |")
            }
            if (withDetails && a.results.any { it.details.isNotEmpty() }) {
                appendLine(); appendLine("<details><summary>Details</summary>"); appendLine(); appendLine("```")
                a.results.filter { it.details.isNotEmpty() }.forEach { i ->
                    appendLine("${i.title}:"); i.details.forEach { appendLine("  ${it.label}: ${it.value}") }
                }
                appendLine("```"); appendLine(); appendLine("</details>")
            }
        }
        if (r.properties.isNotEmpty()) {
            appendLine(); appendLine("<details><summary>Properties</summary>"); appendLine(); appendLine("```")
            r.properties.forEach { (k, v) -> appendLine("[$k]: [$v]") }
            appendLine("```"); appendLine(); appendLine("</details>")
        }
    }

    private fun bbcode(r: Report, withDetails: Boolean) = buildString {
        appendLine("[SIZE=4][B]ROM Bench report[/B][/SIZE]")
        appendLine("Generated ${Report.isoDate(r.generated)} by ROM Bench ${r.appVersion}")
        appendLine()
        appendLine("[LIST]")
        r.device.forEach { appendLine("[*][B]${it.label}:[/B] ${it.value}") }
        appendLine("[/LIST]")
        appendLine("[B]${countsLine(r)}[/B]")
        r.areas.forEach { a ->
            appendLine()
            val st = a.status?.let { " [COLOR=${it.bbColor()}](${it.word()})[/COLOR]" } ?: ""
            appendLine("[B]${a.title}[/B]$st")
            appendLine("[LIST]")
            a.results.forEach { i ->
                val why = i.reason?.let { " — [I]${it}[/I]" } ?: ""
                appendLine("[*][COLOR=${i.status.bbColor()}][B]${i.status.word()}[/B][/COLOR] ${i.title}: ${i.summary}$why")
            }
            appendLine("[/LIST]")
            if (withDetails && a.results.any { it.details.isNotEmpty() }) {
                appendLine("[SPOILER=\"${a.title} details\"][CODE]")
                a.results.filter { it.details.isNotEmpty() }.forEach { i ->
                    appendLine("${i.title}:"); i.details.forEach { appendLine("  ${it.label}: ${it.value}") }
                }
                appendLine("[/CODE][/SPOILER]")
            }
        }
    }
}
