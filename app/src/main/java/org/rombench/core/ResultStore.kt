package org.rombench.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Results of the current session, keyed by result key. Persisted to files/session.json so
 * guided answers survive a process death; cleared by the user from the dashboard.
 */
object ResultStore {
    private val _results = MutableStateFlow<Map<String, TestResult>>(emptyMap())
    val results: StateFlow<Map<String, TestResult>> = _results

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
    fun init(ctx: Context) {
        app = ctx.applicationContext
        load()
        _results.drop(1).debounce(500).onEach { save(it) }.launchIn(scope)
    }

    fun put(r: TestResult) = _results.update { LinkedHashMap(it).apply { put(r.key, r) } }

    fun putAll(list: List<TestResult>) = _results.update { m -> LinkedHashMap(m).apply { list.forEach { put(it.key, it) } } }

    fun remove(key: String) = _results.update { LinkedHashMap(it).apply { remove(key) } }

    fun clear() = _results.update { emptyMap() }

    fun clearArea(area: String) = _results.update { m -> m.filterValues { it.area != area } }

    fun forArea(area: String): List<TestResult> = _results.value.values.filter { it.area == area }

    private fun file() = File(app.filesDir, "session.json")

    private fun save(map: Map<String, TestResult>) = try {
        val arr = JSONArray()
        map.values.forEach { r ->
            arr.put(JSONObject().apply {
                put("key", r.key); put("area", r.area); put("status", r.status.name)
                put("kind", r.kind.name); put("time", r.time)
                put("title", r.title.toJson(app)); put("summary", r.summary.toJson(app))
                r.reason?.let { put("reason", it.toJson(app)) }
                put("details", JSONArray().also { a ->
                    r.details.forEach { dd -> a.put(JSONObject().put("l", dd.label).put("v", dd.value).put("s", dd.sensitive)) }
                })
            })
        }
        file().writeText(arr.toString())
    } catch (e: Exception) {
        Log.w("RomBench", "session save failed", e)
    }

    private fun load() {
        val f = file()
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val map = LinkedHashMap<String, TestResult>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val det = o.optJSONArray("details") ?: JSONArray()
                val r = TestResult(
                    key = o.getString("key"), area = o.getString("area"),
                    title = txtFromJson(app, o.getJSONObject("title")),
                    status = Status.valueOf(o.getString("status")),
                    summary = txtFromJson(app, o.getJSONObject("summary")),
                    reason = o.optJSONObject("reason")?.let { txtFromJson(app, it) },
                    details = (0 until det.length()).map { j ->
                        det.getJSONObject(j).let { Detail(it.getString("l"), it.getString("v"), it.optBoolean("s")) }
                    },
                    kind = Kind.valueOf(o.optString("kind", "AUTO")),
                    time = o.optLong("time"),
                )
                map[r.key] = r
            }
            _results.value = map
        } catch (e: Exception) {
            Log.w("RomBench", "session load failed", e)
        }
    }
}
