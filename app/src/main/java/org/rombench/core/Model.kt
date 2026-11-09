package org.rombench.core

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject

/** Outcome of a check. [rank] orders severity when results are aggregated. */
enum class Status(val rank: Int) {
    FAIL(4), WARN(3), PASS(2), INFO(1), SKIP(0);

    companion object {
        /** Worst status of a group, or null when the group is empty. */
        fun worst(items: Collection<Status>): Status? = items.maxByOrNull { it.rank }
    }
}

/**
 * Text that is localised when shown. Results keep [Txt] instead of plain strings so the UI uses
 * the device language while reports can be rendered in English for bug trackers and XDA.
 */
sealed interface Txt {
    fun resolve(ctx: Context): String
}

data class ResTxt(@StringRes val id: Int, val args: List<Any?> = emptyList()) : Txt {
    override fun resolve(ctx: Context): String {
        // Strings written for String.format escape % as %%; unformatted ones are unescaped here.
        if (args.isEmpty()) return ctx.getString(id).replace("%%", "%")
        val resolved = args.map { if (it is Txt) it.resolve(ctx) else it ?: "—" }.toTypedArray()
        return try {
            ctx.getString(id, *resolved)
        } catch (e: Exception) {
            ctx.getString(id) + " " + resolved.joinToString()
        }
    }
}

data class RawTxt(val text: String) : Txt {
    override fun resolve(ctx: Context) = text
}

/** Localised text from a string resource; arguments may themselves be [Txt]. */
fun txt(@StringRes id: Int, vararg args: Any?): Txt = ResTxt(id, args.toList())

/** Text that is never translated (numbers, kernel strings, names). */
fun raw(text: String): Txt = RawTxt(text)

@Composable
fun Txt.text(): String = resolve(LocalContext.current)

/**
 * One technical fact attached to a result. Labels stay in English on purpose: they are the
 * same words used in logcat, dumpsys and sysfs. [sensitive] values (MAC, IMEI, serial, SSID,
 * phone numbers) are redacted in exported reports unless the user turns redaction off.
 */
data class Detail(val label: String, val value: String, val sensitive: Boolean = false)

/** Short builder for a [Detail]; null values become an em dash. */
fun d(label: String, value: Any?, sensitive: Boolean = false) =
    Detail(label, value?.toString()?.takeIf { it.isNotEmpty() } ?: "—", sensitive)

enum class Kind { AUTO, GUIDED }

data class TestResult(
    /** Stable id, "<area>.<name>", used to compare reports between builds. */
    val key: String,
    val area: String,
    val title: Txt,
    val status: Status,
    val summary: Txt,
    /** Why the status is what it is (mandatory in practice for WARN and FAIL). */
    val reason: Txt? = null,
    val details: List<Detail> = emptyList(),
    val kind: Kind = Kind.AUTO,
    val time: Long = System.currentTimeMillis(),
)

/** Builds a result whose key is "<area id>.<name>". */
fun Area.result(
    name: String,
    title: Txt,
    status: Status,
    summary: Txt,
    reason: Txt? = null,
    details: List<Detail> = emptyList(),
    kind: Kind = Kind.AUTO,
) = TestResult("$id.$name", id, title, status, summary, reason, details, kind)

// ---- Serialisation of Txt for the persisted session (resource names survive rebuilds) ----

internal fun Txt.toJson(ctx: Context): JSONObject = when (this) {
    is RawTxt -> JSONObject().put("raw", text)
    is ResTxt -> JSONObject().apply {
        put("res", ctx.resources.getResourceEntryName(id))
        put("en", resolve(Localized.english(ctx)))
        put("args", JSONArray().also { arr ->
            args.forEach { a ->
                when (a) {
                    is Txt -> arr.put(a.toJson(ctx))
                    is Number, is Boolean -> arr.put(a)
                    null -> arr.put(JSONObject.NULL)
                    else -> arr.put(a.toString())
                }
            }
        })
    }
}

internal fun txtFromJson(ctx: Context, o: JSONObject): Txt {
    if (o.has("raw")) return RawTxt(o.getString("raw"))
    val id = ctx.resources.getIdentifier(o.optString("res"), "string", ctx.packageName)
    if (id == 0) return RawTxt(o.optString("en"))
    val arr = o.optJSONArray("args") ?: JSONArray()
    val args = (0 until arr.length()).map { i ->
        when (val v = arr.get(i)) {
            is JSONObject -> txtFromJson(ctx, v)
            JSONObject.NULL -> null
            else -> v
        }
    }
    return ResTxt(id, args)
}
