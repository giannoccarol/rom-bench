package org.rombench.report

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.rombench.BuildConfig
import org.rombench.core.Areas
import org.rombench.core.Detail
import org.rombench.core.Localized
import org.rombench.core.ResultStore
import org.rombench.core.Settings
import org.rombench.core.Status
import org.rombench.core.Sys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A rendered report: plain strings only, so it can be saved, reloaded and diffed. */
data class Report(
    val generated: Long,
    val appVersion: String,
    val device: List<Detail>,
    val areas: List<AreaReport>,
    val properties: Map<String, String> = emptyMap(),
) {
    data class AreaReport(val id: String, val title: String, val status: Status?, val results: List<Item>)
    data class Item(
        val key: String, val title: String, val status: Status, val kind: String,
        val summary: String, val reason: String?, val details: List<Detail>,
    )

    val counts: Map<Status, Int>
        get() = areas.flatMap { it.results }.groupingBy { it.status }.eachCount()

    fun deviceValue(label: String) = device.firstOrNull { it.label == label }?.value

    /** Short name used for files and in the history list. */
    val title: String
        get() = listOfNotNull(deviceValue("Device"), deviceValue("ROM")).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("generated", isoDate(generated))
        put("generatedMs", generated)
        put("app", JSONObject().put("name", "ROM Bench").put("version", appVersion))
        put("device", JSONObject().also { o -> device.forEach { o.put(it.label, it.value) } })
        put("summary", JSONObject().also { o -> Status.entries.forEach { s -> o.put(s.name, counts[s] ?: 0) } })
        put("areas", JSONArray().also { arr ->
            areas.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id); put("title", a.title); put("status", a.status?.name ?: JSONObject.NULL)
                    put("results", JSONArray().also { ra ->
                        a.results.forEach { r ->
                            ra.put(JSONObject().apply {
                                put("key", r.key); put("title", r.title); put("status", r.status.name)
                                put("kind", r.kind); put("summary", r.summary)
                                r.reason?.let { put("reason", it) }
                                put("details", JSONObject().also { o -> r.details.forEach { dd -> o.put(dd.label, dd.value) } })
                            })
                        }
                    })
                })
            }
        })
        if (properties.isNotEmpty()) put("properties", JSONObject().also { o -> properties.forEach { (k, v) -> o.put(k, v) } })
    }

    companion object {
        const val SCHEMA = "rombench-report/1"
        const val REDACTED = "<redacted>"

        fun isoDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))

        /** Builds a report from the current session, applying the user's report settings. */
        fun fromSession(ctx: Context): Report {
            val rc = Localized.forReport(ctx)
            val redact = Settings.redact(ctx)
            fun clean(list: List<Detail>) = list.map { if (redact && it.sensitive) it.copy(value = REDACTED) else it }
            val results = ResultStore.results.value.values
            val areas = Areas.all.mapNotNull { area ->
                val rs = results.filter { it.area == area.id }
                if (rs.isEmpty()) return@mapNotNull null
                AreaReport(
                    area.id, rc.getString(area.title), Status.worst(rs.map { it.status }),
                    rs.map { r ->
                        Item(
                            r.key, r.title.resolve(rc), r.status, r.kind.name.lowercase(),
                            r.summary.resolve(rc), r.reason?.resolve(rc), clean(r.details),
                        )
                    },
                )
            }
            val props = if (Settings.includeAllProps(ctx)) Sys.allProps().filterKeys { k ->
                !redact || SENSITIVE_PROPS.none { k.contains(it) }
            } else emptyMap()
            return Report(System.currentTimeMillis(), BuildConfig.VERSION_NAME, clean(DeviceInfo.collect(ctx)), areas, props)
        }

        /** Property names that can identify the phone or the user. */
        val SENSITIVE_PROPS = listOf("serial", "imei", "meid", "iccid", "mac", "bdaddr", "wifi.addr", "phone_number", "gsm.sim.operator.iso", "persist.radio.nitz")

        fun fromJson(o: JSONObject): Report {
            val dev = o.optJSONObject("device") ?: JSONObject()
            val device = dev.keys().asSequence().map { Detail(it, dev.getString(it)) }.toList()
            val arr = o.optJSONArray("areas") ?: JSONArray()
            val areas = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                val ra = a.optJSONArray("results") ?: JSONArray()
                AreaReport(
                    a.getString("id"), a.optString("title"),
                    a.optString("status").takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { Status.valueOf(it) }.getOrNull() },
                    (0 until ra.length()).map { j ->
                        val r = ra.getJSONObject(j)
                        val det = r.optJSONObject("details") ?: JSONObject()
                        Item(
                            r.getString("key"), r.optString("title"), Status.valueOf(r.getString("status")),
                            r.optString("kind", "auto"), r.optString("summary"),
                            r.optString("reason").takeIf { r.has("reason") },
                            det.keys().asSequence().map { Detail(it, det.getString(it)) }.toList(),
                        )
                    },
                )
            }
            val props = o.optJSONObject("properties")?.let { p -> p.keys().asSequence().associateWith { p.getString(it) } } ?: emptyMap()
            return Report(o.optLong("generatedMs"), o.optJSONObject("app")?.optString("version") ?: "", device, areas, props)
        }
    }
}
