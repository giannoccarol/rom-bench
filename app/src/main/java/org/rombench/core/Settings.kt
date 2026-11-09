package org.rombench.core

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/** User preferences (SharedPreferences, read synchronously; they are tiny). */
object Settings {
    private const val FILE = "settings"
    const val REDACT = "redact"
    const val REPORT_ENGLISH = "report_english"
    const val ALL_PROPS = "all_props"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val SPEED_URL = "speed_url"
    const val PING_HOST = "ping_host"
    const val PROBE_URL = "probe_url"

    const val DEFAULT_SPEED_URL = "https://speed.cloudflare.com/__down?bytes=25000000"
    const val DEFAULT_PING_HOST = "1.1.1.1"
    const val DEFAULT_PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"

    /** Bumped on every change so Compose readers recompose. */
    val version = mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun bool(ctx: Context, key: String, def: Boolean) = prefs(ctx).getBoolean(key, def)
    fun string(ctx: Context, key: String, def: String) = prefs(ctx).getString(key, def)?.takeIf { it.isNotBlank() } ?: def

    fun set(ctx: Context, key: String, value: Boolean) {
        prefs(ctx).edit().putBoolean(key, value).apply(); version.intValue++
    }

    fun set(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply(); version.intValue++
    }

    fun redact(ctx: Context) = bool(ctx, REDACT, true)
    fun reportInEnglish(ctx: Context) = bool(ctx, REPORT_ENGLISH, true)
    fun includeAllProps(ctx: Context) = bool(ctx, ALL_PROPS, false)
    fun dynamicColor(ctx: Context) = bool(ctx, DYNAMIC_COLOR, true)
    fun speedUrl(ctx: Context) = string(ctx, SPEED_URL, DEFAULT_SPEED_URL)
    fun pingHost(ctx: Context) = string(ctx, PING_HOST, DEFAULT_PING_HOST)
    fun probeUrl(ctx: Context) = string(ctx, PROBE_URL, DEFAULT_PROBE_URL)
}
