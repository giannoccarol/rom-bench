package org.rombench.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Localized {
    @Volatile private var cached: Context? = null

    /** A context whose resources are always English, used to render exported reports. */
    fun english(ctx: Context): Context {
        cached?.let { return it }
        val conf = Configuration(ctx.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return ctx.applicationContext.createConfigurationContext(conf).also { cached = it }
    }

    /** Context used for reports according to the user's setting. */
    fun forReport(ctx: Context): Context =
        if (Settings.reportInEnglish(ctx)) english(ctx) else ctx
}
