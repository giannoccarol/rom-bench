package org.rombench.report

import org.rombench.core.Status

/** Differences between two reports, matched by result key. */
object Compare {
    enum class Change { REGRESSION, IMPROVEMENT, CHANGED, SAME, ONLY_OLD, ONLY_NEW }

    data class Row(
        val key: String, val area: String, val title: String,
        val old: Report.Item?, val new: Report.Item?, val change: Change,
    )

    /** Status ordering for regressions: lower is better. INFO and SKIP are neutral. */
    private fun badness(s: Status) = when (s) {
        Status.PASS -> 0; Status.INFO -> 0; Status.SKIP -> 0; Status.WARN -> 1; Status.FAIL -> 2
    }

    fun diff(old: Report, new: Report): List<Row> {
        val o = old.areas.flatMap { a -> a.results.map { it to a.title } }.associateBy { it.first.key }
        val n = new.areas.flatMap { a -> a.results.map { it to a.title } }.associateBy { it.first.key }
        val keys = (n.keys + o.keys).distinct()
        return keys.map { k ->
            val oi = o[k]?.first
            val ni = n[k]?.first
            val area = n[k]?.second ?: o[k]?.second ?: ""
            val change = when {
                oi == null -> Change.ONLY_NEW
                ni == null -> Change.ONLY_OLD
                oi.status == Status.SKIP || ni.status == Status.SKIP -> if (oi.status == ni.status) Change.SAME else Change.CHANGED
                badness(ni.status) > badness(oi.status) -> Change.REGRESSION
                badness(ni.status) < badness(oi.status) -> Change.IMPROVEMENT
                oi.status != ni.status || oi.summary != ni.summary -> Change.CHANGED
                else -> Change.SAME
            }
            Row(k, area, ni?.title ?: oi?.title ?: k, oi, ni, change)
        }.sortedBy { it.change.ordinal }
    }

    /** Device properties whose value differs (build, kernel, patch level...). */
    fun deviceDiff(old: Report, new: Report): List<Triple<String, String?, String?>> {
        val labels = (new.device.map { it.label } + old.device.map { it.label }).distinct()
        return labels.mapNotNull { l ->
            val a = old.deviceValue(l); val b = new.deviceValue(l)
            if (a != b && l != "Uptime") Triple(l, a, b) else null
        }
    }
}
